package com.fbint.collector.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fbint.collector.data.repository.FileQueueRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FileUploadWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val files: FileQueueRepository,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        val outcome = files.uploadPending()
        if (outcome.retry) Result.retry() else Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "fbint.fileUpload"
        const val PERIODIC_NAME = "fbint.fileUpload.periodic"
        const val MAX_ATTEMPTS = 8
    }
}
