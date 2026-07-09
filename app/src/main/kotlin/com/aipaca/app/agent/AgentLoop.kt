package com.aipaca.app.agent

import android.util.Log
import com.aipaca.app.EngineState
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.tool.ToolRegistry
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.engine.InferenceEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

private const val TAG = "AgentLoop"

/** One step of the agent's think→tool→observe cycle, useful for UI/telemetry. */
sealed interface AgentStep {
    data class Thinking(val partialText: String) : AgentStep
    data class ToolCall(val name: String, val arguments: JsonObject) : AgentStep
    data class ToolObservation(val name: String, val result: ToolResult) : AgentStep
    data class FinalAnswer(val text: String) : AgentStep
    data class Error(val message: String) : AgentStep
}

/**
 * Native, in-process agent loop: think (LLM) → tool call → execute (MCP) → observe → repeat.
 *
 * Reuses the single engine directly via [InferenceEngine.generateChat] — **no** localhost HTTP
 * hop through [com.aipaca.app.server.ApiServer] (spec_issue_43_agent_mode.md §6.1, Non-Goal).
 * Generation is serialized through [generateMutex], the same lock
 * [com.aipaca.app.server.ApiServer] uses (via [EngineState.generateMutex] in production), so the
 * agent and the server never decode concurrently against the one llama.cpp context.
 *
 * Depends on [InferenceEngine] (not the [EngineState] singleton directly) plus a plain
 * `isModelLoaded` callback so it can be unit-tested against a fake engine — see
 * `AgentLoopTest`. [EngineState.newAgentLoop] wires up the production singletons.
 *
 * Brick 1 (#42) needs at most one tool round; this loop generalises that to up to
 * [AgentConfig.maxToolRounds] rounds (#43 Brick 2) using [ToolCallParser] for the Kotlin-side
 * detection of native tool-call tokens (Hermes 2 Pro / Mistral Nemo formats).
 */
class AgentLoop(
    private val engine: InferenceEngine,
    private val generateMutex: Mutex,
    private val tools: ToolRegistry,
    private val config: AgentConfig = AgentConfig(),
    private val isModelLoaded: () -> Boolean = { engine.isLoaded() }
) {

    /**
     * Runs the agent loop for a single user [goal], given the prior conversation [history].
     * Returns the final list of steps taken, ending in [AgentStep.FinalAnswer] on success
     * or [AgentStep.Error] if generation failed or no model is loaded.
     */
    suspend fun run(goal: String, history: List<ChatTurn> = emptyList()): List<AgentStep> {
        if (!isModelLoaded()) {
            return listOf(AgentStep.Error("No model loaded. Load a GGUF model before using the agent."))
        }

        val steps = mutableListOf<AgentStep>()
        val manifest = tools.manifest()
        val systemPrompt = config.renderSystemPrompt(manifest)

        val turns = mutableListOf<ChatTurn>().apply {
            add(ChatTurn("system", systemPrompt))
            addAll(history)
            add(ChatTurn("user", goal))
        }

        var round = 0
        while (round < config.maxToolRounds) {
            round++

            val generated = try {
                generateOnce(turns)
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed in agent loop", e)
                steps += AgentStep.Error(e.message ?: "Generation failed")
                return steps
            }
            steps += AgentStep.Thinking(generated)

            val toolCall = ToolCallParser.parse(generated)
            if (toolCall == null) {
                // No tool call detected — treat the generated text as the final answer.
                steps += AgentStep.FinalAnswer(generated)
                return steps
            }

            if (!tools.hasTool(toolCall.name)) {
                // Model hallucinated a tool name; feed that back so it can self-correct
                // instead of silently failing the whole turn.
                val errorObservation = ToolResult(text = "Unknown tool: ${toolCall.name}", isError = true)
                steps += AgentStep.ToolCall(toolCall.name, toolCall.arguments)
                steps += AgentStep.ToolObservation(toolCall.name, errorObservation)
                turns += ChatTurn("assistant", generated)
                turns += ChatTurn("user", "Tool error: ${errorObservation.text}")
                continue
            }

            steps += AgentStep.ToolCall(toolCall.name, toolCall.arguments)
            val result = tools.callTool(toolCall.name, toolCall.arguments)
            steps += AgentStep.ToolObservation(toolCall.name, result)

            // Feed the tool result back into the context and let the model produce
            // either a final grounded answer or another tool call.
            turns += ChatTurn("assistant", generated)
            turns += ChatTurn("user", "Tool result for ${toolCall.name}: ${result.text}")
        }

        // Ran out of tool rounds — ask for one last untooled answer using what we have.
        val finalText = try {
            generateOnce(turns + ChatTurn("user", "Give your best final answer now, without calling any more tools."))
        } catch (e: Exception) {
            steps += AgentStep.Error(e.message ?: "Final generation failed")
            return steps
        }
        steps += AgentStep.FinalAnswer(finalText)
        return steps
    }

    /** Runs one generateChat pass under the shared engine mutex and collects the full text. */
    private suspend fun generateOnce(turns: List<ChatTurn>): String {
        val builder = StringBuilder()
        generateMutex.withLock {
            engine.generateChat(turns, config.generateParams).collect { chunk ->
                builder.append(chunk.content)
            }
        }
        return builder.toString()
    }
}

/**
 * Builds an [AgentLoop] wired to the process-wide [EngineState] singleton (real engine,
 * shared [EngineState.generateMutex]). Use this from production call sites (UI / services);
 * tests construct [AgentLoop] directly with fakes instead.
 */
fun EngineState.newAgentLoop(tools: ToolRegistry, config: AgentConfig = AgentConfig()): AgentLoop =
    AgentLoop(
        engine = engine,
        generateMutex = generateMutex,
        tools = tools,
        config = config,
        isModelLoaded = { isLoaded.value }
    )
