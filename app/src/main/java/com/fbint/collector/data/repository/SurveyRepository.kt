package com.fbint.collector.data.repository

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.fbint.collector.data.local.SurveyDao
import com.fbint.collector.data.local.entity.SurveyEntity
import com.fbint.collector.data.remote.FormbricksApiFactory
import com.fbint.collector.data.remote.FormbricksManagementApi
import com.fbint.collector.data.remote.dto.HiddenFieldsDto
import com.fbint.collector.data.remote.dto.SurveyDto
import com.fbint.collector.data.remote.dto.resolveWorkspace
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import com.fbint.collector.domain.isVisibleInApp
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurveyRepository @Inject constructor(
    private val factory: FormbricksApiFactory,
    private val dao: SurveyDao,
    private val config: ConfigRepository,
    @ApplicationContext private val ctx: Context,
    moshi: Moshi,
) {
    private val surveyAdapter: JsonAdapter<SurveyDto> = moshi.adapter(SurveyDto::class.java)

    /**
     * Resolved per-call so a config update (admin entered a new base URL) takes effect on the
     * next API call without restarting the app. Factory caches Retrofit per URL so this is cheap.
     */
    private val api: FormbricksManagementApi
        get() = factory.management { config.baseUrl() ?: "https://app.formbricks.com" }

    fun observeCachedSurveys(): Flow<List<SurveyEntity>> = dao.observeAll().map { cached ->
        cached.filter { surveyAdapter.fromJson(it.json)?.isVisibleInApp() == true }
    }

    fun cachedCount(): Flow<Int> = dao.count()

    /**
     * Refreshes the local cache: lists all surveys in the configured environment, pulls the
     * full detail for each, best-effort registers the auto-stamp hidden field IDs server-side
     * (silent on read-only-key 401), persists the JSON, and pre-warms the image cache so
     * picture-selection / welcome-card images render offline at the venue.
     */
    suspend fun refresh(): Result<Int> = runCatching {
        val key = requireNotNull(config.apiKey()) { "API key not configured" }
        val enteredId = config.workspaceId() ?: requireNotNull(config.environmentId()) { "Workspace ID not configured" }
        val currentApi = api
        // Also repairs the interpretation of settings saved by old app versions, where a
        // Workspace ID could be stored in the legacy environmentId preference.
        val connection = currentApi.me(key).resolveWorkspace(enteredId)
        val list = currentApi.listSurveys(key).data
            .filter { it.environmentId == connection.environmentId ||
                (connection.workspaceId != null && it.workspaceId == connection.workspaceId) }
            // v5 rejects responses to paused/completed surveys, not just drafts.
            .filter { it.status.equals("inProgress", ignoreCase = true) }

        val detailed = list.map { summary ->
            val full = if (summary.questions.isEmpty()) {
                runCatching { currentApi.getSurvey(key, summary.id).data }
                    .onFailure { if (it is CancellationException) throw it }.getOrDefault(summary)
            } else summary
            tryAutoRegisterHiddenFields(key, full)
        }
        // Cache active NO surveys too: queued responses still need their language and
        // hidden-field definitions. Visibility only controls new collection, not uploads.
        dao.upsert(detailed.map { it.toEntity() })
        dao.pruneExcept(detailed.map { it.id })
        prewarmImages(detailed)
        detailed.size
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun loadFromCache(id: String): SurveyDto? {
        val entity = dao.getById(id) ?: return null
        return surveyAdapter.fromJson(entity.json)
    }

    /**
     * Add any auto-stamp IDs missing from the survey's hidden fields, then PUT the merged set
     * back to Formbricks. On 401/403 we record that the API key is read-only and stop trying
     * for the lifetime of this install — admin would need to re-onboard with a write-scope key
     * (or add the field IDs manually) to make this work.
     */
    private suspend fun tryAutoRegisterHiddenFields(apiKey: String, survey: SurveyDto): SurveyDto {
        if (config.apiKeyKnownReadOnly()) return survey
        val current = survey.hiddenFields?.fieldIds.orEmpty().toSet()
        val desired = current + AUTO_STAMPED_HIDDEN_FIELD_IDS
        if (desired == current) return survey
        val mergedIds = desired.toList()
        val body = mapOf("hiddenFields" to mapOf("enabled" to true, "fieldIds" to mergedIds))
        return try {
            val resp = api.updateSurvey(apiKey, survey.id, body)
            when {
                resp.code() == 401 || resp.code() == 403 -> {
                    config.markApiKeyReadOnly()
                    survey
                }
                resp.isSuccessful -> survey.copy(
                    hiddenFields = HiddenFieldsDto(enabled = true, fieldIds = mergedIds),
                )
                else -> survey
            }
        } catch (_: Throwable) {
            survey
        }
    }

    /**
     * Fire image fetches into Coil's disk cache. We don't await results — failures here are
     * non-fatal (the runner will still render the URL, just possibly with a load error icon
     * if offline at use time).
     */
    private fun prewarmImages(surveys: List<SurveyDto>) {
        val urls = surveys.flatMap { s ->
            buildList {
                s.welcomeCard?.fileUrl?.let { add(it) }
                s.endings.forEach { it.imageUrl?.let { url -> add(url) } }
                s.questions.forEach { q ->
                    q.imageUrl?.let { add(it) }
                    q.choices?.forEach { c -> c.imageUrl?.let { add(it) } }
                }
            }
        }.filter { it.isNotBlank() }.distinct()

        if (urls.isEmpty()) return
        val loader: ImageLoader = SingletonImageLoader.get(ctx)
        urls.forEach { url ->
            loader.enqueue(ImageRequest.Builder(ctx).data(url).build())
        }
    }

    private fun SurveyDto.toEntity(): SurveyEntity = SurveyEntity(
        id = id,
        name = name,
        status = status,
        type = type,
        environmentId = environmentId,
        updatedAt = updatedAt,
        cachedAt = System.currentTimeMillis(),
        json = surveyAdapter.toJson(this),
    )
}
