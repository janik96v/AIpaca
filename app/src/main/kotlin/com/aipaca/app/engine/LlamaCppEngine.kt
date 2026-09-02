package com.aipaca.app.engine

import android.util.Log
import com.aipaca.app.agent.AgentMessage
import com.aipaca.app.agent.toMessagesJson
import com.aipaca.app.agent.toToolsJson
import com.aipaca.app.agent.mcp.ToolSpec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "LlamaCppEngine"

/**
 * Java interface invoked from the C++ JNI bridge for each generated token.
 *
 * The method name and descriptor must exactly match what llama_jni.cpp looks up:
 *   env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;Ljava/lang/String;)V")
 */
interface TokenCallback {
    fun onToken(content: String, thinking: String)
}

/**
 * Production implementation of [InferenceEngine] backed by llama.cpp via JNI.
 *
 * Thread-safety contract:
 *  - [loadModel] and [unload] serialise native calls via [Dispatchers.IO].
 *  - [generate] is safe to call from any coroutine; work is offloaded internally.
 *  - [stopGeneration] is safe to call from any thread at any time.
 */
class LlamaCppEngine : InferenceEngine {

    // ---- Native library loading --------------------------------------------

    companion object {
        init {
            System.loadLibrary("aipaca")
        }
    }

    // ---- JNI declarations --------------------------------------------------
    // All names must match Java_com_aipaca_app_engine_LlamaCppEngine_nativeXxx

    private external fun nativeLoadModel(
        modelPath: String,
        nThreads: Int,
        nCtx: Int,
        nGpuLayers: Int
    ): Long

    /**
     * Probes whether the GPU backend works for this context by running a
     * single 1-token decode guarded by a POSIX signal handler (sigsetjmp/siglongjmp).
     * Returns true if the decode completes without a SIGSEGV/SIGBUS, false otherwise.
     * Must be called immediately after [nativeLoadModel] before any other decode.
     */
    private external fun nativeProbeGpu(ctxPtr: Long): Boolean

    /** Returns the number of GPU layers in use for this context (0 = CPU-only). */
    private external fun nativeGetActiveGpuLayers(ctxPtr: Long): Int

    private external fun nativeGenerate(
        ctxPtr: Long,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        maxTokens: Int,
        callback: TokenCallback
    )

    private external fun nativeGenerateChat(
        ctxPtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        thinkingBudget: Int,
        callback: TokenCallback
    )

    private external fun nativeBench(ctxPtr: Long, pp: Int, tg: Int, pl: Int, nr: Int): String

    private external fun nativeStopGeneration(ctxPtr: Long)

    private external fun nativeUnloadModel(ctxPtr: Long)

    private external fun nativeGetSystemInfo(): String

    /** Returns JSON model info: {"quant":"Q4_K_M","ftype":15,"gpuCompatible":false} */
    private external fun nativeGetModelInfo(ctxPtr: Long): String

    /** Lightweight GGUF header probe — no model loading. */
    private external fun nativeProbeGgufMeta(modelPath: String): String

    /** Returns the model's chat template string, or null if unavailable. */
    private external fun nativeGetChatTemplate(ctxPtr: Long): String?

    // ---- Vision / mmproj JNI -----------------------------------------------

    private external fun nativeLoadMmproj(ctxPtr: Long, path: String, useGpu: Boolean): Boolean
    private external fun nativeUnloadMmproj(ctxPtr: Long)
    private external fun nativeIsMmprojLoaded(ctxPtr: Long): Boolean
    private external fun nativeGenerateChatWithImage(
        ctxPtr: Long,
        roles: Array<String>,
        contents: Array<String>,
        imageBytes: ByteArray,
        imageSize: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        thinkingBudget: Int,
        callback: TokenCallback
    )

    // ---- Agent / tool-calling JNI -------------------------------------------

    /** True when the loaded GGUF's chat template actually renders tool calls. */
    private external fun nativeProbeToolSupport(ctxPtr: Long): Boolean

    private external fun nativeGenerateAgent(
        ctxPtr: Long,
        messagesJson: String,
        toolsJson: String,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        thinkingBudget: Int,
        callback: TokenCallback
    ): String

    // ---- Mutable state -----------------------------------------------------

