package com.traintracker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that refreshes the CIF schedule database daily at ~01:00.
 *
 * Scheduled as a PeriodicWorkRequest with a 24-hour interval. The initial delay
 * is calculated so the first run lands in the 01:00–02:00 window; subsequent
 * runs fire every 24 hours from there.
 *
 * Constraints: requires an unmetered or any network connection. WorkManager
 * will retry automatically (exponential backoff) if the network isn't available.
 *
 * The worker waits for CorpusData to be available before refreshing, since
 * CifRepository needs TIPLOC→CRS resolution during the parse.
 */
class CifUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "CIF daily update starting")
        return try {
            // Ensure CorpusData is ready — it should be from app init, but guard anyway
            if (!CorpusData.isReady) {
                Log.d(TAG, "CorpusData not ready — aborting, will retry next cycle")
                return Result.retry()
            }
            CifRepository.forceRefresh(appContext)
            Log.d(TAG, "CIF daily update complete")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "CIF daily update failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG         = "CifUpdateWorker"
        private const val WORK_NAME   = "cif_daily_update"
        private const val TARGET_HOUR = 1  // 01:00

        /**
         * Schedule (or reschedule) the daily CIF update worker.
         * Safe to call multiple times — uses KEEP policy so an already-scheduled
         * worker is not interrupted.
         */
        fun schedule(context: Context) {
            val initialDelay = minutesUntilNextTarget()

            val request = PeriodicWorkRequestBuilder<CifUpdateWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            Log.d(TAG, "CIF update scheduled — first run in ${initialDelay}min (~${TARGET_HOUR}:00)")
        }

        /** Minutes until the next TARGET_HOUR:00. Always at least 1 minute ahead. */
        private fun minutesUntilNextTarget(): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, TARGET_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_MONTH, 1) // already past 01:00 today
            }
            val diff = (target.timeInMillis - now.timeInMillis) / 60_000
            return diff.coerceAtLeast(1)
        }
    }
}
