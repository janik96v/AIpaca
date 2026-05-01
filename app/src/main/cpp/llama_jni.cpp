#include "llama.h"
#include "ggml.h"
#include <android/log.h>
#include <jni.h>
#include <string>
#include <atomic>
#include <cmath>
#include <cstring>
#include <vector>

#define LOG_TAG "LamaPhone"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

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
        jint     nCtx)
{
    std::string modelPath = jstring_to_std(env, jModelPath);
    LOGI("Loading model: %s  threads=%d  ctx=%d", modelPath.c_str(), (int)nThreads, (int)nCtx);

    ensure_backend_init();

    // Model params — CPU-only for MVP
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model* model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        LOGE("nativeLoadModel: failed to load model from %s", modelPath.c_str());
        return 0L;
    }

    // Log model metadata
    const llama_vocab* vocab = llama_model_get_vocab(model);
    int64_t n_params     = llama_model_n_params(model);
    int32_t n_ctx_train  = llama_model_n_ctx_train(model);
    const char* desc     = llama_model_desc(model, nullptr, 0) > 0 ? "" : "";
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

    llama_context* ctx = llama_new_context_with_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("nativeLoadModel: failed to create context");
        llama_model_free(model);
        return 0L;
    }

    auto* lc       = new LlamaContext();
    lc->model      = model;
    lc->ctx        = ctx;
    LOGI("Model ready, handle=%p", (void*)lc);
    return reinterpret_cast<jlong>(lc);
}

// ---------------------------------------------------------------------------
// 2. nativeGenerate
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
    int tmpl_len = llama_chat_apply_template(
            model,
            nullptr,                   // use model's built-in template
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
                    model, nullptr,
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
    llama_kv_cache_clear(lc->ctx);

    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(lc->ctx, batch) != 0) {
        LOGE("nativeGenerate: llama_decode failed for prompt");
        return;
    }

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
        if (llama_token_is_eog(vocab, new_token)) {
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
    }

    // Record performance
    double elapsed_ms = (double)ggml_time_ms() - t_start_ms;
    lc->tokens_per_sec = (elapsed_ms > 0.0)
            ? (float)((double)n_generated / (elapsed_ms / 1000.0))
            : 0.0f;

    LOGI("Generation complete: %d tokens @ %.1f tok/s", n_generated, (double)lc->tokens_per_sec);

    llama_sampler_free(sampler);

    // Clear KV cache so the next call starts fresh
    llama_kv_cache_clear(lc->ctx);
}

// ---------------------------------------------------------------------------
// 3. nativeStopGeneration
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
// 4. nativeUnloadModel
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
// 5. nativeGetSystemInfo
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
