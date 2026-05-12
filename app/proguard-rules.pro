# Add project specific ProGuard rules here.

# Keep JNI bridge class and all its native methods
-keep class com.aipaca.app.engine.LlamaCppEngine { *; }
-keepclassmembers class com.aipaca.app.engine.LlamaCppEngine {
    native <methods>;
}

# Keep TokenCallback — called from C++ via JNI
-keep interface com.aipaca.app.engine.TokenCallback { *; }
-keepclassmembers interface com.aipaca.app.engine.TokenCallback {
    public void onToken(java.lang.String);
}

# Ktor — keep server classes
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# kotlinx.serialization
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
