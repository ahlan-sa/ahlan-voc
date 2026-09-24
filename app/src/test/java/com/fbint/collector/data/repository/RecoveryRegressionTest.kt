package com.fbint.collector.data.repository

import android.app.Application
import androidx.room.Room
import androidx.work.*
import com.fbint.collector.data.local.AppDatabase
import com.fbint.collector.data.local.entity.QueuedResponseEntity
import com.fbint.collector.data.remote.FormbricksApiFactory
import com.fbint.collector.sync.ResponseSyncWorker
import com.fbint.collector.sync.SyncScheduler
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RecoveryRegressionTest {
    private fun response() = QueuedResponseEntity("audit", "survey", "env", "collector", true, null, "{}", capturedAt = 1)
    private fun repository(db: AppDatabase, client: OkHttpClient = OkHttpClient(), base: String = "https://audit.invalid"): ResponseRepository {
        val config = Mockito.mock(ConfigRepository::class.java)
        Mockito.`when`(config.baseUrl()).thenReturn(base)
        Mockito.`when`(config.apiKey()).thenReturn("test")
        val files = Mockito.mock(FileQueueRepository::class.java)
        Mockito.`when`(files.extractFilePlaceholders(Mockito.anyMap<String, Any?>())).thenReturn(emptyList())
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        return ResponseRepository(db.responseQueueDao(), FormbricksApiFactory(client, moshi), config,
            files, Mockito.mock(SurveyRepository::class.java), moshi)
    }
    @Test fun dnsFailureBeforeAnyRequestShouldNotBeTreatedAsUncertainUpload() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            db.responseQueueDao().insert(response())
            val client = OkHttpClient.Builder().dns(object : okhttp3.Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = throw UnknownHostException("deliberate DNS failure; no request sent") }).build()
            val result = repository(db, client).syncPending()
            assertEquals(1, result.failed)
            assertNotNull(db.responseQueueDao().getById("audit"))
            assertNull("DNS failure incorrectly leaves a 10-minute uncertain-upload hold", db.responseQueueDao().getById("audit")!!.sendingAt)
        } finally { db.close() }
    }
    @Test fun restoredDnsCanUploadImmediatelyWithoutLosingTheSavedPayload() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            var available = false
            val client = OkHttpClient.Builder().dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    if (!available) throw UnknownHostException("offline")
                    return listOf(java.net.InetAddress.getByName("127.0.0.1"))
                }
            }).build()
            val row = response().copy(dataJson = "{\"answer\":\"preserve me\"}")
            db.responseQueueDao().insert(row)
            val repo = repository(db, client, "http://audit.invalid:${server.port}")
            assertTrue(repo.syncPending().retry)
            assertEquals(row.dataJson, db.responseQueueDao().getById("audit")!!.dataJson)
            assertEquals(0, server.requestCount)
            available = true
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"data\":{\"id\":\"saved\"}}"))
            assertEquals(1, repo.syncPending().synced)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(request.body.readUtf8().contains("preserve me"))
            assertEquals("saved", db.responseQueueDao().getById("audit")!!.serverResponseId)
        } finally { server.shutdown(); db.close() }
    }

    @Test fun dnsFailureAfterRedirectDoesNotPermitBlindResubmission() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val client = OkHttpClient.Builder().dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    if (hostname == "missing.invalid") throw UnknownHostException("redirect DNS failed")
                    return listOf(java.net.InetAddress.getByName("127.0.0.1"))
                }
            }).build()
            db.responseQueueDao().insert(response())
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).addHeader("Location", "http://missing.invalid/accepted"))
            val repo = repository(db, client, server.url("/").toString())
            assertTrue(repo.syncPending().retry)
            assertEquals(1, server.requestCount)
            assertNotNull(db.responseQueueDao().getById("audit")!!.sendingAt)
            assertEquals(0, repo.syncPending().synced)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); db.close() }
    }

    @Test fun queuedResponseInHoldShouldKeepRetryScheduled() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            db.responseQueueDao().insert(response().copy(sendingAt = System.currentTimeMillis()))
            val result = repository(db).syncPending()
            assertEquals(0, result.synced)
            assertTrue("Worker reports success/no retry even though an unsynced response is only temporarily held", result.retry)
        } finally { db.close() }
    }
    @Test fun manualSyncShouldNotWaitBehindDelayedWork() {
        val context = RuntimeEnvironment.getApplication()
        WorkManager.initialize(context, Configuration.Builder().build())
        val wm = WorkManager.getInstance(context)
        val delayed = OneTimeWorkRequestBuilder<ResponseSyncWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        wm.enqueueUniqueWork(ResponseSyncWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, delayed).result.get(10, TimeUnit.SECONDS)
        SyncScheduler(context).requestManualSync()
        // WorkManager queues database operations serially; waiting for the following enqueue
        // establishes that the manual-sync enqueues have completed before inspecting them.
        wm.enqueueUniqueWork("audit-barrier", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ResponseSyncWorker>().setInitialDelay(1, TimeUnit.HOURS).build()).result.get(10, TimeUnit.SECONDS)
        val infos = wm.getWorkInfosForUniqueWork(com.fbint.collector.sync.ManualSyncWorker.UNIQUE_NAME).get(10, TimeUnit.SECONDS)
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfoById(delayed.id).get(10, TimeUnit.SECONDS)!!.state)
        assertFalse("Manual Sync now is BLOCKED behind the delayed retry", infos.any { it.state == WorkInfo.State.BLOCKED })
    }
}
