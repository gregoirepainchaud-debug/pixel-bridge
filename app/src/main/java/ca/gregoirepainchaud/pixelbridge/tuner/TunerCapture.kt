package ca.gregoirepainchaud.pixelbridge.tuner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Capture micro native du tuner — AudioRecord, même approche que
 * l'oreille (OreilleService, prouvée sur ce téléphone) : source MIC
 * sans traitement, PCM 16 bits mono, tampon maxOf(minimum système,
 * 2× le taux). Le taux est celui du moteur DSP : 48 000 Hz (le taux
 * des cas synthétiques et du prototype web sur ce téléphone).
 * Aucun échec muet : chaque problème est retourné ou publié avec sa
 * cause exacte.
 */
class TunerCapture(
    private val contexte: Context,
    private val surEchantillons: (FloatArray) -> Unit,
    private val surErreur: (String) -> Unit
) {
    companion object {
        const val TAUX = 48_000
    }

    @Volatile
    var enCours: Boolean = false
        private set

    /** Nombre total d'échantillons reçus depuis le démarrage. */
    @Volatile
    var echantillonsTotal: Long = 0
        private set

    /** Dernière erreur de capture, verbatim (null si aucune). */
    @Volatile
    var derniereErreur: String? = null
        private set

    private var enregistreur: AudioRecord? = null
    private var filCapture: Thread? = null

    fun permissionAccordee(): Boolean =
        contexte.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Démarre la capture. Retourne null si elle roule, sinon le
     * message d'erreur exact à afficher.
     */
    fun demarrer(): String? {
        if (enCours) return null
        derniereErreur = null
        if (!permissionAccordee()) {
            return "Permission micro refusée : accorde l'accès au " +
                "microphone à l'app Gestion (Réglages Android → " +
                "Applications → Gestion → Autorisations → Microphone)."
        }
        val minTampon = AudioRecord.getMinBufferSize(
            TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minTampon <= 0) {
            return "ERREUR : AudioRecord refuse le format 48000 Hz " +
                "PCM 16 bits mono (getMinBufferSize = $minTampon)."
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, TAUX,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minTampon, TAUX * 2)
            )
        } catch (e: Exception) {
            return "ERREUR : AudioRecord impossible à créer : ${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "ERREUR : le micro ne s'initialise pas " +
                "(AudioRecord non initialisé, 48000 Hz PCM 16 bits mono)."
        }
        enregistreur = rec
        echantillonsTotal = 0
        enCours = true
        try {
            rec.startRecording()
        } catch (e: Exception) {
            enCours = false
            rec.release()
            enregistreur = null
            return "ERREUR : le micro ne démarre pas : ${e.message}"
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            enCours = false
            rec.release()
            enregistreur = null
            return "ERREUR : le micro ne démarre pas " +
                "(état d'enregistrement inactif après startRecording)."
        }
        filCapture = Thread {
            val bloc = ShortArray(TAUX / 10) // blocs de 100 ms
            while (enCours) {
                val lus = try {
                    rec.read(bloc, 0, bloc.size)
                } catch (e: Exception) {
                    -1
                }
                if (lus > 0) {
                    echantillonsTotal += lus
                    val fl = FloatArray(lus) { bloc[it] / 32768f }
                    // Filet (correctif 0.6) : le traitement des
                    // échantillons (moteur, analyseur de partiels)
                    // roule sur CE fil — une exception qui s'en
                    // échapperait tuerait le fil et, par le
                    // gestionnaire par défaut, l'app entière. On
                    // la convertit en erreur de capture franche,
                    // affichée à l'écran comme les autres.
                    try {
                        surEchantillons(fl)
                    } catch (e: Exception) {
                        val msg = "ERREUR : le traitement du son a " +
                            "échoué (${e.javaClass.simpleName} : " +
                            "${e.message}). La capture s'arrête ; " +
                            "l'écran peut être rouvert."
                        derniereErreur = msg
                        enCours = false
                        surErreur(msg)
                        break
                    }
                } else if (lus < 0) {
                    val msg = "ERREUR : lecture du micro interrompue " +
                        "(code AudioRecord $lus)."
                    derniereErreur = msg
                    enCours = false
                    surErreur(msg)
                    break
                }
            }
        }.apply { start() }
        return null
    }

    /** Arrête la capture et libère le micro. Idempotent. */
    fun arreter() {
        enCours = false
        val rec = enregistreur
        enregistreur = null
        if (rec != null) {
            try {
                rec.stop()
            } catch (e: Exception) {
                // Déjà arrêté : rien à signaler.
            }
            rec.release()
        }
        filCapture?.join(400)
        filCapture = null
    }
}
