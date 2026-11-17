package ca.gregoirepainchaud.pixelbridge.bench

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Banc d'écoute — transcription en temps réel (depuis le 2026-11-16
 * au soir, décision de Grégoire : le banc d'extraction LLM est clos,
 * le modèle local est abandonné comme interprète ; on garde l'app,
 * sa coquille et son oreille, et on mesure L'OREILLE).
 *
 * Flux : charger un modèle Whisper (.bin) copié dans l'app, peser
 * « Démarrer l'écoute », parler au micro du téléphone. Toutes les
 * ~3 s de nouvel audio, la fenêtre des 30 dernières secondes est
 * retranscrite et le texte s'affiche en direct, avec le temps de
 * calcul et le facteur temps réel (durée d'audio / temps de
 * calcul). À l'arrêt : passe finale en tranches de 30 s sur tout
 * l'enregistrement, statistiques de la séance, résultat
 * enregistrable au journal (et poussé au pont, comme avant).
 */
class BenchActivity : Activity() {

    private val moteur = WhisperEngine()
    private lateinit var champModele: EditText
    private lateinit var champFils: EditText
    private lateinit var champPont: EditText
    private lateinit var statutPont: TextView
    private lateinit var statutEcoute: TextView
    private lateinit var boutonEcoute: Button
    private lateinit var sortie: TextView
    private val prefs by lazy { getSharedPreferences("bench", MODE_PRIVATE) }

