package ca.gregoirepainchaud.pixelbridge

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.view.WindowManager
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
import ca.gregoirepainchaud.pixelbridge.tuner.TunerView
import ca.gregoirepainchaud.pixelbridge.oreille.OreilleActivity
import ca.gregoirepainchaud.pixelbridge.gestion.Textos
import ca.gregoirepainchaud.pixelbridge.service.BridgeService
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Gestion Android v1 (décision de Grégoire, 2026-11-17) : l'app
 * de test audio devient le cadre de Gestion. On ne refait pas
 * Gestion — l'app web continue d'exister, la base est en ligne,
 * le serveur reste la vérité. Six onglets :
 *
 * - « Gestion » : le site actuel, tel quel, en WebView plein
 *   écran (app.gregoirepainchaud.ca). La WebView expose à la
 *   page un pont natif minimal, « GestionAndroid » (voir
 *   PontScannerRecu plus bas) : la section Reçus du site s'en
 *   sert, quand il est présent, pour lancer le scanner
 *   documentaire ML Kit — et à la sortie du scan, le natif
 *   envoie lui-même la session au pont serveur (cas « scan »,
 *   usage « recu ») ; la page ne reçoit que le statut. Sous
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
 *   demandés ni conservés. L'onglet montre aussi, quand la
 *   file des pièces n'est pas vide, une section « Envois en
 *   attente » (session, taille, pages, depuis quand, dernier
 *   refus noté) — ajoutée le 2026-11-17 au soir après
 *   l'incident du scan de 20 pages resté en file sans que
 *   l'app annonce rien.
 * - « Tuner » : l'accordeur des pianos de Simon Tremblay
 *   (2026-11-18) — 100 % NATIF depuis la version 0.3
 *   (module tuner/ : interface Kotlin, moteur DSP porté de
 *   dsp.js — FFT Hann 32768, partiel de référence, détection
 *   par coïncidence de partiels, chemin du worklet — et
 *   capture AudioRecord). La version 0.2 l'avait livré en
 *   page WebView : getUserMedia ne passe pas le WebView
 *   durci de GrapheneOS, le micro ne s'y activait pas —
 *   décision de Grégoire : natif d'un bout à l'autre, aucun
 *   navigateur n'intervient. Les 14 accords réels de Simon
 *   (tunings.json, lu nativement), diapason limité à
 *   440 / 441 / 442 Hz, micro sans traitement, interrupteur
 *   portrait/paysage persisté (orientation appliquée tant
 *   que l'onglet est affiché, libérée en le quittant).
 *   L'écran reste allumé tant que l'onglet est visible
 *   (FLAG_KEEP_SCREEN_ON).
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
    private var vueTuner: TunerView? = null
    private lateinit var vueTextos: LinearLayout
    private lateinit var vueScanner: ScrollView
    private lateinit var vueReglages: ScrollView
    private lateinit var statutTextos: TextView
    private lateinit var statutScanner: TextView
    private lateinit var vueEnvoisEnAttente: LinearLayout
    private lateinit var texteEnvoisEnAttente: TextView
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
            "tuner" to "Tuner",
            "reglages" to "Réglages"
        )) {
            val b = Button(this).apply {
                text = libelle
                // Six onglets : police réduite pour que les
                // libellés tiennent sur une ligne, à une main.
                textSize = 11f
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
        Thread {
            Scans.viderPieces(this)
            // La vidange a pu vider la file (ou échouer) :
            // l'état affiché dans l'onglet Scanner suit.
            rafraichirEnvoisEnAttente()
        }.start()

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
        if (cle == "tuner" && vueTuner == null) {
            vueTuner = TunerView(
                this,
                demanderPermissionMicro = { demanderMicroTuner() },
                appliquerOrientation = { paysage ->
                    appliquerOrientationTuner(paysage)
                }
            )
            contenu.addView(vueTuner)
        }
        webGestion?.visibility = if (cle == "gestion") View.VISIBLE else View.GONE
        vueTextos.visibility = if (cle == "textos") View.VISIBLE else View.GONE
        vueScanner.visibility =
            if (cle == "scanner") View.VISIBLE else View.GONE
        vueReglages.visibility =
            if (cle == "reglages") View.VISIBLE else View.GONE
        vueTuner?.visibility = if (cle == "tuner") View.VISIBLE else View.GONE
        for ((c, b) in boutonsOnglets) {
            b.typeface = if (c == cle) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (cle == "textos") rafraichirStatutTextos()
        if (cle == "scanner") rafraichirEnvoisEnAttente()
        if (cle == "tuner") {
            // Accorder un piano prend du temps, les mains sur
            // les clés : l'écran ne s'éteint pas tant que
            // l'onglet Tuner est visible.
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            // Orientation choisie dans l'onglet (interrupteur
            // portrait/paysage, persistée) : appliquée seulement
            // le temps que l'onglet est affiché.
            appliquerOrientationTuner(
                prefs.getBoolean("tuner_paysage", false)
            )
            // La permission micro de l'app se demande ici,
            // seulement à l'ouverture de cet onglet — jamais
            // au démarrage pour les autres usages.
            demanderMicroTuner()
        } else {
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            // Onglet quitté : l'orientation redevient libre —
            // le reste de l'app n'est pas figé par le choix du
            // tuner. (La capture du tuner s'arrête d'elle-même :
            // la vue réagit à sa propre disparition.)
            requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Orientation imposée par l'onglet Tuner (interrupteur
    // portrait/paysage de TunerView, choix persisté).
    private fun appliquerOrientationTuner(paysage: Boolean) {
        requestedOrientation = if (paysage) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onDestroy() {
        // La capture du tuner ne survit pas à l'écran.
        vueTuner?.arreterCapture()
        super.onDestroy()
    }

    override fun onPause() {
        // Arrière-plan : la capture du tuner s'arrête (0.6 —
        // l'écoute du tuner est permanente à l'écran, jamais en
        // arrière-plan). Elle repartira toute seule au retour
        // si l'onglet Tuner est affiché.
        vueTuner?.arreterCapture()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (ongletCourant == "tuner") vueTuner?.surPremierPlan()
    }

    // ----------------------------------------------------------------
    // Onglet Tuner : la vue est 100 % native (module tuner/,
    // TunerView) depuis la version 0.3 — plus de WebView ici.
    // Il ne reste à l'activité que la demande de permission
    // micro, déclenchée à l'ouverture de l'onglet. (Depuis la
    // 0.6, la vue n'a plus de bouton micro : son écoute est
    // permanente tant que son écran principal est affiché.)
    // ----------------------------------------------------------------

    private fun demanderMicroTuner() {
        if (checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                DEMANDE_TUNER_MIC
            )
        }
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
        if (requestCode == DEMANDE_TUNER_MIC) {
            if (grantResults.firstOrNull() ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // Permission fraîchement accordée : la vue
                // native du tuner le signale — le prochain tap
                // « Activer le micro » obtient la capture du
                // premier coup.
                vueTuner?.surPermissionMicroAccordee()
            }
            return
        }
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

        // État visible des envois de scans en attente
        // (2026-11-17 au soir, après l'incident du scan de
        // 20 pages refusé par le plafond du pont pendant que
        // son run passait : la pièce attendait en file et
        // l'app n'annonçait rien — Grégoire a cru son scan
        // perdu ; sa règle : aucun état invisible ou ambigu).
        // Alimentée par le contenu RÉEL de la file des pièces
        // (filesDir/scans-attente — la même file que les
        // reçus de l'onglet Reçus, qui passent par
        // Scans.envoyerDetail) ; elle n'apparaît que quand la
        // file est non vide. Affichage seulement : le
        // mécanisme d'envoi ne change pas.
        vueEnvoisEnAttente = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 16)
        }
        vueEnvoisEnAttente.addView(TextView(this).apply {
            text = "Envois en attente"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        })
        texteEnvoisEnAttente = TextView(this).apply {
            textSize = 14f
        }
        vueEnvoisEnAttente.addView(texteEnvoisEnAttente)
        ajout(vueEnvoisEnAttente)
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
    private fun rafraichirEnvoisEnAttente() {
        if (!::vueEnvoisEnAttente.isInitialized) return
        Thread {
            val pieces = Scans.piecesEnAttente(this)
            val essai = Scans.dernierEssai(this)
            runOnUiThread {
                if (pieces.isEmpty()) {
                    vueEnvoisEnAttente.visibility = View.GONE
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
                texteEnvoisEnAttente.text = sb.toString().trimEnd()
                vueEnvoisEnAttente.visibility = View.VISIBLE
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
    // Mécanique resserrée le 2026-11-17 au soir (décision de
    // Grégoire) : « une fois scanné et que je sors du scan,
    // inutile de peser sur envoyer, le scan part tout seul par
    // le pont » — à la sortie du scanner, le natif envoie la
    // session au pont serveur par la machinerie exacte de
    // l'onglet Scanner (module gestion/Scans : PDF de session,
    // file locale d'abord, run « scan »), usage figé à
    // « recu » — comme le reçu McDonald's de 20 h 36, ensuite
    // ingéré par Muse dans la banque de reçus de Gestion.
    // La page ne reçoit QUE le statut, jamais l'image : aucun
    // aperçu, aucun « Next », aucun téléversement vers
    // /api/recus/scanner dans ce chemin.
    //
    // Contrat, volontairement minimal :
    // - la page teste la présence du pont :
    //   typeof window.GestionAndroid !== "undefined" ;
    // - elle appelle window.GestionAndroid.scannerRecu() ;
    // - le natif répond en appelant, une fois l'envoi tenté,
    //   window.GestionScanRecu.retour(objet) avec :
    //     {statut: "envoye"} — pièce et run acceptés par le
    //       pont ;
    //     {statut: "en_file"} — tout est en file locale,
    //       partira au retour du réseau (jamais un faux
    //       « envoyé ») ;
    //     {statut: "annule"} — rien n'a été scanné ;
    //     {statut: "erreur", message: "…"} — échec franc.
    // La session est aussi copiée en durable (Documents/scans/,
    // comme l'onglet Scanner) avant l'envoi : aucun reçu ne se
    // perd, même si la file devait échouer.
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
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
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
                getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: filesDir,
                "scans"
            )
            val dossier = File(racine, "scan-$horodatage")
            if (!dossier.mkdirs() && !dossier.isDirectory) {
                throw IllegalStateException(
                    "dossier de session impossible à créer"
                )
            }
            ciblePdf = File(dossier, "session.pdf")
            val copie = contentResolver.openInputStream(uriPdf)
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
            this, session, ciblePdf, nbPages, "recu"
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
            rafraichirEnvoisEnAttente()
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
                rafraichirEnvoisEnAttente()
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
        private const val DEMANDE_TUNER_MIC = 64
    }
}
