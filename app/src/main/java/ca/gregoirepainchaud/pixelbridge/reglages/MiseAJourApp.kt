package ca.gregoirepainchaud.pixelbridge.reglages

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------
// Mise à jour intégrée (mécanisme hérité de l'ancien écran
// d'accueil, chantier structure 0.8) : télécharge le dernier
// build vert publié (release bench-latest), lance
// l'installateur système par-dessus l'installation courante
// (signature stable), efface le paquet. Aucun fichier ne
// traîne dans Téléchargements. L'écran Réglages reçoit le
// statut et l'état du bouton par les deux rappels.
// ---------------------------------------------------------------------------

class MiseAJourApp(
    private val activite: Activity,
    private val afficherStatut: (String) -> Unit,
    private val boutonActif: (Boolean) -> Unit
) {

    fun fichierMaj(): File =
        File(
            File(activite.cacheDir, "maj").apply { mkdirs() },
            "app-debug.apk"
        )

    /** Un paquet resté d'une mise à jour précédente s'efface au démarrage. */
    fun nettoyerPaquet() {
        fichierMaj().delete()
    }

    fun lancer() {
        if (!activite.packageManager.canRequestPackageInstalls()) {
            afficherStatut(
                "Première fois : autorise l'installation " +
                    "depuis cette app dans l'écran qui s'ouvre, puis " +
                    "reviens peser sur Mettre à jour."
            )
            activite.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activite.packageName}")
                )
            )
            return
        }
        boutonActif(false)
        afficherStatut("Téléchargement de la mise à jour…")
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
                activite.runOnUiThread {
                    afficherStatut("Téléchargement terminé, installation…")
                    val uri = FileProvider.getUriForFile(
                        activite, "${activite.packageName}.fileprovider",
                        cible
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
                    activite.startActivity(intent)
                    boutonActif(true)
                }
            } catch (e: Exception) {
                activite.runOnUiThread {
                    afficherStatut("Échec de la mise à jour : ${e.message}")
                    boutonActif(true)
                }
            }
        }.start()
    }
}
