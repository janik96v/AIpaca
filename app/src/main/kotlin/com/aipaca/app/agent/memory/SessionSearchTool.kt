package com.aipaca.app.agent.memory

import android.util.Log
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import com.aipaca.app.data.FtsSearchResult
import com.aipaca.app.data.MessageDao
import com.aipaca.app.data.MessageEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TAG = "SessionSearchTool"

/**
 * Local tool for FTS5 full-text search over past conversation messages.
 *
 * Returns "bookend" snippets for each matching session:
 * - First 5 messages → what was the goal?
 * - Match window (1 before, match, 1 after) → what's relevant?
 * - Last 5 messages → how did it resolve?
 *
 * Zero LLM calls, deterministic, pure SQLite FTS5 with BM25 ranking.
 */
object SessionSearchTool {

    const val NAME = "session_search"
    private const val DESCRIPTION = "Search past conversations for relevant context."
    private const val MAX_OUTPUT_CHARS = 6000

    private val INPUT_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Search query to find relevant past conversations")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("query")) }
    }

    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = INPUT_SCHEMA
    )

    suspend fun run(args: JsonObject, dao: MessageDao): ToolResult {
        val query = args["query"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'query' parameter", isError = true)

        if (query.isBlank()) {
            return ToolResult("Query cannot be blank", isError = true)
        }

        return try {
            // FTS5 search with BM25 ranking
            val matches = dao.searchFts(query, limit = 10)

            if (matches.isEmpty()) {
                return ToolResult("No past conversations match '$query'.")
            }

            // Group matches by session
            val sessionIds = matches.map { it.sessionId }.distinct().take(5)

            val result = buildString {
                for (sessionId in sessionIds) {
                    val sessionMatches = matches.filter { it.sessionId == sessionId }
                    val sessionTitle = sessionMatches.first().sessionTitle
                    val allMessages = dao.getSessionMessages(sessionId)

                    appendLine("## Session: $sessionTitle")
                    appendLine()

                    val bookend = buildBookend(allMessages, sessionMatches)
                    append(bookend)
                    appendLine()
                    appendLine("---")
                    appendLine()

                    if (length > MAX_OUTPUT_CHARS) break
                }
            }

            ToolResult(text = result.take(MAX_OUTPUT_CHARS))
        } catch (e: Exception) {
            Log.e(TAG, "FTS search failed", e)
            ToolResult("Search failed: ${e.message}", isError = true)
        }
    }

    /**
     * Build "bookend" output for a session:
     * - First 5 messages (goal context)
     * - Match windows (1 before + match + 1 after)
     * - Last 5 messages (resolution)
     *
     * Deduplicates overlapping regions.
     */
    private fun buildBookend(
        allMessages: List<MessageEntity>,
        matches: List<FtsSearchResult>
    ): String {
        if (allMessages.isEmpty()) return "(empty session)\n"

        // Collect indices to show
        val indicesToShow = mutableSetOf<Int>()

        // First 5 messages
        for (i in 0 until minOf(5, allMessages.size)) {
            indicesToShow.add(i)
        }

        // Last 5 messages
        for (i in maxOf(0, allMessages.size - 5) until allMessages.size) {
            indicesToShow.add(i)
        }

        // Match windows (1 before + match + 1 after)
        for (match in matches) {
            val idx = allMessages.indexOfFirst { it.id == match.messageId }
            if (idx >= 0) {
                if (idx > 0) indicesToShow.add(idx - 1)
                indicesToShow.add(idx)
                if (idx < allMessages.size - 1) indicesToShow.add(idx + 1)
            }
        }

        val sorted = indicesToShow.sorted()
        val matchIds = matches.map { it.messageId }.toSet()

        return buildString {
            var lastIdx = -1
            for (idx in sorted) {
                // Show gap marker if indices aren't consecutive
                if (lastIdx >= 0 && idx > lastIdx + 1) {
                    appendLine("  [... ${idx - lastIdx - 1} messages ...]")
                }
                val msg = allMessages[idx]
                val marker = if (msg.id in matchIds) " <<MATCH>>" else ""
                val content = msg.content.take(200).replace("\n", " ")
                appendLine("  ${msg.role}: $content$marker")
                lastIdx = idx
            }
        }
    }
}
