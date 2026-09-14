package com.fbint.collector.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fbint.collector.ui.nav.OnboardingState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for device-level config: base URL, API key, environmentId, project name,
 * surveyor identity. Persisted in EncryptedSharedPreferences so the API key is at rest under the
 * Android Keystore.
 *
 * The admin and surveyor flows write disjoint subsets of these keys, so we don't need atomic txns.
 */
@Singleton
class ConfigRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "fbint_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        // Bind legacy queue rows to the server configured when this version first starts.
        if (!prefs.contains("legacy_queue_server") && prefs.contains(KEY_BASE_URL)) {
            prefs.edit().putString("legacy_queue_server", prefs.getString(KEY_BASE_URL, null)).commit()
        }
    }

    fun legacyQueueServer(): String? = prefs.getString("legacy_queue_server", null)
    private fun draftKey(id: String) = "draft:${baseUrl()}:${workspaceId() ?: environmentId()}:$id"
    fun loadDraft(id: String): String? = prefs.getString(draftKey(id), null)
    fun saveDraft(id: String, json: String) {
        check(prefs.edit().putString(draftKey(id), json).commit()) { "Unable to save draft. Check device storage." }
    }
    fun deleteDraft(id: String) { check(prefs.edit().remove(draftKey(id)).commit()) { "Could not remove saved draft." } }

    private fun performanceKey() = "performance:${baseUrl()}:${workspaceId() ?: environmentId()}:${surveyorId()}"
    fun loadPerformanceSnapshot(): String? = prefs.getString(performanceKey(), null)
    fun savePerformanceSnapshot(json: String) { prefs.edit().putString(performanceKey(), json).apply() }

    private fun pinnedSurveysKey() = "pinned_surveys:${baseUrl()}:${workspaceId() ?: environmentId()}"
    fun pinnedSurveyIds(): Set<String> = prefs.getStringSet(pinnedSurveysKey(), emptySet()).orEmpty().toSet()
    fun savePinnedSurveyIds(ids: Set<String>) {
        prefs.edit().putStringSet(pinnedSurveysKey(), ids.toSet()).apply()
    }

    fun hasAdminPassword(): Boolean = prefs.contains("admin_password")

    fun saveAdminPassword(password: String) {
        require(password.length >= 4) { "Use at least 4 characters." }
        check(prefs.edit().putString("admin_password", password).commit()) { "Could not save password. Try again." }
    }

    fun verifyAdminPassword(password: String): Boolean {
        val saved = prefs.getString("admin_password", null) ?: return false
        return java.security.MessageDigest.isEqual(password.toByteArray(Charsets.UTF_8), saved.toByteArray(Charsets.UTF_8))
    }

    fun baseUrl(): String? = prefs.getString(KEY_BASE_URL, null)
    fun apiKey(): String? = prefs.getString(KEY_API_KEY, null)
    fun environmentId(): String? = prefs.getString(KEY_ENV_ID, null)
    fun workspaceId(): String? = prefs.getString(KEY_WORKSPACE_ID, null)
    fun projectName(): String? = prefs.getString(KEY_PROJECT_NAME, null)
    fun surveyorId(): String? = prefs.getString(KEY_SURVEYOR_ID, null)

    fun saveServerConfig(
        baseUrl: String, apiKey: String, environmentId: String, projectName: String?,
        workspaceId: String? = null,
    ) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_ENV_ID, environmentId.trim())
            .putString(KEY_WORKSPACE_ID, workspaceId?.trim()?.takeIf { it.isNotBlank() })
            .putString(KEY_PROJECT_NAME, projectName)
            .remove(KEY_KEY_READONLY)
            .apply()
    }

    fun saveSurveyorId(id: String) {
        prefs.edit().putString(KEY_SURVEYOR_ID, id.trim()).apply()
    }

    /**
     * Whether the API key has been observed to lack write permission on /management/surveys.
     * Set to true the first time we see a 401 from a survey-update request, so we stop trying.
     * Cleared by [clear] (re-onboarding) or [resetWriteAccessProbe] (admin re-validates).
     */
    fun apiKeyKnownReadOnly(): Boolean = prefs.getBoolean(KEY_KEY_READONLY, false)

    fun markApiKeyReadOnly() {
        prefs.edit().putBoolean(KEY_KEY_READONLY, true).apply()
    }

    fun resetWriteAccessProbe() {
        prefs.edit().remove(KEY_KEY_READONLY).apply()
    }

    /** Last successful auto-update check (epoch ms). 0 if never. Used to throttle. */
    fun lastUpdateCheckMs(): Long = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)

    fun markUpdateCheckedNow() {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
    }

    /** Stable per-install UUID. Generated on first read, persists until uninstall. */
    fun deviceInstallId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    fun clear() {
        val legacy = legacyQueueServer()
        prefs.edit().clear().putString("legacy_queue_server", legacy).commit()
    }

    /**
     * Hidden-field values are saved per-survey-id so the next time this surveyor opens the same
     * survey, the inputs come pre-filled with what they typed last time. They can still edit.
     * Stored as a flat key-per-field under the prefs key `hidden:<surveyId>:<fieldId>`.
     */
    fun saveHiddenFields(surveyId: String, values: Map<String, String>) {
        val editor = prefs.edit()
        values.forEach { (fieldId, value) -> editor.putString(hiddenKey(surveyId, fieldId), value) }
        editor.apply()
    }

    fun loadHiddenFields(surveyId: String, fieldIds: List<String>): Map<String, String> =
        fieldIds.associateWith { prefs.getString(hiddenKey(surveyId, it), null).orEmpty() }

    private fun hiddenKey(surveyId: String, fieldId: String) = "hidden:$surveyId:$fieldId"

    fun observeOnboardingState(): Flow<OnboardingState> = observePrefs().distinctUntilChanged()

    private fun observePrefs(): Flow<OnboardingState> = callbackFlow {
        fun snapshot(): OnboardingState = when {
            apiKey().isNullOrBlank() || environmentId().isNullOrBlank() || baseUrl().isNullOrBlank() ->
                OnboardingState.NoConfig
            surveyorId().isNullOrBlank() -> OnboardingState.NoSurveyor
            else -> OnboardingState.Ready
        }
        trySend(snapshot())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(snapshot()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_ENV_ID = "env_id"
        const val KEY_WORKSPACE_ID = "workspace_id"
        const val KEY_PROJECT_NAME = "project_name"
        const val KEY_SURVEYOR_ID = "surveyor_id"
        const val KEY_INSTALL_ID = "device_install_id"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_ms"
        const val KEY_KEY_READONLY = "api_key_readonly"
    }
}
