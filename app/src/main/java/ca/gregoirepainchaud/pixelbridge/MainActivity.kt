package ca.gregoirepainchaud.pixelbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import ca.gregoirepainchaud.pixelbridge.service.BridgeService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Écran minimal du squelette : état de la file. L'interface réelle
 * (validation des extractions d'un coup d'œil) viendra avec l'usage.
 *
 * Bouton « Mettre à jour » : télécharge le dernier build vert publié
 * (release bench-latest du dépôt), lance l'installateur système
 * par-dessus l'installation courante (signature stable), et efface
 * le paquet téléchargé. Aucun fichier ne traîne dans Téléchargements.
 */
class MainActivity : Activity() {

    private lateinit var statutMaj: TextView
    private lateinit var boutonMaj: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, BridgeService::class.java))

        // Nettoyage : un paquet d'installation resté d'une mise à
        // jour précédente est effacé au démarrage.
        fichierMaj().delete()

        val texte = TextView(this).apply {
            text = "Pixel Bridge\n\n" +
                "Pipeline local : appels et messages vocaux → transcription → " +
                "extraction structurée → synchro quand le réseau revient.\n\n" +
                "Tout fonctionne hors ligne."
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        colonne.addView(texte)
        val versBench = Button(this).apply {
            text = "Banc d'essai — modèles embarqués"
            setOnClickListener {
                startActivity(
                    Intent(
                        this@MainActivity,
                        ca.gregoirepainchaud.pixelbridge.bench.BenchActivity::class.java
                    )
                )
            }
        }
        colonne.addView(versBench)

        boutonMaj = Button(this).apply {
            text = "Mettre à jour l'app (dernier build)"
            setOnClickListener { lancerMiseAJour() }
        }
        colonne.addView(boutonMaj)
        statutMaj = TextView(this).apply {
            textSize = 13f
            setPadding(48, 8, 48, 8)
        }
        colonne.addView(statutMaj)
        setContentView(colonne)
    }

    private fun fichierMaj(): File =
        File(File(cacheDir, "maj").apply { mkdirs() }, "app-debug.apk")

    private fun lancerMiseAJour() {
        if (!packageManager.canRequestPackageInstalls()) {
            statutMaj.text = "Première fois : autorise l'installation " +
                "depuis Pixel Bridge dans l'écran qui s'ouvre, puis " +
                "reviens peser sur Mettre à jour."
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        boutonMaj.isEnabled = false
        statutMaj.text = "Téléchargement de la mise à jour…"
        Thread {
            try {
                val url = URL(
                    "https://github.com/gregoirepainchaud-debug/" +
                        "pixel-bridge/releases/download/bench-latest/app-debug.apk"
                )
                val cible = fichierMaj()
                cible.delete()
                val connexion = url.openConnection() as HttpURLConnection
                connexion.connectTimeout = 15000
                connexion.readTimeout = 60000
                connexion.inputStream.use { entree ->
                    cible.outputStream().use { sortie -> entree.copyTo(sortie) }
                }
                runOnUiThread {
                    statutMaj.text = "Téléchargement terminé, installation…"
                    val uri = FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", cible
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            uri, "application/vnd.android.package-archive"
                        )
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                    startActivity(intent)
                    boutonMaj.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statutMaj.text = "Échec de la mise à jour : ${e.message}"
                    boutonMaj.isEnabled = true
                }
            }
        }.start()
    }
}
