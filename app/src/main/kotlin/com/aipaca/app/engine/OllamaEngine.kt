package com.aipaca.app.engine

import android.util.Log
import com.aipaca.app.agent.AgentMessage
import com.aipaca.app.agent.mcp.ToolSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "OllamaEngine"

/**
 * Remote LLM engine that connects to an Ollama server's OpenAI-compatible API.
 *
 * Provides the same generation contract as [LlamaCppEngine] (chat + agent streaming)
 * but over HTTP instead of JNI. This allows testing the agentic pipeline with larger
 * models running on a desktop machine on the local network.
 */
class OllamaEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val stopRequested = AtomicBoolean(false)

    var serverUrl: String = "http://192.168.1.100:11434"
    var modelName: String = "qwen3:30b"

    private val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000   // 5 min — large models can be slow
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 300_000
        }
    }

    /**
     * Test connectivity by hitting Ollama's /api/tags endpoint.
     * Returns the list of available model names, or throws on failure.
     */
    suspend fun listModels(): List<String> {
        val response = httpClient.get(urlString = "$serverUrl/api/tags") {
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Ollama server returned ${response.status}")
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["models"]?.jsonArray?.mapNotNull { model ->
            model.jsonObject["name"]?.jsonPrimitive?.content
        } ?: emptyList()
    }

    /**
     * Stream chat completion tokens from Ollama's OpenAI-compatible endpoint.
     * Returns a cold [Flow] — collection triggers the HTTP request.
     */
    fun generateChat(
        turns: List<ChatTurn>,
        params: GenerateParams = GenerateParams()
    ): Flow<GenerationChunk> = callbackFlow {
        stopRequested.set(false)

        val messagesJson = buildJsonArray {
            for (turn in turns) {
                add(buildJsonObject {
                    put("role", turn.role)
                    put("content", turn.content)
                })
            }
        }

        val requestBody = buildJsonObject {
            put("model", modelName)
            put("messages", messagesJson)
            put("stream", true)
            put("temperature", params.temperature.toDouble())
            put("max_tokens", params.maxTokens)
        }.toString()

        try {
            val response = httpClient.post("$serverUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(requestBody)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw IllegalStateException("Ollama error ${response.status}: $errorBody")
            }

            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead && !stopRequested.get()) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = json.parseToJsonElement(data).jsonObject
                    val choices = chunk["choices"]?.jsonArray ?: continue
                    if (choices.isEmpty()) continue

                    val delta = choices[0].jsonObject["delta"]?.jsonObject ?: continue
                    val content = delta["content"]?.jsonPrimitive?.content ?: ""

                    if (content.isNotEmpty()) {
                        // Parse <think>...</think> tags for thinking support
                        val (thinkingPart, contentPart) = splitThinking(content)
                        trySend(GenerationChunk(content = contentPart, thinking = thinkingPart))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE parse error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (!stopRequested.get()) {
                Log.e(TAG, "generateChat failed", e)
                throw e
            }
        }

        close()
        awaitClose()
    }

    /**
     * Stream agent generation with tool-calling support via Ollama's OpenAI-compatible API.
     *
     * Emits [AgentChunk] tokens for real-time UI updates. When the model requests tool calls,
     * emits a sentinel `__AGENT_RESULT__:` chunk with the parsed [AgentResult] JSON, matching
     * the protocol used by [LlamaCppEngine.generateAgent].
     */
    fun generateAgent(
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
        params: GenerateParams
    ): Flow<AgentChunk> = callbackFlow {
        stopRequested.set(false)

        val messagesJson = buildAgentMessagesJson(messages)
        val toolsJson = buildToolsJson(tools)

        val requestBody = buildJsonObject {
            put("model", modelName)
            put("messages", messagesJson)
            if (toolsJson.isNotEmpty()) {
                put("tools", toolsJson)
            }
            put("stream", true)
            put("temperature", params.temperature.toDouble())
            put("max_tokens", params.maxTokens)
        }.toString()

        Log.d(TAG, "generateAgent: ${messages.size} messages, ${tools.size} tools")

        try {
            val response = httpClient.post("$serverUrl/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                setBody(requestBody)
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                throw IllegalStateException("Ollama error ${response.status}: $errorBody")
            }

            val contentBuilder = StringBuilder()
            val toolCalls = mutableListOf<AgentToolCall>()
            // Track partial tool call deltas across chunks
            val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()

            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead && !stopRequested.get()) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = json.parseToJsonElement(data).jsonObject
                    val choices = chunk["choices"]?.jsonArray ?: continue
                    if (choices.isEmpty()) continue

                    val choice = choices[0].jsonObject
                    val delta = choice["delta"]?.jsonObject ?: continue

                    // Content tokens
                    val content = delta["content"]?.jsonPrimitive?.content ?: ""
                    if (content.isNotEmpty()) {
                        contentBuilder.append(content)
                        val (thinkingPart, contentPart) = splitThinking(content)
                        trySend(AgentChunk(content = contentPart, thinking = thinkingPart))
                    }

                    // Tool call deltas (streamed incrementally by OpenAI-compatible APIs)
                    delta["tool_calls"]?.jsonArray?.forEach { tcElem ->
                        val tcObj = tcElem.jsonObject
                        val index = tcObj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }

                        tcObj["id"]?.jsonPrimitive?.content?.let { builder.id = it }
                        tcObj["function"]?.jsonObject?.let { fn ->
                            fn["name"]?.jsonPrimitive?.content?.let { builder.name = it }
                            fn["arguments"]?.jsonPrimitive?.content?.let { builder.arguments.append(it) }
                        }
                    }

                    // Check finish_reason
                    val finishReason = choice["finish_reason"]?.jsonPrimitive?.content
                    if (finishReason == "tool_calls" || finishReason == "stop") {
                        // Build final tool calls from accumulated deltas
                        for ((_, builder) in toolCallBuilders) {
                            if (builder.name.isNotEmpty()) {
                                toolCalls.add(AgentToolCall(
                                    id = builder.id.ifEmpty { "ollama_${toolCalls.size}" },
                                    name = builder.name,
                                    argumentsJson = builder.arguments.toString().ifEmpty { "{}" }
                                ))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE parse error: ${e.message}")
                }
            }

            // Emit sentinel with the final AgentResult
            val result = AgentResult(
                content = contentBuilder.toString(),
                toolCalls = toolCalls
            )
            val resultJson = buildJsonObject {
                put("content", result.content)
                put("tool_calls", buildJsonArray {
                    for (tc in result.toolCalls) {
                        add(buildJsonObject {
                            put("id", tc.id)
                            put("name", tc.name)
                            put("arguments", json.parseToJsonElement(tc.argumentsJson))
                        })
                    }
                })
            }.toString()
            trySend(AgentChunk(content = "__AGENT_RESULT__:$resultJson"))

        } catch (e: Exception) {
            if (!stopRequested.get()) {
                Log.e(TAG, "generateAgent failed", e)
                throw e
            }
        }

        close()
        awaitClose()
    }

    fun stopGeneration() {
        stopRequested.set(true)
    }

    fun shutdown() {
        httpClient.close()
    }

    // ---- Private helpers ----

    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments: StringBuilder = StringBuilder()
    }

    /** Build OpenAI-format messages array from AgentMessage list. */
    private fun buildAgentMessagesJson(messages: List<AgentMessage>) = buildJsonArray {
        for (message in messages) {
            add(buildJsonObject {
                put("role", message.role)
                when (message) {
                    is AgentMessage.System -> put("content", message.content)
                    is AgentMessage.User -> put("content", message.content)
                    is AgentMessage.Assistant -> {
                        put("content", message.content)
                        if (message.toolCalls.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                for (tc in message.toolCalls) {
                                    add(buildJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", tc.name)
                                            put("arguments", tc.argumentsJson)
                                        })
                                    })
                                }
                            })
                        }
                    }
                    is AgentMessage.Tool -> {
                        put("content", message.content)
                        put("tool_call_id", message.toolCallId)
                        put("name", message.name)
                    }
                }
            })
        }
    }

    /** Build OpenAI-format tools array from ToolSpec list. */
    private fun buildToolsJson(tools: List<ToolSpec>) = buildJsonArray {
        for (tool in tools) {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description ?: "")
                    put("parameters", tool.inputSchema ?: buildJsonObject {
                        put("type", "object")
                        put("properties", JsonObject(emptyMap()))
                    })
                })
            })
        }
    }

    /**
     * Split `<think>...</think>` tags from content for models that support thinking (e.g. Qwen3).
     * Returns (thinking, content) pair.
     */
    private var inThinkBlock = false
    private val thinkBuffer = StringBuilder()

    private fun splitThinking(text: String): Pair<String, String> {
        var thinking = ""
        var content = text

        if (text.contains("<think>")) {
            inThinkBlock = true
            content = text.substringBefore("<think>")
            val afterTag = text.substringAfter("<think>")
            if (afterTag.contains("</think>")) {
                thinking = afterTag.substringBefore("</think>")
                content += afterTag.substringAfter("</think>")
                inThinkBlock = false
            } else {
                thinking = afterTag
            }
        } else if (inThinkBlock) {
            if (text.contains("</think>")) {
                thinking = text.substringBefore("</think>")
                content = text.substringAfter("</think>")
                inThinkBlock = false
            } else {
                thinking = text
                content = ""
            }
        }

        return thinking to content
    }

    /** Reset thinking state between generations. */
    fun resetThinkingState() {
        inThinkBlock = false
        thinkBuffer.clear()
    }
}

