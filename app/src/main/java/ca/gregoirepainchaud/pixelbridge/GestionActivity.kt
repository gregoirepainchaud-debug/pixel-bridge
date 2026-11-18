package ca.gregoirepainchaud.pixelbridge

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import ca.gregoirepainchaud.pixelbridge.gestion.PontFile
import ca.gregoirepainchaud.pixelbridge.gestion.Scans
import ca.gregoirepainchaud.pixelbridge.oreille.OreilleActivity
import ca.gregoirepainchaud.pixelbridge.gestion.Textos
import ca.gregoirepainchaud.pixelbridge.service.BridgeService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gestion Android v1 (décision de Grégoire, 2026-11-17) : l'app
 * de test audio devient le cadre de Gestion. On ne refait pas
 * Gestion — l'app web continue d'exister, la base est en ligne,
 * le serveur reste la vérité. Cinq onglets :
 *
 * - « Gestion » : le site actuel, tel quel, en WebView plein
 *   écran (app.gregoirepainchaud.ca). La WebView expose à la
 *   page un pont natif minimal, « GestionAndroid » (voir
 *   PontScannerRecu plus bas) : la section Reçus du site s'en
 *   sert, quand il est présent, pour lancer le scanner
 *   documentaire ML Kit et recevoir l'image du reçu — sous
 *   Android seulement ; sur le web, la page ne voit pas le
 *   pont et son scanner reste intact.
 * - « Oreille » : l'écran d'écoute minimal (OreilleActivity) —
 *   un seul gros bouton on/off ; dessous, la mécanique du banc
 *   (flux, sentinelle, épisodes, correction, clips au pont,
 *   back door). L'ancien écran complet du banc reste dans le
 *   code, caché, relié à aucun élément visible.
 * - « Textos » : les textos reçus sont lus et poussés à Muse
 *   par le pont (module gestion/Textos, filigrane local).
 * - « Scanner » : scanner documentaire Google ML Kit (V1,
 *   2026-11-17) — le flux de scan de Play Services fait
 *   détection des bords, redressement, filtres et nettoyage ;
 *   le PDF de session est copié tout de suite dans un
 *   dossier de session horodaté sur l'appareil, prêt à
 *   partager. Depuis la V2 (2026-11-17), l'écran de résultat
 *   envoie aussi le scan à Muse par le pont (module
 *   gestion/Scans, cas « scan », étiquette d'usage Livre /
 *   Autre choisie au moment de l'envoi — l'usage « Reçu » a
 *   disparu le 2026-11-17 : les reçus ont leur porte dans
 *   l'onglet Reçus du site, via le pont natif de la WebView).
 *   Depuis la V3 (2026-11-17), PDF seulement : le test
 *   terrain a prouvé que l'image embarquée dans le PDF ML Kit
 *   est plus nette que les JPEG séparés, qui ne sont plus ni
 *   demandés ni conservés.
 * - « Réglages » : adresse du pont, modèles Whisper, fils — et
 *   le bouton « Mettre à jour l'app » (règle permanente de
 *   Grégoire : nos apps ont toujours un bouton pour les updater,
 *   quelque part ; ici, les Réglages).
 *
 * Les réglages partagent le fichier de préférences « bench »
 * avec l'écran Oreille : une seule source pour l'adresse du
 * pont et les noms de modèles.
 */
class GestionActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("bench", MODE_PRIVATE) }

    private lateinit var contenu: FrameLayout
    private var webGestion: WebView? = null
    private lateinit var vueTextos: LinearLayout
    private lateinit var vueScanner: ScrollView
    private lateinit var vueReglages: ScrollView
    private lateinit var statutTextos: TextView
    private lateinit var statutScanner: TextView
    private lateinit var boutonPartager: Button
    private var fichiersDerniereSession: List<File> = emptyList()
    private var pdfDernierScan: File? = null
    private var pagesDernierScan = 0
    private lateinit var vueEnvoiScan: LinearLayout
    private lateinit var boutonEnvoyerScan: Button
    private val boutonsUsage = LinkedHashMap<String, Button>()
    private var usageScan = "livre"
    private var sessionDernierScan: String? = null
    private var scanMisEnFile: String? = null

    // Pont natif du scanner de reçus (WebView Gestion) : vrai
    // pendant qu'un scan lancé par la page est en cours —
    // un deuxième appel de la page est alors ignoré.
    @Volatile
    private var scanRecuEnCours = false
    private lateinit var statutMaj: TextView
    private lateinit var boutonMaj: Button
    private val boutonsOnglets = LinkedHashMap<String, Button>()
    private var ongletCourant = "gestion"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, BridgeService::class.java))

        // Nettoyage : un paquet d'installation resté d'une mise à
        // jour précédente est effacé au démarrage.
        fichierMaj().delete()

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contenu = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        racine.addView(contenu)

        construireVueTextos()
        construireVueScanner()
        construireVueReglages()
        contenu.addView(vueTextos)
        contenu.addView(vueScanner)
        contenu.addView(vueReglages)

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for ((cle, libelle) in listOf(
            "gestion" to "Gestion",
            "oreille" to "Oreille",
            "textos" to "Textos",
            "scanner" to "Scanner",
            "reglages" to "Réglages"
        )) {
            val b = Button(this).apply {
                text = libelle
                // Cinq onglets : police réduite pour que les
                // libellés tiennent sur une ligne, à une main.
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener { allerA(cle) }
            }
            boutonsOnglets[cle] = b
            barre.addView(b)
        }
        racine.addView(barre)
        setContentView(racine)
        afficherOnglet("gestion")

        // File du pont : ce qui attend (textos, runs, pièces de
        // scan) repart dès l'ouverture si le réseau répond.
        Thread { PontFile.vider(this) }.start()
        Thread { Scans.viderPieces(this) }.start()

        // Textos : vérification à l'ouverture, si la permission
        // est déjà accordée. Sinon, l'onglet Textos la demandera.
        if (checkSelfPermission(android.Manifest.permission.READ_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Textos.verifier(this) { msg ->
                runOnUiThread { statutTextos.text = msg }
            }
        }
    }

    // ----------------------------------------------------------------
    // Navigation par onglets. « Oreille » est un écran-activité :
    // l'onglet le lance, le retour système ramène ici.
    // ----------------------------------------------------------------

    private fun allerA(cle: String) {
        if (cle == "oreille") {
            startActivity(Intent(this, OreilleActivity::class.java))
            return
        }
        afficherOnglet(cle)
    }

    private fun afficherOnglet(cle: String) {
        ongletCourant = cle
        if (cle == "gestion" && webGestion == null) {
            webGestion = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                // Pont natif du scanner de reçus : présent
                // seulement dans l'app — la page web ne le
                // voit jamais (voir PontScannerRecu).
                addJavascriptInterface(
                    PontScannerRecu(), "GestionAndroid"
                )
                loadUrl("https://app.gregoirepainchaud.ca")
            }
            contenu.addView(webGestion)
        }
        webGestion?.visibility = if (cle == "gestion") View.VISIBLE else View.GONE
        vueTextos.visibility = if (cle == "textos") View.VISIBLE else View.GONE
        vueScanner.visibility =
            if (cle == "scanner") View.VISIBLE else View.GONE
        vueReglages.visibility =
            if (cle == "reglages") View.VISIBLE else View.GONE
        for ((c, b) in boutonsOnglets) {
            b.typeface = if (c == cle) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (cle == "textos") rafraichirStatutTextos()
    }

    override fun onBackPressed() {
        val web = webGestion
        if (ongletCourant == "gestion" && web != null && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // ----------------------------------------------------------------
    // Onglet Textos.
    // ----------------------------------------------------------------

    private fun construireVueTextos() {
        vueTextos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        vueTextos.addView(TextView(this).apply {
            text = "Textos reçus → poussés à Muse par le pont.\n" +
                "Seuls les textos reçus après l'installation partent " +
                "(filigrane local) ; chacun est mis en file sur " +
                "l'appareil avant l'envoi, rien ne se perd hors ligne."
            textSize = 13f
        })
        statutTextos = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 24)
        }
        vueTextos.addView(statutTextos)
        val bouton = Button(this).apply {
            text = "Vérifier les textos"
            setOnClickListener { verifierTextosAvecPermission() }
        }
        vueTextos.addView(bouton)
    }

    private fun rafraichirStatutTextos() {
        if (checkSelfPermission(android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statutTextos.text = "Textos : la permission de lire les " +
                "textos n'est pas encore accordée — pèse « Vérifier " +
                "les textos » pour la demander."
        }
    }

    private fun verifierTextosAvecPermission() {
        if (checkSelfPermission(android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_SMS), DEMANDE_TEXTOS
            )
            return
        }
        Textos.verifier(this) { msg ->
            runOnUiThread { statutTextos.text = msg }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != DEMANDE_TEXTOS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Textos.verifier(this) { msg ->
                runOnUiThread { statutTextos.text = msg }
            }
        } else {
            statutTextos.text = "Textos : permission refusée — aucun " +
                "texto ne sera lu ni poussé."
        }
    }

    // ----------------------------------------------------------------
    // Onglet Scanner (V1, 2026-11-17) : le flux de scan complet
    // est celui de Google ML Kit, fourni par Play Services —
    // détection des bords, correction de perspective, rotation,
    // filtres et nettoyage sont faits là, pas ici. Aucune
    // permission CAMERA : le scanner roule dans le processus
    // Play Services. Au retour, l'URI du PDF de ML Kit est
    // ÉPHÉMÈRE : on copie le PDF immédiatement dans un dossier
    // de session horodaté du stockage propre à l'app
    // (Documents/scans/scan-<horodatage>/session.pdf), puis on
    // propose le partage et, depuis la V2, l'envoi à Muse par
    // le pont (module gestion/Scans). Depuis la V3, PDF
    // seulement — voir le cartouche de classe.
    // ----------------------------------------------------------------

    private fun construireVueScanner() {
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun ajout(vue: View) {
            vue.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            colonne.addView(vue)
        }
        ajout(TextView(this).apply {
            text = "Scanner un document, page par page (20 pages " +
                "max par session). Le scanner de Google détecte " +
                "les bords, redresse, nettoie les ombres et " +
                "applique ses filtres. Le PDF reste sur " +
                "l'appareil, dans un dossier de session " +
                "horodaté, prêt à partager ou à envoyer à " +
                "Muse par le pont."
            textSize = 14f
        })
        val boutonScan = Button(this).apply {
            text = "Scanner"
            textSize = 22f
            setPadding(0, 40, 0, 40)
            setOnClickListener { lancerScan() }
        }
        ajout(boutonScan)
        statutScanner = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24, 0, 24)
        }
        ajout(statutScanner)
        boutonPartager = Button(this).apply {
            text = "Partager le PDF"
            isEnabled = false
            setOnClickListener { partagerDerniereSession() }
        }
        ajout(boutonPartager)
        ajout(Button(this).apply {
            text = "Scanner à nouveau"
            setOnClickListener { lancerScan() }
        })

        // Envoi à Muse par le pont (V2) : visible seulement
        // quand un scan est enregistré. Choix d'usage en deux
        // gros boutons (« Livre » par défaut), puis l'envoi —
        // tout part en file locale d'abord, donc hors ligne OK.
        // L'usage « Reçu » n'existe plus ici (2026-11-17) :
        // les reçus se scannent depuis l'onglet Reçus du site,
        // par le pont natif de la WebView.
        vueEnvoiScan = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 32, 0, 0)
        }
        vueEnvoiScan.addView(TextView(this).apply {
            text = "Envoyer ce scan à Muse par le pont " +
                "(PDF pleine résolution, même file que les " +
                "textos et les appels). Pour quel usage ?"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })
        val rangeeUsage = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        for ((valeur, libelle) in listOf(
            "livre" to "Livre",
            "autre" to "Autre"
        )) {
            val b = Button(this).apply {
                text = libelle
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener {
                    usageScan = valeur
                    rafraichirBoutonsUsage()
                }
            }
            boutonsUsage[valeur] = b
            rangeeUsage.addView(b)
        }
        vueEnvoiScan.addView(rangeeUsage)
        boutonEnvoyerScan = Button(this).apply {
            text = "Envoyer à Muse"
            textSize = 20f
            setPadding(0, 32, 0, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { envoyerScanAMuse() }
        }
        vueEnvoiScan.addView(boutonEnvoyerScan)
        ajout(vueEnvoiScan)
        rafraichirBoutonsUsage()

        vueScanner = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            addView(colonne)
        }
    }

    private fun lancerScan() {
        statutScanner.text = "Ouverture du scanner…"
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                try {
                    startIntentSenderForResult(
                        intentSender, DEMANDE_SCAN, null, 0, 0, 0
                    )
                } catch (e: Exception) {
                    statutScanner.text = "Scanner : impossible de " +
                        "l'ouvrir (${e.message})."
                }
            }
            .addOnFailureListener { e ->
                statutScanner.text = "Scanner Google (ML Kit) " +
                    "indisponible sur cet appareil : ${e.message}"
            }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DEMANDE_SCAN_RECU) {
            traiterResultatScanRecu(resultCode, data)
            return
        }
        if (requestCode != DEMANDE_SCAN) return
        if (resultCode != RESULT_OK) {
            statutScanner.text = "Scan annulé — rien n'a été " +
                "enregistré."
            return
        }
        val resultat = try {
            GmsDocumentScanningResult.fromActivityResultIntent(data)
        } catch (e: Exception) {
            statutScanner.text = "Scan terminé, mais résultat " +
                "illisible (${e.message})."
            return
        }
        if (resultat == null) {
            statutScanner.text = "Scan terminé, mais aucun " +
                "résultat reçu du scanner."
            return
        }
        statutScanner.text = "Enregistrement du PDF…"
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
                getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: filesDir,
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
            val copie = contentResolver.openInputStream(pdf.uri)
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
            runOnUiThread {
                fichiersDerniereSession = fichiers
                pdfDernierScan = ciblePdf
                pagesDernierScan = nbPages
                boutonPartager.isEnabled = true
                sessionDernierScan = session.name
                vueEnvoiScan.visibility = View.VISIBLE
                statutScanner.text = "Scan enregistré : " +
                    "$nbPages page(s) (PDF).\n" +
                    "Dossier : ${session.name}\n" +
                    "Le PDF est sur l'appareil, " +
                    "prêt à partager."
            }
        } catch (e: Exception) {
            runOnUiThread {
                statutScanner.text = "Échec de l'enregistrement " +
                    "du scan : ${e.message}"
            }
        }
    }

    // ----------------------------------------------------------------
    // Pont natif du scanner de reçus (2026-11-17, demande de
    // Grégoire) : la section Reçus du site, quand elle roule
    // dans l'app Android, lance le scanner documentaire ML Kit
    // au lieu de la caméra brute du web. Le site reste intact
    // sur le web : le pont n'existe que dans cette WebView-ci.
    //
    // Contrat, volontairement minimal :
    // - la page teste la présence du pont :
    //   typeof window.GestionAndroid !== "undefined" ;
    // - elle appelle window.GestionAndroid.scannerRecu() ;
    // - le natif répond en appelant, une fois le scan terminé,
    //   window.GestionScanRecu.retour(objet) avec :
    //     {statut: "ok", pages: N, base64: "<JPEG>"} — une page
    //       part telle quelle (octets ML Kit intacts) ; un
    //       reçu en plusieurs pages est ASSEMBLÉ ici, en un
    //       seul JPEG vertical (voir assemblerPagesRecu) :
    //       la page reçoit donc toujours UNE image, qu'elle
    //       téléverse par son chemin existant (aperçu, « Next »,
    //       POST /api/recus/scanner) comme une photo choisie ;
    //     {statut: "annule"} — rien n'a été scanné ;
    //     {statut: "erreur", message: "…"} — échec franc.
    // L'assemblage côté natif garde le serveur 100 % intact :
    // un reçu = une image, comme depuis toujours en aval
    // (préparation, révision, dépense).
    // ----------------------------------------------------------------

    inner class PontScannerRecu {
        @JavascriptInterface
        fun scannerRecu() {
            // Les méthodes d'un JavascriptInterface roulent
            // sur un fil d'arrière-plan : le lancement du
            // scanner repasse sur le fil d'interface.
            runOnUiThread { lancerScanRecu() }
        }
    }

    private fun lancerScanRecu() {
        if (scanRecuEnCours) return
        scanRecuEnCours = true
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                try {
                    startIntentSenderForResult(
                        intentSender, DEMANDE_SCAN_RECU, null, 0, 0, 0
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

    private fun traiterResultatScanRecu(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) {
            scanRecuEnCours = false
            livrerScanRecu(JSONObject().put("statut", "annule").toString())
            return
        }
        val resultat = try {
            GmsDocumentScanningResult.fromActivityResultIntent(data)
        } catch (e: Exception) {
            null
        }
        val pages = resultat?.pages
        if (pages.isNullOrEmpty()) {
            scanRecuEnCours = false
            livrerScanRecu(
                JSONObject()
                    .put("statut", "erreur")
                    .put("message", "résultat du scanner illisible")
                    .toString()
            )
            return
        }
        // Les URI des pages ML Kit sont éphémères : lecture et
        // assemblage tout de suite, hors du fil d'interface.
        Thread { traiterPagesScanRecu(pages.map { it.imageUri }) }.start()
    }

    private fun traiterPagesScanRecu(uris: List<Uri>) {
        try {
            val octets = if (uris.size == 1) {
                contentResolver.openInputStream(uris[0])?.use {
                    it.readBytes()
                } ?: throw IllegalStateException("page illisible")
            } else {
                assemblerPagesRecu(uris)
            }
            val json = JSONObject()
                .put("statut", "ok")
                .put("pages", uris.size)
                .put(
                    "base64",
                    Base64.encodeToString(octets, Base64.NO_WRAP)
                )
                .toString()
            livrerScanRecu(json)
        } catch (e: Exception) {
            livrerScanRecu(
                JSONObject()
                    .put("statut", "erreur")
                    .put(
                        "message",
                        "lecture des pages impossible (${e.message})"
                    )
                    .toString()
            )
        } finally {
            scanRecuEnCours = false
        }
    }

    // Assemblage d'un reçu multi-pages : les pages ML Kit sont
    // les tranches successives du reçu, dans l'ordre de
    // capture — on les empile verticalement en une seule image,
    // centrées sur la plus large. Plafonds de bon sens pour la
    // mémoire du téléphone : largeur ramenée à 1500 px au plus
    // (un reçu reste parfaitement lisible), hauteur totale
    // ramenée à 14 000 px au plus par une échelle globale.
    // Sortie JPEG qualité 92, comme la filière photo du site.
    private fun assemblerPagesRecu(uris: List<Uri>): ByteArray {
        val largeurMax = 1500
        val hauteurMax = 14000
        val bitmaps = ArrayList<Bitmap>()
        try {
            for (uri in uris) {
                val bornes = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bornes)
                }
                if (bornes.outWidth <= 0) {
                    throw IllegalStateException("page illisible")
                }
                var echantillon = 1
                while (bornes.outWidth / (echantillon * 2) >=
                    largeurMax
                ) {
                    echantillon *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = echantillon
                }
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: throw IllegalStateException("page illisible")
                bitmaps.add(bmp)
            }
            val largeur = bitmaps.maxOf { it.width }
            val hauteurTotale = bitmaps.sumOf { it.height }
            val echelle = if (hauteurTotale > hauteurMax) {
                hauteurMax.toFloat() / hauteurTotale
            } else {
                1f
            }
            val largeurFinale =
                (largeur * echelle).toInt().coerceAtLeast(1)
            val hauteurFinale =
                (hauteurTotale * echelle).toInt().coerceAtLeast(1)
            val toile = Bitmap.createBitmap(
                largeurFinale, hauteurFinale, Bitmap.Config.ARGB_8888
            )
            try {
                val canvas = Canvas(toile)
                canvas.drawColor(Color.WHITE)
                var y = 0f
                for (bmp in bitmaps) {
                    val w = bmp.width * echelle
                    val h = bmp.height * echelle
                    val dst = RectF(
                        (largeurFinale - w) / 2f, y,
                        (largeurFinale + w) / 2f, y + h
                    )
                    canvas.drawBitmap(bmp, null, dst, null)
                    y += h
                }
                val sortie = ByteArrayOutputStream()
                toile.compress(Bitmap.CompressFormat.JPEG, 92, sortie)
                return sortie.toByteArray()
            } finally {
                toile.recycle()
            }
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }

    private fun livrerScanRecu(json: String) {
        runOnUiThread {
            webGestion?.evaluateJavascript(
                "window.GestionScanRecu && " +
                    "window.GestionScanRecu.retour($json)",
                null
            )
        }
    }

    private fun partagerDerniereSession() {
        val fichiers = fichiersDerniereSession
        if (fichiers.isEmpty()) {
            statutScanner.text = "Rien à partager : aucun scan " +
                "enregistré dans cette session."
            return
        }
        try {
            val uris = ArrayList<Uri>()
            for (f in fichiers) {
                uris.add(
                    FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", f
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
                        contentResolver, "scan", uris[0]
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                titre = "Partager le scan"
                val clip = ClipData.newUri(
                    contentResolver, "scan", uris[0]
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
            startActivity(Intent.createChooser(intent, titre))
        } catch (e: Exception) {
            statutScanner.text = "Partage impossible : ${e.message}"
        }
    }

    // ----------------------------------------------------------------
    // Envoi du dernier scan à Muse par le pont (V2 ; le PDF
    // seul depuis la V3) : le choix d'usage (gras =
    // sélectionné, « livre » par défaut) puis le bouton
    // d'envoi. La mécanique vit dans gestion/Scans — le PDF en
    // file locale, run « scan » par la file partagée.
    // Une même session ne part qu'une fois.
    // ----------------------------------------------------------------

    private fun rafraichirBoutonsUsage() {
        for ((valeur, b) in boutonsUsage) {
            b.typeface =
                if (valeur == usageScan) {
                    Typeface.DEFAULT_BOLD
                } else {
                    Typeface.DEFAULT
                }
        }
    }

    private fun envoyerScanAMuse() {
        val session = sessionDernierScan
        val pdf = pdfDernierScan
        if (session == null || pdf == null) {
            statutScanner.text = "Rien à envoyer : aucun scan " +
                "enregistré dans cette session."
            return
        }
        if (scanMisEnFile == session) {
            statutScanner.text = "Ce scan est déjà en file " +
                "pour Muse."
            return
        }
        scanMisEnFile = session
        boutonEnvoyerScan.isEnabled = false
        statutScanner.text = "Mise en file du scan…"
        Scans.envoyer(this, session, pdf, pagesDernierScan, usageScan) {
            enFile, msg ->
            runOnUiThread {
                if (!enFile) scanMisEnFile = null
                statutScanner.text = msg
                boutonEnvoyerScan.isEnabled = true
            }
        }
    }

    // ----------------------------------------------------------------
    // Onglet Réglages : pont, modèles, fils, mise à jour.
    // Chaque champ s'enregistre dans les préférences à mesure
    // qu'on tape — l'écran Oreille lit les mêmes clés.
    // ----------------------------------------------------------------

    private fun champReglage(
        valeur: String, hint: String, cle: String, numerique: Boolean = false
    ): EditText {
        val e = EditText(this).apply {
            setText(valeur)
            this.hint = hint
            setSingleLine(true)
            if (numerique) {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        }
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit()
                    .putString(cle, s?.toString()?.trim() ?: "")
                    .apply()
            }

            override fun beforeTextChanged(
                s: CharSequence?, debut: Int, compte: Int, apres: Int
            ) {
            }

            override fun onTextChanged(
                s: CharSequence?, debut: Int, compte: Int, avant: Int
            ) {
            }
        })
        return e
    }

    private fun construireVueReglages() {
        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun ajout(vue: View) {
            vue.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            colonne.addView(vue)
        }
        ajout(TextView(this).apply {
            text = "Réglages — enregistrés à mesure, partagés avec " +
                "l'écran Oreille. Les fichiers de modèles se lisent " +
                "dans Téléchargements ou le dossier de l'app : aucun " +
                "déplacement nécessaire."
            textSize = 13f
        })
        ajout(champReglage(
            prefs.getString("pont_url", "") ?: "",
            "Adresse du pont (URL complète, à coller une fois)",
            "pont_url"
        ))
        ajout(champReglage(
            prefs.getString("modele_flux", "ggml-base.bin") ?: "ggml-base.bin",
            "Modèle de flux (.bin) — transcription en direct",
            "modele_flux"
        ))
        ajout(champReglage(
            prefs.getString("modele_correction", "ggml-small.bin")
                ?: "ggml-small.bin",
            "Modèle de correction (.bin) — première passe sur les épisodes",
            "modele_correction"
        ))
        ajout(champReglage(
            prefs.getString("modele_relecture", "ggml-large-v3-turbo.bin")
                ?: "ggml-large-v3-turbo.bin",
            "Modèle de relecture (.bin) — back door, passages doutés",
            "modele_relecture"
        ))
        ajout(champReglage(
            prefs.getString("fils", "4") ?: "4",
            "Nombre de fils pour Whisper",
            "fils", numerique = true
        ))
        boutonMaj = Button(this).apply {
            text = "Mettre à jour l'app (dernier build)"
            setOnClickListener { lancerMiseAJour() }
        }
        ajout(boutonMaj)
        statutMaj = TextView(this).apply {
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        ajout(statutMaj)

        vueReglages = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            addView(colonne)
        }
    }

    // ----------------------------------------------------------------
    // Mise à jour intégrée (mécanisme hérité de l'ancien écran
    // d'accueil) : télécharge le dernier build vert publié
    // (release bench-latest), lance l'installateur système
    // par-dessus l'installation courante (signature stable),
    // efface le paquet. Aucun fichier ne traîne dans
    // Téléchargements.
    // ----------------------------------------------------------------

    private fun fichierMaj(): File =
        File(File(cacheDir, "maj").apply { mkdirs() }, "app-debug.apk")

    private fun lancerMiseAJour() {
        if (!packageManager.canRequestPackageInstalls()) {
            statutMaj.text = "Première fois : autorise l'installation " +
                "depuis cette app dans l'écran qui s'ouvre, puis " +
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

    companion object {
        private const val DEMANDE_TEXTOS = 61
        private const val DEMANDE_SCAN = 62
        private const val DEMANDE_SCAN_RECU = 63
    }
}
