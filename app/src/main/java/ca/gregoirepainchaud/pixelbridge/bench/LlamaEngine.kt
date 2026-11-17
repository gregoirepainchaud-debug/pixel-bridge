package ca.gregoirepainchaud.pixelbridge.bench

/**
 * Moteur llama.cpp du banc d'essai (voir bench_jni.cpp).
 * Chargement d'un GGUF, génération gloutonne (reproductible),
 * temps lus dans le moteur lui-même.
 */
class LlamaEngine {

    /** Récepteur du flux de génération (aperçu à l'écran). */
    interface TokenSink {
        fun onToken(morceau: String)

        /** Étapes chronométrées : lecture du prompt, compteurs. */
        fun onPhase(texte: String)
    }

    companion object {
        // Un échec de chargement natif ne doit jamais planter l'app :
        // l'erreur est gardée et affichée à l'écran du banc.
        private var erreurBibliotheque: String? = null

        init {
            try {
                System.loadLibrary("bench_jni")
            } catch (t: Throwable) {
                erreurBibliotheque = t.message ?: t.toString()
            }
        }

        fun erreurBibliotheque(): String? = erreurBibliotheque
    }

    private var handle: Long = 0

    private external fun nativeLoadModel(
        path: String, threads: Int, poll: Int, epingler: Boolean
    ): Long
    private external fun nativeInfoPools(handle: Long): String
    private external fun nativeGenerate(
        handle: Long, prefix: String, suffix: String,
        maxTokens: Int, sink: TokenSink?
    ): String?
    private external fun nativeLastStats(handle: Long): String
    private external fun nativeFree(handle: Long)

    val charge: Boolean get() = handle != 0L

    fun load(path: String, threads: Int = 5, poll: Int = 50, epingler: Boolean = false) {
        check(erreurBibliotheque == null) {
            "Bibliothèque native non chargée : $erreurBibliotheque"
        }
        free()
        handle = nativeLoadModel(path, threads, poll, epingler)
        check(handle != 0L) { "Échec du chargement du modèle : $path" }
    }

    /** Configuration des groupes de fils du chargement (journal). */
    fun infoPools(): String = if (handle != 0L) nativeInfoPools(handle) else ""

    /**
     * Le préfixe (partie constante du prompt : les règles) est gardé
     * en mémoire KV par le moteur et réutilisé d'un appel à l'autre
     * s'il est identique ; seul le suffixe variable est relu.
     */
    fun generate(
        prefix: String, suffix: String,
        maxTokens: Int = 512, sink: TokenSink? = null
    ): String {
        check(handle != 0L) { "Aucun modèle chargé." }
        return nativeGenerate(handle, prefix, suffix, maxTokens, sink) ?: ""
    }

    fun stats(): String = if (handle != 0L) nativeLastStats(handle) else ""

    fun free() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
    }
}
