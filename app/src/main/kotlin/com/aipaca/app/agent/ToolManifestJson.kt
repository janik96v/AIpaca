package com.aipaca.app.agent

import com.aipaca.app.agent.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders an MCP tool manifest into the `toolsJson` array consumed by
 * `LlamaCppEngine.nativeGenerateAgent` (PR1, Increment 2). Each entry maps to a
 * `common_chat_tool { name, description, parameters }`, which llama.cpp's Jinja template renders
 * into the model's native tool-call format — no hand-written `<tool_call>` prompt instructions.
 *
 * Kept compact on purpose: `research_notes/20_kurzbericht_edge_kontext_agentik.md` §4 (Hebel C)
 * flags tool-schema size as a binary enablement factor under tight on-device context budgets.
 *
 * Shape:
 * ```
 * [ {"name":"tavily_search","description":"...","parameters":{"type":"object","properties":{...}}} ]
 * ```
 */
fun List<ToolSpec>.toToolsJson(): String {
    val array = buildJsonArray {
        this@toToolsJson.forEach { tool ->
            add(
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description ?: "")
                    put("parameters", tool.inputSchema ?: emptyObjectSchema())
                }
            )
        }
    }
    return array.toString()
}

/** Minimal valid JSON-Schema object for tools that advertise no input schema. */
private fun emptyObjectSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(emptyMap()))
}
