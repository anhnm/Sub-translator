// JNI bridge to whisper.cpp for com.subtranslator.WhisperLib.
#include <jni.h>

#include <string>

#include "whisper.h"

namespace {

void silent_log(enum ggml_log_level, const char *, void *) {}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    // Returned as UTF-8 bytes: whisper output is not guaranteed to be valid modified UTF-8,
    // which NewStringUTF would abort on.
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_subtranslator_WhisperLib_nativeInit(JNIEnv *env, jobject, jstring model_path) {
    whisper_log_set(silent_log, nullptr);
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_subtranslator_WhisperLib_nativeFree(JNIEnv *, jobject, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_subtranslator_WhisperLib_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray audio, jstring language,
        jint threads, jint audio_ctx, jfloat no_speech_threshold) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    const char *lang = env->GetStringUTFChars(language, nullptr);
    jsize n = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang;
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.audio_ctx = audio_ctx;

    std::string text;
    if (whisper_full(ctx, p, samples, n) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) {
            // Skip segments whisper itself considers silence/music.
            if (whisper_full_get_segment_no_speech_prob(ctx, i) > no_speech_threshold) continue;
            text += whisper_full_get_segment_text(ctx, i);
        }
    }

    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    return to_bytes(env, text);
}
