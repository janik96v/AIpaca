package com.aipaca.app.agent

import android.util.Log
import com.aipaca.app.EngineState
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.tool.ToolRegistry
import com.aipaca.app.agent.mcp.ToolSpec
import com.aipaca.app.engine.AgentChunk
import com.aipaca.app.engine.AgentResult
import com.aipaca.app.engine.AgentToolCall
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.engine.LlamaCppEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AgentOrchestrator"

/** One step of the agent's think→tool→observe cycle, emitted via Flow for UI/telemetry. */
sealed interface AgentStep {
    /** Streamed token — [partialText] is visible content, [thinkingText] goes into the collapsible thinking block. */
    data class Thinking(val partialText: String, val thinkingText: String = "") : AgentStep
    data class ToolCall(val name: String, val arguments: JsonObject) : AgentStep
    data class ToolObservation(val name: String, val result: ToolResult) : AgentStep
    data class FinalAnswer(val text: String) : AgentStep
    data class Error(val message: String) : AgentStep
}

/**
 * Native tool-calling agent loop (PR1 Increment 3).
 *
 * Uses llama.cpp's Jinja tool templates and native PEG tool-call parser:
 *  - Uses [LlamaCppEngine.generateAgentResult] (native `common_chat_parse`) for
 *    structured tool-call extraction.
 *  - Builds proper [AgentMessage.Assistant] (with `toolCalls`) and [AgentMessage.Tool]
 *    (with `tool_call_id`) turns, so the model's template sees real tool roles.
 *  - Emits [AgentStep]s as a [Flow] for real-time UI updates (streaming thinking,
 *    tool calls, observations, final answer).
 *
 * Serializes all engine calls through [generateMutex] (same lock as
 * [com.aipaca.app.server.ApiServer]).
 */
