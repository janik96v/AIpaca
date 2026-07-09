package com.aipaca.app.agent.tool

import com.aipaca.app.agent.mcp.HttpMcpClient
import com.aipaca.app.agent.mcp.McpClient
import com.aipaca.app.data.AgentPrefs
import com.aipaca.app.data.DEFAULT_TAVILY_MCP_BASE_URL

/**
 * Config wrapper that builds an [McpClient] for the Tavily remote MCP server.
 *
 * The API key is passed as the `tavilyApiKey` query parameter on the endpoint URL,
 * per Tavily's documented remote-MCP contract
 * (`https://mcp.tavily.com/mcp/?tavilyApiKey=<key>`), not as a bearer header —
 * see research/10_research_mcp_kotlin.md.
 */
object TavilyMcp {

    /** The tool name Tavily's `tools/list` advertises for web search. */
    const val SEARCH_TOOL_NAME = "tavily_search"

    /**
     * Builds a ready-to-connect [McpClient] from stored [AgentPrefs], or null if the
     * agent isn't configured yet (missing key / consent not granted).
     */
    fun buildClient(prefs: AgentPrefs): McpClient? {
        if (!prefs.isConfigured()) return null
        val apiKey = prefs.getTavilyApiKey() ?: return null
        val baseUrl = prefs.getMcpServerUrl().ifBlank { DEFAULT_TAVILY_MCP_BASE_URL }
        return HttpMcpClient(serverUrl = endpointUrl(baseUrl, apiKey))
    }

    /** Appends `tavilyApiKey` as a query parameter, respecting any existing query string. */
    fun endpointUrl(baseUrl: String, apiKey: String): String {
        val separator = if (baseUrl.contains('?')) "&" else "?"
        return "$baseUrl${separator}tavilyApiKey=$apiKey"
    }
}
