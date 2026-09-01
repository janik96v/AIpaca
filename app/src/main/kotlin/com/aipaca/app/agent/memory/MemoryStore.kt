package com.aipaca.app.agent.memory

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "MemoryStore"

/**
 * Filesystem-backed storage for the three prompt-resident memory files:
 *
 * - [SOUL_FILE]   — who the agent is and what the user expects it to be (issue #52)
 * - [USER_FILE]   — user preferences, communication style, name
 * - [MEMORY_FILE] — environment facts, project conventions, corrections
 *
 * Entries are `§`-separated and date-stamped (see [MemoryFormat]). Files live
 * unencrypted in app-internal storage (`filesDir/agent_memory/`).
 *
 * Beyond plain reads and writes this store provides the two things the
 * consolidation loop needs to be safe:
 *
 * - [snapshot] keeps the last [MAX_BACKUPS] versions of every file, so a bad
 *   consolidation pass can always be undone from the Memory screen.
 * - [writePending] stages a proposed rewrite next to the live file instead of
 *   replacing it, for changes that require the user to approve them first
 *   (soul edits, and any pass that wants to delete most of a file).
 */
class MemoryStore(rootDir: File) {

    /** Production entry point — files live in `filesDir/agent_memory/`. */
    constructor(context: Context) : this(File(context.filesDir, MEMORY_DIR))


    companion object {
        /** Directory under `filesDir` shared with [SessionIndexStore]. */
        const val MEMORY_DIR = "agent_memory"

        const val SOUL_FILE = "agent_soul.md"
        const val USER_FILE = "agent_user.md"
        const val MEMORY_FILE = "agent_memory.md"

        /** Hermes defaults: ~500 tokens for user, ~800 for memory. Soul matches user. */
        const val MAX_SOUL_CHARS = 1375
        const val MAX_USER_CHARS = 1375
        const val MAX_MEMORY_CHARS = 2200

        const val MAX_BACKUPS = 5

        /** Logical names the model uses in the `memory` tool. */
        const val KEY_SOUL = "soul"
        const val KEY_USER = "user"
        const val KEY_MEMORY = "memory"

        val ALL_FILES = listOf(SOUL_FILE, USER_FILE, MEMORY_FILE)

        fun fileForKey(key: String): String? = when (key) {
            KEY_SOUL -> SOUL_FILE
            KEY_USER -> USER_FILE
            KEY_MEMORY -> MEMORY_FILE
            else -> null
        }

        fun keyForFile(file: String): String = when (file) {
            SOUL_FILE -> KEY_SOUL
            USER_FILE -> KEY_USER
            else -> KEY_MEMORY
        }

        /**
         * Seed content for [SOUL_FILE]. Written once on first launch so the agent
         * always has an identity — an empty soul file would silently fall back to
         * the hardcoded persona and the feature would look broken.
         */
        val DEFAULT_SOUL = listOf(
            "I am AIpaca, an on-device assistant. I run locally; nothing leaves the phone unless the user turns on a network tool.",
            "I answer concisely and say plainly when I am unsure rather than guessing.",
            "I remember what the user tells me about themselves and use it without being asked twice."
        )
    }

    private val dir: File = rootDir.also { it.mkdirs() }

    private val backupDir: File
        get() = File(dir, "backup").also { it.mkdirs() }

    // ---- Reads --------------------------------------------------------------

    /** Raw content of a memory file. Returns "" if not yet created. */
    fun read(file: String): String {
        val f = File(dir, file)
        return if (f.exists()) f.readText() else ""
    }

    fun readEntries(file: String): List<MemoryEntry> = MemoryFormat.parse(read(file))

    // ---- Writes -------------------------------------------------------------

    /** Appends a date-stamped entry, evicting the oldest entries if over the limit. */
    fun add(file: String, entry: String) {
        val text = entry.trim()
        if (text.isBlank()) return
        val entries = readEntries(file) + MemoryEntry(MemoryFormat.today(), text)
        writeEntries(file, entries)
        Log.d(TAG, "add to " + file + ": " + text.take(80))
    }

    /** Replaces the first entry whose text contains [old]. */
    fun replace(file: String, old: String, new: String) {
        val entries = readEntries(file)
        val idx = entries.indexOfFirst { it.text.contains(old) }
        if (idx < 0) {
            Log.w(TAG, "replace: not found in " + file + ": " + old.take(40))
            return
        }
        val updated = entries.toMutableList()
        // A replacement is new information, so it gets today's date — that is what
        // lets the consolidation pass resolve it against older contradicting entries.
        updated[idx] = MemoryEntry(MemoryFormat.today(), new.trim())
        writeEntries(file, updated)
        Log.d(TAG, "replace in " + file)
    }

