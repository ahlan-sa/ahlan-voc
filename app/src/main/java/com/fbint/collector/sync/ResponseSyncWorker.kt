package com.fbint.collector.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fbint.collector.data.repository.ResponseRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class ResponseSyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val repo: ResponseRepository,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        val outcome = repo.syncPending()
        when {
            outcome.retry -> Result.retry()
            else -> Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "fbint.responseSync"
        const val PERIODIC_NAME = "fbint.responseSync.periodic"
        const val MAX_ATTEMPTS = 8
    }
}
