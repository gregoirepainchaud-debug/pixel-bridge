package ca.gregoirepainchaud.pixelbridge.bench

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
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
                "Dossier des modèles (à remplir par USB) :\n${dossierModeles.absolutePath}"
            textSize = 13f
        })
        champModele = EditText(this).apply {
            setText("qwen2.5-vl-3b-instruct-q4_k_m.gguf")
            hint = "Nom du fichier GGUF"
        }
        ajout(champModele)

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
            val chemin = File(dossierModeles, champModele.text.toString().trim()).absolutePath
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
            } catch (e: Exception) {
                "ERREUR : ${e.message}"
            }
            runOnUiThread { sortie.text = texte }
        }.start()
    }

    override fun onDestroy() {
        moteur.free()
        super.onDestroy()
    }
}
