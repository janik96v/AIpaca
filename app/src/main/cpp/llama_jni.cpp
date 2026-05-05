#include "llama.h"
#include "ggml.h"
#include <android/log.h>
#include <jni.h>
#include <string>
#include <atomic>
#include <cmath>
#include <cstring>
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
    int32_t            gpu_layers    = 0;     // actual GPU layers in use
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

    // Load model on CPU first to read the actual transformer layer count.
    // We need the exact count so n_gpu_layers offloads only transformer layers,
    // keeping the input/output embedding tensors on CPU. If n_gpu_layers exceeds
    // the layer count, llama.cpp also puts embedding tensors on GPU — but Vulkan
    // has no get_rows shader for many quant types (e.g. IQ2_M), causing a crash.
    llama_model_params mparams = llama_model_default_params();
    int32_t gpu_layers_actual = 0;

    if (nGpuLayers != 0) {
        mparams.n_gpu_layers = 0;
        llama_model* probe = llama_model_load_from_file(modelPath.c_str(), mparams);
        if (probe == nullptr) {
            LOGE("nativeLoadModel: failed to probe model from %s", modelPath.c_str());
            return 0L;
        }
        int32_t n_layer = llama_model_n_layer(probe);
        llama_model_free(probe);
        LOGI("Model has %d layers, reloading with GPU offload", (int)n_layer);

        // Clamp requested GPU layers to (n_layer - 1).
        // When n_gpu_layers == n_layer llama.cpp also offloads the output/embedding
        // tensors (token_embd, output) to GPU. On Adreno Vulkan this triggers a
        // shader link failure ("Failed to link shaders") for several model
        // architectures (Qwen2, Gemma, etc.) and causes a SIGSEGV.
        // Keeping at least one transformer layer on CPU ensures those tensors stay
        // on the CPU backend and avoids the crash entirely.
        // nGpuLayers == -1 means "all layers" (caller's default).
        int32_t max_gpu = (n_layer > 0) ? n_layer - 1 : 0;
        gpu_layers_actual = (nGpuLayers < 0 || nGpuLayers > max_gpu) ? max_gpu : (int32_t)nGpuLayers;
        LOGI("Using %d/%d GPU layers (capped to n_layer-1 to keep embeddings on CPU)",
             (int)gpu_layers_actual, (int)n_layer);
    } else {
        LOGI("Loading model in CPU-only mode (gpu_layers=0)");
    }

    mparams = llama_model_default_params();
    mparams.n_gpu_layers = gpu_layers_actual;
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

    // Context params
    llama_context_params cparams   = llama_context_default_params();
    cparams.n_ctx                  = static_cast<uint32_t>(nCtx);
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
    lc->gpu_layers = gpu_layers_actual;
    LOGI("Model ready, handle=%p  gpu_layers=%d", (void*)lc, (int)gpu_layers_actual);
    return reinterpret_cast<jlong>(lc);
}

