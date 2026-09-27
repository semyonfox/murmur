#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_dev_local_murmur_LocalWhisper_nativeOpen(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_local_murmur_LocalWhisper_nativeClose(JNIEnv *, jobject, jlong context_ptr) {
    if (context_ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(context_ptr));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_local_murmur_LocalWhisper_nativeTranscribe(
    JNIEnv *env, jobject, jlong context_ptr, jfloatArray samples, jstring language, jboolean translate) {
    auto *context = reinterpret_cast<whisper_context *>(context_ptr);
    if (!context) return nullptr;
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (!lang || !data) {
        if (lang) env->ReleaseStringUTFChars(language, lang);
        if (data) env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
        return nullptr;
    }
    const jsize length = env->GetArrayLength(samples);
    auto full = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    full.language = lang;
    full.translate = translate;
    full.no_context = true;
    full.print_realtime = false;
    full.print_progress = false;
    full.print_timestamps = false;
    full.print_special = false;
    full.n_threads = 4;
    const int status = whisper_full(context, full, data, length);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    std::string result;
    if (status == 0) {
        for (int i = 0; i < whisper_full_n_segments(context); ++i) {
            result += whisper_full_get_segment_text(context, i);
        }
    }
    if (status != 0) return nullptr;
    auto *bytes = env->NewByteArray(static_cast<jsize>(result.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()),
        reinterpret_cast<const jbyte *>(result.data()));
    return bytes;
}
