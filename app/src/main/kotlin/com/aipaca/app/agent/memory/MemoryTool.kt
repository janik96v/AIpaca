package com.aipaca.app.agent.memory

import android.util.Log
import com.aipaca.app.agent.mcp.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TAG = "MemoryTool"

/**
 * Local tool that exposes persistent memory read/write to the agent.
 *
 * Operations: add, replace, remove.
 * Files: "memory" → agent_memory.md, "user" → agent_user.md.
 */
object MemoryTool {

    const val NAME = "memory"
    const val DESCRIPTION = "Store or update persistent notes about the user and environment. " +
        "Notes persist across sessions."

    val INPUT_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                putJsonArray("enum") { add(JsonPrimitive("add")); add(JsonPrimitive("replace")); add(JsonPrimitive("remove")) }
                put("description", "Operation to perform")
            }
            putJsonObject("file") {
                putJsonArray("enum") { add(JsonPrimitive("memory")); add(JsonPrimitive("user")) }
                put("description", "Which file: 'memory' for environment/technical facts, 'user' for personal preferences")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to add, or text to find (for replace/remove)")
            }
            putJsonObject("replacement") {
                put("type", "string")
                put("description", "Replacement text (only for 'replace' action)")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("action")); add(JsonPrimitive("file")); add(JsonPrimitive("text")) }
    }

    fun run(args: JsonObject, store: MemoryStore): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'action' parameter", isError = true)
        val fileKey = args["file"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'file' parameter", isError = true)
        val text = args["text"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'text' parameter", isError = true)

        val file = when (fileKey) {
            "memory" -> MemoryStore.MEMORY_FILE
            "user" -> MemoryStore.USER_FILE
            else -> return ToolResult("Unknown file '$fileKey'. Use 'memory' or 'user'.", isError = true)
        }

        // Anti-poisoning check
        if (action == "add" || action == "replace") {
            val contentToCheck = if (action == "replace") {
                args["replacement"]?.jsonPrimitive?.content ?: text
            } else text
            if (AntiPoisoning.isPoisoned(contentToCheck)) {
                Log.w(TAG, "Rejected poisoned content: ${contentToCheck.take(60)}")
                return ToolResult("Rejected: do not persist transient errors or negative claims.", isError = true)
            }
        }

        return when (action) {
            "add" -> {
                store.add(file, text)
                ToolResult("Saved to $fileKey: ${text.take(60)}")
            }
            "replace" -> {
                val replacement = args["replacement"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'replacement' parameter for replace action", isError = true)
                store.replace(file, text, replacement)
                ToolResult("Replaced in $fileKey")
            }
            "remove" -> {
                store.remove(file, text)
                ToolResult("Removed from $fileKey")
            }
            else -> ToolResult("Unknown action '$action'. Use 'add', 'replace', or 'remove'.", isError = true)
        }
    }
}
