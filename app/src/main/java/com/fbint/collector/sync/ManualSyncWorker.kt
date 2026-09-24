package com.fbint.collector.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fbint.collector.data.repository.FileQueueRepository
import com.fbint.collector.data.repository.ResponseRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** One bounded manual pass. It never backs off in the manual queue or cancels an upload. */
@HiltWorker
class ManualSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val files: FileQueueRepository,
    private val responses: ResponseRepository,
    private val scheduler: SyncScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        setProgress(workDataOf("startedAt" to started))
        return try {
            val uploads = files.uploadPending()
            val outcome = responses.syncPending()
            val pending = responses.pendingCount().first()
            if (pending > 0 || uploads.retry || outcome.retry) scheduler.requestImmediateSync()
            val message = when {
                pending == 0 -> "All saved responses are synced."
                outcome.failed > 0 || uploads.failed > 0 -> "${outcome.synced} synced; $pending remain saved on this device. Some uploads failed. Check the last recorded errors below."
                else -> "${outcome.synced} synced; $pending remain saved on this device. Waiting for upload verification, attachments or another active sync. Automatic retry remains scheduled."
            }
            Result.success(workDataOf("message" to message, "startedAt" to started, "finishedAt" to System.currentTimeMillis()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            scheduler.requestImmediateSync()
            Result.failure(workDataOf("message" to "Sync could not finish. Saved responses are kept; automatic retry remains scheduled.",
                "startedAt" to started, "finishedAt" to System.currentTimeMillis()))
        }
    }
    companion object { const val UNIQUE_NAME = "fbint.manualSync" }
}
