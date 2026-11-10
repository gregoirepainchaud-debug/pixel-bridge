package ca.gregoirepainchaud.pixelbridge.pipeline

import ca.gregoirepainchaud.pixelbridge.data.ElementAudio
import ca.gregoirepainchaud.pixelbridge.data.FileTraitement
import ca.gregoirepainchaud.pixelbridge.data.StatutElement

/**
 * Orchestre le traitement hors ligne :
 * NOUVEAU → (transcription) → TRANSCRIT → (extraction) → EN_FILE_SYNCHRO.
 * Un échec dépose l'élément en ERREUR avec le message ; rien n'est perdu.
 * La validation du JSON contre le schéma (core/extraction.schema.json)
 * sera branchée avec l'extracteur réel.
 */
class Pipeline(
    private val file: FileTraitement,
    private val transcripteur: Transcripteur,
    private val extracteur: Extracteur,
) {
    fun traiterSuivant(): Boolean {
        val element = file.parStatut(StatutElement.NOUVEAU).firstOrNull() ?: return false
        try {
            val transcription = transcripteur.transcrire(element)
            val transcrit = element.copy(
                statut = StatutElement.TRANSCRIT,
                transcription = transcription,
            )
            file.mettreAJour(transcrit)

            val extractionJson = extracteur.extraire(transcrit, transcription)
            file.mettreAJour(
                transcrit.copy(
                    statut = StatutElement.EN_FILE_SYNCHRO,
                    extractionJson = extractionJson,
                )
            )
        } catch (e: Exception) {
            file.mettreAJour(
                element.copy(statut = StatutElement.ERREUR, erreur = e.message)
            )
        }
        return true
    }
}
