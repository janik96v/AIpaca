package com.aipaca.app.agent.memory

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One entry in a memory file (`soul.md` / `user.md` / `memory.md`).
 *
 * On disk entries are separated by `§` (the convention inherited from Hermes Agent)
 * and carry an ISO date prefix:
 *
 * ```
 * § 2026-09-01 | Janik prefers Kotlin over Java
 * § 2026-09-02 | Janik moved from Zürich to Bern
 * ```
 *
 * The date is what makes contradiction handling decidable without a temporal
 * knowledge graph: the consolidation pass is told "when two entries conflict,
 * keep the newer one" and can act on it (see [MemoryConsolidation]).
 *
 * Entries written before dates existed parse with an empty [date] and are
 * treated as oldest — no migration needed.
 */
data class MemoryEntry(
    val date: String,
    val text: String
) {
    /** Serialized form without the leading separator. */
    fun render(): String = if (date.isBlank()) text else "$date | $text"
}

/**
 * Parsing and rendering of the `§`-separated memory file format.
 *
 * Kept separate from [MemoryStore] so it stays pure and unit-testable without
 * touching the filesystem.
 */
object MemoryFormat {

    const val SEPARATOR = "\n§ "

    private val DATED = Regex("""^(\d{4}-\d{2}-\d{2})\s*\|\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

    /** Today as `yyyy-MM-dd`, the stamp put on newly written entries. */
    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Splits raw file content into entries. Tolerates legacy undated entries. */
    fun parse(content: String): List<MemoryEntry> {
        if (content.isBlank()) return emptyList()
        return content
            .split(SEPARATOR)
            .map { it.trim().removePrefix("§").trim() }
            .filter { it.isNotBlank() }
            .map { raw ->
                val match = DATED.matchEntire(raw)
                if (match != null) {
                    MemoryEntry(date = match.groupValues[1], text = match.groupValues[2].trim())
                } else {
                    MemoryEntry(date = "", text = raw)
                }
            }
    }

    /** Renders entries back into file content. The first entry carries no separator. */
    fun render(entries: List<MemoryEntry>): String =
        entries.filter { it.text.isNotBlank() }.joinToString(SEPARATOR) { it.render() }

    /**
     * Drops entries from the front (oldest first) until the rendered content fits
     * [maxChars]. Returns the kept entries plus how many were evicted.
     *
     * Eviction is a last resort — the consolidation loop ([MemoryConsolidation]) is
     * the intended way to keep files small, because it merges instead of discarding.
     */
    fun trimToLimit(entries: List<MemoryEntry>, maxChars: Int): Pair<List<MemoryEntry>, Int> {
        var kept = entries
        var evicted = 0
        while (kept.size > 1 && render(kept).length > maxChars) {
            kept = kept.drop(1)
            evicted++
        }
        return kept to evicted
    }
}
