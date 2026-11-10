package ca.gregoirepainchaud.pixelbridge.pipeline

import ca.gregoirepainchaud.pixelbridge.data.ElementAudio

/**
 * Extraction structurée locale : transcription → JSON conforme à
 * core/extraction.schema.json (copie dans les assets de l'app).
 */
interface Extracteur {
    fun extraire(element: ElementAudio, transcription: String): String
}

/** Construit le prompt d'extraction à partir du gabarit des assets. */
object PromptExtraction {
    fun construire(gabarit: String, element: ElementAudio, transcription: String): String {
        val metadonnees = """
            type: ${element.type.name.lowercase()}
            source_app: ${element.sourceApp.name.lowercase()}
            recu_le: ${element.recuLeIso}
            contact: ${element.contactNom ?: "inconnu"}
        """.trimIndent()
        return gabarit
            .replace("{metadonnees}", metadonnees)
            .replace("{transcription}", transcription)
    }
}

/**
 * À brancher sur l'appareil : modèle local 3-4B (llama.cpp ou
 * équivalent, 12 Go de RAM). Pas de 7B+ : inutilisable (1-3 jetons/s).
 * Le stub échoue bruyamment — jamais de JSON inventé.
 */
class ExtracteurLocal : Extracteur {
    override fun extraire(element: ElementAudio, transcription: String): String {
        throw UnsupportedOperationException(
            "Modèle d'extraction local à brancher sur l'appareil (3-4B)"
        )
    }
}
