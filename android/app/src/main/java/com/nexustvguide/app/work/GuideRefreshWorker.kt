package com.nexustvguide.app.work

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.data.repository.GuideRepositoryProvider
import java.util.concurrent.TimeUnit

class GuideRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "GuideRefreshWorker"
        private const val TAG = "GuideRefreshWorker"

        fun schedule(context: Context) {
            if (GuideRepositoryProvider.getGuideSource(context) != "LOCAL") {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<GuideRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                Log.i(TAG, "Scheduled periodic guide refresh every 6 hours")
            } catch (e: Exception) {
                Log.w(TAG, "WorkManager initialization skipped or unavailable: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Executing scheduled guide refresh")
        return try {
            val source = GuideRepositoryProvider.getGuideSource(applicationContext)
            if (source == "LOCAL") {
                val localRepo = GuideRepositoryProvider.getLocalRepository(applicationContext)
                val channels = localRepo.getChannels().map { c ->
                    Channel(
                        id = c.id,
                        sourceId = c.sourceId,
                        name = c.name,
                        logoUrl = c.logoUrl,
                        inNlziet = c.inNlziet,
                        nlzietSlug = c.nlzietSlug,
                        nlzietChannelId = c.nlzietChannelId,
                        sortOrder = c.sortOrder
                    )
                }
                val result = localRepo.refreshCoordinator.refresh(channels)
                if (result.retryableSourceFailure) return if (runAttemptCount < 2) Result.retry() else Result.failure()
                if (result.successfulDays.isEmpty()) return Result.failure()
                Log.i(TAG, "Periodic refresh completed: ${result.successfulDays.size} days updated, ${result.totalExactTargets} exact targets")
            }
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Periodic guide refresh failed", e)
            if (com.nexustvguide.app.core.http.isTransientSourceFailure(e) && runAttemptCount < 2) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
