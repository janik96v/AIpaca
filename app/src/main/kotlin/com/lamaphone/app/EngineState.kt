package com.lamaphone.app

import android.util.Log
import com.lamaphone.app.engine.GenerateParams
import com.lamaphone.app.engine.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

private const val TAG = "EngineState"

/**
 * Process-scoped singleton that owns the single [LlamaCppEngine] instance.
 *
 * Both the Chat UI (via ViewModel / Compose collectAsState) and the API server
 * read from and write to this object so that exactly one engine ever exists.
 *
 * Observable state is exposed as [StateFlow] so any number of collectors can
 * react to model-load / unload / generation events without coupling to each other.
 */
object EngineState {

    // ---- Engine ------------------------------------------------------------

    val engine: LlamaCppEngine = LlamaCppEngine()

    // ---- Coroutine scope ---------------------------------------------------

    /**
     * Long-lived scope tied to the application process.
     * [SupervisorJob] prevents one failed child from cancelling siblings.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- Observable state --------------------------------------------------

    private val _modelPath    = MutableStateFlow<String?>(null)
    val modelPath: StateFlow<String?> = _modelPath.asStateFlow()

    private val _isLoaded     = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Number of GPU layers actually used by the loaded model.
     * -1 = no model loaded, 0 = CPU-only (GPU probe failed or disabled), >0 = GPU layers count.
     */
    private val _gpuLayers = MutableStateFlow(-1)
    val gpuLayers: StateFlow<Int> = _gpuLayers.asStateFlow()

    // ---- Actions -----------------------------------------------------------

    /**
     * Load a GGUF model from [path].
     *
     * Suspending — callers should invoke from a coroutine (e.g. ViewModel.viewModelScope).
     * Updates [isLoaded], [modelPath], and [errorMessage] accordingly.
     *
     * @param nThreads    CPU threads; defaults to available processors capped at 6.
     * @param contextSize KV-cache token window.
     */
    suspend fun loadModel(
        path: String,
        nThreads: Int    = Runtime.getRuntime().availableProcessors().coerceAtMost(6),
        contextSize: Int = 512,
        nGpuLayers: Int  = -1   // -1 = all layers (full GPU offload)
    ): Result<Unit> {
        _errorMessage.value = null
        _isLoaded.value     = false
        _modelPath.value    = null

        Log.i(TAG, "loadModel: $path  threads=$nThreads  ctx=$contextSize  gpu_layers=$nGpuLayers")
        val result = engine.loadModel(path, nThreads, contextSize, nGpuLayers)

        result.fold(
            onSuccess = {
                _isLoaded.value  = true
                _modelPath.value = path
                _gpuLayers.value = engine.getActiveGpuLayers()
                val gpuInfo = if (_gpuLayers.value > 0) "GPU (${_gpuLayers.value} layers)" else "CPU only"
                Log.i(TAG, "Model ready — backend: $gpuInfo")
            },
            onFailure = { e ->
                _gpuLayers.value = -1
                _errorMessage.value = e.message ?: "Unknown load error"
                Log.e(TAG, "loadModel failed", e)
            }
        )
        return result
    }

    /**
     * Unload the current model and free all native resources.
     */
    fun unload() {
        scope.launch {
            Log.i(TAG, "Unloading model")
            engine.stopGeneration()
            engine.unload()
            _isLoaded.value     = false
            _modelPath.value    = null
            _isGenerating.value = false
            _gpuLayers.value    = -1
        }
    }

    /**
     * Convenience wrapper: runs generation on [scope] and updates [isGenerating].
     *
     * For fine-grained control (e.g. streaming into a message list) callers can
     * collect [engine].generate() directly instead.
     */
    fun generate(
        userPrompt: String,
        params: GenerateParams        = GenerateParams(),
        onToken: (String) -> Unit,
        onDone: () -> Unit            = {},
        onError: (Throwable) -> Unit  = {}
    ) {
        scope.launch {
            engine.generate(userPrompt, params)
                .onStart      { _isGenerating.value = true }
                .onCompletion { _isGenerating.value = false; onDone() }
                .catch        { e -> _errorMessage.value = e.message; onError(e) }
                .collect      { token -> onToken(token) }
        }
    }
}
