package com.aipaca.app.agent.tool

import com.aipaca.app.agent.mcp.McpClient
import com.aipaca.app.agent.mcp.ToolResult
import com.aipaca.app.agent.mcp.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeMcpClient(
    private val tools: List<ToolSpec>,
    private val onCall: (String, JsonObject) -> ToolResult = { name, _ -> ToolResult("handled by $name") }
) : McpClient {
    var connected = false
        private set
    var closed = false
        private set

    override suspend fun connect() { connected = true }
    override suspend fun listTools(): List<ToolSpec> = tools
    override suspend fun callTool(name: String, arguments: JsonObject): ToolResult = onCall(name, arguments)
    override fun close() { closed = true }
}

class ToolRegistryTest {

    @Test
    fun `register connects client and merges its tools into the manifest`() = runTest {
        val registry = ToolRegistry()
        val client = FakeMcpClient(listOf(ToolSpec(name = "tavily_search", description = "Web search")))

        registry.register(client)

        assertTrue(client.connected)
        assertEquals(listOf("tavily_search"), registry.manifest().map { it.name })
        assertTrue(registry.hasTool("tavily_search"))
    }

    @Test
    fun `callTool routes to the owning client`() = runTest {
        val registry = ToolRegistry()
        val client = FakeMcpClient(
            listOf(ToolSpec(name = "tavily_search")),
            onCall = { name, _ -> ToolResult("result from $name") }
        )
        registry.register(client)

        val result = registry.callTool("tavily_search", JsonObject(emptyMap()))

        assertEquals("result from tavily_search", result.text)
        assertFalse(result.isError)
    }

    @Test
    fun `callTool on unknown tool returns error result without throwing`() = runTest {
        val registry = ToolRegistry()

        val result = registry.callTool("does_not_exist", JsonObject(emptyMap()))

        assertTrue(result.isError)
        assertTrue(result.text.contains("does_not_exist"))
    }

    @Test
    fun `closeAll closes every registered client and clears the manifest`() = runTest {
        val registry = ToolRegistry()
        val client = FakeMcpClient(listOf(ToolSpec(name = "tavily_search")))
        registry.register(client)

        registry.closeAll()

        assertTrue(client.closed)
        assertTrue(registry.manifest().isEmpty())
        assertFalse(registry.hasTool("tavily_search"))
    }

    @Test
    fun `manifest aggregates tools across multiple registered clients`() = runTest {
        val registry = ToolRegistry()
        registry.register(FakeMcpClient(listOf(ToolSpec(name = "tavily_search"))))
        registry.register(FakeMcpClient(listOf(ToolSpec(name = "tavily_extract"))))

        val names = registry.manifest().map { it.name }.toSet()

        assertEquals(setOf("tavily_search", "tavily_extract"), names)
    }
}
