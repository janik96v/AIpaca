package com.aipaca.app.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Minimal in-memory [SharedPreferences] fake — enough surface for
 * [DownloadedModelStore], no Android framework/Robolectric required so this
 * runs as a plain JVM unit test.
 */
private class FakeSharedPreferences : SharedPreferences {
    private val backing = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = backing.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        backing[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (backing[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = backing[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = backing[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = backing[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        backing[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = backing.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            pending[key!!] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key!!] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            pending[key!!] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) backing.clear()
            for ((k, v) in pending) {
                if (v == null) backing.remove(k) else backing[k] = v
            }
        }
    }
}

class DownloadedModelStoreTest {

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: DownloadedModelStore

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = DownloadedModelStore(prefs)
    }

    private fun sampleEntry(
        repoId: String = "unsloth/gemma-4-E2B-it-GGUF",
        fileName: String = "gemma-4-E2B-it-Q4_0.gguf",
        filePath: String = "/data/data/com.aipaca.app/files/models/gemma-4-E2B-it-Q4_0.gguf",
        sizeBytes: Long = 2_500_000_000L,
        modelType: ModelType = ModelType.LLM
    ) = DownloadedModelEntry(repoId, fileName, filePath, sizeBytes, modelType)

    @Test
    fun `list is empty before anything is added`() {
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `add persists an entry that reload returns identically`() {
        val entry = sampleEntry()
        store.add(entry)

        // Simulate app restart: a fresh store wrapping the same underlying prefs.
        val reloaded = DownloadedModelStore(prefs)
        val entries = reloaded.list()

        assertEquals(1, entries.size)
        assertEquals(entry, entries.first())
    }

    @Test
    fun `add multiple entries of different model types round-trips all fields`() {
        val llm = sampleEntry()
        val whisper = sampleEntry(
            repoId = "ggerganov/whisper.cpp",
            fileName = "ggml-base.bin",
            filePath = "/data/data/com.aipaca.app/files/models/ggml-base.bin",
            sizeBytes = 147_000_000L,
            modelType = ModelType.WHISPER
        )
        store.add(llm)
        store.add(whisper)

        val reloaded = DownloadedModelStore(prefs).list()
        assertEquals(2, reloaded.size)
        assertTrue(reloaded.contains(llm))
        assertTrue(reloaded.contains(whisper))
        assertEquals(ModelType.WHISPER, reloaded.first { it.fileName == "ggml-base.bin" }.modelType)
    }

    @Test
    fun `add with same repoId and fileName replaces the previous entry`() {
        val original = sampleEntry(sizeBytes = 100L)
        val updated = sampleEntry(sizeBytes = 200L)

        store.add(original)
        store.add(updated)

        val entries = store.list()
        assertEquals(1, entries.size)
        assertEquals(200L, entries.first().sizeBytes)
    }

    @Test
    fun `remove deletes the matching entry and survives reload`() {
        val entry = sampleEntry()
        store.add(entry)
        assertEquals(1, store.list().size)

        store.remove(entry.repoId, entry.fileName)

        assertTrue(store.list().isEmpty())
        assertTrue(DownloadedModelStore(prefs).list().isEmpty())
    }

    @Test
    fun `remove with unknown key is a no-op`() {
        val entry = sampleEntry()
        store.add(entry)

        store.remove("nonexistent/repo", "nonexistent.gguf")

        assertEquals(1, store.list().size)
    }

    @Test
    fun `list returns empty on corrupt stored json`() {
        prefs.edit().putString("entries_json", "{not valid json").apply()
        assertTrue(DownloadedModelStore(prefs).list().isEmpty())
    }
}
