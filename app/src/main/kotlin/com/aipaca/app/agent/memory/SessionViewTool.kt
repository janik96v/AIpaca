package com.aipaca.app.agent.memory

import android.util.Log
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import com.aipaca.app.data.MessageDao
import com.aipaca.app.data.MessageEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TAG = "SessionViewTool"

/**
 * Loads one indexed past conversation in full — the "open the item" half of
 * progressive disclosure, exactly mirroring `skill_view` for skills.
 *
 * The agent sees only one line per session in its prompt (rendered by
 * [SessionIndexStore]); when one of those lines looks relevant it calls
 * `session_view` with the id from that line and gets the transcript.
 *
 * Opening a session bumps its access counter, which is what keeps frequently
 * used conversations in the index as it fills up.
 */
object SessionViewTool {

    const val NAME = "session_view"
    private const val DESCRIPTION = "Load a past conversation by its id from the index."

    /** Head/tail sizes — enough to see the goal and how it resolved. */
    private const val HEAD_MESSAGES = 8
    private const val TAIL_MESSAGES = 8
    private const val MAX_MESSAGE_CHARS = 400
    private const val MAX_OUTPUT_CHARS = 6000

    private val INPUT_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("session_id") {
                put("type", "string")
                put("description", "Session id shown in square brackets in the past-conversation index")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("session_id")) }
    }

    fun spec(): ToolSpec = ToolSpec(name = NAME, description = DESCRIPTION, inputSchema = INPUT_SCHEMA)

    suspend fun run(args: JsonObject, dao: MessageDao, index: SessionIndexStore): ToolResult {
        val sessionId = args["session_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Missing 'session_id' parameter", isError = true)
        if (sessionId.isBlank()) return ToolResult("session_id cannot be blank", isError = true)

        return try {
            val messages = dao.getSessionMessages(sessionId)
            if (messages.isEmpty()) {
                return ToolResult(
                    "No conversation with id '" + sessionId + "'. Use an id from the index, " +
                        "or session_search to look for content instead.",
                    isError = true
                )
            }
            index.markAccessed(sessionId)
            val note = index.note(sessionId)
            val header = if (note != null) "# " + note.title + " (" + note.date + ")\n" + note.summary + "\n\n" else ""
            ToolResult(text = (header + render(messages)).take(MAX_OUTPUT_CHARS))
        } catch (e: Exception) {
            Log.e(TAG, "session_view failed", e)
            ToolResult("Failed to load session: " + (e.message ?: "unknown error"), isError = true)
        }
    }

    /** Head + tail rendering with an explicit gap marker. Pure — unit-testable. */
    fun render(messages: List<MessageEntity>): String {
        val total = messages.size
        val builder = StringBuilder()

        fun appendMessage(msg: MessageEntity) {
            builder.append("  ")
                .append(msg.role)
                .append(": ")
                .append(msg.content.take(MAX_MESSAGE_CHARS).replace("\n", " "))
                .append('\n')
        }

        if (total <= HEAD_MESSAGES + TAIL_MESSAGES) {
            messages.forEach(::appendMessage)
            return builder.toString()
        }
        messages.take(HEAD_MESSAGES).forEach(::appendMessage)
        builder.append("  [... ").append(total - HEAD_MESSAGES - TAIL_MESSAGES).append(" messages ...]\n")
        messages.takeLast(TAIL_MESSAGES).forEach(::appendMessage)
        return builder.toString()
    }
}
