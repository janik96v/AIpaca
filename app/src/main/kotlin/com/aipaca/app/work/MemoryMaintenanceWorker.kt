package com.aipaca.app.work

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aipaca.app.EngineState
import com.aipaca.app.agent.memory.ConsolidationPass
import com.aipaca.app.data.AgentPrefs
import com.aipaca.app.data.MessageDatabase

private const val TAG = "MemoryMaintenance"

/**
 * Loop L3 — the periodic memory consolidation pass ("dream").
 *
 * Runs only while the device is idle, charging and not thermally throttled: on a
 * phone the NPU/GPU is single-tenant, so curating memory during use would compete
 * with the user's own turns. That is the sleep-time-compute argument applied to a
 * device where it matters most.
 *
 * Gating is deliberately an interval **and** a change threshold. An interval alone
 * either burns battery re-reading an unchanged file, or lets a heavily used memory
 * file rot for weeks between runs.
 */
class MemoryMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        /** Set on a manually triggered run to bypass interval and threshold checks. */
        const val KEY_FORCE = "force"

        /** No point waking the model to curate two new entries. */
        const val MIN_NEW_ENTRIES = 5
    }

    override suspend fun doWork(): Result {
        val prefs = AgentPrefs(applicationContext)
        val forced = inputData.getBoolean(KEY_FORCE, false)

        if (!prefs.isMemoryLoopEnabled()) {
            Log.i(TAG, "memory loop disabled — skipping")
            return Result.success()
        }
        if (!EngineState.canRunMemoryPass()) {
            // No model loaded. Retrying soon won't help; the next scheduled run will.
            Log.i(TAG, "no backend ready — skipping")
            return Result.success()
        }
        if (EngineState.isGenerating.value) {
            Log.i(TAG, "engine busy — retrying later")
            return Result.retry()
        }
        if (isThermallyThrottled()) {
            Log.i(TAG, "device thermally throttled — retrying later")
            return Result.retry()
        }
        if (!forced && !isDue(prefs)) return Result.success()

        return try {
            val liveSessionIds = try {
                MessageDatabase.getInstance(applicationContext).messageDao().sessionIds().toSet()
            } catch (e: Exception) {
                Log.w(TAG, "could not read session ids, skipping index prune", e)
                null
            }

            val report = ConsolidationPass.run(
                store = EngineState.memoryStore,
                sessionIndex = EngineState.sessionIndexStore,
                engine = EngineState.memoryEngine(),
                liveSessionIds = liveSessionIds
            )

            prefs.setLastConsolidationAt(System.currentTimeMillis())
            prefs.setEntriesSinceConsolidation(0)
            Log.i(TAG, "consolidation finished: " + report.changedFiles + " files changed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "consolidation failed", e)
            Result.retry()
        }
    }

    private fun isDue(prefs: AgentPrefs): Boolean {
        val newEntries = prefs.getEntriesSinceConsolidation()
        if (newEntries < MIN_NEW_ENTRIES) {
            Log.i(TAG, "only " + newEntries + " new entries since last run — skipping")
            return false
        }
        val intervalMillis = prefs.getConsolidationIntervalHours() * 60L * 60L * 1000L
        val elapsed = System.currentTimeMillis() - prefs.getLastConsolidationAt()
        if (elapsed < intervalMillis) {
            Log.i(TAG, "last run was " + (elapsed / 60000L) + " min ago — skipping")
            return false
        }
        return true
    }

    /** Never add heat to a device that is already throttling. Requires API 29. */
    private fun isThermallyThrottled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val power = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return try {
            power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE
        } catch (e: Exception) {
            false
        }
    }
}