class AgentOrchestrator(
    private val engine: LlamaCppEngine,
    private val generateMutex: Mutex,
    private val tools: ToolRegistry,
    private val config: AgentConfig = AgentConfig(),
    private val isModelLoaded: () -> Boolean = { engine.isLoaded() }
) {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * Runs the native agent loop for a user [goal], emitting [AgentStep]s as they happen.
     *
     * The flow completes after the model produces a final answer (no tool calls) or
     * after [AgentConfig.maxToolRounds] rounds. Collect the flow to drive the UI.
     */
    fun run(
        goal: String,
        history: List<ChatTurn> = emptyList()
    ): Flow<AgentStep> = flow {
        if (!isModelLoaded()) {
            emit(AgentStep.Error("No model loaded. Load a GGUF model before using the agent."))
            return@flow
        }

        val manifest = tools.manifest()
        val systemPrompt = config.renderSystemPrompt(manifest)

        // Build the message list with proper roles
        val messages = mutableListOf<AgentMessage>()
        messages += AgentMessage.System(systemPrompt)
        for (turn in history) {
            when (turn.role.lowercase()) {
                "system" -> { /* already added above */ }
                "assistant" -> messages += AgentMessage.Assistant(content = turn.content)
                else -> messages += AgentMessage.User(content = turn.content)
            }
        }
        messages += AgentMessage.User(content = goal)

        var round = 0
        while (round < config.maxToolRounds) {
            round++
            Log.d(TAG, "Round $round/${config.maxToolRounds}, messages=${messages.size}")

            // Generate with streaming so the UI gets real-time token feedback
            val result: AgentResult = try {
                generateMutex.withLock {
                    collectAgentStreaming(messages, manifest) { chunk ->
                        if (chunk.content.isNotEmpty() || chunk.thinking.isNotEmpty()) {
                            emit(AgentStep.Thinking(
                                partialText = chunk.content,
                                thinkingText = chunk.thinking
                            ))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed in round $round", e)
                emit(AgentStep.Error(e.message ?: "Generation failed"))
                return@flow
            }

            // No tool calls → check for fake tool output, otherwise final answer
            if (!result.hasToolCalls) {
                // Detect when the model simulates a tool call in plain text instead of
                // emitting proper <|tool_call> tokens (common with small models).
                if (round < config.maxToolRounds && looksLikeFakeToolCall(result.content)) {
                    Log.w(TAG, "Round $round: fake tool call detected, retrying")
                    messages += AgentMessage.Assistant(content = result.content)
                    messages += AgentMessage.User(
                        content = "You must actually call the tool, not simulate it. " +
                            "Use the proper tool_call format to search."
                    )
                    continue
                }
                Log.d(TAG, "Round $round final answer, content length=${result.content.length}, content='${result.content.take(200)}'")
                emit(AgentStep.FinalAnswer(stripToolMarkup(result.content)))
                return@flow
            }

            // Append assistant turn with the raw text including tool-call tokens, so the
            // model sees what it generated and doesn't repeat the same call.
            messages += AgentMessage.Assistant(content = result.rawContent)

            // Execute each tool call, collect results, then feed them back.
            // Use Gemma 4 format (<|tool_response>...<tool_response|>) when the
            // tool call came from the Gemma 4 parser, otherwise Hermes format.
            val isGemma4 = result.toolCalls.any { it.id.startsWith("gemma4_") }
            val toolResponses = StringBuilder()
            for (tc in result.toolCalls) {
                val args = parseArguments(tc.argumentsJson)

                emit(AgentStep.ToolCall(tc.name, args))

                val toolResult: ToolResult = if (!tools.hasTool(tc.name)) {
                    ToolResult(text = "Unknown tool: ${tc.name}", isError = true)
                } else {
                    tools.callTool(tc.name, args)
                }

                emit(AgentStep.ToolObservation(tc.name, toolResult))

                // Truncate large tool results to keep context budget manageable
                // for on-device models (avoids multi-minute prefill on big responses).
                val truncated = if (toolResult.text.length > 2000) {
                    toolResult.text.take(2000) + "\n[...truncated]"
                } else {
                    toolResult.text
                }

                if (isGemma4) {
                    toolResponses.appendLine("<|tool_response>")
                    toolResponses.appendLine(truncated)
                    toolResponses.appendLine("<tool_response|>")
                } else {
                    toolResponses.appendLine("<tool_response>")
                    toolResponses.appendLine(truncated)
                    toolResponses.appendLine("</tool_response>")
                }
            }

            // Feed tool results back as a user message
            val toolResponseText = toolResponses.toString().trim()
            Log.d(TAG, "Tool response size: ${toolResponseText.length} chars")
            messages += AgentMessage.User(content = toolResponseText)
        }

        // Exhausted tool rounds — ask for a final answer without tools
        Log.d(TAG, "Max rounds reached, requesting final answer")
        messages += AgentMessage.User("Give your best final answer now, without calling any more tools.")
        val finalResult = try {
            generateMutex.withLock {
                collectAgentStreaming(messages, manifest) { chunk ->
                    if (chunk.content.isNotEmpty() || chunk.thinking.isNotEmpty()) {
                        emit(AgentStep.Thinking(
                            partialText = chunk.content,
                            thinkingText = chunk.thinking
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            emit(AgentStep.Error(e.message ?: "Final generation failed"))
            return@flow
        }
        emit(AgentStep.FinalAnswer(stripToolMarkup(finalResult.content)))
    }

    /**
     * Collects the streaming [engine.generateAgent] flow, invoking [onChunk] for each
     * streamed token (enabling real-time UI updates), then returns the final [AgentResult].
     */
    private suspend fun collectAgentStreaming(
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
        onChunk: suspend (AgentChunk) -> Unit
    ): AgentResult {
        val contentBuilder = StringBuilder()
        var agentResult: AgentResult? = null

        engine.generateAgent(messages, tools, config.generateParams).collect { chunk ->
            if (chunk.content.startsWith("__AGENT_RESULT__:")) {
                // Sentinel chunk from LlamaCppEngine carrying parsed tool calls
                val resultJson = chunk.content.removePrefix("__AGENT_RESULT__:")
                agentResult = parseAgentResultJson(resultJson)
            } else {
                if (chunk.content.isNotEmpty()) {
                    contentBuilder.append(chunk.content)
                }
                onChunk(chunk)
            }
        }

        // If we got a structured result with tool calls, use it;
        // otherwise try Kotlin-side fallback extraction for models whose
        // output format isn't handled by common_chat_parse (e.g. Gemma 3n).
        val raw = agentResult ?: AgentResult(content = contentBuilder.toString())
        Log.d(TAG, "collectAgentStreaming: agentResult=${agentResult != null}, contentBuilder=${contentBuilder.length} chars, raw.content='${raw.content.take(200)}', raw.toolCalls=${raw.toolCalls.size}")
        val extracted = fallbackExtractToolCalls(raw)
        // Preserve the original raw text so the assistant message in conversation history
        // includes the tool-call tokens (the model needs to see what it generated).
        if (extracted.hasToolCalls && extracted.content != raw.content) {
            return extracted.copy(rawContent = raw.content)
        }
        return extracted
    }

    /** Parse the JSON result string from JNI into an [AgentResult]. */
    private fun parseAgentResultJson(jsonStr: String): AgentResult {
        return try {
            val root = lenientJson.parseToJsonElement(jsonStr).jsonObject
            val content = root["content"]?.jsonPrimitive?.content ?: ""
            val toolCalls = root["tool_calls"]?.jsonArray?.map { elem ->
                val obj = elem.jsonObject
                AgentToolCall(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    argumentsJson = obj["arguments"]?.toString() ?: "{}"
                )
            } ?: emptyList()
            AgentResult(content = content, toolCalls = toolCalls)
        } catch (e: Exception) {
            Log.e(TAG, "parseAgentResultJson failed", e)
            AgentResult(content = jsonStr)
        }
    }

    /**
     * Fallback tool-call extraction for models whose output format isn't handled
     * by llama.cpp's common_chat_parse.
     *
     * Tries multiple strategies in order:
     * 1. Gemma 4 format: `<|tool_call>call:name{key:<|"|>value<|"|>}<tool_call|>`
     * 2. JSON format: `{"name":"...", "arguments":{...}}`
     */
    private fun fallbackExtractToolCalls(result: AgentResult): AgentResult {
        if (result.hasToolCalls) return result // already parsed by native side

        val text = result.content

        // Strategy 1: Gemma 4 format
        val gemmaResult = extractGemma4ToolCalls(text)
        if (gemmaResult != null) return gemmaResult

        // Strategy 2: JSON format (Hermes, Llama, etc.)
        if (!text.contains("\"name\"") || !text.contains("\"arguments\"")) return result

        val toolCalls = mutableListOf<AgentToolCall>()
        val candidates = findJsonObjects(text)
        for (candidate in candidates) {
            try {
                val obj = lenientJson.parseToJsonElement(candidate).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val args = obj["arguments"]?.toString() ?: "{}"
                if (tools.hasTool(name)) {
                    toolCalls.add(AgentToolCall(
                        id = "fallback_${toolCalls.size}",
                        name = name,
                        argumentsJson = args
                    ))
                }
            } catch (_: Exception) { }
        }

        if (toolCalls.isEmpty()) return result

        var cleanContent = text
        for (candidate in candidates) {
            cleanContent = cleanContent.replace(candidate, "")
        }
        cleanContent = stripToolMarkup(cleanContent)

        Log.i(TAG, "Fallback extracted ${toolCalls.size} tool calls from raw content")
        return AgentResult(content = cleanContent, toolCalls = toolCalls)
    }

    /**
     * Parses Gemma 4's tool-call format:
     *   `<|tool_call>call:function_name{key:<|"|>value<|"|>, key2:123}<tool_call|>`
     *
     * The `<|"|>` is a single special token (ID 52) that gets detokenized as the
     * 5-char ASCII sequence `<|"|>`. Keys are unquoted, values use `<|"|>` as string
     * delimiters or are bare numbers/booleans.
     */
    private val GEMMA4_TOOL_CALL = Regex(
        """<\|tool_call>call:(\w+)\{(.*?)\}<tool_call\|>""",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun extractGemma4ToolCalls(text: String): AgentResult? {
        val matches = GEMMA4_TOOL_CALL.findAll(text).toList()
        if (matches.isEmpty()) return null

        val toolCalls = mutableListOf<AgentToolCall>()
        for (match in matches) {
            val name = match.groupValues[1]
            val argsRaw = match.groupValues[2]

            if (!tools.hasTool(name)) continue

            val argsJson = gemma4ArgsToJson(argsRaw)
            toolCalls.add(AgentToolCall(
                id = "gemma4_${toolCalls.size}",
                name = name,
                argumentsJson = argsJson
            ))
        }

        if (toolCalls.isEmpty()) return null

        // Remove matched tool-call text from displayed content
        var cleanContent = text
        for (match in matches) {
            cleanContent = cleanContent.replace(match.value, "")
        }
        cleanContent = stripToolMarkup(cleanContent)

        Log.i(TAG, "Gemma4 fallback extracted ${toolCalls.size} tool calls")
        return AgentResult(content = cleanContent, toolCalls = toolCalls)
    }

    /**
     * Converts Gemma 4's `key:<|"|>value<|"|>, key2:123` arg syntax into a JSON object string.
     *
     * Handles:
     * - String values delimited by `<|"|>`: `query:<|"|>search term<|"|>` → `{"query":"search term"}`
     * - Numeric values: `count:5` → `{"count":5}`
     * - Boolean values: `flag:true` → `{"flag":true}`
     */
    private fun gemma4ArgsToJson(raw: String): String {
        if (raw.isBlank()) return "{}"

        val result = mutableMapOf<String, String>()

        // Parse key-value pairs: key:<|"|>string value<|"|> or key:bare_value
        var i = 0
        while (i < raw.length) {
            // Skip whitespace and commas
            while (i < raw.length && (raw[i] == ' ' || raw[i] == ',' || raw[i] == '\n')) i++
            if (i >= raw.length) break

            // Read key (unquoted identifier)
            val keyStart = i
            while (i < raw.length && raw[i] != ':') i++
            if (i >= raw.length) break
            val key = raw.substring(keyStart, i).trim()
            i++ // skip ':'

            // Skip whitespace after colon
            while (i < raw.length && raw[i] == ' ') i++
            if (i >= raw.length) break

            // Read value
            if (raw.startsWith("<|\"|>", i)) {
                // String value delimited by <|"|>
                i += 5 // skip opening <|"|>
                val valueStart = i
                val endIdx = raw.indexOf("<|\"|>", i)
                if (endIdx == -1) {
                    // No closing delimiter — take rest
                    result[key] = "\"${escapeJsonString(raw.substring(valueStart))}\""
                    break
                }
                result[key] = "\"${escapeJsonString(raw.substring(valueStart, endIdx))}\""
                i = endIdx + 5 // skip closing <|"|>
            } else {
                // Bare value (number, boolean, or undelimited string)
                val valueStart = i
                while (i < raw.length && raw[i] != ',' && raw[i] != '}' && raw[i] != '\n') i++
                val bareValue = raw.substring(valueStart, i).trim()
                // Keep numbers and booleans as-is, quote everything else
                result[key] = when {
                    bareValue == "true" || bareValue == "false" -> bareValue
                    bareValue == "null" -> "null"
                    bareValue.toDoubleOrNull() != null -> bareValue
                    else -> "\"${escapeJsonString(bareValue)}\""
                }
            }
        }

        return buildString {
            append("{")
            result.entries.forEachIndexed { idx, (k, v) ->
                if (idx > 0) append(",")
                append("\"$k\":$v")
            }
            append("}")
        }
    }

    private fun escapeJsonString(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /** Find top-level JSON objects in text by matching balanced braces. */
    private fun findJsonObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '{') {
                var depth = 1
                var j = i + 1
                while (j < text.length && depth > 0) {
                    when (text[j]) {
                        '{' -> depth++
                        '}' -> depth--
                        '"' -> {
                            // Skip string content
                            j++
                            while (j < text.length && text[j] != '"') {
                                if (text[j] == '\\') j++ // skip escaped char
                                j++
                            }
                        }
                    }
                    j++
                }
                if (depth == 0) {
                    val candidate = text.substring(i, j)
                    if (candidate.contains("\"name\"") && candidate.contains("\"arguments\"")) {
                        results.add(candidate)
                    }
                }
            }
            i++
        }
        return results
    }

    /**
     * Detects when the model simulates a tool call in plain text instead of emitting
     * proper tool-call tokens. Common with small instruction-tuned models like Gemma 4 1B.
     */
    private fun looksLikeFakeToolCall(text: String): Boolean {
        val fakePatterns = listOf(
            "\uD83D\uDD0D",        // 🔍
            "*Searching:",
            "<tool_response>",      // Hermes-style fake in plain content
            "<|tool_response>",     // Gemma-style fake in plain content
        )
        return fakePatterns.any { text.contains(it) }
    }

    /** Strip raw tool-call/response markup that leaks when common_chat_parse fails. */
    private fun stripToolMarkup(text: String): String {
        var cleaned = text
        // Gemma 4 special tokens
        cleaned = cleaned.replace("<|tool_call>", "")
        cleaned = cleaned.replace("<tool_call|>", "")
        cleaned = cleaned.replace("<|tool_response>", "")
        cleaned = cleaned.replace("<tool_response|>", "")
        // Hermes / generic tags
        cleaned = cleaned.replace(Regex("</?tool_call>"), "")
        cleaned = cleaned.replace(Regex("</?tool_response>"), "")
        // Remove <|python_tag|> markers
        cleaned = cleaned.replace(Regex("<\\|python_tag\\|>"), "")
        // Remove [TOOL_CALLS] markers (Mistral style)
        cleaned = cleaned.replace(Regex("\\[TOOL_CALLS\\]"), "")
        // Remove ```json / ``` code fence wrappers
        cleaned = cleaned.replace(Regex("```json\\s*"), "")
        cleaned = cleaned.replace(Regex("```\\s*"), "")
        // Remove fake tool interaction text (emoji + markdown formatting)
        cleaned = cleaned.replace(Regex("\uD83D\uDD0D[^\n]*\n?"), "")  // 🔍 lines
        cleaned = cleaned.replace(Regex("\u274C[^\n]*\n?"), "")        // ❌ lines
        cleaned = cleaned.replace(Regex("\\*Searching:[^*]*\\*"), "")
        return cleaned.trim()
    }

    private fun parseArguments(raw: String): JsonObject {
        return try {
            lenientJson.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject
                ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
    }
}

/**
 * Builds an [AgentOrchestrator] wired to the process-wide [EngineState] singleton.
 * Use from production call sites; tests construct [AgentOrchestrator] directly with fakes.
 */
fun EngineState.newAgentOrchestrator(
    tools: ToolRegistry,
    config: AgentConfig = AgentConfig()
): AgentOrchestrator = AgentOrchestrator(
    engine = engine,
    generateMutex = generateMutex,
    tools = tools,
    config = config,
    isModelLoaded = { isLoaded.value }
)
