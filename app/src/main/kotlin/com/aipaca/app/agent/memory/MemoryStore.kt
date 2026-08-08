package com.aipaca.app.agent.memory

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "MemoryStore"

/**
 * Filesystem-backed storage for two bounded plaintext memory files:
 * - [MEMORY_FILE] — environment facts, project conventions, corrections
 * - [USER_FILE] — user preferences, communication style, name
 *
 * Entries are separated by the `§` character (same convention as Hermes Agent).
 * Files are stored unencrypted in app-internal storage (`filesDir/agent_memory/`).
 *
 * Limits match Hermes defaults: ~800 tokens for memory, ~500 tokens for user.
 * When a file exceeds its char limit after an add, oldest entries are trimmed (FIFO).
 */
class MemoryStore(private val context: Context) {

    companion object {
        const val MEMORY_FILE = "agent_memory.md"
        const val USER_FILE = "agent_user.md"
        const val MAX_MEMORY_CHARS = 2200   // Hermes default: ~800 tokens
        const val MAX_USER_CHARS = 1375     // Hermes default: ~500 tokens
        private const val SEPARATOR = "\n§ "
    }

    private val dir: File
        get() = File(context.filesDir, "agent_memory").also { it.mkdirs() }

    /** Read the full content of a memory file. Returns "" if not yet created. */
    fun read(file: String): String {
        val f = File(dir, file)
        return if (f.exists()) f.readText() else ""
    }

    /** Append an entry. Trims oldest entries (FIFO) if over char limit. */
    fun add(file: String, entry: String) {
        val trimmedEntry = entry.trim()
        if (trimmedEntry.isBlank()) return

        val existing = read(file)
        val newContent = if (existing.isBlank()) trimmedEntry
                         else existing + SEPARATOR + trimmedEntry

        val maxChars = maxCharsFor(file)
        val trimmed = trimToLimit(newContent, maxChars)
        write(file, trimmed)
        Log.d(TAG, "add to $file: ${trimmedEntry.take(80)}... (total ${trimmed.length} chars)")
    }

    /** Replace first occurrence of [old] with [new] (substring match). */
    fun replace(file: String, old: String, new: String) {
        val content = read(file)
        if (!content.contains(old)) {
            Log.w(TAG, "replace: '$old' not found in $file")
            return
        }
        val replaced = content.replaceFirst(old, new)
        val maxChars = maxCharsFor(file)
        write(file, trimToLimit(replaced, maxChars))
        Log.d(TAG, "replace in $file: '${old.take(40)}' → '${new.take(40)}'")
    }

    /** Remove first occurrence of [text] (substring match). */
    fun remove(file: String, text: String) {
        val content = read(file)
        if (!content.contains(text)) {
            Log.w(TAG, "remove: '$text' not found in $file")
            return
        }
        var removed = content.replaceFirst(text, "")
        // Clean up orphaned separators
        removed = removed.replace(Regex("(§\\s*){2,}"), SEPARATOR)
        removed = removed.removePrefix(SEPARATOR.trimStart()).removeSuffix(SEPARATOR.trimEnd())
        write(file, removed.trim())
        Log.d(TAG, "remove from $file: '${text.take(40)}'")
    }

    private fun maxCharsFor(file: String): Int = when (file) {
        MEMORY_FILE -> MAX_MEMORY_CHARS
        USER_FILE -> MAX_USER_CHARS
        else -> MAX_MEMORY_CHARS
    }

    /** Trim oldest entries (by removing from the start) until under [maxChars]. */
    private fun trimToLimit(content: String, maxChars: Int): String {
        if (content.length <= maxChars) return content
        val entries = content.split(SEPARATOR).toMutableList()
        while (entries.size > 1 && entries.joinToString(SEPARATOR).length > maxChars) {
            entries.removeFirst()
        }
        return entries.joinToString(SEPARATOR).take(maxChars)
    }

    private fun write(file: String, content: String) {
        File(dir, file).writeText(content)
    }
}
