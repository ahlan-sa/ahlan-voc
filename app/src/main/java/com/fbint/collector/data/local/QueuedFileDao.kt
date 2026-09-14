package com.fbint.collector.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fbint.collector.data.local.entity.QueuedFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QueuedFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: QueuedFileEntity)

    @Query("SELECT * FROM queued_files WHERE surveyId = :surveyId AND serverBaseUrl = :origin AND boundResponseUuid IS NULL")
    suspend fun unboundForSurvey(surveyId: String, origin: String): List<QueuedFileEntity>

    @Query("SELECT * FROM queued_files WHERE clientUuid = :id")
    suspend fun getById(id: String): QueuedFileEntity?

    @Query("SELECT * FROM queued_files WHERE clientUuid IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<QueuedFileEntity>

    @Query("SELECT * FROM queued_files WHERE uploadedAt IS NULL AND boundResponseUuid IS NOT NULL ORDER BY capturedAt ASC")
    suspend fun pendingOnce(): List<QueuedFileEntity>

    @Query("UPDATE queued_files SET uploadingAt = :now WHERE clientUuid = :id AND uploadedAt IS NULL AND (uploadingAt IS NULL OR uploadingAt < :staleBefore)")
    suspend fun claimUpload(id: String, now: Long, staleBefore: Long): Int

    @Query("UPDATE queued_files SET uploadingAt = NULL WHERE clientUuid = :id")
    suspend fun releaseUpload(id: String)

    @Query("UPDATE queued_files SET boundResponseUuid = :responseUuid WHERE clientUuid IN (:fileIds)")
    suspend fun bindToResponse(fileIds: List<String>, responseUuid: String)

    @Query("UPDATE queued_files SET attempts = attempts + 1, lastError = :error WHERE clientUuid = :id")
    suspend fun markFailure(id: String, error: String)

    @Query("UPDATE queued_files SET uploadedFileUrl = :url, uploadedAt = :ts, lastError = NULL WHERE clientUuid = :id")
    suspend fun markUploaded(id: String, url: String, ts: Long)

    @Query("DELETE FROM queued_files WHERE clientUuid = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM queued_files WHERE uploadedAt IS NULL")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queued_files WHERE uploadedAt IS NOT NULL")
    fun uploadedCount(): Flow<Int>
}
