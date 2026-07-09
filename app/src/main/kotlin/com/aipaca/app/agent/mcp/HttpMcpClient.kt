package com.aipaca.app.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.atomic.AtomicLong

/** Thrown for MCP protocol-level failures (transport errors, JSON-RPC `error` objects). */
class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * [McpClient] implementation over **Streamable HTTP** (MCP spec revision `2025-06-18`).
 *
 * Deliberately hand-rolled instead of the official `io.modelcontextprotocol:kotlin-sdk` —
 * see `research/spec_issue_42_web_search_mcp.md` §5 / `research/10_research_mcp_kotlin.md`:
 * the official SDK (0.13.0, pre-1.0) forces Ktor 3.5.1 / Kotlin 2.4.0, which is binary
 * incompatible with AIpaca's existing Ktor-2.3.12 server stack. The MCP surface actually
 * needed here (`initialize` → `notifications/initialized` → `tools/list` → `tools/call`)
 * is small, static JSON-RPC 2.0 over one HTTP POST endpoint — trivially buildable on the
 * Ktor-2 client with kotlinx.serialization, no stack upgrade required.
 *
 * Handles both Streamable HTTP response shapes per spec: a plain `application/json` body
 * (typical for simple, non-streaming tool calls) and a `text/event-stream` body (SSE
 * framing, used when the server chooses to stream). Captures the `Mcp-Session-Id` response
 * header (if the server assigns one) and echoes it back on every subsequent request.
 *
 * @param serverUrl    Full MCP endpoint URL, e.g. `https://mcp.tavily.com/mcp/?tavilyApiKey=...`.
 * @param extraHeaders Additional headers sent with every request (e.g. `Authorization`).
 * @param httpClient   Injectable Ktor client — production code can rely on the CIO default;
 *                     tests inject a `MockEngine`-backed client to simulate server responses.
 */
class HttpMcpClient(
    private val serverUrl: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
        }
    }
) : McpClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val idCounter = AtomicLong(1)
    @Volatile private var sessionId: String? = null
    @Volatile private var connected: Boolean = false

    override suspend fun connect() {
        if (connected) return

        val initResponse = postRpc(
            JsonRpcRequest(
                id = nextId(),
                method = "initialize",
                params = json.encodeToJsonElement(InitializeParams())
            )
        )
        initResponse.error?.let {
            throw McpException("MCP initialize failed: ${it.code} ${it.message}")
        }

        postNotification(JsonRpcNotification(method = "notifications/initialized"))
        connected = true
    }

    override suspend fun listTools(): List<ToolSpec> {
        ensureConnected()
        val response = postRpc(
            JsonRpcRequest(id = nextId(), method = "tools/list", params = JsonObject(emptyMap()))
        )
        response.error?.let { throw McpException("tools/list failed: ${it.code} ${it.message}") }
        val result = response.result ?: throw McpException("tools/list returned an empty result")
        return json.decodeFromJsonElement(ToolsListResult.serializer(), result).tools
    }

    override suspend fun callTool(name: String, arguments: JsonObject): ToolResult {
        ensureConnected()
        val response = postRpc(
            JsonRpcRequest(
                id = nextId(),
                method = "tools/call",
                params = toolCallParams(name, arguments)
            )
        )
        response.error?.let {
            // Surface JSON-RPC-level errors as a failed ToolResult rather than throwing,
            // so a single failed tool call doesn't crash the agent loop (#43).
            return ToolResult(text = "MCP error ${it.code}: ${it.message}", isError = true)
        }
        val result = response.result ?: return ToolResult(text = "Empty tool result", isError = true)
        val payload = json.decodeFromJsonElement(ToolCallResultPayload.serializer(), result)
        val text = payload.content.joinToString("\n") { it.text.orEmpty() }
        return ToolResult(text = text, isError = payload.isError)
    }

    override fun close() {
        httpClient.close()
    }

    // -------------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------------

    private suspend fun ensureConnected() {
        if (!connected) connect()
    }

    private fun nextId(): Long = idCounter.getAndIncrement()

    private suspend fun postRpc(request: JsonRpcRequest): JsonRpcResponse {
        val bodyStr = json.encodeToString(JsonRpcRequest.serializer(), request)
        val httpResponse = sendRaw(bodyStr)
        captureSessionId(httpResponse)
        if (!httpResponse.status.isSuccess()) {
            throw McpException("MCP server returned HTTP ${httpResponse.status} for method=${request.method}")
        }
        val raw = httpResponse.bodyAsText()
        return if (isEventStream(httpResponse)) {
            parseSseForResponse(raw, request.id)
        } else {
            json.decodeFromString(JsonRpcResponse.serializer(), raw)
        }
    }

    private suspend fun postNotification(notification: JsonRpcNotification) {
        val bodyStr = json.encodeToString(JsonRpcNotification.serializer(), notification)
        val httpResponse = sendRaw(bodyStr)
        captureSessionId(httpResponse)
        if (!httpResponse.status.isSuccess()) {
            throw McpException("MCP server returned HTTP ${httpResponse.status} for notification=${notification.method}")
        }
    }

    private suspend fun sendRaw(bodyStr: String): HttpResponse =
        httpClient.post(serverUrl) {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            accept(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            header(MCP_PROTOCOL_HEADER, MCP_PROTOCOL_VERSION)
            sessionId?.let { header(MCP_SESSION_HEADER, it) }
            extraHeaders.forEach { (key, value) -> header(key, value) }
            setBody(bodyStr)
        }

    private fun captureSessionId(response: HttpResponse) {
        response.headers[MCP_SESSION_HEADER]?.let { sessionId = it }
    }

    private fun isEventStream(response: HttpResponse): Boolean =
        response.contentType()?.match(ContentType.Text.EventStream) == true

    /**
     * Parses an SSE body (`data: {...}\n\n` frames) and returns the JSON-RPC response
     * whose `id` matches [expectedId]. Streamable HTTP may emit several events per
     * request (progress notifications etc.) before the final result — only the
     * matching-id response is returned to the caller.
     */
    private fun parseSseForResponse(raw: String, expectedId: Long): JsonRpcResponse {
        val dataLines = raw.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() }

        var last: JsonRpcResponse? = null
        for (data in dataLines) {
            val parsed = try {
                json.decodeFromString(JsonRpcResponse.serializer(), data)
            } catch (e: Exception) {
                continue
            }
            if (parsed.id == expectedId) return parsed
            last = parsed
        }
        return last ?: throw McpException("SSE stream contained no parsable JSON-RPC response")
    }
}
