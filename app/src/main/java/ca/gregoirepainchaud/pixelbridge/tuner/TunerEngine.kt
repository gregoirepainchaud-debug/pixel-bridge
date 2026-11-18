package ca.gregoirepainchaud.pixelbridge.tuner

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.log10

// ---------------------------------------------------------------------------
// Données des accords (tunings.json — le même fichier que le prototype
// web, lu et parsé nativement ; aucune donnée ne change).
// ---------------------------------------------------------------------------

data class NoteAccord(
    val key: Int,
    val name: String,
    val fundamentalHz440: Double,
    val partialTargetHz440: Double,
    val listenPartial: Int
)

data class Accord(
    val id: Int,
    val name: String,
    val serial: String,
    val notes: List<NoteAccord>
)

data class DonneesAccords(
    val referencePitch: Int,
    val pitchChoicesHz: List<Int>,
    val tunings: List<Accord>
)

/** Parse le contenu de tunings.json (org.json, natif Android). */
fun parserDonneesAccords(json: String): DonneesAccords {
    val racine = JSONObject(json)
    val choix = racine.getJSONArray("pitchChoicesHz")
    val pitchChoices = List(choix.length()) { choix.getInt(it) }
    val arrAccords = racine.getJSONArray("tunings")
    val accords = List(arrAccords.length()) { ia ->
        val a = arrAccords.getJSONObject(ia)
        val arrNotes = a.getJSONArray("notes")
        val notes = List(arrNotes.length()) { inote ->
            val n = arrNotes.getJSONObject(inote)
            NoteAccord(
                key = n.getInt("key"),
                name = n.getString("name"),
                fundamentalHz440 = n.getDouble("fundamentalHz440"),
                partialTargetHz440 = n.getDouble("partialTargetHz440"),
                listenPartial = n.getInt("listenPartial")
            )
        }
        Accord(
            id = a.getInt("id"),
            name = a.getString("name"),
            serial = a.getString("serial"),
            notes = notes
        )
    }
    return DonneesAccords(
        referencePitch = racine.getInt("referencePitch"),
        pitchChoicesHz = pitchChoices,
        tunings = accords
    )
}

/** État publié par le moteur ~12×/s (message {type:'state'} du worklet). */
data class EtatTuner(
    val frameConf: Double,
    val levelDb: Double,
    val snrDb: Double,
    val ageSec: Double,
    val latest: MesurePartiel?
)

/**
 * Orchestration du tuner — port fidèle du processeur AudioWorklet
 * (worklet-body.js) : le traqueur reçoit tout le flux ; le détecteur
 * ne roule qu'en mode auto/demi-ton ; une note adoptée verrouille le
 * traqueur sur son partiel de référence ; l'état est publié à chaque
 * tranche de 4096 échantillons. C'est LE chemin prouvé du prototype.
 *
 * Écart assumé (0.12) — TRAITEMENT DU TRANSITOIRE D'ATTAQUE, propre
 * à cet écran (le parcours de mesure ne passe pas par ce moteur) :
 * à la frappe, la hauteur d'une corde part trop dièse puis redescend
 * vers sa valeur stable à mesure que l'amplitude décroît (tension
 * effective accrue à forte amplitude) ; PianoMeter semble ignorer
 * ce pic et donner le pitch réel ~250 ms plus tard (hypothèse de
 * Grégoire, 2026-11-18). Courbe MESURÉE sur son échantillon (Fa5,
 * Steinway D du Palais, hauteur établie 698,2 Hz) : +3,0 ¢ à
 * 100 ms, plateau ≈ +2,7 ¢ jusqu'à ~400 ms, puis +1,8 ¢ à 500 ms,
 * +1,4 ¢ à 700 ms, +1,0 ¢ à 900 ms, établi vers 1,2 s — un pic de
 * ~3 ¢, mais une queue d'environ une seconde (une seule note, un
 * seul piano, sa force de frappe de travail ; des cordes graves
 * frappées fort glissent davantage). Traitement calibré dessus :
 * quand une frappe est détectée (montée de niveau franche, voir
 * detecterFrappe), le poids du suivi du traqueur tombe à 0
 * pendant FENETRE_ATTAQUE_MS (dépondération forte : aucune
 * estimation du plateau n'entre dans l'historique), puis remonte
 * en rampe linéaire jusqu'au plein poids à
 * ATTAQUE_PLEIN_POIDS_MS — la queue du transitoire n'entre qu'avec
 * un poids croissant et par une porte de continuité resserrée à
 * ±1,5 ¢, pendant que l'historique garde la hauteur établie. La
 * sortie publiée reste donc la hauteur établie et
 * l'affichage vit normalement (rien n'« attend » la frappe :
 * c'est la définition de la lecture qui ignore le pic). Cas de la
 * première frappe d'une note, où aucune hauteur établie n'existe
 * encore : la dépondération tient jusqu'au plein poids, la
 * première lecture naît établie. Après la rampe, le suivi
 * normal — porte de continuité comprise — a le relais. Un
 * glissement de cheville sans frappe ne déclenche rien : le
 * niveau n'y monte jamais franchement.
 */
