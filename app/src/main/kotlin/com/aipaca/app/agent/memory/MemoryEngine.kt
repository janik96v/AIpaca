package com.aipaca.app.agent.memory

import android.util.Log
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.engine.GenerateParams
import com.aipaca.app.engine.GenerationChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MemoryEngine"

/**
 * The only generation surface the memory passes need: one system prompt in,
 * one block of text out.
 *
 * Deliberately not tied to a concrete engine. The local llama.cpp engine and the
 * Ollama backend both expose `generateChat(turns, params): Flow<GenerationChunk>`
 * but share no interface, and the previous learn pass hardcoded `LlamaCppEngine`
 * — which meant it silently failed whenever the remote backend was the active one.
 * Injecting the call keeps the passes backend-agnostic and testable with a fake.
 *
 * @param lock the shared generation mutex for the local engine (llama.cpp has one
 *        context and concurrent decodes corrupt native state). Pass null for
 *        stateless HTTP backends.
 */
class MemoryEngine(
    private val generate: (List<ChatTurn>, GenerateParams) -> Flow<GenerationChunk>,
    private val lock: Mutex?
) {

    /** Runs one non-streaming completion and returns the assistant text. */
    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.2f
    ): String {
        val turns = listOf(
            ChatTurn(role = "system", content = systemPrompt),
            ChatTurn(role = "user", content = userPrompt)
        )
        val params = GenerateParams(
            temperature = temperature,
            maxTokens = maxTokens,
            // Memory passes are extraction, not reasoning — thinking tokens would
            // burn most of the budget before any answer appears.
            thinkingEnabled = false
        )
        val builder = StringBuilder()
        try {
            if (lock != null) {
                lock.withLock { collectInto(builder, turns, params) }
            } else {
                collectInto(builder, turns, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "completion failed", e)
            return ""
        }
        return builder.toString().trim()
    }

    private suspend fun collectInto(
        builder: StringBuilder,
        turns: List<ChatTurn>,
        params: GenerateParams
    ) {
        generate(turns, params).collect { chunk: GenerationChunk ->
            if (chunk.content.isNotEmpty()) builder.append(chunk.content)
        }
    }
}
