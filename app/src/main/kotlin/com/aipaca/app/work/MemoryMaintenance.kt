package com.aipaca.app.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

private const val TAG = "MemoryMaintenance"

/** Schedules and triggers the memory consolidation loop ([MemoryMaintenanceWorker]). */
object MemoryMaintenance {

    private const val PERIODIC_WORK = "aipaca-memory-consolidation"
    private const val ONE_SHOT_WORK = "aipaca-memory-consolidation-now"

    /**
     * Constraints for the scheduled pass. Idle + charging is what makes running a
     * language model in the background acceptable on a phone; the worker re-checks
     * thermal state itself, since WorkManager has no constraint for it.
     */
    private fun scheduledConstraints(): Constraints = Constraints.Builder()
        .setRequiresCharging(true)
        .setRequiresDeviceIdle(true)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Enqueues the daily pass. Safe to call on every app start — [ExistingPeriodicWorkPolicy.KEEP]
     * leaves an already-scheduled run alone.
     *
     * The 24 h period is only the outer bound: the worker additionally requires a
     * minimum number of new memory entries before it does any work.
     */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequest.Builder(
            MemoryMaintenanceWorker::class.java, 24L, TimeUnit.HOURS
        )
            .setConstraints(scheduledConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        Log.i(TAG, "periodic memory consolidation scheduled")
    }

    /**
     * Runs the pass as soon as possible, ignoring the interval and entry threshold.
     *
     * Triggered by "Update memory now" on the Memory screen — the charging/idle
     * constraints are dropped here because the user is explicitly asking for it and
     * is watching, but the worker's own thermal and busy-engine checks still apply.
     */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequest.Builder(MemoryMaintenanceWorker::class.java)
            .setInputData(Data.Builder().putBoolean(MemoryMaintenanceWorker.KEY_FORCE, true).build())
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.KEEP, request)
        Log.i(TAG, "manual memory consolidation enqueued")
    }
}
