package ca.gregoirepainchaud.pixelbridge.tuner

import java.util.Locale

// ---------------------------------------------------------------------------
// Télémétrie de l'accordeur (0.14) — demande de Grégoire : un
// start et un stop pour enregistrer un log à transmettre par le
// pont, « toutes les hauteurs détectées pour chaque
// échantillonnage et comment se comportait le curseur de
// l'affichage pendant ce temps », pour régler l'affichage avec
// des chiffres plutôt qu'au pif.
//
// Format : JSONL — une ligne JSON par enregistrement, trois
// types de lignes dans le MÊME fichier :
//   {"type":"entete", ...}  une fois, au départ : version de
//       l'app, horodatage ABSOLU du départ (ISO — pour recaler
//       la session avec le souvenir de Grégoire), les six
//       réglages fins en vigueur, le slider, l'accord
//       sélectionné, le diapason, le taux d'échantillonnage ;
//   {"type":"ech", ...}     à chaque échantillonnage du
//       traqueur (~85 ms) : t_ms relatif, note cible, hauteur
//       estimée (Hz de sortie + cents BRUTS de la dernière
//       estimation + cents de la sortie médiane), confiance,
//       décision d'admission (franche / continuité / attaque /
//       rejetée) avec le poids de suivi en vigueur, NIVEAU
//       SONORE de la trame (dB — sans lui, ni la « tenue si le
//       son baisse » ni les chutes de confiance ne
//       s'interprètent), nombre d'estimations retenues dans la
//       sortie, valeur lissée de l'aiguille, position du
//       slider, mode ;
//   {"type":"evt", ...}     le canal d'événements, horodaté
//       dans la même échelle : frappes détectées, changements
//       de note adoptée, de mode, d'accord, de diapason, TOUT
//       changement de paramètre ou du slider EN COURS de log
//       (sinon les données deviennent ininterprétables),
//       préemptions du micro par l'Oreille, et les MARQUES de
//       Grégoire (bouton « Marquer » : « là ça a sauté » — le
//       chaînon entre son verdict subjectif et les chiffres) ;
//   {"type":"fin", ...}     à l'arrêt : durée et compteurs.
//
// Cette classe est PURE (aucun Android) : l'horloge (monotone,
// ms) lui est fournie par l'appelant ; elle accumule le texte
// en mémoire (une session de quelques minutes ≈ quelques
// centaines de Ko) et le rend d'un bloc à l'arrêt. Utilisée
// depuis le seul fil d'interface (TunerView). Tests JVM :
// TelemetrieTest. L'envoi par le pont vit dans
// gestion/Telemetrie.kt.
// ---------------------------------------------------------------------------

class EnregistreurTelemetrie {

    /** En-tête de session : l'état complet au départ du log. */
    data class Entete(
        val versionApp: String,
        val debutIso: String,
        val picMs: Int,
        val pleinPoidsMs: Int,
        val pleinPoidsEffectifMs: Int,
        val medianeEch: Int,
        val tenueMs: Int,
        val confiancePct: Int,
        val porteCents: Double,
        val slider: Int,
        val alphaSlider: Double,
        val accordNom: String,
        val accordId: Int,
        val diapason: Double,
        val tauxHz: Int
    )

    /** Un échantillonnage du traqueur, vu de l'écran. */
    data class Echantillon(
        /** ms depuis le départ du log. */
        val tMs: Long,
        /** Touche cible/adoptée (1-88), null si inconnue. */
        val note: Int?,
        /** Hauteur de sortie (Hz), null si rien de mesuré. */
        val hz: Double?,
        /** Cents de la dernière estimation BRUTE (avant médiane). */
        val centsBrut: Double?,
        /** Cents de la sortie du traqueur (la médiane affichée). */
        val centsMediane: Double?,
        /** Cents de l'aiguille après lissage du slider. */
        val centsAiguille: Double?,
        /** Confiance de trame du traqueur (0-1). */
        val confiance: Double,
        /** DECISION_* du traqueur. */
        val decision: String,
        /** Poids de suivi en vigueur (rampe d'attaque). */
        val poids: Double,
        /** Niveau sonore de la trame (dB, négatif). */
        val niveauDb: Double,
        /** Estimations de l'historique ayant produit la sortie. */
        val nbRetenues: Int,
        /** Âge de la sortie (s depuis la dernière admission) —
         * c'est lui que le paramètre « tenue » borne. */
        val ageSec: Double,
        /** Position du slider de réactivité (0-100). */
        val slider: Int,
        /** Mode en vigueur (auto / demi / bloque). */
        val mode: String
    )

    var enCours: Boolean = false
        private set
    var nbEchantillons: Int = 0
        private set
    var nbEvenements: Int = 0
        private set

    private val lignes = StringBuilder()
    private var debutMs: Long = 0

    /** Durée écoulée depuis le départ (ms), 0 hors session. */
    fun dureeMs(maintenantMs: Long): Long =
        if (debutMs > 0) maxOf(0, maintenantMs - debutMs) else 0

