package com.lamaphone.app

import android.app.Application
import android.util.Log

class LamaPhoneApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("LamaPhoneApp", "Application starting — EngineState initialised")
        // EngineState is an object (singleton); referencing it here triggers
        // its static initialiser and creates the LlamaCppEngine instance.
        // No model is loaded yet — that happens when the user picks a file.
        EngineState   // touch to init
    }
}
