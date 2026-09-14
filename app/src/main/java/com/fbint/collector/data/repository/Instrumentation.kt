package com.fbint.collector.data.repository

import android.location.Location

/**
 * Per-response telemetry the runner snapshots at submit time. The repository writes only the
 * fields whose names match a hidden-field ID in the target survey — surveys that don't declare
 * a given field skip it silently.
 *
 * Field names recognised at the survey level (set as Hidden Field IDs in Formbricks):
 * `surveyor_id`, `device_install_id`, `app_version`, `started_at`, `submitted_at`,
 * `time_to_complete_seconds`, `surveyor_pace_today`, `language_used`, `is_offline_capture`,
 * `location`, `location_lat`, `location_lng`, `location_accuracy_m`.
 */
/**
 * Hidden-field IDs the runtime fills in automatically. The "Before you start" screen filters
 * these out so surveyors are never asked to type them; if a survey's only hidden fields are in
 * this set, that screen is skipped entirely and the runner opens straight away.
 */
val AUTO_STAMPED_HIDDEN_FIELD_IDS: Set<String> = setOf(
    "surveyor_id",
    "surveyor_name",
    "device_install_id",
    "app_version",
    "started_at",
    "submitted_at",
    "time_to_complete_seconds",
    "surveyor_pace_today",
    "language_used",
    "is_offline_capture",
    "location",
    "location_lat",
    "location_lng",
    "location_accuracy_m",
)

data class Instrumentation(
    val startedAtIso: String,
    val submittedAtIso: String,
    val timeToCompleteSeconds: Long,
    val surveyorPaceToday: Int,
    val languageUsed: String,
    val isOfflineCapture: Boolean,
    val location: Location?,
)

/** Build the candidate auto-stamp map; the repository filters it to the survey's hidden fields. */
fun Instrumentation.toCandidateStamps(
    surveyorId: String?,
    deviceInstallId: String,
    appVersion: String,
): Map<String, String> {
    val out = mutableMapOf<String, String>()
    out["surveyor_id"] = surveyorId.orEmpty()
    out["surveyor_name"] = surveyorId.orEmpty()
    out["device_install_id"] = deviceInstallId
    out["app_version"] = appVersion
    out["started_at"] = startedAtIso
    out["submitted_at"] = submittedAtIso
    out["time_to_complete_seconds"] = timeToCompleteSeconds.toString()
    out["surveyor_pace_today"] = surveyorPaceToday.toString()
    out["language_used"] = languageUsed
    out["is_offline_capture"] = isOfflineCapture.toString()
    if (location != null) {
        out["location"] = "${location.latitude},${location.longitude}"
        out["location_lat"] = location.latitude.toString()
        out["location_lng"] = location.longitude.toString()
        out["location_accuracy_m"] = location.accuracy.toString()
    }
    return out
}
