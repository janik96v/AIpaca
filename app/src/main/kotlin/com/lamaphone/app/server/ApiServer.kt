package com.lamaphone.app.server

import android.util.Log
import com.lamaphone.app.EngineState
import com.lamaphone.app.engine.GenerateParams
import com.lamaphone.app.server.models.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.utils.io.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// Simple response types for endpoints that don't use the OpenAI models
@Serializable
private data class HealthResponse(
    val status: String,
    val model: String? = null,
    val loaded: Boolean
)

@Serializable
private data class ApiError(val message: String, val type: String)

@Serializable
private data class ApiErrorWrapper(val error: ApiError)

private const val TAG = "ApiServer"

object ApiServer {

    val port = 8080
    val requestCount = AtomicLong(0L)

    private var server: ApplicationEngine? = null

    /** Mutex to ensure only one generate call runs at a time through the API. */
    private val generateMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun start(engineState: EngineState) {
        if (server != null) {
            Log.w(TAG, "Server already running — ignoring start()")
            return
        }
        Log.i(TAG, "Starting Ktor server on 0.0.0.0:$port")
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            configurePlugins()
            configureRouting(engineState)
        }.also { it.start(wait = false) }
        Log.i(TAG, "Ktor server started")
    }

    fun stop() {
        Log.i(TAG, "Stopping Ktor server")
        server?.stop(500, 1000)
        server = null
        Log.i(TAG, "Ktor server stopped")
    }

    fun isRunning(): Boolean = server != null

    // -------------------------------------------------------------------------
    // Ktor configuration
    // -------------------------------------------------------------------------

    private fun Application.configurePlugins() {
        install(ContentNegotiation) {
            json(json)
        }
        install(CORS) {
            anyHost()
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Options)
        }
    }

    private fun Application.configureRouting(engineState: EngineState) {
        routing {

            // ------------------------------------------------------------------
            // GET /health
            // ------------------------------------------------------------------
            get("/health") {
                requestCount.incrementAndGet()
                val modelName = engineState.modelPath.value?.substringAfterLast('/')?.substringBeforeLast('.')
                call.respond(
                    HttpStatusCode.OK,
                    HealthResponse(
                        status  = "ok",
                        model   = modelName,
                        loaded  = engineState.isLoaded.value
                    )
                )
            }

            // ------------------------------------------------------------------
            // GET /v1/models
            // ------------------------------------------------------------------
            get("/v1/models") {
                requestCount.incrementAndGet()
                val modelPath = engineState.modelPath.value
                val modelList = if (modelPath != null) {
                    val modelId = modelPath.substringAfterLast('/').substringBeforeLast('.')
                    listOf(ModelInfo(id = modelId))
                } else {
                    emptyList()
                }
                call.respond(HttpStatusCode.OK, ModelsResponse(data = modelList))
            }

            // ------------------------------------------------------------------
            // POST /v1/chat/completions
            // ------------------------------------------------------------------
            post("/v1/chat/completions") {
                requestCount.incrementAndGet()

                val request = try {
                    call.receive<ChatCompletionRequest>()
                } catch (e: Exception) {
                    Log.w(TAG, "Bad request: ${e.message}")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiErrorWrapper(ApiError(
                            message = "Invalid request body: ${e.message}",
                            type    = "invalid_request_error"
                        ))
                    )
                    return@post
                }

                if (!engineState.isLoaded.value) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiErrorWrapper(ApiError(
                            message = "No model loaded. Load a GGUF model in the LamaPhone app first.",
                            type    = "model_not_loaded"
                        ))
                    )
                    return@post
                }

                val (systemPrompt, conversationText) = buildPromptFromMessages(request.messages)

                // Pass systemPrompt via GenerateParams so the JNI layer can apply
                // the model's built-in chat template rather than manual string concat.
                val params = GenerateParams(
                    systemPrompt = systemPrompt ?: "You are a helpful assistant.",
                    temperature  = request.temperature.toFloat(),
                    maxTokens    = request.maxTokens,
                    topP         = request.topP.toFloat()
                )

                val modelName = engineState.modelPath.value
                    ?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?: request.model

                val completionId = "chatcmpl-${UUID.randomUUID()}"
                val createdAt = System.currentTimeMillis() / 1000

                if (!request.stream) {
                    // ---- Non-streaming path ----------------------------------
                    val fullText = StringBuilder()
                    try {
                        generateMutex.withLock {
                            engineState.engine.generate(conversationText, params).collect { token ->
                                fullText.append(token)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Generation error", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiErrorWrapper(ApiError(
                                message = "Generation failed: ${e.message}",
                                type    = "internal_error"
                            ))
                        )
                        return@post
                    }

                    val completionText = fullText.toString()
                    val completionTokens = completionText.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
                    val promptTokens = conversationText.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size

                    val response = ChatCompletionResponse(
                        id = completionId,
                        created = createdAt,
                        model = modelName,
                        choices = listOf(
                            Choice(
                                index = 0,
                                message = OpenAIMessage(role = "assistant", content = completionText),
                                finishReason = "stop"
                            )
                        ),
                        usage = Usage(
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalTokens = promptTokens + completionTokens
                        )
                    )
                    call.respond(HttpStatusCode.OK, response)

                } else {
                    // ---- Streaming SSE path ----------------------------------
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.response.header(HttpHeaders.Connection, "keep-alive")

                    call.respondBytesWriter(
                        contentType = ContentType.Text.EventStream,
                        status = HttpStatusCode.OK
                    ) {
                        // Initial chunk: role announcement
                        val roleDelta = ChatCompletionChunk(
                            id = completionId,
                            created = createdAt,
                            model = modelName,
                            choices = listOf(
                                ChunkChoice(
                                    index = 0,
                                    delta = DeltaContent(role = "assistant"),
                                    finishReason = null
                                )
                            )
                        )
                        writeStringUtf8("data: ${json.encodeToString(roleDelta)}\n\n")
                        flush()

                        try {
                            generateMutex.withLock {
                                engineState.engine.generate(conversationText, params).collect { token ->
                                    val chunk = ChatCompletionChunk(
                                        id = completionId,
                                        created = createdAt,
                                        model = modelName,
                                        choices = listOf(
                                            ChunkChoice(
                                                index = 0,
                                                delta = DeltaContent(content = token),
                                                finishReason = null
                                            )
                                        )
                                    )
                                    writeStringUtf8("data: ${json.encodeToString(chunk)}\n\n")
                                    flush()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Streaming generation error", e)
                        }

                        // Final stop chunk
                        val stopChunk = ChatCompletionChunk(
                            id = completionId,
                            created = createdAt,
                            model = modelName,
                            choices = listOf(
                                ChunkChoice(
                                    index = 0,
                                    delta = DeltaContent(),
                                    finishReason = "stop"
                                )
                            )
                        )
                        writeStringUtf8("data: ${json.encodeToString(stopChunk)}\n\n")
                        writeStringUtf8("data: [DONE]\n\n")
                        flush()
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildPromptFromMessages(messages: List<OpenAIMessage>): Pair<String?, String> {
        val systemPrompt = messages.firstOrNull { it.role == "system" }?.content
        // Pass non-system messages as plain text; llama_jni.cpp will apply the
        // model's own chat template via llama_chat_apply_template.
        // For multi-turn conversations we concatenate turns so the template sees them.
        val nonSystem = messages.filter { it.role != "system" }
        val conversationText = if (nonSystem.size == 1) {
            nonSystem.first().content
        } else {
            // Flatten multi-turn into a single user message; the JNI layer wraps
            // it in the model's template. This is a simplification until the JNI
            // layer gains proper multi-turn support.
            nonSystem.joinToString("\n") { msg ->
                when (msg.role) {
                    "assistant" -> "Assistant: ${msg.content}"
                    else -> msg.content
                }
            }
        }
        return Pair(systemPrompt, conversationText)
    }
}
