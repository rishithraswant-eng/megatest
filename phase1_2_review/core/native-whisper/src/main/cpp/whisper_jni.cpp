#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

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
static std::string quote(const std::string &text) {
    std::string out = "\"";
    const char *hex = "0123456789abcdef";
    for (unsigned char c : text) {
        if (c == '"' || c == '\\') { out += '\\'; out += c; }
        else if (c < 32) { out += "\\u00"; out += hex[c >> 4]; out += hex[c & 15]; }
        else out += c;
    }
    return out + "\"";
}
static jbyteArray result(JNIEnv *env, const std::string &text) {
    auto bytes = env->NewByteArray(text.size());
    env->SetByteArrayRegion(bytes, 0, text.size(), (const jbyte *)text.data());
    return bytes;
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_seedhecode_incidentassistant_engine_NativeWhisper_load(JNIEnv *env, jobject, jstring path) {
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto ctx = whisper_init_from_file_with_params(utf8(env, path).c_str(), params);
    if (ctx && !whisper_is_multilingual(ctx)) { whisper_free(ctx); return 0; }
    return reinterpret_cast<jlong>(ctx);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_seedhecode_incidentassistant_engine_NativeWhisper_transcribe(JNIEnv *env, jobject,
        jlong handle, jfloatArray input, jstring language, jboolean translate) {
    auto ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Whisper is not loaded."); return nullptr; }
    std::vector<float> samples(env->GetArrayLength(input));
    env->GetFloatArrayRegion(input, 0, samples.size(), samples.data());
    std::string lang = utf8(env, language);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;
    params.translate = translate;
    params.language = lang == "auto" ? nullptr : lang.c_str();
    params.no_context = true;
    params.print_realtime = false; params.print_progress = false;
    params.print_timestamps = false; params.print_special = false;
    params.suppress_blank = true;
    if (samples.empty() || whisper_full(ctx, params, samples.data(), samples.size()) != 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Whisper transcription failed."); return nullptr;
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(ctx); ++i) text += whisper_full_get_segment_text(ctx, i);
    const char *detected = whisper_lang_str(whisper_full_lang_id(ctx));
    return result(env, "{\"text\":" + quote(text) + ",\"language\":" + quote(detected ? detected : "unknown") + "}");
}
extern "C" JNIEXPORT void JNICALL
Java_org_seedhecode_incidentassistant_engine_NativeWhisper_release(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}
