package ca.gregoirepainchaud.pixelbridge.oreille

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ca.gregoirepainchaud.pixelbridge.commun.GardeVidange
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Écran Oreille de Gestion (design de Grégoire, 2026-11-17) :
 * UN seul gros bouton, en bas, à portée de pouce. On pèse —
 * boum, le micro écoute et transcrit tout ; on repèse — l'écoute
 * arrête et TOUT part au traitement tout seul, sans aucune étape
 * manuelle. Au-dessus du bouton, le strict nécessaire, toujours
 * visible (aucun état invisible) : la ligne d'état, le compteur
 * d'épisodes et le transcript en direct.
 *
 * Depuis le service robuste (2026-11-17 au soir), la séance vit
 * dans OreilleService (service d'avant-plan micro) : cet écran
 * n'est plus qu'une fenêtre et une télécommande — il lit l'état
 * publié par le service (filesDir/oreille-etat.json) et lui
 * envoie démarrer/arrêter. Fermer l'écran, changer d'app ou
 * éteindre l'écran ne coupe plus l'écoute ; l'arrêt se fait au
 * bouton d'ici, à la tuile des réglages rapides ou au bouton de
 * la notification du service.
 *
 * Aucun réglage ici : les valeurs (adresse du pont, modèles,
 * fils) vivent dans les préférences « bench » et se règlent
 * dans l'onglet Réglages de GestionActivity. (L'ancien écran
 * complet du banc, qui dupliquait cette mécanique, a été
 * supprimé en 0.18 — l'Oreille est la seule implémentation.)
 *
 * Ce qui reste ici : la vidange des files du pont à l'ouverture
 * (ce qui attend repart tout seul) et la back door de relecture —
 * le modèle lourd répond aux questions déposées par l'analyse de
 * Muse, un passage précis à la fois, traitement automatique à
 * l'ouverture, sans bouton.
 */
class OreilleActivity : Activity() {

    private val moteurRelecture = WhisperEngine()

    // Gardes des vidanges de files (0.18) : une à la fois par
    // file, arrêt au premier échec réseau, frein de reprise.
    private val gardePont = GardeVidange()
    private val gardeAudio = GardeVidange()
    private lateinit var statutEcoute: TextView
    private lateinit var compteurEpisodes: TextView
    private lateinit var defilementTranscript: ScrollView
    private lateinit var vueTranscript: TextView
    private lateinit var boutonEcoute: Button
    private val prefs by lazy { PreferencesApp.prefs(this) }

    // Phase courante publiée par le service (lue dans son fichier
    // d'état) : "idle", "chargement", "ecoute", "traitement",
    // "termine", "erreur".
    @Volatile private var phaseCourante = "idle"
    // Message local (résultat d'une relecture, erreur de
    // bibliothèque) : il tient la ligne d'état jusqu'à échéance,
    // le temps d'être lu, même si le service republie son état.
    private var messageLocal: String? = null
    private var messageLocalJusqua = 0L

    private val horloge = Handler(Looper.getMainLooper())
    private val ticEtat = object : Runnable {
        override fun run() {
            lireEtatService()
            horloge.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        statutEcoute = TextView(this).apply {
            text = "Prêt."
            textSize = 17f
            setPadding(0, 0, 0, 12)
        }
        racine.addView(statutEcoute)
        compteurEpisodes = TextView(this).apply {
            text = "Épisodes détectés : 0"
            textSize = 15f
            setPadding(0, 0, 0, 12)
        }
        racine.addView(compteurEpisodes)
        vueTranscript = TextView(this).apply { textSize = 14f }
        defilementTranscript = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(vueTranscript)
        }
        racine.addView(defilementTranscript)
        boutonEcoute = Button(this).apply {
            text = "👂 Écouter"
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (96 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = 24 }
            setOnClickListener { basculerEcoute() }
        }
        racine.addView(boutonEcoute)
        setContentView(racine)

        WhisperEngine.erreurBibliotheque()?.let {
            poserMessageLocal("ERREUR bibliothèque native : $it")
        }

        lireEtatService()
        // Le sondage d'état démarre en onResume et s'arrête en
        // onPause (0.18) : écran en arrière-plan, il ne tourne
        // plus pour rien.

        // Files du pont : ce qui attend repart dès l'ouverture.
        viderFilePont()
        viderFileAudio()

        // Back door : à chaque ouverture, ramasser les questions
        // de relecture déposées par l'analyse de Muse.
        traiterRelectures()

        // Demandes venues de la tuile (démarrage ou arrêt en
        // repli, quand le pilotage direct du service a échoué).
        traiterDemandes(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        traiterDemandes(intent)
    }

    override fun onResume() {
        super.onResume()
        lireEtatService()
        horloge.removeCallbacks(ticEtat)
        horloge.post(ticEtat)
    }

    override fun onPause() {
        horloge.removeCallbacks(ticEtat)
        super.onPause()
    }

    override fun onDestroy() {
        horloge.removeCallbacks(ticEtat)
        moteurRelecture.free()
        super.onDestroy()
    }

    // ----------------------------------------------------------------
    // Affichage : l'état du service, lu en boucle dans son fichier.
    // ----------------------------------------------------------------

    private fun lireEtatService() {
        val f = File(filesDir, OreilleService.FICHIER_ETAT)
        if (!f.exists()) return
        val etat = try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            return
        }
        val phase = etat.optString("phase", "idle")
        phaseCourante = phase
        val dureeS = etat.optDouble("duree_s", 0.0)
        val nbEpisodes = etat.optInt("episodes", 0)
        val statut = etat.optString("statut", "")
        val transcript = etat.optString("transcript", "")
        compteurEpisodes.text = "Épisodes détectés : $nbEpisodes"
        if (vueTranscript.text.toString() != transcript) {
            vueTranscript.text = transcript
            defilementTranscript.post {
                defilementTranscript.fullScroll(View.FOCUS_DOWN)
            }
        }
        val maintenant = System.currentTimeMillis()
        if (messageLocal != null && maintenant < messageLocalJusqua) {
            statutEcoute.text = messageLocal
        } else {
            messageLocal = null
            statutEcoute.text = if (phase == "ecoute") {
                "👂 À l'écoute — ${formatTemps(dureeS)}"
            } else {
                statut
            }
        }
        when (phase) {
            "ecoute" -> {
                boutonEcoute.text = "⏹ Arrêter et envoyer"
                boutonEcoute.isEnabled = true
            }
            "chargement" -> {
                boutonEcoute.text = "Chargement du modèle…"
                boutonEcoute.isEnabled = false
            }
            "traitement" -> {
                boutonEcoute.text = "Traitement en cours…"
                boutonEcoute.isEnabled = false
            }
            else -> {
                boutonEcoute.text = "👂 Écouter"
                boutonEcoute.isEnabled = true
            }
        }
    }

    private fun poserMessageLocal(texte: String) {
        messageLocal = texte
        messageLocalJusqua = System.currentTimeMillis() + 10_000
        statutEcoute.text = texte
    }

    private fun formatTemps(secondes: Double): String {
        val total = secondes.toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    // ----------------------------------------------------------------
    // Le bouton unique : démarrer / arrêter-et-envoyer — via le
    // service, jamais en direct.
    // ----------------------------------------------------------------

    private fun basculerEcoute() {
        when (phaseCourante) {
            "chargement", "traitement" -> return
            "ecoute" -> {
                startService(OreilleService.intentionArreter(this, "ecran"))
            }
            else -> {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.RECORD_AUDIO),
                        DEMANDE_MICRO
                    )
                    return
                }
                startForegroundService(
                    OreilleService.intentionDemarrer(this, "ecran")
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DEMANDE_MICRO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                basculerEcoute()
            } else {
                Toast.makeText(this, "Micro refusé : impossible d'écouter.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Demandes venues de la tuile « Oreille » en repli (le
     * pilotage direct du service n'a pas pu se faire : micro à
     * autoriser ou démarrage d'arrière-plan refusé). L'écran,
     * au premier plan, refait la demande par le chemin visible.
     */
    private fun traiterDemandes(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_ARRETER_ECOUTE, false)) {
            if (phaseCourante == "ecoute") {
                startService(OreilleService.intentionArreter(this, "tuile"))
            }
            return
        }
        if (intent.getBooleanExtra(EXTRA_DEMARRER_ECOUTE, false)) {
            if (phaseCourante != "ecoute" && phaseCourante != "traitement" &&
                phaseCourante != "chargement"
            ) {
                basculerEcoute()
            }
        }
    }

    // ----------------------------------------------------------------
    // Réglages et modèles pour la relecture (préférences « bench »,
    // onglet Réglages de GestionActivity).
    // ----------------------------------------------------------------

    private fun nomModeleRelecture(): String =
        prefs.getString(PreferencesApp.CLE_MODELE_RELECTURE, PreferencesApp.DEFAUT_MODELE_RELECTURE)
            ?.trim()?.ifEmpty { null } ?: PreferencesApp.DEFAUT_MODELE_RELECTURE

    private fun filsChoisis(): Int =
        PreferencesApp.nombreFils(prefs)

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

    // ----------------------------------------------------------------
    // Files du pont : à l'ouverture, ce qui attend (résultats JSON
    // et clips WAV, p. ex. faute de réseau à l'envoi) repart tout
    // seul. Mécanique jumelle de celle du service.
    // ----------------------------------------------------------------

    private fun viderFilePont() {
        val url = PreferencesApp.pontUrl(prefs)
        if (url.isEmpty()) return
        val dirAttente = File(filesDir, "pont-attente")
        if (!gardePont.essayer()) return
        Thread {
            var echecReseau = false
            try {
                val fichiers = dirAttente.listFiles()
                    ?.filter { it.name.endsWith(".json") }?.sorted()
                    ?: emptyList()
                for (f in fichiers) {
                    try {
                        val conn = URL(url).openConnection()
                            as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty(
                            "Content-Type", "application/json"
                        )
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        conn.doOutput = true
                        conn.outputStream.use { it.write(f.readBytes()) }
                        if (conn.responseCode in 200..299) f.delete()
                        conn.disconnect()
                    } catch (e: Exception) {
                        // Réseau absent : le fichier reste en
                        // file, la vidange s'arrête là (0.18).
                        echecReseau = true
                        break
                    }
                }
            } finally {
                gardePont.terminer(echecReseau)
            }
        }.start()
    }

    /** Pousse les clips WAV en attente vers /pont/<jeton>/audio/. */
    private fun viderFileAudio() {
        val urlRun = PreferencesApp.pontUrl(prefs)
        if (!urlRun.endsWith("/run")) return
        val base = urlRun.removeSuffix("/run")
        val racineAttente = File(filesDir, "appels-attente")
        if (!gardeAudio.essayer()) return
        Thread {
            var echecReseau = false
            try {
                val dossiers = racineAttente.listFiles()
                    ?.filter { it.isDirectory }?.sorted() ?: emptyList()
                for (dossier in dossiers) {
                    if (echecReseau) break
                    val fichiers = dossier.listFiles()
                        ?.filter { it.name.endsWith(".wav") }?.sorted()
                        ?: emptyList()
                    for (f in fichiers) {
                        try {
                            val cible = URL(
                                "$base/audio/${dossier.name}/${f.name}"
                            )
                            val conn = cible.openConnection()
                                as HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty(
                                "Content-Type", "audio/wav"
                            )
                            conn.connectTimeout = 10000
                            conn.readTimeout = 15000
                            conn.doOutput = true
                            conn.outputStream.use {
                                it.write(f.readBytes())
                            }
                            if (conn.responseCode in 200..299) {
                                f.delete()
                            }
                            conn.disconnect()
                        } catch (e: Exception) {
                            // Réseau absent : le clip reste en
                            // file, la vidange s'arrête (0.18).
                            echecReseau = true
                            break
                        }
                    }
                    if (dossier.listFiles()?.isEmpty() == true) {
                        dossier.delete()
                    }
                }
            } finally {
                gardeAudio.terminer(echecReseau)
            }
        }.start()
    }

    // ----------------------------------------------------------------
    // Back door de relecture : le modèle lourd du téléphone répond
    // aux questions de l'analyse de Muse — un passage précis à
    // la fois, jamais une analyse longue. Traitement automatique
    // à l'ouverture, sans bouton. Une question à laquelle on ne
    // peut pas répondre reste en attente au serveur.
    // ----------------------------------------------------------------

    private fun statutSiLibre(texte: String) {
        if (phaseCourante != "ecoute" && phaseCourante != "traitement" &&
            phaseCourante != "chargement"
        ) {
            runOnUiThread { poserMessageLocal(texte) }
        }
    }

    private fun traiterRelectures() {
        val urlRun = PreferencesApp.pontUrl(prefs)
        if (!urlRun.endsWith("/run")) return
        val base = urlRun.removeSuffix("/run")
        Thread {
            val questions: JSONArray = try {
                val conn = URL("$base/questions").openConnection()
                    as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val txt = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                JSONArray(txt)
            } catch (e: Exception) {
                statutSiLibre("Relectures : file illisible (${e.message}).")
                return@Thread
            }
            if (questions.length() == 0) return@Thread
            val nomLourd = nomModeleRelecture()
            val fichierLourd = trouverModele(nomLourd)
            if (fichierLourd == null) {
                statutSiLibre(
                    "Relectures : ${questions.length()} demande(s) en " +
                        "attente, modèle « $nomLourd » introuvable."
                )
                return@Thread
            }
            try {
                moteurRelecture.load(fichierLourd.absolutePath, filsChoisis())
            } catch (e: Throwable) {
                statutSiLibre(
                    "Relectures : modèle lourd impossible à charger " +
                        "(${e.message})."
                )
                return@Thread
            }
            var traitees = 0
            for (i in 0 until questions.length()) {
                val q = questions.optJSONObject(i) ?: continue
                val qid = q.optString("question_id")
                val appel = q.optString("appel")
                val ep = q.optInt("episode", 0)
                val dS = q.optDouble("debut_s", 0.0)
                val fS = q.optDouble("fin_s", 0.0)
                val contexte = q.optString("contexte")
                // Une question peut nommer son fichier (p. ex.
                // « fen-2.wav » pour une fenêtre de réunion) ;
                // à défaut, le clip de l'épisode de l'appel,
                // comme avant.
                val nomClip = q.optString("fichier").takeIf {
                    it.endsWith(".wav") && !it.contains("/") &&
                        !it.contains("..")
                } ?: "ep-$ep.wav"
                try {
                    val conn = URL("$base/audio/$appel/$nomClip")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    val octets = conn.inputStream.readBytes()
                    conn.disconnect()
                    val pcm = lireWavPcm(octets)
                    // Une seconde de contexte de chaque bord.
                    val debut = ((dS - 1.0) * TAUX).toInt()
                        .coerceIn(0, pcm.size)
                    val fin = ((fS + 1.0) * TAUX).toInt()
                        .coerceIn(debut, pcm.size)
                    check(fin - debut >= TAUX / 2) { "passage trop court" }
                    val texte = moteurRelecture.transcribe(
                        pcm.copyOfRange(debut, fin), contexte
                    )
                    val corps = JSONObject()
                        .put("question_id", qid)
                        .put("texte_lourd", texte.trim())
                    val c2 = URL("$base/reponse").openConnection()
                        as HttpURLConnection
                    c2.requestMethod = "POST"
                    c2.setRequestProperty("Content-Type", "application/json")
                    c2.connectTimeout = 10000
                    c2.readTimeout = 15000
                    c2.doOutput = true
                    c2.outputStream.use {
                        it.write(corps.toString().toByteArray(Charsets.UTF_8))
                    }
                    val code = c2.responseCode
                    c2.disconnect()
                    if (code in 200..299) traitees++
                } catch (e: Exception) {
                    // La question reste en attente au serveur.
                }
            }
            moteurRelecture.free()
            if (traitees > 0) {
                statutSiLibre(
                    "Relectures : $traitees/${questions.length()} " +
                        "réponse(s) envoyée(s) au pont ✓"
                )
            }
        }.start()
    }

    /** Décode un WAV PCM16 mono (les clips produits par l'app) en
     *  échantillons float. Cherche le bloc « data » plutôt que de
     *  supposer un en-tête fixe. */
    private fun lireWavPcm(octets: ByteArray): FloatArray {
        var pos = 12
        var dataDebut = -1
        var dataTaille = 0
        while (pos + 8 <= octets.size) {
            val id = String(octets, pos, 4, Charsets.US_ASCII)
            val taille = ByteBuffer.wrap(octets, pos + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                dataDebut = pos + 8
                dataTaille = taille
                break
            }
            pos += 8 + taille + (taille % 2)
        }
        require(dataDebut >= 0) { "WAV sans bloc data" }
        val n = minOf(dataTaille / 2, (octets.size - dataDebut) / 2)
        val bb = ByteBuffer.wrap(octets, dataDebut, n * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { bb.getShort(it * 2) / 32768f }
    }

    companion object {
        // Demandes portées par l'intention de la tuile « Oreille »
        // (OreilleTileService) quand elle ne peut pas piloter le
        // service directement.
        const val EXTRA_DEMARRER_ECOUTE = "demarrer_ecoute"
        const val EXTRA_ARRETER_ECOUTE = "arreter_ecoute"
        private const val DEMANDE_MICRO = 44
        private const val TAUX = 16_000
    }
}
