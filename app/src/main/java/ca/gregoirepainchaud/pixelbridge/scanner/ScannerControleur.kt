package ca.gregoirepainchaud.pixelbridge.scanner

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import ca.gregoirepainchaud.pixelbridge.gestion.Scans
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// La mécanique de l'onglet Scanner (chantier structure 0.8) :
// le flux de scan complet est celui de Google ML Kit, fourni
// par Play Services — détection des bords, correction de
// perspective, rotation, filtres et nettoyage sont faits là,
// pas ici. Aucune permission CAMERA : le scanner roule dans
// le processus Play Services. Au retour, l'URI du PDF de
// ML Kit est ÉPHÉMÈRE : on copie le PDF immédiatement dans
// un dossier de session horodaté du stockage propre à l'app
// (Documents/scans/scan-<horodatage>/session.pdf), puis on
// propose le partage et, depuis la V2, l'envoi à Muse par
// le pont (module gestion/Scans). Depuis la V3, PDF
// seulement. L'écran est ScannerOngletView ; l'activité ne
// fait que livrer ici le résultat du scan (onActivityResult)
// et demander le rafraîchissement des envois en attente.
// ---------------------------------------------------------------------------

class ScannerControleur(private val activite: Activity) {

    private var fichiersDerniereSession: List<File> = emptyList()
    private var pdfDernierScan: File? = null
    private var pagesDernierScan = 0
    private var sessionDernierScan: String? = null
    private var scanMisEnFile: String? = null

    // Usage choisi pour le prochain envoi : « livre » par
    // défaut, ou « autre » (choix en deux gros boutons).
    private var usageScan = "livre"

    /** L'usage actuellement sélectionné (lu par l'écran à sa construction). */
    val usageActuel: String get() = usageScan

    /** L'écran de l'onglet, créé avec le contrôleur. */
    val vue: ScannerOngletView = ScannerOngletView(activite, this)

