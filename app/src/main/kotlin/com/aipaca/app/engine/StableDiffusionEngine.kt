package com.aipaca.app.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "StableDiffusionEngine"

class StableDiffusionEngine : ImageGenerationEngine {

    companion object {
        init {
            System.loadLibrary("aipaca_sd")
        }
    }

    // JNI declarations — names must match Java_com_aipaca_app_engine_StableDiffusionEngine_nativeXxx
    private external fun nativeLoadModel(modelPath: String, nThreads: Int): Long
    private external fun nativeGenerateImage(
        ctxPtr: Long,
        prompt: String,
        negPrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfgScale: Float,
        seed: Long
    ): ByteArray?
    private external fun nativeUnloadModel(ctxPtr: Long)
    private external fun nativeGetSystemInfo(): String

    private val contextPtr  = AtomicLong(0L)
    private val _modelPath  = AtomicReference<String?>(null)

    override suspend fun loadModel(modelPath: String, nThreads: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (contextPtr.get() != 0L) {
                    Log.i(TAG, "Unloading previous SD model before reload")
                    unload()
                }
                Log.i(TAG, "Loading SD model: $modelPath  threads=$nThreads")
                val ptr = nativeLoadModel(modelPath, nThreads)
                if (ptr == 0L) {
                    return@withContext Result.failure(
                        IllegalStateException("Failed to load model — check logcat for details")
                    )
                }
                contextPtr.set(ptr)
                _modelPath.set(modelPath)
                Log.i(TAG, "SD model loaded, ptr=$ptr")
                Log.i(TAG, "SD system info: ${nativeGetSystemInfo()}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "loadModel exception", e)
                Result.failure(e)
            }
        }

    override suspend fun generateImage(request: ImageGenRequest): Result<ImageGenResult> =
        withContext(Dispatchers.IO) {
            val ptr = contextPtr.get()
            if (ptr == 0L) {
                return@withContext Result.failure(IllegalStateException("No SD model loaded"))
            }
            try {
                Log.i(TAG, "Generating ${request.width}x${request.height} steps=${request.steps} cfg=${request.cfgScale}")
                val raw = nativeGenerateImage(
                    ctxPtr    = ptr,
                    prompt    = request.prompt,
                    negPrompt = request.negativePrompt,
                    width     = request.width,
                    height    = request.height,
                    steps     = request.steps,
                    cfgScale  = request.cfgScale,
                    seed      = request.seed
                )
                if (raw == null || raw.size < 8) {
                    return@withContext Result.failure(IllegalStateException("Generation returned no data"))
                }
                val w = readInt32LE(raw, 0)
                val h = readInt32LE(raw, 4)
                val rgba = raw.copyOfRange(8, raw.size)
                Log.i(TAG, "Image ready: ${w}x${h}")
                Result.success(ImageGenResult(rgba, w, h))
            } catch (e: Exception) {
                Log.e(TAG, "generateImage exception", e)
                Result.failure(e)
            }
        }

    override fun isLoaded(): Boolean = contextPtr.get() != 0L

    override fun getModelPath(): String? = _modelPath.get()

    override fun unload() {
        val ptr = contextPtr.getAndSet(0L)
        if (ptr != 0L) {
            Log.i(TAG, "Unloading SD model, ptr=$ptr")
            _modelPath.set(null)
            nativeUnloadModel(ptr)
        }
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
