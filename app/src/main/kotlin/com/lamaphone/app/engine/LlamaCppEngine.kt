package com.lamaphone.app.engine

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "LlamaCppEngine"

/**
 * Java interface invoked from the C++ JNI bridge for each generated token.
 *
 * The method name and descriptor must exactly match what llama_jni.cpp looks up:
 *   env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V")
 */
interface TokenCallback {
    fun onToken(token: String)
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
            System.loadLibrary("lamaphone")
        }
    }

    // ---- JNI declarations --------------------------------------------------
    // All names must match Java_com_lamaphone_app_engine_LlamaCppEngine_nativeXxx

    private external fun nativeLoadModel(
        modelPath: String,
        nThreads: Int,
        nCtx: Int,
        nGpuLayers: Int
    ): Long

    /**
     * Probes whether the GPU (Vulkan) backend works for this context by running a
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

    private external fun nativeStopGeneration(ctxPtr: Long)

    private external fun nativeUnloadModel(ctxPtr: Long)

    private external fun nativeGetSystemInfo(): String

    // ---- Mutable state -----------------------------------------------------

    /** Holds the native LlamaContext* cast to Long; 0 means no model loaded. */
    private val contextPtr      = AtomicLong(0L)
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
     * After loading with GPU layers, runs [nativeProbeGpu] to verify the Vulkan
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
                Log.i(TAG, "Probing GPU (Vulkan) backend...")
                nativeProbeGpu(ptr)
            } else {
                true  // CPU-only requested explicitly, skip probe
            }

            val activePtr: Long
            if (!gpuProbeOk) {
                // GPU probe failed — free GPU context and reload CPU-only
                Log.w(TAG, "GPU probe FAILED — Vulkan driver crash detected. Reloading in CPU-only mode.")
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
    ): Flow<String> = callbackFlow {
        val ptr = contextPtr.get()
        if (ptr == 0L) {
            close(IllegalStateException("No model loaded"))
            return@callbackFlow
        }

        val startMs    = System.currentTimeMillis()
        var tokenCount = 0

        val callback = object : TokenCallback {
            override fun onToken(token: String) {
                tokenCount++
                trySend(token)   // non-blocking; callbackFlow provides back-pressure
            }
        }

        try {
            withContext(Dispatchers.IO) {
                nativeGenerate(
                    ctxPtr       = ptr,
                    systemPrompt = params.systemPrompt,
                    userPrompt   = userPrompt,
                    temperature  = params.temperature,
                    maxTokens    = params.maxTokens,
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

    /**
     * Expose llama.cpp system info string (CPU features, BLAS flags, etc.)
     * for diagnostic use (e.g. Settings screen).
     */
    fun getSystemInfo(): String =
        if (_isLoaded.get()) nativeGetSystemInfo() else "model not loaded"
}
