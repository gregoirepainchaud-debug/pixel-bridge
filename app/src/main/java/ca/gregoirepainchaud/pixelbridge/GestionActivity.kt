package ca.gregoirepainchaud.pixelbridge

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp
import ca.gregoirepainchaud.pixelbridge.gestion.PontFile
import ca.gregoirepainchaud.pixelbridge.gestion.Scans
import ca.gregoirepainchaud.pixelbridge.gestion.Telemetrie
import ca.gregoirepainchaud.pixelbridge.gestion.Textos
import ca.gregoirepainchaud.pixelbridge.gestion.textos.TextosOngletView
import ca.gregoirepainchaud.pixelbridge.gestion.web.GestionWebView
import ca.gregoirepainchaud.pixelbridge.gestion.web.PontScannerRecu
import ca.gregoirepainchaud.pixelbridge.gps.GpsEtat
import ca.gregoirepainchaud.pixelbridge.gps.GpsFichier
import ca.gregoirepainchaud.pixelbridge.gps.GpsService
import ca.gregoirepainchaud.pixelbridge.oreille.OreilleActivity
import ca.gregoirepainchaud.pixelbridge.reglages.MiseAJourApp
import ca.gregoirepainchaud.pixelbridge.reglages.ReglagesOngletView
import ca.gregoirepainchaud.pixelbridge.scanner.ScannerControleur
import ca.gregoirepainchaud.pixelbridge.service.BridgeService
import ca.gregoirepainchaud.pixelbridge.tuner.TunerView

/**
 * Gestion Android v1 (décision de Grégoire, 2026-11-17) : l'app
 * de test audio devient le cadre de Gestion. On ne refait pas
 * Gestion — l'app web continue d'exister, la base est en ligne,
 * le serveur reste la vérité. Six onglets :
 *
 * - « Gestion » : le site actuel, tel quel, en WebView plein
 *   écran (app.gregoirepainchaud.ca). La WebView expose à la
 *   page un pont natif minimal, « GestionAndroid » (voir
 *   gestion/web/PontScannerRecu) : la section Reçus du site
 *   s'en sert, quand il est présent, pour lancer le scanner
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
 *
 * Chantier structure 0.8 : cette activité n'est plus que la
 * coquille — barre d'onglets (registre Onglets), navigation,
 * et dispatch du cycle de vie, des permissions et des
 * résultats d'activité. Chaque onglet a son écran et sa
 * mécanique dans son paquet : gestion/web (WebView et pont
 * du scanner de reçus), gestion/textos, scanner, tuner,
 * reglages.
 */
class GestionActivity : Activity() {

    private val prefs by lazy { PreferencesApp.prefs(this) }

    private lateinit var contenu: FrameLayout
    private var webGestion: WebView? = null
    private var vueTuner: TunerView? = null
    private lateinit var vueTextos: TextosOngletView
    private lateinit var scannerControleur: ScannerControleur
    private lateinit var vueReglages: ReglagesOngletView
    private val boutonsOnglets = LinkedHashMap<String, Button>()
    private var ongletCourant = Onglets.GESTION

    // Le pont natif du scanner de reçus, créé au premier
    // besoin (la WebView Gestion) : il livre son statut à la
    // WebView et signale chaque envoi terminé au contrôleur
    // du Scanner — l'état des envois en attente suit, un reçu
    // en file y est visible comme un scan de l'onglet.
    private val pontScannerRecu by lazy {
        PontScannerRecu(
            this,
            fournirWebView = { webGestion },
            surEnvoiTermine = {
                scannerControleur.rafraichirEnvoisEnAttente()
            }
        )
    }

