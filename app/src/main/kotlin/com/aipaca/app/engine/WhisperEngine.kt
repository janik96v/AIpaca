package com.aipaca.app.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "WhisperEngine"

/**
 * Kotlin wrapper around the whisper.cpp JNI bridge.
 *
 * whisper_jni.cpp is compiled into the same "aipaca" shared library as
 * llama_jni.cpp, so no additional System.loadLibrary call is needed.
 *
 * Lifecycle: loadModel → transcribe (repeatable) → unload
 */
class WhisperEngine {

    private external fun nativeLoadWhisperModel(modelPath: String): Long
    private external fun nativeTranscribe(ctxPtr: Long, samples: FloatArray, nSamples: Int): String?
    private external fun nativeGetLanguage(ctxPtr: Long): String
    private external fun nativeFreeWhisperModel(ctxPtr: Long)

    private val contextPtr = AtomicLong(0L)

    val isLoaded: Boolean get() = contextPtr.get() != 0L

    /**
     * Load a whisper model (.bin file). Unloads any previously loaded model first.
     * Suspends on [Dispatchers.IO].
     */
    suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val prev = contextPtr.getAndSet(0L)
            if (prev != 0L) nativeFreeWhisperModel(prev)

            val ptr = nativeLoadWhisperModel(modelPath)
            if (ptr == 0L) {
                return@withContext Result.failure(
                    IllegalStateException("Failed to load whisper model from $modelPath")
                )
            }
            contextPtr.set(ptr)
            Log.i(TAG, "Whisper model loaded from $modelPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "loadModel exception", e)
            Result.failure(e)
        }
    }

    /**
     * Transcribe [samples] (16 kHz, mono, float32 in [-1, 1]).
     * Suspends on [Dispatchers.IO].
     */
    suspend fun transcribe(samples: FloatArray): Result<String> = withContext(Dispatchers.IO) {
        val ptr = contextPtr.get()
        if (ptr == 0L) return@withContext Result.failure(
            IllegalStateException("No whisper model loaded")
        )
        try {
            val text = nativeTranscribe(ptr, samples, samples.size)
            if (text == null) Result.failure(IllegalStateException("Transcription returned null"))
            else Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "transcribe exception", e)
            Result.failure(e)
        }
    }

    /** Returns the language code detected in the last transcription, or "unknown". */
    fun detectedLanguage(): String {
        val ptr = contextPtr.get()
        return if (ptr == 0L) "unknown" else nativeGetLanguage(ptr)
    }

    /** Free native resources. Thread-safe; safe to call when not loaded. */
    fun unload() {
        val ptr = contextPtr.getAndSet(0L)
        if (ptr != 0L) {
            Log.i(TAG, "Unloading whisper model")
            nativeFreeWhisperModel(ptr)
        }
    }
}
