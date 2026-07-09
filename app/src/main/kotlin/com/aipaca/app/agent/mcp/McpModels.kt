package com.aipaca.app.agent.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire types for a minimal JSON-RPC 2.0 client speaking to a remote MCP server
 * over Streamable HTTP (spec revision 2025-06-18).
 *
 * Deliberately small: only `initialize` / `notifications/initialized` /
 * `tools/list` / `tools/call` are modelled — the exact subset AIpaca needs for
 * Brick 1 (#42) and the generalised loop (#43 Brick 2). See
 * research/spec_issue_42_web_search_mcp.md §5 for the rationale for a
 * hand-rolled client instead of the official (Ktor-3-only) Kotlin MCP SDK.
 */

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

/** One tool as advertised by `tools/list`. */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String? = null,
    @SerialName("inputSchema") val inputSchema: JsonObject? = null
)

@Serializable
data class ToolsListResult(
    val tools: List<ToolSpec> = emptyList(),
    val nextCursor: String? = null
)

/** Content block inside a `tools/call` result (MCP only really uses "text" today). */
@Serializable
data class ToolContentBlock(
    val type: String = "text",
    val text: String? = null
)

@Serializable
data class ToolCallResultPayload(
    val content: List<ToolContentBlock> = emptyList(),
    val isError: Boolean = false
)

/** Normalised result handed back to the agent loop / tool registry. */
data class ToolResult(
    val text: String,
    val isError: Boolean = false
)

/** MCP `initialize` request params — minimal client/protocol identification. */
@Serializable
data class InitializeParams(
    val protocolVersion: String = MCP_PROTOCOL_VERSION,
    val capabilities: JsonObject = JsonObject(emptyMap()),
    val clientInfo: ClientInfo = ClientInfo()
)

@Serializable
data class ClientInfo(
    val name: String = "AIpaca",
    val version: String = "0.3.0"
)

@Serializable
data class InitializeResult(
    val protocolVersion: String = MCP_PROTOCOL_VERSION,
    val serverInfo: JsonObject? = null,
    val capabilities: JsonObject? = null
)

const val MCP_PROTOCOL_VERSION = "2025-06-18"
const val MCP_SESSION_HEADER = "Mcp-Session-Id"
const val MCP_PROTOCOL_HEADER = "Mcp-Protocol-Version"

/** Helper: build the `{"name": ..., "arguments": {...}}` params object for `tools/call`. */
fun toolCallParams(name: String, arguments: JsonObject): JsonObject = JsonObject(
    mapOf(
        "name" to JsonPrimitive(name),
        "arguments" to arguments
    )
)
