package com.fbint.collector.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm by lazy { WorkManager.getInstance(ctx) }

    fun scheduleAll() {
        scheduleResponseSyncPeriodic()
        scheduleSurveyRefreshPeriodic()
        scheduleFileUploadPeriodic()
    }

    /** Keep one automatic retry chain without cancelling an active upload or growing a backlog. */
    fun requestImmediateSync() {
        enqueueOneShot<FileUploadWorker>(FileUploadWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP)
        enqueueOneShot<ResponseSyncWorker>(ResponseSyncWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP)
    }

    fun requestManualSync() {
        enqueueOneShot<ManualSyncWorker>(ManualSyncWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP)
    }

    fun observeManualSync(): kotlinx.coroutines.flow.Flow<List<androidx.work.WorkInfo>> =
        wm.getWorkInfosForUniqueWorkFlow(ManualSyncWorker.UNIQUE_NAME)

    fun requestImmediateSurveyRefresh() {
        enqueueOneShot<SurveyRefreshWorker>(SurveyRefreshWorker.UNIQUE_NAME)
    }

    private inline fun <reified W : androidx.work.ListenableWorker> enqueueOneShot(
        uniqueName: String,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ) {
        val req = OneTimeWorkRequestBuilder<W>()
            .setConstraints(networkConstraints())
            .setInputData(androidx.work.workDataOf("requestedAt" to System.currentTimeMillis()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(uniqueName, policy, req)
    }

    private fun scheduleResponseSyncPeriodic() {
        val req = PeriodicWorkRequestBuilder<ResponseSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            ResponseSyncWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, req,
        )
    }

    private fun scheduleFileUploadPeriodic() {
        val req = PeriodicWorkRequestBuilder<FileUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            FileUploadWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, req,
        )
    }

    private fun scheduleSurveyRefreshPeriodic() {
        val req = PeriodicWorkRequestBuilder<SurveyRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .build()
        wm.enqueueUniquePeriodicWork(
            SurveyRefreshWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, req,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}
