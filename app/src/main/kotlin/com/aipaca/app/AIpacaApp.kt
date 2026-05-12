package com.aipaca.app

import android.app.Application
import android.system.Os
import android.util.Log

class AIpacaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must be set before System.loadLibrary so ggml-opencl picks it up at init time.
        // Enables Qualcomm's cl_qcom_large_buffer extension on Adreno GPUs, allowing
        // model weights > 1 GB to stay in GPU memory. No-op on non-Adreno devices.
        Os.setenv("LM_GGML_OPENCL_ADRENO_USE_LARGE_BUFFER", "1", true)
        Log.i("AIpacaApp", "Application starting — EngineState initialised")
        // EngineState is an object (singleton); referencing it here triggers
        // its static initialiser and creates the LlamaCppEngine instance.
        // No model is loaded yet — that happens when the user picks a file.
        EngineState   // touch to init
    }
}
