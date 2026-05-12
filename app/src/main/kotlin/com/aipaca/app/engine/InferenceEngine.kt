package com.aipaca.app.engine

import kotlinx.coroutines.flow.Flow

data class GenerateParams(
    val systemPrompt: String    = "You are a helpful assistant.",
    val temperature: Float      = 0.7f,
    val maxTokens: Int          = 512,
    val topP: Float             = 0.95f,
    val repeatPenalty: Float    = 1.1f,
    val thinkingEnabled: Boolean = true
)

data class ChatTurn(
    val role: String,
    val content: String
)

data class GenerationChunk(
    val content: String = "",
    val thinking: String = ""
)

/**
 * Quantization and GPU compatibility info for a loaded model.
 * [gpuCompatible] is true only for Q4_0 and Q6_K — the quant types with
 * optimised Adreno OpenCL kernels. All others silently fall back to CPU.
 */
data class ModelInfo(
    val quant: String       = "unknown",
    val ftype: Int          = -1,
    val gpuCompatible: Boolean = false,
    val pureQ4_0: Boolean = false,
    val tensorHistogram: String = "{}",
    val backendDevices: String = "",
    val supportsThinking: Boolean = false,
    val thinkingStartTag: String = "",
    val thinkingEndTag: String = "",
    val modelName: String = ""
)

data class BenchResult(
    val ppAvg: Float = 0f,
    val tgAvg: Float = 0f,
    val ppRuns: Int = 0,
    val tgRuns: Int = 0,
    val gpuLayers: Int = -1,
    val pureQ4_0: Boolean = false,
    val tensorHistogram: String = "{}"
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
    fun generate(userPrompt: String, params: GenerateParams = GenerateParams()): Flow<GenerationChunk>

    /**
     * Stream generated tokens for structured chat turns. This preserves role
     * boundaries so llama.cpp can apply the model's chat template directly.
     */
    fun generateChat(turns: List<ChatTurn>, params: GenerateParams = GenerateParams()): Flow<GenerationChunk>

    /** Run a native prefill/generation benchmark similar to llama.rn's ctx.bench(). */
    suspend fun benchmark(pp: Int = 128, tg: Int = 128, pl: Int = 1, nr: Int = 3): Result<BenchResult>

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

    /**
     * Returns quantization info for the loaded model.
     * [ModelInfo.gpuCompatible] is false for quant types not optimised for Adreno OpenCL
     * (anything other than Q4_0 or Q6_K), which will silently fall back to CPU.
     */
    fun getModelInfo(): ModelInfo
}