    // La mise à jour intégrée (reglages/MiseAJourApp) : son
    // statut et l'état de son bouton vivent dans l'écran
    // Réglages, rejoints par ces deux rappels.
    private val miseAJour by lazy {
        MiseAJourApp(
            this,
            afficherStatut = { msg -> vueReglages.afficherStatutMaj(msg) },
            boutonActif = { actif -> vueReglages.setBoutonMajActif(actif) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, BridgeService::class.java))
        // Suivi de trajet natif (0.16) : le service vit sa vie
        // (boot, dormance, trajets) — l'ouverture de l'app le
        // (re)lance simplement s'il est actif et arrêté.
        if (GpsEtat.actif(this)) GpsService.demarrer(this)

        // Nettoyage : un paquet d'installation resté d'une mise à
        // jour précédente est effacé au démarrage.
        miseAJour.nettoyerPaquet()

        val racine = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contenu = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        racine.addView(contenu)

        vueTextos = TextosOngletView(this) { verifierTextosAvecPermission() }
        scannerControleur = ScannerControleur(this)
        vueReglages = ReglagesOngletView(
            this, prefs,
            surMettreAJour = { miseAJour.lancer() },
            surDemanderPermissionsGps = { demanderPermissionsGps() },
            surBasculerGps = { actif -> basculerGps(actif) }
        )
        contenu.addView(vueTextos)
        contenu.addView(scannerControleur.vue)
        contenu.addView(vueReglages)

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (onglet in Onglets.tous) {
            val b = Button(this).apply {
                text = onglet.libelle
                // Six onglets : police réduite pour que les
                // libellés tiennent sur une ligne, à une main.
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener { allerA(onglet.cle) }
            }
            boutonsOnglets[onglet.cle] = b
            barre.addView(b)
        }
        racine.addView(barre)
        setContentView(racine)
        afficherOnglet(Onglets.defaut)

        // File du pont : ce qui attend (textos, runs, pièces de
        // scan) repart dès l'ouverture si le réseau répond.
        Thread { PontFile.vider(this) }.start()
        Thread {
            Scans.viderPieces(this)
            // La vidange a pu vider la file (ou échouer) :
            // l'état affiché dans l'onglet Scanner suit.
            scannerControleur.rafraichirEnvoisEnAttente()
        }.start()
        // Logs de télémétrie du tuner restés en file (0.14).
        Thread { Telemetrie.viderPieces(this) }.start()
        // Points de trajet restés en file (0.16) : ils partent
        // dès l'ouverture si la session Gestion répond.
        Thread { GpsFichier.vider(this) }.start()

        // Textos : vérification à l'ouverture, si la permission
        // est déjà accordée. Sinon, l'onglet Textos la demandera.
        if (checkSelfPermission(android.Manifest.permission.READ_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            lancerVerificationTextos()
        }
    }

    // ----------------------------------------------------------------
    // Navigation par onglets. « Oreille » est un écran-activité :
    // l'onglet le lance, le retour système ramène ici.
    // ----------------------------------------------------------------

    private fun allerA(cle: String) {
        if (cle == Onglets.OREILLE) {
            startActivity(Intent(this, OreilleActivity::class.java))
            return
        }
        afficherOnglet(cle)
    }

    private fun afficherOnglet(cle: String) {
        ongletCourant = cle
        if (cle == Onglets.GESTION && webGestion == null) {
            webGestion = GestionWebView.creer(this, pontScannerRecu)
            contenu.addView(webGestion)
        }
        if (cle == Onglets.TUNER && vueTuner == null) {
            vueTuner = TunerView(
                this,
                demanderPermissionMicro = { demanderMicroTuner() },
                appliquerOrientation = { paysage ->
                    appliquerOrientationTuner(paysage)
                }
            )
            contenu.addView(vueTuner)
        }
        webGestion?.visibility =
            if (cle == Onglets.GESTION) View.VISIBLE else View.GONE
        vueTextos.visibility =
            if (cle == Onglets.TEXTOS) View.VISIBLE else View.GONE
        scannerControleur.vue.visibility =
            if (cle == Onglets.SCANNER) View.VISIBLE else View.GONE
        vueReglages.visibility =
            if (cle == Onglets.REGLAGES) View.VISIBLE else View.GONE
        vueTuner?.visibility =
            if (cle == Onglets.TUNER) View.VISIBLE else View.GONE
        for ((c, b) in boutonsOnglets) {
            b.typeface = if (c == cle) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (cle == Onglets.TEXTOS) rafraichirStatutTextos()
        if (cle == Onglets.REGLAGES) vueReglages.rafraichirGps()
        if (cle == Onglets.SCANNER) {
            scannerControleur.rafraichirEnvoisEnAttente()
        }
        if (cle == Onglets.TUNER) {
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
                prefs.getBoolean(PreferencesApp.CLE_TUNER_PAYSAGE, false)
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
        // Arrière-plan : l'arbitre du tuner lui retire le micro
        // (0.6 — l'écoute du tuner est permanente à l'écran,
        // jamais en arrière-plan ; 0.13 — c'est l'arbitre qui
        // le garantit, comme pour les onglets et l'Oreille).
        // Elle repartira toute seule au retour si la fenêtre du
        // tuner est active et le micro libre.
        vueTuner?.surArrierePlan()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Quel que soit l'onglet courant : le drapeau « premier
        // plan » de l'arbitre du tuner doit rester frais, pour
        // que le retour sur l'onglet Tuner tranche juste.
        vueTuner?.surPremierPlan()
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
        if (ongletCourant == Onglets.GESTION && web != null && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // ----------------------------------------------------------------
    // Onglet Textos : l'écran est gestion/textos/
    // TextosOngletView, la mécanique gestion/Textos — il ne
    // reste ici que la permission READ_SMS et le lancement.
    // ----------------------------------------------------------------

    private fun lancerVerificationTextos() {
        Textos.verifier(this) { msg ->
            runOnUiThread { vueTextos.afficherStatut(msg) }
        }
    }

    private fun rafraichirStatutTextos() {
        if (checkSelfPermission(android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            vueTextos.afficherPermissionManquante()
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
        lancerVerificationTextos()
    }

    // ----------------------------------------------------------------
    // Suivi de trajet natif (0.16) : permissions et interrupteur,
    // pilotés depuis la section GPS de l'onglet Réglages. La
    // position précise se demande ici ; l'arrière-plan (« Toujours
    // autoriser ») ne se demande pas en dialogue sur Android —
    // le bouton des Réglages ouvre alors les paramètres de
    // l'app, où Grégoire le règle une fois.
    // ----------------------------------------------------------------

    private fun demanderPermissionsGps() {
        val fineAccordee = checkSelfPermission(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineAccordee) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ),
                DEMANDE_GPS
            )
            return
        }
        val arrierePlanAccorde = checkSelfPermission(
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!arrierePlanAccorde) {
            // Le réglage « Toujours autoriser » vit dans les
            // paramètres de l'app : on y amène Grégoire.
            try {
                startActivity(
                    Intent(
                        android.provider.Settings
                            .ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                // Paramètres inaccessibles : l'état affiché
                // dans les Réglages dit quand même ce qui manque.
            }
            return
        }
        // Tout est accordé : relancer le service au cas où il
        // se serait arrêté faute de permission, et réafficher.
        if (GpsEtat.actif(this)) GpsService.demarrer(this)
        vueReglages.rafraichirGps()
    }

    private fun basculerGps(actif: Boolean) {
        if (actif) {
            GpsService.demarrer(this)
        } else {
            stopService(Intent(this, GpsService::class.java))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DEMANDE_GPS) {
            vueReglages.rafraichirGps()
            if (grantResults.firstOrNull() ==
                PackageManager.PERMISSION_GRANTED &&
                GpsEtat.actif(this)
            ) {
                GpsService.demarrer(this)
            }
            return
        }
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
            lancerVerificationTextos()
        } else {
            vueTextos.afficherStatut(
                "Textos : permission refusée — aucun " +
                    "texto ne sera lu ni poussé."
            )
        }
    }

    // ----------------------------------------------------------------
    // Résultats d'activité : l'activité ne fait que router —
    // le scan de reçu (WebView) va au pont, le scan de
    // l'onglet à son contrôleur.
    // ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PontScannerRecu.DEMANDE) {
            pontScannerRecu.traiterResultat(resultCode, data)
            return
        }
        if (requestCode == ScannerControleur.DEMANDE) {
            scannerControleur.traiterResultat(resultCode, data)
        }
    }

    companion object {
        private const val DEMANDE_TEXTOS = 61
        private const val DEMANDE_TUNER_MIC = 64
        private const val DEMANDE_GPS = 65
    }
}
