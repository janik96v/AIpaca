package com.aipaca.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_NAME = "agent_prefs"
private const val KEY_TAVILY_API_KEY = "tavily_api_key"
private const val KEY_MCP_SERVER_URL = "mcp_server_url"
private const val KEY_AGENT_ENABLED = "agent_enabled"

/** Default Tavily remote MCP endpoint (Streamable HTTP). API key is appended as a query param. */
const val DEFAULT_TAVILY_MCP_BASE_URL = "https://mcp.tavily.com/mcp/"

/**
 * Encrypted storage for agent/MCP configuration — Tavily API key and MCP server URL.
 *
 * Uses [EncryptedSharedPreferences] (AES256-GCM), matching the pattern already
 * established by `ChatConversationStore`. The API key must never appear in logs
 * or task handoffs (see spec_issue_42_web_search_mcp.md §5.5).
 */
class AgentPrefs(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveTavilyApiKey(apiKey: String) {
        prefs.edit().putString(KEY_TAVILY_API_KEY, apiKey).apply()
    }

    fun getTavilyApiKey(): String? = prefs.getString(KEY_TAVILY_API_KEY, null)

    fun clearTavilyApiKey() {
        prefs.edit().remove(KEY_TAVILY_API_KEY).apply()
    }

    fun saveMcpServerUrl(url: String) {
        prefs.edit().putString(KEY_MCP_SERVER_URL, url).apply()
    }

    fun getMcpServerUrl(): String = prefs.getString(KEY_MCP_SERVER_URL, null) ?: DEFAULT_TAVILY_MCP_BASE_URL

    /** Explicit user opt-in required before the agent may perform any network tool call. */
    fun isAgentEnabled(): Boolean = prefs.getBoolean(KEY_AGENT_ENABLED, false)

    fun setAgentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AGENT_ENABLED, enabled).apply()
    }

    /** True once both an API key and consent are present — the minimum to connect. */
    fun isConfigured(): Boolean = isAgentEnabled() && !getTavilyApiKey().isNullOrBlank()
}
