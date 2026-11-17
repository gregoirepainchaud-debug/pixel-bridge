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
    // Préfixe constant (les règles du prompt) gardé en mémoire KV
    // d'un run à l'autre : seule la partie variable est relue.
    std::string prefixe;
    int n_prefixe = 0;
    std::string info_prefixe;
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
        JNIEnv * env, jobject thiz, jlong poignee, jstring prompt_prefixe,
        jstring prompt_suffixe, jint max_jetons, jobject sink) {
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

    const char * texte_pre = env->GetStringUTFChars(prompt_prefixe, nullptr);
    std::string prefixe_str(texte_pre);
    env->ReleaseStringUTFChars(prompt_prefixe, texte_pre);
    const char * texte_suf = env->GetStringUTFChars(prompt_suffixe, nullptr);
    std::string suffixe_str(texte_suf);
    env->ReleaseStringUTFChars(prompt_suffixe, texte_suf);

    // Tokenisation en deux morceaux : le préfixe constant (les
    // règles) et le suffixe variable (métadonnées + transcription).
    auto tokeniser = [&](const std::string & s, bool speciaux) {
        std::vector<llama_token> js;
        if (s.empty()) return js;
        int cap = (int) s.size() + 64;
        js.resize(cap);
        int k = llama_tokenize(m->vocab, s.c_str(), (int) s.size(),
                               js.data(), cap, speciaux, true);
        if (k < 0) {
            js.resize(-k);
            k = llama_tokenize(m->vocab, s.c_str(), (int) s.size(),
                               js.data(), (int) js.size(), speciaux, true);
        }
        if (k < 0) k = 0;
        js.resize(k);
        return js;
    };
    auto jetons_pre = tokeniser(prefixe_str, true);
    auto jetons_suf = tokeniser(suffixe_str, jetons_pre.empty());
    if (jetons_pre.empty() && jetons_suf.empty()) return nullptr;

    llama_perf_context_reset(m->ctx);
    auto * memoire = llama_get_memory(m->ctx);

    // Lecture d'un segment par lots de 64, progression et vitesse
    // affichées en temps réel. Retourne le temps écoulé (secondes).
    auto lire = [&](const std::vector<llama_token> & js,
                    const char * etiquette) -> double {
        const auto debut = Horloge::now();
        const int lot = 64;
        for (int i = 0; i < (int) js.size(); i += lot) {
            int morceau = std::min(lot, (int) js.size() - i);
            llama_batch batch = llama_batch_get_one(
                const_cast<llama_token *>(js.data()) + i, morceau);
            if (llama_decode(m->ctx, batch) != 0) return -1.0;
            double t = secondes(debut, Horloge::now());
            char tp[224];
            snprintf(tp, sizeof(tp),
                     "%s : %d/%d jetons, %.1f s (%.0f t/s)",
                     etiquette, i + morceau, (int) js.size(), t,
                     t > 0 ? (i + morceau) / t : 0);
            phase(tp);
        }
        return secondes(debut, Horloge::now());
    };

    // Préfixe : réutilisé si identique au run précédent (on efface
    // seulement ce qui suit dans la mémoire KV), relu sinon.
    const bool reutilise = !jetons_pre.empty() &&
        m->prefixe == prefixe_str &&
        m->n_prefixe == (int) jetons_pre.size();
    if (reutilise) {
        llama_memory_seq_rm(memoire, 0, m->n_prefixe, -1);
        char tp[192];
        snprintf(tp, sizeof(tp),
                 "Préfixe réutilisé : %d jetons déjà en mémoire",
                 m->n_prefixe);
        phase(tp);
        m->info_prefixe = "préfixe réutilisé : " +
            std::to_string(m->n_prefixe) + " jetons";
    } else {
        llama_memory_clear(memoire, true);
        m->prefixe.clear();
        m->n_prefixe = 0;
        m->info_prefixe = "sans préfixe";
        if (!jetons_pre.empty()) {
            double t_pre = lire(jetons_pre, "Lecture du préfixe");
            if (t_pre < 0) {
                LOGI("nativeGenerate : échec du décodage du préfixe");
                return nullptr;
            }
            m->prefixe = prefixe_str;
            m->n_prefixe = (int) jetons_pre.size();
            char tp[224];
            snprintf(tp, sizeof(tp),
                     "Préfixe lu : %d jetons en %.1f s (%.0f t/s)",
                     m->n_prefixe, t_pre,
                     t_pre > 0 ? m->n_prefixe / t_pre : 0);
            phase(tp);
            m->info_prefixe = "préfixe lu : " +
                std::to_string(m->n_prefixe) + " jetons";
        }
    }
    if (!jetons_suf.empty()) {
        if (lire(jetons_suf, "Lecture des données") < 0) {
            LOGI("nativeGenerate : échec du décodage des données");
            return nullptr;
        }
    }
    const int n_total = (int) (jetons_pre.size() + jetons_suf.size());
    const auto debut_generation = Horloge::now();

    // Échantillonneur glouton : reproductible.
    llama_sampler * smpl = llama_sampler_chain_init(
        llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0));

    std::string sortie;
    char tampon[256];
    llama_token courant;
    int position = n_total;
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
    char tampon[384];
    double vitesse_prompt = d.t_p_eval_ms > 0
        ? (double) d.n_p_eval / (d.t_p_eval_ms / 1000.0) : 0;
    double vitesse_gen = d.t_eval_ms > 0
        ? (double) d.n_eval / (d.t_eval_ms / 1000.0) : 0;
    snprintf(tampon, sizeof(tampon),
             "prompt : %d jetons en %.0f ms (%.1f t/s) · "
             "génération : %d jetons en %.0f ms (%.1f t/s) · "
             "chargement : %.0f ms · %s",
             d.n_p_eval, d.t_p_eval_ms, vitesse_prompt,
             d.n_eval, d.t_eval_ms, vitesse_gen, d.t_load_ms,
             m->info_prefixe.c_str());
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