    /** Holds the native LlamaContext* cast to Long; 0 means no model loaded. */
    private val contextPtr      = AtomicLong(0L)

    /** Probe result for the currently loaded model; cleared on load/unload. */
    private val toolSupportCache = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
    private val _isLoaded       = AtomicBoolean(false)
    private val _modelPath      = AtomicReference<String?>(null)
    private val _activeGpuLayers = AtomicInteger(-1)

    @Volatile private var lastTokensPerSec: Float = 0f
    @Volatile private var lastTotalTokens: Int    = 0

    // ---- InferenceEngine ---------------------------------------------------

    /**
     * Load a GGUF model file. Runs the blocking native call on [Dispatchers.IO].
     * Any previously-loaded model is unloaded first.
     *
     * After loading with GPU layers, runs [nativeProbeGpu] to verify the GPU
     * backend actually works. If the probe fails (Adreno driver SIGSEGV), the
     * GPU context is freed and the model is reloaded in CPU-only mode.
     */
    override suspend fun loadModel(
        modelPath: String,
        nThreads: Int,
        contextSize: Int,
        nGpuLayers: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_isLoaded.get()) {
                Log.i(TAG, "Unloading previous model before re-load")
                unload()
            }

            Log.i(TAG, "loadModel: $modelPath  threads=$nThreads  ctx=$contextSize  gpu_layers=$nGpuLayers")

            // --- Step 1: load with requested GPU layers ---
            val ptr = nativeLoadModel(modelPath, nThreads, contextSize, nGpuLayers)
            if (ptr == 0L) {
                return@withContext Result.failure(IllegalStateException(
                    "nativeLoadModel returned null — check logcat for details"))
            }

            // --- Step 2: probe GPU if any layers were offloaded ---
            val requestedGpu = nGpuLayers != 0
            val gpuProbeOk = if (requestedGpu) {
                Log.i(TAG, "Probing GPU backend...")
                nativeProbeGpu(ptr)
            } else {
                true  // CPU-only requested explicitly, skip probe
            }

            val activePtr: Long
            if (!gpuProbeOk) {
                // GPU probe failed — free GPU context and reload CPU-only
                Log.w(TAG, "GPU probe FAILED — backend crash detected. Reloading in CPU-only mode.")
                nativeUnloadModel(ptr)

                val cpuPtr = nativeLoadModel(modelPath, nThreads, contextSize, 0)
                if (cpuPtr == 0L) {
                    return@withContext Result.failure(IllegalStateException(
                        "nativeLoadModel (CPU fallback) returned null — check logcat"))
                }
                activePtr = cpuPtr
                _activeGpuLayers.set(0)
                Log.i(TAG, "Model loaded in CPU-only fallback mode")
            } else {
                activePtr = ptr
                _activeGpuLayers.set(nativeGetActiveGpuLayers(ptr))
                Log.i(TAG, "GPU probe OK — using ${_activeGpuLayers.get()} GPU layers")
            }

