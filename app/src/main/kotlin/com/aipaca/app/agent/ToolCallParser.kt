package com.aipaca.app.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A single tool call parsed out of the model's raw generated text. */
@Serializable
data class ParsedToolCall(
    val name: String,
    val arguments: JsonObject
)

/**
 * Parses native tool-call tokens emitted by the two handlers AIpaca targets
 * (see research/10_research_mcp_kotlin.md and spec_issue_42_web_search_mcp.md §5.4):
 *
 *  - **Hermes 2 Pro** (Qwen 2.5 family): `<tool_call>{"name": ..., "arguments": {...}}</tool_call>`
 *  - **Mistral Nemo** (Mistral-7B-Instruct-v0.3 only): `[TOOL_CALLS][{"name": ..., "arguments": {...}}]`
 *
 * This is the Kotlin-side fallback path referenced in the spec: the preferred path is
 * native parsing via llama.cpp's PEG-based tool-call parser reachable once `inputs.tools`
 * is wired through the JNI layer (tracked as follow-up; see AgentLoop docs). Until then —
 * and as a robustness net even after that lands — this parser lets the agent loop detect
 * a tool call in the plain generated text of either handler.
 */
object ToolCallParser {

    private val json = Json { ignoreUnknownKeys = true }

    // Android ICU regex requires } to be escaped as \\} and ] as \\]
    private val hermesToolCallRegex = Regex(
        "<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>",
        RegexOption.DOT_MATCHES_ALL
    )

    private val mistralToolCallsRegex = Regex(
        "\\[TOOL_CALLS\\]\\s*(\\[.*\\])",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Scans [text] for a tool call in either supported handler format.
     * Returns the first parsed call, or null if none is present / parsing fails.
     */
    fun parse(text: String): ParsedToolCall? {
        hermesToolCallRegex.find(text)?.let { match ->
            parseHermesBody(match.groupValues[1])?.let { return it }
        }
        mistralToolCallsRegex.find(text)?.let { match ->
            parseMistralBody(match.groupValues[1])?.let { return it }
        }
        return null
    }

    /** True if [text] contains what looks like an in-progress or complete tool-call token. */
    fun containsToolCallMarker(text: String): Boolean =
        text.contains("<tool_call>") || text.contains("[TOOL_CALLS]")

    private fun parseHermesBody(body: String): ParsedToolCall? = try {
        val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val arguments = (obj["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        ParsedToolCall(name = name, arguments = arguments)
    } catch (e: Exception) {
        null
    }

    private fun parseMistralBody(body: String): ParsedToolCall? = try {
        val arr = json.parseToJsonElement(body) as? JsonArray ?: return null
        val first = arr.firstOrNull() as? JsonObject ?: return null
        val name = first["name"]?.jsonPrimitive?.content ?: return null
        val arguments = (first["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        ParsedToolCall(name = name, arguments = arguments)
    } catch (e: Exception) {
        null
    }
}
