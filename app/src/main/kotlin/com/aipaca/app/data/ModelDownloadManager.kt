package com.aipaca.app.data

import android.content.Context
import android.util.Log
import com.aipaca.app.EngineState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "ModelDownloadManager"
private const val BUFFER_SIZE_BYTES = 8 * 1024

/** Thrown when a streaming model download fails (non-2xx response, transport error, etc). */
class ModelDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Lifecycle state of a single tracked download. */
enum class DownloadState { DOWNLOADING, COMPLETED, FAILED, CANCELLED }

/**
 * Progress snapshot for one in-flight or finished download, keyed by
 * `"repoId/fileName"` in [ModelDownloadManager.downloadProgress].
 */
data class DownloadProgress(
    val repoId: String,
    val fileName: String,
    val bytesRead: Long = 0L,
    /** Total size in bytes, or -1 if the server didn't send a Content-Length. */
    val totalBytes: Long = -1L,
    val state: DownloadState = DownloadState.DOWNLOADING,
    val error: String? = null
) {
    /** 0f..1f complete, or -1f if [totalBytes] is unknown. */
    val fraction: Float
        get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else -1f
}

/**
 * Process-scoped singleton that streams GGUF/BIN model files from Hugging Face into
 * internal storage, tracking per-file progress and persisting completed downloads.
 *
 * Mirrors the [EngineState] pattern: a `lateinit appContext` populated via [init],
 * object-level [kotlinx.coroutines.flow.StateFlow] state that any number of collectors
 * (view models, Compose screens) can observe, and actions launched on the shared
 * [EngineState.scope] rather than a private scope — so downloads keep running across
 * configuration changes / screen navigation exactly like model loading does.
 *
 * Uses [HuggingFaceApi] to build the resolve/download URL and [DownloadedModelStore]
 * to persist completed downloads across process restarts.
 */
object ModelDownloadManager {

    lateinit var appContext: Context
        private set

    private lateinit var store: DownloadedModelStore
    private lateinit var modelsDir: File

    var httpClient: HttpClient = defaultHttpClient()
        private set

    /**
     * Coroutine scope downloads are launched on. Defaults to [EngineState.scope] lazily —
     * touching [EngineState] eagerly (e.g. as a property initialiser) would trigger its
     * `LlamaCppEngine` companion's `System.loadLibrary("aipaca")` even in plain JVM unit
     * tests, where the native lib isn't available. [configureForTest] overrides this with
     * a test scope so [EngineState] is never touched off-device.
     */
    private var _scope: CoroutineScope? = null
    private val scope: CoroutineScope
        get() = _scope ?: EngineState.scope

    fun init(context: Context) {
        appContext = context.applicationContext
        store = DownloadedModelStore(appContext)
        modelsDir = DownloadedModelStore.modelsDir(appContext)
        _downloadedModels.value = store.list()
    }

    /**
     * Test-only seam: wires the manager to a fake/in-memory [DownloadedModelStore], a
     * scratch [modelsDir], a mocked [httpClient], and a test [CoroutineScope] instead of
     * the real Android context / network / [EngineState.scope], and resets all in-memory
     * state. Production code must call [init].
     */
    internal fun configureForTest(
        store: DownloadedModelStore,
        modelsDir: File,
        httpClient: HttpClient,
        scope: CoroutineScope
    ) {
        this.store = store
        this.modelsDir = modelsDir
        this.httpClient = httpClient
        this._scope = scope
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        _downloads.value = emptyMap()
        _downloadedModels.value = store.list()
    }

    // ---- Observable state ---------------------------------------------------

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private val _downloadedModels = MutableStateFlow<List<DownloadedModelEntry>>(emptyList())
    val downloadedModels: StateFlow<List<DownloadedModelEntry>> = _downloadedModels.asStateFlow()

    private val activeJobs = mutableMapOf<String, Job>()

    // ---- Actions --------------------------------------------------------------

