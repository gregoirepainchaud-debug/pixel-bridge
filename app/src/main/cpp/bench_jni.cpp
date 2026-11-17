// Pont JNI minimal vers llama.cpp pour le banc d'essai maison.
// API C seulement : charger un GGUF, générer sur un prompt, et lire
// les temps du moteur lui-même (llama_perf) — pas d'horloge externe,
// pas d'approximation. Échantillonnage glouton (température 0) :
// le banc d'essai doit être reproductible d'un run à l'autre.

#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>
#include <android/log.h>
#include "llama.h"
#include "ggml-cpu.h"

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
    // Groupes de fils explicites (expérience « synchronisation ») :
    // épinglés sur les gros cœurs quand le banc le demande.
    ggml_threadpool * poolGen = nullptr;
    ggml_threadpool * poolBatch = nullptr;
    std::string info_pools;
};

// Masque des « gros cœurs » : lit la fréquence max de chaque cœur
// dans /sys et retient les nThreads plus rapides. Si /sys est
// illisible, repli classique Android : les derniers cœurs sont les
// gros. Retourne la description pour le journal du run.
static std::string construireMasque(bool * masque, int nThreads) {
    for (int i = 0; i < GGML_MAX_N_THREADS; i++) masque[i] = false;
    int nCpu = (int) sysconf(_SC_NPROCESSORS_ONLN);
    if (nCpu <= 0) nCpu = 8;
    std::vector<std::pair<long, int>> freqs;
    for (int i = 0; i < nCpu; i++) {
        char chemin[128];
        snprintf(chemin, sizeof chemin,
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        long v = 0;
        FILE * f = fopen(chemin, "r");
        if (f) {
            if (fscanf(f, "%ld", &v) != 1) v = 0;
            fclose(f);
        }
        freqs.push_back({v, i});
    }
    std::sort(freqs.begin(), freqs.end(),
              [](const std::pair<long, int> & a, const std::pair<long, int> & b) {
                  return a.first > b.first;
              });
    bool illisibles = freqs.empty() || freqs[0].first == 0;
    std::string desc = "coeurs [";
    int gardes = 0;
    for (int k = 0; k < (int) freqs.size() && gardes < nThreads; k++) {
        int cpu = illisibles ? (nCpu - 1 - k) : freqs[k].second;
        if (cpu < 0 || cpu >= GGML_MAX_N_THREADS) continue;
        masque[cpu] = true;
        if (gardes > 0) desc += ",";
        desc += std::to_string(cpu);
        gardes++;
    }
    desc += "]";
    if (illisibles) desc += " (repli : /sys illisible)";
    return desc;
}

static void journal_llama(enum ggml_log_level niveau, const char * texte,
                          void * donnees) {
    (void) niveau; (void) donnees;
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", texte);
}

extern "C" JNIEXPORT jlong JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_LlamaEngine_nativeLoadModel(
        JNIEnv * env, jobject thiz, jstring chemin, jint filsGen,
        jint filsBatch, jint poll, jboolean epingler) {
    (void) thiz;
    const char * path = env->GetStringUTFChars(chemin, nullptr);
    llama_log_set(journal_llama, nullptr);
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    // Chargement en RAM pour de vrai (LOAD_MODE_NONE : lecture
    // ordinaire du fichier, pas de mmap). Constat du 2026-11-16
    // (run 5) : avec le mmap par défaut, la lecture du prompt
    // bondit à 34 t/s en Release mais la génération reste à
    // ~1 t/s — la signature d'un modèle relu depuis le stockage à
    // chaque jeton (éviction des pages entre deux jetons) plutôt
    // que tenu en mémoire vive. (Dans cette version de llama.cpp,
    // l'ancien booléen use_mmap est devenu l'énum load_mode.)
    mparams.load_mode = LLAMA_LOAD_MODE_NONE;
    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(chemin, path);
    if (model == nullptr) {
        LOGI("nativeLoadModel : échec du chargement");
        return 0;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    // Fils réglables depuis l'écran du banc, SÉPARÉS par phase
    // (question de Grégoire, 2026-11-16) : la lecture du prompt
    // (batch) et la génération (jeton par jeton) n'ont pas le même
    // point doux — la génération plafonne à 2 fils (bande passante),
    // la lecture aime plus de fils. llama.cpp a deux groupes de
    // fils exactement pour ça.
    if (filsGen < 1) filsGen = 1;
    if (filsBatch < 1) filsBatch = 1;
    cparams.n_threads = filsGen;
    cparams.n_threads_batch = filsBatch;
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
    // Expérience « synchronisation » (2026-11-16, runs 7-8) : la
    // génération s'effondre à fils = 5 (0,5-1 t/s) mais bondit à
    // fils = 2 (5 t/s) — le coût de réveil/synchronisation des fils
    // domine la génération. Au lieu d'éviter les fils, on les
    // ÉPINGLE sur les gros cœurs (masque d'affinité + placement
    // strict) avec un niveau de poll réglable : les fils attendent
    // en tournant plutôt qu'en dormant, aucun ne migre sur un petit
    // cœur. Les réglages par défaut de ggml visent les serveurs,
    // pas un SoC de téléphone à cœurs asymétriques.
    if (epingler) {
        // Chaque groupe reçoit SON masque : les filsBatch plus
        // rapides pour la lecture, les filsGen plus rapides pour
        // la génération.
        auto fairePool = [&](int nFils, std::string & desc) {
            bool masque[GGML_MAX_N_THREADS];
            desc = construireMasque(masque, nFils);
            ggml_threadpool_params tpp;
            memset(&tpp, 0, sizeof tpp);
            memcpy(tpp.cpumask, masque, sizeof tpp.cpumask);
            tpp.n_threads = nFils;
            tpp.prio = GGML_SCHED_PRIO_NORMAL;
            tpp.poll = (uint32_t) (poll < 0 ? 0 : (poll > 100 ? 100 : poll));
            tpp.strict_cpu = true;
            return ggml_threadpool_new(&tpp);
        };
        std::string descGen, descBatch;
        m->poolGen = fairePool(filsGen, descGen);
        m->poolBatch = fairePool(filsBatch, descBatch);
        if (m->poolGen != nullptr && m->poolBatch != nullptr) {
            llama_attach_threadpool(ctx, m->poolGen, m->poolBatch);
            m->info_pools = "épinglé génération " + descGen +
                            " / lecture " + descBatch + ", poll=" +
                            std::to_string(poll < 0 ? 0 : (poll > 100 ? 100 : poll)) +
                            ", strict";
        } else {
            m->info_pools = "épinglage ÉCHOUÉ (création des groupes)";
        }
        LOGI("nativeLoadModel : %s", m->info_pools.c_str());
    } else {
        m->info_pools = "pools par défaut de llama (poll non réglé)";
    }
    LOGI("nativeLoadModel : prêt");
    return reinterpret_cast<jlong>(m);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ca_gregoirepainchaud_pixelbridge_bench_LlamaEngine_nativeInfoPools(
        JNIEnv * env, jobject thiz, jlong poignee) {
    (void) thiz;
    auto * m = reinterpret_cast<Moteur *>(poignee);
    if (m == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(m->info_pools.c_str());
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
    if (m->poolGen) ggml_threadpool_free(m->poolGen);
    if (m->poolBatch) ggml_threadpool_free(m->poolBatch);
    if (m->model) llama_model_free(m->model);
    delete m;
}
