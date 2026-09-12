package com.aipaca.app.agent.memory

import android.util.Log
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TAG = "MemoryTool"

/**
 * Local tool exposing persistent memory read/write to the agent during a turn (loop L1).
 *
 * Operations: add, replace, remove. Files: `user`, `memory` — and `soul`, but only
 * for callers that explicitly allow it.
 *
 * `soul` is deliberately **not** writable from the hot path: a 3B model that can
 * rewrite its own persona mid-conversation will do so, and the change is global and
 * invisible. Soul edits come from the consolidation pass and land as a pending
 * proposal the user approves (see [MemoryConsolidation]).
 */
object MemoryTool {

    const val NAME = "memory"
    const val DESCRIPTION = "Store or update persistent notes about the user and environment. " +
        "Notes persist across sessions."

    /** Files writable from the hot path — soul is excluded on purpose. */
    val HOT_PATH_FILES = setOf(MemoryStore.KEY_USER, MemoryStore.KEY_MEMORY)

    /** Files writable from a consolidation pass. */
    val ALL_FILES = setOf(MemoryStore.KEY_USER, MemoryStore.KEY_MEMORY, MemoryStore.KEY_SOUL)

    fun inputSchema(allowedFiles: Set<String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                putJsonArray("enum") {
                    add(JsonPrimitive("add")); add(JsonPrimitive("replace")); add(JsonPrimitive("remove"))
                }
                put("description", "Operation to perform")
            }
            putJsonObject("file") {
                putJsonArray("enum") { allowedFiles.sorted().forEach { add(JsonPrimitive(it)) } }
                put("description", "'user' for personal preferences, 'memory' for technical facts")
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
        putJsonArray("required") {
            add(JsonPrimitive("action")); add(JsonPrimitive("file")); add(JsonPrimitive("text"))
        }
    }

    fun spec(allowedFiles: Set<String> = HOT_PATH_FILES): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = inputSchema(allowedFiles)
    )

    fun run(
        args: JsonObject,
        store: MemoryStore,
        allowedFiles: Set<String> = HOT_PATH_FILES
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'action' parameter", isError = true)
        val fileKey = args["file"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'file' parameter", isError = true)
        val text = args["text"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'text' parameter", isError = true)

        if (fileKey !in allowedFiles) {
            return ToolResult(
                "File '" + fileKey + "' is not writable here. Use " + allowedFiles.sorted().joinToString(" or "),
                isError = true
            )
        }
        val file = MemoryStore.fileForKey(fileKey)
            ?: return ToolResult("Unknown file '" + fileKey + "'.", isError = true)

        if (action == "add" || action == "replace") {
            val contentToCheck = if (action == "replace") {
                args["replacement"]?.jsonPrimitive?.content ?: text
            } else text
            if (AntiPoisoning.isPoisoned(contentToCheck)) {
                Log.w(TAG, "Rejected poisoned content: " + contentToCheck.take(60))
                return ToolResult("Rejected: do not persist transient errors or negative claims.", isError = true)
            }
        }

        return when (action) {
            "add" -> {
                store.add(file, text)
                ToolResult("Saved to " + fileKey + ": " + text.take(60))
            }
            "replace" -> {
                val replacement = args["replacement"]?.jsonPrimitive?.content
                    ?: return ToolResult("Missing 'replacement' parameter for replace action", isError = true)
                store.replace(file, text, replacement)
                ToolResult("Replaced in " + fileKey)
            }
            "remove" -> {
                store.remove(file, text)
                ToolResult("Removed from " + fileKey)
            }
            else -> ToolResult("Unknown action '" + action + "'. Use 'add', 'replace', or 'remove'.", isError = true)
        }
    }
}
