package ca.gregoirepainchaud.pixelbridge.pipeline

import ca.gregoirepainchaud.pixelbridge.data.ElementAudio

/** Transcription locale d'un élément audio (français). */
interface Transcripteur {
    fun transcrire(element: ElementAudio): String
}

/**
 * À brancher sur l'appareil : whisper.cpp (JNI), modèle français.
 * Le stub échoue bruyamment plutôt que de produire une fausse
 * transcription — le pipeline laissera l'élément en ERREUR.
 */
class TranscripteurWhisper : Transcripteur {
    override fun transcrire(element: ElementAudio): String {
        throw UnsupportedOperationException(
            "Whisper à brancher sur l'appareil (whisper.cpp, modèle FR)"
        )
    }
}
