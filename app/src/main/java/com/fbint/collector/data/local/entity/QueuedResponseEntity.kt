package com.fbint.collector.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A captured response waiting to be POSTed. [clientUuid] is generated on capture and echoed
 * into the server payload via meta.source — that way if a successful POST appears as a
 * network failure to the device and the worker retries, the duplicate can be detected later
 * (Formbricks does not de-dupe server-side).
 *
 * `variablesJson` and `hiddenFieldsJson` are JSON-encoded `Map<String,Any?>` snapshots taken
 * at the moment the response was finalised — they reflect logic-engine state, not future
 * mutations.
 */
@Entity(tableName = "queued_responses")
data class QueuedResponseEntity(
    @PrimaryKey val clientUuid: String,
    val surveyId: String,
    val environmentId: String,
    val surveyorId: String?,
    val finished: Boolean,
    val language: String?,
    val dataJson: String,
    val variablesJson: String? = null,
    val hiddenFieldsJson: String? = null,
    /**
     * All auto-stamp candidates captured at submit time, unfiltered. Sync filters this against
     * the survey's CURRENT cached `hiddenFields.fieldIds` so a response captured before the
     * admin declared the field IDs in Formbricks still gets its auto-stamps through, as long
     * as the cache has caught up by sync time.
     */
    val autoStampsJson: String? = null,
    val capturedAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    val syncedAt: Long? = null,
    val serverResponseId: String? = null,
    /**
     * Set just before a POST is issued, cleared on success or on a confirmed-rejection
     * 4xx. An atomic claim prevents competing workers from sending the same row within
     * the ten-minute lease. After the lease expires an ambiguous network failure can
     * still cause a duplicate; this marker is not server-side idempotency.
     */
    val sendingAt: Long? = null,
    val serverBaseUrl: String? = null,
    val allowedHiddenFieldsJson: String? = null,
)
