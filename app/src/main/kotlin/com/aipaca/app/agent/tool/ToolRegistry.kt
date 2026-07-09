package com.aipaca.app.agent.tool

import com.aipaca.app.agent.mcp.McpClient
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import kotlinx.serialization.json.JsonObject

/** One MCP tool paired with the client instance that can execute it. */
data class RegisteredTool(
    val spec: ToolSpec,
    val client: McpClient
)

/**
 * Aggregates tool manifests from all connected [McpClient]s so the agent loop /
 * JNI tool-calling path sees a single flat list of callable tools, while
 * routing `tools/call` back to the owning client transparently.
 *
 * Brick 1 (#42) registers exactly one client (Tavily). Brick 2 (#43) reuses this
 * same registry unchanged to support multiple MCP servers.
 */
class ToolRegistry {

    private val clients = mutableListOf<McpClient>()
    private var tools: List<RegisteredTool> = emptyList()

    /** Connects [client] and merges its tools into the registry's manifest. */
    suspend fun register(client: McpClient) {
        client.connect()
        val specs = client.listTools()
        clients += client
        tools = tools + specs.map { RegisteredTool(it, client) }
    }

    /** Flat manifest of every tool across all registered MCP servers. */
    fun manifest(): List<ToolSpec> = tools.map { it.spec }

    /** True once at least one tool with [name] has been registered. */
    fun hasTool(name: String): Boolean = tools.any { it.spec.name == name }

    /**
     * Executes the tool named [name] with [arguments] via its owning client.
     * Returns an error [ToolResult] (never throws) if no tool with that name is registered.
     */
    suspend fun callTool(name: String, arguments: JsonObject): ToolResult {
        val registered = tools.firstOrNull { it.spec.name == name }
            ?: return ToolResult(text = "Unknown tool: $name", isError = true)
        return registered.client.callTool(name, arguments)
    }

    /** Closes every registered client's underlying HTTP resources. */
    fun closeAll() {
        clients.forEach { it.close() }
        clients.clear()
        tools = emptyList()
    }
}
