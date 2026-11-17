package ca.gregoirepainchaud.pixelbridge.oreille

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import ca.gregoirepainchaud.pixelbridge.R
import ca.gregoirepainchaud.pixelbridge.bench.WhisperEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Service d'écoute robuste (design de Grégoire, 2026-11-17 au
 * soir) : la séance d'écoute vit ICI, dans un service d'avant-plan
 * de type micro — plus dans l'écran. L'écran peut être fermé,
 * l'app quittée, l'écran éteint : la capture continue, tenue par
 * la notification permanente (qui porte son propre bouton
 * « Arrêter et envoyer ») et par un verrou de réveil partiel.
 * Avant ce service, la séance vivait dans OreilleActivity et des
 * écoutes terrain se sont retrouvées tronquées (2,9 s, 27,4 s)
 * quand l'écran a été fermé ou détruit en cours de route.
 *
 * Démarrage et arrêt, trois portes équivalentes :
 *  - la tuile « Oreille » des réglages rapides (swipe down ×2 sur
 *    Pixel, un tap) — bascule directe, sans ouvrir l'écran ;
 *  - le bouton de l'écran Oreille ;
 *  - le bouton d'arrêt de la notification.
 * À l'arrêt, tout s'enchaîne tout seul, comme avant : vidange du
 * flux, correction des épisodes par le modèle avancé, clips WAV,
 * envoi au pont (cas « appel-ecoute », format inchangé, plus le
 * champ « arret » qui dit quelle porte a fermé la séance — pour
 * diagnostiquer toute future coupure). Muse analyse ensuite
 * côté serveur : résumé de discussion ou extraction d'infos
 * d'appel, avec relecture par le modèle lourd (back door) sur
 * les points flous signalés dans les épisodes.
 *
 * Mode réunion (2026-11-17, demande de Grégoire) : si la
 * sentinelle n'a ouvert AUCUN épisode — une dictée de compte
 * rendu, une réunion, aucun mot d'affaires — la séance bascule
 * automatiquement en mode réunion, annoncé dans la ligne
 * d'état : l'enregistrement AU COMPLET est retranscrit par le
 * modèle de correction (fenêtres de 30 s chaînées, sans le
 * plafond de 90 s des épisodes) et envoyé au pont en cas
 * « reunion », avec les heures réelles de début et de fin ;
 * l'audio part aussi, par fenêtres (fen-<i>.wav), pour qu'une
 * relecture back door reste possible sur un passage douteux.
 * Aucun bouton de plus : la bascule est automatique et dite.
 *
 * État visible, aucun état invisible : le service publie son état
 * (phase, statut, durée, épisodes, transcript) dans le fichier
 * filesDir/oreille-etat.json, que l'écran Oreille lit en boucle ;
 * la préférence « ecoute_en_cours » continue d'alimenter la tuile.
 */
class OreilleService : Service() {

    private data class Segment(val debutS: Double, val finS: Double, val texte: String)

    private class Episode(
        val debutS: Double,
        var finS: Double,
        val categories: MutableSet<String>,
        val texteBase: StringBuilder,
        var texteCorrige: String = ""
    ) {
        val incertitudes = ArrayList<JSONObject>()
    }

    private val moteur = WhisperEngine()
    private val prefs by lazy { getSharedPreferences("bench", MODE_PRIVATE) }

    // État de la séance.
    @Volatile private var enEcoute = false
    @Volatile private var traitementEnCours = false
    private val verrouAudio = Object()
    private val tamponAudio = ArrayList<Float>(16_000 * 60)
    private val verrouSegments = Object()
    private val segments = ArrayList<Segment>()
    private val episodes = ArrayList<Episode>()
    private var coupeA = 0
    private var calculTotalMs = 0L
    private var enregistreur: AudioRecord? = null
    private var filCapture: Thread? = null
    private var filInference: Thread? = null
    @Volatile private var fluxCharge = false
    private var verrouReveil: PowerManager.WakeLock? = null
    private var sourceArret = "inconnu"
    // Heures réelles de la séance (début de la capture, demande
    // d'arrêt) : le payload « reunion » les porte en ISO pour que
    // Muse date la réunion et ses heures exactes.
    @Volatile private var debutSeanceMs = 0L
    @Volatile private var finSeanceMs = 0L

