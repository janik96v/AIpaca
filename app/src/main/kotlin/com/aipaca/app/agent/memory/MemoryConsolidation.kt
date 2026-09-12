package com.aipaca.app.agent.memory

/** One edit the consolidation pass proposed against a numbered memory file. */
sealed interface ConsolidationOp {
    /** Remove entry [index] (1-based) — obsolete or contradicted. */
    data class Drop(val index: Int) : ConsolidationOp
    /** Rewrite entry [index] (1-based). */
    data class Edit(val index: Int, val text: String) : ConsolidationOp
    /** Replace all [indices] (1-based) with a single combined entry. */
    data class Merge(val indices: List<Int>, val text: String) : ConsolidationOp
    /** Append a new entry. */
    data class Add(val text: String) : ConsolidationOp
}

/**
 * Prompt, parser and applier for the consolidation pass (loop L3, the "dream").
 *
 * Extraction and consolidation are deliberately separate passes. Extraction is
 * cheap, append-biased and runs often; consolidation is expensive, rewrite-biased
 * and runs rarely. Asking a small model to do both in one prompt is exactly what
 * produces duplicated, self-contradictory memory files — the pattern Mem0's
 * ADD/UPDATE/DELETE/NOOP decision step and Anthropic's memory "dreaming" both
 * avoid by treating curation as its own operation over the whole store.
 *
 * Like [MemoryExtraction] this uses line-structured text rather than tool calls,
 * so it works on every model and both backends. All parsing and application is
 * pure and unit-testable; only the generation call lives outside.
 */
object MemoryConsolidation {

    const val MAX_ENTRY_CHARS = 160

    /**
     * A pass that would remove more than this share of a file is staged as a
     * pending proposal instead of applied — a small model collapsing a whole
     * memory file into two lines is a real failure mode, and silent data loss
     * is the one outcome worth blocking on.
     */
    const val PENDING_DELETION_RATIO = 0.30

