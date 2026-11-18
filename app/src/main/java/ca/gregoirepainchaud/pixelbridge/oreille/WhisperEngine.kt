package ca.gregoirepainchaud.pixelbridge.oreille

/**
 * Moteur de transcription de l'écoute : whisper.cpp via JNI.
 *
 * Né dans le banc d'écoute (il y remplaçait LlamaEngine, banc
 * d'extraction LLM, dossier clos le 2026-11-16 : le modèle
 * local est abandonné comme interprète), c'est devenu le
 * moteur de production du service et de l'écran Oreille —
 * micro -> texte, en temps réel, avec le facteur temps réel
 * affiché à chaque fenêtre. Le banc caché s'en sert toujours.
 * (Chantier structure 0.8 : déplacé de bench/ vers oreille/,
 * son paquet d'usage réel ; la bibliothèque native garde son
 * nom historique « bench_jni ».)
 */
class WhisperEngine {

    private var handle: Long = 0

    private external fun nativeLoadModel(path: String, threads: Int): Long
    private external fun nativeTranscribe(
        handle: Long, pcm: FloatArray, prompt: String
    ): String
    private external fun nativeLastTokens(handle: Long): String
    private external fun nativeFree(handle: Long)

    fun load(path: String, threads: Int = 4) {
        check(erreurBibliotheque == null) {
            "Bibliothèque native non chargée : $erreurBibliotheque"
        }
        // Même verrou que transcribe : charger (donc libérer l'ancien
        // contexte) ne doit jamais chevaucher une transcription en
        // cours — la passe de correction à l'arrêt échange le modèle
        // pendant qu'un segment peut encore finir.
        synchronized(this) {
            if (handle != 0L) {
                nativeFree(handle)
                handle = 0
            }
            handle = nativeLoadModel(path, threads)
        }
        check(handle != 0L) { "Échec du chargement du modèle Whisper : $path" }
    }

    /**
     * Transcrit un tampon PCM float 16 kHz mono.
     *
     * Verrouillé : whisper_full n'est PAS sûr entre fils sur un même
     * contexte. Sans ce verrou, peser « Arrêter » pendant qu'une
     * fenêtre était en cours de transcription lançait la passe
     * finale en parallèle sur le même contexte — le processus
     * mourait et l'app revenait à son écran d'accueil (constaté le
     * 2026-11-16). Les appels se mettent en file, jamais en
     * parallèle.
     */
    fun transcribe(pcm: FloatArray, prompt: String = ""): String =
        synchronized(this) {
            check(handle != 0L) { "Aucun modèle Whisper chargé" }
            nativeTranscribe(handle, pcm, prompt)
        }

    /**
     * Transcription + jetons de la passe, sous le même verrou : le
     * texte et le JSON des jetons ([{t0, t1 en centisecondes, p,
     * txt}]) viennent de la MÊME passe — les probabilités par mot
     * servent à repérer les incertitudes horodatées.
     */
    fun transcribeComplet(
        pcm: FloatArray, prompt: String = ""
    ): Pair<String, String> =
        synchronized(this) {
            check(handle != 0L) { "Aucun modèle Whisper chargé" }
            val texte = nativeTranscribe(handle, pcm, prompt)
            val jetons = try {
                nativeLastTokens(handle)
            } catch (e: Throwable) {
                "[]"
            }
            Pair(texte, jetons)
        }

    fun free() {
        synchronized(this) {
            if (handle != 0L) {
                nativeFree(handle)
                handle = 0
            }
        }
    }

    companion object {
        @Volatile
        private var erreurBibliotheque: String? = null

        init {
            try {
                System.loadLibrary("bench_jni")
            } catch (e: Throwable) {
                erreurBibliotheque = e.message ?: e.toString()
            }
        }

        fun erreurBibliotheque(): String? = erreurBibliotheque
    }
}
