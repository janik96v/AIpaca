package com.lamaphone.app.server

import android.util.Log
import com.lamaphone.app.EngineState
import com.lamaphone.app.engine.ChatTurn
import com.lamaphone.app.engine.GenerateParams
import com.lamaphone.app.server.models.*
import com.lamaphone.app.server.security.LamaPhoneAuth
import com.lamaphone.app.server.security.AuthorizedKeysAttrKey
import com.lamaphone.app.server.security.AuthorizedKeysStore
import com.lamaphone.app.server.security.PairingManager
import com.lamaphone.app.server.security.TlsManager
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.utils.io.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val MAX_REQUEST_BYTES = 1_048_576L   // 1 MB — enforced both at connector and handler level
private const val GENERATE_TIMEOUT_MS = 120_000L   // 2 minutes max per request
private const val TAG = "ApiServer"

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

@Serializable
private data class PairRequest(
    val clientPublicKey: String,
    val pin: String,
    val displayName: String = "Unknown Device"
)

@Serializable
private data class PairResponse(
    val status: String,
    val serverCertFingerprint: String
)

object ApiServer {

    const val port = 8443
    val requestCount = AtomicLong(0L)

    private var server: ApplicationEngine? = null

    /** Mutex to ensure only one generate call runs at a time through the API. */
    private val generateMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        // isLenient intentionally OFF — strict parsing reduces parser confusion attack surface
        encodeDefaults = true
        explicitNulls = false
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun start(
        engineState: EngineState,
        tlsConfig: TlsManager.TlsConfig,
        authorizedKeys: AuthorizedKeysStore,
        bindAddress: String = "0.0.0.0"
    ) {
        if (server != null) {
            Log.w(TAG, "Server already running — ignoring start()")
            return
        }
        Log.i(TAG, "Starting Ktor HTTPS server on $bindAddress:$port")

        val env = applicationEngineEnvironment {
            sslConnector(
                keyStore          = tlsConfig.keyStore,
                keyAlias          = "lamaphone_tls",
                keyStorePassword  = { tlsConfig.keystorePassword.toCharArray() },
                privateKeyPassword = { tlsConfig.keystorePassword.toCharArray() }
            ) {
                // Bind to the resolved local WiFi/eth address to avoid exposing the server
                // on hotspot, VPN, or other unexpected network interfaces.
                host = bindAddress
                port = this@ApiServer.port
            }
            module {
                attributes.put(AuthorizedKeysAttrKey, authorizedKeys)
                configurePlugins()
                configureRouting(engineState, authorizedKeys, tlsConfig)
            }
        }

        server = embeddedServer(Netty, env).also { it.start(wait = false) }
        Log.i(TAG, "Ktor HTTPS server started on port $port")
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
        // CORS intentionally NOT installed — API clients are not browsers.
        // Removing anyHost() means no browser can make cross-origin requests (CSRF protection).
        install(LamaPhoneAuth)
    }

    private fun Application.configureRouting(
        engineState: EngineState,
        authorizedKeys: AuthorizedKeysStore,
        tlsConfig: TlsManager.TlsConfig
    ) {
        routing {

            // ------------------------------------------------------------------
            // GET /health  (public — no auth required)
            // ------------------------------------------------------------------
            get("/health") {
                val modelName = engineState.modelPath.value?.substringAfterLast('/')?.substringBeforeLast('.')
                call.respond(
                    HttpStatusCode.OK,
                    HealthResponse(
                        status = "ok",
                        model  = modelName,
                        loaded = engineState.isLoaded.value
                    )
                )
            }

            // ------------------------------------------------------------------
            // POST /v1/pair  (public — protected by PIN, not auth header)
            // ------------------------------------------------------------------
            post("/v1/pair") {
                val contentLength = call.request.contentLength() ?: 0L
                if (contentLength > MAX_REQUEST_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorWrapper(ApiError("Request body too large", "invalid_request_error")))
                    return@post
                }

                val req = try {
                    call.receive<PairRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorWrapper(ApiError("Invalid request body: ${e.message}", "invalid_request_error")))
                    return@post
                }

                if (!PairingManager.validateAndConsume(req.pin)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiErrorWrapper(ApiError("Invalid or expired pairing PIN", "auth_error")))
                    return@post
                }