    private val DROP = Regex("""^\s*DROP\s+(\d+)\s*$""", RegexOption.IGNORE_CASE)
    private val EDIT = Regex("""^\s*EDIT\s+(\d+)\s*->\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val MERGE = Regex("""^\s*MERGE\s+([\d\s,]+?)\s*->\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val ADD = Regex("""^\s*ADD\s+(.+)$""", RegexOption.IGNORE_CASE)

    /** Renders the current file as a numbered list for the prompt. */
    fun numbered(entries: List<MemoryEntry>): String =
        entries.mapIndexed { i, e ->
            (i + 1).toString() + ". " + (if (e.date.isBlank()) "" else "[" + e.date + "] ") + e.text
        }.joinToString("\n")

    fun prompt(label: String, entries: List<MemoryEntry>): String = buildString {
        appendLine("Here is everything currently stored in " + label + ", one numbered entry per line.")
        appendLine("Each entry carries the date it was written.")
        appendLine()
        appendLine(numbered(entries))
        appendLine()
        appendLine("Clean this list up. Answer only with commands, one per line:")
        appendLine("DROP <n>              remove an entry that is obsolete or contradicted")
        appendLine("EDIT <n> -> <text>    rewrite an entry more clearly")
        appendLine("MERGE <n>,<m> -> <text>   replace duplicates with one combined entry")
        appendLine("ADD <text>            add something that follows from the entries but is missing")
        appendLine()
        appendLine("Rules:")
        appendLine("- When two entries contradict each other, keep the one with the NEWER date and DROP the older.")
        appendLine("- Merge entries that say the same thing in different words.")
        appendLine("- Drop one-off task details; keep lasting facts.")
        appendLine("- Entries you do not mention are kept unchanged. Do not rewrite things that are already fine.")
        appendLine("- One short sentence per entry. No explanations.")
        appendLine("- If the list is already clean, answer with the single word NONE.")
    }

    /** Parses the model's answer. Malformed and poisoned lines are dropped silently. */
    fun parse(raw: String): List<ConsolidationOp> {
        if (raw.isBlank()) return emptyList()
        val ops = mutableListOf<ConsolidationOp>()
        for (line in raw.lines()) {
            val trimmed = line.trim().removePrefix("-").removePrefix("*").trim()
            if (trimmed.isBlank()) continue

            val drop = DROP.matchEntire(trimmed)
            if (drop != null) {
                drop.groupValues[1].toIntOrNull()?.let { ops += ConsolidationOp.Drop(it) }
                continue
            }

            val merge = MERGE.matchEntire(trimmed)
            if (merge != null) {
                val indices = merge.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
                val text = clean(merge.groupValues[2])
                if (indices.size >= 2 && text.isNotBlank() && !AntiPoisoning.isPoisoned(text)) {
                    ops += ConsolidationOp.Merge(indices, text)
                }
                continue
            }

            val edit = EDIT.matchEntire(trimmed)
            if (edit != null) {
                val index = edit.groupValues[1].toIntOrNull()
                val text = clean(edit.groupValues[2])
                if (index != null && text.isNotBlank() && !AntiPoisoning.isPoisoned(text)) {
                    ops += ConsolidationOp.Edit(index, text)
                }
                continue
            }

            val add = ADD.matchEntire(trimmed)
            if (add != null) {
                val text = clean(add.groupValues[1])
                if (text.isNotBlank() && !AntiPoisoning.isPoisoned(text)) {
                    ops += ConsolidationOp.Add(text)
                }
            }
        }
        return ops
    }

    private fun clean(value: String): String =
        value.trim().removeSurrounding("\"").replace(Regex("\\s+"), " ").trim().take(MAX_ENTRY_CHARS)

    /**
     * Applies [ops] to [entries], preserving original order.
     *
     * Entries the model did not mention survive untouched — the default is to keep,
     * not to require an explicit KEEP, so a truncated answer can never wipe the file.
     * Out-of-range indices are ignored.
     */
    fun apply(entries: List<MemoryEntry>, ops: List<ConsolidationOp>, today: String): List<MemoryEntry> {
        val valid = 1..entries.size
        val dropped = mutableSetOf<Int>()
        val edits = mutableMapOf<Int, String>()
        // Merges are anchored at their lowest index so the combined entry keeps its place.
        val mergeAnchor = mutableMapOf<Int, String>()
        val additions = mutableListOf<String>()

        for (op in ops) {
            when (op) {
                is ConsolidationOp.Drop -> if (op.index in valid) dropped += op.index
                is ConsolidationOp.Edit -> if (op.index in valid) edits[op.index] = op.text
                is ConsolidationOp.Merge -> {
                    val indices = op.indices.filter { it in valid }.distinct().sorted()
                    if (indices.size >= 2) {
                        mergeAnchor[indices.first()] = op.text
                        indices.drop(1).forEach { dropped += it }
                    }
                }
                is ConsolidationOp.Add -> additions += op.text
            }
        }

        val result = mutableListOf<MemoryEntry>()
        entries.forEachIndexed { zeroBased, entry ->
            val index = zeroBased + 1
            when {
                mergeAnchor.containsKey(index) -> result += MemoryEntry(today, mergeAnchor.getValue(index))
                dropped.contains(index) -> Unit
                edits.containsKey(index) -> result += MemoryEntry(today, edits.getValue(index))
                else -> result += entry
            }
        }
        additions.forEach { result += MemoryEntry(today, it) }
        return result
    }

    /** True when the change is large enough that the user should approve it first. */
    fun needsApproval(before: List<MemoryEntry>, after: List<MemoryEntry>): Boolean {
        if (before.isEmpty()) return false
        val removed = before.size - after.size
        if (removed <= 0) return false
        return removed.toDouble() / before.size > PENDING_DELETION_RATIO
    }
}
