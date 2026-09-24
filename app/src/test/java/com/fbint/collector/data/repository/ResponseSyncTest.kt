package com.fbint.collector.data.repository

import android.app.Application
import androidx.room.Room
import com.fbint.collector.data.local.AppDatabase
import com.fbint.collector.data.local.ResponseQueueDao
import com.fbint.collector.data.local.entity.QueuedResponseEntity
import com.fbint.collector.data.remote.FormbricksApiFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ResponseSyncTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ResponseQueueDao
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .build()
        dao = db.responseQueueDao()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun reconnectWithTwoWorkersUploadsEachQueuedResponseOnce() = runBlocking {
        withTimeout(30_000) {
            val count = 20
            repeat(count) { dao.insert(response("offline-$it")) }
            // Leave enough replies for the broken implementation to finish too.
            repeat(count * 2) {
                server.enqueue(MockResponse().setBody("""{"data":{"id":"server-$it"}}"""))
            }

            // Force both workers to read the entire offline backlog before either sends.
            val readers = AtomicInteger()
            val bothRead = CompletableDeferred<Unit>()
            val snapshotDao = object : ResponseQueueDao by dao {
                override suspend fun pendingOnce(staleBefore: Long): List<QueuedResponseEntity> {
                    val rows = dao.pendingOnce(staleBefore)
                    if (readers.incrementAndGet() == 2) bothRead.complete(Unit)
                    bothRead.await()
                    return rows
                }
            }
            val repo = repository(snapshotDao)
            val outcomes = List(2) { async(Dispatchers.IO) { repo.syncPending() } }.awaitAll()

            assertEquals(count, server.requestCount)
            assertEquals(count, outcomes.sumOf { it.synced })
            assertEquals(0, outcomes.sumOf { it.failed })
            assertTrue(dao.pendingOnce(Long.MAX_VALUE).isEmpty())
            val payloads = List(count) { server.takeRequest().body.readUtf8() }
            repeat(count) { index ->
                assertEquals(1, payloads.count { it.contains("\"source\":\"fbint:offline-$index\"") })
            }
        }
    }

    @Test
    fun onlyOneConcurrentWorkerCanClaimAResponse() = runBlocking {
        dao.insert(response("pending"))
        val claims = List(10) {
            async(Dispatchers.IO) { dao.claimForSending("pending", 1_000, 0) }
        }.awaitAll()
        assertEquals(1, claims.sum())
    }

    @Test
    fun staleSnapshotCannotClaimAnAlreadySyncedResponse() = runBlocking {
        dao.insert(response("done"))
        val snapshot = dao.pendingOnce(0).single()
        assertEquals(1, dao.claimForSending(snapshot.clientUuid, 1_000, 0))
        dao.markSynced(snapshot.clientUuid, 1_001, "remote-id")
        assertEquals(0, dao.claimForSending(snapshot.clientUuid, 2_000, 1_500))
    }

    @Test
    fun ambiguousUploadIsHeldUntilItsLeaseExpires() = runBlocking {
        dao.insert(response("uncertain"))
        assertEquals(1, dao.claimForSending("uncertain", 1_000, 0))
        dao.markFailure("uncertain", "Connection lost after sending")
        assertEquals(0, dao.claimForSending("uncertain", 2_000, 1_000))
        assertEquals(1, dao.claimForSending("uncertain", 3_000, 1_001))
    }

    @Test
    fun rejectedUploadCanBeRetriedAfterClaimIsCleared() = runBlocking {
        dao.insert(response("rejected"))
        assertEquals(1, dao.claimForSending("rejected", 1_000, 0))
        dao.clearSending("rejected")
        assertEquals(1, dao.claimForSending("rejected", 2_000, 0))
    }

    @Test
    fun validationRejectionShowsFieldDetailsAndPreservesSavedAnswersForRetry() = runBlocking {
        val original = response("invalid")
        dao.insert(original)
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"code":"bad_request","message":"Validation failed","details":{"response.data.adm03_e":"Invalid date"}}"""
        ))
        val repo = repository(dao)
        val outcome = repo.syncPending()
        assertEquals(1, outcome.failed)
        val saved = dao.getById("invalid")!!
        assertEquals(original.dataJson, saved.dataJson)
        assertNull(saved.syncedAt)
        assertNull(saved.sendingAt)
        assertTrue(saved.lastError!!.contains("response.data.adm03_e: Invalid date"))
        server.enqueue(MockResponse().setBody("""{"data":{"id":"recovered"}}"""))
        assertEquals(1, repo.syncPending().synced)
        assertEquals("recovered", dao.getById("invalid")!!.serverResponseId)
    }

    @Test
    fun serverErrorDoesNotImmediatelyResendAnUncertainResponse() = runBlocking {
        dao.insert(response("server-error"))
        server.enqueue(MockResponse().setResponseCode(503))
        val repo = repository(dao)
        val first = repo.syncPending()
        assertTrue(first.retry)
        assertEquals(1, first.failed)
        assertEquals(0, repo.syncPending().synced)
        assertEquals(1, server.requestCount)
        assertEquals(1, dao.pendingOnce(Long.MAX_VALUE).size)
    }

    @Test
    fun cancellationAfterServerSuccessStillRecordsTheResponseAsSynced() = runBlocking {
        withTimeout(30_000) {
            dao.insert(response("cancelled-after-success"))
            server.enqueue(MockResponse().setBody("""{"data":{"id":"accepted"}}"""))
            val receivedSuccess = CompletableDeferred<Unit>()
            val allowDatabaseWrite = CompletableDeferred<Unit>()
            val delayedDao = object : ResponseQueueDao by dao {
                override suspend fun markSynced(id: String, ts: Long, serverId: String) {
                    receivedSuccess.complete(Unit)
                    allowDatabaseWrite.await()
                    dao.markSynced(id, ts, serverId)
                }
            }
            val worker = async(Dispatchers.IO) { repository(delayedDao).syncPending() }
            try {
                receivedSuccess.await()
                worker.cancel()
                allowDatabaseWrite.complete(Unit)
                worker.join()
                assertEquals("accepted", dao.recent(1).first().single().serverResponseId)
                assertTrue(dao.pendingOnce(Long.MAX_VALUE).isEmpty())
                assertEquals(0, repository(dao).syncPending().synced)
                assertEquals(1, server.requestCount)
            } finally {
                allowDatabaseWrite.complete(Unit)
                worker.cancelAndJoin()
            }
        }
    }

    @Test
    fun uncertainAcknowledgementFindsExistingResponseWithoutPostingAgain() = runBlocking {
        dao.insert(response("uncertain").copy(sendingAt = 1))
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"already-saved","meta":{"source":"fbint:uncertain"}}]}"""))
        assertEquals(1, repository(dao).syncPending().synced)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, server.requestCount)
        assertEquals("already-saved", dao.getById("uncertain")!!.serverResponseId)
    }

    @Test
    fun captureIdentifierPreventsDraftRecoveryFromDuplicatingQueuedResponse() = runBlocking {
        val repo = repository(dao)
        repo.enqueue("survey", "env", mapOf("answer" to "original"), true, null, clientUuid = "stable")
        dao.markSynced("stable", 1, "server")
        repo.enqueue("survey", "env", mapOf("answer" to "changed"), true, null, clientUuid = "stable")
        assertEquals("server", dao.getById("stable")!!.serverResponseId)
        assertTrue(dao.getById("stable")!!.dataJson.contains("original"))
        assertEquals(1, dao.recent(10).first().size)
    }

    @Test
    fun savedServerAddressSurvivesADeviceConfigurationChange() = runBlocking {
        val origin = MockWebServer()
        origin.start()
        try {
            dao.insert(response("bound").copy(serverBaseUrl = origin.url("/").toString()))
            origin.enqueue(MockResponse().setBody("""{"data":{"id":"original-server"}}"""))
            assertEquals(1, repository(dao).syncPending().synced)
            assertEquals(1, origin.requestCount)
            assertEquals(0, server.requestCount)
        } finally { origin.shutdown() }
    }

    @Test
    fun failedReconciliationDoesNotBlindlyPostAgain() = runBlocking {
        dao.insert(response("uncertain").copy(sendingAt = 1))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(0, repository(dao).syncPending().synced)
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, server.requestCount)
        assertNull(dao.getById("uncertain")!!.syncedAt)
        assertNotNull(dao.getById("uncertain")!!.sendingAt)
    }

    private fun repository(queue: ResponseQueueDao): ResponseRepository {
        val config = Mockito.mock(ConfigRepository::class.java)
        Mockito.`when`(config.apiKey()).thenReturn("test-key")
        Mockito.`when`(config.baseUrl()).thenReturn(server.url("/").toString())
        val files = Mockito.mock(FileQueueRepository::class.java)
        Mockito.`when`(files.extractFilePlaceholders(Mockito.anyMap<String, Any?>()))
            .thenReturn(emptyList())
        val surveys = Mockito.mock(SurveyRepository::class.java)
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        return ResponseRepository(queue, FormbricksApiFactory(OkHttpClient(), moshi), config, files, surveys, moshi)
    }

    private fun response(id: String) = QueuedResponseEntity(
        clientUuid = id,
        surveyId = "survey",
        environmentId = "environment",
        surveyorId = "surveyor",
        finished = true,
        language = null,
        dataJson = """{"question":"answer"}""",
        capturedAt = 1,
    )
}
