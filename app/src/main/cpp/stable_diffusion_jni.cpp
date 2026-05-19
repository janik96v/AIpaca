#include "stable-diffusion.h"
#include <android/log.h>
#include <jni.h>
#include <string>
#include <atomic>
#include <cstdlib>
#include <cstring>

#define SD_TAG "AIpaca-SD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  SD_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SD_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  SD_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, SD_TAG, __VA_ARGS__)

// Per-instance context
struct SDContext {
    sd_ctx_t*          ctx      = nullptr;
    std::atomic<bool>  stop_flag{false};
};

static std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (js == nullptr) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

static void sd_log_cb(enum sd_log_level_t level, const char* text, void*) {
    if (!text) return;
    // strip trailing newline for logcat
    std::string msg(text);
    while (!msg.empty() && (msg.back() == '\n' || msg.back() == '\r')) msg.pop_back();
    if (msg.empty()) return;
    switch (level) {
        case SD_LOG_DEBUG: LOGD("%s", msg.c_str()); break;
        case SD_LOG_INFO:  LOGI("%s", msg.c_str()); break;
        case SD_LOG_WARN:  LOGW("%s", msg.c_str()); break;
        case SD_LOG_ERROR: LOGE("%s", msg.c_str()); break;
    }
}

static void sd_progress_cb(int step, int steps, float time_s, void*) {
    LOGI("Step %d/%d  %.2f s/step", step, steps, (double)time_s);
}

// ---------------------------------------------------------------------------
// 1. nativeLoadModel
//    Loads a GGUF/safetensors diffusion model. Returns a jlong handle (pointer
//    to heap-allocated SDContext), or 0 on failure.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jlong JNICALL
Java_com_aipaca_app_engine_StableDiffusionEngine_nativeLoadModel(
        JNIEnv*  env,
        jobject  /* thiz */,
        jstring  jModelPath,
        jint     nThreads)
{
    std::string modelPath = jstring_to_std(env, jModelPath);
    LOGI("Loading SD model: %s  threads=%d", modelPath.c_str(), (int)nThreads);

    sd_set_log_callback(sd_log_cb, nullptr);
    sd_set_progress_callback(sd_progress_cb, nullptr);

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path              = modelPath.c_str();
    params.n_threads               = (int)nThreads;
    params.vae_decode_only         = true;
    params.free_params_immediately = false;
    params.wtype                   = SD_TYPE_F16;
    params.rng_type                = STD_DEFAULT_RNG;
    params.sampler_rng_type        = CPU_RNG;
    params.flash_attn              = true;

    sd_ctx_t* ctx = new_sd_ctx(&params);
    if (ctx == nullptr) {
        LOGE("new_sd_ctx failed for %s", modelPath.c_str());
        return 0L;
    }

    auto* sdc = new SDContext();
    sdc->ctx = ctx;
    LOGI("SD model loaded, handle=%p", (void*)sdc);
    return reinterpret_cast<jlong>(sdc);
}

