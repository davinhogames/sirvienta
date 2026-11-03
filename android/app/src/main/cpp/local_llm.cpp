#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <cstdint>
#include <ctime>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

std::mutex modelMutex;
llama_model *model = nullptr;

void throwJavaException(JNIEnv *env, const char *message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

std::string toString(JNIEnv *env, jstring value) {
    const char *characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) {
        return {};
    }
    std::string result(characters);
    env->ReleaseStringUTFChars(value, characters);
    return result;
}

bool tokenizePrompt(const llama_vocab *vocab, const std::string &prompt, std::vector<llama_token> &tokens) {
    const int32_t capacity = static_cast<int32_t>(prompt.size()) + 16;
    tokens.resize(static_cast<size_t>(capacity));
    int32_t tokenCount = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            capacity,
            true,
            true);
    if (tokenCount < 0) {
        tokens.resize(static_cast<size_t>(-tokenCount));
        tokenCount = llama_tokenize(
                vocab,
                prompt.c_str(),
                static_cast<int32_t>(prompt.size()),
                tokens.data(),
                static_cast<int32_t>(tokens.size()),
                true,
                true);
    }
    if (tokenCount <= 0) {
        return false;
    }
    tokens.resize(static_cast<size_t>(tokenCount));
    return true;
}

std::string tokenToPiece(const llama_vocab *vocab, llama_token token) {
    char buffer[128];
    int32_t count = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
    if (count >= 0) {
        return std::string(buffer, static_cast<size_t>(count));
    }

    std::vector<char> dynamicBuffer(static_cast<size_t>(-count));
    count = llama_token_to_piece(
            vocab,
            token,
            dynamicBuffer.data(),
            static_cast<int32_t>(dynamicBuffer.size()),
            0,
            false);
    if (count <= 0) {
        return {};
    }
    return std::string(dynamicBuffer.data(), static_cast<size_t>(count));
}

}

extern "C" JNIEXPORT void JNICALL
Java_com_sirvienta_android_LocalLlm_nativeLoadModel(
        JNIEnv *env,
        jclass,
        jstring modelPathValue) {
    std::lock_guard<std::mutex> lock(modelMutex);
    if (model != nullptr) {
        return;
    }

    const std::string modelPath = toString(env, modelPathValue);
    if (modelPath.empty()) {
        throwJavaException(env, "La ruta del modelo local está vacía.");
        return;
    }

    llama_backend_init();
    llama_model_params parameters = llama_model_default_params();
    model = llama_model_load_from_file(modelPath.c_str(), parameters);
    if (model == nullptr) {
        llama_backend_free();
        throwJavaException(env, "No se pudo abrir el modelo GGUF de Sirvienta.");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sirvienta_android_LocalLlm_nativeGenerate(
        JNIEnv *env,
        jclass,
        jstring promptValue,
        jint maxTokens) {
    std::lock_guard<std::mutex> lock(modelMutex);
    if (model == nullptr) {
        throwJavaException(env, "El modelo local no está cargado.");
        return nullptr;
    }

    const std::string prompt = toString(env, promptValue);
    if (prompt.empty()) {
        throwJavaException(env, "El mensaje para el modelo local está vacío.");
        return nullptr;
    }

    llama_context_params contextParameters = llama_context_default_params();
    contextParameters.n_ctx = std::min<uint32_t>(2048, llama_model_n_ctx_train(model));
    contextParameters.n_batch = 256;
    contextParameters.n_ubatch = 256;
    const int32_t threadCount = std::max(2, std::min(4, static_cast<int32_t>(std::thread::hardware_concurrency())));
    contextParameters.n_threads = threadCount;
    contextParameters.n_threads_batch = threadCount;

    llama_context *context = llama_init_from_model(model, contextParameters);
    if (context == nullptr) {
        throwJavaException(env, "No se pudo crear el contexto del modelo local.");
        return nullptr;
    }

    std::vector<llama_token> promptTokens;
    const llama_vocab *vocab = llama_model_get_vocab(model);
    if (!tokenizePrompt(vocab, prompt, promptTokens)) {
        llama_free(context);
        throwJavaException(env, "No se pudo convertir el mensaje a tokens.");
        return nullptr;
    }

    if (promptTokens.size() + static_cast<size_t>(maxTokens) >= contextParameters.n_ctx) {
        llama_free(context);
        throwJavaException(env, "La conversación local excede el contexto del modelo.");
        return nullptr;
    }

    llama_batch promptBatch = llama_batch_get_one(
            promptTokens.data(),
            static_cast<int32_t>(promptTokens.size()));
    if (llama_decode(context, promptBatch) != 0) {
        llama_free(context);
        throwJavaException(env, "El modelo local no pudo procesar el mensaje.");
        return nullptr;
    }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_dist(static_cast<uint32_t>(std::time(nullptr))));

    std::string reply;
    for (jint index = 0; index < maxTokens; ++index) {
        llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        reply += tokenToPiece(vocab, token);
        llama_batch tokenBatch = llama_batch_get_one(&token, 1);
        if (llama_decode(context, tokenBatch) != 0) {
            llama_sampler_free(sampler);
            llama_free(context);
            throwJavaException(env, "El modelo local se detuvo durante la respuesta.");
            return nullptr;
        }
    }

    llama_sampler_free(sampler);
    llama_free(context);
    return env->NewStringUTF(reply.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_sirvienta_android_LocalLlm_nativeUnloadModel(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(modelMutex);
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
        llama_backend_free();
    }
}
