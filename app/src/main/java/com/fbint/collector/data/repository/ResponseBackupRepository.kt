package com.fbint.collector.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.fbint.collector.data.local.AppDatabase
import com.fbint.collector.data.local.entity.*
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

internal data class ResponseBackup(
    val format: String = "ahlan-response-backup-v1",
    val responses: List<QueuedResponseEntity>,
    val files: List<BackupFile>,
    val surveys: List<SurveyEntity>,
)
internal data class BackupFile(val record: QueuedFileEntity, val entry: String?, val sha256: String? = null)

class ResponseBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val config: ConfigRepository,
    private val files: FileQueueRepository,
    moshi: Moshi,
) {
    private val adapter = moshi.adapter(ResponseBackup::class.java)
    private val answers = moshi.adapter(Any::class.java)

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val archive = File.createTempFile("response-backup-", ".zip", context.cacheDir)
        try {
            val rows = db.responseQueueDao().exportAll().map {
                it.copy(serverBaseUrl = it.serverBaseUrl ?: config.legacyQueueServer() ?: config.baseUrl())
            }
            require(rows.isNotEmpty()) { "No submitted responses to export yet." }
            val surveys = rows.map { it.surveyId }.distinct().mapNotNull { db.surveyDao().getById(it) }
            val ids = rows.flatMap { fileIds(it) }.distinct()
            val attachments = mutableListOf<BackupFile>()
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (id in ids) {
                    val original = requireNotNull(db.queuedFileDao().getById(id)) { "Attachment record is missing: $id" }
                    val input = runCatching { File(original.localPath).inputStream() }.getOrNull()
                    val entry = if (input != null) "files/${attachments.size}" else null
                    val record = if (input == null) db.queuedFileDao().getById(id) ?: original else original
                    require(input != null || !record.uploadedFileUrl.isNullOrBlank()) {
                        "Attachment $id is missing. Backup was not completed."
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    input?.use {
                        zip.putNextEntry(ZipEntry(entry!!))
                        java.security.DigestInputStream(it, digest).copyTo(zip)
                        zip.closeEntry()
                    }
                    attachments.add(BackupFile(record.copy(localPath = "", uploadingAt = null,
                        serverBaseUrl = record.serverBaseUrl ?: config.legacyQueueServer() ?: config.baseUrl()), entry, if (entry != null) digest.digest().joinToString("") { "%02x".format(it) } else null))
                }
                zip.putNextEntry(ZipEntry("responses.json"))
                zip.write(adapter.toJson(ResponseBackup(responses = rows, files = attachments, surveys = surveys)).toByteArray())
                zip.closeEntry()
            }
            require(archive.length() <= MAX_BACKUP_BYTES) { "Backup is larger than the supported 2 GB limit" }
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> archive.inputStream().use { it.copyTo(out) } }
                ?: error("Cannot write the selected backup file")
            rows.size
        } finally { archive.delete() }
    }

    /** Never overwrites existing rows. Every restored pending response is reconciled before POST. */
    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val archive = File.createTempFile("response-restore-", ".zip", context.cacheDir)
        val copied = mutableListOf<File>()
        var committed = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                archive.outputStream().use { copyBounded(input, it, MAX_BACKUP_BYTES) }
            } ?: error("Cannot open backup")
            ZipFile(archive).use { zip ->
                val manifest = requireNotNull(zip.getEntry("responses.json")) { "Not an Ahlan response backup" }
                val json = java.io.ByteArrayOutputStream().also { out ->
                    zip.getInputStream(manifest).use { copyBounded(it, out, 16L * 1024 * 1024) }
                }.toString("UTF-8")
                val backup = requireNotNull(adapter.fromJson(json)) { "Invalid backup" }
                require(backup.format == "ahlan-response-backup-v1") { "Unsupported backup version" }
                require(backup.responses.size <= 10_000 && backup.files.size <= 50_000) { "Backup exceeds recovery limits" }
                require(backup.responses.map { it.clientUuid }.distinct().size == backup.responses.size) { "Duplicate response IDs in backup" }
                require(backup.files.map { it.record.clientUuid }.distinct().size == backup.files.size) { "Duplicate attachment IDs in backup" }
                val origin = config.baseUrl()?.trimEnd('/') ?: error("Connect to the original Formbricks server first")
                require(backup.responses.all { it.serverBaseUrl?.trimEnd('/') == origin } &&
                    backup.files.all { it.record.serverBaseUrl?.trimEnd('/') == origin }) {
                    "This backup belongs to another server. Connect to its original server first."
                }
                val rows = backup.responses.filter { db.responseQueueDao().getById(it.clientUuid) == null }
                val needed = rows.flatMap { fileIds(it) }.toSet()
                require(backup.files.map { it.record.clientUuid }.containsAll(needed)) { "Backup is missing attachment records" }
                val byId = backup.files.associateBy { it.record.clientUuid }
                rows.forEach { row -> fileIds(row).forEach { id ->
                    val file = byId.getValue(id).record
                    require(file.boundResponseUuid == row.clientUuid && file.surveyId == row.surveyId && file.environmentId == row.environmentId) { "Attachment does not belong to this response" }
                } }
                val restored = mutableListOf<QueuedFileEntity>()
                var total = 0L
                for (file in backup.files.filter { it.record.clientUuid in needed }) {
                    val record = file.record
                    require(db.queuedFileDao().getById(record.clientUuid) == null) { "Attachment already exists; restore on a device without a conflicting draft" }
                    val local = File(context.filesDir, "fbint-uploads/restore-${UUID.randomUUID()}")
                    if (file.entry != null) {
                        require(file.entry.matches(Regex("files/[0-9]+"))) { "Invalid attachment path" }
                        val entry = requireNotNull(zip.getEntry(file.entry)) { "Missing attachment in backup" }
                        local.parentFile?.mkdirs()
                        copied.add(local)
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { input ->
                            local.outputStream().use { total += copyBounded(java.security.DigestInputStream(input, digest), it, MAX_BACKUP_BYTES - total) }
                        }
                        require(file.sha256 != null && digest.digest().joinToString("") { "%02x".format(it) } == file.sha256) { "Attachment checksum does not match backup" }
                        require(local.length() == record.sizeBytes) { "Attachment size does not match backup" }
                    } else require(!record.uploadedFileUrl.isNullOrBlank()) { "Backup has neither attachment nor uploaded URL" }
                    restored.add(record.copy(localPath = local.absolutePath, uploadingAt = null))
                }
                db.withTransaction {
                    restored.forEach { db.queuedFileDao().restore(it) }
                    backup.surveys.filter { s -> rows.any { it.surveyId == s.id } }.forEach {
                        if (db.surveyDao().getById(it.id) == null) db.surveyDao().upsert(it)
                    }
                    rows.forEach { db.responseQueueDao().insert(it.copy(sendingAt = if (it.syncedAt == null) 1L else null)) }
                }
                committed = true
                rows.size
            }
        } finally {
            archive.delete()
            if (!committed) copied.forEach { it.delete() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fileIds(row: QueuedResponseEntity): List<String> =
        files.extractFilePlaceholders(answers.fromJson(row.dataJson) as? Map<String, Any?> ?: error("Invalid response data"))

    private fun copyBounded(input: java.io.InputStream, out: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            count += size
            require(count <= limit) { "Backup exceeds the 2 GB recovery limit" }
            out.write(buffer, 0, size)
        }
        return count
    }

    companion object { private const val MAX_BACKUP_BYTES = 2L * 1024 * 1024 * 1024 }
}
