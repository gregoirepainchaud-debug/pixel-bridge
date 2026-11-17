package ca.gregoirepainchaud.pixelbridge.oreille

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
 * Écran Oreille de Gestion (design de Grégoire, 2026-11-17) :
 * UN seul gros bouton, en bas, à portée de pouce. On pèse —
 * boum, le micro écoute et transcrit tout ; on repèse — l'écoute
 * arrête et TOUT part au traitement tout seul (correction des
 * épisodes par le modèle avancé, clips WAV, payload au pont),
 * sans aucune étape manuelle. Au-dessus du bouton, le strict
 * nécessaire, toujours visible (aucun état invisible) : la ligne
 * d'état, le compteur d'épisodes et le transcript en direct.
 *
 * Aucun réglage ici : les valeurs (adresse du pont, modèles,
 * fils) vivent dans les préférences « bench » et se règlent
 * dans l'onglet Réglages de GestionActivity. L'ancien écran
 * complet du banc (BenchActivity) reste dans le code, caché —
 * relié à aucun élément visible de l'interface.
 *
 * La mécanique est celle du banc, éprouvée : flux segmenté par
 * le petit modèle, sentinelle en code qui ouvre des épisodes
 * sur les valeurs critiques, correction des seuls épisodes à
 * l'arrêt, back door de relecture traitée automatiquement à
 * l'ouverture (le modèle lourd répond aux questions déposées
 * par l'analyse de Muse, un passage précis à la fois).
 */
class OreilleActivity : Activity() {

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
    private val moteurRelecture = WhisperEngine()
    private lateinit var statutEcoute: TextView
    private lateinit var compteurEpisodes: TextView
    private lateinit var defilementTranscript: ScrollView
    private lateinit var vueTranscript: TextView
    private lateinit var boutonEcoute: Button
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
    // Le modèle de flux est-il chargé dans le moteur ? Après la
    // passe de correction, c'est small qui occupe le moteur : il
    // faudra recharger le flux au prochain démarrage.
    @Volatile private var fluxCharge = false

