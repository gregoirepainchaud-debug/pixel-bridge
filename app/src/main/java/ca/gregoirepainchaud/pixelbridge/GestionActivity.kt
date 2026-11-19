package ca.gregoirepainchaud.pixelbridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp
import ca.gregoirepainchaud.pixelbridge.gestion.PontFile
import ca.gregoirepainchaud.pixelbridge.gestion.Scans
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

/**
 * Gestion Android v1 (décision de Grégoire, 2026-11-17) : l'app
 * de test audio devient le cadre de Gestion. On ne refait pas
 * Gestion — l'app web continue d'exister, la base est en ligne,
 * le serveur reste la vérité. Quatre onglets (depuis 0.17) :
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
 *   un seul gros bouton on/off ; dessous, la mécanique
 *   (flux, sentinelle, épisodes, correction, clips au pont,
 *   back door), qui vit dans OreilleService.
 * - « Textos » : les textos reçus sont lus et poussés à Muse
 *   par le pont (module gestion/Textos, filigrane local).
 * - « Réglages » : adresse du pont, modèles Whisper, fils,
 *   suivi de trajet natif (0.16), section « Envois en
 *   attente » des reçus scannés — et le bouton « Mettre à
 *   jour l'app » (règle permanente de Grégoire : nos apps
 *   ont toujours un bouton pour les updater, quelque part ;
 *   ici, les Réglages).
 *
 * Retraits 0.17 (décisions de Grégoire, 2026-11-18) :
 * l'onglet Tuner (accordeur de Simon — app autonome Tuner du
 * bac à sable) et l'onglet Scanner général (usages Livre /
 * Autre — app autonome Scan-it) ont été retirés au complet,
 * code propre inclus. Le scanner de REÇUS ne bouge pas : il
 * vit dans l'onglet Gestion (page Reçus du site, pont natif
 * de la WebView, module gestion/Scans) et sa section
 * « Envois en attente » a déménagé dans les Réglages.
 * La section Entraînement n'a jamais eu de code natif ici
 * (page web seulement) : rien à retirer côté Android, les
 * données et routes serveur de l'app Entraînement autonome
 * sont intactes.
 *
 * Nettoyage 0.18 (décision de Grégoire, 2026-11-19, à la
 * suite de la critique post-0.17) : l'ancien écran du banc
 * (BenchActivity, doublon de l'Oreille) et le squelette
 * d'origine jamais branché (BridgeService et les paquets
 * data/ingestion/pipeline/sync, guetteurs vides et
 * travailleur horaire TODO) ont été supprimés au complet.
 * Le pont n'en dépendait pas : PontFile, les envois de
 * l'Oreille, du scanner de reçus et du GPS sont inchangés.
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
 * du scanner de reçus), gestion/textos, gps, reglages.
 */
class GestionActivity : Activity() {

    private val prefs by lazy { PreferencesApp.prefs(this) }

    private lateinit var contenu: FrameLayout
    private var webGestion: WebView? = null
    private lateinit var vueTextos: TextosOngletView
    private lateinit var vueReglages: ReglagesOngletView
    private val boutonsOnglets = LinkedHashMap<String, Button>()
    private var ongletCourant = Onglets.GESTION

    // Le pont natif du scanner de reçus, créé au premier
    // besoin (la WebView Gestion) : il livre son statut à la
    // WebView et signale chaque envoi terminé à l'écran
    // Réglages — l'état des envois en attente suit, un reçu
    // en file y reste visible (section relocalisée en 0.17,
    // elle vivait dans l'onglet Scanner retiré).
    private val pontScannerRecu by lazy {
        PontScannerRecu(
            this,
            fournirWebView = { webGestion },
            surEnvoiTermine = {
                vueReglages.rafraichirEnvoisEnAttente()
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
        vueReglages = ReglagesOngletView(
            this, prefs,
            surMettreAJour = { miseAJour.lancer() },
            surDemanderPermissionsGps = { demanderPermissionsGps() },
            surBasculerGps = { actif -> basculerGps(actif) }
        )
        contenu.addView(vueTextos)
        contenu.addView(vueReglages)

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (onglet in Onglets.tous) {
            val b = Button(this).apply {
                text = onglet.libelle
                // Police réduite pour que les libellés
                // tiennent sur une ligne, à une main.
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
            // l'état affiché dans les Réglages suit.
            vueReglages.rafraichirEnvoisEnAttente()
        }.start()
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
        webGestion?.visibility =
            if (cle == Onglets.GESTION) View.VISIBLE else View.GONE
        vueTextos.visibility =
            if (cle == Onglets.TEXTOS) View.VISIBLE else View.GONE
        vueReglages.visibility =
            if (cle == Onglets.REGLAGES) View.VISIBLE else View.GONE
        for ((c, b) in boutonsOnglets) {
            b.typeface = if (c == cle) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (cle == Onglets.TEXTOS) rafraichirStatutTextos()
        if (cle == Onglets.REGLAGES) {
            vueReglages.rafraichirGps()
            vueReglages.rafraichirEnvoisEnAttente()
            vueReglages.rafraichirPlantage()
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
    // Résultats d'activité : le seul flux restant est le scan
    // de reçu lancé depuis la WebView — il va au pont natif.
    // (Le code 62 de l'ancien onglet Scanner est libre depuis
    // son retrait en 0.17.)
    // ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PontScannerRecu.DEMANDE) {
            pontScannerRecu.traiterResultat(resultCode, data)
        }
    }

    companion object {
        private const val DEMANDE_TEXTOS = 61
        private const val DEMANDE_GPS = 65
    }
}
