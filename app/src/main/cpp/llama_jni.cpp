#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "gguf.h"
#include <android/log.h>
#include <jni.h>
#include <string>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <map>
#include <sstream>
#include <vector>
#include <setjmp.h>
#include <signal.h>

#define LOG_TAG "LamaPhone"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Global backend init flag — llama_backend_init() must be called exactly once
// per process. llama_backend_free() is intentionally NOT called per-model.
// ---------------------------------------------------------------------------
static std::atomic<bool> g_backend_inited { false };

static void ensure_backend_init() {
    bool expected = false;
    if (g_backend_inited.compare_exchange_strong(expected, true)) {
        llama_backend_init();
        LOGI("llama backend initialised");
    }
}

// ---------------------------------------------------------------------------
// Per-instance context bundling model + context + stop signal + perf stats
// ---------------------------------------------------------------------------
struct LlamaContext {
    llama_model*       model         = nullptr;
    llama_context*     ctx           = nullptr;
    std::atomic<bool>  stop_flag     { false };
    float              tokens_per_sec = 0.0f;
    int32_t            gpu_layers    = 0;     // effective GPU/offload layers in use
    int32_t            requested_gpu_layers = 0;
    int32_t            n_layer       = 0;
    std::string        model_path;
    std::string        tensor_histogram_json = "{}";
    bool               pure_q4_0     = false;
    bool               speed_compatible = false;
};

// ---------------------------------------------------------------------------
// Helper: convert a jstring to std::string, releasing the UTF chars
// ---------------------------------------------------------------------------
static std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (js == nullptr) return {};
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string s(chars);
    env->ReleaseStringUTFChars(js, chars);
    return s;
}

static const char* ftype_to_name(int ftype) {
    switch (ftype) {
        case LLAMA_FTYPE_ALL_F32:        return "F32";
        case LLAMA_FTYPE_MOSTLY_F16:     return "F16";
        case LLAMA_FTYPE_MOSTLY_Q4_0:    return "Q4_0";
        case LLAMA_FTYPE_MOSTLY_Q4_1:    return "Q4_1";
        case LLAMA_FTYPE_MOSTLY_Q8_0:    return "Q8_0";
        case LLAMA_FTYPE_MOSTLY_Q5_0:    return "Q5_0";
        case LLAMA_FTYPE_MOSTLY_Q5_1:    return "Q5_1";
        case LLAMA_FTYPE_MOSTLY_Q2_K:    return "Q2_K";
        case LLAMA_FTYPE_MOSTLY_Q3_K_S:  return "Q3_K_S";
        case LLAMA_FTYPE_MOSTLY_Q3_K_M:  return "Q3_K_M";
        case LLAMA_FTYPE_MOSTLY_Q3_K_L:  return "Q3_K_L";
        case LLAMA_FTYPE_MOSTLY_Q4_K_S:  return "Q4_K_S";
        case LLAMA_FTYPE_MOSTLY_Q4_K_M:  return "Q4_K_M";
        case LLAMA_FTYPE_MOSTLY_Q5_K_S:  return "Q5_K_S";
        case LLAMA_FTYPE_MOSTLY_Q5_K_M:  return "Q5_K_M";
        case LLAMA_FTYPE_MOSTLY_Q6_K:    return "Q6_K";
        case LLAMA_FTYPE_MOSTLY_IQ2_XXS: return "IQ2_XXS";
        case LLAMA_FTYPE_MOSTLY_IQ2_XS:  return "IQ2_XS";
        case LLAMA_FTYPE_MOSTLY_Q2_K_S:  return "Q2_K_S";
        case LLAMA_FTYPE_MOSTLY_IQ3_XS:  return "IQ3_XS";
        case LLAMA_FTYPE_MOSTLY_IQ3_XXS: return "IQ3_XXS";
        case LLAMA_FTYPE_MOSTLY_IQ1_S:   return "IQ1_S";
        case LLAMA_FTYPE_MOSTLY_IQ4_NL:  return "IQ4_NL";
        case LLAMA_FTYPE_MOSTLY_IQ3_S:   return "IQ3_S";
        case LLAMA_FTYPE_MOSTLY_IQ3_M:   return "IQ3_M";
        case LLAMA_FTYPE_MOSTLY_IQ2_S:   return "IQ2_S";
        case LLAMA_FTYPE_MOSTLY_IQ2_M:   return "IQ2_M";
        case LLAMA_FTYPE_MOSTLY_IQ4_XS:  return "IQ4_XS";
        case LLAMA_FTYPE_MOSTLY_IQ1_M:   return "IQ1_M";
        case LLAMA_FTYPE_MOSTLY_BF16:    return "BF16";
        case LLAMA_FTYPE_MOSTLY_TQ1_0:   return "TQ1_0";
        case LLAMA_FTYPE_MOSTLY_TQ2_0:   return "TQ2_0";
        case LLAMA_FTYPE_MOSTLY_MXFP4_MOE: return "MXFP4_MOE";
        case LLAMA_FTYPE_MOSTLY_NVFP4:   return "NVFP4";
        case LLAMA_FTYPE_MOSTLY_Q1_0:    return "Q1_0";
        default: return "unknown";
    }
}

