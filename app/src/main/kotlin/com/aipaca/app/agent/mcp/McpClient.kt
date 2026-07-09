package com.aipaca.app.agent.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Minimal MCP client contract needed for AIpaca's agent tool-calling (#42 / #43 Brick 2).
 *
 * Implementations connect to exactly one remote MCP server per instance.
 */
interface McpClient {

    /**
     * Performs the MCP handshake: `initialize` followed by the
     * `notifications/initialized` notification. Must be called once before
     * [listTools] / [callTool]. Safe to call again (idempotent no-op) if
     * already connected.
     */
    suspend fun connect()

    /** Lists the tools this MCP server exposes. Requires [connect] to have succeeded. */
    suspend fun listTools(): List<ToolSpec>

    /** Invokes [name] with [arguments] (a JSON object) and returns the tool's result text. */
    suspend fun callTool(name: String, arguments: JsonObject): ToolResult

    /** Releases any underlying HTTP resources (session, connection pool). */
    fun close()
}
