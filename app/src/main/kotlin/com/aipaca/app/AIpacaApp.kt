package com.aipaca.app

import android.app.Application
import android.system.Os
import android.util.Log
import com.aipaca.app.data.WhisperModelPrefs
import kotlinx.coroutines.launch

class AIpacaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must be set before System.loadLibrary so ggml-opencl picks it up at init time.
        // Enables Qualcomm's cl_qcom_large_buffer extension on Adreno GPUs, allowing
        // model weights > 1 GB to stay in GPU memory. No-op on non-Adreno devices.
        Os.setenv("LM_GGML_OPENCL_ADRENO_USE_LARGE_BUFFER", "1", true)
        Log.i("AIpacaApp", "Application starting — EngineState initialised")
        // Inject context before touching EngineState so whisper prefs work.
        EngineState.init(this)
        EngineState   // touch to init

        // Restore whisper model from last session (non-blocking)
        val savedWhisperPath = WhisperModelPrefs.getPath(this)
        if (savedWhisperPath != null) {
            EngineState.scope.launch {
                EngineState.loadWhisperModel(savedWhisperPath)
            }
        }
    }
}