static std::string json_escape(const std::string& s) {
    std::ostringstream out;
    for (char c : s) {
        switch (c) {
            case '"':  out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b";  break;
            case '\f': out << "\\f";  break;
            case '\n': out << "\\n";  break;
            case '\r': out << "\\r";  break;
            case '\t': out << "\\t";  break;
            default:
                unsigned char uc = static_cast<unsigned char>(c);
                if (uc < 0x20) {
                    out << "\\u00";
                    const char* hex = "0123456789abcdef";
                    out << hex[(uc >> 4) & 0x0f] << hex[uc & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

static std::string get_backend_devices_summary() {
    std::ostringstream out;
    size_t reg_count = ggml_backend_reg_count();
    size_t dev_count = ggml_backend_dev_count();
    out << "registries=" << reg_count << " devices=" << dev_count;
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        out << " [" << i << "] "
            << (ggml_backend_dev_name(dev) ? ggml_backend_dev_name(dev) : "unknown")
            << " / "
            << (ggml_backend_dev_description(dev) ? ggml_backend_dev_description(dev) : "unknown")
            << " type=" << (int)ggml_backend_dev_type(dev);
    }
    return out.str();
}

static void log_backend_devices() {
    LOGI("ggml backend devices: %s", get_backend_devices_summary().c_str());
}

struct TensorHistogram {
    std::string json = "{}";
    bool pure_q4_0 = false;
};

static TensorHistogram build_tensor_histogram(const std::string& model_path) {
    TensorHistogram result;
    gguf_init_params params = {
        /*.no_alloc =*/ true,
        /*.ctx      =*/ nullptr,
    };
    gguf_context* ctx = gguf_init_from_file(model_path.c_str(), params);
    if (ctx == nullptr) {
        LOGW("Could not read GGUF tensor metadata for %s", model_path.c_str());
        return result;
    }

    std::map<std::string, int64_t> counts;
    const int64_t n_tensors = gguf_get_n_tensors(ctx);
    for (int64_t i = 0; i < n_tensors; ++i) {
        enum ggml_type type = gguf_get_tensor_type(ctx, i);
        const char* name = ggml_type_name(type);
        counts[name ? name : "unknown"]++;
    }
    gguf_free(ctx);

    bool pure_q4_0 = !counts.empty();
    for (const auto& entry : counts) {
        // llama.cpp's "mostly Q4_0" can still keep small 1D tensors in float.
        // Treat those as speed-compatible; anything else is a red flag for Adreno OpenCL.
        if (entry.first != "q4_0" && entry.first != "f32" && entry.first != "f16") {
            pure_q4_0 = false;
            break;
        }
    }

    std::ostringstream json;
    json << "{";
    bool first = true;
    for (const auto& entry : counts) {
        if (!first) json << ",";
        first = false;
        json << "\"" << json_escape(entry.first) << "\":" << entry.second;
    }
    json << "}";

    result.json = json.str();
    result.pure_q4_0 = pure_q4_0;
    return result;
}

static int get_model_ftype(const llama_model* model) {
    char ftype_buf[32] = {};
    int ftype_val = -1;
    if (llama_model_meta_val_str(model, "general.file_type", ftype_buf, sizeof(ftype_buf)) >= 0) {
        ftype_val = atoi(ftype_buf);
    }
    return ftype_val;
}

static std::vector<std::string> jobject_array_to_vector(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> values;
    if (array == nullptr) return values;
    const jsize n = env->GetArrayLength(array);
    values.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        values.push_back(jstring_to_std(env, item));
        env->DeleteLocalRef(item);
    }
    return values;
}

// ---------------------------------------------------------------------------
// GPU probe signal handler machinery
// Must be process-global because signal handlers are process-global.
// ---------------------------------------------------------------------------
static sigjmp_buf        g_probe_jmp;
static struct sigaction  g_old_sigsegv;
static struct sigaction  g_old_sigbus;
static std::atomic<bool> g_probe_active { false };

static void probe_signal_handler(int /*sig*/) {
    // Jump back to the setjmp site with value 1
    siglongjmp(g_probe_jmp, 1);
}

// ---------------------------------------------------------------------------
// 1. nativeLoadModel
//    Returns a jlong handle (pointer to heap-allocated LlamaContext), 0 on failure.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jlong JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeLoadModel(
        JNIEnv*  env,
        jobject  /* thiz */,
        jstring  jModelPath,
        jint     nThreads,
        jint     nCtx,
        jint     nGpuLayers)
{
    std::string modelPath = jstring_to_std(env, jModelPath);
    LOGI("Loading model: %s  threads=%d  ctx=%d  gpu_layers=%d",
         modelPath.c_str(), (int)nThreads, (int)nCtx, (int)nGpuLayers);

    ensure_backend_init();
    log_backend_devices();

    TensorHistogram histogram = build_tensor_histogram(modelPath);
    LOGI("GGUF tensor histogram: %s  pure_q4_0=%s",
         histogram.json.c_str(), histogram.pure_q4_0 ? "true" : "false");

    llama_model_params mparams = llama_model_default_params();
    if (nGpuLayers == 0) {
        mparams.n_gpu_layers = 0;
        LOGI("Loading model in CPU-only mode (gpu_layers=0)");
    } else if (nGpuLayers < 0) {
        mparams.n_gpu_layers = -1;
        LOGI("Loading model with full GPU offload request (n_gpu_layers=-1/all)");
    } else {
        mparams.n_gpu_layers = (int32_t)nGpuLayers;
        LOGI("Loading model with explicit GPU offload request (n_gpu_layers=%d)", (int)nGpuLayers);
    }
    mparams.use_mmap = false;
    mparams.use_extra_bufts = true;

    llama_model* model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        LOGE("nativeLoadModel: failed to load model from %s", modelPath.c_str());
        return 0L;
    }

    // Log model metadata
    const llama_vocab* vocab = llama_model_get_vocab(model);
    int64_t n_params     = llama_model_n_params(model);
    int32_t n_ctx_train  = llama_model_n_ctx_train(model);
    char desc_buf[128]   = {};
    llama_model_desc(model, desc_buf, sizeof(desc_buf));
    LOGI("Model loaded: type=%s  n_params=%lld  n_ctx_train=%d  vocab_size=%d",
         desc_buf, (long long)n_params, (int)n_ctx_train,
         llama_vocab_n_tokens(vocab));

    const int32_t n_layer = llama_model_n_layer(model);
    const int32_t effective_gpu_layers =
        nGpuLayers == 0 ? 0 :
        nGpuLayers < 0 ? n_layer + 1 :
        std::min<int32_t>((int32_t)nGpuLayers, n_layer + 1);
    const int ftype_val = get_model_ftype(model);
    const bool speed_compatible =
        histogram.pure_q4_0 || ftype_val == LLAMA_FTYPE_MOSTLY_Q6_K;

    LOGI("Offload request resolved: requested=%d effective=%d max_all_layers=%d",
         (int)nGpuLayers, (int)effective_gpu_layers, (int)(n_layer + 1));

    if (effective_gpu_layers > 0) {
        LOGI("Offloaded %d layers to GPU. Tensor histogram: %s",
             (int)effective_gpu_layers, histogram.json.c_str());
    }

    // Context params
    llama_context_params cparams   = llama_context_default_params();
    cparams.n_ctx                  = static_cast<uint32_t>(nCtx);
    cparams.n_batch                = std::min<uint32_t>(512, static_cast<uint32_t>(nCtx));
    cparams.n_ubatch               = std::min<uint32_t>(512, cparams.n_batch);
    cparams.n_threads              = static_cast<uint32_t>(nThreads);
    cparams.n_threads_batch        = static_cast<uint32_t>(nThreads);

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("nativeLoadModel: failed to create context");
        llama_model_free(model);
        return 0L;
    }

    auto* lc       = new LlamaContext();
    lc->model      = model;
    lc->ctx        = ctx;
    lc->gpu_layers = effective_gpu_layers;
    lc->requested_gpu_layers = (int32_t)nGpuLayers;
    lc->n_layer = n_layer;
    lc->model_path = modelPath;
    lc->tensor_histogram_json = histogram.json;
    lc->pure_q4_0 = histogram.pure_q4_0;
    lc->speed_compatible = speed_compatible;
    LOGI("Model ready, handle=%p  gpu_layers=%d  n_batch=%u  n_ubatch=%u  mmap=false repack=true",
         (void*)lc, (int)effective_gpu_layers, cparams.n_batch, cparams.n_ubatch);
    return reinterpret_cast<jlong>(lc);
}

