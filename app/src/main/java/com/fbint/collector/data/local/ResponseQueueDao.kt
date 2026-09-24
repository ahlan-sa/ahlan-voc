package com.fbint.collector.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fbint.collector.data.local.entity.QueuedResponseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ResponseQueueDao {
    @Query("SELECT * FROM queued_responses ORDER BY capturedAt ASC")
    suspend fun exportAll(): List<QueuedResponseEntity>


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: QueuedResponseEntity)

    @Query("SELECT * FROM queued_responses WHERE clientUuid = :id")
    fun observeById(id: String): Flow<QueuedResponseEntity?>

    @Query("SELECT * FROM queued_responses WHERE clientUuid = :id")
    suspend fun getById(id: String): QueuedResponseEntity?

    /**
     * Pending rows excluding ones currently in-flight. A row is "in-flight" when its
     * [QueuedResponseEntity.sendingAt] is more recent than [staleBefore] — within that
     * window we assume a previous worker invocation may have already POSTed it (and the
     * server may have created a response we never saw the 200 for), so re-sending would
     * duplicate. Past the window we accept the small re-send risk to avoid losing data.
     */
    @Query(
        "SELECT * FROM queued_responses " +
            "WHERE syncedAt IS NULL AND (sendingAt IS NULL OR sendingAt < :staleBefore) " +
            "ORDER BY capturedAt ASC"
    )
    suspend fun pendingOnce(staleBefore: Long): List<QueuedResponseEntity>

    @Query("UPDATE queued_responses SET attempts = attempts + 1, lastError = :error WHERE clientUuid = :id")
    suspend fun markFailure(id: String, error: String)

    /** Atomically claim a pending row; only the caller that updates one row may POST it. */
    @Query(
        "UPDATE queued_responses SET sendingAt = :ts WHERE clientUuid = :id " +
            "AND syncedAt IS NULL AND (sendingAt IS NULL OR sendingAt < :staleBefore)"
    )
    suspend fun claimForSending(id: String, ts: Long, staleBefore: Long): Int

    /** Clear in-flight marker — used after a confirmed-rejection (4xx) so retries aren't blocked. */
    @Query("UPDATE queued_responses SET sendingAt = NULL WHERE clientUuid = :id")
    suspend fun clearSending(id: String)

    @Query("UPDATE queued_responses SET syncedAt = :ts, serverResponseId = :serverId, sendingAt = NULL, lastError = NULL WHERE clientUuid = :id")
    suspend fun markSynced(id: String, ts: Long, serverId: String)

    @Query("SELECT COUNT(*) FROM queued_responses WHERE syncedAt IS NULL")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queued_responses WHERE syncedAt IS NOT NULL")
    fun syncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queued_responses WHERE syncedAt IS NULL AND attempts >= :threshold")
    fun strugglingCount(threshold: Int): Flow<Int>

    @Query("SELECT * FROM queued_responses ORDER BY capturedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<QueuedResponseEntity>>

    @Query("SELECT surveyId, COUNT(*) AS total, SUM(CASE WHEN syncedAt IS NULL THEN 1 ELSE 0 END) AS pending FROM queued_responses GROUP BY surveyId")
    fun observePerSurveyCounts(): Flow<List<PerSurveyCount>>

    @Query("SELECT clientUuid, serverResponseId FROM queued_responses WHERE finished = 1 AND surveyorId = :surveyorId AND environmentId = :environmentId AND (serverBaseUrl = :server OR (serverBaseUrl IS NULL AND :includeLegacy)) AND capturedAt >= :fromMs AND capturedAt < :toMs")
    fun observeDailyResponses(surveyorId: String, environmentId: String, server: String, includeLegacy: Boolean, fromMs: Long, toMs: Long): Flow<List<DailyResponseIdentity>>

    @Query("SELECT COUNT(*) FROM queued_responses WHERE surveyorId = :surveyorId AND capturedAt >= :sinceMs")
    suspend fun countSince(surveyorId: String, sinceMs: Long): Int
}

data class PerSurveyCount(val surveyId: String, val total: Int, val pending: Int)

data class DailyResponseIdentity(val clientUuid: String, val serverResponseId: String?)
