package com.aipaca.app.data

import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Minimal in-memory [SharedPreferences] fake — same shape as the one in
 * [DownloadedModelStoreTest], duplicated locally to keep each test file
 * self-contained (test sources aren't shared production code).
 */
private class FakeDownloadPrefs : SharedPreferences {
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

/**
 * Unit tests for [ModelDownloadManager] against a fake [DownloadedModelStore], a scratch
 * temp directory standing in for `context.filesDir/models/`, and a Ktor [MockEngine] —
 * no real Android context, network, or [com.aipaca.app.EngineState] touched.
 *
 * Downloads run on a real (non-virtual-time) [CoroutineScope] since [MockEngine] dispatches
 * its response handling on its own real dispatcher — a virtual-time `TestDispatcher` would
 * never observe that work complete. Each test drives the download to completion by fetching
 * the in-flight [Job] via [ModelDownloadManager.activeJob] and joining it inside [runBlocking].
 */
class ModelDownloadManagerTest {

    private lateinit var tempDir: File
    private lateinit var store: DownloadedModelStore
    private lateinit var scope: CoroutineScope
    private val repoId = "org/repo"
    private val fileName = "model.Q4_K_M.gguf"

    @Before
    fun setUp() {
        tempDir = File.createTempFile("model-download-test", "").apply {
            delete()
            mkdirs()
        }
        store = DownloadedModelStore(FakeDownloadPrefs())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun configure(body: ByteArray, status: HttpStatusCode = HttpStatusCode.OK) {
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentLength, body.size.toString())
            )
        }
        ModelDownloadManager.configureForTest(
            store = store,
            modelsDir = tempDir,
            httpClient = HttpClient(engine),
            scope = scope
        )
    }

    private fun configureError(status: HttpStatusCode) {
        val engine = MockEngine { respondError(status) }
        ModelDownloadManager.configureForTest(
            store = store,
            modelsDir = tempDir,
            httpClient = HttpClient(engine),
            scope = scope
        )
    }

    /** Starts the download and blocks (via [runBlocking]) until its job finishes. */
    private fun startAndAwait(repoId: String = this.repoId, fileName: String = this.fileName) = runBlocking {
        ModelDownloadManager.startDownload(repoId, fileName, ModelType.LLM)
        ModelDownloadManager.activeJob(repoId, fileName)?.join()
    }

    @Test
    fun `startDownload transitions from DOWNLOADING to COMPLETED and reflects total bytes read`() {
        val payload = ByteArray(50_000) { (it % 256).toByte() }
        configure(payload)

        startAndAwait()

        val k = "$repoId/$fileName"
        val progress = ModelDownloadManager.downloadProgress.value.getValue(k)

        assertEquals(DownloadState.COMPLETED, progress.state)
        assertEquals(payload.size.toLong(), progress.bytesRead)
        assertEquals(payload.size.toLong(), progress.totalBytes)
        assertEquals(1f, progress.fraction)
    }

    @Test
    fun `startDownload writes the file and registers it with DownloadedModelStore`() {
        val payload = ByteArray(1_000) { 7 }
        configure(payload)

        startAndAwait()

        val downloadedFile = File(tempDir, fileName)
        assertTrue(downloadedFile.exists())
        assertEquals(payload.size.toLong(), downloadedFile.length())

        val entries = ModelDownloadManager.downloadedModels.value
        assertEquals(1, entries.size)
        assertEquals(repoId, entries.first().repoId)
        assertEquals(fileName, entries.first().fileName)
        assertEquals(ModelType.LLM, entries.first().modelType)
        assertEquals(downloadedFile.absolutePath, entries.first().filePath)
    }

    @Test
    fun `startDownload on http error marks progress FAILED and removes partial file`() {
        configureError(HttpStatusCode.NotFound)

        startAndAwait()

        val k = "$repoId/$fileName"
        val progress = ModelDownloadManager.downloadProgress.value.getValue(k)
        assertEquals(DownloadState.FAILED, progress.state)
        assertTrue(progress.error != null)
        assertTrue(ModelDownloadManager.downloadedModels.value.isEmpty())
        assertTrue(!File(tempDir, fileName).exists())
    }

    @Test
    fun `deleteDownload removes the file and the store entry`() {
        val payload = ByteArray(2_000) { 1 }
        configure(payload)

        startAndAwait()

        assertTrue(File(tempDir, fileName).exists())
        assertEquals(1, ModelDownloadManager.downloadedModels.value.size)

        ModelDownloadManager.deleteDownload(repoId, fileName)

        assertTrue(!File(tempDir, fileName).exists())
        assertTrue(ModelDownloadManager.downloadedModels.value.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `deleteDownload also clears any tracked progress entry`() {
        val payload = ByteArray(2_000) { 1 }
        configure(payload)

        startAndAwait()

        val k = "$repoId/$fileName"
        assertTrue(ModelDownloadManager.downloadProgress.value.containsKey(k))

        ModelDownloadManager.deleteDownload(repoId, fileName)

        assertTrue(!ModelDownloadManager.downloadProgress.value.containsKey(k))
    }

    @Test
    fun `starting a second download for the same key while one is active is a no-op`() = runBlocking {
        val payload = ByteArray(200_000) { 3 }
        configure(payload)

        ModelDownloadManager.startDownload(repoId, fileName, ModelType.LLM)
        val firstJob = ModelDownloadManager.activeJob(repoId, fileName)
        // Second call before the first job has finished — must not replace/duplicate it.
        ModelDownloadManager.startDownload(repoId, fileName, ModelType.LLM)
        val secondJob = ModelDownloadManager.activeJob(repoId, fileName)

        assertTrue(firstJob != null)
        assertEquals(firstJob, secondJob)

        firstJob?.join()

        assertEquals(1, ModelDownloadManager.downloadedModels.value.size)
    }

    @Test
    fun `clearProgress removes a finished entry from downloadProgress`() {
        val payload = ByteArray(500) { 9 }
        configure(payload)

        startAndAwait()

        val k = "$repoId/$fileName"
        assertTrue(ModelDownloadManager.downloadProgress.value.containsKey(k))

        ModelDownloadManager.clearProgress(repoId, fileName)

        assertTrue(!ModelDownloadManager.downloadProgress.value.containsKey(k))
    }
}