    // État de la séance d'écoute.
    @Volatile private var enEcoute = false
    private val verrouAudio = Object()
    private val tamponAudio = ArrayList<Float>(16_000 * 60)
    private var enregistreur: AudioRecord? = null
    private var filCapture: Thread? = null
    private var filInference: Thread? = null
    private var dernierResultat: String? = null

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
            text = "Banc d'écoute — transcription en direct (Whisper)\n" +
                "Le modèle (.bin) peut être déposé par USB ici :\n${dossierModeles.absolutePath}\n" +
                "ou téléchargé dans le dossier Téléchargements du téléphone."
            textSize = 13f
        })
        champModele = EditText(this).apply {
            setText("ggml-base.bin")
            hint = "Nom du fichier modèle Whisper (.bin)"
        }
        ajout(champModele)
        champFils = EditText(this).apply {
            setText("4")
            hint = "Nombre de fils pour Whisper"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        ajout(champFils)

        // Oreille du pont : adresse complète (avec jeton) du service
        // qui reçoit les résultats sur le serveur de Grégoire. Collée
        // une fois, gardée dans les préférences — jamais dans le code
        // (le dépôt est public).
        champPont = EditText(this).apply {
            hint = "Adresse du pont (URL complète, à coller une fois)"
            setSingleLine(true)
            setText(prefs.getString("pont_url", ""))
        }
        ajout(champPont)
        statutPont = TextView(this).apply { textSize = 12f }
        ajout(statutPont)
        viderFilePont()

        bouton("Choisir le fichier du modèle (le copie dans l'app)") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, CHOIX_MODELE)
        }

        bouton("Charger le modèle Whisper") {
            val nom = champModele.text.toString().trim()
            val telechargements = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val candidats = listOf(
                File(File(filesDir, "models"), nom),
                File(dossierModeles, nom),
                File(telechargements, nom),
                File("/sdcard/Models", nom)
            )
            val fichier = candidats.firstOrNull { it.exists() }
            if (fichier == null) {
                sortie.text = "Fichier « $nom » introuvable. Cherché dans :\n" +
                    candidats.joinToString("\n") { it.absolutePath }
                return@bouton
            }
            val chemin = fichier.absolutePath
            val fils = champFils.text.toString().toIntOrNull()
                ?.coerceIn(1, 9) ?: 4
            lancerEnArrierePlan("Chargement de $chemin …") {
                val debut = System.currentTimeMillis()
                moteur.load(chemin, fils)
                "Modèle Whisper chargé : $chemin (fils : $fils) " +
                    "en ${System.currentTimeMillis() - debut} ms.\n" +
                    "Pèse « Démarrer l'écoute » et parle."
            }
        }

        boutonEcoute = bouton("Démarrer l'écoute") { basculerEcoute() }
        statutEcoute = TextView(this).apply { textSize = 13f }
        ajout(statutEcoute)

        bouton("Enregistrer le résultat au journal") {
            val r = dernierResultat
            if (r == null) {
                Toast.makeText(this, "Rien à enregistrer : fais une séance d'écoute d'abord.",
                    Toast.LENGTH_SHORT).show()
                return@bouton
            }
            val journal = File(filesDir, "bench_resultats.txt")
            val horodatage = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CANADA_FRENCH)
                .format(Date())
            journal.appendText("\n===== $horodatage =====\n$r\n")
            Toast.makeText(this, "Enregistré : ${journal.absolutePath}",
                Toast.LENGTH_LONG).show()
            pousserAuPont(r)
        }

        sortie = TextView(this).apply { textSize = 14f }
        ajout(sortie)

        val defilement = ScrollView(this)
        defilement.addView(racine)
        setContentView(defilement)

        WhisperEngine.erreurBibliotheque()?.let {
            sortie.text = "ERREUR bibliothèque native : $it"
        }
    }

    // ----------------------------------------------------------------
    // Écoute : capture micro + retranscription en fenêtre glissante.
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
        boutonEcoute.text = "Arrêter l'écoute"
        statutEcoute.text = "Écoute démarrée — parle, le texte s'en vient."
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
                        // Garde-fou du banc : 15 minutes maximum.
                        val max = TAUX * 60 * 15
                        if (tamponAudio.size > max) {
                            tamponAudio.subList(0, tamponAudio.size - max).clear()
                        }
                    }
                }
            }
        }.apply { start() }

        filInference = Thread {
            var echantillonsVus = 0
            var invite = ""
            while (enEcoute) {
                Thread.sleep(400)
                val total = synchronized(verrouAudio) { tamponAudio.size }
                // Nouvelle fenêtre quand ~3 s d'audio neuf est arrivé.
                if (total - echantillonsVus < TAUX * 3 && echantillonsVus != 0) continue
                if (total < TAUX * 2) continue
                echantillonsVus = total
                val fenetre = synchronized(verrouAudio) {
                    val debut = maxOf(0, tamponAudio.size - TAUX * 30)
                    FloatArray(tamponAudio.size - debut) { tamponAudio[debut + it] }
                }
                val dureeFenetre = fenetre.size.toDouble() / TAUX
                val t0 = System.currentTimeMillis()
                val texte = try {
                    moteur.transcribe(fenetre, invite)
                } catch (e: Throwable) {
                    runOnUiThread { statutEcoute.text = "ERREUR transcription : ${e.message}" }
                    continue
                }
                val calculMs = System.currentTimeMillis() - t0
                if (texte.isNotBlank()) {
                    invite = texte.takeLast(200)
                }
                val facteur = dureeFenetre / (calculMs / 1000.0)
                runOnUiThread {
                    statutEcoute.text =
                        ("Fenêtre de %.1f s transcrite en %d ms — " +
                        "facteur temps réel ×%.1f — audio capté : %.0f s").format(
                            dureeFenetre, calculMs, facteur, total / TAUX.toDouble()
                        )
                    if (texte.isNotBlank()) sortie.text = texte.trim()
                }
            }
        }.apply { start() }
    }

    private fun arreterEcoute() {
        enEcoute = false
        boutonEcoute.text = "Démarrer l'écoute"
        statutEcoute.text = "Écoute arrêtée — passe finale en cours…"
        try {
            enregistreur?.stop()
        } catch (e: Exception) {
            // Déjà arrêté.
        }
        enregistreur?.release()
        enregistreur = null
        filCapture?.join(1000)
        filInference?.join(1000)

        // Passe finale : tranches de 30 s sans chevauchement sur
        // tout l'enregistrement, statistiques de la séance.
        Thread {
            val tout = synchronized(verrouAudio) { tamponAudio.toFloatArray() }
            val dureeTotale = tout.size.toDouble() / TAUX
            val morceau = TAUX * 30
            val assemble = StringBuilder()
            var invite = ""
            var calculTotalMs = 0L
            var pos = 0
            while (pos < tout.size) {
                val fin = minOf(tout.size, pos + morceau)
                val tranche = tout.copyOfRange(pos, fin)
                val t0 = System.currentTimeMillis()
                val texte = try {
                    moteur.transcribe(tranche, invite)
                } catch (e: Throwable) {
                    ""
                }
                calculTotalMs += System.currentTimeMillis() - t0
                if (texte.isNotBlank()) {
                    assemble.append(texte.trim()).append(' ')
                    invite = texte.takeLast(200)
                }
                pos = fin
            }
            val facteurGlobal = if (calculTotalMs > 0) {
                dureeTotale / (calculTotalMs / 1000.0)
            } else 0.0
            val stats = "AUDIO=%.1f s CALCUL_FINAL=%d ms FACTEUR_GLOBAL=%.1f".format(
                dureeTotale, calculTotalMs, facteurGlobal
            )
            dernierResultat = "CAS=whisper-ecoute MODELE=${champModele.text} " +
                "FILS=${champFils.text} STATS: $stats\n" +
                "TRANSCRIPT FINAL :\n${assemble.toString().trim()}"
            runOnUiThread {
                statutEcoute.text =
                    ("Séance terminée — %.1f s d'audio, passe finale en %d ms " +
                    "(facteur temps réel ×%.1f).").format(
                        dureeTotale, calculTotalMs, facteurGlobal
                    )
                sortie.text = assemble.toString().trim()
            }
        }.start()
    }

    // ----------------------------------------------------------------
    // Oreille du pont (inchangée) : le résultat enregistré part vers
    // le serveur de Grégoire, où Muse le ramasse. Sans réseau, le
    // JSON attend dans une file locale et repart tout seul.
    // ----------------------------------------------------------------

    private fun pousserAuPont(resultat: String) {
        val url = champPont.text.toString().trim()
        prefs.edit().putString("pont_url", url).apply()
        val charge = JSONObject()
            .put("cas", "whisper-ecoute")
            .put("contexte", false)
            .put("modele", champModele.text.toString())
            .put("fils", champFils.text.toString().toIntOrNull() ?: -1)
            .put("resultat", resultat)
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
        val url = prefs.getString("pont_url", "")?.trim().orEmpty()
        val dirAttente = File(filesDir, "pont-attente")
        if (url.isEmpty()) return
        Thread {
            val fichiers = dirAttente.listFiles()
                ?.filter { it.name.endsWith(".json") }?.sorted() ?: emptyList()
            for (f in fichiers) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.doOutput = true
                    conn.outputStream.use { it.write(f.readBytes()) }
                    if (conn.responseCode in 200..299) f.delete()
                    conn.disconnect()
                } catch (e: Exception) {
                    // Réseau absent : le fichier reste en file.
                }
            }
            val reste = dirAttente.listFiles()
                ?.count { it.name.endsWith(".json") } ?: 0
            runOnUiThread {
                statutPont.text = if (reste == 0) {
                    "Pont : résultats envoyés au serveur ✓"
                } else {
                    "Pont : $reste résultat(s) en file — ils partiront au retour du réseau."
                }
            }
        }.start()
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
            var nom = champModele.text.toString()
                .substringAfterLast('/').ifBlank { "modele.bin" }
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
            runOnUiThread { champModele.setText(nom) }
            "Modèle copié dans l'app : ${cible.name} " +
                "(${cible.length() / 1_000_000} Mo)\n" +
                "Pèse « Charger le modèle Whisper »."
        }
    }

    override fun onDestroy() {
        enEcoute = false
        try {
            enregistreur?.stop()
        } catch (e: Exception) {
            // Rien à arrêter.
        }
        enregistreur?.release()
        moteur.free()
        super.onDestroy()
    }

    companion object {
        private const val CHOIX_MODELE = 42
        private const val DEMANDE_MICRO = 43
        private const val TAUX = 16_000
    }
}