// ---------------------------------------------------------------------------
// 2. nativeProbeGpu
//    Runs a single 1-token decode with SIGSEGV/SIGBUS handlers installed.
//    Returns JNI_TRUE if the decode succeeds (GPU is working), JNI_FALSE if
//    the GPU backend crashes (SIGSEGV/SIGBUS caught via siglongjmp).
//
//    This is needed because the Adreno GPU driver can crash inside shader
//    pipeline compilation — a driver-level SIGSEGV that C++ try/catch cannot
//    intercept. The signal handler lets us detect and survive the crash.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeProbeGpu(
        JNIEnv*  /* env */,
        jobject  /* thiz */,
        jlong    ctxPtr)
{
    if (ctxPtr == 0L) {
        LOGE("nativeProbeGpu: null context pointer");
        return JNI_FALSE;
    }

    auto* lc = reinterpret_cast<LlamaContext*>(ctxPtr);

    if (lc->gpu_layers == 0) {
        // Already CPU-only; no need to probe
        LOGI("nativeProbeGpu: skipped (CPU-only context)");
        return JNI_TRUE;
    }

    LOGI("nativeProbeGpu: probing GPU with 1-token decode...");

    // Install temporary signal handlers for SIGSEGV and SIGBUS.
    // SA_RESETHAND: auto-restore default handler after first signal fires,
    // so production crashes are never silently swallowed.
    struct sigaction sa = {};
    sa.sa_handler = probe_signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESETHAND;

    g_probe_active.store(true);
    sigaction(SIGSEGV, &sa, &g_old_sigsegv);
    sigaction(SIGBUS,  &sa, &g_old_sigbus);

    int crashed = sigsetjmp(g_probe_jmp, /*savesigs=*/1);
    if (crashed == 0) {
        // --- Happy path: run minimal decode ---
        llama_memory_clear(llama_get_memory(lc->ctx), true);
        llama_token probe_tok = 1;   // token id 1 is always valid (BOS or similar)
        llama_batch b = llama_batch_get_one(&probe_tok, 1);
        int rc = llama_decode(lc->ctx, b);
        llama_memory_clear(llama_get_memory(lc->ctx), true);

        // Restore original handlers (only reached if no signal fired)
        sigaction(SIGSEGV, &g_old_sigsegv, nullptr);
        sigaction(SIGBUS,  &g_old_sigbus,  nullptr);
        g_probe_active.store(false);

        if (rc != 0) {
            LOGW("nativeProbeGpu: llama_decode returned error %d — treating as GPU failure", rc);
            return JNI_FALSE;
        }
        LOGI("nativeProbeGpu: GPU probe SUCCESS (%d layers)", (int)lc->gpu_layers);
        return JNI_TRUE;
    } else {
        // --- Signal caught: GPU driver crashed ---
        // Handlers already reset by SA_RESETHAND.
        g_probe_active.store(false);
        LOGW("nativeProbeGpu: GPU probe CRASHED (SIGSEGV/SIGBUS caught) — GPU backend not usable");
        return JNI_FALSE;
    }
}

