#include <jni.h>
#include <android/log.h>
#if defined(__aarch64__)
#include <sys/auxv.h>
#endif
#include <atomic>
#include <thread>
#include <algorithm>
#include <string>
#include <cstring>
#include <vector>
#include "llama.h"

#define TAG "AstraLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static std::vector<llama_token> g_cache;   // tokens currently in KV cache
static std::atomic<bool> g_stop{false};
static int g_nctx = 4096;

#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1 << 20)
#endif

// Longest prefix of s that does not end in the middle of a UTF-8 character.
static size_t utf8_valid_prefix(const std::string& s) {
    size_t i = 0, n = s.size(), ok = 0;
    while (i < n) {
        unsigned char c = (unsigned char)s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (len == 0) { i++; ok = i; continue; }
        if (i + len > n) break;
        i += len; ok = i;
    }
    return ok;
}

static void free_all() {
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_cache.clear();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_astra_agent_llm_LlamaBridge_nativeLoad(JNIEnv* env, jobject, jstring jpath, jint nctx, jint threads) {
    free_all();
#if defined(__aarch64__)
    // The native code is built with ARM dot-product instructions (2 to 3 times faster quantized math). A CPU without them
    // (only very old phones) must not run it: report "could not load" instead of crashing.
    if ((getauxval(AT_HWCAP) & HWCAP_ASIMDDP) == 0) { LOGI("this CPU has no dot-product instructions"); return JNI_FALSE; }
#endif
    llama_backend_init();
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!g_model) { LOGI("model load failed"); return JNI_FALSE; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nctx; cp.n_batch = 512; cp.n_ubatch = 512;
    // answering uses the chosen thread count; reading the prompt is a short burst, so it may use more cores
    int hw = (int)std::thread::hardware_concurrency();
    cp.n_threads = threads; cp.n_threads_batch = std::max(threads, std::min(threads + 1, std::max(2, hw - 3)));
    g_nctx = nctx;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { free_all(); return JNI_FALSE; }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_astra_agent_llm_LlamaBridge_nativeGenerate(JNIEnv* env, jobject, jbyteArray jprompt,
        jint maxTokens, jfloat temp, jfloat topP, jobject cb) {
    if (!g_ctx || !g_model) return;
    g_stop = false;
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    jsize plen = env->GetArrayLength(jprompt);
    std::string prompt(plen, '\0');
    env->GetByteArrayRegion(jprompt, 0, plen, (jbyte*)prompt.data());

    int n = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    if (n <= 0 || n >= g_nctx - 8) { LOGI("prompt too long: %d", n); return; }
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), toks.data(), n, true, true) < 0) return;

    // reuse KV cache for the shared prefix
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t common = 0;
    while (common < g_cache.size() && common < toks.size() && g_cache[common] == toks[common]) common++;
    if (common >= toks.size()) common = toks.size() - 1;
    if (!llama_memory_seq_rm(mem, 0, (llama_pos)common, -1)) { llama_memory_clear(mem, true); common = 0; }
    g_cache.assign(toks.begin(), toks.begin() + common);

    for (size_t i = common; i < toks.size(); i += 512) {
        size_t cnt = std::min<size_t>(512, toks.size() - i);
        if (llama_decode(g_ctx, llama_batch_get_one(toks.data() + i, (int32_t)cnt)) != 0) { g_cache.clear(); return; }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + cnt);
        if (g_stop) return;
    }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = env->FindClass("com/astra/agent/llm/TokenCallback");
    jmethodID mid = env->GetMethodID(cls, "onToken", "([B)Z");
    std::string pending;

    for (int i = 0; i < maxTokens && !g_stop; i++) {
        llama_token t = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char buf[256];
        int len = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);
        if (len > 0) pending.append(buf, len);
        size_t ok = utf8_valid_prefix(pending);
        if (ok > 0) {
            std::string out = pending.substr(0, ok);
            pending.erase(0, ok);
            jbyteArray arr = env->NewByteArray((jsize)out.size());
            env->SetByteArrayRegion(arr, 0, (jsize)out.size(), (const jbyte*)out.data());
            jboolean cont = env->CallBooleanMethod(cb, mid, arr);
            env->DeleteLocalRef(arr);
            if (!cont) break;
        }
        if ((int)g_cache.size() >= g_nctx - 1) break;
        if (llama_decode(g_ctx, llama_batch_get_one(&t, 1)) != 0) break;
        g_cache.push_back(t);
    }
    llama_sampler_free(smpl);
}

// Loads the fixed start of the chat prompt into the KV cache ahead of time, so the first answer does not have to read it.
extern "C" JNIEXPORT void JNICALL
Java_com_astra_agent_llm_LlamaBridge_nativeWarmup(JNIEnv* env, jobject, jbyteArray jprompt) {
    if (!g_ctx || !g_model) return;
    g_stop = false;
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    jsize plen = env->GetArrayLength(jprompt);
    std::string prompt(plen, '\0');
    env->GetByteArrayRegion(jprompt, 0, plen, (jbyte*)prompt.data());
    int n = -llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    if (n <= 0 || n >= g_nctx / 2) return;
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, prompt.c_str(), (int)prompt.size(), toks.data(), n, true, true) < 0) return;
    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_clear(mem, true);
    g_cache.clear();
    for (size_t i = 0; i < toks.size(); i += 256) {
        size_t cnt = std::min<size_t>(256, toks.size() - i);
        if (llama_decode(g_ctx, llama_batch_get_one(toks.data() + i, (int32_t)cnt)) != 0) { g_cache.clear(); return; }
        g_cache.insert(g_cache.end(), toks.begin() + i, toks.begin() + i + cnt);
        if (g_stop) return;
    }
}

// The model's own chat template (Jinja text) as UTF-8 bytes, or an empty array. Kotlin uses it to pick the right chat layout.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_astra_agent_llm_LlamaBridge_nativeTemplate(JNIEnv* env, jobject) {
    const char* t = g_model ? llama_model_chat_template(g_model, nullptr) : nullptr;
    size_t n = t ? strlen(t) : 0;
    jbyteArray arr = env->NewByteArray((jsize)n);
    if (n > 0) env->SetByteArrayRegion(arr, 0, (jsize)n, (const jbyte*)t);
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_astra_agent_llm_LlamaBridge_nativeStop(JNIEnv*, jobject) { g_stop = true; }

extern "C" JNIEXPORT void JNICALL
Java_com_astra_agent_llm_LlamaBridge_nativeFree(JNIEnv*, jobject) { free_all(); }
