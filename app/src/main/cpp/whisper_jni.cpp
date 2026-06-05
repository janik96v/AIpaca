#include "whisper.h"
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <setjmp.h>
#include <signal.h>
#include <chrono>

#define LOG_TAG "AIpacaWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

static std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (js == nullptr) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

// ---------------------------------------------------------------------------
// GPU probe signal handler machinery (same pattern as llama_jni.cpp)
// ---------------------------------------------------------------------------
static sigjmp_buf        g_whisper_probe_jmp;
static struct sigaction  g_whisper_old_sigsegv;
static struct sigaction  g_whisper_old_sigbus;

static void whisper_probe_signal_handler(int /*sig*/) {
    siglongjmp(g_whisper_probe_jmp, 1);
}

// Track the model path so we can reload in CPU-only mode after a GPU failure.
static std::string g_last_model_path;

// Returns a jlong handle (whisper_context*), 0 on failure.
extern "C"
JNIEXPORT jlong JNICALL
Java_com_aipaca_app_engine_WhisperEngine_nativeLoadWhisperModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring jModelPath,
        jboolean useGpu)
{
    std::string path = jstring_to_std(env, jModelPath);
    LOGI("loading model from %s  use_gpu=%d", path.c_str(), (int)useGpu);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu    = (bool)useGpu;
    cparams.flash_attn = false;  // Disable flash attention — OpenCL FLASH_ATTN_EXT may produce wrong results for whisper tensor shapes

    whisper_context* ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("failed to load model from %s", path.c_str());
        return 0L;
    }

    g_last_model_path = path;
    LOGI("model loaded, ctx=%p  use_gpu=%d  flash_attn=%d",
         (void*)ctx, (int)cparams.use_gpu, (int)cparams.flash_attn);
    return reinterpret_cast<jlong>(ctx);
}

// ---------------------------------------------------------------------------
// GPU probe: runs whisper_full() on a short silent buffer with signal handlers
// installed to catch GPU driver crashes (SIGSEGV/SIGBUS).
//
// Returns:
//   1 = GPU probe succeeded (inference ran without crash)
//   0 = GPU probe failed (SIGSEGV/SIGBUS caught, or whisper_full returned error)
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_aipaca_app_engine_WhisperEngine_nativeProbeWhisperGpu(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   ctxPtr)
{
    if (ctxPtr == 0L) {
        LOGE("nativeProbeWhisperGpu: null context");
        return JNI_FALSE;
    }
    auto* ctx = reinterpret_cast<whisper_context*>(ctxPtr);

    LOGI("nativeProbeWhisperGpu: running encoder+decoder on 1s silent buffer...");

    // 1 second of silence at 16 kHz — enough to exercise the full encoder pipeline
    // (conv1d → GELU → attention layers → decoder)
    const int n_samples = 16000;
    std::vector<float> silence(n_samples, 0.0f);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_special    = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.language         = "en";
    params.n_threads        = 4;
    params.single_segment   = true;

    // Install signal handlers
    struct sigaction sa = {};
    sa.sa_handler = whisper_probe_signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESETHAND;

    sigaction(SIGSEGV, &sa, &g_whisper_old_sigsegv);
    sigaction(SIGBUS,  &sa, &g_whisper_old_sigbus);

    int crashed = sigsetjmp(g_whisper_probe_jmp, /*savesigs=*/1);
    if (crashed == 0) {
        auto t0 = std::chrono::steady_clock::now();

        int rc = whisper_full(ctx, params, silence.data(), n_samples);

        auto t1 = std::chrono::steady_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

        // Restore original handlers
        sigaction(SIGSEGV, &g_whisper_old_sigsegv, nullptr);
        sigaction(SIGBUS,  &g_whisper_old_sigbus,  nullptr);

        if (rc != 0) {
            LOGW("nativeProbeWhisperGpu: whisper_full returned error %d — GPU probe FAILED", rc);
            return JNI_FALSE;
        }

        LOGI("nativeProbeWhisperGpu: GPU probe SUCCESS (%lld ms)", (long long)ms);
        return JNI_TRUE;
    } else {
        // Signal caught — GPU driver crashed
        LOGW("nativeProbeWhisperGpu: GPU probe CRASHED (SIGSEGV/SIGBUS caught)");
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// Reload the model in CPU-only mode. Frees the existing context first.
// Returns a new jlong handle, 0 on failure.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jlong JNICALL
Java_com_aipaca_app_engine_WhisperEngine_nativeReloadWhisperCpuOnly(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong   ctxPtr)
{
    if (ctxPtr != 0L) {
        auto* old = reinterpret_cast<whisper_context*>(ctxPtr);
        LOGI("nativeReloadWhisperCpuOnly: freeing GPU context %p", (void*)old);
        whisper_free(old);
    }

    if (g_last_model_path.empty()) {
        LOGE("nativeReloadWhisperCpuOnly: no model path stored — cannot reload");
        return 0L;
    }

    LOGI("nativeReloadWhisperCpuOnly: reloading %s in CPU-only mode", g_last_model_path.c_str());

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu    = false;
    cparams.flash_attn = false;

    whisper_context* ctx = whisper_init_from_file_with_params(g_last_model_path.c_str(), cparams);
    if (ctx == nullptr) {
        LOGE("nativeReloadWhisperCpuOnly: failed to reload model");
        return 0L;
    }

    LOGI("nativeReloadWhisperCpuOnly: reloaded, ctx=%p (CPU-only)", (void*)ctx);
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

    auto t0 = std::chrono::steady_clock::now();

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_special    = false;
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.language         = "auto";
    params.n_threads        = 4;
    params.single_segment   = false;

    int rc = whisper_full(ctx, params, samples.data(), (int)samples.size());

    auto t1 = std::chrono::steady_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();

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

    LOGI("transcription done (%zu chars, %lld ms): %.60s",
         result.size(), (long long)ms, result.c_str());
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
