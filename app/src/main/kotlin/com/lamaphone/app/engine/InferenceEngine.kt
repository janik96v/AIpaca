package com.lamaphone.app.engine

import kotlinx.coroutines.flow.Flow

data class GenerateParams(
    val systemPrompt: String    = "You are a helpful assistant.",
    val temperature: Float      = 0.7f,
    val maxTokens: Int          = 512,
    val topP: Float             = 0.95f,
    val repeatPenalty: Float    = 1.1f
)

data class InferenceStats(
    val tokensPerSecond: Float  = 0f,
    val totalTokens: Int        = 0,
    val modelName: String       = ""
)

interface InferenceEngine {
    /**
     * Load a GGUF model from [modelPath].
     * Must be called from a coroutine — performs blocking I/O on Dispatchers.IO.
     */
    suspend fun loadModel(
        modelPath: String,
        nThreads: Int    = 4,
        contextSize: Int = 2048,
        nGpuLayers: Int  = -1   // -1 = all layers; 0 = CPU only
    ): Result<Unit>

    /**
     * Stream generated tokens for the given [userPrompt].
     * The [params] carry the optional system prompt and sampling configuration.
     * Returns a cold [Flow] — collection triggers generation.
     */
    fun generate(userPrompt: String, params: GenerateParams = GenerateParams()): Flow<String>

    /** Request cancellation of any in-progress generation. Thread-safe. */
    fun stopGeneration()

    /** Free all native resources. Must be called when the engine is no longer needed. */
    fun unload()

    fun isLoaded(): Boolean
    fun getModelPath(): String?
    fun getStats(): InferenceStats

    /**
     * Returns the number of GPU layers actually used by the loaded model.
     * -1 = no model loaded, 0 = CPU-only (GPU probe failed or disabled), >0 = GPU layers.
     */
    fun getActiveGpuLayers(): Int
}
