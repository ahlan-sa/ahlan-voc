package com.fbint.collector.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.fbint.collector.data.local.QueuedFileDao
import com.fbint.collector.data.local.entity.QueuedFileEntity
import com.fbint.collector.data.remote.FormbricksClientApi
import com.fbint.collector.data.remote.dto.UploadFileRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

const val FILE_PLACEHOLDER_PREFIX = "fbint-file:"

/**
 * Persists picked/captured files to app private storage and runs the upload pipeline.
 *
 * Uploads use the Formbricks public client storage endpoint to obtain a signed URL, then
 * deliver bytes to that URL. Three storage backends are handled:
 *  - presigned S3 POST (multipart with `presignedFields`)
 *  - presigned S3 PUT (plain PUT with Content-Type)
 *  - self-hosted local PUT (PUT with `signingData` echoed in headers)
 */
@Singleton
class FileQueueRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: QueuedFileDao,
    private val factory: com.fbint.collector.data.remote.FormbricksApiFactory,
    private val config: ConfigRepository,
    private val client: OkHttpClient,
) {
    fun pendingCount(): Flow<Int> = dao.pendingCount()
    fun uploadedCount(): Flow<Int> = dao.uploadedCount()

    /**
     * Copy the picked URI into our private storage and queue it. Returns the placeholder string
     * the runner should embed in the question's answer (e.g. `fbint-file:abc-123`).
     */
    suspend fun ingestPickedFile(
        sourceUri: Uri,
        surveyId: String,
        questionId: String,
        environmentId: String,
        suggestedName: String?,
    ): String = withContext(Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val mime = resolver.getType(sourceUri) ?: "application/octet-stream"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: suggestedName?.substringAfterLast('.', "")
            ?: ""
        val uuid = UUID.randomUUID().toString()
        val finalName = (suggestedName?.takeIf { it.isNotBlank() } ?: "file-$uuid")
            .let { name -> if (name.contains('.') || ext.isBlank()) name else "$name.$ext" }
        val targetDir = File(ctx.filesDir, "fbint-uploads").apply { mkdirs() }
        val targetFile = File(targetDir, "$uuid-$finalName")
        resolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read picked file")

        dao.insert(
            QueuedFileEntity(
                clientUuid = uuid,
                serverBaseUrl = config.baseUrl(),
                surveyId = surveyId,
                questionId = questionId,
                environmentId = environmentId,
                localPath = targetFile.absolutePath,
                fileName = finalName,
                mimeType = mime,
                sizeBytes = targetFile.length(),
                capturedAt = System.currentTimeMillis(),
            )
        )
        "$FILE_PLACEHOLDER_PREFIX$uuid"
    }

    suspend fun discardUnboundFiles(surveyId: String) = withContext(Dispatchers.IO) {
        val origin = config.baseUrl() ?: return@withContext
        dao.unboundForSurvey(surveyId, origin).forEach { file ->
            if (!File(file.localPath).exists() || File(file.localPath).delete()) dao.delete(file.clientUuid)
        }
    }

    suspend fun bindFilesToResponse(fileIds: List<String>, responseUuid: String) {
        if (fileIds.isEmpty()) return
        dao.bindToResponse(fileIds, responseUuid)
    }

    suspend fun getByIds(fileIds: List<String>): List<QueuedFileEntity> = dao.getByIds(fileIds)

    /** Upload every queued file. Returns whether at least one upload should be retried later. */
    suspend fun uploadPending(): UploadOutcome {
        val pending = dao.pendingOnce()
        if (pending.isEmpty()) return UploadOutcome(0, 0, false)
        var done = 0
        var failed = 0
        var retry = false
        for (item in pending) {
            val now = System.currentTimeMillis()
            if (dao.claimUpload(item.clientUuid, now, now - 10 * 60_000L) != 1) {
                retry = true
                continue
            }
            try {
                uploadOne(item)
                done++
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failed++
                dao.markFailure(item.clientUuid, (t.message ?: t.javaClass.simpleName).take(500))
                if (!isFatal(t)) retry = true
            } finally {
                dao.releaseUpload(item.clientUuid)
            }
        }
        return UploadOutcome(done, failed, retry)
    }

    private suspend fun uploadOne(item: QueuedFileEntity) = withContext(Dispatchers.IO) {
        val ext = item.fileName.substringAfterLast('.', "").lowercase()
        val req = UploadFileRequest(
            fileName = item.fileName,
            fileType = item.mimeType,
            surveyId = item.surveyId,
            elementId = item.questionId,
            allowedFileExtensions = ext.takeIf { it.isNotBlank() }?.let { listOf(it) },
        )
        val origin = item.serverBaseUrl ?: config.legacyQueueServer() ?: config.baseUrl()
            ?: error("Original upload server is unavailable")
        val signed = factory.client { origin }.requestUploadUrl(item.environmentId, req).data
        val file = File(item.localPath)
        if (!file.exists()) error("Local file missing: ${item.localPath}")
        val media = item.mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()

        val builder = Request.Builder().url(signed.signedUrl)
        when {
            signed.presignedFields != null -> {
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                signed.presignedFields.forEach { (k, v) -> multipart.addFormDataPart(k, v) }
                multipart.addFormDataPart("file", item.fileName, file.asRequestBody(media))
                builder.post(multipart.build())
            }
            signed.signingData != null -> {
                builder
                    .addHeader("X-File-Signature", signed.signingData.signature)
                    .addHeader("X-Timestamp", signed.signingData.timestamp.toString())
                    .addHeader("X-UUID", signed.signingData.uuid)
                    .put(file.asRequestBody(media))
            }
            else -> {
                builder.addHeader("Content-Type", item.mimeType).put(file.asRequestBody(media))
            }
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Upload HTTP ${resp.code}: ${resp.message}")
        }

        dao.markUploaded(item.clientUuid, signed.fileUrl, System.currentTimeMillis())
    }

    /**
     * After the response has been successfully POSTed, the on-disk file is no longer needed.
     * Removing the row would lose the audit trail but the bytes can go.
     */
    suspend fun purgeUploadedFile(clientUuid: String) {
        val item = dao.getById(clientUuid) ?: return
        runCatching { File(item.localPath).delete() }
    }

    private fun isFatal(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        return Regex("HTTP 4[0-9]{2}").containsMatchIn(msg) && !msg.contains("HTTP 429")
    }

    data class UploadOutcome(val done: Int, val failed: Int, val retry: Boolean)

    /** Pull placeholders out of a heterogeneous answers map. */
    fun extractFilePlaceholders(answers: Map<String, Any?>): List<String> = answers.values
        .flatMap { v ->
            when (v) {
                is List<*> -> v.mapNotNull { it as? String }.filter { it.startsWith(FILE_PLACEHOLDER_PREFIX) }
                is String -> if (v.startsWith(FILE_PLACEHOLDER_PREFIX)) listOf(v) else emptyList()
                else -> emptyList()
            }
        }
        .map { it.removePrefix(FILE_PLACEHOLDER_PREFIX) }
}
