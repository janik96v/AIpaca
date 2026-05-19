package com.aipaca.app.data

import android.content.Context

private const val PREFS_NAME = "whisper_model_prefs"
private const val KEY_MODEL_PATH = "whisper_model_path"

/** Plain SharedPreferences for the whisper model file path (not sensitive). */
object WhisperModelPrefs {

    fun savePath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL_PATH, path).apply()
    }

    fun getPath(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_PATH, null)

    fun clearPath(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_MODEL_PATH).apply()
    }
}
