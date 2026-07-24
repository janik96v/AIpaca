package com.aipaca.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "DownloadedModelStore"
private const val PREFS_NAME = "downloaded_model_store"
private const val KEY_ENTRIES = "entries_json"

/** Kind of model a [DownloadedModelEntry] represents. */
@Serializable
enum class ModelType { LLM, WHISPER }

/**
 * A single downloaded-from-Hugging-Face model file that has been persisted to
 * internal storage.
 *
 * @param repoId Hugging Face repo id, e.g. "unsloth/gemma-4-E2B-it-GGUF".
 * @param fileName The GGUF/BIN file name within the repo, e.g. "gemma-4-E2B-it-Q4_0.gguf".
 * @param filePath Absolute path on internal storage where the file was saved
 *   (under `context.filesDir/models/`).
 * @param sizeBytes File size in bytes.
 * @param modelType Whether this is an LLM (llama.cpp) or Whisper (STT) model.
 */
@Serializable
data class DownloadedModelEntry(
    val repoId: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val modelType: ModelType
)

/**
 * SharedPreferences-based persistence for models downloaded from Hugging Face.
 *
 * Follows the plain (non-encrypted) SharedPreferences pattern used by
 * [WhisperModelPrefs] / [MmprojModelPrefs] — model paths are not sensitive —
 * but stores a JSON-encoded list under a single key since, unlike those
 * single-path prefs, multiple downloaded models must be tracked at once.
 *
 * The primary constructor takes a [SharedPreferences] instance directly so
 * it can be unit-tested with a fake/in-memory implementation without needing
 * Robolectric or an instrumented device. The [Context]-based secondary
 * constructor is what call sites normally use.
 */
class DownloadedModelStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(DownloadedModelEntry.serializer())

    /** Returns all persisted entries. Empty list if none or on parse failure. */
    fun list(): List<DownloadedModelEntry> {
        val rawJson = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read downloaded model entries", e)
            emptyList()
        }
    }

    /**
     * Adds [entry], replacing any existing entry with the same (repoId, fileName)
     * pair. Returns the updated list.
     */
    fun add(entry: DownloadedModelEntry): List<DownloadedModelEntry> {
        val updated = list()
            .filterNot { it.repoId == entry.repoId && it.fileName == entry.fileName } + entry
        return save(updated)
    }

    /** Removes the entry matching (repoId, fileName), if any. Returns the updated list. */
    fun remove(repoId: String, fileName: String): List<DownloadedModelEntry> {
        return save(list().filterNot { it.repoId == repoId && it.fileName == fileName })
    }

    private fun save(entries: List<DownloadedModelEntry>): List<DownloadedModelEntry> {
        prefs.edit()
            .putString(KEY_ENTRIES, json.encodeToString(serializer, entries))
            .apply()
        return entries
    }

    companion object {
        /**
         * Internal storage directory where downloaded model files are written.
         * Created on demand. Cleaned up automatically by the OS on app uninstall.
         */
        fun modelsDir(context: Context): File =
            File(context.applicationContext.filesDir, "models").apply { mkdirs() }
    }
}