// ---------------------------------------------------------------------------
// 3. nativeGetActiveGpuLayers
//    Returns the number of GPU layers actually in use for this context.
//    0 = CPU-only, >0 = GPU offloaded layers.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jint JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeGetActiveGpuLayers(
        JNIEnv*  /* env */,
        jobject  /* thiz */,
        jlong    ctxPtr)
{
    if (ctxPtr == 0L) return 0;
    auto* lc = reinterpret_cast<LlamaContext*>(ctxPtr);
    return (jint)lc->gpu_layers;
}

static void run_generate(
        JNIEnv* env,
        LlamaContext* lc,
        const std::vector<std::pair<std::string, std::string>>& turns,
        float temperature,
        float top_p,
        float repeat_penalty,
        int max_tokens,
        jobject tokenCallback) {
    lc->stop_flag.store(false);

    LOGD("run_generate: temp=%.2f top_p=%.2f repeat=%.2f maxTokens=%d turns=%zu",
         (double)temperature, (double)top_p, (double)repeat_penalty, max_tokens, turns.size());

    const llama_model* model = lc->model;
    const llama_vocab* vocab = llama_model_get_vocab(model);

    std::vector<llama_chat_message> messages;
    messages.reserve(turns.size());
    for (const auto& turn : turns) {
        if (!turn.second.empty()) {
            messages.push_back({ turn.first.c_str(), turn.second.c_str() });
        }
    }
    if (messages.empty()) {
        LOGE("run_generate: no messages to generate from");
        return;
    }

    std::vector<char> tmpl_buf(4096);
    const char* tmpl = llama_model_chat_template(model, nullptr);
    int tmpl_len = llama_chat_apply_template(
            tmpl,
            messages.data(),
            messages.size(),
            /*add_ass=*/true,
            tmpl_buf.data(),
            (int32_t)tmpl_buf.size());

    std::string formatted_prompt;
    if (tmpl_len > 0) {
        if (tmpl_len > (int)tmpl_buf.size()) {
            tmpl_buf.resize(tmpl_len + 1);
            tmpl_len = llama_chat_apply_template(
                    tmpl,
                    messages.data(),
                    messages.size(),
                    /*add_ass=*/true,
                    tmpl_buf.data(),
                    (int32_t)tmpl_buf.size());
        }
        if (tmpl_len <= 0) {
            LOGE("run_generate: chat template retry failed, tmpl_len=%d", tmpl_len);
            return;
        }
        tmpl_buf[tmpl_len] = '\0';
        formatted_prompt = std::string(tmpl_buf.data(), tmpl_len);
        LOGD("Chat template applied to %zu turns, prompt_len=%d", messages.size(), tmpl_len);
    } else {
        LOGD("Chat template unavailable, using role-labelled fallback");
        for (const auto& turn : turns) {
            if (turn.first == "system") {
                formatted_prompt += "System: " + turn.second + "\n";
            } else if (turn.first == "assistant") {
                formatted_prompt += "Assistant: " + turn.second + "\n";
            } else {
                formatted_prompt += "User: " + turn.second + "\n";
            }
        }
        formatted_prompt += "Assistant:";
    }

    std::vector<llama_token> tokens(formatted_prompt.size() + 64);
    int n_tokens = llama_tokenize(
            vocab,
            formatted_prompt.c_str(),
            (int32_t)formatted_prompt.size(),
            tokens.data(),
            (int32_t)tokens.size(),
            /*add_special=*/true,
            /*parse_special=*/false);

    if (n_tokens < 0) {
        tokens.resize(-n_tokens + 4);
        n_tokens = llama_tokenize(
                vocab,
                formatted_prompt.c_str(),
                (int32_t)formatted_prompt.size(),
                tokens.data(),
                (int32_t)tokens.size(),
                /*add_special=*/true,
                /*parse_special=*/false);
    }
    if (n_tokens <= 0) {
        LOGE("run_generate: tokenisation failed, n_tokens=%d", n_tokens);
        return;
    }
    tokens.resize(n_tokens);
    LOGI("Prompt tokenised: %d tokens (n_batch=%u n_ubatch=%u)",
         n_tokens, llama_n_batch(lc->ctx), llama_n_ubatch(lc->ctx));

    if ((uint32_t)n_tokens > llama_n_batch(lc->ctx)) {
        LOGW("Prompt tokens (%d) exceed n_batch (%u); llama.cpp may reject this request. Reduce history or raise n_batch.",
             n_tokens, llama_n_batch(lc->ctx));
    }

    jclass cbClass = env->GetObjectClass(tokenCallback);
    jmethodID onTokMid = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (onTokMid == nullptr) {
        LOGE("run_generate: could not find onToken method on callback");
        return;
    }

    llama_memory_clear(llama_get_memory(lc->ctx), true);

    double t_prefill_start = (double)ggml_time_ms();
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(lc->ctx, batch) != 0) {
        LOGE("run_generate: llama_decode failed for prompt");
        return;
    }
    double t_prefill_ms = (double)ggml_time_ms() - t_prefill_start;
    LOGI("Prefill: %d tokens in %.0f ms (%.1f ms/tok, %.2f tok/s)",
         n_tokens, t_prefill_ms,
         n_tokens > 0 ? t_prefill_ms / n_tokens : 0.0,
         t_prefill_ms > 0.0 ? (double)n_tokens / (t_prefill_ms / 1000.0) : 0.0);

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
            /*penalty_last_n=*/64,
            /*penalty_repeat=*/repeat_penalty,
            /*penalty_freq=*/0.0f,
            /*penalty_present=*/0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int n_generated = 0;
    double t_start_ms = (double)ggml_time_ms();

    while (n_generated < max_tokens && !lc->stop_flag.load()) {
        llama_token new_token = llama_sampler_sample(sampler, lc->ctx, -1);

        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGD("EOS reached after %d tokens", n_generated);
            break;
        }

        char piece_buf[256] = {};
        int piece_len = llama_token_to_piece(
                vocab, new_token,
                piece_buf, sizeof(piece_buf) - 1,
                /*lstrip=*/0,
                /*special=*/false);
        if (piece_len < 0) {
            LOGE("llama_token_to_piece failed for token %d", (int)new_token);
            break;
        }
        piece_buf[piece_len] = '\0';

        jstring jTok = env->NewStringUTF(piece_buf);
        env->CallVoidMethod(tokenCallback, onTokMid, jTok);
        env->DeleteLocalRef(jTok);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("Exception in onToken callback; stopping generation");
            break;
        }

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(lc->ctx, next_batch) != 0) {
            LOGE("llama_decode failed at generation step %d", n_generated);
            break;
        }

        n_generated++;

        if (n_generated % 5 == 0) {
            double now = (double)ggml_time_ms();
            double elapsed = now - t_start_ms;
            LOGI("Generation: %d tokens @ %.2f tok/s",
                 n_generated, elapsed > 0 ? (double)n_generated / (elapsed / 1000.0) : 0.0);
        }
    }

    double elapsed_ms = (double)ggml_time_ms() - t_start_ms;
    lc->tokens_per_sec = (elapsed_ms > 0.0)
            ? (float)((double)n_generated / (elapsed_ms / 1000.0))
            : 0.0f;

    LOGI("Generation complete: %d tokens @ %.1f tok/s", n_generated, (double)lc->tokens_per_sec);

    llama_sampler_free(sampler);
    llama_memory_clear(llama_get_memory(lc->ctx), true);
}

