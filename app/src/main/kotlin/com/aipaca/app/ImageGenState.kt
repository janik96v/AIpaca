package com.aipaca.app

import android.util.Log
import com.aipaca.app.engine.ImageGenRequest
import com.aipaca.app.engine.ImageGenResult
import com.aipaca.app.engine.StableDiffusionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ImageGenState"

object ImageGenState {

    val engine: StableDiffusionEngine = StableDiffusionEngine()

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _modelPath      = MutableStateFlow<String?>(null)
    val modelPath: StateFlow<String?> = _modelPath.asStateFlow()

    private val _isLoaded       = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isLoadingModel = MutableStateFlow(false)
    val isLoadingModel: StateFlow<Boolean> = _isLoadingModel.asStateFlow()

    private val _isGenerating   = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorMessage   = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun loadModel(path: String, nThreads: Int = 4): Result<Unit> {
        _errorMessage.value    = null
        _isLoadingModel.value  = true
        _isLoaded.value        = false
        _modelPath.value       = null

        Log.i(TAG, "loadModel: $path")
        return try {
            val result = engine.loadModel(path, nThreads)
            result.fold(
                onSuccess = {
                    _isLoaded.value  = true
                    _modelPath.value = path
                    Log.i(TAG, "SD model ready")
                },
                onFailure = { e ->
                    _errorMessage.value = e.message ?: "Unknown load error"
                    Log.e(TAG, "loadModel failed", e)
                }
            )
            result
        } finally {
            _isLoadingModel.value = false
        }
    }

    fun unload() {
        Log.i(TAG, "Unloading SD model")
        _isLoaded.value       = false
        _modelPath.value      = null
        _isLoadingModel.value = false
        _isGenerating.value   = false
        engine.unload()
    }

    suspend fun generateImage(request: ImageGenRequest): Result<ImageGenResult> {
        _errorMessage.value = null
        _isGenerating.value = true
        return try {
            val result = engine.generateImage(request)
            result.onFailure { e ->
                _errorMessage.value = e.message
                Log.e(TAG, "generateImage failed", e)
            }
            result
        } finally {
            _isGenerating.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