    /** Removes the first entry whose text contains [text]. */
    fun remove(file: String, text: String) {
        val entries = readEntries(file)
        val idx = entries.indexOfFirst { it.text.contains(text) }
        if (idx < 0) {
            Log.w(TAG, "remove: not found in " + file + ": " + text.take(40))
            return
        }
        writeEntries(file, entries.filterIndexed { i, _ -> i != idx })
        Log.d(TAG, "remove from " + file)
    }

    /** Writes [entries] verbatim, trimming oldest-first if over the file's limit. */
    fun writeEntries(file: String, entries: List<MemoryEntry>) {
        val trimmed = MemoryFormat.trimToLimit(entries, maxCharsFor(file))
        val kept = trimmed.first
        val evicted = trimmed.second
        if (evicted > 0) {
            Log.w(TAG, file + " over limit — evicted " + evicted +
                " oldest entries. Run a consolidation pass to merge instead of losing them.")
        }
        writeRaw(file, MemoryFormat.render(kept))
    }

    /** Overwrites a file with raw content (used by the Memory screen's editor). */
    fun writeRaw(file: String, content: String) {
        File(dir, file).writeText(content)
    }

    /** Writes [DEFAULT_SOUL] if the soul file has never been populated. */
    fun seedSoulIfEmpty() {
        if (read(SOUL_FILE).isNotBlank()) return
        val today = MemoryFormat.today()
        writeRaw(SOUL_FILE, MemoryFormat.render(DEFAULT_SOUL.map { MemoryEntry(today, it) }))
        Log.i(TAG, "seeded default soul.md")
    }

    private fun maxCharsFor(file: String): Int = when (file) {
        SOUL_FILE -> MAX_SOUL_CHARS
        USER_FILE -> MAX_USER_CHARS
        else -> MAX_MEMORY_CHARS
    }

    // ---- Backups ------------------------------------------------------------

    /**
     * Copies every non-empty file into `backup/<file>.<timestamp>`, keeping at most
     * [MAX_BACKUPS] per file. Called before any consolidation pass writes.
     */
    fun snapshot() {
        val stamp = System.currentTimeMillis()
        for (file in ALL_FILES) {
            val content = read(file)
            if (content.isBlank()) continue
            File(backupDir, file + "." + stamp).writeText(content)
        }
        pruneBackups()
    }

    /** Backup timestamps for [file], newest first. */
    fun backupsFor(file: String): List<Long> {
        val prefix = file + "."
        val files = backupDir.listFiles { f: File -> f.name.startsWith(prefix) } ?: return emptyList()
        return files.mapNotNull { it.name.removePrefix(prefix).toLongOrNull() }.sortedDescending()
    }

    /** Restores [file] from its most recent backup. Returns false if there is none. */
    fun restoreLatestBackup(file: String): Boolean {
        val stamp = backupsFor(file).firstOrNull() ?: return false
        val backup = File(backupDir, file + "." + stamp)
        if (!backup.exists()) return false
        writeRaw(file, backup.readText())
        backup.delete()
        Log.i(TAG, "restored " + file + " from backup " + stamp)
        return true
    }

    private fun pruneBackups() {
        for (file in ALL_FILES) {
            backupsFor(file).drop(MAX_BACKUPS).forEach { stamp ->
                File(backupDir, file + "." + stamp).delete()
            }
        }
    }

    // ---- Pending (approval-gated) changes -----------------------------------

    /**
     * Stages a proposed rewrite of [file] without touching the live file.
     *
     * Used for changes the user must see first: soul edits, and any consolidation
     * that would remove a large share of a file. Mirrors the approve/reject/modify
     * step in Anthropic's memory "dreaming" workflow.
     */
    fun writePending(file: String, content: String) {
        File(dir, file + ".pending").writeText(content)
        Log.i(TAG, "staged pending change for " + file + " (" + content.length + " chars)")
    }

    fun readPending(file: String): String? {
        val f = File(dir, file + ".pending")
        return if (f.exists()) f.readText() else null
    }

    fun hasPending(file: String): Boolean = File(dir, file + ".pending").exists()

    fun hasAnyPending(): Boolean = ALL_FILES.any { hasPending(it) }

    /** Applies a staged change (snapshotting the current version first). */
    fun applyPending(file: String): Boolean {
        val pending = readPending(file) ?: return false
        snapshot()
        writeRaw(file, pending)
        discardPending(file)
        Log.i(TAG, "applied pending change for " + file)
        return true
    }

    fun discardPending(file: String) {
        File(dir, file + ".pending").delete()
    }
}