// ---------------------------------------------------------------------------
// 2. nativeProbeGpu
//    Runs a single 1-token decode with SIGSEGV/SIGBUS handlers installed.
//    Returns JNI_TRUE if the decode succeeds (GPU is working), JNI_FALSE if
//    the Vulkan driver crashes (SIGSEGV/SIGBUS caught via siglongjmp).
//
//    This is needed because the Adreno Vulkan driver can crash inside shader
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
        LOGW("nativeProbeGpu: GPU probe CRASHED (SIGSEGV/SIGBUS caught) — Vulkan not usable");
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
    lc->stop_flag.store(false);

    std::string systemPrompt = jstring_to_std(env, jSystemPrompt);
    std::string userPrompt   = jstring_to_std(env, jUserPrompt);

    LOGD("nativeGenerate: temp=%.2f maxTokens=%d sys_len=%zu user_len=%zu",
         (double)temperature, (int)maxTokens,
         systemPrompt.size(), userPrompt.size());

    // ---- Build chat-formatted prompt using llama_chat_apply_template --------
    // Prepare message array: system (optional) + user
    std::vector<llama_chat_message> messages;
    if (!systemPrompt.empty()) {
        messages.push_back({ "system", systemPrompt.c_str() });
    }
    messages.push_back({ "user", userPrompt.c_str() });

    // First call: measure required buffer size
    const llama_model* model = lc->model;
    const llama_vocab* vocab = llama_model_get_vocab(model);

    std::vector<char> tmpl_buf(4096);
    const char* tmpl = llama_model_chat_template(model, nullptr);
    int tmpl_len = llama_chat_apply_template(
            tmpl,
            messages.data(),
            messages.size(),
            /*add_ass=*/true,          // append start of assistant turn
            tmpl_buf.data(),
            (int32_t)tmpl_buf.size());

    std::string formatted_prompt;
    if (tmpl_len > 0) {
        if (tmpl_len > (int)tmpl_buf.size()) {
            tmpl_buf.resize(tmpl_len + 1);
            llama_chat_apply_template(
                    tmpl,
                    messages.data(), messages.size(),
                    /*add_ass=*/true,
                    tmpl_buf.data(), (int32_t)tmpl_buf.size());
        }
        tmpl_buf[tmpl_len] = '\0';
        formatted_prompt = std::string(tmpl_buf.data(), tmpl_len);
        LOGD("Chat template applied, prompt_len=%d", tmpl_len);
    } else {
        // Fallback: manual [INST] format (LLaMA-2 / Mistral style)
        LOGD("Chat template unavailable, using [INST] fallback");
        if (!systemPrompt.empty()) {
            formatted_prompt = "<<SYS>>\n" + systemPrompt + "\n<</SYS>>\n\n";
        }
        formatted_prompt += "[INST] " + userPrompt + " [/INST]";
    }

    // ---- Tokenise ----------------------------------------------------------
    // Allocate conservatively: 1 token per UTF-8 byte + some headroom
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
        // Buffer too small — retry with exact size
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
        LOGE("nativeGenerate: tokenisation failed, n_tokens=%d", n_tokens);
        return;
    }
    tokens.resize(n_tokens);
    LOGD("Prompt tokenised: %d tokens", n_tokens);

    // ---- Locate the onToken callback method --------------------------------
    jclass    cbClass  = env->GetObjectClass(tokenCallback);
    jmethodID onTokMid = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (onTokMid == nullptr) {
        LOGE("nativeGenerate: could not find onToken method on callback");
        return;
    }

    // ---- Evaluate prompt tokens (prefill) ----------------------------------
    llama_memory_clear(llama_get_memory(lc->ctx), true);

    double t_prefill_start = (double)ggml_time_ms();
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(lc->ctx, batch) != 0) {
        LOGE("nativeGenerate: llama_decode failed for prompt");
        return;
    }
    double t_prefill_ms = (double)ggml_time_ms() - t_prefill_start;
    LOGI("Prefill: %d tokens in %.0f ms (%.1f ms/tok)",
         n_tokens, t_prefill_ms,
         n_tokens > 0 ? t_prefill_ms / n_tokens : 0.0);

    // ---- Build sampler chain -----------------------------------------------
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
            /*penalty_last_n=*/64,
            /*penalty_repeat=*/1.1f,
            /*penalty_freq=*/0.0f,
            /*penalty_present=*/0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ---- Generation loop ---------------------------------------------------
    int    n_generated  = 0;
    double t_start_ms   = (double)ggml_time_ms();

    while (n_generated < (int)maxTokens && !lc->stop_flag.load()) {
        llama_token new_token = llama_sampler_sample(sampler, lc->ctx, -1);

        // Stop on end-of-generation tokens
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGD("EOS reached after %d tokens", n_generated);
            break;
        }

        // Decode token id → UTF-8 piece
        char piece_buf[256] = {};
        int  piece_len = llama_token_to_piece(
                vocab, new_token,
                piece_buf, sizeof(piece_buf) - 1,
                /*lstrip=*/0,
                /*special=*/false);
        if (piece_len < 0) {
            LOGE("llama_token_to_piece failed for token %d", (int)new_token);
            break;
        }
        piece_buf[piece_len] = '\0';

        // Fire the Kotlin callback
        jstring jTok = env->NewStringUTF(piece_buf);
        env->CallVoidMethod(tokenCallback, onTokMid, jTok);
        env->DeleteLocalRef(jTok);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("Exception in onToken callback — stopping generation");
            break;
        }

        // Decode the sampled token to advance context
        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(lc->ctx, next_batch) != 0) {
            LOGE("llama_decode failed at generation step %d", n_generated);
            break;
        }

        n_generated++;

        // Log speed every 5 tokens
        if (n_generated % 5 == 0) {
            double now = (double)ggml_time_ms();
            double elapsed = now - t_start_ms;
            LOGI("Generation: %d tokens @ %.2f tok/s",
                 n_generated, elapsed > 0 ? (double)n_generated / (elapsed / 1000.0) : 0.0);
        }
    }

    // Record performance
    double elapsed_ms = (double)ggml_time_ms() - t_start_ms;
    lc->tokens_per_sec = (elapsed_ms > 0.0)
            ? (float)((double)n_generated / (elapsed_ms / 1000.0))
            : 0.0f;

    LOGI("Generation complete: %d tokens @ %.1f tok/s", n_generated, (double)lc->tokens_per_sec);

    llama_sampler_free(sampler);

    // Clear KV cache so the next call starts fresh
    llama_memory_clear(llama_get_memory(lc->ctx), true);
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
    LOGI("System info: %s", info ? info : "unavailable");
    return env->NewStringUTF(info ? info : "unavailable");
}
