package com.aipaca.app.data

import android.content.Context

private const val PREFS_NAME = "ollama_prefs"
private const val KEY_SERVER_URL = "ollama_server_url"
private const val KEY_MODEL_NAME = "ollama_model_name"
private const val KEY_ENABLED = "ollama_enabled"

/** Plain SharedPreferences for Ollama remote LLM connection settings (not sensitive). */
object OllamaPrefs {

    fun saveServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "http://192.168.1.100:11434") ?: "http://192.168.1.100:11434"

    fun saveModelName(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL_NAME, model).apply()
    }

    fun getModelName(context: Context): String =
        prefs(context).getString(KEY_MODEL_NAME, "qwen3:30b") ?: "qwen3:30b"

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
