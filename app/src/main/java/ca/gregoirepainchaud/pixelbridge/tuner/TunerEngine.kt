package ca.gregoirepainchaud.pixelbridge.tuner

import org.json.JSONObject
import kotlin.math.abs

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

/** État publié par le moteur à chaque tranche de 1024 échantillons
 * poussés (message {type:'state'} du worklet ; 4096 avant la 0.10). */
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
 * tranche de 1024 échantillons. C'est LE chemin prouvé du prototype.
 *
 * Écarts de cadence assumés (0.10, réactivité) : pas d'analyse du
 * traqueur à 1024 (voir PartialTracker), publication à 1024, et
 * détecteur à adoption en deux analyses concordantes au lieu de
 * trois — sur l'appareil, une analyse du détecteur vaut ~100 ms
 * (un bloc de capture), l'adoption en Auto passe ainsi de ~300 ms
 * à ~200 ms après remplissage, critères par analyse inchangés.
 */
class TunerEngine(val sampleRate: Int) {
    val tracker = PartialTracker(sampleRate)
    val detector = NoteDetector(sampleRate, seuilAdoption = 2)

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
        depuisEtat += samples.size
        if (depuisEtat >= 1024) {
            depuisEtat = 0
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
}
