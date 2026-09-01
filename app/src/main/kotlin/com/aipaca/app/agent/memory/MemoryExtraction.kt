package com.aipaca.app.agent.memory

/** Which memory file an extracted fact belongs in. */
enum class MemoryTarget { USER, MEMORY }

/** One fact the model proposed writing to memory. */
data class ExtractedFact(val target: MemoryTarget, val text: String)

/**
 * Prompt and parser for the extraction pass (loop L1.5).
 *
 * The pass deliberately does **not** use tool calling. Extraction runs on every
 * model the app can load, including ones with no tool-calling chat template at all,
 * and small models are markedly more reliable at emitting a few constrained lines
 * than at emitting well-formed tool calls. Asking for line-prefixed output and
 * parsing it deterministically here removes the whole failure mode — and lets the
 * pass run through plain `generateChat`, which both the local and the Ollama
 * backend implement, so it no longer depends on which engine is active.
 *
 * Everything in this object is pure, so the parsing rules are unit-testable.
 */
object MemoryExtraction {

    /** Never write more than this many facts from a single pass. */
    const val MAX_FACTS_PER_PASS = 4

    const val MAX_FACT_CHARS = 160

    /** Two facts are treated as the same when their word overlap reaches this. */
    const val DUPLICATE_JACCARD = 0.6

    private val LINE = Regex("""^\s*(?:[-*]\s*)?(USER|FACT)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * Builds the extraction prompt. [userMemory] and [factMemory] are included so the
     * model can avoid restating what is already stored — the deterministic duplicate
     * filter in [parse] is the backstop, not the first line of defence.
     */
    fun prompt(digest: String, userMemory: String, factMemory: String): String = buildString {
        appendLine("Read the conversation below and extract lasting facts worth remembering.")
        appendLine()
        appendLine("Answer with at most " + MAX_FACTS_PER_PASS + " lines, each in exactly one of these forms:")
        appendLine("USER: <one sentence about the user — their name, preferences, situation, working style>")
        appendLine("FACT: <one sentence about their environment, projects, tools or conventions>")
        appendLine()
        appendLine("Rules:")
        appendLine("- One short sentence per line. No explanations, no numbering, no other text.")
        appendLine("- Only lasting facts. Never one-off task details, error messages or things that failed.")
        appendLine("- Never repeat something already listed under \"Already known\".")
        appendLine("- If there is nothing worth saving, answer with the single word NONE.")
        appendLine()
        if (userMemory.isNotBlank() || factMemory.isNotBlank()) {
            appendLine("## Already known")
            if (userMemory.isNotBlank()) appendLine(userMemory)
            if (factMemory.isNotBlank()) appendLine(factMemory)
            appendLine()
        }
        appendLine("## Conversation")
        append(digest)
    }

    /**
     * Parses the model's answer into facts, dropping anything unusable:
     * malformed lines, poisoned content, and duplicates of what is already stored
     * or of another line in the same answer.
     */
    fun parse(
        raw: String,
        existingUser: List<String> = emptyList(),
        existingMemory: List<String> = emptyList()
    ): List<ExtractedFact> {
        if (raw.isBlank()) return emptyList()
        val accepted = mutableListOf<ExtractedFact>()
        for (line in raw.lines()) {
            if (accepted.size >= MAX_FACTS_PER_PASS) break
            val match = LINE.matchEntire(line.trim()) ?: continue
            val target = if (match.groupValues[1].uppercase() == "USER") MemoryTarget.USER else MemoryTarget.MEMORY
            val text = cleanup(match.groupValues[2])
            if (text.isBlank()) continue
            if (AntiPoisoning.isPoisoned(text)) continue
            val existing = if (target == MemoryTarget.USER) existingUser else existingMemory
            val alreadyAccepted = accepted.filter { it.target == target }.map { it.text }
            if ((existing + alreadyAccepted).any { isDuplicate(text, it) }) continue
            accepted += ExtractedFact(target, text)
        }
        return accepted
    }

    private fun cleanup(value: String): String =
        value.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FACT_CHARS)

    /** Word-overlap duplicate check — cheap, language-agnostic, no embeddings needed. */
    fun isDuplicate(a: String, b: String): Boolean {
        val left = tokenize(a)
        val right = tokenize(b)
        if (left.isEmpty() || right.isEmpty()) return false
        val intersection = left.intersect(right).size.toDouble()
        val union = left.union(right).size.toDouble()
        return intersection / union >= DUPLICATE_JACCARD
    }

    private fun tokenize(value: String): Set<String> =
        value.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 }
            .toSet()
}
