// On-phone LLM: a thin JNI layer over llama.cpp's C API (llama.h), for com.dirk.kalshiodds.llm.LlamaNative.
//
// One loaded GGUF model per handle. generate() formats a system + user message with the model's own chat
// template, decodes the prompt in n_batch chunks, samples until an end-of-generation token, maxTokens, a full
// context or the sink asking to stop, and streams the raw UTF-8 bytes of each piece to the sink (Kotlin decodes
// them: a piece can end in the middle of a character, which NewStringUTF cannot take).

#include <jni.h>
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define LOG_TAG "LocalLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_batch_ext * batch = nullptr;
    int n_batch = 512;
    std::mutex lock;
};

std::once_flag g_backend_once;
std::string g_last_error;
std::mutex g_error_lock;

void set_error(const std::string & e) {
    std::lock_guard<std::mutex> g(g_error_lock);
    g_last_error = e;
    LOGE("%s\n", e.c_str());
}

void log_callback(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
}

std::string to_std(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const jsize n = env->GetStringUTFLength(s);
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c, n);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java strings arrive as modified UTF-8 (a supplementary character as two 3-byte surrogates); text for the model
// must be standard UTF-8, so convert through UTF-16.
std::string to_utf8(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const jsize n = env->GetStringLength(s);
    const jchar * u = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(n * 3);
    for (jsize i = 0; i < n; ++i) {
        uint32_t cp = u[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(s, u);
    return out;
}

Session * from_handle(jlong h) { return reinterpret_cast<Session *>(h); }

bool decode_tokens(Session * s, const llama_token * tokens, int n, int pos0, bool last_logits) {
    for (int start = 0; start < n; start += s->n_batch) {
        const int len = std::min(s->n_batch, n - start);
        llama_batch_ext_clear(s->batch);
        for (int i = 0; i < len; ++i) {
            const int32_t idx = llama_batch_ext_add_token(s->batch, 0, tokens[start + i]);
            if (idx < 0) {
                set_error("batch rejected a token");
                return false;
            }
            const llama_pos pos = pos0 + start + i;
            llama_batch_ext_set_pos(s->batch, idx, &pos);
        }
        if (last_logits && start + len == n) llama_batch_ext_set_output_logits(s->batch, len - 1, true);
        const int32_t ret = llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, s->batch);
        if (ret != 0) {
            set_error("decode failed (" + std::to_string(ret) + ")");
            return false;
        }
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_dirk_kalshiodds_llm_LlamaNative_init(JNIEnv * env, jclass, jstring jlib_dir) {
    const std::string dir = to_std(env, jlib_dir);
    std::call_once(g_backend_once, [&] {
        llama_log_set(log_callback, nullptr);
        if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str()); else ggml_backend_load_all();
        llama_backend_init();
    });
}

JNIEXPORT jlong JNICALL
Java_com_dirk_kalshiodds_llm_LlamaNative_load(JNIEnv * env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    const std::string path = to_std(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) {
        set_error("could not load the model file");
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    const int train_ctx = llama_model_n_ctx_train(model);
    cp.n_ctx = static_cast<uint32_t>(train_ctx > 0 ? std::min<int>(n_ctx, train_ctx) : n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    const int cores = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    const int threads = n_threads > 0 ? n_threads : std::max(1, std::min(4, cores - 2));
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        set_error("could not create a context (not enough memory?)");
        return 0;
    }
    auto * s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->n_batch = static_cast<int>(cp.n_batch);
    s->batch = llama_batch_ext_init(ctx);
    if (s->batch == nullptr) {
        llama_free(ctx);
        llama_model_free(model);
        delete s;
        set_error("could not allocate a batch");
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL
Java_com_dirk_kalshiodds_llm_LlamaNative_describe(JNIEnv * env, jclass, jlong h) {
    Session * s = from_handle(h);
    if (s == nullptr) return env->NewStringUTF("");
    char buf[256] = {0};
    llama_model_desc(s->model, buf, sizeof(buf));
    std::string out = std::string(buf) + " | " + std::to_string(llama_model_n_params(s->model)) + " params | ctx " +
                      std::to_string(llama_n_ctx(s->ctx));
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_dirk_kalshiodds_llm_LlamaNative_lastError(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> g(g_error_lock);
    return env->NewStringUTF(g_last_error.c_str());
}

// Returns the number of tokens generated, or -1 on an error (lastError() says which).
JNIEXPORT jint JNICALL
Java_com_dirk_kalshiodds_llm_LlamaNative_generate(JNIEnv * env, jclass, jlong h, jstring jsystem, jstring juser,
                                                   jint max_tokens, jfloat temperature, jint seed, jobject sink) {
    Session * s = from_handle(h);
    if (s == nullptr) {
        set_error("no model loaded");
        return -1;
    }
    std::lock_guard<std::mutex> guard(s->lock);

    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_bytes = env->GetMethodID(sink_class, "onBytes", "([B)Z");
    if (on_bytes == nullptr) {
        env->ExceptionClear();
        set_error("sink has no onBytes([B)Z");
        return -1;
    }

    const std::string system = to_utf8(env, jsystem);
    const std::string user = to_utf8(env, juser);
    std::vector<llama_chat_message> msgs;
    if (!system.empty()) msgs.push_back({"system", system.c_str()});
    msgs.push_back({"user", user.c_str()});

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    std::vector<char> buf(2 * (system.size() + user.size()) + 1024);
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    if (len < 0 && tmpl != nullptr) {
        // A template llama.cpp cannot run without a Jinja engine: fall back to ChatML.
        tmpl = "chatml";
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len > static_cast<int>(buf.size())) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) {
        set_error("could not apply the chat template");
        return -1;
    }
    const std::string prompt(buf.data(), len);

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        set_error("empty prompt");
        return -1;
    }
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n_prompt, true, true) < 0) {
        set_error("could not tokenize the prompt");
        return -1;
    }
    const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    if (n_prompt + 8 > n_ctx) {
        set_error("prompt is " + std::to_string(n_prompt) + " tokens, the context holds " + std::to_string(n_ctx));
        return -1;
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    int generated = 0;
    bool ok = decode_tokens(s, tokens.data(), n_prompt, 0, true);
    int pos = n_prompt;
    while (ok && generated < max_tokens && pos + 1 < n_ctx) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        std::vector<char> piece(256);
        int n = llama_token_to_piece(vocab, tok, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (n < 0) {
            piece.resize(-n);
            n = llama_token_to_piece(vocab, tok, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (n < 0) {
            set_error("could not read a token");
            ok = false;
            break;
        }
        ++generated;
        if (n > 0) {
            jbyteArray arr = env->NewByteArray(n);
            env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(piece.data()));
            const jboolean more = env->CallBooleanMethod(sink, on_bytes, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            if (!more) break;
        }
        ok = decode_tokens(s, &tok, 1, pos, true);
        ++pos;
    }
    llama_sampler_free(smpl);
    env->DeleteLocalRef(sink_class);
    return ok ? generated : -1;
}

JNIEXPORT void JNICALL
Java_com_dirk_kalshiodds_llm_LlamaNative_free(JNIEnv *, jclass, jlong h) {
    Session * s = from_handle(h);
    if (s == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(s->lock);
        if (s->batch) llama_batch_ext_free(s->batch);
        if (s->ctx) llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
    }
    delete s;
}

}  // extern "C"
