// Pont JNI minimal vers llama.cpp pour le banc d'essai maison.
// API C seulement : charger un GGUF, générer sur un prompt, et lire
// les temps du moteur lui-même (llama_perf) — pas d'horloge externe,
// pas d'approximation. Échantillonnage glouton (température 0) :
// le banc d'essai doit être reproductible d'un run à l'autre.

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "BenchLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

struct Moteur {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
};

static void journal_llama(enum ggml_log_level niveau, const char * texte,
                          void * donnees) {
    (void) niveau; (void) donnees;
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", texte);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_LlamaEngine_nativeLoadModel(
        JNIEnv * env, jobject thiz, jstring chemin) {
    (void) thiz;
    const char * path = env->GetStringUTFChars(chemin, nullptr);
    llama_log_set(journal_llama, nullptr);
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(chemin, path);
    if (model == nullptr) {
        LOGI("nativeLoadModel : échec du chargement");
        return 0;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGI("nativeLoadModel : échec du contexte");
        llama_model_free(model);
        return 0;
    }
    auto * m = new Moteur();
    m->model = model;
    m->ctx = ctx;
    m->vocab = llama_model_get_vocab(model);
    LOGI("nativeLoadModel : prêt");
    return reinterpret_cast<jlong>(m);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_LlamaEngine_nativeGenerate(
        JNIEnv * env, jobject thiz, jlong poignee, jstring prompt_texte,
        jint max_jetons) {
    (void) thiz;
    auto * m = reinterpret_cast<Moteur *>(poignee);
    if (m == nullptr || m->ctx == nullptr) return nullptr;

    const char * texte = env->GetStringUTFChars(prompt_texte, nullptr);
    std::string prompt(texte);
    env->ReleaseStringUTFChars(prompt_texte, texte);

    // Tokenisation du prompt.
    int capacite = (int) prompt.size() + 64;
    std::vector<llama_token> jetons(capacite);
    int n = llama_tokenize(m->vocab, prompt.c_str(), (int) prompt.size(),
                           jetons.data(), capacite, true, true);
    if (n < 0) {
        jetons.resize(-n);
        n = llama_tokenize(m->vocab, prompt.c_str(), (int) prompt.size(),
                           jetons.data(), (int) jetons.size(), true, true);
    }
    if (n <= 0) return nullptr;
    jetons.resize(n);

    llama_perf_context_reset(m->ctx);
    llama_memory_clear(llama_get_memory(m->ctx), true);

    // Évaluation du prompt par lots de 512.
    const int lot = 512;
    for (int i = 0; i < n; i += lot) {
        int morceau = std::min(lot, n - i);
        llama_batch batch = llama_batch_get_one(jetons.data() + i, morceau);
        if (llama_decode(m->ctx, batch) != 0) {
            LOGI("nativeGenerate : échec du décodage du prompt");
            return nullptr;
        }
    }

    // Échantillonneur glouton : reproductible.
    llama_sampler * smpl = llama_sampler_chain_init(
        llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    std::string sortie;
    char tampon[256];
    llama_token courant;
    int position = n;
    for (int genere = 0; genere < max_jetons; genere++) {
        courant = llama_sampler_sample(smpl, m->ctx, -1);
        if (llama_vocab_is_eog(m->vocab, courant)) break;
        int k = llama_token_to_piece(m->vocab, courant, tampon,
                                     sizeof(tampon), 0, true);
        if (k > 0) sortie.append(tampon, k);
        llama_batch batch = llama_batch_get_one(&courant, 1);
        // llama_batch_get_one place le jeton à la position 0 du lot ;
        // le contexte suit la séquence depuis le prompt déjà décodé.
        (void) position;
        if (llama_decode(m->ctx, batch) != 0) {
            LOGI("nativeGenerate : échec du décodage en génération");
            break;
        }
        position++;
    }
    llama_sampler_free(smpl);
    return env->NewStringUTF(sortie.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_LlamaEngine_nativeLastStats(
        JNIEnv * env, jobject thiz, jlong poignee) {
    (void) thiz;
    auto * m = reinterpret_cast<Moteur *>(poignee);
    if (m == nullptr || m->ctx == nullptr) return env->NewStringUTF("");
    llama_perf_context_data d = llama_perf_context(m->ctx);
    char tampon[256];
    double vitesse_prompt = d.t_p_eval_ms > 0
        ? (double) d.n_p_eval / (d.t_p_eval_ms / 1000.0) : 0;
    double vitesse_gen = d.t_eval_ms > 0
        ? (double) d.n_eval / (d.t_eval_ms / 1000.0) : 0;
    snprintf(tampon, sizeof(tampon),
             "prompt : %d jetons en %.0f ms (%.1f t/s) · "
             "génération : %d jetons en %.0f ms (%.1f t/s) · "
             "chargement : %.0f ms",
             d.n_p_eval, d.t_p_eval_ms, vitesse_prompt,
             d.n_eval, d.t_eval_ms, vitesse_gen, d.t_load_ms);
    return env->NewStringUTF(tampon);
}

extern "C" JNIEXPORT void JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_LlamaEngine_nativeFree(
        JNIEnv * env, jobject thiz, jlong poignee) {
    (void) env; (void) thiz;
    auto * m = reinterpret_cast<Moteur *>(poignee);
    if (m == nullptr) return;
    if (m->ctx) llama_free(m->ctx);
    if (m->model) llama_model_free(m->model);
    delete m;
}