class TunerEngine(val sampleRate: Int) {
    val tracker = PartialTracker(sampleRate)
    val detector = NoteDetector(sampleRate)

    companion object {
        /** Fenêtre de dépondération forte après une frappe (ms) :
         * poids du suivi à 0 — couvre le plateau du transitoire
         * mesuré (~+3 ¢ jusqu'à ~400 ms). */
        const val FENETRE_ATTAQUE_MS = 300

        /** Retour au plein poids du suivi (ms après la frappe) :
         * entre FENETRE_ATTAQUE_MS et ce point, le poids remonte
         * en rampe linéaire pendant que la queue du transitoire
         * décroît (≈ +1,8 ¢ à 500 ms, ≈ +1,0 ¢ à 900 ms, établie
         * vers 1,2 s sur la courbe mesurée — le plein poids
         * coïncide avec la fin mesurée du transitoire). */
        const val ATTAQUE_PLEIN_POIDS_MS = 1200

        /** Montée de niveau (dB) au-dessus du minimum récent qui
         * signe une frappe. */
        const val ATTAQUE_MONTEE_MIN_DB = 10.0

        /** Niveau absolu minimal (dB) d'un bloc pour qu'une montée
         * compte comme une frappe (en deçà : bruit de fond). */
        const val ATTAQUE_NIVEAU_MIN_DB = -55.0

        /** Porte de continuité resserrée (¢) pendant la rampe du
         * transitoire : la queue encore dièse reste dehors, un
         * glissement de cheville proche de la hauteur établie
         * passe. (Régime normal du traqueur : ±3 ¢.) */
        const val ATTAQUE_PORTE_RESSERREE_CENTS = 1.5

        /** Nombre de blocs (~85 ms chacun) du minimum récent. */
        const val ATTAQUE_BLOCS_MEMOIRE = 12
    }

    var mode: String = "bloque" // mode initial du worklet
        private set
    var curKey: Int = 49
        private set
    private var notes: List<NoteBrute>? = null
    private var tableId: String? = null
    var activeKey: Int? = null
        private set

    @Volatile
    var dernierEtat: EtatTuner? = null
        private set

    /** Adoption la plus récente (message {type:'note'} du worklet). */
    @Volatile
    var derniereNoteAdoptee: Int? = null
        private set

    /** Compteur d'adoptions — l'interface le sonde pour réagir. */
    @Volatile
    var seqAdoptions: Int = 0
        private set

    private var depuisEtat = 0

    // --- Traitement du transitoire d'attaque (voir l'en-tête) ---
    /** Échantillons poussés depuis l'ouverture du flux. */
    private var totalEch: Long = 0

    /** Somme des carrés et compteur du bloc en cours (entre deux
     * publications) : le niveau par bloc voit la frappe dès le
     * premier bloc, contrairement au niveau du tampon FFT du
     * traqueur (fenêtre de 0,68 s, trop lente à monter). */
    private var sommeCarresBloc: Double = 0.0
    private var nbBloc: Int = 0
    private val niveauxRecents = mutableListOf<Double>()

    /** Position (en échantillons) de la dernière frappe détectée. */
    private var frappeEch: Long = -1

    /** Nombre de frappes détectées (diagnostic et tests). */
    @Volatile
    var frappesDetectees: Int = 0
        private set

    /**
     * Configuration complète (équivalent du message {type:'config'}).
     * [notes] : table des 88 notes, fréquences déjà mises à l'échelle
     * du diapason par l'appelant.
     */
    fun config(mode: String, key: Int, tableId: String, notes: List<NoteBrute>?) {
        this.mode = mode
        this.curKey = key
        if (tableId != this.tableId && notes != null) {
            this.tableId = tableId
            this.notes = notes
            detector.setNotes(notes)
        }
        if (this.mode == "bloque") activate(key, false)
    }

