#include "whisper.h"
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LOG_TAG "AIpacaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (js == nullptr) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

// Returns a jlong handle (whisper_context*), 0 on failure.
extern "C"
JNIEXPORT jlong JNICALL
Java_com_aipaca_app_engine_WhisperEngine_nativeLoadWhisperModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring jModelPath)
{
    std::string path = jstring_to_std(env, jModelPath);
    LOGI("loading model from %s", path.c_str());

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // OpenCL lacks CONV_1D/IM2COL — whisper encoder produces silent zero output; CPU only

    whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("failed to load model from %s", path.c_str());
        return 0L;
    }
    LOGI("model loaded, ctx=%p", (void*)ctx);
    return reinterpret_cast<jlong>(ctx);
}

// Transcribes float[] PCM (16 kHz, mono, [-1, 1]). Returns null on error.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_aipaca_app_engine_WhisperEngine_nativeTranscribe(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   ctxPtr,
        jfloatArray jSamples,
        jint    nSamples)
{
    if (ctxPtr == 0L || jSamples == nullptr || nSamples <= 0) {
        LOGE("nativeTranscribe: invalid args");
        return nullptr;
    }
    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);

    jfloat* raw = env->GetFloatArrayElements(jSamples, nullptr);
    std::vector<float> samples(raw, raw + nSamples);
    env->ReleaseFloatArrayElements(jSamples, raw, JNI_ABORT);

    LOGI("transcribing %d samples (%.1f s)", nSamples, (float)nSamples / 16000.0f);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_special    = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.language         = "auto";
    params.n_threads        = 4;
    params.single_segment   = false;

    int rc = whisper_full(ctx, params, samples.data(), (int)samples.size());
    if (rc != 0) {
        LOGE("whisper_full failed with code %d", rc);
        return nullptr;
    }

    int n_segments = whisper_full_n_segments(ctx);
    std::string result;
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) result += text;
    }

    // Trim leading/trailing whitespace (whisper often prepends a space)
    size_t start = result.find_first_not_of(" \t\n\r");
    size_t end   = result.find_last_not_of(" \t\n\r");
    if (start == std::string::npos) result = "";
    else result = result.substr(start, end - start + 1);

    LOGI("transcription done (%zu chars): %.60s", result.size(), result.c_str());
    return env->NewStringUTF(result.c_str());
}

// Returns the detected language code (e.g. "en") after a transcription.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_aipaca_app_engine_WhisperEngine_nativeGetLanguage(
        JNIEnv* env,
        jobject /* thiz */,
        jlong   ctxPtr)
{
    if (ctxPtr == 0L) return env->NewStringUTF("unknown");
    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);
    int lang_id = whisper_full_lang_id(ctx);
    const char* lang = whisper_lang_str(lang_id);
    return env->NewStringUTF(lang ? lang : "unknown");
}

// Frees the whisper_context. Safe to call even if ctxPtr is 0.
extern "C"
JNIEXPORT void JNICALL
Java_com_aipaca_app_engine_WhisperEngine_nativeFreeWhisperModel(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   ctxPtr)
{
    if (ctxPtr == 0L) return;
    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);
    LOGI("freeing model, ctx=%p", (void*)ctx);
    whisper_free(ctx);
}