    // Horloge de la ligne d'état pendant l'écoute (durée qui
    // court) : la ligne appartient à l'horloge tant que ça roule.
    private val horloge = Handler(Looper.getMainLooper())
    private val ticHorloge = object : Runnable {
        override fun run() {
            if (!enEcoute) return
            val audioS = synchronized(verrouAudio) { tamponAudio.size } /
                TAUX.toDouble()
            statutEcoute.text = "👂 À l'écoute — ${formatTemps(audioS)}"
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
            statutEcoute.text = "ERREUR bibliothèque native : $it"
        }

        // Files du pont : ce qui attend repart dès l'ouverture.
        viderFilePont()
        viderFileAudio()

        // Back door : à chaque ouverture, ramasser les questions
        // de relecture déposées par l'analyse de Muse.
        traiterRelectures()

        // Tuile « Oreille » : si l'écran est ouvert par la tuile,
        // l'écoute démarre (le modèle de flux se charge tout seul
        // au besoin).
        traiterDemandeDemarrage(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        traiterDemandeDemarrage(intent)
    }

    // ----------------------------------------------------------------
    // Réglages : lus dans les préférences (onglet Réglages de
    // GestionActivity) ; un champ vidé retombe sur son défaut.
    // ----------------------------------------------------------------

    private fun nomModeleFlux(): String =
        prefs.getString("modele_flux", "ggml-base.bin")?.trim()
            ?.ifEmpty { null } ?: "ggml-base.bin"

    private fun nomModeleCorrection(): String =
        prefs.getString("modele_correction", "ggml-small.bin")?.trim()
            ?.ifEmpty { null } ?: "ggml-small.bin"

    private fun nomModeleRelecture(): String =
        prefs.getString("modele_relecture", "ggml-large-v3-turbo.bin")
            ?.trim()?.ifEmpty { null } ?: "ggml-large-v3-turbo.bin"

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

    // ----------------------------------------------------------------
    // Le bouton unique : démarrer / arrêter-et-envoyer.
    // ----------------------------------------------------------------

    private fun basculerEcoute() {
        if (traitementEnCours) return
        if (enEcoute) {
            arreterEcoute()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                DEMANDE_MICRO
            )
            return
        }
        if (fluxCharge) {
            demarrerEcoute()
            return
        }
        // Premier démarrage (ou après une correction) : le modèle
        // de flux se charge tout seul, puis l'écoute part.
        val nom = nomModeleFlux()
        val fichier = trouverModele(nom)
        if (fichier == null) {
            statutEcoute.text = "Modèle de flux « $nom » introuvable " +
                "(Téléchargements, dossiers de l'app)."
            return
        }
        boutonEcoute.isEnabled = false
        statutEcoute.text = "Chargement du modèle de flux…"
        Thread {
            try {
                moteur.load(fichier.absolutePath, filsChoisis())
                fluxCharge = true
                runOnUiThread {
                    boutonEcoute.isEnabled = true
                    if (!enEcoute) demarrerEcoute()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    boutonEcoute.isEnabled = true
                    statutEcoute.text =
                        "Chargement du modèle impossible : ${e.message}"
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DEMANDE_MICRO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            basculerEcoute()
        } else if (requestCode == DEMANDE_MICRO) {
            Toast.makeText(this, "Micro refusé : impossible d'écouter.",
                Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Demande de démarrage venue de la tuile « Oreille » : ouvrir
     * l'écran ne suffit pas, l'écoute part (le modèle de flux se
     * charge tout seul au besoin, par le même chemin que le
     * bouton). Si l'écoute roule déjà, l'écran est simplement
     * ramené devant — l'arrêt se fait au bouton.
     */
    private fun traiterDemandeDemarrage(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_DEMARRER_ECOUTE, false) != true) {
            return
        }
        if (enEcoute || traitementEnCours) return
        basculerEcoute()
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
    // Écoute : capture micro + flux segmenté + épisodes en direct.
    // ----------------------------------------------------------------

    private fun demarrerEcoute() {
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
            statutEcoute.text = "ERREUR : le micro ne s'initialise pas."
            rec.release()
            return
        }
        enregistreur = rec
        enEcoute = true
        boutonEcoute.text = "⏹ Arrêter et envoyer"
        compteurEpisodes.text = "Épisodes détectés : 0"
        vueTranscript.text = ""
        noterEtatEcoute()
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
                    runOnUiThread {
                        statutEcoute.text =
                            "ERREUR transcription : ${e.message}"
                    }
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
                rafraichirAffichage()
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

    private fun formatTemps(secondes: Double): String {
        val total = secondes.toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** Pendant l'écoute : le transcript défile et le compteur
     *  d'épisodes suit. La ligne d'état appartient à l'horloge. */
    private fun rafraichirAffichage() {
        val copieSegments: List<Segment>
        val nbEpisodes: Int
        synchronized(verrouSegments) {
            copieSegments = ArrayList(segments)
            nbEpisodes = episodes.size
        }
        val transcript = copieSegments.joinToString(" ") { it.texte }
        runOnUiThread {
            compteurEpisodes.text = "Épisodes détectés : $nbEpisodes"
            vueTranscript.text = transcript
            defilementTranscript.post {
                defilementTranscript.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    // ----------------------------------------------------------------
    // Arrêt = envoyer : vidange du flux, correction des épisodes
    // par le modèle avancé, clips audio, envoi au pont — tout
    // s'enchaîne tout seul, la ligne d'état suit les phases.
    // ----------------------------------------------------------------

    private fun arreterEcoute() {
        enEcoute = false
        noterEtatEcoute()
        horloge.removeCallbacks(ticHorloge)
        traitementEnCours = true
        boutonEcoute.isEnabled = false
        boutonEcoute.text = "Traitement en cours…"
        statutEcoute.text = "Raccroché — correction des épisodes…"
        try {
            enregistreur?.stop()
        } catch (e: Exception) {
            // Déjà arrêté.
        }
        enregistreur?.release()
        enregistreur = null
        filCapture?.join(1000)
        filInference?.join(1500)

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

            // Première passe de correction : le modèle avancé
            // retranscrit les seuls épisodes, depuis l'audio gardé.
            val nomCorrection = nomModeleCorrection()
            val fichierCorrection = trouverModele(nomCorrection)
            var noteCorrection = ""
            if (fichierCorrection != null && copieEpisodes.isNotEmpty()) {
                try {
                    moteur.load(fichierCorrection.absolutePath, filsChoisis())
                    // Le moteur tient maintenant small, plus le
                    // modèle de flux : le prochain démarrage le
                    // rechargera tout seul.
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
            viderFileAudio()

            // Rendez-vous temporaire : transcript + épisodes +
            // références des clips, envoyé au pont pour la seconde
            // passe de Muse.
            runOnUiThread { statutEcoute.text = "Envoi au pont…" }
            val transcript = copieSegments.joinToString(" ") { it.texte }
            val donnees = JSONObject()
                .put("appel", appelId)
                .put("duree_s", dureeS)
                .put("modele_flux", nomModeleFlux())
                .put("modele_correction", nomCorrection)
                .put("note_correction", noteCorrection)
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
                boutonEcoute.isEnabled = true
                boutonEcoute.text = "👂 Écouter"
                compteurEpisodes.text =
                    "Épisodes détectés : ${copieEpisodes.size}"
                statutEcoute.text = when {
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
            }
        }.start()
    }

    /** Retranscrit une plage d'audio (plafond 90 s, tranches de
     *  30 s chaînées par le texte précédent) avec le modèle chargé.
     *  Les passages incertains de la passe sont ajoutés à
     *  [incertitudes] en temps absolus dans l'appel. */
    private fun retranscrirePlage(
        audio: FloatArray, debutS: Double, finS: Double,
        incertitudes: ArrayList<JSONObject>
    ): String {
        val debut = (debutS * TAUX).toInt().coerceIn(0, audio.size)
        val fin = minOf((finS * TAUX).toInt(), debut + TAUX * 90, audio.size)
        if (fin <= debut) return ""
        val morceaux = StringBuilder()
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
                morceaux.append(texte.trim()).append(' ')
                invite = texte.takeLast(200)
            }
            pos = bout
        }
        return morceaux.toString().trim()
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
    // Oreille du pont : résultats JSON et clips audio partent vers
    // le serveur. Sans réseau, les fichiers attendent dans leurs
    // files locales et repartent tout seuls.
    // ----------------------------------------------------------------

    private fun pousserAuPont(
        resultat: String, donnees: JSONObject,
        auRetour: (envoyes: Int, reste: Int) -> Unit
    ) {
        val charge = JSONObject()
            .put("cas", "appel-ecoute")
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

    private fun viderFilePont(
        auRetour: ((envoyes: Int, reste: Int) -> Unit)? = null
    ) {
        val url = prefs.getString("pont_url", "")?.trim().orEmpty()
        val dirAttente = File(filesDir, "pont-attente")
        if (url.isEmpty()) {
            if (auRetour != null) {
                val reste = dirAttente.listFiles()
                    ?.count { it.name.endsWith(".json") } ?: 0
                runOnUiThread { auRetour(0, reste) }
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
            if (auRetour != null) {
                runOnUiThread { auRetour(envoyes, reste) }
            }
        }.start()
    }

    /** Pousse les clips WAV en attente vers /pont/<jeton>/audio/. */
    private fun viderFileAudio() {
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

    // ----------------------------------------------------------------
    // Back door de relecture : le modèle lourd du téléphone répond
    // aux questions de l'analyse de Muse — un passage précis à
    // la fois, jamais une analyse longue. Traitement automatique
    // à l'ouverture, sans bouton. Une question à laquelle on ne
    // peut pas répondre reste en attente au serveur.
    // ----------------------------------------------------------------

    private fun statutSiLibre(texte: String) {
        if (!enEcoute && !traitementEnCours) {
            statutEcoute.text = texte
        }
    }

    private fun traiterRelectures() {
        val urlRun = prefs.getString("pont_url", "")?.trim().orEmpty()
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
                runOnUiThread {
                    statutSiLibre("Relectures : file illisible (${e.message}).")
                }
                return@Thread
            }
            if (questions.length() == 0) return@Thread
            val nomLourd = nomModeleRelecture()
            val fichierLourd = trouverModele(nomLourd)
            if (fichierLourd == null) {
                runOnUiThread {
                    statutSiLibre(
                        "Relectures : ${questions.length()} demande(s) en " +
                            "attente, modèle « $nomLourd » introuvable."
                    )
                }
                return@Thread
            }
            try {
                moteurRelecture.load(fichierLourd.absolutePath, filsChoisis())
            } catch (e: Throwable) {
                runOnUiThread {
                    statutSiLibre(
                        "Relectures : modèle lourd impossible à charger " +
                            "(${e.message})."
                    )
                }
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
                try {
                    val conn = URL("$base/audio/$appel/ep-$ep.wav")
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
                runOnUiThread {
                    statutSiLibre(
                        "Relectures : $traitees/${questions.length()} " +
                            "réponse(s) envoyée(s) au pont ✓"
                    )
                }
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

    override fun onDestroy() {
        enEcoute = false
        horloge.removeCallbacks(ticHorloge)
        prefs.edit().putBoolean("ecoute_en_cours", false).apply()
        try {
            enregistreur?.stop()
        } catch (e: Exception) {
            // Rien à arrêter.
        }
        enregistreur?.release()
        moteur.free()
        moteurRelecture.free()
        super.onDestroy()
    }

    companion object {
        // Demande de démarrage portée par l'intention de la tuile
        // « Oreille » (OreilleTileService).
        const val EXTRA_DEMARRER_ECOUTE = "demarrer_ecoute"
        private const val DEMANDE_MICRO = 44
        private const val TAUX = 16_000
        // Sous ce seuil de probabilité, un mot de la passe de
        // correction est une incertitude horodatée à relire.
        private const val SEUIL_INCERTITUDE = 0.5
    }
}
