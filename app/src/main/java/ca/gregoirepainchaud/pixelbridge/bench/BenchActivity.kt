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
    private lateinit var champPrompt: EditText
    private lateinit var sortie: TextView
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
            lancerEnArrierePlan("Chargement de $chemin …") {
                moteur.load(chemin)
                "Modèle chargé : $chemin"
            }
        }
        bouton("Lancer l'extraction") {
            val prompt = champPrompt.text.toString()
            if (prompt.isBlank()) {
                Toast.makeText(this, "Choisis d'abord un cas.", Toast.LENGTH_SHORT).show()
                return@bouton
            }
            lancerEnArrierePlan("Génération en cours …") {
                val texte = moteur.generate(prompt)
                val stats = moteur.stats()
                dernierResultat = "CAS=$casCourant CONTEXTE=$avecContexte " +
                    "MODELE=${champModele.text} STATS: $stats\nSORTIE:\n$texte"
                "STATS : $stats\n\nSORTIE BRUTE :\n$texte"
            }
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

    private fun chargerCas(fichier: String, contexte: Boolean) {
        val cas = JSONObject(assets.open("bench/$fichier").bufferedReader().readText())
        val gabarit = assets.open("prompt_extraction.txt").bufferedReader().readText()
        var prompt = gabarit
            .replace("{metadonnees}", cas.getJSONObject("metadonnees").toString())
            .replace("{transcription}", cas.getString("transcription_reference"))
        if (contexte) {
            val lexique = assets.open("bench/lexique_qc.md").bufferedReader().readText()
            prompt += "\n\nContexte et lexique québécois :\n" + lexique
        }
        casCourant = fichier
        avecContexte = contexte
        champPrompt.setText(prompt)
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
