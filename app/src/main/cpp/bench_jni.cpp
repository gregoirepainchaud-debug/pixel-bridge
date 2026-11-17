// Pont JNI minimal vers llama.cpp pour le banc d'essai maison.
// API C seulement : charger un GGUF, générer sur un prompt, et lire
// les temps du moteur lui-même (llama_perf) — pas d'horloge externe,
// pas d'approximation. Échantillonnage glouton (température 0) :
// le banc d'essai doit être reproductible d'un run à l'autre.

#include <jni.h>
#include <chrono>
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
        JNIEnv * env, jobject thiz, jstring chemin, jint fils) {
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
    // Fils réglables depuis l'écran du banc : le banc mesure
    // l'effet du nombre de fils au lieu de le deviner.
    if (fils < 1) fils = 1;
    cparams.n_threads = fils;
    cparams.n_threads_batch = fils;
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
        jint max_jetons, jobject sink) {
    (void) thiz;
    auto * m = reinterpret_cast<Moteur *>(poignee);
    if (m == nullptr || m->ctx == nullptr) return nullptr;

    // Récepteur de flux (sortie affichée au fur et à mesure).
    // Les pièces peuvent couper un caractère UTF-8 en deux : l'aperçu
    // en flux peut montrer un caractère de remplacement, le texte
    // final retourné, lui, est complet et valide.
    // onPhase reçoit les étapes chronométrées (lecture du prompt,
    // compteur de génération) : le banc ne doit plus jamais attendre
    // dans le noir sans savoir quelle phase travaille.
    jmethodID methodeToken = nullptr;
    jmethodID methodePhase = nullptr;
    if (sink != nullptr) {
        jclass classeSink = env->GetObjectClass(sink);
        methodeToken = env->GetMethodID(
            classeSink, "onToken", "(Ljava/lang/String;)V");
        methodePhase = env->GetMethodID(
            classeSink, "onPhase", "(Ljava/lang/String;)V");
    }
    std::string flux;
    auto viderFlux = [&]() {
        if (sink != nullptr && methodeToken != nullptr && !flux.empty()) {
            jstring js = env->NewStringUTF(flux.c_str());
            env->CallVoidMethod(sink, methodeToken, js);
            env->DeleteLocalRef(js);
            flux.clear();
        }
    };
    auto phase = [&](const std::string & texte) {
        if (sink != nullptr && methodePhase != nullptr) {
            jstring js = env->NewStringUTF(texte.c_str());
            env->CallVoidMethod(sink, methodePhase, js);
            env->DeleteLocalRef(js);
        }
    };
    using Horloge = std::chrono::steady_clock;
    auto secondes = [](Horloge::time_point a, Horloge::time_point b) {
        return std::chrono::duration<double>(b - a).count();
    };

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

    // Évaluation du prompt par lots de 512, chronométrée à part.
    {
        char tampon_phase[192];
        snprintf(tampon_phase, sizeof(tampon_phase),
                 "Lecture du prompt : %d jetons…", n);
        phase(tampon_phase);
    }
    const auto debut_lecture = Horloge::now();
    // Lots de 64 : la lecture se voit avancer en temps réel, avec
    // sa vitesse (demande de Grégoire, 2026-11-16 — à 512, un lot
    // atomique lent laissait l'écran figé des minutes).
    const int lot = 64;
    for (int i = 0; i < n; i += lot) {
        int morceau = std::min(lot, n - i);
        llama_batch batch = llama_batch_get_one(jetons.data() + i, morceau);
        if (llama_decode(m->ctx, batch) != 0) {
            LOGI("nativeGenerate : échec du décodage du prompt");
            return nullptr;
        }
        double t_morceau = secondes(debut_lecture, Horloge::now());
        char tampon_phase[192];
        snprintf(tampon_phase, sizeof(tampon_phase),
                 "Lecture du prompt : %d/%d jetons, %.1f s (%.0f t/s)",
                 i + morceau, n, t_morceau,
                 t_morceau > 0 ? (i + morceau) / t_morceau : 0);
        phase(tampon_phase);
    }
    const double t_lecture = secondes(debut_lecture, Horloge::now());
    {
        char tampon_phase[192];
        snprintf(tampon_phase, sizeof(tampon_phase),
                 "Prompt lu : %d jetons en %.1f s (%.0f t/s) — génération…",
                 n, t_lecture, t_lecture > 0 ? n / t_lecture : 0);
        phase(tampon_phase);
    }
    const auto debut_generation = Horloge::now();

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
        if (k > 0) {
            sortie.append(tampon, k);
            flux.append(tampon, k);
            // Compteur en temps réel, jeton par jeton (1, 2, 3…) :
            // à ces vitesses (quelques jetons/s), le coût d'un
            // rafraîchissement par jeton est négligeable, et on voit
            // la machine travailler — ou pas.
            viderFlux();
            double t_gen = secondes(debut_generation, Horloge::now());
            char tampon_phase[192];
            snprintf(tampon_phase, sizeof(tampon_phase),
                     "Génération : %d jetons, %.1f s (%.1f t/s)",
                     genere + 1, t_gen,
                     t_gen > 0 ? (genere + 1) / t_gen : 0);
            phase(tampon_phase);
        }
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
    viderFlux();
    {
        double t_gen = secondes(debut_generation, Horloge::now());
        char tampon_phase[192];
        snprintf(tampon_phase, sizeof(tampon_phase),
                 "Génération terminée en %.1f s", t_gen);
        phase(tampon_phase);
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