    private fun activate(key: Int, notify: Boolean) {
        val table = notes ?: return
        val note = table.getOrNull(key - 1) ?: return
        activeKey = key
        tracker.setTarget(note.partialTargetHz)
        if (notify) {
            derniereNoteAdoptee = key
            seqAdoptions++
        }
    }

    /** Pousse un bloc d'échantillons du flux micro (process() du worklet). */
    fun push(samples: FloatArray) {
        tracker.push(samples)
        val table = notes
        if (mode != "bloque" && table != null) {
            detector.push(samples)
            var cand = detector.adoptedKey
            if (cand != null && mode == "demi" && abs(cand - curKey) > 1) cand = null
            if (cand != null && cand != activeKey) {
                curKey = cand
                activate(cand, true)
            }
        }
        // Comptabilité du bloc (niveau + transitoire d'attaque).
        totalEch += samples.size
        for (s in samples) sommeCarresBloc += s.toDouble() * s
        nbBloc += samples.size
        val poids = poidsAttaqueCourant()
        tracker.poidsSuivi = poids
        tracker.porteContinuiteCents =
            if (poids < 1.0) ATTAQUE_PORTE_RESSERREE_CENTS else 3.0
        depuisEtat += samples.size
        if (depuisEtat >= 4096) {
            depuisEtat = 0
            val niveauBlocDb = if (nbBloc > 0)
                10 * log10(sommeCarresBloc / nbBloc + 1e-12) else -120.0
            sommeCarresBloc = 0.0
            nbBloc = 0
            detecterFrappe(niveauBlocDb)
            val t = tracker
            dernierEtat = EtatTuner(
                frameConf = t.frameConf,
                levelDb = t.frameLevelDb,
                snrDb = t.frameSnrDb,
                ageSec = t.latestAgeSec(),
                latest = t.latest
            )
        }
    }

    /**
     * Poids du suivi selon le temps écoulé depuis la dernière
     * frappe détectée : 0 pendant FENETRE_ATTAQUE_MS, rampe
     * linéaire jusqu'à 1 à ATTAQUE_PLEIN_POIDS_MS, 1 ensuite
     * (et quand aucune frappe n'est en cours).
     */
    private fun poidsAttaqueCourant(): Double {
        if (frappeEch < 0) return 1.0
        val dtMs = (totalEch - frappeEch) * 1000 / sampleRate
        return when {
            dtMs < FENETRE_ATTAQUE_MS -> 0.0
            dtMs >= ATTAQUE_PLEIN_POIDS_MS -> 1.0
            // Pas encore de hauteur établie sur cette cible
            // (première frappe d'une note) : la dépondération
            // tient jusqu'au plein poids — la première lecture
            // naît établie au lieu de naître dans le transitoire
            // étalé par la fenêtre d'analyse. Quand une hauteur
            // établie existe déjà (le cas de travail : refrappe
            // pour vérifier), elle reste affichée pendant la
            // rampe, c'est elle que la rampe protège.
            tracker.latest == null -> 0.0
            else -> (dtMs - FENETRE_ATTAQUE_MS).toDouble() /
                (ATTAQUE_PLEIN_POIDS_MS - FENETRE_ATTAQUE_MS)
        }
    }

    /**
     * Frappe = montée d'au moins ATTAQUE_MONTEE_MIN_DB au-dessus du
     * minimum des ATTAQUE_BLOCS_MEMOIRE derniers blocs, à un niveau
     * d'au moins ATTAQUE_NIVEAU_MIN_DB. Détectée, elle fait partir
     * la dépondération du suivi (voir l'en-tête de la classe) et
     * réinitialise la mémoire des niveaux : le son qui suit
     * devient la référence, une note tenue ne réarme pas le
     * traitement. Une nouvelle montée franche pendant la rampe
     * (refrappe) le réarme.
     */
    private fun detecterFrappe(niveauBlocDb: Double) {
        if (niveauBlocDb >= ATTAQUE_NIVEAU_MIN_DB) {
            val minRecent = niveauxRecents.minOrNull()
            if (minRecent == null || niveauBlocDb >= minRecent + ATTAQUE_MONTEE_MIN_DB) {
                frappesDetectees++
                frappeEch = totalEch
                niveauxRecents.clear()
            }
        }
        niveauxRecents.add(niveauBlocDb)
        if (niveauxRecents.size > ATTAQUE_BLOCS_MEMOIRE) niveauxRecents.removeAt(0)
    }
}
