package com.fbint.collector.data.repository

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.fbint.collector.data.local.AppDatabase
import com.fbint.collector.data.local.entity.QueuedResponseEntity
import com.fbint.collector.data.remote.FormbricksApiFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ResponseBackupTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val config = mock(ConfigRepository::class.java).also {
        `when`(it.baseUrl()).thenReturn("https://example.test")
        `when`(it.apiKey()).thenReturn("secret-not-for-backup")
    }
    private fun database() = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
    private fun files(db: AppDatabase) = FileQueueRepository(ctx, db.queuedFileDao(), FormbricksApiFactory(OkHttpClient(), moshi), config, OkHttpClient())
    private fun backups(db: AppDatabase) = ResponseBackupRepository(ctx, db, config, files(db), moshi)

    @Test fun eightPhotosSurviveExportRestoreAndRepeatedRestoreDoesNotDuplicate() = runBlocking {
        val source = database(); val target = database()
        val archive = File(ctx.cacheDir, "eight-photo-backup.zip")
        try {
            val files = files(source)
            val placeholders = (1..8).map { index ->
                val photo = File(ctx.cacheDir, "image-$index.jpg").apply { writeBytes(ByteArray(index * 100) { index.toByte() }) }
                files.ingestPickedFile(Uri.fromFile(photo), "survey", "photos", "env", photo.name, 1, listOf("jpg"))
            }
            files.bindFilesToResponse(placeholders.map { it.removePrefix(FILE_PLACEHOLDER_PREFIX) }, "response")
            val json = moshi.adapter(Any::class.java).toJson(mapOf("photos" to placeholders, "answer" to "keep me"))
            source.responseQueueDao().insert(QueuedResponseEntity("response", "survey", "env", "original surveyor", true, "ar-SA", json,
                capturedAt = 123, serverBaseUrl = "https://example.test", autoStampsJson = """{"location_lat":"24.7","time_to_complete_seconds":"120"}"""))
            assertEquals(1, backups(source).export(Uri.fromFile(archive)))
            ZipFile(archive).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("responses.json")).bufferedReader().readText()
                assertFalse(manifest.contains("secret-not-for-backup"))
                assertFalse(manifest.contains(ctx.filesDir.absolutePath))
                assertEquals(9, zip.size())
            }
            val damaged = File(ctx.cacheDir, "damaged-backup.zip")
            ZipFile(archive).use { input ->
                java.util.zip.ZipOutputStream(damaged.outputStream()).use { output ->
                    input.entries().asSequence().filter { it.name != "files/7" }.forEach { entry ->
                        output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        input.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            val beforeFailedRestore = File(ctx.filesDir, "fbint-uploads").listFiles().orEmpty().map { it.name }.toSet()
            assertTrue(runCatching { backups(target).restore(Uri.fromFile(damaged)) }.isFailure)
            assertEquals(0, target.responseQueueDao().exportAll().size)
            assertEquals(beforeFailedRestore, File(ctx.filesDir, "fbint-uploads").listFiles().orEmpty().map { it.name }.toSet())
            damaged.delete()
            assertEquals(1, backups(target).restore(Uri.fromFile(archive)))
            assertEquals(0, backups(target).restore(Uri.fromFile(archive)))
            val restored = target.responseQueueDao().getById("response")!!
            assertEquals(json, restored.dataJson)
            assertEquals("original surveyor", restored.surveyorId)
            assertEquals(1L, restored.sendingAt)
            assertEquals(123L, restored.capturedAt)
            placeholders.forEachIndexed { index, placeholder ->
                val record = target.queuedFileDao().getById(placeholder.removePrefix(FILE_PLACEHOLDER_PREFIX))!!
                assertArrayEquals(ByteArray((index + 1) * 100) { (index + 1).toByte() }, File(record.localPath).readBytes())
                assertEquals("response", record.boundResponseUuid)
            }
            `when`(config.baseUrl()).thenReturn("https://another.test")
            assertTrue(runCatching { backups(target).restore(Uri.fromFile(archive)) }.isFailure)
            assertEquals(1, target.responseQueueDao().exportAll().size)
        } finally { source.close(); target.close(); archive.delete() }
    }

    @Test fun eightQueuedImagesUploadSequentiallyWithoutACountLimit() = runBlocking {
        val db = database()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            `when`(config.baseUrl()).thenReturn(server.url("/").toString())
            val repository = files(db)
            val placeholders = (1..8).map { index ->
                val file = File(ctx.cacheDir, "upload-$index.jpg").apply { writeBytes(ByteArray(100) { index.toByte() }) }
                val placeholder = repository.ingestPickedFile(Uri.fromFile(file), "survey", "photos", "env", file.name)
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                    """{"data":{"signedUrl":"${server.url("/upload/$index")}","fileUrl":"${server.url("/storage/$index.jpg")}"}}"""))
                server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200))
                placeholder
            }
            repository.bindFilesToResponse(placeholders.map { it.removePrefix(FILE_PLACEHOLDER_PREFIX) }, "response")
            val outcome = repository.uploadPending()
            assertEquals(8, outcome.done)
            assertEquals(0, outcome.failed)
            assertEquals(16, server.requestCount)
            repeat(8) {
                assertEquals("POST", server.takeRequest().method)
                assertEquals("PUT", server.takeRequest().method)
            }
        } finally { server.shutdown(); db.close() }
    }

    @Test fun oversizedAndUnsupportedFilesFailWithoutLeavingPartialCopies() = runBlocking {
        val db = database()
        try {
            val photo = File(ctx.cacheDir, "oversize.jpg").apply { writeBytes(ByteArray(1024 * 1024 + 1)) }
            val before = File(ctx.filesDir, "fbint-uploads").listFiles().orEmpty().map { it.name }.toSet()
            assertTrue(runCatching { files(db).ingestPickedFile(Uri.fromFile(photo), "s", "q", "e", photo.name, 1, listOf("jpg")) }.isFailure)
            assertTrue(runCatching { files(db).ingestPickedFile(Uri.fromFile(photo), "s", "q", "e", "wrong.exe", 2, listOf("jpg")) }.isFailure)
            assertEquals(before, File(ctx.filesDir, "fbint-uploads").listFiles().orEmpty().map { it.name }.toSet())
        } finally { db.close() }
    }
}
