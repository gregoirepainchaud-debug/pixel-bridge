// Pont JNI du banc d'écoute : whisper.cpp (v1.9.5, sous-module).
//
// Remplace l'ancien pont llama.cpp (banc d'extraction LLM, dossier
// clos le 2026-11-16 au soir : le modèle local est abandonné comme
// interprète — fiabilité — et le banc devient un banc d'ÉCOUTE :
// mesurer en temps réel la transcription micro -> texte).
//
// Trois appels : charger un modèle .bin, transcrire un tampon PCM
// float 16 kHz mono, libérer. whisper_full n'est pas sûr entre
// fils sur un même contexte : l'appelant transcrit séquentiellement
// depuis un seul fil de travail.

#include <jni.h>

#include <string>

#include <android/log.h>

#include "whisper.h"

#define LOG_TAG "bench_whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct MoteurWhisper {
    whisper_context * ctx = nullptr;
    int fils = 4;
    // Jetons de la dernière transcription (JSON) : temps en
    // centisecondes et probabilité de chaque mot — la matière des
    // incertitudes horodatées remontées par la passe de correction.
    std::string derniersJetons = "[]";
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_WhisperEngine_nativeLoadModel(
        JNIEnv * env, jobject thiz, jstring chemin, jint fils) {
    const char * path = env->GetStringUTFChars(chemin, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    whisper_context * ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(chemin, path);
    if (ctx == nullptr) {
        LOGE("nativeLoadModel : échec du chargement");
        return 0;
    }
    auto * m = new MoteurWhisper();
    m->ctx = ctx;
    m->fils = fils < 1 ? 1 : fils;
    LOGI("nativeLoadModel : modèle chargé (fils = %d)", m->fils);
    return reinterpret_cast<jlong>(m);
}

// Transcrit le tampon PCM (float, 16 kHz, mono). `invite` sert de
// prompt initial (queue du texte précédent) pour la continuité
// entre fenêtres. Retourne le texte des segments, concaténé.
extern "C" JNIEXPORT jstring JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_WhisperEngine_nativeTranscribe(
        JNIEnv * env, jobject thiz, jlong poignee, jfloatArray pcm,
        jstring invite) {
    auto * m = reinterpret_cast<MoteurWhisper *>(poignee);
    if (m == nullptr || m->ctx == nullptr) {
        return env->NewStringUTF("");
    }
    const jsize n = env->GetArrayLength(pcm);
    jfloat * samples = env->GetFloatArrayElements(pcm, nullptr);

    whisper_full_params p =
        whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = m->fils;
    p.language = "fr";
    p.translate = false;
    p.no_timestamps = false;
    p.token_timestamps = true;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_blank = true;

    const char * prompt = nullptr;
    if (invite != nullptr) {
        prompt = env->GetStringUTFChars(invite, nullptr);
        if (prompt != nullptr && prompt[0] != '\0') {
            p.initial_prompt = prompt;
        }
    }

    const int rc = whisper_full(m->ctx, p, samples, (int) n);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (prompt != nullptr) {
        env->ReleaseStringUTFChars(invite, prompt);
    }
    if (rc != 0) {
        LOGE("nativeTranscribe : whisper_full a échoué (%d)", rc);
        return env->NewStringUTF("");
    }
    std::string texte;
    const int nseg = whisper_full_n_segments(m->ctx);
    for (int i = 0; i < nseg; ++i) {
        texte += whisper_full_get_segment_text(m->ctx, i);
    }
    // Jetons de la passe : probabilité et temps de chaque mot.
    {
        std::string js = "[";
        bool premier = true;
        for (int i = 0; i < nseg; ++i) {
            const int ntok = whisper_full_n_tokens(m->ctx, i);
            for (int j = 0; j < ntok; ++j) {
                whisper_token_data d =
                    whisper_full_get_token_data(m->ctx, i, j);
                const char * txt = whisper_token_to_str(m->ctx, d.id);
                if (!premier) js += ",";
                premier = false;
                js += "{\"t0\":" + std::to_string(d.t0) +
                      ",\"t1\":" + std::to_string(d.t1) +
                      ",\"p\":" + std::to_string(d.p) + ",\"txt\":\"";
                for (const char * c = txt; *c; ++c) {
                    if (*c == '"' || *c == '\\') {
                        js += '\\';
                        js += *c;
                    } else if ((unsigned char) *c < 0x20) {
                        js += ' ';
                    } else {
                        js += *c;
                    }
                }
                js += "\"}";
            }
        }
        js += "]";
        m->derniersJetons = js;
    }
    return env->NewStringUTF(texte.c_str());
}

// Jetons (JSON) de la dernière transcription effectuée sur ce
// contexte : [{"t0":cs,"t1":cs,"p":0.0-1.0,"txt":"..."}].
extern "C" JNIEXPORT jstring JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_WhisperEngine_nativeLastTokens(
        JNIEnv * env, jobject thiz, jlong poignee) {
    auto * m = reinterpret_cast<MoteurWhisper *>(poignee);
    if (m == nullptr) {
        return env->NewStringUTF("[]");
    }
    return env->NewStringUTF(m->derniersJetons.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_WhisperEngine_nativeFree(
        JNIEnv * env, jobject thiz, jlong poignee) {
    auto * m = reinterpret_cast<MoteurWhisper *>(poignee);
    if (m == nullptr) return;
    if (m->ctx != nullptr) {
        whisper_free(m->ctx);
    }
    delete m;
}
