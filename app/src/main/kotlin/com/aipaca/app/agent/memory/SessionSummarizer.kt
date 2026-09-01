package com.aipaca.app.agent.memory

import android.util.Log

private const val TAG = "SessionSummarizer"

/**
 * Loop L2 — one sentence per finished conversation, written into the session index.
 *
 * This is what makes `memory.md` cheap enough to keep in the prompt: the agent gets
 * a scannable list of what it has talked about before instead of having to search
 * blind, and only pulls a full transcript when a line looks relevant.
 *
 * One short completion per conversation, run once when the conversation is left.
 */
object SessionSummarizer {

    /** Below this a conversation is too thin to be worth an index line. */
    const val MIN_MESSAGES = 4

    const val MAX_INPUT_CHARS = 3000

    private const val SYSTEM_PROMPT =
        "You summarize conversations in a single short sentence. You never add preamble or quotes."

    /**
     * Produces the one-line summary, or null when the model returned nothing usable.
     * The caller is responsible for only calling this once per conversation.
     */
    suspend fun summarize(digest: String, engine: MemoryEngine): String? {
        if (digest.isBlank()) return null
        val raw = engine.complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildString {
                appendLine("Summarize this conversation in ONE sentence of at most " +
                    SessionIndexStore.MAX_SUMMARY_CHARS + " characters.")
                appendLine("Name the topic and how it ended. No preamble, no quotes, no bullet points.")
                appendLine()
                append(digest.take(MAX_INPUT_CHARS))
            },
            maxTokens = 96
        )
        val summary = clean(raw)
        if (summary.isBlank()) {
            Log.w(TAG, "summarizer returned nothing usable")
            return null
        }
        return summary
    }

    /** Takes the first non-empty line and strips the decorations small models add. */
    fun clean(raw: String): String {
        val line = raw.lines().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return ""
        return line
            .removePrefix("-").removePrefix("*").trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removePrefix("Summary:").removePrefix("summary:")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(SessionIndexStore.MAX_SUMMARY_CHARS)
    }
}
