package ca.gregoirepainchaud.pixelbridge.bench

/**
 * Moteur llama.cpp du banc d'essai (voir bench_jni.cpp).
 * Chargement d'un GGUF, génération gloutonne (reproductible),
 * temps lus dans le moteur lui-même.
 */
class LlamaEngine {

    companion object {
        init {
            System.loadLibrary("bench_jni")
        }
    }

    private var handle: Long = 0

    private external fun nativeLoadModel(path: String): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String?
    private external fun nativeLastStats(handle: Long): String
    private external fun nativeFree(handle: Long)

    val charge: Boolean get() = handle != 0L

    fun load(path: String) {
        free()
        handle = nativeLoadModel(path)
        check(handle != 0L) { "Échec du chargement du modèle : $path" }
    }

    fun generate(prompt: String, maxTokens: Int = 1024): String {
        check(handle != 0L) { "Aucun modèle chargé." }
        return nativeGenerate(handle, prompt, maxTokens) ?: ""
    }

    fun stats(): String = if (handle != 0L) nativeLastStats(handle) else ""

    fun free() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
    }
}
