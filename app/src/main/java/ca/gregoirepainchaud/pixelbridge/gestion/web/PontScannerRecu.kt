package ca.gregoirepainchaud.pixelbridge.gestion.web

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import ca.gregoirepainchaud.pixelbridge.gestion.Scans
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Pont natif du scanner de reçus (2026-11-17, demande de
// Grégoire) : la section Reçus du site, quand elle roule dans
// l'app Android, lance le scanner documentaire ML Kit au lieu
// de la caméra brute du web. Le site reste intact sur le web :
// le pont n'existe que dans cette WebView-ci.
//
// Mécanique resserrée le 2026-11-17 au soir (décision de
// Grégoire) : « une fois scanné et que je sors du scan,
// inutile de peser sur envoyer, le scan part tout seul par
// le pont » — à la sortie du scanner, le natif envoie la
// session au pont serveur par la machinerie exacte de
// l'onglet Scanner (module gestion/Scans : PDF de session,
// file locale d'abord, run « scan »), usage figé à « recu »
// — comme le reçu McDonald's de 20 h 36, ensuite ingéré par
// Muse dans la banque de reçus de Gestion. La page ne reçoit
// QUE le statut, jamais l'image : aucun aperçu, aucun
// « Next », aucun téléversement vers /api/recus/scanner dans
// ce chemin.
//
// Contrat, volontairement minimal :
// - la page teste la présence du pont :
//   typeof window.GestionAndroid !== "undefined" ;
// - elle appelle window.GestionAndroid.scannerRecu() ;
// - le natif répond en appelant, une fois l'envoi tenté,
//   window.GestionScanRecu.retour(objet) avec :
//     {statut: "envoye"} — pièce et run acceptés par le pont ;
//     {statut: "en_file"} — tout est en file locale, partira
//       au retour du réseau (jamais un faux « envoyé ») ;
//     {statut: "annule"} — rien n'a été scanné ;
//     {statut: "erreur", message: "…"} — échec franc.
// La session est aussi copiée en durable (Documents/scans/,
// comme l'onglet Scanner) avant l'envoi : aucun reçu ne se
// perd, même si la file devait échouer.
//
// Chantier structure 0.8 : ce pont vivait en classe interne de
// GestionActivity ; il est ici, inchangé, avec ses deux
// dépendances explicites — la WebView à qui livrer le statut
// (fournie au moment de livrer) et le signal qu'un envoi vient
// de passer en file (l'onglet Scanner rafraîchit son état).
// ---------------------------------------------------------------------------

class PontScannerRecu(
    private val activite: Activity,
    private val fournirWebView: () -> WebView?,
    private val surEnvoiTermine: () -> Unit
) {

    // Vrai pendant qu'un scan lancé par la page est en cours —
    // un deuxième appel de la page est alors ignoré.
    @Volatile
    private var scanRecuEnCours = false

    @JavascriptInterface
    fun scannerRecu() {
        // Les méthodes d'un JavascriptInterface roulent
        // sur un fil d'arrière-plan : le lancement du
        // scanner repasse sur le fil d'interface.
        activite.runOnUiThread { lancerScanRecu() }
    }

    private fun lancerScanRecu() {
        if (scanRecuEnCours) return
        scanRecuEnCours = true
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
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
                    scanRecuEnCours = false
                    livrerScanRecu(
                        JSONObject()
                            .put("statut", "erreur")
                            .put(
                                "message",
                                "impossible d'ouvrir le scanner " +
                                    "(${e.message})"
                            )
                            .toString()
                    )
                }
            }
            .addOnFailureListener { e ->
                scanRecuEnCours = false
                livrerScanRecu(
                    JSONObject()
                        .put("statut", "erreur")
                        .put(
                            "message",
                            "scanner Google (ML Kit) indisponible " +
                                "sur cet appareil : ${e.message}"
                        )
                        .toString()
                )
            }
    }

    /** Le résultat du scan de reçu, livré par l'activité. */
    fun traiterResultat(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            scanRecuEnCours = false
            livrerScanRecu(JSONObject().put("statut", "annule").toString())
            return
        }
        val resultat = try {
            GmsDocumentScanningResult.fromActivityResultIntent(data)
        } catch (e: Exception) {
            null
        }
        val pdf = resultat?.pdf
        if (pdf == null) {
            scanRecuEnCours = false
            livrerScanRecu(
                JSONObject()
                    .put("statut", "erreur")
                    .put("message", "résultat du scanner illisible")
                    .toString()
            )
            return
        }
        // L'URI du PDF ML Kit est éphémère : copie durable et
        // envoi au pont tout de suite, hors du fil d'interface.
        Thread {
            enregistrerEtEnvoyerScanRecu(pdf.uri, pdf.pageCount)
        }.start()
    }

    // Copie durable de la session (même racine et même nom que
    // l'onglet Scanner : Documents/scans/scan-<horodatage>/
    // session.pdf), puis envoi au pont par la machinerie
    // partagée (Scans.envoyerDetail), usage figé à « recu ».
    // Le statut livré à la page dit exactement où en est le
    // reçu : parti au pont, en file, ou erreur franche.
    // scanRecuEnCours retombe à la toute fin, quel que soit le
    // sort du scan — la page peut relancer un scan dès que le
    // statut est livré.
    private fun enregistrerEtEnvoyerScanRecu(uriPdf: Uri, nbPages: Int) {
        val session: String
        val ciblePdf: File
        try {
            val horodatage = SimpleDateFormat(
                "yyyy-MM-dd_HHmmss", Locale.CANADA_FRENCH
            ).format(Date())
            val racine = File(
                activite.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: activite.filesDir,
                "scans"
            )
            val dossier = File(racine, "scan-$horodatage")
            if (!dossier.mkdirs() && !dossier.isDirectory) {
                throw IllegalStateException(
                    "dossier de session impossible à créer"
                )
            }
            ciblePdf = File(dossier, "session.pdf")
            val copie = activite.contentResolver.openInputStream(uriPdf)
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
            session = dossier.name
        } catch (e: Exception) {
            scanRecuEnCours = false
            livrerScanRecu(
                JSONObject()
                    .put("statut", "erreur")
                    .put(
                        "message",
                        "enregistrement du scan impossible " +
                            "(${e.message})"
                    )
                    .toString()
            )
            return
        }
        Scans.envoyerDetail(
            activite, session, ciblePdf, nbPages, "recu"
        ) { etat, message ->
            scanRecuEnCours = false
            val json = when (etat) {
                Scans.ENVOI_ENVOYE ->
                    JSONObject().put("statut", "envoye")
                Scans.ENVOI_EN_FILE ->
                    JSONObject().put("statut", "en_file")
                else ->
                    JSONObject()
                        .put("statut", "erreur")
                        .put("message", message)
            }
            livrerScanRecu(json.toString())
            // Un reçu parti en file attend dans la même file
            // que les scans de l'onglet Scanner : son état
            // doit y être visible aussi.
            surEnvoiTermine()
        }
    }

    private fun livrerScanRecu(json: String) {
        activite.runOnUiThread {
            fournirWebView()?.evaluateJavascript(
                "window.GestionScanRecu && " +
                    "window.GestionScanRecu.retour($json)",
                null
            )
        }
    }

    companion object {
        /** Code de requête du scan de reçu (résultat d'activité). */
        const val DEMANDE = 63
    }
}