// ---------------------------------------------------------------------------
// 4. nativeGenerate
//    Builds a chat-formatted prompt from systemPrompt + userPrompt,
//    tokenises it, runs the decode/sample loop, fires tokenCallback per token.
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeGenerate(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    ctxPtr,
        jstring  jSystemPrompt,
        jstring  jUserPrompt,
        jfloat   temperature,
        jint     maxTokens,
        jobject  tokenCallback)
{
    if (ctxPtr == 0L) {
        LOGE("nativeGenerate: null context pointer");
        return;
    }

    auto* lc = reinterpret_cast<LlamaContext*>(ctxPtr);
    std::string systemPrompt = jstring_to_std(env, jSystemPrompt);
    std::string userPrompt   = jstring_to_std(env, jUserPrompt);
    std::vector<std::pair<std::string, std::string>> messages;
    if (!systemPrompt.empty()) {
        messages.push_back({ "system", systemPrompt });
    }
    messages.push_back({ "user", userPrompt });
    run_generate(env, lc, messages, temperature, 0.95f, 1.1f, (int)maxTokens, tokenCallback);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeGenerateChat(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    ctxPtr,
        jobjectArray jRoles,
        jobjectArray jContents,
        jfloat   temperature,
        jfloat   topP,
        jfloat   repeatPenalty,
        jint     maxTokens,
        jobject  tokenCallback)
{
    if (ctxPtr == 0L) {
        LOGE("nativeGenerateChat: null context pointer");
        return;
    }

    auto* lc = reinterpret_cast<LlamaContext*>(ctxPtr);
    std::vector<std::string> roles = jobject_array_to_vector(env, jRoles);
    std::vector<std::string> contents = jobject_array_to_vector(env, jContents);
    if (roles.size() != contents.size()) {
        LOGE("nativeGenerateChat: roles/content size mismatch (%zu vs %zu)", roles.size(), contents.size());
        return;
    }

    std::vector<std::pair<std::string, std::string>> turns;
    turns.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) {
        std::string role = roles[i] == "assistant" || roles[i] == "system" ? roles[i] : "user";
        turns.push_back({ role, contents[i] });
    }

    run_generate(env, lc, turns, temperature, topP, repeatPenalty, (int)maxTokens, tokenCallback);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeBench(
        JNIEnv* env,
        jobject /* thiz */,
        jlong ctxPtr,
        jint pp,
        jint tg,
        jint pl,
        jint nr)
{
    if (ctxPtr == 0L) {
        return env->NewStringUTF("{\"error\":\"no model loaded\"}");
    }

    auto* lc = reinterpret_cast<LlamaContext*>(ctxPtr);
    const llama_vocab* vocab = llama_model_get_vocab(lc->model);
    const int prompt_tokens = std::max(1, (int)pp);
    const int gen_tokens = std::max(1, (int)tg);
    const int repeats = std::max(1, (int)nr);

    double pp_sum = 0.0;
    double tg_sum = 0.0;
    int pp_runs = 0;
    int tg_runs = 0;

    LOGI("nativeBench: pp=%d tg=%d pl=%d nr=%d backend_layers=%d histogram=%s",
         prompt_tokens, gen_tokens, (int)pl, repeats, (int)lc->gpu_layers, lc->tensor_histogram_json.c_str());

    for (int r = 0; r < repeats; ++r) {
        std::vector<llama_token> tokens(prompt_tokens, 1);
        llama_memory_clear(llama_get_memory(lc->ctx), true);

        double pp_start = (double)ggml_time_ms();
        llama_batch batch = llama_batch_get_one(tokens.data(), prompt_tokens);
        int pp_rc = llama_decode(lc->ctx, batch);
        double pp_ms = (double)ggml_time_ms() - pp_start;
        if (pp_rc == 0 && pp_ms > 0.0) {
            double speed = (double)prompt_tokens / (pp_ms / 1000.0);
            pp_sum += speed;
            pp_runs++;
            LOGI("nativeBench: run %d pp %.2f tok/s", r + 1, speed);
        } else {
            LOGW("nativeBench: run %d pp failed rc=%d", r + 1, pp_rc);
        }

        llama_sampler* sampler = llama_sampler_init_greedy();
        double tg_start = (double)ggml_time_ms();
        int generated = 0;
        for (; generated < gen_tokens; ++generated) {
            llama_token token = llama_sampler_sample(sampler, lc->ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                token = 1;
            }
            llama_batch next = llama_batch_get_one(&token, 1);
            if (llama_decode(lc->ctx, next) != 0) {
                LOGW("nativeBench: tg decode failed at token %d", generated);
                break;
            }
        }
        double tg_ms = (double)ggml_time_ms() - tg_start;
        llama_sampler_free(sampler);
        if (generated > 0 && tg_ms > 0.0) {
            double speed = (double)generated / (tg_ms / 1000.0);
            tg_sum += speed;
            tg_runs++;
            LOGI("nativeBench: run %d tg %.2f tok/s", r + 1, speed);
        }
    }

    llama_memory_clear(llama_get_memory(lc->ctx), true);

    const double pp_avg = pp_runs > 0 ? pp_sum / pp_runs : 0.0;
    const double tg_avg = tg_runs > 0 ? tg_sum / tg_runs : 0.0;
    std::ostringstream json;
    json << "{"
         << "\"ppAvg\":" << pp_avg << ","
         << "\"tgAvg\":" << tg_avg << ","
         << "\"ppRuns\":" << pp_runs << ","
         << "\"tgRuns\":" << tg_runs << ","
         << "\"gpuLayers\":" << lc->gpu_layers << ","
         << "\"requestedGpuLayers\":" << lc->requested_gpu_layers << ","
         << "\"nLayer\":" << lc->n_layer << ","
         << "\"pureQ4_0\":" << (lc->pure_q4_0 ? "true" : "false") << ","
         << "\"speedCompatible\":" << (lc->speed_compatible ? "true" : "false") << ","
         << "\"tensorHistogram\":" << lc->tensor_histogram_json
         << "}";
    LOGI("nativeBench result: %s", json.str().c_str());
    return env->NewStringUTF(json.str().c_str());
}

// ---------------------------------------------------------------------------
// 5. nativeStopGeneration
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeStopGeneration(
        JNIEnv*  /* env */,
        jobject  /* thiz */,
        jlong    ctxPtr)
{
    if (ctxPtr == 0L) return;
    auto* lc = reinterpret_cast<LlamaContext*>(ctxPtr);
    lc->stop_flag.store(true);
    LOGI("Stop signal set on handle=%p", (void*)lc);
}

// ---------------------------------------------------------------------------
// 6. nativeUnloadModel
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT void JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeUnloadModel(
        JNIEnv*  /* env */,
        jobject  /* thiz */,
        jlong    ctxPtr)
{
    if (ctxPtr == 0L) return;
    auto* lc = reinterpret_cast<LlamaContext*>(ctxPtr);

    // Signal any in-progress generation to stop
    lc->stop_flag.store(true);

    LOGI("Unloading model, handle=%p", (void*)lc);
    if (lc->ctx)   { llama_free(lc->ctx);        lc->ctx   = nullptr; }
    if (lc->model) { llama_model_free(lc->model); lc->model = nullptr; }
    delete lc;
    LOGI("Model unloaded (backend remains alive for next load)");
}

// ---------------------------------------------------------------------------
// 7. nativeGetSystemInfo
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeGetSystemInfo(
        JNIEnv*  env,
        jobject  /* thiz */)
{
    const char* info = llama_print_system_info();
    std::string devices = get_backend_devices_summary();
    LOGI("System info: %s | %s", info ? info : "unavailable", devices.c_str());
    std::string combined = std::string(info ? info : "unavailable") + " | " + devices;
    return env->NewStringUTF(combined.c_str());
}

// ---------------------------------------------------------------------------
// 8. nativeGetModelInfo
//    Returns a JSON string with quant type and GPU compatibility info.
//    Used by the Kotlin layer to surface a warning when the model quant is
//    not optimised for the Adreno OpenCL backend.
//    JSON format: {"quant":"Q4_0","ftype":2,"gpuCompatible":true,...}
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jstring JNICALL
Java_com_lamaphone_app_engine_LlamaCppEngine_nativeGetModelInfo(
        JNIEnv*  env,
        jobject  /* thiz */,
        jlong    ctxPtr)
{
    if (ctxPtr == 0L) {
        return env->NewStringUTF("{\"quant\":\"unknown\",\"ftype\":-1,\"gpuCompatible\":false}");
    }
    auto* lc = reinterpret_cast<LlamaContext*>(ctxPtr);

    int ftype_val = get_model_ftype(lc->model);
    bool gpu_compatible = lc->speed_compatible;
    const char* quant_name = ftype_to_name(ftype_val);
    std::ostringstream json;
    json << "{"
         << "\"quant\":\"" << json_escape(quant_name) << "\","
         << "\"ftype\":" << ftype_val << ","
         << "\"gpuCompatible\":" << (gpu_compatible ? "true" : "false") << ","
         << "\"pureQ4_0\":" << (lc->pure_q4_0 ? "true" : "false") << ","
         << "\"tensorHistogram\":" << lc->tensor_histogram_json << ","
         << "\"gpuLayers\":" << lc->gpu_layers << ","
         << "\"requestedGpuLayers\":" << lc->requested_gpu_layers << ","
         << "\"nLayer\":" << lc->n_layer << ","
         << "\"backendDevices\":\"" << json_escape(get_backend_devices_summary()) << "\""
         << "}";
    return env->NewStringUTF(json.str().c_str());
}
