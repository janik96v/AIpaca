package com.aipaca.app.agent.memory

import android.util.Log

private const val TAG = "LearnPass"

/**
 * Loop L1.5 — extraction. Runs after the user already has their answer and pulls
 * lasting facts about the user and their environment out of the recent conversation.
 *
 * Three things changed relative to the first version of this pass, all of which
 * were the reason it almost never ran:
 *
 * 1. It no longer lives on the agent path, so it fires for every conversation
 *    rather than only when a Tavily key happens to be configured.
 * 2. It no longer hardcodes the local engine — it takes a [MemoryEngine], so it
 *    works with the Ollama backend too.
 * 3. It no longer drives the tool-calling orchestrator. It asks for line-structured
 *    text and parses it deterministically ([MemoryExtraction]), which works on
 *    models that have no tool-calling template at all and removes the malformed
 *    tool-call failure mode entirely.
 *
 * Counters still gate it, not an LLM judge: a judge costs a forward pass every
 * single turn just to decide whether to spend a forward pass.
 */
object LearnPass {

    /** User turns between extraction passes. */
    const val MEMORY_REVIEW_THRESHOLD = 10

    /** Messages of conversation handed to the pass — a digest, not a replay. */
    const val DIGEST_MESSAGES = 8

    const val MAX_DIGEST_CHARS = 4000

    private const val SYSTEM_PROMPT =
        "You extract durable facts from conversations. You answer only in the requested " +
        "line format, never in prose."

    /** What a pass did, for logging and for the Memory screen's status line. */
    data class Result(val userFacts: Int, val memoryFacts: Int) {
        val total: Int get() = userFacts + memoryFacts
    }

    /**
     * Runs one extraction pass and writes whatever survives filtering.
     *
     * @param digest recent conversation as `role: text` lines
     */
    suspend fun run(
        digest: String,
        store: MemoryStore,
        engine: MemoryEngine
    ): Result {
        if (digest.isBlank()) return Result(0, 0)

        val existingUser = store.readEntries(MemoryStore.USER_FILE).map { it.text }
        val existingMemory = store.readEntries(MemoryStore.MEMORY_FILE).map { it.text }

        val raw = engine.complete(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = MemoryExtraction.prompt(
                digest = digest.take(MAX_DIGEST_CHARS),
                userMemory = existingUser.joinToString("\n") { "- " + it },
                factMemory = existingMemory.joinToString("\n") { "- " + it }
            ),
            maxTokens = 256
        )
        if (raw.isBlank()) {
            Log.w(TAG, "extraction produced no output")
            return Result(0, 0)
        }

        val facts = MemoryExtraction.parse(raw, existingUser, existingMemory)
        if (facts.isEmpty()) {
            Log.i(TAG, "nothing worth saving")
            return Result(0, 0)
        }

        var user = 0
        var memory = 0
        for (fact in facts) {
            when (fact.target) {
                MemoryTarget.USER -> { store.add(MemoryStore.USER_FILE, fact.text); user++ }
                MemoryTarget.MEMORY -> { store.add(MemoryStore.MEMORY_FILE, fact.text); memory++ }
            }
        }
        Log.i(TAG, "stored " + user + " user facts, " + memory + " environment facts")
        return Result(user, memory)
    }

    /** Renders chat messages into the `role: text` digest the prompt expects. */
    fun buildDigest(messages: List<Pair<String, String>>): String =
        messages.takeLast(DIGEST_MESSAGES)
            .filter { it.second.isNotBlank() }
            .joinToString("\n\n") { it.first + ": " + it.second.take(600) }
}
