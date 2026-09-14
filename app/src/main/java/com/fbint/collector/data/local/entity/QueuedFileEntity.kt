package com.fbint.collector.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A file selected for a `fileUpload` question, queued for offline-first upload.
 *
 * Lifecycle:
 *  1. User picks/captures a file → row inserted with `localPath` (private app storage),
 *     `boundResponseUuid = null`. The runner stores `clientUuid` as a placeholder in the
 *     question's answer (`fbint-file:<clientUuid>`).
 *  2. User submits the response → all referenced files get `boundResponseUuid` set.
 *  3. FileUploadWorker uploads each file (request signed URL via /storage, then PUT bytes)
 *     and writes back `uploadedFileUrl` + `uploadedAt`.
 *  4. ResponseSyncWorker only POSTs a response once every bound file has `uploadedFileUrl`.
 *     It substitutes placeholders with the URL and deletes the local file after success.
 */
@Entity(tableName = "queued_files")
data class QueuedFileEntity(
    @PrimaryKey val clientUuid: String,
    val surveyId: String,
    val questionId: String,
    val environmentId: String,
    val localPath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val capturedAt: Long,
    val boundResponseUuid: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val uploadedFileUrl: String? = null,
    val uploadedAt: Long? = null,
    val serverBaseUrl: String? = null,
    val uploadingAt: Long? = null,
)