    fun lancerScan() {
        vue.afficherStatut("Ouverture du scanner…")
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activite)
            .addOnSuccessListener { intentSender ->
                try {
                    activite.startIntentSenderForResult(
                        intentSender, DEMANDE, null, 0, 0, 0
                    )
                } catch (e: Exception) {
                    vue.afficherStatut(
                        "Scanner : impossible de " +
                            "l'ouvrir (${e.message})."
                    )
                }
            }
            .addOnFailureListener { e ->
                vue.afficherStatut(
                    "Scanner Google (ML Kit) " +
                        "indisponible sur cet appareil : ${e.message}"
                )
            }
    }

    /** Le résultat du scan, livré par l'activité. */
    fun traiterResultat(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            vue.afficherStatut(
                "Scan annulé — rien n'a été " +
                    "enregistré."
            )
            return
        }
        val resultat = try {
            GmsDocumentScanningResult.fromActivityResultIntent(data)
        } catch (e: Exception) {
            vue.afficherStatut(
                "Scan terminé, mais résultat " +
                    "illisible (${e.message})."
            )
            return
        }
        if (resultat == null) {
            vue.afficherStatut(
                "Scan terminé, mais aucun " +
                    "résultat reçu du scanner."
            )
            return
        }
        vue.afficherStatut("Enregistrement du PDF…")
        Thread { enregistrerScan(resultat) }.start()
    }

    // Copie de l'URI éphémère du PDF ML Kit vers le dossier de
    // session (V3 : le PDF seul ; son nombre de pages vient de
    // Pdf.pageCount, lu avant la copie). Roule hors du fil UI ;
    // le statut final revient sur le fil UI dans tous les cas,
    // succès comme échec.
    private fun enregistrerScan(resultat: GmsDocumentScanningResult) {
        try {
            val horodatage = SimpleDateFormat(
                "yyyy-MM-dd_HHmmss", Locale.CANADA_FRENCH
            ).format(Date())
            val racine = File(
                activite.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: activite.filesDir,
                "scans"
            )
            val session = File(racine, "scan-$horodatage")
            if (!session.mkdirs() && !session.isDirectory) {
                throw IllegalStateException(
                    "dossier de session impossible à créer"
                )
            }
            val pdf = resultat.pdf
                ?: throw IllegalStateException(
                    "le scanner n'a pas produit de PDF"
                )
            val nbPages = pdf.pageCount
            val ciblePdf = File(session, "session.pdf")
            val copie = activite.contentResolver.openInputStream(pdf.uri)
                ?.use { entree ->
                    ciblePdf.outputStream().use { sortie ->
                        entree.copyTo(sortie)
                    }
                }
            if (copie == null) {
                throw IllegalStateException(
                    "PDF illisible depuis le scanner"
                )
            }
            val fichiers = listOf(ciblePdf)
            activite.runOnUiThread {
                fichiersDerniereSession = fichiers
                pdfDernierScan = ciblePdf
                pagesDernierScan = nbPages
                sessionDernierScan = session.name
                vue.sessionEnregistree(nbPages, session.name)
            }
        } catch (e: Exception) {
            activite.runOnUiThread {
                vue.afficherStatut(
                    "Échec de l'enregistrement " +
                        "du scan : ${e.message}"
                )
            }
        }
    }

    fun partagerDerniereSession() {
        val fichiers = fichiersDerniereSession
        if (fichiers.isEmpty()) {
            vue.afficherStatut(
                "Rien à partager : aucun scan " +
                    "enregistré dans cette session."
            )
            return
        }
        try {
            val uris = ArrayList<Uri>()
            for (f in fichiers) {
                uris.add(
                    FileProvider.getUriForFile(
                        activite, "${activite.packageName}.fileprovider", f
                    )
                )
            }
            // Depuis la V3, la session ne contient que son PDF :
            // partage simple d'un fichier, type exact. Le chemin
            // multiple reste en place pour partager ce qui
            // existe si la liste contenait autre chose (une
            // ancienne session, par exemple) — rien ne casse.
            val titre: String
            val intent: Intent
            if (uris.size == 1) {
                titre = "Partager le PDF"
                intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (fichiers[0].name.endsWith(".pdf")) {
                        "application/pdf"
                    } else {
                        "*/*"
                    }
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    clipData = ClipData.newUri(
                        activite.contentResolver, "scan", uris[0]
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                titre = "Partager le scan"
                val clip = ClipData.newUri(
                    activite.contentResolver, "scan", uris[0]
                )
                for (i in 1 until uris.size) {
                    clip.addItem(ClipData.Item(uris[i]))
                }
                intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM, uris
                    )
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            activite.startActivity(Intent.createChooser(intent, titre))
        } catch (e: Exception) {
            vue.afficherStatut("Partage impossible : ${e.message}")
        }
    }

    // Choix de l'usage du prochain envoi (boutons de
    // l'écran) : gras = sélectionné.
    fun choisirUsage(valeur: String) {
        usageScan = valeur
        vue.afficherUsage(valeur)
    }

    // Envoi du dernier scan à Muse par le pont (V2 ; le PDF
    // seul depuis la V3). La mécanique vit dans
    // gestion/Scans — le PDF en file locale, run « scan »
    // par la file partagée. Une même session ne part qu'une
    // fois.
    fun envoyerScanAMuse() {
        val session = sessionDernierScan
        val pdf = pdfDernierScan
        if (session == null || pdf == null) {
            vue.afficherStatut(
                "Rien à envoyer : aucun scan " +
                    "enregistré dans cette session."
            )
            return
        }
        if (scanMisEnFile == session) {
            vue.afficherStatut(
                "Ce scan est déjà en file " +
                    "pour Muse."
            )
            return
        }
        scanMisEnFile = session
        vue.setBoutonEnvoyerActif(false)
        vue.afficherStatut("Mise en file du scan…")
        Scans.envoyer(activite, session, pdf, pagesDernierScan, usageScan) {
            enFile, msg ->
            activite.runOnUiThread {
                if (!enFile) scanMisEnFile = null
                vue.afficherStatut(msg)
                vue.setBoutonEnvoyerActif(true)
                rafraichirEnvoisEnAttente()
            }
        }
    }

    // Rafraîchit la section « Envois en attente » depuis le
    // contenu réel de la file des pièces de scans
    // (Scans.piecesEnAttente : session, taille, pages lues
    // dans le PDF, heure de mise en file) et la note du
    // dernier essai de vidange (Scans.dernierEssai). Lecture
    // fichiers hors du fil d'interface ; l'affichage revient
    // sur le fil UI. La section disparaît quand la file est
    // vide. Appelée à l'ouverture de l'onglet Scanner, après
    // chaque tentative de vidange et après chaque nouvel
    // envoi mis en file — aucun état invisible.
    fun rafraichirEnvoisEnAttente() {
        Thread {
            val pieces = Scans.piecesEnAttente(activite)
            val essai = Scans.dernierEssai(activite)
            activite.runOnUiThread {
                if (pieces.isEmpty()) {
                    vue.afficherEnvoisEnAttente(null)
                } else {
                    val sb = StringBuilder()
                    for (p in pieces) {
                        val taille = String.format(
                            Locale.CANADA_FRENCH, "%.1f",
                            p.tailleOctets / 1_000_000.0
                        )
                        sb.append("• ").append(p.session)
                            .append(" — ").append(taille).append(" Mo")
                        if (p.nbPages != null) {
                            sb.append(", ").append(p.nbPages)
                                .append(" page(s)")
                        }
                        sb.append(", en attente depuis ")
                            .append(formatDepuis(p.depuisMs))
                            .append("\n")
                    }
                    if (essai != null) {
                        val heure = SimpleDateFormat(
                            "HH:mm", Locale.CANADA_FRENCH
                        ).format(Date(essai.first))
                        if (essai.second > 0) {
                            sb.append(
                                "Dernier essai : refusé par le pont " +
                                    "(code ${essai.second}) à $heure."
                            )
                        } else {
                            sb.append(
                                "Dernier essai à $heure : échec " +
                                    "d'envoi (réseau ou pont " +
                                    "injoignable)."
                            )
                        }
                    } else {
                        sb.append("En attente d'envoi.")
                    }
                    vue.afficherEnvoisEnAttente(sb.toString().trimEnd())
                }
            }
        }.start()
    }

    // « 21:12 » si c'est aujourd'hui, « 16 nov. à 21:12 »
    // sinon — pour le « en attente depuis … » de la section.
    private fun formatDepuis(ms: Long): String {
        val maintenant = Calendar.getInstance()
        val depuis = Calendar.getInstance().apply {
            timeInMillis = ms
        }
        return if (maintenant.get(Calendar.YEAR) ==
            depuis.get(Calendar.YEAR) &&
            maintenant.get(Calendar.DAY_OF_YEAR) ==
            depuis.get(Calendar.DAY_OF_YEAR)
        ) {
            SimpleDateFormat(
                "HH:mm", Locale.CANADA_FRENCH
            ).format(Date(ms))
        } else {
            SimpleDateFormat(
                "d MMM 'à' HH:mm", Locale.CANADA_FRENCH
            ).format(Date(ms))
        }
    }

    companion object {
        /** Code de requête du scan de l'onglet (résultat d'activité). */
        const val DEMANDE = 62
    }
}
