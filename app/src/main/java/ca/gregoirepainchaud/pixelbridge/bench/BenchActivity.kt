package ca.gregoirepainchaud.pixelbridge.bench

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import ca.gregoirepainchaud.pixelbridge.oreille.OreilleTileService
import ca.gregoirepainchaud.pixelbridge.oreille.WhisperEngine
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
 * Mécanique d'appel (depuis le 2026-11-16 au soir, design de
 * Grégoire, construit dans l'ancien banc d'écoute) :
 *
 * - Pendant l'écoute, le petit modèle (base) transcrit le micro
 *   en flux, par segments horodatés qui s'ajoutent au transcript.
 * - Une SENTINELLE en code (aucun modèle) lit chaque segment et
 *   ouvre des ÉPISODES quand une valeur critique passe : nom,
 *   courriel, téléphone, adresse, rendez-vous, argent,
 *   épellation. Les épisodes sont visibles en direct.
 * - À l'arrêt (= raccrocher) : le modèle avancé (small) fait une
 *   première passe de correction sur les seuls épisodes ; les
 *   clips audio des épisodes (jamais le flux entier) partent au
 *   serveur avec le transcript complet. L'ensemble forme un
 *   rendez-vous temporaire que Muse corrige en seconde passe ;
 *   ce qui reste non concluant garde son clip, réécoutable par
 *   Grégoire.
 * - Back door de relecture (2026-11-16, design de Grégoire) : le
 *   gros modèle (large-v3-turbo) vit ici, en cage — aucune
 *   analyse longue, jamais d'initiative. Quand l'analyse de
 *   Muse flanche sur un passage précis, elle dépose une
 *   QUESTION au pont (appel, épisode, passage relatif au clip,
 *   doute, contexte). À l'ouverture de l'app (ou au bouton), le
 *   téléphone ramasse les questions en attente, télécharge le
 *   clip déjà au serveur, décode JUSTE le passage demandé avec
 *   le modèle lourd et renvoie la lecture au pont. La troisième
 *   instance serveur a été retirée le même soir (mémoire du VPS
 *   de production, risque chez l'hébergeur) : c'est ce
 *   mécanisme-ci qui la remplace.
 */
class BenchActivity : Activity() {

    private data class Segment(val debutS: Double, val finS: Double, val texte: String)

    private class Episode(
        val debutS: Double,
        var finS: Double,
        val categories: MutableSet<String>,
        val texteBase: StringBuilder,
        var texteCorrige: String = ""
    ) {
        // Passages incertains de la passe de correction (small) :
        // {debut_s, fin_s, texte, proba_min} en temps d'appel —
        // les drapeaux que la troisième instance relit.
        val incertitudes = ArrayList<JSONObject>()
    }

    private val moteur = WhisperEngine()
    // Moteur séparé pour la back door : son propre contexte, le
    // modèle lourd ne touche jamais au moteur de flux/correction.
    private val moteurRelecture = WhisperEngine()
    private lateinit var champModele: EditText
    private lateinit var champModeleCorrection: EditText
    private lateinit var champModeleRelecture: EditText
    private lateinit var champFils: EditText
    private lateinit var champPont: EditText
    private lateinit var statutPont: TextView
    private lateinit var statutRelecture: TextView
    private lateinit var statutEcoute: TextView
    private lateinit var episodesConteneur: LinearLayout
    private lateinit var boutonEcoute: Button
    private lateinit var sortie: TextView
    private val prefs by lazy { PreferencesApp.prefs(this) }

    // État de la séance.
    @Volatile private var enEcoute = false
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
    private var dernierResultat: String? = null
    // Le modèle de flux est-il chargé dans le moteur ? Suit les
    // chargements pour que la tuile « Oreille » puisse démarrer
    // l'écoute sans repasser par le bouton de chargement (et pour
    // savoir qu'après la passe de correction, c'est small qui
    // occupe le moteur, plus le flux).
    @Volatile private var fluxCharge = false
    // Rangées d'épisodes AFFICHÉES, parallèles à la liste des
    // épisodes : créées une fois puis mises à jour en place.
    // (Les glissés dessus ont été retirés le 2026-11-17 —
    // décision de Grégoire : distraction au volant. L'affichage
    // en direct reste, la confiance se fait après l'appel.)
    private val rangeesEpisodes = ArrayList<TextView>()
    private var vueAucunEpisode: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dossierModeles = File(getExternalFilesDir(null), "models").apply { mkdirs() }

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun ajout(vue: android.view.View) {
            vue.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            racine.addView(vue)
        }
        fun bouton(texte: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = texte
            b.setOnClickListener { action() }
            ajout(b)
            return b
        }

        ajout(TextView(this).apply {
            text = "Mécanique d'appel — écoute, sentinelle, correction\n" +
                "Modèle de flux (petit) et modèle de correction (avancé) : " +
                "fichiers .bin déposés par USB dans ${dossierModeles.absolutePath} " +
                "ou téléchargés dans Téléchargements, puis choisis ci-dessous."
            textSize = 13f
        })
        // Les valeurs initiales viennent des préférences —
        // l'onglet Réglages de GestionActivity édite les mêmes
        // clés ; les défauts restent ceux d'origine.
        champModele = EditText(this).apply {
            setText(prefs.getString(PreferencesApp.CLE_MODELE_FLUX, PreferencesApp.DEFAUT_MODELE_FLUX))
            hint = "Modèle de flux (.bin) — transcription en direct"
        }
        ajout(champModele)
        champModeleCorrection = EditText(this).apply {
            setText(prefs.getString(PreferencesApp.CLE_MODELE_CORRECTION, PreferencesApp.DEFAUT_MODELE_CORRECTION))
            hint = "Modèle de correction (.bin) — première passe sur les épisodes"
        }
        ajout(champModeleCorrection)
        champModeleRelecture = EditText(this).apply {
            setText(
                prefs.getString(PreferencesApp.CLE_MODELE_RELECTURE, PreferencesApp.DEFAUT_MODELE_RELECTURE)
            )
            hint = "Modèle de relecture (.bin) — back door, passages doutés seulement"
        }
        ajout(champModeleRelecture)
        champFils = EditText(this).apply {
            setText(prefs.getString(PreferencesApp.CLE_FILS, PreferencesApp.DEFAUT_FILS))
            hint = "Nombre de fils pour Whisper"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        ajout(champFils)

        champPont = EditText(this).apply {
            hint = "Adresse du pont (URL complète, à coller une fois)"
            setSingleLine(true)
            setText(prefs.getString(PreferencesApp.CLE_PONT_URL, ""))
        }
        ajout(champPont)
        statutPont = TextView(this).apply { textSize = 12f }
        ajout(statutPont)
        viderFilePont()
        viderFileAudio()

        statutRelecture = TextView(this).apply { textSize = 12f }
        ajout(statutRelecture)
        bouton("Traiter les relectures demandées (back door)") {
            traiterRelectures()
        }

        bouton("Choisir le fichier du modèle (le copie dans l'app)") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, CHOIX_MODELE)
        }

        bouton("Charger le modèle de flux") {
            val nom = champModele.text.toString().trim()
            val fichier = trouverModele(nom)
            if (fichier == null) {
                sortie.text = "Fichier « $nom » introuvable (Téléchargements, " +
                    "dossiers de l'app, /sdcard/Models)."
                return@bouton
            }
            val chemin = fichier.absolutePath
            val fils = filsChoisis()
            lancerEnArrierePlan("Chargement de $chemin …") {
                val debut = System.currentTimeMillis()
                moteur.load(chemin, fils)
                fluxCharge = true
                "Modèle de flux chargé : $chemin (fils : $fils) " +
                    "en ${System.currentTimeMillis() - debut} ms.\n" +
                    "Pèse « Démarrer l'écoute » et parle."
            }
        }

        boutonEcoute = bouton("Démarrer l'écoute") { basculerEcoute() }
        statutEcoute = TextView(this).apply { textSize = 13f }
        ajout(statutEcoute)
        episodesConteneur = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        ajout(episodesConteneur)

        bouton("Enregistrer le résultat au journal") {
            val r = dernierResultat
            if (r == null) {
                Toast.makeText(this, "Rien à enregistrer : fais un appel d'abord.",
                    Toast.LENGTH_SHORT).show()
                return@bouton
            }
            val journal = File(filesDir, "bench_resultats.txt")
            val horodatage = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CANADA_FRENCH)
                .format(Date())
            journal.appendText("\n===== $horodatage =====\n$r\n")
            Toast.makeText(this, "Enregistré : ${journal.absolutePath}",
                Toast.LENGTH_LONG).show()
            pousserAuPont(r, null)
        }

        sortie = TextView(this).apply { textSize = 14f }
        ajout(sortie)

        // Back door : à chaque ouverture, ramasser les questions
        // de relecture déposées par l'analyse de Muse.
        traiterRelectures()

        // Tuile « Oreille » : si l'écran est ouvert par la tuile,
        // l'écoute démarre (le modèle de flux se charge tout seul
        // au besoin).
        traiterDemandeDemarrage(intent)

        val defilement = ScrollView(this)
        defilement.addView(racine)
        setContentView(defilement)

        WhisperEngine.erreurBibliotheque()?.let {
            sortie.text = "ERREUR bibliothèque native : $it"
        }
    }

    private fun filsChoisis(): Int =
        champFils.text.toString().toIntOrNull()?.coerceIn(1, 9) ?: 4

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        traiterDemandeDemarrage(intent)
    }

    /**
     * Demande de démarrage venue de la tuile « Oreille » : ouvrir
     * l'écran ne suffit pas, l'écoute part. Si elle roule déjà,
     * on ne fait rien de plus (l'écran est simplement ramené
     * devant — l'arrêt se fait au bouton, v1 prévisible). Si le
     * modèle de flux n'est pas chargé, on le charge d'abord.
     */
    private fun traiterDemandeDemarrage(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_DEMARRER_ECOUTE, false) != true) {
            return
        }
        if (enEcoute) return
        if (fluxCharge) {
            basculerEcoute()
            return
        }
        val nom = champModele.text.toString().trim()
        val fichier = trouverModele(nom)
        if (fichier == null) {
            statutEcoute.text = "Oreille (tuile) : modèle de flux " +
                "« $nom » introuvable — charge-le depuis cet écran."
            return
        }
        statutEcoute.text = "Oreille (tuile) : chargement du modèle de flux…"
        Thread {
            try {
                moteur.load(fichier.absolutePath, filsChoisis())
                fluxCharge = true
                runOnUiThread { if (!enEcoute) basculerEcoute() }
            } catch (e: Throwable) {
                runOnUiThread {
                    statutEcoute.text = "Oreille (tuile) : chargement " +
                        "impossible (${e.message})."
                }
            }
        }.start()
    }

    /** État d'écoute publié pour la tuile : préférence lue par
     *  OreilleTileService + demande de rafraîchissement de la
     *  tuile si elle est au panneau. */
    private fun noterEtatEcoute() {
        prefs.edit().putBoolean(PreferencesApp.CLE_ECOUTE_EN_COURS, enEcoute).apply()
        try {
            TileService.requestListeningState(
                this, ComponentName(this, OreilleTileService::class.java)
            )
        } catch (e: Exception) {
            // Tuile absente du panneau : rien à rafraîchir.
        }
    }

    private fun trouverModele(nom: String): File? {
        val dossierModeles = File(getExternalFilesDir(null), "models")
        val telechargements = android.os.Environment.getExternalStoragePublicDirectory(
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
    // Sentinelle : motifs en code sur le texte normalisé (minuscules,
    // accents retirés, apostrophes en espaces). Aucun modèle.
    // ----------------------------------------------------------------

    private fun normaliser(s: String): String {
        val sansAccents = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        // La ponctuation devient des espaces : sans ça, un mot-clé
        // collé à sa ponctuation (« gmail.com ») ne matche jamais
        // le mot seul — le courriel du 2026-11-16 23 h 16 est passé
        // à côté pour exactement cette raison.
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

    // Virgules comprises : un numéro dicté chiffre par chiffre
    // (« 4, 8, 8, 3, 6… ») est plein de virgules — la première
    // version du motif s'y brisait et le numéro du 2026-11-16
    // 23 h 16 n'a jamais ouvert son épisode.
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

    private fun basculerEcoute() {
        if (enEcoute) {
            arreterEcoute()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO), DEMANDE_MICRO
            )
            return
        }
        demarrerEcoute()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DEMANDE_MICRO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            demarrerEcoute()
        } else if (requestCode == DEMANDE_MICRO) {
            Toast.makeText(this, "Micro refusé : impossible d'écouter.",
                Toast.LENGTH_LONG).show()
        }
    }

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
            sortie.text = "ERREUR : le micro ne s'initialise pas."
            rec.release()
            return
        }
        enregistreur = rec
        enEcoute = true
        boutonEcoute.text = "Arrêter l'écoute (raccrocher)"
        statutEcoute.text = "Écoute démarrée — la sentinelle guette les valeurs."
        episodesConteneur.removeAllViews()
        rangeesEpisodes.clear()
        vueAucunEpisode = null
        noterEtatEcoute()
        sortie.text = ""
        rec.startRecording()

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
                            tamponAudio.subList(0, tamponAudio.size - max).clear()
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
                        statutEcoute.text = "ERREUR transcription : ${e.message}"
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

    private fun rafraichirAffichage() {
        val copieSegments: List<Segment>
        val copieEpisodes: List<Episode>
        val calcul: Long
        val audioS: Double
        synchronized(verrouSegments) {
            copieSegments = ArrayList(segments)
            copieEpisodes = ArrayList(episodes)
            calcul = calculTotalMs
        }
        audioS = synchronized(verrouAudio) { tamponAudio.size } / TAUX.toDouble()
        val facteur = if (calcul > 0) {
            (copieSegments.lastOrNull()?.finS ?: 0.0) / (calcul / 1000.0)
        } else 0.0
        val transcript = copieSegments.joinToString(" ") { it.texte }
        runOnUiThread {
            statutEcoute.text =
                ("Audio capté : %.0f s — calcul cumulé %d ms — " +
                    "facteur temps réel ×%.1f").format(audioS, calcul, facteur)
            if (copieEpisodes.isEmpty()) {
                if (rangeesEpisodes.isNotEmpty()) {
                    episodesConteneur.removeAllViews()
                    rangeesEpisodes.clear()
                }
                if (vueAucunEpisode == null) {
                    vueAucunEpisode = TextView(this@BenchActivity).apply {
                        text = "Aucun épisode capté pour le moment."
                        textSize = 13f
                    }
                    episodesConteneur.addView(vueAucunEpisode)
                }
            } else {
                vueAucunEpisode?.let { episodesConteneur.removeView(it) }
                vueAucunEpisode = null
                while (rangeesEpisodes.size > copieEpisodes.size) {
                    val derniere =
                        rangeesEpisodes.removeAt(rangeesEpisodes.size - 1)
                    episodesConteneur.removeView(derniere)
                }
                copieEpisodes.forEachIndexed { i, ep ->
                    if (i < rangeesEpisodes.size) {
                        majRangeeEpisode(rangeesEpisodes[i], i, ep)
                    } else {
                        val tv = fabriquerRangeeEpisode(i, ep)
                        rangeesEpisodes.add(tv)
                        episodesConteneur.addView(tv)
                    }
                }
            }
            sortie.text = transcript
        }
    }

    /**
     * Une rangée par épisode, lisible pendant l'appel — lecture
     * seule. Les glissés de confirmation/révision qui vivaient
     * ici ont été retirés le 2026-11-17 (décision de Grégoire :
     * distraction au volant ; le contexte d'appel principal est
     * la conduite). La confiance se fait entièrement après
     * l'appel : correction, analyse, back door.
     */
    private fun fabriquerRangeeEpisode(index: Int, ep: Episode): TextView {
        val tv = TextView(this)
        tv.textSize = 13f
        tv.setPadding(16, 20, 16, 20)
        majRangeeEpisode(tv, index, ep)
        return tv
    }

    /** Contenu d'une rangée d'épisode, mis à jour en place : le
     *  texte grandit à mesure que l'épisode s'étend. */
    private fun majRangeeEpisode(tv: TextView, index: Int, ep: Episode) {
        tv.text = "Épisode ${index + 1} · ${ep.categories.joinToString("+")} · " +
            "${formatTemps(ep.debutS)} → ${formatTemps(ep.finS)} · " +
            "« ${ep.texteBase.toString().take(140)} »"
    }

    // ----------------------------------------------------------------
    // Arrêt = raccrocher : vidange du flux, correction des épisodes
    // par le modèle avancé, clips audio, envoi au serveur.
    // ----------------------------------------------------------------

    private fun arreterEcoute() {
        enEcoute = false
        noterEtatEcoute()
        boutonEcoute.text = "Démarrer l'écoute"
        statutEcoute.text = "Raccroché — correction des épisodes et envoi en cours…"
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
            val nomCorrection = champModeleCorrection.text.toString().trim()
            val fichierCorrection = trouverModele(nomCorrection)
            var noteCorrection = ""
            if (fichierCorrection != null && copieEpisodes.isNotEmpty()) {
                try {
                    moteur.load(fichierCorrection.absolutePath, filsChoisis())
                    // Le moteur tient maintenant small, plus le
                    // modèle de flux : la tuile devra recharger.
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
            // références des clips, envoyé au serveur pour la
            // seconde passe de Muse.
            val transcript = copieSegments.joinToString(" ") { it.texte }
            val donnees = JSONObject()
                .put("appel", appelId)
                .put("duree_s", dureeS)
                .put("modele_flux", champModele.text.toString())
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
                            // Glissés retirés (2026-11-17) : le
                            // champ reste, à « aucun » en permanence.
                            .put("avis", "aucun")
                            .put("audio", "ep-$i.wav"))
                    }
                })
            val facteur = if (calcul > 0) dureeS / (calcul / 1000.0) else 0.0
            val resume = "APPEL $appelId DUREE=%.1f s EPISODES=%d FACTEUR_FLUX=%.1f\n%s".format(
                dureeS, copieEpisodes.size, facteur, transcript
            )
            dernierResultat = resume
            pousserAuPont(resume, donnees)

            val lignes = copieEpisodes.mapIndexed { i, ep ->
                "Épisode ${i + 1} · ${ep.categories.joinToString("+")} · " +
                    "${formatTemps(ep.debutS)} → ${formatTemps(ep.finS)}\n" +
                    "base : ${ep.texteBase}\n" +
                    if (ep.texteCorrige.isNotBlank()) {
                        "corrigé : ${ep.texteCorrige}"
                    } else {
                        "corrigé : (aucune première passe)"
                    }
            }
            runOnUiThread {
                statutEcoute.text =
                    ("Appel envoyé au serveur — %.1f s, %d épisode(s). " +
                        "La seconde passe se fera côté Muse.").format(
                            dureeS, copieEpisodes.size
                        )
                episodesConteneur.removeAllViews()
                rangeesEpisodes.clear()
                vueAucunEpisode = null
                episodesConteneur.addView(TextView(this@BenchActivity).apply {
                    textSize = 13f
                    text = lignes.joinToString("\n\n")
                })
                sortie.text = transcript
            }
        }.start()
    }

    /** Retranscrit une plage d'audio (plafond 90 s, tranches de
     *  30 s chaînées par le texte précédent) avec le modèle chargé.
     *  Si [incertitudes] est fourni, les passages incertains de la
     *  passe (mots sous le seuil de probabilité) y sont ajoutés en
     *  temps absolus dans l'appel. */
    private fun retranscrirePlage(
        audio: FloatArray, debutS: Double, finS: Double,
        incertitudes: ArrayList<JSONObject>? = null
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
            val texte: String
            if (incertitudes != null) {
                val (t, jetonsJson) = try {
                    moteur.transcribeComplet(tranche, invite)
                } catch (e: Throwable) {
                    Pair("", "[]")
                }
                texte = t
                extraireIncertitudes(
                    jetonsJson, pos.toDouble() / TAUX, incertitudes
                )
            } else {
                texte = try {
                    moteur.transcribe(tranche, invite)
                } catch (e: Throwable) {
                    ""
                }
            }
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
     * question de relecture (back door : le modèle lourd du
     * téléphone décode alors juste ce passage).
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
                // Ponctuation pure écartée : une incertitude doit
                // porter sur un mot, pas sur un point d'interrogation.
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
    // le serveur de Grégoire. Sans réseau, les fichiers attendent
    // dans leurs files locales et repartent tout seuls.
    // ----------------------------------------------------------------

    private fun pousserAuPont(resultat: String, donnees: JSONObject?) {
        val url = champPont.text.toString().trim()
        // L'adresse du pont et les réglages de modèles vivent
        // dans les préférences — l'onglet Réglages de
        // GestionActivity édite les mêmes clés.
        prefs.edit()
            .putString(PreferencesApp.CLE_PONT_URL, url)
            .putString(PreferencesApp.CLE_MODELE_FLUX, champModele.text.toString().trim())
            .putString(
                PreferencesApp.CLE_MODELE_CORRECTION,
                champModeleCorrection.text.toString().trim()
            )
            .putString(
                PreferencesApp.CLE_MODELE_RELECTURE,
                champModeleRelecture.text.toString().trim()
            )
            .putString(PreferencesApp.CLE_FILS, champFils.text.toString().trim())
            .apply()
        val charge = JSONObject()
            .put("cas", if (donnees != null) "appel-ecoute" else "whisper-ecoute")
            .put("contexte", false)
            .put("modele", champModele.text.toString())
            .put("fils", champFils.text.toString().toIntOrNull() ?: -1)
            .put("resultat", resultat)
        if (donnees != null) charge.put("donnees", donnees)
        val dirAttente = File(filesDir, "pont-attente").apply { mkdirs() }
        File(dirAttente, "run-${System.currentTimeMillis()}.json")
            .writeText(charge.toString())
        if (url.isEmpty()) {
            statutPont.text = "Pont : résultat en file — il partira quand l'adresse du pont sera collée dans le champ."
            return
        }
        viderFilePont()
    }

    private fun viderFilePont() {
        val url = PreferencesApp.pontUrl(prefs)
        val dirAttente = File(filesDir, "pont-attente")
        if (url.isEmpty()) return
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
            runOnUiThread {
                // Libellé honnête (Grégoire, 2026-11-16) : « envoyés ✓ »
                // seulement si un envoi réel vient d'avoir lieu ; une
                // file vide au départ ne veut rien dire d'autre que
                // « rien en attente ».
                statutPont.text = when {
                    reste > 0 ->
                        "Pont : $reste résultat(s) en file — ils partiront au retour du réseau."
                    envoyes > 0 ->
                        "Pont : résultats envoyés au serveur ✓"
                    else ->
                        "Pont : rien en attente."
                }
            }
        }.start()
    }

    /** Pousse les clips WAV en attente vers /pont/<jeton>/audio/. */
    private fun viderFileAudio() {
        val urlRun = PreferencesApp.pontUrl(prefs)
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
    // la fois, jamais une analyse longue. Une question à laquelle
    // on ne peut pas répondre (réseau, clip, modèle absent) reste
    // en attente au serveur et repartira à la prochaine ouverture.
    // ----------------------------------------------------------------

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
                runOnUiThread {
                    statutRelecture.text =
                        "Relectures : file illisible (${e.message})."
                }
                return@Thread
            }
            if (questions.length() == 0) {
                runOnUiThread {
                    statutRelecture.text =
                        "Relectures : aucune demande en attente."
                }
                return@Thread
            }
            val nomLourd = champModeleRelecture.text.toString().trim()
            val fichierLourd = trouverModele(nomLourd)
            if (fichierLourd == null) {
                runOnUiThread {
                    statutRelecture.text =
                        "Relectures : ${questions.length()} demande(s) en " +
                        "attente, mais le modèle « $nomLourd » est " +
                        "introuvable — choisis son fichier pour le copier."
                }
                return@Thread
            }
            try {
                moteurRelecture.load(fichierLourd.absolutePath, filsChoisis())
            } catch (e: Throwable) {
                runOnUiThread {
                    statutRelecture.text =
                        "Relectures : modèle lourd impossible à charger " +
                        "(${e.message})."
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
                runOnUiThread {
                    statutRelecture.text =
                        "Relecture $qid : décodage du passage…"
                }
                try {
                    val conn = URL("$base/audio/$appel/ep-$ep.wav")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    val octets = conn.inputStream.readBytes()
                    conn.disconnect()
                    val pcm = lireWavPcm(octets)
                    // Une seconde de contexte de chaque bord : le
                    // décodeur entend la phrase, pas un mot coupé.
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
            runOnUiThread {
                statutRelecture.text =
                    "Relectures : $traitees/${questions.length()} " +
                    "réponse(s) envoyée(s) au pont ✓"
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

    private fun lancerEnArrierePlan(message: String, travail: () -> String) {
        sortie.text = message
        Thread {
            val texte = try {
                travail()
            } catch (e: Throwable) {
                "ERREUR : ${e.message}"
            }
            runOnUiThread { sortie.text = texte }
        }.start()
    }

    /**
     * Le sélecteur de fichiers système donne un accès en lecture au
     * fichier choisi, où qu'il soit. La lecture directe dans
     * Téléchargements est refusée aux apps par Android — on copie
     * donc le modèle dans le dossier privé de l'app, lu sans
     * restriction (constaté le 2026-11-16 avec les GGUF).
     */
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CHOIX_MODELE || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        lancerEnArrierePlan("Copie du modèle dans l'app…") {
            var nom = "modele.bin"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)?.let { nom = it }
                }
            }
            val dossier = File(filesDir, "models").apply { mkdirs() }
            val cible = File(dossier, nom)
            val flux = contentResolver.openInputStream(uri)
                ?: error("Fichier illisible")
            flux.use { entree ->
                FileOutputStream(cible).use { sortie ->
                    entree.copyTo(sortie)
                }
            }
            "Modèle copié dans l'app : ${cible.name} " +
                "(${cible.length() / 1_000_000} Mo)\n" +
                "Écris son nom dans le champ du modèle de flux ou de " +
                "correction, puis charge le modèle de flux."
        }
    }

    override fun onDestroy() {
        enEcoute = false
        prefs.edit().putBoolean(PreferencesApp.CLE_ECOUTE_EN_COURS, false).apply()
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
        private const val CHOIX_MODELE = 42
        private const val DEMANDE_MICRO = 43
        private const val TAUX = 16_000
        // Sous ce seuil de probabilité, un mot de la passe de
        // correction est une incertitude horodatée à relire.
        private const val SEUIL_INCERTITUDE = 0.5
    }
}
