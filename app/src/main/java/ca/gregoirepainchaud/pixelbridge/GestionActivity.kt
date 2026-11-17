package ca.gregoirepainchaud.pixelbridge

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
import ca.gregoirepainchaud.pixelbridge.bench.BenchActivity
import ca.gregoirepainchaud.pixelbridge.gestion.PontFile
import ca.gregoirepainchaud.pixelbridge.gestion.Textos
import ca.gregoirepainchaud.pixelbridge.service.BridgeService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestion Android v1 (décision de Grégoire, 2026-11-17) : l'app
 * de test audio devient le cadre de Gestion. On ne refait pas
 * Gestion — l'app web continue d'exister, la base est en ligne,
 * le serveur reste la vérité. Quatre onglets :
 *
 * - « Gestion » : le site actuel, tel quel, en WebView plein
 *   écran (app.gregoirepainchaud.ca).
 * - « Oreille » : la mécanique d'écoute du banc (BenchActivity),
 *   lancée comme écran — flux, sentinelle, épisodes, correction,
 *   clips au pont, back door.
 * - « Textos » : les textos reçus sont lus et poussés à Muse
 *   par le pont (module gestion/Textos, filigrane local).
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
    private lateinit var vueReglages: ScrollView
    private lateinit var statutTextos: TextView
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
        construireVueReglages()
        contenu.addView(vueTextos)
        contenu.addView(vueReglages)

        val barre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for ((cle, libelle) in listOf(
            "gestion" to "Gestion",
            "oreille" to "Oreille",
            "textos" to "Textos",
            "reglages" to "Réglages"
        )) {
            val b = Button(this).apply {
                text = libelle
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

        // File du pont : ce qui attend (textos, runs) repart dès
        // l'ouverture si le réseau répond.
        Thread { PontFile.vider(this) }.start()

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
            startActivity(Intent(this, BenchActivity::class.java))
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
    }
}
