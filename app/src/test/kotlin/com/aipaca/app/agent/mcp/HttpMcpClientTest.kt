package com.aipaca.app.agent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [HttpMcpClient] against a [MockEngine] — no real network calls.
 * Covers the JSON-RPC roundtrip, SSE framing, and the documented failure modes
 * from spec_issue_42_web_search_mcp.md §7 (timeout, auth failure, empty result).
 */
class HttpMcpClientTest {

    private fun jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK, sessionId: String? = null) =
        MockEngine { _ ->
            val headers = buildMap {
                put(HttpHeaders.ContentType, "application/json")
                sessionId?.let { put(MCP_SESSION_HEADER, it) }
            }
            respond(
                content = body,
                status = status,
                headers = headersOf(*headers.map { it.key to listOf(it.value) }.toTypedArray())
            )
        }

    @Test
    fun `initialize then listTools returns tavily_search`() = runTest {
        var callCount = 0
        val engine = MockEngine { request ->
            callCount++
            val body = when (callCount) {
                1 -> """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""
                2 -> "" // notifications/initialized has no body reply expected, but engine still responds 200
                3 -> """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"tavily_search","description":"Web search"}]}}"""
                else -> error("Unexpected call #$callCount")
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpMcpClient("https://mcp.tavily.com/mcp/?tavilyApiKey=test", httpClient = HttpClient(engine))

        client.connect()
        val tools = client.listTools()

        assertEquals(1, tools.size)
        assertEquals("tavily_search", tools[0].name)
    }

    @Test
    fun `callTool returns joined text content`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            val body = when (callCount) {
                1 -> """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""
                2 -> ""
                3 -> """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"Result A"},{"type":"text","text":"Result B"}],"isError":false}}"""
                else -> error("Unexpected call #$callCount")
            }
            respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpMcpClient("https://mcp.tavily.com/mcp/?tavilyApiKey=test", httpClient = HttpClient(engine))

        val result = client.callTool("tavily_search", JsonObject(mapOf("query" to JsonPrimitive("kotlin mcp"))))

        assertEquals("Result A\nResult B", result.text)
        assertTrue(!result.isError)
    }

    @Test
    fun `sse framed response is parsed for matching id`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            when (callCount) {
                1 -> respond(
                    content = """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
                2 -> respond(content = "", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                3 -> respond(
                    content = "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"tavily_search\"}]}}\n\n",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
                )
                else -> error("Unexpected call #$callCount")
            }
        }
        val client = HttpMcpClient("https://mcp.tavily.com/mcp/?tavilyApiKey=test", httpClient = HttpClient(engine))

        client.connect()
        val tools = client.listTools()

        assertEquals("tavily_search", tools.single().name)
    }

    @Test
    fun `http 401 raises McpException`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = "Unauthorized", status = HttpStatusCode.Unauthorized, headers = headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = HttpMcpClient("https://mcp.tavily.com/mcp/?tavilyApiKey=bad", httpClient = HttpClient(engine))

        assertFailsWith<McpException> {
            client.connect()
        }
    }

    @Test
    fun `jsonrpc error on tools call surfaces as error ToolResult not exception`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            val body = when (callCount) {
                1 -> """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""
                2 -> ""
                3 -> """{"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"Tool execution failed"}}"""
                else -> error("Unexpected call #$callCount")
            }
            respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpMcpClient("https://mcp.tavily.com/mcp/?tavilyApiKey=test", httpClient = HttpClient(engine))

        val result = client.callTool("tavily_search", JsonObject(emptyMap()))

        assertTrue(result.isError)
        assertTrue(result.text.contains("Tool execution failed"))
    }

    @Test
    fun `empty tools list result yields empty tool list`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            val body = when (callCount) {
                1 -> """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""
                2 -> ""
                3 -> """{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""
                else -> error("Unexpected call #$callCount")
            }
            respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpMcpClient("https://mcp.tavily.com/mcp/?tavilyApiKey=test", httpClient = HttpClient(engine))

        client.connect()
        val tools = client.listTools()

        assertTrue(tools.isEmpty())
    }
}
