package com.aipaca.app.ui.memory

import com.aipaca.app.agent.memory.MemoryFormat
import com.aipaca.app.agent.memory.SessionIndexStore
import java.text.SimpleDateFormat
import java.util.Locale

/** One entry as the Memory screen shows it: a date stamp over sentence-case text. */
data class MemoryLine(val stamp: String, val text: String)

/**
 * Turns a memory file into display lines, newest first. Session-index entries
 * read as `title — summary`; malformed lines are skipped rather than shown raw.
 */
fun memoryLines(content: String, sessions: Boolean): List<MemoryLine> =
    MemoryFormat.parse(content).mapNotNull { entry ->
        val text = if (sessions) {
            SessionIndexStore.decode(entry.date, entry.text)?.let { note ->
                listOf(note.title, note.summary).filter { it.isNotBlank() }.joinToString(" — ")
            }
        } else {
            entry.text
        }
        text?.takeIf { it.isNotBlank() }?.let { MemoryLine(stampFor(entry.date), it) }
    }.asReversed()

/** `2026-09-13` → `13 Sep`; undated entries get no stamp. */
fun stampFor(isoDate: String): String {
    if (isoDate.isBlank()) return ""
    return try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate) ?: return isoDate
        SimpleDateFormat("d MMM", Locale.US).format(date)
    } catch (_: Exception) {
        isoDate
    }
}
