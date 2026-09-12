package com.aipaca.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_NAME = "agent_prefs"
private const val KEY_TAVILY_API_KEY = "tavily_api_key"
private const val KEY_MCP_SERVER_URL = "mcp_server_url"
private const val KEY_WEB_SEARCH_ENABLED = "agent_enabled"
private const val KEY_ITERS_SINCE_SKILL = "iters_since_skill"
private const val KEY_TURNS_SINCE_MEMORY = "turns_since_memory"
private const val KEY_MIGRATED_TO_FTS = "migrated_to_fts"
private const val KEY_MEMORY_LOOP_ENABLED = "memory_loop_enabled"
private const val KEY_LAST_CONSOLIDATION_AT = "last_consolidation_at"
private const val KEY_ENTRIES_SINCE_CONSOLIDATION = "entries_since_consolidation"
private const val KEY_CONSOLIDATION_INTERVAL_HOURS = "consolidation_interval_hours"
private const val KEY_SOUL_SEEDED = "soul_seeded"

/** Default Tavily remote MCP endpoint (Streamable HTTP). API key is appended as a query param. */
const val DEFAULT_TAVILY_MCP_BASE_URL = "https://mcp.tavily.com/mcp/"

/**
 * Encrypted storage for agent configuration — web-search credentials and the
 * counters that drive the self-learning memory loops.
 *
 * Uses [EncryptedSharedPreferences] (AES256-GCM), matching the pattern established by
 * `ChatConversationStore`. The API key must never appear in logs or task handoffs
 * (see spec_issue_42_web_search_mcp.md §5.5).
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

    // ---- Web search (Tavily MCP) -------------------------------------------

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
    fun isWebSearchEnabled(): Boolean = prefs.getBoolean(KEY_WEB_SEARCH_ENABLED, false)

    fun setWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEB_SEARCH_ENABLED, enabled).apply()
    }

    /**
     * True once both an API key and consent are present.
     *
     * This gates the web-search tool only. It deliberately no longer gates the agent
     * loop as a whole: the local tools (memory, session recall, skills) need no
     * network, and tying them to a Tavily key is what made the learn pass unreachable.
     */
    fun isWebSearchConfigured(): Boolean = isWebSearchEnabled() && !getTavilyApiKey().isNullOrBlank()

    // ---- Learn-pass counters ------------------------------------------------

    fun getItersSinceSkill(): Int = prefs.getInt(KEY_ITERS_SINCE_SKILL, 0)
    fun setItersSinceSkill(count: Int) { prefs.edit().putInt(KEY_ITERS_SINCE_SKILL, count).apply() }

    fun getTurnsSinceMemory(): Int = prefs.getInt(KEY_TURNS_SINCE_MEMORY, 0)
    fun setTurnsSinceMemory(count: Int) { prefs.edit().putInt(KEY_TURNS_SINCE_MEMORY, count).apply() }

    // ---- Consolidation loop (L3) -------------------------------------------

    /** Master switch for all automatic memory writing. Visible on the Memory screen. */
    fun isMemoryLoopEnabled(): Boolean = prefs.getBoolean(KEY_MEMORY_LOOP_ENABLED, true)
    fun setMemoryLoopEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEMORY_LOOP_ENABLED, enabled).apply()
    }

    fun getLastConsolidationAt(): Long = prefs.getLong(KEY_LAST_CONSOLIDATION_AT, 0L)
    fun setLastConsolidationAt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_CONSOLIDATION_AT, millis).apply()
    }

    /**
     * Memory entries written since the last consolidation. The dream only runs when
     * there is actually something new to curate — an interval alone either burns
     * battery on an unchanged file or lets a heavily used one rot for weeks.
     */
    fun getEntriesSinceConsolidation(): Int = prefs.getInt(KEY_ENTRIES_SINCE_CONSOLIDATION, 0)
    fun setEntriesSinceConsolidation(count: Int) {
        prefs.edit().putInt(KEY_ENTRIES_SINCE_CONSOLIDATION, count).apply()
    }
    fun addEntriesSinceConsolidation(delta: Int) {
        if (delta <= 0) return
        setEntriesSinceConsolidation(getEntriesSinceConsolidation() + delta)
    }

    fun getConsolidationIntervalHours(): Int = prefs.getInt(KEY_CONSOLIDATION_INTERVAL_HOURS, 24)
    fun setConsolidationIntervalHours(hours: Int) {
        prefs.edit().putInt(KEY_CONSOLIDATION_INTERVAL_HOURS, hours.coerceIn(1, 24 * 7)).apply()
    }

    // ---- One-time setup flags ----------------------------------------------

    fun hasMigratedToFts(): Boolean = prefs.getBoolean(KEY_MIGRATED_TO_FTS, false)
    fun setMigratedToFts(done: Boolean) { prefs.edit().putBoolean(KEY_MIGRATED_TO_FTS, done).apply() }

    fun hasSeededSoul(): Boolean = prefs.getBoolean(KEY_SOUL_SEEDED, false)
    fun setSeededSoul(done: Boolean) { prefs.edit().putBoolean(KEY_SOUL_SEEDED, done).apply() }
}
