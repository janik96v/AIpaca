package com.aipaca.app

import android.content.Context
import android.util.Log
import com.aipaca.app.data.MmprojModelPrefs
import com.aipaca.app.data.OllamaPrefs
import com.aipaca.app.data.WhisperModelPrefs
import com.aipaca.app.engine.BenchResult
import com.aipaca.app.engine.ChatTurn
import com.aipaca.app.engine.GenerateParams
import com.aipaca.app.engine.GgufProbeResult
import com.aipaca.app.engine.LlamaCppEngine
import com.aipaca.app.engine.ModelHeader
import com.aipaca.app.engine.ModelHeaders
import com.aipaca.app.engine.ModelInfo
import com.aipaca.app.engine.OllamaEngine
import com.aipaca.app.engine.WhisperEngine
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
import kotlinx.coroutines.sync.Mutex

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

    // ---- Application context (injected from AIpacaApp.onCreate) -----------

    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---- Engines -----------------------------------------------------------

    val engine: LlamaCppEngine = LlamaCppEngine()
    val whisperEngine: WhisperEngine = WhisperEngine()
    val ollamaEngine: OllamaEngine = OllamaEngine()

    /**
     * Shared serialization lock for anything that calls [engine].generateChat().
     *
     * There are two generation consumers in the process — the OpenAI-compatible
     * server ([com.aipaca.app.server.ApiServer]) and the on-device agent loop
     * ([com.aipaca.app.agent.AgentOrchestrator]). Both MUST acquire this mutex before
     * calling into the engine; llama.cpp has exactly one context and concurrent
     * decode calls corrupt native state / crash (see spec_issue_43_agent_mode.md §6.2).
     */
    val generateMutex: Mutex = Mutex()

    /**
     * Single-flight guard for the memory loops (extraction, session summary,
     * consolidation). They all drive the same single engine context, and two of them
     * running at once would queue behind [generateMutex] and delay the user's own turn.
     */
    val memoryPassMutex: Mutex = Mutex()

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

    private val _isLoadingModel = MutableStateFlow(false)
    val isLoadingModel: StateFlow<Boolean> = _isLoadingModel.asStateFlow()

    /** Path of the model whose load is in flight, null when idle. */
    private val _loadingModelPath = MutableStateFlow<String?>(null)
    val loadingModelPath: StateFlow<String?> = _loadingModelPath.asStateFlow()

    /**
     * Header of the model being loaded or loaded — read *before* the weights so
     * the UI can describe the model (and build its shape) while it loads.
     * Null when no local model is loaded or its header could not be read.
     */
    private val _modelHeader = MutableStateFlow<ModelHeader?>(null)
    val modelHeader: StateFlow<ModelHeader?> = _modelHeader.asStateFlow()

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

    /**
     * Model quantization info. [ModelInfo.gpuCompatible] is false when the quant type
     * (e.g. Q4_K_M) lacks optimised Adreno OpenCL kernels and will silently fall back
     * to CPU — causing dramatically reduced inference speed.
     */
    private val _modelInfo = MutableStateFlow(ModelInfo())
    val modelInfo: StateFlow<ModelInfo> = _modelInfo.asStateFlow()

    private val _contextSize = MutableStateFlow(10240)
    val contextSize: StateFlow<Int> = _contextSize.asStateFlow()

    /**
     * Compute context-picker options based on model architecture and device RAM.
     *
     * Probes the GGUF header directly when [modelPath] is provided (before model
     * load), or uses the loaded [ModelInfo] as fallback. This ensures the picker
     * shows accurate, model-specific options even before loading.
     */
    data class ContextSizeConfig(
        val options: List<Int>,
        val recommended: Int,
        val maxSafe: Int,
        val isRecurrent: Boolean
    )

    fun computeContextConfig(modelPath: String? = null): ContextSizeConfig {
        val probe = modelPath?.let { engine.probeGgufMeta(it) }

        val isRecurrent = probe?.isRecurrentKV ?: _modelInfo.value.isRecurrentKV
        val nCtxTrain = (probe?.nCtxTrain ?: _modelInfo.value.nCtxTrain)
        val nParams = probe?.nParams ?: _modelInfo.value.nParams
        val quant = probe?.quant ?: _modelInfo.value.quant
        val arch = probe?.architecture ?: _modelInfo.value.architecture
        val nLayer = probe?.nLayer ?: 0
        val nHeadKv = probe?.nHeadKv ?: 0
        val dHead = probe?.dHead ?: 0

        if (isRecurrent) {
            val trainCtx = nCtxTrain.coerceAtLeast(8192)
            val options = listOf(16384, 32768, 65536, 131072, 262144)
                .filter { it <= trainCtx * 4 }
                .ifEmpty { listOf(16384) }
            return ContextSizeConfig(
                options = options,
                recommended = options.firstOrNull { it >= 65536 } ?: options.last(),
                maxSafe = options.last(),
                isRecurrent = true
            )
        }

        val totalRamMb = getTotalDeviceRamMb()
        val modelWeightMb = estimateModelWeightRamMb(nParams, quant)
        val reserveMb = 3072L  // 3 GB for Android OS + app overhead
        val availableForKvMb = (totalRamMb - modelWeightMb - reserveMb).coerceAtLeast(512)

        val kvBytesPerToken = estimateKvBytesPerToken(nParams, nLayer, nHeadKv, dHead)
        val maxCtxFromRam = if (kvBytesPerToken > 0)
            ((availableForKvMb * 1024 * 1024) / kvBytesPerToken).toInt()
        else
            65536
        val trainCtx = nCtxTrain.coerceAtLeast(2048)
        val maxSafe = maxCtxFromRam.coerceAtMost(trainCtx).coerceAtLeast(2048)

        val allOptions = listOf(4096, 8192, 16384, 32768, 65536, 131072)
        val options = allOptions.filter { it <= maxSafe }
            .ifEmpty { listOf(allOptions.first { it >= 2048 }) }
        val recommended = options.firstOrNull { it >= 10240 } ?: options.last()

        Log.i(TAG, "contextConfig: totalRam=${totalRamMb}MB  modelWeight=${modelWeightMb}MB  " +
                "kvPerToken=${kvBytesPerToken}B  maxSafe=$maxSafe  recommended=$recommended  " +
                "nCtxTrain=$trainCtx  arch=$arch  probe=${probe != null}")

        return ContextSizeConfig(
            options = options,
            recommended = recommended,
            maxSafe = maxSafe,
            isRecurrent = false
        )
    }

    private fun getTotalDeviceRamMb(): Long {
        return try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.totalMem / (1024 * 1024)
        } catch (_: Exception) {
            12288L // conservative 12 GB fallback
        }
    }

    private fun estimateModelWeightRamMb(nParams: Long, quant: String): Long {
        val bitsPerParam = when {
            quant.contains("Q2")   -> 2.5
            quant.contains("Q3")   -> 3.5
            quant.contains("IQ4")  -> 4.5
            quant.contains("Q4")   -> 4.5
            quant.contains("Q5")   -> 5.5
            quant.contains("Q6")   -> 6.5
            quant.contains("Q8")   -> 8.0
            quant.contains("F16")  -> 16.0
            quant.contains("F32")  -> 32.0
            else                    -> 5.0
        }
        return ((nParams * bitsPerParam) / 8 / 1024 / 1024).toLong()
    }

    private fun estimateKvBytesPerToken(
        nParams: Long, nLayer: Int, nHeadKv: Int, dHead: Int
    ): Long {
        // GPU path uses f16 KV, CPU path uses q8_0 KV (see nativeLoadModel)
        val gpuPath = _gpuLayers.value > 0
        val kvTypeBytes = if (gpuPath) 2L else 1L

        // Use exact GGUF dimensions when available from probe
        if (nLayer > 0 && nHeadKv > 0 && dHead > 0) {
            // KV per token = 2 (K+V) × n_layer × n_head_kv × d_head × kvTypeBytes
            return 2L * nLayer * nHeadKv * dHead * kvTypeBytes
        }

        // Fallback: estimate from nParams
        if (nParams <= 0) return 1024L
        val estLayers: Long
        val estEmbd: Long
        when {
            nParams <  2_000_000_000L -> { estLayers = 16; estEmbd = 2048 }
            nParams <  5_000_000_000L -> { estLayers = 32; estEmbd = 3072 }
            nParams < 10_000_000_000L -> { estLayers = 32; estEmbd = 4096 }
            nParams < 20_000_000_000L -> { estLayers = 40; estEmbd = 5120 }
            else                      -> { estLayers = 48; estEmbd = 6144 }
        }
        return 2 * estLayers * estEmbd * kvTypeBytes
    }

    private val _lastBenchmark = MutableStateFlow(BenchResult())
    val lastBenchmark: StateFlow<BenchResult> = _lastBenchmark.asStateFlow()

    private val _isBenchmarking = MutableStateFlow(false)
    val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    // ---- Whisper STT state -------------------------------------------------

    private val _whisperModelPath = MutableStateFlow<String?>(null)
    val whisperModelPath: StateFlow<String?> = _whisperModelPath.asStateFlow()

    private val _isLoadingWhisperModel = MutableStateFlow(false)
    val isLoadingWhisperModel: StateFlow<Boolean> = _isLoadingWhisperModel.asStateFlow()

    /** Header of the Whisper model being loaded or loaded (ggml `.bin`). */
    private val _whisperHeader = MutableStateFlow<ModelHeader?>(null)
    val whisperHeader: StateFlow<ModelHeader?> = _whisperHeader.asStateFlow()

    private val _whisperError = MutableStateFlow<String?>(null)
    val whisperError: StateFlow<String?> = _whisperError.asStateFlow()

    // ---- Vision projector (mmproj) state -----------------------------------

    private val _mmprojPath = MutableStateFlow<String?>(null)
    val mmprojPath: StateFlow<String?> = _mmprojPath.asStateFlow()

    private val _isMmprojLoaded = MutableStateFlow(false)
    val isMmprojLoaded: StateFlow<Boolean> = _isMmprojLoaded.asStateFlow()

    private val _isLoadingMmproj = MutableStateFlow(false)
    val isLoadingMmproj: StateFlow<Boolean> = _isLoadingMmproj.asStateFlow()

    private val _mmprojError = MutableStateFlow<String?>(null)
    val mmprojError: StateFlow<String?> = _mmprojError.asStateFlow()

    // ---- Ollama remote LLM state -------------------------------------------

    private val _useOllama = MutableStateFlow(false)
    val useOllama: StateFlow<Boolean> = _useOllama.asStateFlow()

    // ---- Agent capability --------------------------------------------------

    /**
     * Whether the active backend can do native tool calling.
     *
     * Probed from the GGUF's Jinja chat template on load (see
     * [LlamaCppEngine.supportsToolCalling]); assumed true for the Ollama backend,
     * which handles tool schemas server-side. This is the main input to
     * [com.aipaca.app.agent.TierPolicy] — a model without tool support gets plain
     * chat rather than a tool manifest it will only mangle.
     */
    private val _toolCallingSupported = MutableStateFlow(false)
    val toolCallingSupported: StateFlow<Boolean> = _toolCallingSupported.asStateFlow()

    // ---- Memory stores (process-wide, shared by chat, UI and the maintenance worker) ----

    val memoryStore: com.aipaca.app.agent.memory.MemoryStore by lazy {
        com.aipaca.app.agent.memory.MemoryStore(appContext)
    }
    val sessionIndexStore: com.aipaca.app.agent.memory.SessionIndexStore by lazy {
        com.aipaca.app.agent.memory.SessionIndexStore(appContext)
    }
    val skillStore: com.aipaca.app.agent.memory.SkillStore by lazy {
        com.aipaca.app.agent.memory.SkillStore(appContext)
    }

    /** Sandbox roots for the agent's `files` tool (issue #54). */
    val agentWorkspace: com.aipaca.app.agent.tool.AgentWorkspace by lazy {
        com.aipaca.app.agent.tool.AgentWorkspace(appContext).also { it.ensureRoots() }
    }

    /**
     * Generation surface for the memory passes, bound to whichever backend is active.
     *
     * The local engine serializes through [generateMutex] because llama.cpp has one
     * context; Ollama is stateless HTTP and needs no lock.
     */
    fun memoryEngine(): com.aipaca.app.agent.memory.MemoryEngine =
        if (_useOllama.value) {
            com.aipaca.app.agent.memory.MemoryEngine(
                generate = { turns, params ->
                    ollamaEngine.resetThinkingState()
                    ollamaEngine.generateChat(turns, params)
                },
                lock = null
            )
        } else {
            com.aipaca.app.agent.memory.MemoryEngine(
                generate = { turns, params -> engine.generateChat(turns, params) },
                lock = generateMutex
            )
        }

    /** True when some backend is ready to run a memory pass. */
    fun canRunMemoryPass(): Boolean = _useOllama.value || engine.isLoaded()

    private val _ollamaModelName = MutableStateFlow("")
    val ollamaModelName: StateFlow<String> = _ollamaModelName.asStateFlow()

    /**
     * Enable Ollama remote mode. The local llama.cpp engine is preserved but inactive.
     * [isLoaded] reflects Ollama readiness so the rest of the app treats it as "model loaded".
     */
    fun enableOllama(serverUrl: String, model: String) {
        ollamaEngine.serverUrl = serverUrl
        ollamaEngine.modelName = model
        _ollamaModelName.value = model
        _useOllama.value = true
        _toolCallingSupported.value = true
        // Synthetic "loaded" state so chat/agent paths proceed
        _isLoaded.value = true
        _modelInfo.value = ModelInfo(
            quant = "remote",
            modelName = "Ollama: $model",
            supportsThinking = true,
            supportsMultimodal = false
        )
        OllamaPrefs.setEnabled(appContext, true)
        OllamaPrefs.saveServerUrl(appContext, serverUrl)
        OllamaPrefs.saveModelName(appContext, model)
        Log.i(TAG, "Ollama enabled: $serverUrl model=$model")
    }

    fun disableOllama() {
        _useOllama.value = false
        _ollamaModelName.value = ""
        OllamaPrefs.setEnabled(appContext, false)
        // Restore real model state
        _toolCallingSupported.value = engine.isLoaded() && engine.supportsToolCalling()
        _isLoaded.value = engine.isLoaded()
        if (engine.isLoaded()) {
            _modelInfo.value = engine.getModelInfo()
        } else {
            _modelInfo.value = ModelInfo()
        }
        Log.i(TAG, "Ollama disabled, local model loaded=${engine.isLoaded()}")
    }

    /** Restore Ollama mode from persisted preferences (called during init). */
    fun restoreOllamaIfEnabled() {
        if (OllamaPrefs.isEnabled(appContext)) {
            val url = OllamaPrefs.getServerUrl(appContext)
            val model = OllamaPrefs.getModelName(appContext)
            enableOllama(url, model)
        }
    }

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
        contextSize: Int = 10240,
        nGpuLayers: Int  = -1   // -1 = all layers (full GPU offload)
    ): Result<Unit> {
        _errorMessage.value = null
        _isLoadingModel.value = true
        _loadingModelPath.value = path
        _isLoaded.value     = false
        _modelPath.value    = null
        _gpuLayers.value    = -1
        _modelInfo.value    = ModelInfo()
        _modelHeader.value  = null

        Log.i(TAG, "loadModel: $path  threads=$nThreads  ctx=$contextSize  gpu_layers=$nGpuLayers")
        _contextSize.value = contextSize
        return try {
            // Header first (no weights): lets the UI name the architecture and
            // build the model's shape while the weights are still loading.
            _modelHeader.value = ModelHeaders.read(path, engine)
            val result = engine.loadModel(path, nThreads, contextSize, nGpuLayers)

            result.fold(
                onSuccess = {
                    _isLoaded.value  = true
                    _modelPath.value = path
                    _gpuLayers.value = engine.getActiveGpuLayers()
                    _modelInfo.value = engine.getModelInfo()
                    _toolCallingSupported.value = engine.supportsToolCalling()
                    val gpuInfo = if (_gpuLayers.value > 0) "GPU (${_gpuLayers.value} layers)" else "CPU only"
                    val quantInfo = _modelInfo.value.quant
                    val gpuCompat = if (_gpuLayers.value > 0 && !_modelInfo.value.gpuCompatible)
                        " [WARNING: $quantInfo not GPU-optimised — expect slow inference]" else ""
                    Log.i(TAG, "Model ready — backend: $gpuInfo  quant: $quantInfo$gpuCompat")
                },
                onFailure = { e ->
                    _gpuLayers.value = -1
                    _modelInfo.value = ModelInfo()
                    _modelHeader.value = null
                    _toolCallingSupported.value = false
                    _errorMessage.value = e.message ?: "Unknown load error"
                    Log.e(TAG, "loadModel failed", e)
                }
            )
            result
        } finally {
            _isLoadingModel.value = false
            _loadingModelPath.value = null
        }
    }

    /**
     * Unload the current model and free all native resources.
     */
    fun unload() {
        Log.i(TAG, "Unloading model")
        _isLoaded.value       = false
        _modelPath.value      = null
        _isLoadingModel.value = false
        _loadingModelPath.value = null
        _isGenerating.value   = false
        _gpuLayers.value      = -1
        _modelInfo.value      = ModelInfo()
        _modelHeader.value    = null
        _toolCallingSupported.value = false
        _lastBenchmark.value  = BenchResult()
        _isBenchmarking.value = false
        // Clear mmproj state — it depends on the model
        _isMmprojLoaded.value = false
        _mmprojPath.value     = null
        _mmprojError.value    = null
        MmprojModelPrefs.clearPath(appContext)
        scope.launch {
            engine.stopGeneration()
            engine.unload()
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
                .collect      { chunk -> onToken(chunk.content) }
        }
    }

    fun generateChat(
        turns: List<ChatTurn>,
        params: GenerateParams        = GenerateParams(),
        onToken: (String) -> Unit,
        onDone: () -> Unit            = {},
        onError: (Throwable) -> Unit  = {}
    ) {
        scope.launch {
            engine.generateChat(turns, params)
                .onStart      { _isGenerating.value = true }
                .onCompletion { _isGenerating.value = false; onDone() }
                .catch        { e -> _errorMessage.value = e.message; onError(e) }
                .collect      { chunk -> onToken(chunk.content) }
        }
    }

    suspend fun loadWhisperModel(path: String): Result<Unit> {
        _whisperError.value = null
        _isLoadingWhisperModel.value = true
        return try {
            _whisperHeader.value = ModelHeaders.read(path, engine)
            val result = whisperEngine.loadModel(path)
            result.fold(
                onSuccess = {
                    _whisperModelPath.value = path
                    WhisperModelPrefs.savePath(appContext, path)
                    Log.i(TAG, "Whisper model loaded: $path")
                },
                onFailure = { e ->
                    _whisperHeader.value = if (whisperEngine.isLoaded) {
                        _whisperModelPath.value?.let { ModelHeaders.peek(it) }
                    } else null
                    _whisperError.value = e.message ?: "Failed to load whisper model"
                    Log.e(TAG, "loadWhisperModel failed", e)
                }
            )
            result
        } finally {
            _isLoadingWhisperModel.value = false
        }
    }

    fun unloadWhisper() {
        whisperEngine.unload()
        _whisperModelPath.value = null
        _whisperHeader.value = null
        _whisperError.value = null
        WhisperModelPrefs.clearPath(appContext)
        Log.i(TAG, "Whisper model unloaded")
    }

    // ---- Vision projector (mmproj) actions ---------------------------------

    suspend fun loadMmproj(path: String): Result<Unit> {
        _mmprojError.value = null
        _isLoadingMmproj.value = true
        return try {
            val ok = engine.loadMmproj(path)
            if (ok) {
                _isMmprojLoaded.value = true
                _mmprojPath.value = path
                MmprojModelPrefs.savePath(appContext, path)
                Log.i(TAG, "mmproj loaded: $path")
                Result.success(Unit)
            } else {
                _mmprojError.value = "Failed to load vision projector"
                Log.e(TAG, "loadMmproj failed for $path")
                Result.failure(IllegalStateException("Failed to load vision projector"))
            }
        } catch (e: Exception) {
            _mmprojError.value = e.message ?: "Failed to load vision projector"
            Log.e(TAG, "loadMmproj exception", e)
            Result.failure(e)
        } finally {
            _isLoadingMmproj.value = false
        }
    }

    fun unloadMmproj() {
        engine.unloadMmproj()
        _isMmprojLoaded.value = false
        _mmprojPath.value = null
        _mmprojError.value = null
        MmprojModelPrefs.clearPath(appContext)
        Log.i(TAG, "mmproj unloaded")
    }

    suspend fun benchmark(pp: Int = 128, tg: Int = 128, pl: Int = 1, nr: Int = 3): Result<BenchResult> {
        _isBenchmarking.value = true
        _errorMessage.value = null
        return try {
            val result = engine.benchmark(pp, tg, pl, nr)
            result.fold(
                onSuccess = {
                    _lastBenchmark.value = it
                    Log.i(TAG, "Benchmark: pp=${it.ppAvg} tok/s tg=${it.tgAvg} tok/s gpuLayers=${it.gpuLayers}")
                },
                onFailure = { e ->
                    _errorMessage.value = e.message
                    Log.e(TAG, "Benchmark failed", e)
                }
            )
            result
        } finally {
            _isBenchmarking.value = false
        }
    }
}
