package com.aipaca.app.agent.memory

import android.util.Log

private const val TAG = "ConsolidationPass"

/**
 * Loop L3 — the "dream". Curates the whole memory store instead of appending to it:
 * merges duplicates, resolves contradictions in favour of the newer entry, and drops
 * one-off details that were never lasting facts.
 *
 * Runs rarely and off the user-facing path (see `MemoryMaintenanceWorker`), which is
 * the sleep-time-compute argument: on a phone the NPU/GPU is single-tenant, so the
 * one place this work is free is while the device is idle and charging.
 *
 * Safety, mirroring the approve/reject/modify step in Anthropic's memory dreaming:
 * every run snapshots the files first, soul edits are always staged for approval,
 * and any pass that wants to delete a large share of a file is staged too rather
 * than applied.
 */
object ConsolidationPass {

    /** A file is only worth consolidating once it has at least this many entries. */
    const val MIN_ENTRIES = 4

    private const val SYSTEM_PROMPT =
        "You curate an assistant's long-term memory. You answer only with the requested " +
        "commands, one per line, and never with prose."

    private const val SOUL_INSTRUCTION =
        "\n\nThis file describes who you are and what the user expects of you. " +
        "Be conservative: only merge duplicates and drop entries the conversations " +
        "clearly contradict. Never invent new traits."

    data class FileResult(
        val file: String,
        val entriesBefore: Int,
        val entriesAfter: Int,
        /** True when the change was staged for approval instead of applied. */
        val staged: Boolean
    ) {
        val changed: Boolean get() = entriesBefore != entriesAfter || staged
    }

    data class Report(
        val files: List<FileResult> = emptyList(),
        val prunedSessions: Int = 0,
        val skippedReason: String? = null
    ) {
        val changedFiles: Int get() = files.count { it.changed }
    }

    /**
     * Consolidates every memory file and prunes the session index.
     *
     * @param liveSessionIds session ids still present in the message database;
     *        index entries pointing at deleted conversations are removed.
     */
    suspend fun run(
        store: MemoryStore,
        sessionIndex: SessionIndexStore,
        engine: MemoryEngine,
        liveSessionIds: Set<String>? = null
    ): Report {
        store.snapshot()

        val results = listOfNotNull(
            consolidate(store, engine, MemoryStore.USER_FILE, "what you know about the user", false),
            consolidate(store, engine, MemoryStore.MEMORY_FILE, "what you know about the user's environment", false),
            consolidate(store, engine, MemoryStore.SOUL_FILE, "your own description", true)
        )

        val pruned = pruneSessionIndex(sessionIndex, liveSessionIds)
        val report = Report(files = results, prunedSessions = pruned)
        Log.i(TAG, "consolidation done: " + report.changedFiles + " files changed, " + pruned + " session notes pruned")
        return report
    }

    private suspend fun consolidate(
        store: MemoryStore,
        engine: MemoryEngine,
        file: String,
        label: String,
        alwaysStage: Boolean
    ): FileResult? {
        val before = store.readEntries(file)
        if (before.size < MIN_ENTRIES) {
            Log.d(TAG, file + ": only " + before.size + " entries, nothing to consolidate")
            return null
        }

        val prompt = MemoryConsolidation.prompt(label, before) + if (alwaysStage) SOUL_INSTRUCTION else ""
        val raw = engine.complete(systemPrompt = SYSTEM_PROMPT, userPrompt = prompt, maxTokens = 512)
        val ops = MemoryConsolidation.parse(raw)
        if (ops.isEmpty()) {
            Log.d(TAG, file + ": no changes proposed")
            return null
        }

        val after = MemoryConsolidation.apply(before, ops, MemoryFormat.today())
        if (after.isEmpty()) {
            Log.w(TAG, file + ": pass emptied the file, refusing")
            return null
        }

        val stage = alwaysStage || MemoryConsolidation.needsApproval(before, after)
        if (stage) {
            store.writePending(file, MemoryFormat.render(after))
        } else {
            store.writeEntries(file, after)
        }
        Log.i(TAG, file + ": " + before.size + " -> " + after.size + " entries" + if (stage) " (staged)" else "")
        return FileResult(file, before.size, after.size, stage)
    }

    /** Removes index entries whose conversation no longer exists. */
    private fun pruneSessionIndex(sessionIndex: SessionIndexStore, liveSessionIds: Set<String>?): Int {
        if (liveSessionIds == null) return 0
        val notes = sessionIndex.list()
        val kept = notes.filter { it.sessionId in liveSessionIds }
        if (kept.size == notes.size) return 0
        sessionIndex.writeAll(kept)
        return notes.size - kept.size
    }
}
