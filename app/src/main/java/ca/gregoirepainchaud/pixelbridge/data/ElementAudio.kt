package ca.gregoirepainchaud.pixelbridge.data

/** Type d'élément audio capté. Valeurs alignées sur core/extraction.schema.json. */
enum class TypeElement { APPEL, MESSAGE_VOCAL, NOTE_VOCALE }

/** Application d'origine de l'élément. */
enum class SourceApp { TELEPHONE, WHATSAPP, MESSENGER, SIGNAL, AUTRE }

/**
 * État dans le pipeline. Un élément ne recule jamais et n'est jamais
 * perdu : un échec le laisse en ERREUR avec son message.
 */
enum class StatutElement { NOUVEAU, TRANSCRIT, EXTRAIT, EN_FILE_SYNCHRO, SYNCHRONISE, ERREUR }

/**
 * Un élément audio professionnel capté par un guetteur.
 * [recuLeIso] sert de référence pour résoudre les dates relatives
 * (« mardi prochain ») lors de l'extraction.
 */
data class ElementAudio(
    val id: String,
    val type: TypeElement,
    val sourceApp: SourceApp,
    val cheminAudio: String,
    val recuLeIso: String,
    val dureeSecondes: Int? = null,
    val contactNom: String? = null,
    val contactNumero: String? = null,
    val statut: StatutElement = StatutElement.NOUVEAU,
    val transcription: String? = null,
    val extractionJson: String? = null,
    val erreur: String? = null,
)