                val fingerprint = AuthorizedKeysStore.fingerprintOf(req.clientPublicKey)
                if (fingerprint == null) {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorWrapper(ApiError("Invalid public key encoding", "invalid_request_error")))
                    return@post
                }

                authorizedKeys.add(
                    AuthorizedKeysStore.AuthorizedKey(
                        fingerprint    = fingerprint,
                        displayName    = sanitizeDisplayName(req.displayName),
                        publicKeyBase64 = req.clientPublicKey
                    )
                )
                call.respond(HttpStatusCode.OK, PairResponse(
                    status               = "paired",
                    serverCertFingerprint = tlsConfig.certFingerprint
                ))
            }

            // ------------------------------------------------------------------
            // GET /v1/models  (requires auth)
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
            // POST /v1/chat/completions  (requires auth)
            // ------------------------------------------------------------------
            post("/v1/chat/completions") {
                requestCount.incrementAndGet()

                val contentLength = call.request.contentLength() ?: 0L
                if (contentLength > MAX_REQUEST_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorWrapper(ApiError("Request body too large (max 1 MB)", "invalid_request_error")))
                    return@post
                }

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

                val chatTurns = buildTurnsFromMessages(request.messages)
                val params = GenerateParams(
                    temperature = request.temperature.toFloat(),
                    maxTokens   = request.maxTokens,
                    topP        = request.topP.toFloat()
                )

                val modelName = engineState.modelPath.value
                    ?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?: request.model

                val completionId = "chatcmpl-${UUID.randomUUID()}"
                val createdAt = System.currentTimeMillis() / 1000

                if (!request.stream) {
                    // ---- Non-streaming path ----------------------------------
                    val fullText = StringBuilder()
                    val thinkText = StringBuilder()
                    val acquired = withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
                        try {
                            generateMutex.withLock {
                                engineState.engine.generateChat(chatTurns, params).collect { chunk ->
                                    fullText.append(chunk.content)
                                    thinkText.append(chunk.thinking)
                                }
                            }
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "Generation error", e)
                            null
                        }
                    }

                    if (acquired == null) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ApiErrorWrapper(ApiError("Server busy or generation timed out. Try again.", "server_busy"))
                        )
                        return@post
                    }

                    val completionText = if (request.includeThinking && thinkText.isNotEmpty())
                        "<think>${thinkText}</think>${fullText}"
                    else
                        fullText.toString()
                    val completionTokens = completionText.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
                    val promptTokens = chatTurns.sumOf { it.content.split("\\s+".toRegex()).count { token -> token.isNotEmpty() } }

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

                        val acquired = withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
                            try {
                                generateMutex.withLock {
                                    engineState.engine.generateChat(chatTurns, params).collect { generationChunk ->
                                        val output = if (request.includeThinking)
                                            generationChunk.thinking + generationChunk.content
                                        else
                                            generationChunk.content
                                        if (output.isNotEmpty()) {
                                            val chunk = ChatCompletionChunk(
                                                id = completionId,
                                                created = createdAt,
                                                model = modelName,
                                                choices = listOf(
                                                    ChunkChoice(
                                                        index = 0,
                                                        delta = DeltaContent(content = output),
                                                        finishReason = null
                                                    )
                                                )
                                            )
                                            writeStringUtf8("data: ${json.encodeToString(chunk)}\n\n")
                                            flush()
                                        }
                                    }
                                }
                                true
                            } catch (e: Exception) {
                                Log.e(TAG, "Streaming generation error", e)
                                null
                            }
                        }

                        if (acquired == null) {
                            writeStringUtf8("data: {\"error\":\"server_busy\"}\n\n")
                            flush()
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

    /**
     * Strips control characters and Unicode bidirectional override codepoints from a
     * display name to prevent spoofing in the UI (e.g. RTL overrides, null bytes).
     * Limits to 64 printable characters.
     */
    private fun sanitizeDisplayName(raw: String): String =
        raw.filter { c ->
            c.code >= 0x20 &&           // no C0 controls / null
            c.category != CharCategory.FORMAT &&  // no Unicode format chars (e.g. U+202E RLO)
            c.category != CharCategory.CONTROL &&
            c.category != CharCategory.PRIVATE_USE &&
            c.category != CharCategory.SURROGATE
        }.take(64).ifBlank { "Unknown Device" }

    private fun buildTurnsFromMessages(messages: List<OpenAIMessage>): List<ChatTurn> {
        val turns = messages.mapNotNull { msg ->
            if (msg.content.isBlank()) return@mapNotNull null
            val role = when (msg.role.lowercase()) {
                "system", "assistant", "user" -> msg.role.lowercase()
                else -> "user"
            }
            ChatTurn(role = role, content = msg.content)
        }
        return if (turns.none { it.role == "system" }) {
            listOf(ChatTurn("system", "You are a helpful assistant.")) + turns
        } else {
            turns
        }
    }
}