    fun demarrer(maintenantMs: Long, entete: Entete) {
        lignes.setLength(0)
        nbEchantillons = 0
        nbEvenements = 0
        debutMs = maintenantMs
        enCours = true
        ligne(
            "{\"type\":\"entete\"" +
                ",\"version_app\":${texte(entete.versionApp)}" +
                ",\"debut_iso\":${texte(entete.debutIso)}" +
                ",\"pic_ms\":${entete.picMs}" +
                ",\"plein_poids_ms\":${entete.pleinPoidsMs}" +
                ",\"plein_poids_effectif_ms\":${entete.pleinPoidsEffectifMs}" +
                ",\"mediane_ech\":${entete.medianeEch}" +
                ",\"tenue_ms\":${entete.tenueMs}" +
                ",\"confiance_pct\":${entete.confiancePct}" +
                ",\"porte_cents\":${nombre(entete.porteCents, 2)}" +
                ",\"slider\":${entete.slider}" +
                ",\"alpha_slider\":${nombre(entete.alphaSlider, 3)}" +
                ",\"accord\":${texte(entete.accordNom)}" +
                ",\"accord_id\":${entete.accordId}" +
                ",\"diapason\":${nombre(entete.diapason, 1)}" +
                ",\"taux_hz\":${entete.tauxHz}" +
                "}"
        )
    }

    /** Ajoute un échantillon ; ignoré hors session. */
    fun echantillon(e: Echantillon) {
        if (!enCours) return
        nbEchantillons++
        ligne(
            "{\"type\":\"ech\"" +
                ",\"t_ms\":${e.tMs}" +
                ",\"note\":${e.note?.toString() ?: "null"}" +
                ",\"hz\":${nombreOuNull(e.hz, 2)}" +
                ",\"cents_brut\":${nombreOuNull(e.centsBrut, 2)}" +
                ",\"cents_mediane\":${nombreOuNull(e.centsMediane, 2)}" +
                ",\"cents_aiguille\":${nombreOuNull(e.centsAiguille, 2)}" +
                ",\"confiance\":${nombre(e.confiance, 3)}" +
                ",\"decision\":${texte(e.decision)}" +
                ",\"poids\":${nombre(e.poids, 2)}" +
                ",\"niveau_db\":${nombre(e.niveauDb, 1)}" +
                ",\"nb_retenues\":${e.nbRetenues}" +
                ",\"age_s\":${nombre(e.ageSec, 2)}" +
                ",\"slider\":${e.slider}" +
                ",\"mode\":${texte(e.mode)}" +
                "}"
        )
    }

    /**
     * Ajoute un événement au canal horodaté ; ignoré hors
     * session. [details] : valeurs String, Int, Long, Double,
     * Boolean ou null, sérialisées dans l'ordre de la map.
     */
    fun evenement(tMs: Long, type: String, details: Map<String, Any?> = emptyMap()) {
        if (!enCours) return
        nbEvenements++
        val sb = StringBuilder()
        sb.append("{\"type\":\"evt\",\"t_ms\":").append(tMs)
        sb.append(",\"evt\":").append(texte(type))
        for ((cle, valeur) in details) {
            sb.append(',').append(texte(cle)).append(':').append(valeurJson(valeur))
        }
        sb.append('}')
        ligne(sb.toString())
    }

    /**
     * Clôt la session et retourne le log complet (JSONL).
     * L'enregistreur peut ensuite redémarrer.
     */
    fun arreter(maintenantMs: Long): String {
        if (enCours) {
            ligne(
                "{\"type\":\"fin\"" +
                    ",\"t_ms\":${dureeMs(maintenantMs)}" +
                    ",\"nb_ech\":$nbEchantillons" +
                    ",\"nb_evt\":$nbEvenements" +
                    "}"
            )
            enCours = false
        }
        return lignes.toString()
    }

    private fun ligne(s: String) {
        lignes.append(s).append('\n')
    }

    companion object {
        // Types d'événements du canal horodaté.
        const val EVT_FRAPPE = "frappe"
        const val EVT_NOTE_ADOPTEE = "note_adoptee"
        const val EVT_MODE = "mode"
        const val EVT_PARAMETRE = "parametre"
        const val EVT_SLIDER = "slider"
        const val EVT_ACCORD = "accord"
        const val EVT_DIAPASON = "diapason"
        const val EVT_MICRO = "micro"
        const val EVT_MARQUE = "marque"

        private fun nombre(v: Double, decimales: Int): String =
            if (v.isFinite()) {
                String.format(Locale.ROOT, "%.${decimales}f", v)
            } else {
                "null"
            }

        private fun nombreOuNull(v: Double?, decimales: Int): String =
            if (v == null) "null" else nombre(v, decimales)

        private fun valeurJson(v: Any?): String = when (v) {
            null -> "null"
            is String -> texte(v)
            is Boolean -> v.toString()
            is Int -> v.toString()
            is Long -> v.toString()
            is Double -> nombre(v, 3)
            else -> texte(v.toString())
        }

        /** Chaîne JSON échappée (guillemets compris). */
        private fun texte(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
