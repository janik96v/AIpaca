package com.aipaca.app.agent

import com.aipaca.app.engine.AgentToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Message model for the native tool-calling agent path (PR1).
 *
 * Unlike [com.aipaca.app.engine.ChatTurn] (which only knows system/user/assistant), this carries
 * the two roles native tool-calling needs: an [Assistant] turn that *requests* tool calls, and a
 * [Tool] turn that returns a result keyed by `tool_call_id`. These map 1:1 onto llama.cpp's
 * `common_chat_msg` so the model's Jinja tool template renders them in the correct native format.
 *
 * [toMessagesJson] serializes a list into exactly the `messagesJson` array consumed by
 * `LlamaCppEngine.nativeGenerateAgent` (see docs/roadmap/PR1_agent_foundation.md, Increment 2).
 */
sealed interface AgentMessage {
    val role: String

    data class System(val content: String) : AgentMessage {
        override val role: String get() = "system"
    }

    data class User(val content: String) : AgentMessage {
        override val role: String get() = "user"
    }

    /** Assistant turn: optional free-text [content] and/or the [toolCalls] the model requested. */
    data class Assistant(
        val content: String,
        val toolCalls: List<AgentToolCall> = emptyList()
    ) : AgentMessage {
        override val role: String get() = "assistant"
    }

    /** Result of executing a tool, tied back to the [toolCallId] the assistant produced. */
    data class Tool(
        val toolCallId: String,
        val name: String,
        val content: String
    ) : AgentMessage {
        override val role: String get() = "tool"
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Parses a raw arguments JSON string into an object, falling back to `{}` on malformed input. */
private fun parseArgumentsObject(raw: String): JsonObject =
    try {
        lenientJson.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    } catch (e: Exception) {
        JsonObject(emptyMap())
    }

/**
 * Serializes a message list into the JSON array the JNI layer parses into `common_chat_msg`s.
 * Shape (OpenAI-near):
 * ```
 * [ {"role":"system","content":"..."},
 *   {"role":"user","content":"..."},
 *   {"role":"assistant","content":"","tool_calls":[{"id","name","arguments":{...}}]},
 *   {"role":"tool","tool_call_id":"...","name":"...","content":"..."} ]
 * ```
 */
fun List<AgentMessage>.toMessagesJson(): String {
    val array = buildJsonArray {
        this@toMessagesJson.forEach { message ->
            add(
                buildJsonObject {
                    put("role", message.role)
                    when (message) {
                        is AgentMessage.System -> put("content", message.content)
                        is AgentMessage.User -> put("content", message.content)
                        is AgentMessage.Assistant -> {
                            put("content", message.content)
                            if (message.toolCalls.isNotEmpty()) {
                                put(
                                    "tool_calls",
                                    buildJsonArray {
                                        message.toolCalls.forEach { call ->
                                            add(
                                                buildJsonObject {
                                                    put("id", call.id)
                                                    put("type", "function")
                                                    put("function", buildJsonObject {
                                                        put("name", call.name)
                                                        put("arguments", parseArgumentsObject(call.argumentsJson))
                                                    })
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        is AgentMessage.Tool -> {
                            put("content", message.content)
                            put("tool_call_id", message.toolCallId)
                            put("name", message.name)
                        }
                    }
                }
            )
        }
    }
    return array.toString()
}
