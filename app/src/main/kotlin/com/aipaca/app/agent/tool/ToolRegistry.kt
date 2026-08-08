package com.aipaca.app.agent.tool

import android.util.Log
import com.aipaca.app.agent.mcp.McpClient
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject

private const val TAG = "ToolRegistry"

/** One MCP tool paired with the client instance that can execute it. */
data class RegisteredTool(
    val spec: ToolSpec,
    val client: McpClient
)

/** A tool implemented in-process (no MCP server), registered via [ToolRegistry.registerLocal]. */
data class LocalTool(
    val spec: ToolSpec,
    val handler: suspend (JsonObject) -> ToolResult
)

/**
 * Aggregates tool manifests from all connected [McpClient]s and local (in-process)
 * tools so the agent loop / JNI tool-calling path sees a single flat list of
 * callable tools, while routing `tools/call` back to the owning handler transparently.
 */
class ToolRegistry {

    private val clients = mutableListOf<McpClient>()
    private var tools: List<RegisteredTool> = emptyList()
    private val localTools = mutableListOf<LocalTool>()

    /** Connects [client] and merges its tools into the registry's manifest. */
    suspend fun register(client: McpClient) {
        Log.d(TAG, "register: connecting client...")
        client.connect()
        Log.d(TAG, "register: connected, listing tools...")
        val specs = client.listTools()
        Log.d(TAG, "register: got ${specs.size} tools: ${specs.map { it.name }}")
        clients += client
        tools = tools + specs.map { RegisteredTool(it, client) }
    }

    /** Registers a local (in-process) tool that doesn't need an MCP server. */
    fun registerLocal(spec: ToolSpec, handler: suspend (JsonObject) -> ToolResult) {
        localTools += LocalTool(spec, handler)
        Log.d(TAG, "registerLocal: ${spec.name}")
    }

    /** Flat manifest of every tool across all registered MCP servers and local tools. */
    fun manifest(): List<ToolSpec> = tools.map { it.spec } + localTools.map { it.spec }

    /** True once at least one tool with [name] has been registered. */
    fun hasTool(name: String): Boolean =
        tools.any { it.spec.name == name } || localTools.any { it.spec.name == name }

    /**
     * Executes the tool named [name] with [arguments] via its owning handler.
     * Checks local tools first, then MCP tools.
     * Returns an error [ToolResult] (never throws) for unknown tools or execution failures.
     */
    suspend fun callTool(name: String, arguments: JsonObject): ToolResult {
        // Check local tools first
        localTools.firstOrNull { it.spec.name == name }?.let { local ->
            return try {
                local.handler(arguments)
            } catch (e: Exception) {
                Log.e(TAG, "callTool local '$name' failed", e)
                ToolResult(text = "Tool call failed: ${e.message ?: "unknown error"}", isError = true)
            }
        }
        // Then MCP tools
        val registered = tools.firstOrNull { it.spec.name == name }
            ?: return ToolResult(text = "Unknown tool: $name", isError = true)
        return try {
            registered.client.callTool(name, arguments)
        } catch (e: Exception) {
            Log.e(TAG, "callTool '$name' failed", e)
            ToolResult(text = "Tool call failed: ${e.message ?: "unknown error"}", isError = true)
        }
    }

    /** Closes every registered client's underlying HTTP resources and clears local tools. */
    fun closeAll() {
        clients.forEach { it.close() }
        clients.clear()
        tools = emptyList()
        localTools.clear()
    }
}
