// Minimal JNI bridge for on-device completion via llama.cpp (Bonsai 1-bit GGUF).
// One model, one context, one generation at a time (guarded by a mutex) — exactly what
// the keyboard's polish/compose features need. No servers, no sessions, no streaming.

#include <jni.h>
#include <android/log.h>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "TeclasLlm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
};

std::mutex g_mutex;

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_fran_teclas_llm_LocalLlmEngine_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nCtx, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    static bool backend_ready = false;
    if (!backend_ready) { llama_backend_init(); backend_ready = true; }

    const std::string path = jstr(env, jpath);
    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) { LOGW("model load failed: %s", path.c_str()); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) nCtx;
    cparams.n_batch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) { llama_model_free(model); LOGW("context init failed"); return 0; }

    auto *e = new Engine{model, ctx};
    return (jlong) e;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fran_teclas_llm_LocalLlmEngine_nativeGenerate(
        JNIEnv *env, jclass, jlong handle, jstring jprompt, jint maxTokens, jfloat temperature,
        jstring jgrammar) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto *e = (Engine *) handle;
    if (!e || !e->ctx) return nullptr;

    const std::string user = jstr(env, jprompt);
    const std::string grammar = jgrammar ? jstr(env, jgrammar) : "";
    const llama_vocab *vocab = llama_model_get_vocab(e->model);

    // Wrap the request in the model's chat template (embedded in the GGUF) so an
    // instruction-tuned model actually follows instructions. Raw prompt is the fallback.
    std::string prompt = user;
    const char *tmpl = llama_model_chat_template(e->model, nullptr);
    if (tmpl) {
        llama_chat_message msg{"user", user.c_str()};
        std::vector<char> buf(user.size() * 2 + 1024);
        int n = llama_chat_apply_template(tmpl, &msg, 1, /*add_ass=*/true, buf.data(), (int32_t) buf.size());
        if (n > (int) buf.size()) {
            buf.resize(n + 1);
            n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int32_t) buf.size());
        }
        if (n > 0) prompt.assign(buf.data(), n);
    }

    // Fresh conversation every call: clear the KV cache from the previous one.
    llama_memory_clear(llama_get_memory(e->ctx), true);

    std::vector<llama_token> tokens(prompt.size() + 16);
    int n_tok = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                               tokens.data(), (int32_t) tokens.size(), /*add_special=*/true, /*parse_special=*/true);
    if (n_tok < 0) { LOGW("tokenize failed"); return nullptr; }
    tokens.resize(n_tok);

    const int n_ctx = (int) llama_n_ctx(e->ctx);
    if (n_tok >= n_ctx - maxTokens) {
        // Keep the tail of the prompt: the recent text matters most for rewriting/composing.
        const int keep = n_ctx - maxTokens - 8;
        if (keep <= 0) return nullptr;
        tokens.erase(tokens.begin(), tokens.end() - keep);
        n_tok = keep;
    }

    // Prefill in n_batch chunks.
    for (int i = 0; i < n_tok; i += 512) {
        const int n = std::min(512, n_tok - i);
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            LOGW("prefill decode failed at %d", i);
            return nullptr;
        }
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Grammar first in the chain: masks logits so the output CANNOT violate the GBNF —
    // this is what makes local structured JSON more reliable than any cloud "JSON mode".
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (g) llama_sampler_chain_add(smpl, g);
        else LOGW("grammar parse failed — generating unconstrained");
    }
    if (temperature <= 0.05f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string out;
    llama_token tok;
    char piece[256];
    for (int i = 0; i < maxTokens; i++) {
        tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        const int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        if (llama_decode(e->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    llama_sampler_free(smpl);

    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_fran_teclas_llm_LocalLlmEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto *e = (Engine *) handle;
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

// ── Embeddings: same runtime, an ungated BERT-style GGUF (nomic-embed). A context in
// embeddings mode with MEAN pooling turns text into one L2-normalized vector. No tokenizer
// file — the GGUF carries it — so semantic search auto-downloads with zero user setup.

extern "C" JNIEXPORT jlong JNICALL
Java_com_fran_teclas_llm_EmbedEngine_nativeLoadEmbedder(JNIEnv *env, jclass, jstring jpath, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    static bool backend_ready = false;
    if (!backend_ready) { llama_backend_init(); backend_ready = true; }

    const std::string path = jstr(env, jpath);
    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) { LOGW("embedder load failed: %s", path.c_str()); return 0; }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 512;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;
    cparams.embeddings = true;
    cparams.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) { llama_model_free(model); LOGW("embedder ctx init failed"); return 0; }

    auto *e = new Engine{model, ctx};
    return (jlong) e;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_fran_teclas_llm_EmbedEngine_nativeEmbed(JNIEnv *env, jclass, jlong handle, jstring jtext) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto *e = (Engine *) handle;
    if (!e || !e->ctx) return nullptr;

    const std::string text = jstr(env, jtext);
    const llama_vocab *vocab = llama_model_get_vocab(e->model);

    std::vector<llama_token> tokens(text.size() + 16);
    int n_tok = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                               tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_tok < 0) {
        tokens.resize(-n_tok);
        n_tok = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                               tokens.data(), (int32_t) tokens.size(), true, true);
    }
    if (n_tok <= 0) return nullptr;
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    if (n_tok > n_ctx) n_tok = n_ctx;
    tokens.resize(n_tok);

    llama_memory_clear(llama_get_memory(e->ctx), true);
    if (llama_decode(e->ctx, llama_batch_get_one(tokens.data(), n_tok)) != 0) {
        LOGW("embed decode failed");
        return nullptr;
    }

    const int n_embd = llama_model_n_embd(e->model);
    const float *emb = llama_get_embeddings_seq(e->ctx, 0);
    if (!emb) emb = llama_get_embeddings_ith(e->ctx, n_tok - 1);
    if (!emb) { LOGW("no embeddings produced"); return nullptr; }

    double sum = 0.0;
    for (int i = 0; i < n_embd; i++) sum += (double) emb[i] * emb[i];
    const float inv = sum > 0.0 ? (float) (1.0 / std::sqrt(sum)) : 1.0f;
    std::vector<float> norm(n_embd);
    for (int i = 0; i < n_embd; i++) norm[i] = emb[i] * inv;

    jfloatArray arr = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(arr, 0, n_embd, norm.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fran_teclas_llm_EmbedEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto *e = (Engine *) handle;
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}
