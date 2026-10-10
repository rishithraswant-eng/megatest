#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <memory>
#include "llama.h"

struct Model { llama_model *model; llama_context *ctx; std::atomic<bool> cancelled{false}; };
static bool cancelled(void *data) { return static_cast<Model *>(data)->cancelled.load(); }
static std::string utf8(JNIEnv *env, jstring text) {
    jclass cls = env->FindClass("java/lang/String");
    jmethodID method = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring encoding = env->NewStringUTF("UTF-8");
    auto bytes = (jbyteArray)env->CallObjectMethod(text, method, encoding);
    std::string value(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, value.size(), (jbyte *)value.data());
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return value;
}
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
static void fill(llama_batch_ext *batch, const llama_token *tokens, int count, int position) {
    llama_batch_ext_clear(batch);
    for (int i = 0; i < count; ++i) {
        const int index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = position + i;
        llama_batch_ext_set_pos(batch, index, &pos);
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_seedhecode_incidentassistant_engine_NativeLlama_load(JNIEnv *env, jobject, jstring path) {
    llama_backend_init();
    auto params = llama_model_default_params(); params.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(utf8(env, path).c_str(), params);
    if (!model) return 0;
    auto item = new Model{model, nullptr};
    auto context = llama_context_default_params();
    context.n_ctx = 4096; context.n_batch = 512; context.n_threads = 4; context.n_threads_batch = 4;
    context.abort_callback = cancelled; context.abort_callback_data = item;
    item->ctx = llama_init_from_model(model, context);
    if (!item->ctx) { llama_model_free(model); delete item; return 0; }
    return reinterpret_cast<jlong>(item);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_seedhecode_incidentassistant_engine_NativeLlama_generate(JNIEnv *env, jobject,
        jlong handle, jstring text, jint maximum) {
    auto item = reinterpret_cast<Model *>(handle);
    if (!item || maximum < 1 || maximum > 1024) { fail(env, "Invalid Qwen request."); return nullptr; }
    item->cancelled.store(false);
    llama_memory_clear(llama_get_memory(item->ctx), true);
    auto vocab = llama_model_get_vocab(item->model);
    std::string prompt = utf8(env, text);
    int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (count <= 0 || count + maximum > 4096) { fail(env, "Qwen context budget exceeded."); return nullptr; }
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) < 0) {
        fail(env, "Qwen tokenization failed."); return nullptr;
    }
    auto batch = std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)>(llama_batch_ext_init(item->ctx), llama_batch_ext_free);
    for (int pos = 0; pos < count; pos += 512) {
        int n = std::min(512, count - pos); fill(batch.get(), tokens.data() + pos, n, pos);
        if (item->cancelled.load() || llama_process(item->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get())) {
            fail(env, "Qwen job cancelled or decode failed."); return nullptr;
        }
    }
    // Constrain syntax at generation time; Kotlin still checks evidence and the contract.
    static const char *grammar = R"gbnf(
root ::= "{" ws "\"fields\"" ws ":" ws fields "," ws "\"dialogue_act\"" ws ":" ws act "," ws "\"tool_calls\"" ws ":" ws tools "," ws "\"incident_groups\"" ws ":" ws groups "}" ws
fields ::= "[" ws (field ("," ws field)*)? "]" ws
field ::= "{" ws "\"field_name\"" ws ":" ws string "," ws "\"value\"" ws ":" ws value "," ws "\"confidence\"" ws ":" ws confidence "," ws "\"evidence\"" ws ":" ws string "," ws "\"status\"" ws ":" ws status "}" ws
confidence ::= ("0" ("." [0-9]+)? | "1" ("." "0"+)?) ws
status ::= ("\"known\"" | "\"unknown\"" | "\"ambiguous\"") ws
act ::= ("null" | "\"answer\"" | "\"correction\"" | "\"negation\"" | "\"uncertainty\"" | "\"confirmation\"") ws
tools ::= "[" ws (tool ("," ws tool)*)? "]" ws
tool ::= "{" ws "\"name\"" ws ":" ws tool-name "," ws "\"arguments\"" ws ":" ws object "}" ws
tool-name ::= ("\"get_form_fields\"" | "\"get_zone_details\"" | "\"get_zone_incident_history\"") ws
groups ::= "[" ws (string ("," ws string)*)? "]" ws
value ::= object | array | string | number | ("true" | "false" | "null") ws
object ::= "{" ws (string ":" ws value ("," ws string ":" ws value)*)? "}" ws
array ::= "[" ws (value ("," ws value)*)? "]" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\"" ws
number ::= "-"? ("0" | [1-9] [0-9]{0,15}) ("." [0-9]+)? ([eE] [-+]? [0-9]{1,16})? ws
ws ::= | " " | "\n" [ \t]{0,20}
)gbnf";
    auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    auto constrained = llama_sampler_init_grammar(vocab, grammar, "root");
    if (!constrained) { fail(env, "Qwen JSON grammar initialization failed."); return nullptr; }
    llama_sampler_chain_add(sampler.get(), constrained);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    std::string output;
    bool ended = false;
    for (int i = 0; i < maximum; ++i) {
        if (item->cancelled.load()) { fail(env, "Qwen job cancelled."); return nullptr; }
        llama_token token = llama_sampler_sample(sampler.get(), item->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
        std::vector<char> piece(256);
        int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
        if (length < 0) { piece.resize(-length); length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true); }
        if (length < 0) { fail(env, "Qwen token decoding failed."); return nullptr; }
        output.append(piece.data(), length);
        fill(batch.get(), &token, 1, count + i);
        if (llama_process(item->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get())) {
            fail(env, "Qwen decode failed."); return nullptr;
        }
    }
    if (!ended) { fail(env, "Qwen response exceeded the token budget; discarded."); return nullptr; }
    auto bytes = env->NewByteArray(output.size());
    env->SetByteArrayRegion(bytes, 0, output.size(), (const jbyte *)output.data());
    return bytes;
}
extern "C" JNIEXPORT void JNICALL
Java_org_seedhecode_incidentassistant_engine_NativeLlama_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Model *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_org_seedhecode_incidentassistant_engine_NativeLlama_release(JNIEnv *, jobject, jlong handle) {
    auto item = reinterpret_cast<Model *>(handle);
    if (!item) return;
    llama_free(item->ctx); llama_model_free(item->model); delete item;
}
