package com.fbint.collector.data.repository

import com.fbint.collector.data.local.ResponseQueueDao
import com.fbint.collector.data.local.entity.QueuedResponseEntity
import com.fbint.collector.data.remote.FormbricksClientApi
import com.fbint.collector.data.remote.syncErrorMessage
import com.fbint.collector.data.remote.dto.CreateResponseRequest
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResponseRepository @Inject constructor(
    private val dao: ResponseQueueDao,
    private val factory: com.fbint.collector.data.remote.FormbricksApiFactory,
    private val config: ConfigRepository,
    private val files: FileQueueRepository,
    private val surveyRepo: SurveyRepository,
    moshi: Moshi,
) {
    private val mapAdapter: JsonAdapter<Map<String, Any?>> =
        moshi.adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
    private val variableMapAdapter: JsonAdapter<Map<String, Any?>> = mapAdapter
    private val hiddenFieldsAdapter: JsonAdapter<Map<String, Any?>> = mapAdapter

    fun pendingCount(): Flow<Int> = dao.pendingCount()
    fun syncedCount(): Flow<Int> = dao.syncedCount()
    fun strugglingCount(): Flow<Int> = dao.strugglingCount(STRUGGLE_THRESHOLD)
    fun recent(limit: Int = 50): Flow<List<QueuedResponseEntity>> = dao.recent(limit)

    /**
     * Capture a response into the local queue. Auto-stamp candidates ([autoStampCandidates])
     * are filtered against the survey's declared hidden field IDs ([allowedHiddenFieldIds]) —
     * keys not present in that whitelist are silently dropped, so a candidate like
     * `time_to_complete_seconds` is sent only when the admin actually added that hidden field
     * in Formbricks. User-entered hidden fields take precedence over auto-stamps.
     */
    suspend fun enqueue(
        surveyId: String,
        environmentId: String,
        data: Map<String, Any?>,
        finished: Boolean,
        language: String?,
        variables: Map<String, Any?> = emptyMap(),
        hiddenFields: Map<String, Any?> = emptyMap(),
        autoStampCandidates: Map<String, String> = emptyMap(),
        allowedHiddenFieldIds: Set<String> = emptySet(),
        clientUuid: String = UUID.randomUUID().toString(),
        surveyorId: String? = config.surveyorId(),
    ): String {
        val uuid = clientUuid
        if (dao.getById(uuid) != null) return uuid
        val placeholderIds = files.extractFilePlaceholders(data)
        if (placeholderIds.isNotEmpty()) files.bindFilesToResponse(placeholderIds, uuid)
        // Store auto-stamps UNFILTERED. Sync filters against the survey's current cached
        // fieldIds, so a stale-at-capture cache doesn't permanently drop these values —
        // they survive until the survey definition catches up.
        val nonBlankAutoStamps = autoStampCandidates.filterValues { it.isNotBlank() }
        dao.insert(
            QueuedResponseEntity(
                clientUuid = uuid,
                serverBaseUrl = config.baseUrl(),
                allowedHiddenFieldsJson = mapAdapter.toJson(allowedHiddenFieldIds.associateWith { true }),
                surveyId = surveyId,
                environmentId = environmentId,
                surveyorId = surveyorId,
                finished = finished,
                language = language,
                dataJson = mapAdapter.toJson(data),
                variablesJson = variableMapAdapter.toJson(variables),
                hiddenFieldsJson = hiddenFieldsAdapter.toJson(hiddenFields),
                autoStampsJson = mapAdapter.toJson(nonBlankAutoStamps),
                capturedAt = System.currentTimeMillis(),
            )
        )
        return uuid
    }

    /**
     * Sync everything currently pending. Skips responses whose bound files haven't all
     * uploaded yet (those will succeed on a later run after FileUploadWorker completes).
     *
     * Duplicate avoidance: pendingOnce is only a snapshot. Before each POST, an atomic
     * claim rechecks that the row is still unsynced and outside [SENDING_STALE_MS]. Only
     * the worker that wins that claim sends it, even when workers read the same snapshot.
     * On 4xx (request rejected, no row created)
     * we clear `sendingAt` so the row can be retried on the next worker run; on
     * network/5xx/429 we leave it set so the row stays in-flight until the stale window
     * expires — Formbricks may have processed the request even though we got an error.
     */
    suspend fun syncPending(): SyncOutcome {
        val now = System.currentTimeMillis()
        val pending = dao.pendingOnce(staleBefore = now - SENDING_STALE_MS)
        if (pending.isEmpty()) return SyncOutcome(0, 0, dao.pendingCount().first() > 0)
        var synced = 0
        var failed = 0
        var retry = false

        for (item in pending) {
            val rawData: Map<String, Any?>
            val resolvedFiles: Map<String, com.fbint.collector.data.local.entity.QueuedFileEntity>
            val req: CreateResponseRequest
            try {
                if (item.sendingAt != null || item.attempts > 0) {
                    val existing = reconcile(item)
                    if (existing != null) {
                        dao.markSynced(item.clientUuid, System.currentTimeMillis(), existing)
                        synced++
                        continue
                    }
                }
                rawData = mapAdapter.fromJson(item.dataJson) ?: emptyMap()
                val placeholderIds = files.extractFilePlaceholders(rawData)
                resolvedFiles = if (placeholderIds.isEmpty()) emptyMap()
                    else files.getByIds(placeholderIds).associateBy { it.clientUuid }

                if (placeholderIds.any { id -> resolvedFiles[id]?.uploadedFileUrl.isNullOrBlank() }) {
                    // Surface attachment failures instead of leaving the response silently waiting.
                    val fileError = placeholderIds.firstNotNullOfOrNull { id -> resolvedFiles[id]?.lastError }
                    if (fileError != null) dao.markFailure(item.clientUuid, "Attachment upload: $fileError".take(1200))
                    retry = true
                    continue
                }

                val finalData = rawData.mapValues { (_, v) -> substitutePlaceholders(v, resolvedFiles) }
                val variables = variableMapAdapter.fromJson(item.variablesJson.orEmpty().ifBlank { "{}" }) ?: emptyMap()
                val hidden = hiddenFieldsAdapter.fromJson(item.hiddenFieldsJson.orEmpty().ifBlank { "{}" }) ?: emptyMap()

                // Sanitize the magic "default" language at sync time too — old responses
                // queued before the language fix shipped still carry it and the server 400s.
                val sanitizedLang = sanitizeLanguageForServer(item.language, item.surveyId)
                // Filter auto-stamps against the survey's CURRENT cached fieldIds (may have
                // grown since capture). Then merge into data map — public API ignores the
                // top-level `hiddenFields` field, values must ride in `data` keyed by field
                // name. Question-answer keys are CUIDs, hidden field names are admin-chosen
                // strings, so no collision; explicit answers win over hidden if any clash.
                val autoStamps: Map<String, Any?> = mapAdapter.fromJson(item.autoStampsJson.orEmpty().ifBlank { "{}" }) ?: emptyMap()
                val survey = surveyRepo.loadFromCache(item.surveyId)
                val declaredAtCapture = mapAdapter.fromJson(item.allowedHiddenFieldsJson ?: "{}")?.keys.orEmpty()
                val allowed = survey?.hiddenFields?.fieldIds.orEmpty().toSet() + declaredAtCapture
                val identity = item.surveyorId?.takeIf { it.isNotBlank() }?.let {
                    mapOf("surveyor_id" to it, "surveyor_name" to it)
                }.orEmpty()
                check(autoStamps.isEmpty() || allowed.isNotEmpty()) {
                    "Restore and refresh the original survey before uploading its saved metadata."
                }
                val filteredAutoStamps = (identity + autoStamps).filterKeys { it in allowed }
                val mergedData = hidden.filterValues { it != null && it != "" } + filteredAutoStamps + finalData
                req = CreateResponseRequest(
                    surveyId = item.surveyId,
                    finished = item.finished,
                    data = com.fbint.collector.domain.choiceAnswerPayload(mergedData, survey, sanitizedLang),
                    // Surveyors are collectors, not respondent contacts. v5 gates userId
                    // behind Contacts licensing; retain collector attribution in stamps.
                    userId = null,
                    meta = mapOf(
                        "source" to "fbint:${item.clientUuid}",
                        "surveyor" to item.surveyorId.orEmpty(),
                    ),
                    language = sanitizedLang,
                    variables = variables.takeIf { it.isNotEmpty() },
                    hiddenFields = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // Failure during payload prep — the POST never started, so leave sendingAt
                // alone (it's still null) and just log the error.
                failed++
                dao.markFailure(item.clientUuid, syncErrorMessage(t))
                if (!isFatal(t)) retry = true
                continue
            }

            // Another worker may have claimed OR finished this row since pendingOnce.
            val claimTime = System.currentTimeMillis()
            if (dao.claimForSending(item.clientUuid, claimTime, claimTime - SENDING_STALE_MS) != 1) {
                continue
            }
            try {
                val origin = item.serverBaseUrl ?: config.legacyQueueServer() ?: config.baseUrl()
                    ?: error("Original server is unavailable. Restore the device connection before syncing.")
                val resp = factory.client { origin }.createResponse(item.environmentId, req)
                // Once success is received, persist it even if WorkManager stops this job.
                withContext(NonCancellable) {
                    dao.markSynced(item.clientUuid, System.currentTimeMillis(), resp.data.id)
                }
                synced++
                resolvedFiles.keys.forEach { files.purgeUploadedFile(it) }
            } catch (cancelled: CancellationException) {
                // Keep the claim: the server may have accepted a cancelled request.
                throw cancelled
            } catch (t: Throwable) {
                failed++
                val msg = syncErrorMessage(t)
                dao.markFailure(item.clientUuid, msg)
                if (t is com.fbint.collector.data.remote.RequestNotSentException) {
                    // Transport proved that no request headers were attempted, even across
                    // redirects. Keep the row but release its uncertainty hold for recovery.
                    dao.clearSending(item.clientUuid)
                    retry = true
                } else if (isFatal(t)) {
                    // 4xx: server rejected the request — no response row was created, so
                    // it's safe to clear in-flight and let a future worker retry without
                    // waiting out the stale window. (Whether retry succeeds is a separate
                    // problem; isFatal returning true means the worker won't reschedule
                    // immediately, but the next periodic run will pick it up.)
                    dao.clearSending(item.clientUuid)
                } else {
                    // Network / 5xx / 429: server may have processed the request even
                    // though we got an error. Hold the in-flight marker so subsequent
                    // worker runs within SENDING_STALE_MS won't re-POST and duplicate.
                    retry = true
                }
            }
        }
        // Include rows skipped due to another worker's claim or a still-active safety hold.
        val held = dao.pendingCount().first() > 0 && dao.pendingOnce(System.currentTimeMillis() - SENDING_STALE_MS).isEmpty()
        return SyncOutcome(synced, failed, retry || held)
    }

    /** Resolve an uncertain acknowledgement before permitting another POST. */
    private suspend fun reconcile(item: QueuedResponseEntity): String? {
        val current = config.baseUrl()?.trimEnd('/') ?: error("Restore the original connection to reconcile this response.")
        val origin = (item.serverBaseUrl ?: config.legacyQueueServer() ?: current).trimEnd('/')
        check(current == origin) { "Reconnect the original server to verify an uncertain upload." }
        val key = config.apiKey() ?: error("API key required to verify an uncertain upload.")
        val management = factory.management { origin }
        for (page in 0 until 100) {
            val rows = management.listResponses(key, item.surveyId, 100, page * 100).data
            rows.firstOrNull { it.meta?.get("source") == "fbint:${item.clientUuid}" }?.let { return it.id }
            if (rows.size < 100) return null
        }
        error("Upload needs administrator review: response history exceeds the automatic verification limit.")
    }

    @Suppress("UNCHECKED_CAST")
    private fun substitutePlaceholders(
        value: Any?,
        files: Map<String, com.fbint.collector.data.local.entity.QueuedFileEntity>,
    ): Any? = when (value) {
        is String -> if (value.startsWith(FILE_PLACEHOLDER_PREFIX))
            files[value.removePrefix(FILE_PLACEHOLDER_PREFIX)]?.uploadedFileUrl ?: value
            else value
        is List<*> -> value.map { substitutePlaceholders(it, files) }
        else -> value
    }

    private fun isFatal(t: Throwable): Boolean {
        val status = (t as? retrofit2.HttpException)?.code() ?: return false
        // A proxy timeout can arrive after the server processed the request. Reconcile
        // before retrying it, just like an uncertain 5xx or disconnected connection.
        return status in 400..499 && status != 408 && status != 429
    }

    /**
     * The runner's i18n lookup uses `"default"` as a magic key, but Formbricks rejects that
     * as an unknown language code. Resolve it to the survey's real default language code (e.g.
     * `en-GB`) at sync time so old queued responses don't 400 forever.
     */
    private suspend fun sanitizeLanguageForServer(stored: String?, surveyId: String): String? {
        if (stored.isNullOrBlank()) return null
        if (stored != "default") return stored
        val survey = surveyRepo.loadFromCache(surveyId) ?: return null
        return survey.languages.firstOrNull { it.default }?.language?.code
    }

    data class SyncOutcome(val synced: Int, val failed: Int, val retry: Boolean)

    private companion object {
        const val STRUGGLE_THRESHOLD = 3

        /**
         * How long a row stays "in-flight" before the sync loop is willing to re-send it.
         * Within this window, concurrent or retried worker runs will skip it — that's what
         * prevents duplicate POSTs when the periodic worker fires while the one-shot worker
         * is mid-flight, or when a 200 OK is lost and our `markSynced` write never lands.
         *
         * 10 minutes comfortably exceeds the connect+read timeouts (20s + 30s) and the
         * one-shot worker's 30s exponential backoff, while still being short enough that
         * a genuine network failure that the server never received gets retried in a
         * sensible amount of time rather than sitting in the queue indefinitely.
         */
        const val SENDING_STALE_MS = 10L * 60 * 1000
    }
}