            contextPtr.set(activePtr)
            toolSupportCache.set(null)   // re-probe for the newly loaded model
            _isLoaded.set(true)
            _modelPath.set(modelPath)
            Log.i(TAG, "Model loaded, ptr=$activePtr  activeGpuLayers=${_activeGpuLayers.get()}")
            Log.i(TAG, "System info: ${nativeGetSystemInfo()}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "loadModel exception", e)
            Result.failure(e)
        }
    }

    /**
     * Stream generated tokens as a cold [Flow].
     * Collecting the flow starts generation; cancelling it (or calling
     * [stopGeneration]) aborts the native loop.
     *
     * [nativeGenerate] blocks until generation finishes or [stop_flag] is set,
     * so it runs on [Dispatchers.IO] inside the flow.
     */
    override fun generate(
        userPrompt: String,
        params: GenerateParams
    ): Flow<GenerationChunk> {
        val turns = buildList {
            if (params.systemPrompt.isNotBlank()) add(ChatTurn("system", params.systemPrompt))
            add(ChatTurn("user", userPrompt))
        }
        return generateChat(turns, params)
    }

    override fun generateChat(
        turns: List<ChatTurn>,
        params: GenerateParams
    ): Flow<GenerationChunk> = callbackFlow {
        val ptr = contextPtr.get()
        if (ptr == 0L) {
            close(IllegalStateException("No model loaded"))
            return@callbackFlow
        }
        val cleanTurns = turns
            .filter { it.content.isNotBlank() }
            .map { turn ->
                val role = when (turn.role.lowercase()) {
                    "system", "assistant", "user" -> turn.role.lowercase()
                    else -> "user"
                }
                ChatTurn(role, turn.content)
            }
        if (cleanTurns.isEmpty()) {
            close(IllegalArgumentException("No chat turns provided"))
            return@callbackFlow
        }

        val startMs    = System.currentTimeMillis()
        var tokenCount = 0

        val callback = object : TokenCallback {
            override fun onToken(content: String, thinking: String) {
                tokenCount++
                trySend(GenerationChunk(content = content, thinking = thinking))
            }
        }

        try {
            withContext(Dispatchers.IO) {
                nativeGenerateChat(
                    ctxPtr       = ptr,
                    roles        = cleanTurns.map { it.role }.toTypedArray(),
                    contents     = cleanTurns.map { it.content }.toTypedArray(),
                    temperature  = params.temperature,
                    topP         = params.topP,
                    repeatPenalty = params.repeatPenalty,
                    maxTokens    = params.maxTokens,
                    thinkingBudget = if (params.thinkingEnabled) -1 else 0,
                    callback     = callback
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "generate exception", e)
            close(e)
            return@callbackFlow
        } finally {
            val elapsedSec   = (System.currentTimeMillis() - startMs) / 1000f
            lastTokensPerSec = if (elapsedSec > 0f) tokenCount / elapsedSec else 0f
            lastTotalTokens  = tokenCount
            Log.d(TAG, "Generation done: $tokenCount tokens @ ${"%.1f".format(lastTokensPerSec)} tok/s")
        }

        close()

        // Called when the downstream collector cancels the flow
        awaitClose { nativeStopGeneration(ptr) }
    }

    override suspend fun benchmark(pp: Int, tg: Int, pl: Int, nr: Int): Result<BenchResult> =
        withContext(Dispatchers.IO) {
            val ptr = contextPtr.get()
            if (ptr == 0L) {
                return@withContext Result.failure(IllegalStateException("No model loaded"))
            }
            try {
                val json = nativeBench(ptr, pp, tg, pl, nr)
                if (json.contains("\"error\"")) {
                    return@withContext Result.failure(IllegalStateException(json))
                }
                Result.success(
                    BenchResult(
                        ppAvg = Regex("\"ppAvg\":([0-9.Ee+-]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
                        tgAvg = Regex("\"tgAvg\":([0-9.Ee+-]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f,
                        ppRuns = Regex("\"ppRuns\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                        tgRuns = Regex("\"tgRuns\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                        gpuLayers = Regex("\"gpuLayers\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
                        pureQ4_0 = json.contains("\"pureQ4_0\":true"),
                        tensorHistogram = Regex("\"tensorHistogram\":(\\{[^}]*\\})").find(json)?.groupValues?.get(1) ?: "{}"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "benchmark failed", e)
                Result.failure(e)
            }
        }

    /** Signal the native loop to stop. Safe to call from any thread. */
    override fun stopGeneration() {
        val ptr = contextPtr.get()
        if (ptr != 0L) {
            Log.d(TAG, "stopGeneration requested")
            nativeStopGeneration(ptr)
        }
    }

    /** Free all native resources. Resets the context pointer atomically. */
    override fun unload() {
        val ptr = contextPtr.getAndSet(0L)
        toolSupportCache.set(null)
        if (ptr != 0L) {
            Log.i(TAG, "Unloading model, ptr=$ptr")
            _isLoaded.set(false)
            _modelPath.set(null)
            _activeGpuLayers.set(-1)
            nativeUnloadModel(ptr)
        }
    }

    override fun isLoaded(): Boolean = _isLoaded.get()

    override fun getModelPath(): String? = _modelPath.get()

    override fun getActiveGpuLayers(): Int = _activeGpuLayers.get()

    override fun getStats(): InferenceStats = InferenceStats(
        tokensPerSecond = lastTokensPerSec,
        totalTokens     = lastTotalTokens,
        modelName       = _modelPath.get()
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?: ""
    )

    // ---- Vision / mmproj public API ----------------------------------------

    suspend fun loadMmproj(path: String, useGpu: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            val ptr = contextPtr.get()
            if (ptr == 0L) {
                Log.e(TAG, "loadMmproj: no model loaded")
                return@withContext false
            }
            Log.i(TAG, "loadMmproj: $path  useGpu=$useGpu")
            nativeLoadMmproj(ptr, path, useGpu)
        }

    fun unloadMmproj() {
        val ptr = contextPtr.get()
        if (ptr != 0L) {
            nativeUnloadMmproj(ptr)
        }
    }

    fun isMmprojLoaded(): Boolean {
        val ptr = contextPtr.get()
        return ptr != 0L && nativeIsMmprojLoaded(ptr)
    }

    /**
     * Whether the loaded model can do native tool calling — the input that decides
     * which execution tier a turn gets (see `agent/AgentTier.kt`).
     *
     * Probes the Jinja template once and caches the answer for the loaded model;
     * a model whose template ignores tools silently produces prose instead of tool
     * calls, and without this check every such model would be handed a tool manifest
     * it cannot use.
     *
     * On any native failure this reports `true`, which is the pre-probe behaviour:
     * the loop then attempts tools and falls back to a plain answer after a couple
     * of malformed calls, rather than losing tool support outright.
     */
    fun supportsToolCalling(): Boolean {
        val ptr = contextPtr.get()
        if (ptr == 0L) return false
        toolSupportCache.get()?.let { return it }
        val supported = try {
            nativeProbeToolSupport(ptr)
        } catch (t: Throwable) {
            Log.w(TAG, "tool-support probe unavailable, assuming supported", t)
            true
        }
        toolSupportCache.set(supported)
        Log.i(TAG, "tool calling supported: $supported")
        return supported
    }

    fun generateChatWithImage(
        turns: List<ChatTurn>,
        imageBytes: ByteArray,
        params: GenerateParams
    ): Flow<GenerationChunk> = callbackFlow {
        val ptr = contextPtr.get()
        if (ptr == 0L) {
            close(IllegalStateException("No model loaded"))
            return@callbackFlow
        }
        if (!nativeIsMmprojLoaded(ptr)) {
            close(IllegalStateException("No vision projector loaded"))
            return@callbackFlow
        }
        val cleanTurns = turns
            .filter { it.content.isNotBlank() }
            .map { turn ->
                val role = when (turn.role.lowercase()) {
                    "system", "assistant", "user" -> turn.role.lowercase()
                    else -> "user"
                }
                ChatTurn(role, turn.content)
            }
        if (cleanTurns.isEmpty()) {
            close(IllegalArgumentException("No chat turns provided"))
            return@callbackFlow
        }

        val startMs    = System.currentTimeMillis()
        var tokenCount = 0

        val callback = object : TokenCallback {
            override fun onToken(content: String, thinking: String) {
                tokenCount++
                trySend(GenerationChunk(content = content, thinking = thinking))
            }
        }

        try {
            withContext(Dispatchers.IO) {
                nativeGenerateChatWithImage(
                    ctxPtr        = ptr,
                    roles         = cleanTurns.map { it.role }.toTypedArray(),
                    contents      = cleanTurns.map { it.content }.toTypedArray(),
                    imageBytes    = imageBytes,
                    imageSize     = imageBytes.size,
                    temperature   = params.temperature,
                    topP          = params.topP,
                    repeatPenalty = params.repeatPenalty,
                    maxTokens     = params.maxTokens,
                    thinkingBudget = if (params.thinkingEnabled) -1 else 0,
                    callback      = callback
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateChatWithImage exception", e)
            close(e)
            return@callbackFlow
        } finally {
            val elapsedSec   = (System.currentTimeMillis() - startMs) / 1000f
            lastTokensPerSec = if (elapsedSec > 0f) tokenCount / elapsedSec else 0f
            lastTotalTokens  = tokenCount
            Log.d(TAG, "Vision gen done: $tokenCount tokens @ ${"%.1f".format(lastTokensPerSec)} tok/s")
        }

        close()

        awaitClose { nativeStopGeneration(ptr) }
    }

    /**
     * Expose llama.cpp system info string (CPU features, BLAS flags, etc.)
     * for diagnostic use (e.g. Settings screen).
     */
    fun getSystemInfo(): String =
        if (_isLoaded.get()) nativeGetSystemInfo() else "model not loaded"

    // ---- Agent / tool-calling public API ------------------------------------

    private val agentJson = Json { ignoreUnknownKeys = true }

    /**
     * Generate with native tool-calling support.
     *
     * Streams [AgentChunk]s during generation, then returns a final [AgentResult]
     * as the last emitted element (with [AgentChunk.content] = "" and tool calls
     * in a trailing emission). The caller collects the flow and uses the JNI
     * return value to build the structured [AgentResult].
     */
    fun generateAgent(
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
        params: GenerateParams
    ): Flow<AgentChunk> = callbackFlow {
        val ptr = contextPtr.get()
        if (ptr == 0L) {
            close(IllegalStateException("No model loaded"))
            return@callbackFlow
        }

        val messagesJson = messages.toMessagesJson()
        val toolsJson = tools.toToolsJson()
        Log.d(TAG, "generateAgent: ${messages.size} messages, toolsJson=${toolsJson.length} chars, thinkingBudget=${if (params.thinkingEnabled) -1 else 0}")
        Log.d(TAG, "generateAgent messagesJson: ${messagesJson.take(500)}...")

        val startMs    = System.currentTimeMillis()
        var tokenCount = 0

        val callback = object : TokenCallback {
            override fun onToken(content: String, thinking: String) {
                tokenCount++
                trySend(AgentChunk(content = content, thinking = thinking))
            }
        }

        try {
            val resultJson = withContext(Dispatchers.IO) {
                nativeGenerateAgent(
                    ctxPtr         = ptr,
                    messagesJson   = messagesJson,
                    toolsJson      = toolsJson,
                    temperature    = params.temperature,
                    topP           = params.topP,
                    repeatPenalty  = params.repeatPenalty,
                    maxTokens      = params.maxTokens,
                    thinkingBudget = if (params.thinkingEnabled) -1 else 0,
                    callback       = callback
                )
            }

            // Parse structured result from JNI return value
            val result = parseAgentResult(resultJson)
            // Emit a final chunk carrying the parsed tool calls (if any)
            if (result.hasToolCalls) {
                trySend(AgentChunk(content = "__AGENT_RESULT__:$resultJson"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateAgent exception", e)
            close(e)
            return@callbackFlow
        } finally {
            val elapsedSec   = (System.currentTimeMillis() - startMs) / 1000f
            lastTokensPerSec = if (elapsedSec > 0f) tokenCount / elapsedSec else 0f
            lastTotalTokens  = tokenCount
            Log.d(TAG, "Agent gen done: $tokenCount tokens @ ${"%.1f".format(lastTokensPerSec)} tok/s")
        }

        close()
        awaitClose { nativeStopGeneration(ptr) }
    }

    /**
     * Convenience: run agent generation and collect the full [AgentResult] (non-streaming).
     */
    suspend fun generateAgentResult(
        messages: List<AgentMessage>,
        tools: List<ToolSpec>,
        params: GenerateParams
    ): AgentResult = withContext(Dispatchers.IO) {
        val ptr = contextPtr.get()
        if (ptr == 0L) throw IllegalStateException("No model loaded")

        val messagesJson = messages.toMessagesJson()
        val toolsJson = tools.toToolsJson()

        val callback = object : TokenCallback {
            override fun onToken(content: String, thinking: String) { /* discard streaming */ }
        }

        val resultJson = nativeGenerateAgent(
            ctxPtr         = ptr,
            messagesJson   = messagesJson,
            toolsJson      = toolsJson,
            temperature    = params.temperature,
            topP           = params.topP,
            repeatPenalty  = params.repeatPenalty,
            maxTokens      = params.maxTokens,
            thinkingBudget = if (params.thinkingEnabled) -1 else 0,
            callback       = callback
        )

        parseAgentResult(resultJson)
    }

    private fun parseAgentResult(jsonStr: String): AgentResult {
        return try {
            val root = agentJson.parseToJsonElement(jsonStr).jsonObject
            if (root.containsKey("error")) {
                Log.e(TAG, "Agent JNI error: ${root["error"]}")
                return AgentResult(content = root["error"]?.jsonPrimitive?.content ?: "unknown error")
            }
            val content = root["content"]?.jsonPrimitive?.content ?: ""
            val toolCalls = root["tool_calls"]?.jsonArray?.map { elem ->
                val obj = elem.jsonObject
                AgentToolCall(
                    id            = obj["id"]?.jsonPrimitive?.content ?: "",
                    name          = obj["name"]?.jsonPrimitive?.content ?: "",
                    argumentsJson = obj["arguments"]?.toString() ?: "{}"
                )
            } ?: emptyList()
            AgentResult(content = content, toolCalls = toolCalls)
        } catch (e: Exception) {
            Log.e(TAG, "parseAgentResult failed", e)
            AgentResult(content = jsonStr)
        }
    }

    override fun getModelInfo(): ModelInfo {
        val ptr = contextPtr.get()
        if (ptr == 0L) return ModelInfo()
        return try {
            val json = nativeGetModelInfo(ptr)
            // Simple parse: {"quant":"Q4_K_M","ftype":15,"gpuCompatible":false}
            val quant = Regex("\"quant\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "unknown"
            val ftype = Regex("\"ftype\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val gpuCompatible = json.contains("\"gpuCompatible\":true")
            val pureQ4 = json.contains("\"pureQ4_0\":true")
            val histogram = Regex("\"tensorHistogram\":(\\{[^}]*\\})").find(json)?.groupValues?.get(1) ?: "{}"
            val devices = Regex("\"backendDevices\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""

            val modelName = Regex("\"modelName\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
            val supportsThinking = json.contains("\"supportsThinking\":true")
            val thinkingStartTag = Regex("\"thinkingStartTag\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
            val thinkingEndTag = Regex("\"thinkingEndTag\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
            val supportsMultimodal = json.contains("\"supportsMultimodal\":true")
            val architecture = Regex("\"architecture\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
            val isRecurrentKV = json.contains("\"isRecurrentKV\":true")
            val nCtxTrain = Regex("\"nCtxTrain\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val nParams = Regex("\"nParams\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            ModelInfo(
                quant = quant,
                ftype = ftype,
                gpuCompatible = gpuCompatible,
                pureQ4_0 = pureQ4,
                tensorHistogram = histogram,
                backendDevices = devices,
                supportsThinking = supportsThinking,
                thinkingStartTag = thinkingStartTag,
                thinkingEndTag = thinkingEndTag,
                modelName = modelName,
                supportsMultimodal = supportsMultimodal,
                architecture = architecture,
                isRecurrentKV = isRecurrentKV,
                nCtxTrain = nCtxTrain,
                nParams = nParams
            )
        } catch (e: Exception) {
            Log.w(TAG, "getModelInfo failed", e)
            ModelInfo()
        }
    }

    /**
     * Probe GGUF metadata from file header without loading the model.
     * Returns architecture, nCtxTrain, nParams, quant, layer/embedding dims
     * for RAM-aware context sizing before the model is loaded.
     */
    fun probeGgufMeta(modelPath: String): GgufProbeResult {
        return try {
            val json = nativeProbeGgufMeta(modelPath)
            val arch = Regex("\"architecture\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
            val isRecurrentKV = json.contains("\"isRecurrentKV\":true")
            val nCtxTrain = Regex("\"nCtxTrain\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val nParams = Regex("\"nParams\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val nEmbd = Regex("\"nEmbd\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val nLayer = Regex("\"nLayer\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val nHeadKv = Regex("\"nHeadKv\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val dHead = Regex("\"dHead\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val quant = Regex("\"quant\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: "unknown"
            GgufProbeResult(arch, isRecurrentKV, nCtxTrain, nParams, nEmbd, nLayer, nHeadKv, dHead, quant)
        } catch (e: Exception) {
            Log.w(TAG, "probeGgufMeta failed for $modelPath", e)
            GgufProbeResult()
        }
    }
}

data class GgufProbeResult(
    val architecture: String = "",
    val isRecurrentKV: Boolean = false,
    val nCtxTrain: Int = 0,
    val nParams: Long = 0L,
    val nEmbd: Int = 0,
    val nLayer: Int = 0,
    val nHeadKv: Int = 0,
    val dHead: Int = 0,
    val quant: String = "unknown"
)
