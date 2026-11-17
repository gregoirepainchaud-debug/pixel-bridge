package ca.gregoirepainchaud.pixelbridge

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
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
 *   écran (app.gregoirepainchaud.ca).
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
 *   les pages sont copiées tout de suite dans un dossier de
 *   session horodaté sur l'appareil, prêtes à partager.
 *   Depuis la V2 (2026-11-17), l'écran de résultat envoie
 *   aussi le scan à Muse par le pont (module gestion/Scans,
 *   cas « scan », étiquette d'usage Livre / Reçu / Autre
 *   choisie au moment de l'envoi).
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
    private lateinit var vueEnvoiScan: LinearLayout
    private lateinit var boutonEnvoyerScan: Button
    private val boutonsUsage = LinkedHashMap<String, Button>()
    private var usageScan = "livre"
    private var sessionDernierScan: String? = null
    private var scanMisEnFile: String? = null
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
    // Play Services. Au retour, les URI de ML Kit sont
    // ÉPHÉMÈRES : on copie les pages immédiatement dans un
    // dossier de session horodaté du stockage propre à l'app
    // (Documents/scans/scan-<horodatage>/page-001.jpg… +
    // session.pdf), puis on propose le partage et, depuis la
    // V2, l'envoi à Muse par le pont (module gestion/Scans).
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
                "applique ses filtres. Les pages restent sur " +
                "l'appareil, dans un dossier de session " +
                "horodaté, prêtes à partager ou à envoyer à " +
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
            text = "Partager les pages"
            isEnabled = false
            setOnClickListener { partagerDerniereSession() }
        }
        ajout(boutonPartager)
        ajout(Button(this).apply {
            text = "Scanner à nouveau"
            setOnClickListener { lancerScan() }
        })

        // Envoi à Muse par le pont (V2) : visible seulement
        // quand un scan est enregistré. Choix d'usage en trois
        // gros boutons (« Livre » par défaut), puis l'envoi —
        // tout part en file locale d'abord, donc hors ligne OK.
        vueEnvoiScan = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 32, 0, 0)
        }
        vueEnvoiScan.addView(TextView(this).apply {
            text = "Envoyer ce scan à Muse par le pont " +
                "(pages pleine résolution, même file que les " +
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
            "reçu" to "Reçu",
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
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
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
        statutScanner.text = "Enregistrement des pages…"
        Thread { enregistrerScan(resultat) }.start()
    }

    // Copie des URI éphémères de ML Kit vers le dossier de
    // session. Roule hors du fil UI ; le statut final revient
    // sur le fil UI dans tous les cas, succès comme échec.
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
            val fichiers = mutableListOf<File>()
            var numero = 0
            for (page in resultat.pages ?: emptyList()) {
                numero++
                val cible = File(
                    session,
                    String.format(Locale.US, "page-%03d.jpg", numero)
                )
                val copie = contentResolver.openInputStream(page.imageUri)
                    ?.use { entree ->
                        cible.outputStream().use { sortie ->
                            entree.copyTo(sortie)
                        }
                    }
                if (copie == null) {
                    throw IllegalStateException(
                        "page $numero illisible depuis le scanner"
                    )
                }
                fichiers.add(cible)
            }
            resultat.pdf?.let { pdf ->
                val ciblePdf = File(session, "session.pdf")
                val copie = contentResolver.openInputStream(pdf.uri)
                    ?.use { entree ->
                        ciblePdf.outputStream().use { sortie ->
                            entree.copyTo(sortie)
                        }
                    }
                if (copie != null) fichiers.add(ciblePdf)
            }
            runOnUiThread {
                fichiersDerniereSession = fichiers
                boutonPartager.isEnabled = fichiers.isNotEmpty()
                sessionDernierScan = session.name
                vueEnvoiScan.visibility =
                    if (fichiers.isEmpty()) View.GONE else View.VISIBLE
                statutScanner.text = "Scan enregistré : " +
                    "$numero page(s).\n" +
                    "Dossier : ${session.name}\n" +
                    "Pages et PDF sont sur l'appareil, " +
                    "prêts à partager."
            }
        } catch (e: Exception) {
            runOnUiThread {
                statutScanner.text = "Échec de l'enregistrement " +
                    "du scan : ${e.message}"
            }
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
            val clip = ClipData.newUri(
                contentResolver, "pages", uris[0]
            )
            for (i in 1 until uris.size) {
                clip.addItem(ClipData.Item(uris[i]))
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Partager les pages"))
        } catch (e: Exception) {
            statutScanner.text = "Partage impossible : ${e.message}"
        }
    }

    // ----------------------------------------------------------------
    // Envoi du dernier scan à Muse par le pont (V2) : le choix
    // d'usage (gras = sélectionné, « livre » par défaut) puis
    // le bouton d'envoi. La mécanique vit dans gestion/Scans —
    // pièces en file locale, run « scan » par la file partagée.
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
        val fichiers = fichiersDerniereSession
        if (session == null || fichiers.isEmpty()) {
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
        Scans.envoyer(this, session, fichiers, usageScan) {
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
    }
}