// ---------------------------------------------------------------------------
// 2. nativeGenerateImage
//    Runs text-to-image generation and returns raw pixels as a jbyteArray.
//
//    Layout of the returned array:
//      bytes [0..3]  : image width  as uint32_t LE
//      bytes [4..7]  : image height as uint32_t LE
//      bytes [8..]   : RGBA pixels  (R, G, B, A per pixel, A=255 for RGB models)
//
//    Returns null on failure.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_aipaca_app_engine_StableDiffusionEngine_nativeGenerateImage(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    ctxPtr,
        jstring  jPrompt,
        jstring  jNegPrompt,
        jint     width,
        jint     height,
        jint     steps,
        jfloat   cfgScale,
        jlong    seed)
{
    if (ctxPtr == 0L) {
        LOGE("nativeGenerateImage: null context");
        return nullptr;
    }
    auto* sdc = reinterpret_cast<SDContext*>(ctxPtr);
    sdc->stop_flag.store(false);

    std::string prompt    = jstring_to_std(env, jPrompt);
    std::string negPrompt = jstring_to_std(env, jNegPrompt);

    LOGI("Generating %dx%d  steps=%d  cfg=%.2f  seed=%lld",
         (int)width, (int)height, (int)steps, (double)cfgScale, (long long)seed);
    LOGI("Prompt: %.120s", prompt.c_str());

    sd_img_gen_params_t gen;
    sd_img_gen_params_init(&gen);
    gen.prompt                        = prompt.c_str();
    gen.negative_prompt               = negPrompt.c_str();
    gen.width                         = (int)width;
    gen.height                        = (int)height;
    gen.seed                          = (int64_t)seed;
    gen.batch_count                   = 1;
    gen.sample_params.sample_steps    = (int)steps;
    gen.sample_params.guidance.txt_cfg = (float)cfgScale;

    sd_image_t* results = generate_image(sdc->ctx, &gen);
    if (results == nullptr || results[0].data == nullptr) {
        LOGE("generate_image returned null result");
        if (results) free(results);
        return nullptr;
    }

    const uint32_t w   = results[0].width;
    const uint32_t h   = results[0].height;
    const uint32_t ch  = results[0].channel;
    const int n_pixels = (int)(w * h);
    LOGI("Got image: %ux%u ch=%u", w, h, ch);

    // 8-byte header + RGBA pixels
    const int out_size = 8 + n_pixels * 4;
    jbyteArray out = env->NewByteArray(out_size);
    if (!out) {
        LOGE("Failed to allocate output jbyteArray (%d bytes)", out_size);
        free(results[0].data);
        free(results);
        return nullptr;
    }

    jbyte* buf = env->GetByteArrayElements(out, nullptr);
    if (!buf) {
        LOGE("GetByteArrayElements failed");
        free(results[0].data);
        free(results);
        return nullptr;
    }

    // Write width/height header (little-endian uint32)
    buf[0] = (jbyte)(w        & 0xFF);
    buf[1] = (jbyte)((w >> 8) & 0xFF);
    buf[2] = (jbyte)((w >>16) & 0xFF);
    buf[3] = (jbyte)((w >>24) & 0xFF);
    buf[4] = (jbyte)(h        & 0xFF);
    buf[5] = (jbyte)((h >> 8) & 0xFF);
    buf[6] = (jbyte)((h >>16) & 0xFF);
    buf[7] = (jbyte)((h >>24) & 0xFF);

    const uint8_t* src = results[0].data;
    jbyte* dst = buf + 8;

    if (ch == 3) {
        for (int i = 0; i < n_pixels; ++i) {
            dst[i * 4 + 0] = (jbyte)src[i * 3 + 0]; // R
            dst[i * 4 + 1] = (jbyte)src[i * 3 + 1]; // G
            dst[i * 4 + 2] = (jbyte)src[i * 3 + 2]; // B
            dst[i * 4 + 3] = (jbyte)0xFF;             // A
        }
    } else if (ch == 4) {
        memcpy(dst, src, (size_t)n_pixels * 4);
    } else {
        LOGW("Unexpected channel count %u; copying raw bytes", ch);
        memcpy(dst, src, (size_t)w * h * ch);
    }

    env->ReleaseByteArrayElements(out, buf, 0);

    free(results[0].data);
    free(results);

    LOGI("Image serialised: %d bytes", out_size);
    return out;
}

// ---------------------------------------------------------------------------
// 3. nativeUnloadModel
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_aipaca_app_engine_StableDiffusionEngine_nativeUnloadModel(
        JNIEnv*  /* env */,
        jobject  /* thiz */,
        jlong    ctxPtr)
{
    if (ctxPtr == 0L) return;
    auto* sdc = reinterpret_cast<SDContext*>(ctxPtr);
    sdc->stop_flag.store(true);
    LOGI("Unloading SD model, handle=%p", (void*)sdc);
    if (sdc->ctx) {
        free_sd_ctx(sdc->ctx);
        sdc->ctx = nullptr;
    }
    delete sdc;
    LOGI("SD model unloaded");
}

// ---------------------------------------------------------------------------
// 4. nativeGetSystemInfo
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_aipaca_app_engine_StableDiffusionEngine_nativeGetSystemInfo(
        JNIEnv* env,
        jobject /* thiz */)
{
    const char* info = sd_get_system_info();
    return env->NewStringUTF(info ? info : "unavailable");
}
