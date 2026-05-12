package com.aipaca.app.server.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

// ---------------------------------------------------------------------------
// Request
// ---------------------------------------------------------------------------

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val stream: Boolean = false,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int = 512,
    @SerialName("top_p") val topP: Double = 0.9,
    @SerialName("frequency_penalty") val frequencyPenalty: Double = 0.0,
    @SerialName("presence_penalty") val presencePenalty: Double = 0.0,
    val stop: List<String>? = null,
    @SerialName("include_thinking") val includeThinking: Boolean = false
)

@Serializable
data class OpenAIMessage(
    val role: String,   // "system", "user", "assistant"
    val content: String
)

// ---------------------------------------------------------------------------
// Non-streaming response
// ---------------------------------------------------------------------------

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage
)

@Serializable
data class Choice(
    val index: Int,
    val message: OpenAIMessage,
    @SerialName("finish_reason") val finishReason: String = "stop"
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int
)

// ---------------------------------------------------------------------------
// Streaming response (SSE chunks)
// ---------------------------------------------------------------------------

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: DeltaContent,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class DeltaContent(
    val role: String? = null,
    val content: String? = null
)

// ---------------------------------------------------------------------------
// Models list response
// ---------------------------------------------------------------------------

@Serializable
data class ModelsResponse(
    val `object`: String = "list",
    val data: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val id: String,
    val `object`: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    @SerialName("owned_by") val ownedBy: String = "local"
)
