package com.aipaca.app.agent

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [ToolCallParser] against real-shaped tool-call samples for both supported
 * handlers (Hermes 2 Pro / Qwen 2.5, Mistral Nemo / Mistral-7B-Instruct-v0.3),
 * per spec_issue_42_web_search_mcp.md §7.
 */
class ToolCallParserTest {

    @Test
    fun `parses hermes 2 pro tool_call format`() {
        val text = """
            <tool_call>
            {"name": "tavily_search", "arguments": {"query": "kotlin coroutines"}}
            </tool_call>
        """.trimIndent()

        val call = ToolCallParser.parse(text)

        requireNotNull(call)
        assertEquals("tavily_search", call.name)
        assertEquals("kotlin coroutines", call.arguments["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parses mistral nemo TOOL_CALLS format`() {
        val text = """[TOOL_CALLS][{"name": "tavily_search", "arguments": {"query": "edge llm context"}}]"""

        val call = ToolCallParser.parse(text)

        requireNotNull(call)
        assertEquals("tavily_search", call.name)
        assertEquals("edge llm context", call.arguments["query"]?.jsonPrimitive?.content)
    }

    @Test
    fun `plain text without tool call returns null`() {
        val call = ToolCallParser.parse("The capital of France is Paris.")
        assertNull(call)
    }

    @Test
    fun `malformed tool_call json returns null instead of throwing`() {
        val text = "<tool_call>{not valid json</tool_call>"
        val call = ToolCallParser.parse(text)
        assertNull(call)
    }

    @Test
    fun `containsToolCallMarker detects both handler markers`() {
        assertTrue(ToolCallParser.containsToolCallMarker("<tool_call>{}"))
        assertTrue(ToolCallParser.containsToolCallMarker("[TOOL_CALLS][{}]"))
        assertTrue(!ToolCallParser.containsToolCallMarker("just plain text"))
    }

    @Test
    fun `missing arguments defaults to empty object`() {
        val text = """<tool_call>{"name": "tavily_search"}</tool_call>"""
        val call = ToolCallParser.parse(text)
        requireNotNull(call)
        assertEquals("tavily_search", call.name)
        assertTrue(call.arguments.isEmpty())
    }
}