    // État publié (fichier lu par l'écran + texte de notification).
    @Volatile private var phase = "idle"
    @Volatile private var statut = "Prêt."
    private val horloge = Handler(Looper.getMainLooper())
    private val ticHorloge = object : Runnable {
        override fun run() {
            if (!enEcoute) return
            publierEtat()
            mettreAJourNotification()
            horloge.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARRETER -> {
                sourceArret = intent.getStringExtra(EXTRA_SOURCE) ?: "inconnu"
                if (enEcoute) {
                    arreterEcoute()
                } else if (!traitementEnCours) {
                    stopSelf()
                }
            }
            else -> {
                if (!enEcoute && !traitementEnCours) demarrerSeance()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Le système tue le service (ou l'app est fermée de force) :
        // on libère proprement et on dit la vérité dans l'état.
        enEcoute = false
        horloge.removeCallbacks(ticHorloge)
        try {
            enregistreur?.stop()
        } catch (e: Exception) {
            // Déjà arrêté.
        }
        enregistreur?.release()
        enregistreur = null
        libererVerrouReveil()
        moteur.free()
        prefs.edit().putBoolean("ecoute_en_cours", false).apply()
        if (phase == "ecoute" || phase == "traitement") {
            phase = "erreur"
            statut = "Écoute interrompue : le service a été fermé " +
                "avant l'envoi. Rien n'a été transmis."
            ecrireFichierEtat()
        }
        super.onDestroy()
    }

    // ----------------------------------------------------------------
    // Démarrage : notification d'avant-plan immédiate, puis
    // vérifications (micro, modèle), chargement du modèle de flux
    // et capture.
    // ----------------------------------------------------------------

    private fun demarrerSeance() {
        creerCanal()
        // Le contrat startForegroundService : la notification doit
        // être affichée tout de suite, avant tout chargement.
        try {
            startForeground(
                NOTIF_ECOUTE_ID, construireNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            // Type micro refusé (permission révoquée entre-temps) :
            // on n'insiste pas, l'écran prendra le relais.
            phase = "erreur"
            statut = "Service micro refusé par le système : ${e.message}. " +
                "Ouvre l'écran Oreille pour écouter."
            ecrireFichierEtat()
            stopSelf()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifierResultat(
                "Micro à autoriser",
                "Touche pour ouvrir l'écran Oreille et autoriser le micro."
            )
            phase = "erreur"
            statut = "Micro non autorisé : passe par l'écran Oreille."
            ecrireFichierEtat()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val nom = nomModeleFlux()
        val fichier = trouverModele(nom)
        if (fichier == null) {
            phase = "erreur"
            statut = "Modèle de flux « $nom » introuvable " +
                "(Téléchargements, dossiers de l'app)."
            ecrireFichierEtat()
            notifierResultat("Oreille : modèle introuvable", statut)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        phase = "chargement"
        statut = "Chargement du modèle de flux…"
        ecrireFichierEtat()
        mettreAJourNotification()
        Thread {
            try {
                moteur.load(fichier.absolutePath, filsChoisis())
                fluxCharge = true
            } catch (e: Throwable) {
                phase = "erreur"
                statut = "Chargement du modèle impossible : ${e.message}"
                ecrireFichierEtat()
                notifierResultat("Oreille : chargement impossible", statut)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@Thread
            }
            demarrerCapture()
        }.start()
    }

    private fun demarrerCapture() {
        synchronized(verrouAudio) { tamponAudio.clear() }
        synchronized(verrouSegments) {
            segments.clear()
            episodes.clear()
        }
        coupeA = 0
        calculTotalMs = 0L
        val minTampon = AudioRecord.getMinBufferSize(
            TAUX, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, TAUX,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minTampon, TAUX * 2)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            phase = "erreur"
            statut = "ERREUR : le micro ne s'initialise pas."
            ecrireFichierEtat()
            notifierResultat("Oreille : micro en erreur", statut)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        enregistreur = rec
        enEcoute = true
        debutSeanceMs = System.currentTimeMillis()
        phase = "ecoute"
        statut = "À l'écoute…"
        noterEtatEcoute()
        ecrireFichierEtat()
        prendreVerrouReveil()
        rec.startRecording()
        horloge.post(ticHorloge)

        filCapture = Thread {
            val bloc = ShortArray(TAUX / 2)
            while (enEcoute) {
                val lus = rec.read(bloc, 0, bloc.size)
                if (lus > 0) {
                    synchronized(verrouAudio) {
                        for (i in 0 until lus) {
                            tamponAudio.add(bloc[i] / 32768f)
                        }
                        val max = TAUX * 60 * 15
                        if (tamponAudio.size > max) {
                            tamponAudio.subList(0, tamponAudio.size - max)
                                .clear()
                        }
                    }
                }
            }
        }.apply { start() }

        filInference = Thread {
            var invite = ""
            while (enEcoute) {
                Thread.sleep(400)
                val total = synchronized(verrouAudio) { tamponAudio.size }
                val dispo = total - coupeA
                if (dispo < TAUX * 8) continue
                val prendre = minOf(dispo, TAUX * 30)
                val debutEch = coupeA
                val tranche = synchronized(verrouAudio) {
                    FloatArray(prendre) { tamponAudio[debutEch + it] }
                }
                val t0 = System.currentTimeMillis()
                val texte = try {
                    moteur.transcribe(tranche, invite)
                } catch (e: Throwable) {
                    statut = "ERREUR transcription : ${e.message}"
                    ecrireFichierEtat()
                    coupeA += prendre
                    continue
                }
                val dt = System.currentTimeMillis() - t0
                coupeA += prendre
                if (texte.isNotBlank()) {
                    invite = texte.takeLast(200)
                    enregistrerSegment(
                        debutEch.toDouble() / TAUX,
                        (debutEch + prendre).toDouble() / TAUX,
                        texte.trim(), dt
                    )
                } else {
                    synchronized(verrouSegments) { calculTotalMs += dt }
                }
                publierEtat()
            }
        }.apply { start() }
    }

    /** Ajoute un segment transcrit, fait passer la sentinelle et
     *  ouvre ou prolonge l'épisode courant. */
    private fun enregistrerSegment(
        debutS: Double, finS: Double, texte: String, calculMs: Long
    ) {
        synchronized(verrouSegments) {
            segments.add(Segment(debutS, finS, texte))
            calculTotalMs += calculMs
            val categories = sentinelle(texte)
            if (categories.isEmpty()) return
            val dernier = episodes.lastOrNull()
            if (dernier != null && debutS - dernier.finS <= 12.0) {
                dernier.finS = finS
                dernier.categories.addAll(categories)
                dernier.texteBase.append(' ').append(texte)
            } else {
                episodes.add(
                    Episode(debutS, finS, categories, StringBuilder(texte))
                )
            }
        }
    }

    // ----------------------------------------------------------------
    // Arrêt = envoyer : vidange du flux, correction des épisodes
    // par le modèle avancé, clips audio, envoi au pont — tout
    // s'enchaîne tout seul, l'état publié suit les phases.
    // ----------------------------------------------------------------

    private fun arreterEcoute() {
        finSeanceMs = System.currentTimeMillis()
        enEcoute = false
        noterEtatEcoute()
        horloge.removeCallbacks(ticHorloge)
        traitementEnCours = true
        phase = "traitement"
        statut = "Raccroché — traitement en cours…"
        ecrireFichierEtat()
        mettreAJourNotification()
        try {
            enregistreur?.stop()
        } catch (e: Exception) {
            // Déjà arrêté.
        }
        enregistreur?.release()
        enregistreur = null
        filCapture?.join(1000)
        filInference?.join(1500)
        libererVerrouReveil()

        Thread {
            // Vidange : le reste d'audio non transcrit devient un
            // dernier segment (le modèle de flux est encore chargé).
            val total = synchronized(verrouAudio) { tamponAudio.size }
            val reste = total - coupeA
            if (reste >= TAUX) {
                val debutEch = coupeA
                val tranche = synchronized(verrouAudio) {
                    FloatArray(reste) { tamponAudio[debutEch + it] }
                }
                val t0 = System.currentTimeMillis()
                val texte = try {
                    moteur.transcribe(tranche, "")
                } catch (e: Throwable) {
                    ""
                }
                val dt = System.currentTimeMillis() - t0
                coupeA += reste
                if (texte.isNotBlank()) {
                    enregistrerSegment(
                        debutEch.toDouble() / TAUX, total.toDouble() / TAUX,
                        texte.trim(), dt
                    )
                }
            }

            val copieSegments: List<Segment>
            val copieEpisodes: List<Episode>
            val toutAudio: FloatArray
            val calcul: Long
            synchronized(verrouSegments) {
                copieSegments = ArrayList(segments)
                copieEpisodes = ArrayList(episodes)
                calcul = calculTotalMs
            }
            toutAudio = synchronized(verrouAudio) { tamponAudio.toFloatArray() }
            val dureeS = toutAudio.size.toDouble() / TAUX
            val appelId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CANADA_FRENCH)
                .format(Date())

            // Aucun épisode : ce n'était pas un appel d'affaires
            // mais une réunion ou une dictée de compte rendu —
            // bascule automatique en mode réunion.
            if (copieEpisodes.isEmpty()) {
                traiterReunion(copieSegments, toutAudio, dureeS, appelId)
                return@Thread
            }
            statut = "Correction des épisodes…"
            ecrireFichierEtat()
            mettreAJourNotification()

            // Première passe de correction : le modèle avancé
            // retranscrit les seuls épisodes, depuis l'audio gardé.
            val nomCorrection = nomModeleCorrection()
            val fichierCorrection = trouverModele(nomCorrection)
            var noteCorrection = ""
            if (fichierCorrection != null && copieEpisodes.isNotEmpty()) {
                try {
                    moteur.load(fichierCorrection.absolutePath, filsChoisis())
                    fluxCharge = false
                    for (ep in copieEpisodes) {
                        ep.texteCorrige = retranscrirePlage(
                            toutAudio, ep.debutS, ep.finS, ep.incertitudes
                        )
                    }
                } catch (e: Throwable) {
                    noteCorrection = "Correction impossible : ${e.message}"
                }
            } else if (copieEpisodes.isNotEmpty()) {
                noteCorrection =
                    "Modèle de correction « $nomCorrection » introuvable : " +
                    "épisodes envoyés sans première passe."
            }

            // Clips audio des épisodes : écrits en file locale puis
            // poussés au serveur (repris tout seuls au besoin).
            val dirAttente = File(filesDir, "appels-attente/$appelId")
                .apply { mkdirs() }
            for ((i, ep) in copieEpisodes.withIndex()) {
                val wav = construireWav(toutAudio, ep.debutS, ep.finS)
                File(dirAttente, "ep-$i.wav").writeBytes(wav)
            }
            // Couverture audio complète (méthode corrigée du
            // 2026-11-17, demande de Grégoire) : l'enregistrement
            // AU COMPLET part aussi au pont, en fenêtres de 30 s
            // (fen-<i>.wav, même file que les clips) — aucun bout
            // absent du serveur, et la back door peut relire
            // n'importe quel moment de l'appel, pas seulement les
            // épisodes (les présentations du début, par exemple).
            // Supplément jamais bloqueur : si le découpage échoue,
            // le payload part quand même, sans les fenêtres.
            val fenetres = ArrayList<String>()
            try {
                var debutFenetre = 0.0
                var iFenetre = 0
                while (debutFenetre < dureeS) {
                    val finFenetre = minOf(debutFenetre + 30.0, dureeS)
                    val nomFenetre = "fen-$iFenetre.wav"
                    File(dirAttente, nomFenetre).writeBytes(
                        construireWav(
                            toutAudio, debutFenetre, finFenetre
                        )
                    )
                    fenetres.add(nomFenetre)
                    debutFenetre = finFenetre
                    iFenetre++
                }
            } catch (e: Exception) {
                // Fenêtres non poussées : le payload part quand même.
            }
            viderFileAudio()

            statut = "Envoi au pont…"
            ecrireFichierEtat()
            mettreAJourNotification()
            val transcript = copieSegments.joinToString(" ") { it.texte }
            val donnees = JSONObject()
                .put("appel", appelId)
                .put("duree_s", dureeS)
                .put("modele_flux", nomModeleFlux())
                .put("modele_correction", nomCorrection)
                .put("note_correction", noteCorrection)
                .put("arret", sourceArret)
                .put("debut_iso", formatIso(debutSeanceMs))
                .put("fin_iso", formatIso(finSeanceMs))
                .put("fenetres", JSONArray(fenetres))
                .put("transcript", transcript)
                .put("segments", JSONArray().apply {
                    for (s in copieSegments) {
                        put(JSONObject()
                            .put("debut_s", s.debutS)
                            .put("fin_s", s.finS)
                            .put("texte", s.texte))
                    }
                })
                .put("episodes", JSONArray().apply {
                    for ((i, ep) in copieEpisodes.withIndex()) {
                        put(JSONObject()
                            .put("index", i)
                            .put("categories", JSONArray(ep.categories.toList()))
                            .put("debut_s", ep.debutS)
                            .put("fin_s", ep.finS)
                            .put("texte_base", ep.texteBase.toString())
                            .put("texte_corrige", ep.texteCorrige)
                            .put(
                                "incertitudes",
                                JSONArray(ep.incertitudes.take(40))
                            )
                            .put("avis", "aucun")
                            .put("audio", "ep-$i.wav"))
                    }
                })
            val facteur = if (calcul > 0) dureeS / (calcul / 1000.0) else 0.0
            val resume = "APPEL $appelId DUREE=%.1f s EPISODES=%d FACTEUR_FLUX=%.1f\n%s".format(
                dureeS, copieEpisodes.size, facteur, transcript
            )
            pousserAuPont(resume, donnees) { envoyes, resteFile ->
                val heure = SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH)
                    .format(Date())
                traitementEnCours = false
                phase = "termine"
                statut = when {
                    resteFile > 0 ->
                        ("En file ($resteFile) — partira au retour du " +
                            "réseau. ${copieEpisodes.size} épisode(s), " +
                            "%.0f s.").format(dureeS)
                    envoyes > 0 ->
                        ("Envoyé ✓ à $heure — ${copieEpisodes.size} " +
                            "épisode(s), %.0f s. La seconde passe se " +
                            "fera côté Muse.").format(dureeS)
                    else ->
                        ("En file — adresse du pont absente (onglet " +
                            "Réglages). ${copieEpisodes.size} épisode(s), " +
                            "%.0f s.").format(dureeS)
                }
                ecrireFichierEtat()
                notifierResultat("Oreille — écoute terminée", statut)
                moteur.free()
                fluxCharge = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
    }

    /**
     * Mode réunion (2026-11-17) : aucun épisode détecté par la
     * sentinelle — l'enregistrement au complet est relu par le
     * modèle de correction (fenêtres de 30 s chaînées, sans le
     * plafond de 90 s des épisodes, progression publiée dans la
     * ligne d'état), l'audio part au pont par fenêtres
     * (fen-<i>.wav, même route que les clips d'épisodes) et le
     * payload part en cas « reunion » avec les heures réelles de
     * la séance. L'audio est un supplément, jamais un bloqueur :
     * s'il échoue, le payload part quand même. Appelé depuis le
     * fil de traitement de l'arrêt ; termine la séance comme le
     * chemin des appels (résultat notifié, service arrêté).
     */
    private fun traiterReunion(
        segmentsFlux: List<Segment>, toutAudio: FloatArray,
        dureeS: Double, reunionId: String
    ) {
        statut = "Aucun épisode — mode réunion : relecture complète…"
        ecrireFichierEtat()
        mettreAJourNotification()
        val nomCorrection = nomModeleCorrection()
        val fichierCorrection = trouverModele(nomCorrection)
        var noteCorrection = ""
        var segmentsCorriges: List<Segment> = emptyList()
        if (fichierCorrection != null) {
            try {
                moteur.load(fichierCorrection.absolutePath, filsChoisis())
                fluxCharge = false
                val incertitudes = ArrayList<JSONObject>()
                segmentsCorriges = retranscrireFenetres(
                    toutAudio, 0.0, dureeS, incertitudes
                ) { faitS ->
                    statut = "Aucun épisode — mode réunion : " +
                        "relecture complète… " +
                        "${formatTemps(faitS)} / ${formatTemps(dureeS)}"
                    ecrireFichierEtat()
                    mettreAJourNotification()
                }
            } catch (e: Throwable) {
                noteCorrection =
                    "Relecture complète impossible : ${e.message}"
            }
        } else {
            noteCorrection =
                "Modèle de correction « $nomCorrection » introuvable : " +
                "réunion envoyée avec la seule lecture du flux."
        }
        val transcriptBase = segmentsFlux.joinToString(" ") { it.texte }
        val transcriptCorrige =
            segmentsCorriges.joinToString(" ") { it.texte }

        // Audio par fenêtres de 30 s, rangé sous l'id de la
        // réunion dans la même file que les clips d'appels : la
        // vidange existante le pousse vers /audio/<reunion>/.
        try {
            val dirAttente = File(filesDir, "appels-attente/$reunionId")
                .apply { mkdirs() }
            var debutFenetre = 0.0
            var i = 0
            while (debutFenetre < dureeS) {
                val finFenetre = minOf(debutFenetre + 30.0, dureeS)
                File(dirAttente, "fen-$i.wav").writeBytes(
                    construireWav(toutAudio, debutFenetre, finFenetre)
                )
                debutFenetre = finFenetre
                i++
            }
            viderFileAudio()
        } catch (e: Exception) {
            // Audio non poussé : le payload part quand même.
        }

        statut = "Envoi au pont…"
        ecrireFichierEtat()
        mettreAJourNotification()
        val debutIso = formatIso(debutSeanceMs)
        val finIso = formatIso(finSeanceMs)
        val donnees = JSONObject()
            .put("reunion", reunionId)
            .put("debut_iso", debutIso)
            .put("fin_iso", finIso)
            .put("duree_s", dureeS)
            .put("modele_flux", nomModeleFlux())
            .put("modele_correction", nomCorrection)
            .put("note_correction", noteCorrection)
            .put("arret", sourceArret)
            .put("transcript_base", transcriptBase)
            .put("transcript_corrige", transcriptCorrige)
            .put("segments_corriges", JSONArray().apply {
                for (s in segmentsCorriges) {
                    put(JSONObject()
                        .put("debut_s", s.debutS)
                        .put("fin_s", s.finS)
                        .put("texte", s.texte))
                }
            })
        val resume = "REUNION $reunionId DUREE=%.1f s DEBUT=%s FIN=%s\n%s"
            .format(
                dureeS, debutIso, finIso,
                transcriptCorrige.ifEmpty { transcriptBase }
            )
        pousserAuPont(resume, donnees, cas = "reunion") { envoyes, resteFile ->
            val heure = SimpleDateFormat("HH:mm", Locale.CANADA_FRENCH)
                .format(Date())
            traitementEnCours = false
            phase = "termine"
            statut = when {
                resteFile > 0 ->
                    ("En file ($resteFile) — partira au retour du " +
                        "réseau. Réunion de %.0f s.").format(dureeS)
                envoyes > 0 ->
                    "Réunion envoyée ✓ à $heure — %.0f s.".format(dureeS)
                else ->
                    ("En file — adresse du pont absente (onglet " +
                        "Réglages). Réunion de %.0f s.").format(dureeS)
            }
            ecrireFichierEtat()
            notifierResultat("Oreille — réunion terminée", statut)
            moteur.free()
            fluxCharge = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Retranscrit une plage d'audio (plafond 90 s, tranches de
     *  30 s chaînées par le texte précédent) avec le modèle chargé.
     *  Les passages incertains de la passe sont ajoutés à
     *  [incertitudes] en temps absolus dans l'appel. */
    private fun retranscrirePlage(
        audio: FloatArray, debutS: Double, finS: Double,
        incertitudes: ArrayList<JSONObject>
    ): String {
        val segs = retranscrireFenetres(
            audio, debutS, minOf(finS, debutS + 90.0), incertitudes
        )
        return segs.joinToString(" ") { it.texte }
    }

    /**
     * Retranscrit une plage en fenêtres de 30 s chaînées par
     * invite, SANS plafond de durée, et retourne les segments
     * corrigés (temps absolus dans l'enregistrement). Les
     * passages incertains sont ajoutés à [incertitudes] ;
     * [surProgres] est appelé après chaque fenêtre avec la
     * position atteinte, en secondes — le mode réunion s'en sert
     * pour sa progression visible.
     */
    private fun retranscrireFenetres(
        audio: FloatArray, debutS: Double, finS: Double,
        incertitudes: ArrayList<JSONObject>,
        surProgres: (Double) -> Unit = {}
    ): List<Segment> {
        val debut = (debutS * TAUX).toInt().coerceIn(0, audio.size)
        val fin = (finS * TAUX).toInt().coerceIn(debut, audio.size)
        if (fin <= debut) return emptyList()
        val sortie = ArrayList<Segment>()
        var invite = ""
        var pos = debut
        while (pos < fin) {
            val bout = minOf(fin, pos + TAUX * 30)
            val tranche = audio.copyOfRange(pos, bout)
            val (texte, jetonsJson) = try {
                moteur.transcribeComplet(tranche, invite)
            } catch (e: Throwable) {
                Pair("", "[]")
            }
            extraireIncertitudes(jetonsJson, pos.toDouble() / TAUX, incertitudes)
            if (texte.isNotBlank()) {
                sortie.add(
                    Segment(
                        pos.toDouble() / TAUX, bout.toDouble() / TAUX,
                        texte.trim()
                    )
                )
                invite = texte.takeLast(200)
            }
            pos = bout
            surProgres(bout.toDouble() / TAUX)
        }
        return sortie
    }

    /**
     * Extrait les passages incertains d'une passe : jetons sous le
     * seuil de probabilité, fusionnés en plages (écart ≤ 0,4 s),
     * convertis en temps absolus dans l'appel. Ce sont les
     * drapeaux sur lesquels l'analyse de Muse peut déposer une
     * question de relecture (back door).
     */
    private fun extraireIncertitudes(
        jetonsJson: String, debutTrancheS: Double,
        sortieIncertitudes: ArrayList<JSONObject>
    ) {
        val jetons = try {
            JSONArray(jetonsJson)
        } catch (e: Exception) {
            return
        }
        var spanDebut = -1.0
        var spanFin = -1.0
        var pMin = 1.0
        val spanTexte = StringBuilder()
        fun fermer() {
            if (spanDebut >= 0) {
                sortieIncertitudes.add(
                    JSONObject()
                        .put("debut_s", (spanDebut * 10).roundToInt() / 10.0)
                        .put("fin_s", (spanFin * 10).roundToInt() / 10.0)
                        .put("texte", spanTexte.toString().trim())
                        .put("proba_min", (pMin * 100).roundToInt() / 100.0)
                )
                spanDebut = -1.0
                spanFin = -1.0
                pMin = 1.0
                spanTexte.setLength(0)
            }
        }
        for (j in 0 until jetons.length()) {
            val jt = jetons.optJSONObject(j) ?: continue
            val txt = jt.optString("txt")
            if (txt.isBlank() || txt.startsWith("[") ||
                txt.contains("<|") ||
                !txt.any { it.isLetterOrDigit() }
            ) {
                continue
            }
            val p = jt.optDouble("p", 1.0)
            val t0 = debutTrancheS + jt.optLong("t0") / 100.0
            val t1 = debutTrancheS + jt.optLong("t1") / 100.0
            if (p < SEUIL_INCERTITUDE) {
                if (spanDebut >= 0 && t0 - spanFin > 0.4) fermer()
                if (spanDebut < 0) spanDebut = t0
                spanFin = maxOf(spanFin, t1)
                if (p < pMin) pMin = p
                spanTexte.append(txt)
            } else {
                fermer()
            }
        }
        fermer()
    }

    /** Construit un WAV PCM16 16 kHz mono pour la plage demandée. */
    private fun construireWav(
        audio: FloatArray, debutS: Double, finS: Double
    ): ByteArray {
        val debut = (debutS * TAUX).toInt().coerceIn(0, audio.size)
        val fin = (finS * TAUX).toInt().coerceIn(debut, audio.size)
        val n = fin - debut
        val octetsDonnees = n * 2
        val tampon = ByteBuffer.allocate(44 + octetsDonnees)
            .order(ByteOrder.LITTLE_ENDIAN)
        tampon.put("RIFF".toByteArray(Charsets.US_ASCII))
        tampon.putInt(36 + octetsDonnees)
        tampon.put("WAVE".toByteArray(Charsets.US_ASCII))
        tampon.put("fmt ".toByteArray(Charsets.US_ASCII))
        tampon.putInt(16)
        tampon.putShort(1)
        tampon.putShort(1)
        tampon.putInt(TAUX)
        tampon.putInt(TAUX * 2)
        tampon.putShort(2)
        tampon.putShort(16)
        tampon.put("data".toByteArray(Charsets.US_ASCII))
        tampon.putInt(octetsDonnees)
        for (i in debut until fin) {
            val v = (audio[i] * 32767f).toInt().coerceIn(-32768, 32767)
            tampon.putShort(v.toShort())
        }
        return tampon.array()
    }

    // ----------------------------------------------------------------
    // Sentinelle : motifs en code sur le texte normalisé
    // (minuscules, accents retirés, ponctuation en espaces).
    // Aucun modèle — copie de la mécanique du banc.
    // ----------------------------------------------------------------

    private fun normaliser(s: String): String {
        val sansAccents = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return sansAccents.replace('\'', ' ').replace('’', ' ')
            .replace(Regex("[.,;:!?()«»\"/@-]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    private val declencheurs: List<Pair<String, List<String>>> = listOf(
        "NOM" to listOf(
            "nom", "prenom", "je m appelle", "appelez moi",
            "c est madame", "c est monsieur", "madame", "monsieur"
        ),
        "COURRIEL" to listOf(
            "courriel", "email", "e mail", "gmail", "hotmail",
            "outlook", "icloud", "arrobase"
        ),
        "TELEPHONE" to listOf(
            "telephone", "cellulaire", "numero", "texto",
            "rappelle", "appelez"
        ),
        "ADRESSE" to listOf(
            "adresse", "rue", "avenue", "boulevard", "chemin",
            "route", "rang", "postal", "j habite", "je reste",
            "demeure", "appartement"
        ),
        "RENDEZ-VOUS" to listOf(
            "rendez vous", "disponible", "disponibilite", "demain",
            "semaine prochaine", "a quelle heure",
            "lundi", "mardi", "mercredi", "jeudi", "vendredi",
            "samedi", "dimanche"
        ),
        "ARGENT" to listOf(
            "combien", "prix", "tarif", "dollars", "virement",
            "interac", "paiement", "depot", "acompte", "cachet"
        ),
        "EPELLATION" to listOf(
            "epelle", "ca s ecrit", "s ecrit", "avec deux", "avec un"
        )
    )

    private val motifChiffres = Regex("\\d[\\d\\s.,\\-]{4,}\\d")
    private val motifNumeroCivique = Regex("\\b\\d{3,4}\\b")
    private val motifCodePostal = Regex("[A-Za-z]\\d[A-Za-z]\\s?\\d[A-Za-z]\\d")
    private val motifLettresEpellees =
        Regex("(\\b[a-zA-Zàâéèêîôûç]\\b[\\s,]+){2,}\\b[a-zA-Zàâéèêîôûç]\\b")

    private fun sentinelle(texte: String): MutableSet<String> {
        val trouve = mutableSetOf<String>()
        val norm = normaliser(texte)
        val mots = norm.split(' ').toSet()
        for ((categorie, motsCles) in declencheurs) {
            for (motCle in motsCles) {
                val touche = if (motCle.contains(' ')) {
                    norm.contains(motCle)
                } else {
                    mots.contains(motCle)
                }
                if (touche) {
                    trouve.add(categorie)
                    break
                }
            }
        }
        if (motifChiffres.containsMatchIn(texte) ||
            texte.count { it.isDigit() } >= 7
        ) {
            trouve.add("TELEPHONE")
        }
        if (motifNumeroCivique.containsMatchIn(texte)) trouve.add("ADRESSE")
        if (motifCodePostal.containsMatchIn(texte)) trouve.add("ADRESSE")
        if (motifLettresEpellees.containsMatchIn(texte)) trouve.add("EPELLATION")
        if (texte.contains('$')) trouve.add("ARGENT")
        return trouve
    }

    // ----------------------------------------------------------------
    // Réglages et modèles : mêmes préférences « bench » que
    // l'écran (onglet Réglages de GestionActivity).
    // ----------------------------------------------------------------

    private fun nomModeleFlux(): String =
        prefs.getString("modele_flux", "ggml-base.bin")?.trim()
            ?.ifEmpty { null } ?: "ggml-base.bin"

    private fun nomModeleCorrection(): String =
        prefs.getString("modele_correction", "ggml-small.bin")?.trim()
            ?.ifEmpty { null } ?: "ggml-small.bin"

    private fun filsChoisis(): Int =
        prefs.getString("fils", "4")?.toIntOrNull()?.coerceIn(1, 9) ?: 4

    private fun trouverModele(nom: String): File? {
        val dossierModeles = File(getExternalFilesDir(null), "models")
        val telechargements = android.os.Environment
            .getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
        return listOf(
            File(File(filesDir, "models"), nom),
            File(dossierModeles, nom),
            File(telechargements, nom),
            File("/sdcard/Models", nom)
        ).firstOrNull { it.exists() }
    }

    /** État d'écoute publié pour la tuile : préférence lue par
     *  OreilleTileService + demande de rafraîchissement. */
    private fun noterEtatEcoute() {
        prefs.edit().putBoolean("ecoute_en_cours", enEcoute).apply()
        try {
            TileService.requestListeningState(
                this, ComponentName(this, OreilleTileService::class.java)
            )
        } catch (e: Exception) {
            // Tuile absente du panneau : rien à rafraîchir.
        }
    }

    // ----------------------------------------------------------------
    // État publié : fichier JSON lu par l'écran Oreille.
    // ----------------------------------------------------------------

    private fun dureeActuelleS(): Double =
        synchronized(verrouAudio) { tamponAudio.size } / TAUX.toDouble()

    private fun publierEtat() {
        ecrireFichierEtat()
    }

    private fun ecrireFichierEtat() {
        val copieSegments: List<Segment>
        val nbEpisodes: Int
        synchronized(verrouSegments) {
            copieSegments = ArrayList(segments)
            nbEpisodes = episodes.size
        }
        val etat = JSONObject()
            .put("phase", phase)
            .put("statut", statut)
            .put("duree_s", dureeActuelleS())
            .put("episodes", nbEpisodes)
            .put("transcript", copieSegments.joinToString(" ") { it.texte })
            .put("maj_ms", System.currentTimeMillis())
        try {
            val cible = File(filesDir, FICHIER_ETAT)
            val tmp = File(filesDir, "$FICHIER_ETAT.tmp")
            tmp.writeText(etat.toString())
            tmp.renameTo(cible)
        } catch (e: Exception) {
            // État non publié : l'écran garde son dernier affichage.
        }
    }

    private fun formatTemps(secondes: Double): String {
        val total = secondes.toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Horodatage ISO 8601 avec le fuseau local du téléphone
     *  (payload « reunion » : date et heures exactes de la séance). */
    private fun formatIso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CANADA_FRENCH)
            .format(Date(ms))

    // ----------------------------------------------------------------
    // Notification d'avant-plan : durée qui court, nombre
    // d'épisodes, et son propre bouton d'arrêt.
    // ----------------------------------------------------------------

    private fun creerCanal() {
        val canal = NotificationChannel(
            CANAL_ID, "Oreille", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(canal)
    }

    private fun construireNotification(): Notification {
        val ouvrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, OreilleActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val texte = when (phase) {
            "ecoute" -> {
                val nb = synchronized(verrouSegments) { episodes.size }
                "À l'écoute — ${formatTemps(dureeActuelleS())} · " +
                    "$nb épisode(s)"
            }
            "traitement" -> statut
            "chargement" -> "Chargement du modèle de flux…"
            else -> statut
        }
        val b = Notification.Builder(this, CANAL_ID)
            .setContentTitle("👂 Oreille")
            .setContentText(texte)
            .setSmallIcon(R.drawable.ic_oreille)
            .setContentIntent(ouvrir)
            .setOngoing(phase == "ecoute" || phase == "traitement" ||
                phase == "chargement")
        if (phase == "ecoute") {
            val arret = PendingIntent.getService(
                this, 1,
                Intent(this, OreilleService::class.java).apply {
                    action = ACTION_ARRETER
                    putExtra(EXTRA_SOURCE, "notification")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(
                android.R.drawable.ic_media_pause,
                "Arrêter et envoyer", arret
            )
        }
        return b.build()
    }

    private fun mettreAJourNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ECOUTE_ID, construireNotification())
    }

    /** Notification ordinaire (non liée au service) : résultat
     *  final ou alerte — elle survit à l'arrêt du service. */
    private fun notifierResultat(titre: String, texte: String) {
        creerCanal()
        val ouvrir = PendingIntent.getActivity(
            this, 2,
            Intent(this, OreilleActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CANAL_ID)
            .setContentTitle(titre)
            .setContentText(texte)
            .setSmallIcon(R.drawable.ic_oreille)
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_RESULTAT_ID, n)
    }

    // ----------------------------------------------------------------
    // Verrou de réveil : la capture survit à l'écran éteint.
    // Plafond de 4 h par prudence ; la séance type est bien en
    // deçà.
    // ----------------------------------------------------------------

    private fun prendreVerrouReveil() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            verrouReveil = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "pixelbridge:oreille"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            // Sans verrou, la capture continue tant que le système
            // la laisse vivre — on ne bloque pas la séance pour ça.
        }
    }

    private fun libererVerrouReveil() {
        try {
            if (verrouReveil?.isHeld == true) verrouReveil?.release()
        } catch (e: Exception) {
            // Déjà libéré.
        }
        verrouReveil = null
    }

    // ----------------------------------------------------------------
    // Oreille du pont : résultats JSON et clips audio partent vers
    // le serveur. Sans réseau, les fichiers attendent dans leurs
    // files locales et repartent tout seuls.
    // ----------------------------------------------------------------

    private fun pousserAuPont(
        resultat: String, donnees: JSONObject, cas: String = "appel-ecoute",
        auRetour: (envoyes: Int, reste: Int) -> Unit
    ) {
        val charge = JSONObject()
            .put("cas", cas)
            .put("contexte", false)
            .put("modele", nomModeleFlux())
            .put("fils", filsChoisis())
            .put("resultat", resultat)
            .put("donnees", donnees)
        val dirAttente = File(filesDir, "pont-attente").apply { mkdirs() }
        File(dirAttente, "run-${System.currentTimeMillis()}.json")
            .writeText(charge.toString())
        viderFilePont(auRetour)
    }

    fun viderFilePont(
        auRetour: ((envoyes: Int, reste: Int) -> Unit)? = null
    ) {
        val url = prefs.getString("pont_url", "")?.trim().orEmpty()
        val dirAttente = File(filesDir, "pont-attente")
        if (url.isEmpty()) {
            if (auRetour != null) {
                val reste = dirAttente.listFiles()
                    ?.count { it.name.endsWith(".json") } ?: 0
                auRetour(0, reste)
            }
            return
        }
        Thread {
            val fichiers = dirAttente.listFiles()
                ?.filter { it.name.endsWith(".json") }?.sorted() ?: emptyList()
            var envoyes = 0
            for (f in fichiers) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.doOutput = true
                    conn.outputStream.use { it.write(f.readBytes()) }
                    if (conn.responseCode in 200..299 && f.delete()) envoyes++
                    conn.disconnect()
                } catch (e: Exception) {
                    // Réseau absent : le fichier reste en file.
                }
            }
            val reste = dirAttente.listFiles()
                ?.count { it.name.endsWith(".json") } ?: 0
            auRetour?.invoke(envoyes, reste)
        }.start()
    }

    /** Pousse les clips WAV en attente vers /pont/<jeton>/audio/. */
    fun viderFileAudio() {
        val urlRun = prefs.getString("pont_url", "")?.trim().orEmpty()
        if (!urlRun.endsWith("/run")) return
        val base = urlRun.removeSuffix("/run")
        val racineAttente = File(filesDir, "appels-attente")
        Thread {
            val dossiers = racineAttente.listFiles()
                ?.filter { it.isDirectory }?.sorted() ?: emptyList()
            for (dossier in dossiers) {
                val fichiers = dossier.listFiles()
                    ?.filter { it.name.endsWith(".wav") }?.sorted() ?: emptyList()
                for (f in fichiers) {
                    try {
                        val cible = URL("$base/audio/${dossier.name}/${f.name}")
                        val conn = cible.openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty("Content-Type", "audio/wav")
                        conn.connectTimeout = 10000
                        conn.readTimeout = 15000
                        conn.doOutput = true
                        conn.outputStream.use { it.write(f.readBytes()) }
                        if (conn.responseCode in 200..299) f.delete()
                        conn.disconnect()
                    } catch (e: Exception) {
                        // Réseau absent : le clip reste en file.
                    }
                }
                if (dossier.listFiles()?.isEmpty() == true) dossier.delete()
            }
        }.start()
    }

    companion object {
        const val ACTION_DEMARRER =
            "ca.gregoirepainchaud.pixelbridge.oreille.DEMARRER"
        const val ACTION_ARRETER =
            "ca.gregoirepainchaud.pixelbridge.oreille.ARRETER"
        const val EXTRA_SOURCE = "source_arret"
        const val FICHIER_ETAT = "oreille-etat.json"

        private const val CANAL_ID = "oreille-ecoute"
        private const val NOTIF_ECOUTE_ID = 42
        private const val NOTIF_RESULTAT_ID = 43
        private const val TAUX = 16_000
        // Sous ce seuil de probabilité, un mot de la passe de
        // correction est une incertitude horodatée à relire.
        private const val SEUIL_INCERTITUDE = 0.5

        /** Démarre le service d'écoute (premier plan, type micro).
         *  À appeler depuis un contexte d'interaction (écran, tuile)
         *  : l'appelant gère le refus système éventuel. */
        fun intentionDemarrer(ctx: Context, source: String): Intent =
            Intent(ctx, OreilleService::class.java).apply {
                action = ACTION_DEMARRER
                putExtra(EXTRA_SOURCE, source)
            }

        fun intentionArreter(ctx: Context, source: String): Intent =
            Intent(ctx, OreilleService::class.java).apply {
                action = ACTION_ARRETER
                putExtra(EXTRA_SOURCE, source)
            }
    }
}
