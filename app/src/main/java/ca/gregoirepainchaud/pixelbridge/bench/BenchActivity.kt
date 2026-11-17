package ca.gregoirepainchaud.pixelbridge.bench

import android.app.Activity
import android.content.Intent
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
 * Écran du banc d'essai maison (étape 1 : texte seulement).
 *
 * Flux : choisir un cas du corpus figé (assets/bench/, copies de
 * benchmarks/corpus/), charger un GGUF déposé dans le dossier
 * « models » de l'app, lancer, lire la sortie brute et les temps du
 * moteur, enregistrer le résultat dans un fichier journal local.
 * Voir benchmarks/PROTOCOLE.md.
 */
class BenchActivity : Activity() {

    private val moteur = LlamaEngine()
    private lateinit var champModele: EditText
    private lateinit var champFilsGen: EditText
    private lateinit var champFilsBatch: EditText
    private lateinit var champPoll: EditText
    private lateinit var caseEpingler: android.widget.CheckBox
    private lateinit var champPont: EditText
    private lateinit var statutPont: TextView
    private lateinit var champPrompt: EditText
    private lateinit var sortie: TextView
    private val prefs by lazy { getSharedPreferences("bench", MODE_PRIVATE) }
    private var casCourant: String = "aucun"
    private var avecContexte: Boolean = false

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
            text = "Banc d'essai — modèles embarqués\n" +
                "Le modèle peut être déposé par USB ici :\n${dossierModeles.absolutePath}\n" +
                "ou simplement téléchargé dans le dossier Téléchargements du téléphone."
            textSize = 13f
        })
        champModele = EditText(this).apply {
            setText("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf")
            hint = "Nom du fichier GGUF"
        }
        ajout(champModele)

        // Fils séparés par phase : la génération plafonne à 2 fils
        // (bande passante mémoire), la lecture du prompt en veut 5.
        champFilsGen = EditText(this).apply {
            setText("2")
            hint = "Fils de génération (point doux mesuré : 2)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        ajout(champFilsGen)
        champFilsBatch = EditText(this).apply {
            setText("5")
            hint = "Fils de lecture du prompt (batch)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        ajout(champFilsBatch)

        // Expérience « synchronisation » : épingler les fils sur les
        // gros cœurs + niveau de poll (attente active) réglable.
        champPoll = EditText(this).apply {
            setText("100")
            hint = "Poll des fils (0-100, attente active)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        ajout(champPoll)
        caseEpingler = android.widget.CheckBox(this).apply {
            text = "Épingler les fils sur les gros cœurs"
            isChecked = true
        }
        ajout(caseEpingler)

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

        bouton("Cas D1 — note d'accord") { chargerCas("cas_d1.json", contexte = false) }
        bouton("Cas D1 — avec contexte (lexique)") { chargerCas("cas_d1.json", contexte = true) }
        bouton("Cas D2 — vocal de gig") { chargerCas("cas_d2.json", contexte = false) }
        bouton("Cas D2 — avec contexte (lexique)") { chargerCas("cas_d2.json", contexte = true) }

        champPrompt = EditText(this).apply {
            hint = "Prompt assemblé (modifiable)"
            minLines = 6
        }
        ajout(champPrompt)

        bouton("Charger le modèle") {
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
            val filsGen = champFilsGen.text.toString().toIntOrNull()
                ?.coerceIn(1, 9) ?: 2
            val filsBatch = champFilsBatch.text.toString().toIntOrNull()
                ?.coerceIn(1, 9) ?: 5
            val poll = champPoll.text.toString().toIntOrNull()
                ?.coerceIn(0, 100) ?: 100
            val epingler = caseEpingler.isChecked
            lancerEnArrierePlan("Chargement de $chemin …") {
                moteur.load(chemin, filsGen, filsBatch, poll, epingler)
                "Modèle chargé : $chemin " +
                    "(génération : $filsGen fils, lecture : $filsBatch fils)\n" +
                    "Groupes de fils : ${moteur.infoPools()}"
            }
        }
        bouton("Lancer l'extraction") {
            val brut = champPrompt.text.toString()
            if (brut.isBlank()) {
                Toast.makeText(this, "Choisis d'abord un cas.", Toast.LENGTH_SHORT).show()
                return@bouton
            }
            // Gabarit de conversation ChatML : le modèle est un modèle
            // « instruct » ; sans le gabarit, il complète un document
            // au lieu de répondre, et ne s'arrête pas après le JSON
            // (run du 2026-11-16 : plus de 5 minutes, aucune sortie).
            // Si le champ n'a pas été édité, on envoie le prompt en
            // deux morceaux pour réutiliser le préfixe en mémoire KV.
            val (prefixe, suffixe) =
                if (brut == promptAffiche && prefixePrompt.isNotEmpty()) {
                    prefixePrompt to suffixePrompt
                } else {
                    "" to "<|im_start|>user\n$brut<|im_end|>\n" +
                        "<|im_start|>assistant\n"
                }
            sortie.text = "Génération en cours …"
            Thread {
                try {
                    val recu = StringBuilder()
                    var phaseActuelle = "Génération en cours …"
                    fun afficher() {
                        val apercu = recu.toString()
                        val phaseLue = phaseActuelle
                        runOnUiThread {
                            sortie.text = "$phaseLue\n\n$apercu"
                        }
                    }
                    val texte = moteur.generate(
                        prefixe, suffixe, 512,
                        object : LlamaEngine.TokenSink {
                            override fun onToken(morceau: String) {
                                recu.append(morceau)
                                afficher()
                            }

                            override fun onPhase(texte: String) {
                                phaseActuelle = texte
                                afficher()
                            }
                        }
                    )
                    val stats = moteur.stats()
                    dernierResultat = "CAS=$casCourant " +
                        "CONTEXTE=$avecContexte GABARIT=chatml " +
                        "MODELE=${champModele.text} " +
                        "POOLS=${moteur.infoPools()} STATS: $stats\n" +
                        "SORTIE:\n$texte"
                    runOnUiThread {
                        sortie.text = "STATS : $stats\n\nSORTIE BRUTE :\n$texte"
                    }
                } catch (e: Throwable) {
                    runOnUiThread { sortie.text = "ERREUR : ${e.message}" }
                }
            }.start()
        }
        bouton("Enregistrer le résultat au journal") {
            val r = dernierResultat
            if (r == null) {
                Toast.makeText(this, "Rien à enregistrer : lance un cas d'abord.",
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

        sortie = TextView(this).apply { textSize = 13f }
        ajout(sortie)

        val defilement = ScrollView(this)
        defilement.addView(racine)
        setContentView(defilement)

        LlamaEngine.erreurBibliotheque()?.let {
            sortie.text = "ERREUR bibliothèque native : $it"
        }
    }

    private var dernierResultat: String? = null

    /**
     * Oreille du pont : le résultat enregistré part aussi vers le
     * serveur de Grégoire, où Muse le ramasse. Sans réseau (mode
     * avion du protocole), le JSON attend dans une file locale et
     * repart tout seul au prochain essai.
     */
    private fun pousserAuPont(resultat: String) {
        val url = champPont.text.toString().trim()
        prefs.edit().putString("pont_url", url).apply()
        // Le résultat entre en file D'ABORD, même sans adresse : il
        // partira dès qu'une adresse sera collée (leçon du run 5,
        // 2026-11-16 : adresse vide au moment de l'enregistrement,
        // résultat resté local sans que rien le signale assez fort).
        val charge = JSONObject()
            .put("cas", casCourant)
            .put("contexte", avecContexte)
            .put("modele", champModele.text.toString())
            .put("fils", champFilsGen.text.toString().toIntOrNull() ?: -1)
            .put("fils_lecture", champFilsBatch.text.toString().toIntOrNull() ?: -1)
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

    // Prompt en deux morceaux : le préfixe (les règles, constantes —
    // le moteur le garde en mémoire KV entre les runs) et le suffixe
    // (métadonnées + transcription, variables). promptAffiche garde
    // le texte complet montré dans le champ, pour détecter une
    // édition manuelle (dans ce cas, tout part en suffixe).
    private var prefixePrompt: String = ""
    private var suffixePrompt: String = ""
    private var promptAffiche: String = ""

    private fun chargerCas(fichier: String, contexte: Boolean) {
        val cas = JSONObject(assets.open("bench/$fichier").bufferedReader().readText())
        val gabarit = assets.open("prompt_extraction.txt").bufferedReader().readText()
        val coupure = gabarit.indexOf("{metadonnees}")
        val tete = if (coupure >= 0) gabarit.substring(0, coupure) else ""
        var reste = if (coupure >= 0) gabarit.substring(coupure) else gabarit
        reste = reste
            .replace("{metadonnees}", cas.getJSONObject("metadonnees").toString())
            .replace("{transcription}", cas.getString("transcription_reference"))
        if (contexte) {
            val lexique = assets.open("bench/lexique_qc.md").bufferedReader().readText()
            reste += "\n\nContexte et lexique québécois :\n" + lexique
        }
        prefixePrompt = "<|im_start|>user\n" + tete
        suffixePrompt = reste + "<|im_end|>\n<|im_start|>assistant\n"
        promptAffiche = tete + reste
        casCourant = fichier
        avecContexte = contexte
        champPrompt.setText(promptAffiche)
        sortie.text = "Cas $fichier chargé (contexte : $contexte). Prompt prêt à lancer."
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
     * Téléchargements est refusée aux apps par Android (le fichier
     * est visible, mais le moteur natif ne peut pas l'ouvrir —
     * constaté le 2026-11-16). On copie donc le modèle dans le
     * dossier privé de l'app, lu sans restriction.
     */
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CHOIX_MODELE || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        lancerEnArrierePlan("Copie du modèle dans l'app…") {
            var nom = champModele.text.toString()
                .substringAfterLast('/').ifBlank { "modele.gguf" }
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
                "Pèse « Charger le modèle »."
        }
    }

    override fun onDestroy() {
        moteur.free()
        super.onDestroy()
    }

    companion object {
        private const val CHOIX_MODELE = 42
    }
}