    /**
     * Starts streaming [fileName] from [repoId] into `context.filesDir/models/<fileName>`
     * on [EngineState.scope]. Progress is published to [downloadProgress] as bytes arrive;
     * on success the file is registered with [DownloadedModelStore] and [downloadedModels]
     * refreshed. On failure or cancellation the partial file is removed.
     *
     * A call for a (repoId, fileName) pair that already has an active download is a no-op.
     */
    fun startDownload(
        repoId: String,
        fileName: String,
        modelType: ModelType,
        downloadUrl: String = HuggingFaceApi.resolveUrl(repoId, fileName)
    ) {
        val k = key(repoId, fileName)
        if (activeJobs[k]?.isActive == true) {
            Log.w(TAG, "Download already in progress for $k")
            return
        }

        _downloads.update { it + (k to DownloadProgress(repoId, fileName, state = DownloadState.DOWNLOADING)) }

        val job = scope.launch {
            val targetFile = File(modelsDir, fileName)
            try {
                httpClient.prepareGet(downloadUrl).execute { response ->
                    if (!response.status.isSuccess()) {
                        throw ModelDownloadException(
                            "Hugging Face returned HTTP ${response.status} for $k"
                        )
                    }
                    val total = response.contentLength() ?: -1L
                    val channel = response.bodyAsChannel()
                    var bytesRead = 0L
                    _downloads.update {
                        it + (k to DownloadProgress(repoId, fileName, bytesRead, total, DownloadState.DOWNLOADING))
                    }
                    targetFile.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER_SIZE_BYTES)
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) continue
                            out.write(buffer, 0, read)
                            bytesRead += read
                            _downloads.update {
                                it + (k to DownloadProgress(repoId, fileName, bytesRead, total, DownloadState.DOWNLOADING))
                            }
                        }
                    }
                }

                val entry = DownloadedModelEntry(
                    repoId = repoId,
                    fileName = fileName,
                    filePath = targetFile.absolutePath,
                    sizeBytes = targetFile.length(),
                    modelType = modelType
                )
                _downloadedModels.value = store.add(entry)
                _downloads.update { current ->
                    val prior = current[k] ?: DownloadProgress(repoId, fileName)
                    current + (k to prior.copy(
                        bytesRead = targetFile.length(),
                        totalBytes = targetFile.length(),
                        state = DownloadState.COMPLETED
                    ))
                }
                Log.i(TAG, "Download complete: $k -> ${targetFile.absolutePath}")
            } catch (e: CancellationException) {
                targetFile.delete()
                _downloads.update { current ->
                    val prior = current[k] ?: DownloadProgress(repoId, fileName)
                    current + (k to prior.copy(state = DownloadState.CANCELLED))
                }
                Log.i(TAG, "Download cancelled: $k")
                throw e
            } catch (e: Exception) {
                targetFile.delete()
                Log.e(TAG, "Download failed for $k", e)
                _downloads.update { current ->
                    val prior = current[k] ?: DownloadProgress(repoId, fileName)
                    current + (k to prior.copy(state = DownloadState.FAILED, error = e.message ?: "Download failed"))
                }
            } finally {
                activeJobs.remove(k)
            }
        }
        activeJobs[k] = job
    }

    /** Cancels an in-flight download for (repoId, fileName), if any. The partial file is removed. */
    fun cancelDownload(repoId: String, fileName: String) {
        activeJobs[key(repoId, fileName)]?.cancel()
    }

    /**
     * Deletes a downloaded model: removes its file from `context.filesDir/models/`, its
     * [DownloadedModelStore] entry, and any tracked progress. Cancels an in-flight download
     * for the same key first, if any.
     */
    fun deleteDownload(repoId: String, fileName: String) {
        val k = key(repoId, fileName)
        activeJobs.remove(k)?.cancel()

        val entry = store.list().firstOrNull { it.repoId == repoId && it.fileName == fileName }
        val file = if (entry != null) File(entry.filePath) else File(modelsDir, fileName)
        file.delete()

        _downloadedModels.value = store.remove(repoId, fileName)
        _downloads.update { it - k }
    }

    /** Removes a finished (COMPLETED/FAILED/CANCELLED) entry from [downloadProgress]. */
    fun clearProgress(repoId: String, fileName: String) {
        _downloads.update { it - key(repoId, fileName) }
    }

    /** Job backing an in-flight download, if any — exposed for tests to await completion. */
    internal fun activeJob(repoId: String, fileName: String): Job? = activeJobs[key(repoId, fileName)]

    private fun key(repoId: String, fileName: String) = "$repoId/$fileName"

    private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            // Model files can be multi-gigabyte; don't time out mid-transfer.
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }
}
