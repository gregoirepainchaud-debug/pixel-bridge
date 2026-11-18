package ca.gregoirepainchaud.pixelbridge.tuner

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import ca.gregoirepainchaud.pixelbridge.TracePlantage
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp
import ca.gregoirepainchaud.pixelbridge.gestion.Telemetrie
import ca.gregoirepainchaud.pixelbridge.oreille.OreilleService
import ca.gregoirepainchaud.pixelbridge.tuner.parcours.ParcoursMesureControleur
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ---------------------------------------------------------------------------
// Onglet Tuner 100 % natif (2026-11-18, version 0.3) — remplace la page
// WebView de la 0.2, dont la capture getUserMedia ne passait pas le
// WebView durci de GrapheneOS. L'écran reprend celui du prototype web
// (docs/reference-tuner-web/index.html) : mêmes fonctions, mêmes
// intentions, mêmes textes. Moteur : TunerDsp/TunerEngine (port du
// chemin worklet prouvé). Capture : TunerCapture (AudioRecord).
// Aucun navigateur n'intervient nulle part ; 100 % hors ligne.
// ---------------------------------------------------------------------------

class BarreAccordView(context: Context) : View(context) {
    var fracAiguille: Double = 0.5
    var aiguilleOk: Boolean = false
    var aiguilleOpacite: Float = 0.25f

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Fond panneau + bordure.
        p.style = Paint.Style.FILL
        p.color = COUL_PANEL
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(10f), dp(10f), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1f)
        p.color = COUL_LINE
        canvas.drawRoundRect(
            RectF(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)),
            dp(10f), dp(10f), p
        )
        // Ligne centrale.
        p.style = Paint.Style.FILL
        p.color = COUL_CENTRE
        canvas.drawRect(w / 2 - dp(1f), 0f, w / 2 + dp(1f), h, p)
        // Graduations et étiquettes.
        p.color = COUL_TICK
        for (g in intArrayOf(30, 20, 10, 5, 1)) {
            for (s in intArrayOf(-1, 1)) {
                val x = (centsToFrac(s * g.toDouble()) * w).toFloat()
                canvas.drawRect(x - dp(0.5f), 0f, x + dp(0.5f), dp(14f), p)
            }
        }
        p.color = COUL_DIM
        p.textSize = sp(11f)
        p.textAlign = Paint.Align.CENTER
        for (g in intArrayOf(30, 20, 10, 5, 1)) {
            for (s in intArrayOf(-1, 1)) {
                val x = (centsToFrac(s * g.toDouble()) * w).toFloat()
                canvas.drawText(g.toString(), x, dp(16f) + sp(11f), p)
            }
        }
        canvas.drawText("0", w / 2, dp(16f) + sp(11f), p)
        // Aiguille (curseur).
        val ax = (fracAiguille * w).toFloat()
        p.color = if (aiguilleOk) COUL_OK else Color.WHITE
        p.alpha = (aiguilleOpacite * 255).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(
            RectF(ax - dp(3f), dp(26f), ax + dp(3f), h - dp(8f)),
            dp(3f), dp(3f), p
        )
    }
}

class TunerView(
    context: Context,
    private val demanderPermissionMicro: () -> Unit,
    private val appliquerOrientation: (Boolean) -> Unit
) : ScrollView(context), ParcoursMesureControleur.HoteParcours {

    private val prefs = PreferencesApp.prefs(context)
    private val gestionnaire = Handler(Looper.getMainLooper())

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    // --- Données et état (miroir d'app.js) ---
    private var donnees: DonneesAccords? = null
    private var tuningId: Int = 1
    private var key: Int = 49
    private var pitch: Int = 440
    private var mode: String = "auto"

    // Tous les accords sélectionnables : les 14 de Simon (ids
    // positifs, lecture seule) puis « Mes accords » (ids négatifs,
    // fichiers locaux — voir MesAccords.kt). Le sélecteur présente
    // les deux sections ; lignesAccords donne, par ligne du
    // sélecteur, l'id de l'accord ou null pour un en-tête.
    private var tousAccords: List<Accord> = emptyList()
    private var lignesAccords: List<Int?> = emptyList()
    private var fichierParAccordId: Map<Int, String> = emptyMap()

    // --- Modes du tuner (registre : TunerModes.kt) ---
    // L'écran principal est le mode ACCORDEUR ; le parcours
    // « Nouveau piano » (mode PARCOURS) vit dans le paquet
    // tuner/parcours/ depuis le chantier structure 0.8 —
    // TunerView ne fait que l'héberger et lui fournir ses
    // services d'hôte (voir HoteParcours plus bas).
    private var modeTuner: ModeTuner = ModesTuner.defaut
    private val parcoursOuvert: Boolean
        get() = modeTuner == ModeTuner.PARCOURS
    private lateinit var parcours: ParcoursMesureControleur

    private var moteur: TunerEngine = TunerEngine(TunerCapture.TAUX)

    // Capture et aiguillage des échantillons : le routeur
    // (TunerAudioRouteur) détient le TunerCapture et distribue
    // au consommateur actif — l'analyseur du parcours pendant
    // une mesure d'ancre, le moteur le reste du temps. La
    // politique de démarrage/arrêt reste ici (startMic/stopMic).
    private val routeur = TunerAudioRouteur(
        context,
        surErreurCapture = { msg ->
            gestionnaire.post { surErreurCapture(msg) }
        }
    )

    // Arbitre du micro (0.13) : LA décision « qui détient le
    // micro » vit dans ArbitreMicro (machine à états pure,
    // testée en JVM). Cette vue ne fait que lui rapporter les
    // événements — visibilité de l'onglet, premier plan de
    // l'app, écran qui veut le micro, état d'écoute de
    // l'Oreille — et appliquer sa décision dans
    // appliquerArbitrage(). Avant la 0.13, startMic/stopMic
    // étaient appelés directement d'une demi-douzaine d'endroits
    // qui ignoraient tous l'Oreille : une écoute démarrée par la
    // tuile pendant que l'onglet Tuner capturait laissait les
    // deux AudioRecord se disputer le micro.
    private val arbitre = ArbitreMicro()

    // L'état d'écoute de l'Oreille, lu de ses deux publications
    // existantes : la préférence « ecoute_en_cours » (écouteur
    // ci-dessous, réaction immédiate) et son fichier d'état
    // oreille-etat.json (sondage pendant que l'onglet est
    // affiché — il couvre la phase « chargement » d'une séance
    // qui démarre, avant même que l'Oreille crée son
    // enregistreur, et sert de filet si un événement de
    // préférence était manqué).
    private val ecouteurEtatOreille =
        SharedPreferences.OnSharedPreferenceChangeListener { _, cle ->
            if (cle == PreferencesApp.CLE_ECOUTE_EN_COURS) {
                gestionnaire.post { majEtatOreille() }
            }
        }
    private var sondageOreilleActif = false
    private val sondageOreille = object : Runnable {
        override fun run() {
            if (!sondageOreilleActif) return
            majEtatOreille()
            gestionnaire.postDelayed(this, 750)
        }
    }

    private var micActif = false
    private var dispCents = 0.0
    private var seqAdoptionsVue = 0
    private var dernierTotalEch = 0L
    private var dernierMouvementEchMs = 0L

    // Réactivité de la barre, persistée (SharedPreferences —
    // l'équivalent natif du localStorage de la page web).
    // Correspondance curseur → alpha REMAPPÉE en 0.10 : l'ancienne
    // (0,02 × 50^(v/100), défaut 73 ≈ 0,35) laissait l'aiguille
    // traîner puis rattraper par marches. Désormais alpha va de
    // 0,03 (v = 0, très stable — notes qui battent) à 1,0 (v = 100,
    // aucun lissage, suivi immédiat), et le défaut pour qui n'a
    // jamais touché le curseur est 88 ≈ 0,83 : la barre suit la
    // note sans retard perçu. La clé de préférence est inchangée ;
    // une valeur déjà sauvegardée est relue par la nouvelle
    // correspondance (73, par exemple, vaut maintenant ≈ 0,64).
    private var smoothAlpha: Double = 0.83
    private fun alphaDepuisCurseur(v: Int): Double =
        0.03 + 0.97 * (v / 100.0).pow(1.5)

    /** Clé du traqueur à laquelle dispCents se rapporte : si la
     * cible change (adoption, désignation), la première lecture de
     * la nouvelle note remplace la valeur lissée au lieu de la
     * faire glisser depuis l'ancienne note. */
    private var cleDispCents: Int? = null

    // Réglages fins de l'affichage (0.14) : les six valeurs du
    // bas de l'écran accordeur, validées par ParametresAffichage,
    // persistées, appliquées en direct au moteur et au traqueur
    // (appliquerParametres). Le parcours de mesure n'est pas
    // touché : ses traqueurs gardent leurs constantes.
    private var params: ParametresAffichage.Valeurs = chargerParametres()
    private val champsParams =
        mutableMapOf<ParametresAffichage.Champ, EditText>()

    /** Position courante du slider de réactivité — consignée
     * dans chaque échantillon de télémétrie. */
    private var curseurReactivite: Int = 88

    // Télémétrie (0.14) : l'enregistreur pur (tuner/Telemetrie.kt),
    // l'état de sa session et les vues de son bloc d'écran.
    // L'envoi par le pont vit dans gestion/Telemetrie.kt.
    private val tele = EnregistreurTelemetrie()
    private var teleSession: String = ""
    private var teleVersionApp: String = ""
    private var teleDernierEtat: EtatTuner? = null
    private var teleFrappesVues: Int = 0
    private var teleNbMarques: Int = 0
    private var teleMicroPreempte: Boolean = false
    private lateinit var btnTele: Button
    private lateinit var btnMarquer: Button
    private lateinit var txtTeleStatut: TextView

    // --- Éléments d'interface ---
    private lateinit var vueAccord: LinearLayout
    private lateinit var vueParcours: LinearLayout
    private lateinit var spinAccord: Spinner
    private lateinit var btnSupprimerAccord: Button
    private lateinit var spinNote: Spinner
    private lateinit var segPitch: Segment
    private lateinit var segMode: Segment
    private lateinit var interrupteurPaysage: Switch
    private lateinit var texteOrientation: TextView
    private lateinit var txtErr: TextView
    private lateinit var banniereOreille: LinearLayout
    private lateinit var bannierePlantage: LinearLayout
    private lateinit var txtPlantage: TextView
    private lateinit var rondNote: FrameLayout
    private lateinit var txtNomNote: TextView
    private lateinit var txtBadge: TextView
    private lateinit var txtMeta: TextView
    private lateinit var txtCents: TextView
    private lateinit var barre: BarreAccordView
    private lateinit var txtDiag: TextView

    /** Petit contrôle segmenté (Diapason, Mode) dans le style de la page. */
    private inner class Segment(
        options: List<String>,
        choixInitial: Int,
        private val surChoix: (Int) -> Unit
    ) {
        val vue: LinearLayout = LinearLayout(context)
        private val boutons = mutableListOf<Button>()
        var choix: Int = choixInitial
            private set

        init {
            vue.orientation = LinearLayout.HORIZONTAL
            vue.background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
            options.forEachIndexed { idx, libelle ->
                val b = Button(context).apply {
                    text = libelle
                    textSize = 15f
                    isAllCaps = false
                    setPadding(dp(13f), dp(9f), dp(13f), dp(9f))
                    minWidth = 0
                    minimumWidth = 0
                    setOnClickListener { selectionner(idx, true) }
                }
                boutons.add(b)
                vue.addView(b)
            }
            rafraichir()
        }

        fun selectionner(idx: Int, notifier: Boolean) {
            choix = idx
            rafraichir()
            if (notifier) surChoix(idx)
        }

        private fun rafraichir() {
            boutons.forEachIndexed { idx, b ->
                if (idx == choix) {
                    b.background = fondArrondi(context, COUL_ACCENT, COUL_ACCENT, 6f)
                    b.setTextColor(COUL_SUR_ACCENT)
                    b.typeface = Typeface.DEFAULT_BOLD
                } else {
                    b.background = fondArrondi(context, COUL_PANEL, COUL_PANEL, 6f)
                    b.setTextColor(COUL_TXT)
                    b.typeface = Typeface.DEFAULT
                }
            }
        }
    }

    private fun fondOvale(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2f), stroke)
        }

    private fun adaptateurSpinner(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(
            context, android.R.layout.simple_spinner_item, items
        ) {
            override fun getView(
                position: Int, convertView: View?, parent: ViewGroup
            ): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(COUL_TXT)
                v.textSize = 15f
                return v
            }

            override fun getDropDownView(
                position: Int, convertView: View?, parent: ViewGroup
            ): View {
                val v = super.getDropDownView(
                    position, convertView, parent
                ) as TextView
                v.setTextColor(COUL_TXT)
                v.setBackgroundColor(COUL_PANEL)
                v.textSize = 15f
                v.setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                return v
            }
        }

    init {
        routeur.moteur = moteur
        // L'écran principal de l'accordeur est l'écran par
        // défaut de la fenêtre : il veut le micro (l'arbitre
        // décidera selon la visibilité, le premier plan et
        // l'Oreille). Le parcours pilote ensuite ce vouloir via
        // l'hôte (menus = faux, mesures = vrai).
        arbitre.ecranVeutMicro(true)
        prefs.registerOnSharedPreferenceChangeListener(ecouteurEtatOreille)
        setBackgroundColor(COUL_BG)
        isFillViewport = true
        val racine = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(18f))
        }
        addView(racine)

        // Deux panneaux dans l'onglet : l'accordeur (vueAccord,
        // tout l'écran historique) et le parcours « Nouveau
        // piano » (vueParcours, mesure d'inharmonicité) — un seul
        // visible à la fois.
        vueAccord = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        parcours = ParcoursMesureControleur(context, routeur, this)
        vueParcours = parcours.vue.apply {
            visibility = View.GONE
        }

        // Bandeau « micro pris par l'Oreille » (0.13) : quand
        // une écoute détient le micro pendant que la fenêtre du
        // tuner est affichée, l'état se DIT ici, en toutes
        // lettres — jamais un écran muet qui a l'air en panne.
        // Au-dessus des deux panneaux (accordeur et parcours) :
        // le blocage vaut pour toute la fenêtre. Visible
        // seulement dans cet état ; appliquerArbitrage le pilote.
        banniereOreille = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = fondArrondi(context, COUL_PANEL, COUL_ACCENT, 8f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8f)) }
        }
        banniereOreille.addView(TextView(context).apply {
            text = "🎧 Micro pris par l'Oreille — une écoute est " +
                "en cours. Le tuner est en pause ; il reprendra " +
                "le micro tout seul dès la fin de l'écoute."
            textSize = 13.5f
            setTextColor(COUL_TXT)
        })
        racine.addView(banniereOreille)

        // Bannière « dernier plantage » (0.6) : si le
        // gestionnaire de TracePlantage a écrit une trace à la
        // mort précédente de l'app, elle s'affiche ici — lisible
        // par Grégoire, dismissible. Remplie par
        // majBannierePlantage().
        bannierePlantage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = fondArrondi(context, COUL_PANEL, COUL_BAD, 8f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8f)) }
        }
        txtPlantage = TextView(context).apply {
            textSize = 12f
            setTextColor(COUL_TXT)
            typeface = Typeface.MONOSPACE
        }
        bannierePlantage.addView(txtPlantage)
        bannierePlantage.addView(Button(context).apply {
            text = "Effacer cette trace"
            textSize = 13f
            isAllCaps = false
            setTextColor(COUL_TXT)
            background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
            setOnClickListener {
                TracePlantage.effacer(context)
                bannierePlantage.visibility = View.GONE
            }
        })
        racine.addView(bannierePlantage)
        racine.addView(vueAccord)
        racine.addView(vueParcours)

        vueAccord.addView(TextView(context).apply {
            text = "Tuner Simon"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COUL_TXT)
            setPadding(0, dp(2f), 0, dp(8f))
        })

        // Accord (les 14 accords de Simon).
        val rangeeAccord = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        rangeeAccord.addView(etiquette(context, "Accord"))
        spinAccord = Spinner(context).apply {
            background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rangeeAccord.addView(spinAccord)
        // Suppression d'un accord de « Mes accords » — visible
        // seulement quand l'accord sélectionné en est un (jamais
        // pour ceux de Simon).
        btnSupprimerAccord = Button(context).apply {
            text = "🗑"
            textSize = 15f
            isAllCaps = false
            setTextColor(COUL_TXT)
            background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
            visibility = View.GONE
            setOnClickListener { confirmerSuppressionAccord() }
        }
        rangeeAccord.addView(btnSupprimerAccord)
        vueAccord.addView(rangeeAccord)

        // Entrée du parcours « Nouveau piano » : mesurer
        // l'inharmonicité du piano devant soi (la-do par octave)
        // et en tirer un accord sur mesure, enregistré à côté de
        // ceux de Simon.
        vueAccord.addView(Button(context).apply {
            text = "🎹 Nouveau piano — mesurer l'inharmonicité"
            textSize = 15f
            isAllCaps = false
            setTextColor(COUL_TXT)
            background = fondArrondi(context, COUL_PANEL, COUL_ACCENT, 8f)
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2f), 0, dp(4f)) }
            setOnClickListener { ouvrirParcours() }
        })

        // Diapason 440 / 441 / 442.
        val rangeePitch = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        rangeePitch.addView(etiquette(context, "Diapason"))
        segPitch = Segment(listOf("440", "441", "442"), 0) { idx ->
            pitch = listOf(440, 441, 442)[idx]
            if (tele.enCours) {
                tele.evenement(
                    teleTms(), EnregistreurTelemetrie.EVT_DIAPASON,
                    mapOf("diapason" to pitch)
                )
            }
            sendConfig()
        }
        rangeePitch.addView(segPitch.vue)
        vueAccord.addView(rangeePitch)

        // Mode Auto / Demi-ton / Bloqué.
        val rangeeMode = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        rangeeMode.addView(etiquette(context, "Mode"))
        segMode = Segment(listOf("Auto", "Demi-ton", "Bloqué"), 0) { idx ->
            setMode(listOf("auto", "demi", "bloque")[idx])
        }
        rangeeMode.addView(segMode.vue)
        vueAccord.addView(rangeeMode)

        // Orientation : interrupteur portrait / paysage, persisté,
        // appliqué tant que l'onglet est affiché (l'activité libère
        // l'orientation en quittant l'onglet).
        val rangeeOrientation = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        rangeeOrientation.addView(etiquette(context, "Écran"))
        interrupteurPaysage = Switch(context).apply {
            text = "Paysage"
            textSize = 15f
            setTextColor(COUL_TXT)
            isChecked = prefs.getBoolean(PreferencesApp.CLE_TUNER_PAYSAGE, false)
        }
        texteOrientation = TextView(context).apply {
            textSize = 13f
            setTextColor(COUL_DIM)
            setPadding(dp(8f), 0, 0, 0)
        }
        majTexteOrientation()
        interrupteurPaysage.setOnCheckedChangeListener { _, coche ->
            prefs.edit().putBoolean(PreferencesApp.CLE_TUNER_PAYSAGE, coche).apply()
            majTexteOrientation()
            appliquerOrientation(coche)
        }
        rangeeOrientation.addView(interrupteurPaysage)
        rangeeOrientation.addView(texteOrientation)
        vueAccord.addView(rangeeOrientation)

        // Zone d'erreur franche. (Le bouton « Activer le micro »
        // a disparu en 0.6, consigne de Grégoire : l'écoute est
        // permanente tant que la fenêtre du tuner est active —
        // et seulement là (0.13) : l'arbitre du micro la démarre
        // et l'arrête selon la visibilité de l'onglet, le
        // premier plan de l'app, les menus du parcours et les
        // écoutes de l'Oreille. Voir appliquerArbitrage.)
        txtErr = TextView(context).apply {
            textSize = 14f
            setTextColor(COUL_BAD)
            setPadding(0, dp(6f), 0, dp(6f))
        }
        vueAccord.addView(txtErr)

        // Contrôle de note : ‹ rond › (le rond reçoit les gestes).
        val rangeeNote = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6f), 0, dp(2f))
        }
        val btnBas = Button(context).apply {
            text = "‹"
            textSize = 30f
            setTextColor(COUL_TXT)
            background = fondOvale(COUL_PANEL, COUL_LINE)
            layoutParams = LinearLayout.LayoutParams(dp(62f), dp(62f)).apply {
                marginEnd = dp(14f)
            }
            setOnClickListener { if (key > 1) setKey(key - 1, false) }
        }
        rangeeNote.addView(btnBas)
        rondNote = FrameLayout(context).apply {
            background = fondOvale(COUL_BG, COUL_LINE)
            layoutParams = LinearLayout.LayoutParams(dp(148f), dp(148f))
        }
        txtNomNote = TextView(context).apply {
            text = "La4"
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COUL_TXT)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rondNote.addView(txtNomNote)
        installerGestesRond(rondNote)
        rangeeNote.addView(rondNote)
        val btnHaut = Button(context).apply {
            text = "›"
            textSize = 30f
            setTextColor(COUL_TXT)
            background = fondOvale(COUL_PANEL, COUL_LINE)
            layoutParams = LinearLayout.LayoutParams(dp(62f), dp(62f)).apply {
                marginStart = dp(14f)
            }
            setOnClickListener { if (key < 88) setKey(key + 1, false) }
        }
        rangeeNote.addView(btnHaut)
        vueAccord.addView(rangeeNote)

        txtBadge = TextView(context).apply {
            textSize = 12.5f
            setTextColor(COUL_ACCENT)
            gravity = Gravity.CENTER
            minHeight = dp(20f)
        }
        vueAccord.addView(txtBadge)
        txtMeta = TextView(context).apply {
            text = "—"
            textSize = 14f
            setTextColor(COUL_DIM)
            gravity = Gravity.CENTER
            minHeight = dp(20f)
        }
        vueAccord.addView(txtMeta)

        // Sélecteur de note (88 notes).
        val rangeeSelNote = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, dp(4f))
        }
        spinNote = Spinner(context).apply {
            background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
        }
        rangeeSelNote.addView(spinNote)
        vueAccord.addView(rangeeSelNote)

        // Lecture numérique en cents (mesure brute).
        txtCents = TextView(context).apply {
            gravity = Gravity.CENTER
            minHeight = dp(84f)
            fontFeatureSettings = "\"tnum\""
            setPadding(0, dp(4f), 0, 0)
        }
        afficherCentsIncertain()
        vueAccord.addView(txtCents)

        // Barre de mesure.
        barre = BarreAccordView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92f)
            ).apply { setMargins(0, dp(10f), 0, dp(4f)) }
        }
        vueAccord.addView(barre)

        // Curseur « Réactivité de la barre ».
        val rangeeReactivite = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        rangeeReactivite.addView(etiquette(context, "Réactivité de la barre"))
        rangeeReactivite.addView(TextView(context).apply {
            text = "lente · stable"
            textSize = 12f
            setTextColor(COUL_DIM)
        })
        var vInit = 88
        val sauve = prefs.getInt(PreferencesApp.CLE_TUNER_REACTIVITE, -1)
        if (sauve in 0..100) vInit = sauve
        smoothAlpha = alphaDepuisCurseur(vInit)
        curseurReactivite = vInit
        val seek = SeekBar(context).apply {
            max = 100
            progress = vInit
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(8f), 0, dp(8f), 0) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?, progres: Int, parUtilisateur: Boolean
                ) {
                    curseurReactivite = progres
                    smoothAlpha = alphaDepuisCurseur(progres)
                    prefs.edit().putInt(PreferencesApp.CLE_TUNER_REACTIVITE, progres).apply()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    // Un changement de slider EN COURS de log
                    // tombe dans le canal d'événements, sinon
                    // les échantillons qui suivent deviennent
                    // ininterprétables (0.14).
                    if (tele.enCours) {
                        tele.evenement(
                            teleTms(),
                            EnregistreurTelemetrie.EVT_SLIDER,
                            mapOf(
                                "valeur" to curseurReactivite,
                                "alpha" to alphaDepuisCurseur(curseurReactivite)
                            )
                        )
                    }
                }
            })
        }
        rangeeReactivite.addView(seek)
        rangeeReactivite.addView(TextView(context).apply {
            text = "100 % réactif"
            textSize = 12f
            setTextColor(COUL_DIM)
        })
        vueAccord.addView(rangeeReactivite)

        // Ligne de diagnostic.
        vueAccord.addView(View(context).apply {
            setBackgroundColor(COUL_LINE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
            ).apply { setMargins(0, dp(10f), 0, 0) }
        })
        txtDiag = TextView(context).apply {
            text = "En attente du micro. Active le micro, puis joue une " +
                "note près du téléphone. Capture native sans aucun " +
                "traitement (ni écho, ni réduction de bruit, ni gain " +
                "automatique)."
            textSize = 12.5f
            setTextColor(COUL_DIM)
            setLineSpacing(0f, 1.55f)
            fontFeatureSettings = "\"tnum\""
            setPadding(0, dp(8f), 0, 0)
        }
        vueAccord.addView(txtDiag)

        // Bas de l'écran (0.14) : les réglages fins de
        // l'affichage, puis le bloc télémétrie (start/stop du
        // log envoyé par le pont).
        construireBlocReglages()
        construireBlocTelemetrie()

        chargerDonnees()
        majBannierePlantage()
    }

    /** Affiche la trace du dernier plantage s'il en existe une. */
    private fun majBannierePlantage() {
        if (!::bannierePlantage.isInitialized) return
        val trace = TracePlantage.lire(context)
        if (trace != null) {
            txtPlantage.text = "⚠ L'app a planté à sa dernière " +
                "session. Voici la trace — lis-la-moi ou " +
                "montre-la-moi, puis efface-la :\n\n" + trace
            bannierePlantage.visibility = View.VISIBLE
        } else {
            bannierePlantage.visibility = View.GONE
        }
    }

    private fun majTexteOrientation() {
        texteOrientation.text =
            if (interrupteurPaysage.isChecked) "écran couché" else "écran debout"
    }

    // ----------------------------------------------------------------
    // Données et configuration du moteur (miroir d'app.js).
    // ----------------------------------------------------------------

    private fun chargerDonnees() {
        try {
            val json = context.assets.open("tuner/tunings.json")
                .readBytes().decodeToString()
            val d = parserDonneesAccords(json)
            donnees = d
            pitch = d.referencePitch
            chargerMesAccords()
            tuningId = d.tunings[0].id
            construireSpinnerAccords()
            val idxPitch = d.pitchChoicesHz.indexOf(pitch)
            if (idxPitch > 0) segPitch.selectionner(idxPitch, false)
            remplirSpinnerNotes()
            refreshMeta()
        } catch (e: Exception) {
            txtErr.text = "Impossible de charger tunings.json : ${e.message}"
        }
    }

    /**
     * (Re)charge « Mes accords » depuis les fichiers locaux et
     * reconstruit la liste unifiée. Les accords enregistrés
     * reçoivent des ids négatifs (−1, −2, …), du plus récent au
     * plus ancien ; ceux de Simon gardent leurs ids et ne sont
     * jamais modifiés.
     */
    private fun chargerMesAccords() {
        val d = donnees ?: return
        val sauvegardes = chargerAccordsSauvegardes(context)
        val fichiers = mutableMapOf<Int, String>()
        val miens = sauvegardes.mapIndexed { idx, s ->
            val idNeg = -(idx + 1)
            fichiers[idNeg] = s.idFichier
            s.accord.copy(id = idNeg)
        }
        fichierParAccordId = fichiers
        tousAccords = d.tunings + miens
    }

    /** Sélecteur d'accords à deux sections : Simon, puis les miens. */
    private fun construireSpinnerAccords() {
        val d = donnees ?: return
        val libelles = mutableListOf<String>()
        val lignes = mutableListOf<Int?>()
        libelles.add("── Accords de Simon ──")
        lignes.add(null)
        for (a in d.tunings) {
            libelles.add("${a.name} (${a.serial})")
            lignes.add(a.id)
        }
        val miens = tousAccords.filter { it.id < 0 }
        if (miens.isNotEmpty()) {
            libelles.add("── Mes accords ──")
            lignes.add(null)
            for (a in miens) {
                libelles.add(
                    if (a.serial.isNotBlank()) "${a.name} (${a.serial})" else a.name
                )
                lignes.add(a.id)
            }
        }
        lignesAccords = lignes
        spinAccord.adapter = adaptateurSpinner(libelles)
        val pos = lignes.indexOf(tuningId)
        if (pos >= 0) spinAccord.setSelection(pos)
        spinAccord.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    val idAccord = lignesAccords.getOrNull(pos)
                    if (idAccord == null) {
                        // En-tête de section : pas un accord — on
                        // revient à l'accord courant.
                        val retour = lignesAccords.indexOf(tuningId)
                        if (retour >= 0 && retour != pos) {
                            spinAccord.setSelection(retour)
                        }
                        return
                    }
                    if (idAccord == tuningId) {
                        majBoutonSupprimer()
                        return
                    }
                    tuningId = idAccord
                    if (tele.enCours) {
                        tele.evenement(
                            teleTms(), EnregistreurTelemetrie.EVT_ACCORD,
                            mapOf(
                                "accord_id" to idAccord,
                                "accord" to currentTuning().name
                            )
                        )
                    }
                    remplirSpinnerNotes()
                    sendConfig()
                    majBoutonSupprimer()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        majBoutonSupprimer()
    }

    private fun majBoutonSupprimer() {
        if (::btnSupprimerAccord.isInitialized) {
            btnSupprimerAccord.visibility =
                if (tuningId < 0) View.VISIBLE else View.GONE
        }
    }

    /** Suppression d'un accord de « Mes accords », avec confirmation. */
    private fun confirmerSuppressionAccord() {
        val fichier = fichierParAccordId[tuningId] ?: return
        val nom = currentTuning().name
        AlertDialog.Builder(context)
            .setTitle("Supprimer cet accord ?")
            .setMessage(
                "« $nom » sera supprimé définitivement de " +
                    "« Mes accords ». Les accords de Simon ne sont " +
                    "jamais touchés."
            )
            .setPositiveButton("Supprimer") { _, _ ->
                supprimerAccordSauvegarde(context, fichier)
                chargerMesAccords()
                tuningId = donnees!!.tunings[0].id
                construireSpinnerAccords()
                remplirSpinnerNotes()
                sendConfig()
                txtDiag.text = "Accord « $nom » supprimé de « Mes accords »."
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun remplirSpinnerNotes() {
        if (donnees == null) return
        val accord = currentTuning()
        // Écouteur coupé pendant le remplissage : poser un nouvel
        // adaptateur peut déclencher l'ancien écouteur avec la
        // position 0 — un faux changement de note.
        spinNote.onItemSelectedListener = null
        spinNote.adapter = adaptateurSpinner(
            accord.notes.map { "${it.key} · ${it.name}" }
        )
        spinNote.setSelection(key - 1)
        spinNote.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    if (pos + 1 == key) return
                    setKey(pos + 1, false)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun currentTuning(): Accord =
        tousAccords.first { it.id == tuningId }

    private fun currentNote(): NoteAccord = currentTuning().notes[key - 1]

    private fun targetHz(): Double =
        currentNote().partialTargetHz440 * (pitch / 440.0)

    private fun tableId(): String = "$tuningId@$pitch"

    private fun notesTable(): List<NoteBrute> =
        currentTuning().notes.map {
            NoteBrute(
                key = it.key,
                fundamentalHz = it.fundamentalHz440 * (pitch / 440.0),
                partialTargetHz = it.partialTargetHz440 * (pitch / 440.0),
                listenPartial = it.listenPartial
            )
        }

    private fun sendConfig() {
        if (donnees == null) return
        moteur.config(mode, key, tableId(), notesTable())
        appliquerParametres()
        refreshMeta()
    }

    private fun refreshMeta() {
        if (donnees == null) return
        val n = currentNote()
        txtNomNote.text = n.name
        if (spinNote.selectedItemPosition != key - 1) {
            spinNote.setSelection(key - 1)
        }
        txtMeta.text = "Touche ${n.key} · partiel écouté nº ${n.listenPartial} · " +
            "cible du partiel ${fmt(targetHz(), 1)} Hz · fondamental cible " +
            "${fmt(n.fundamentalHz440 * pitch / 440.0, 1)} Hz"
    }

    private fun setMode(m: String) {
        if (m != mode && tele.enCours) {
            tele.evenement(
                teleTms(), EnregistreurTelemetrie.EVT_MODE,
                mapOf("mode" to m)
            )
        }
        mode = m
        val idx = listOf("auto", "demi", "bloque").indexOf(m)
        if (idx >= 0 && segMode.choix != idx) segMode.selectionner(idx, false)
        if (m == "bloque") txtBadge.text = ""
        sendConfig()
    }

    private fun setKey(k: Int, fromDetector: Boolean) {
        key = k
        if (fromDetector) {
            // Le moteur a déjà verrouillé son traqueur.
            txtBadge.text = "🎯 note détectée"
            refreshMeta()
        } else {
            // Règle (Grégoire, 0.10) : désigner une note — geste,
            // boutons, liste déroulante, redésignation de la même —
            // ne change JAMAIS le mode courant. « Si je lui dis
            // quelle note c'est, on ne bascule pas de mode. » Le
            // mode ne change que par son sélecteur explicite
            // (setMode). En Bloqué, sendConfig() fait re-verrouiller
            // le traqueur sur la note par le moteur ; en Auto et
            // Demi-ton, la note devient la référence courante
            // (en Demi-ton, elle recentre la fenêtre de détection)
            // et la détection continue.
            sendConfig()
        }
    }

    // Gestes sur le rond central : glisser ← → = demi-ton (−1/+1),
    // glisser ↑ ↓ = octave (+12/−12). Désigner une note ne change
    // jamais le mode (voir setKey).
    private fun installerGestesRond(vue: View) {
        var debutX = 0f
        var debutY = 0f
        vue.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    debutX = ev.x
                    debutY = ev.y
                    // Le rond garde son geste : le ScrollView parent
                    // ne doit pas l'intercepter (touch-action:none
                    // de la page web).
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - debutX
                    val dy = ev.y - debutY
                    val ax = abs(dx)
                    val ay = abs(dy)
                    if (max(ax, ay) >= dp(24f)) {
                        val delta = if (ax > ay) {
                            if (dx > 0) 1 else -1
                        } else {
                            if (dy < 0) 12 else -12
                        }
                        val nk = max(1, min(88, key + delta))
                        if (nk != key) {
                            setKey(nk, false)
                            rondNote.background = fondOvale(COUL_BG, COUL_ACCENT)
                            gestionnaire.postDelayed({
                                rondNote.background = fondOvale(COUL_BG, COUL_LINE)
                            }, 160)
                        }
                    }
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }

    // ----------------------------------------------------------------
    // Capture micro : pilotée par l'arbitre (0.13). startMic et
    // stopMic restent la mécanique basse (moteur neuf, routeur,
    // boucle d'affichage) ; ils ne sont plus appelés que par
    // appliquerArbitrage, qui applique la décision de
    // l'ArbitreMicro. Tous les événements du monde — visibilité,
    // premier plan, permission, parcours, Oreille — passent par
    // lui, aucun ne démarre ni n'arrête la capture en douce.
    // ----------------------------------------------------------------

    /**
     * Applique la décision de l'arbitre à la capture réelle :
     * démarre si le tuner doit capturer et ne capture pas,
     * arrête s'il capture sans en avoir le droit, et tient le
     * bandeau « micro pris par l'Oreille » à jour. Idempotent —
     * chaque événement du monde peut l'appeler sans risque.
     */
    private fun appliquerArbitrage() {
        val bloque = arbitre.tunerBloqueParOreille
        if (::banniereOreille.isInitialized) {
            banniereOreille.visibility =
                if (bloque) View.VISIBLE else View.GONE
        }
        // Télémétrie (0.14) : une préemption du micro par
        // l'Oreille — et son rendre — tombent dans le canal
        // d'événements du log en cours : sans elles, le trou
        // d'échantillons au milieu d'une session serait
        // inexplicable.
        if (tele.enCours && bloque != teleMicroPreempte) {
            teleMicroPreempte = bloque
            tele.evenement(
                teleTms(), EnregistreurTelemetrie.EVT_MICRO,
                mapOf("etat" to if (bloque) "preempte_oreille" else "rendu")
            )
        }
        if (arbitre.tunerDoitCapturer) {
            if (!micActif) {
                if (routeur.permissionAccordee()) {
                    startMic()
                } else if (!parcoursOuvert) {
                    // Permission absente : l'état se dit, comme
                    // avant — la demande système part à
                    // l'ouverture de l'onglet, côté activité.
                    txtDiag.text = "🔇 Micro arrêté — la permission " +
                        "microphone n'est pas accordée à l'app " +
                        "Gestion. Accorde-la (Réglages Android → " +
                        "Applications → Gestion → Autorisations → " +
                        "Microphone) : l'écoute démarrera toute seule."
                }
            }
            return
        }
        if (micActif) {
            val pourOreille =
                arbitre.proprietaire == ArbitreMicro.Proprietaire.OREILLE
            stopMic()
            if (pourOreille && parcoursOuvert && parcours.mesuresVisibles) {
                // Une mesure d'ancre était en cours : la pause
                // se dit à l'écran de l'ancre aussi (le bandeau
                // couvre le reste de la fenêtre).
                parcours.afficherErreurCapture(
                    "🎧 Micro pris par l'Oreille — écoute en " +
                        "cours : la mesure est en pause. La " +
                        "capture repartira toute seule à la fin " +
                        "de l'écoute ; rejoue la note à ce moment."
                )
            }
        }
        if (bloque && !parcoursOuvert) {
            // Écran principal affiché mais micro à l'Oreille :
            // la ligne de diagnostic le dit aussi — elle ne doit
            // pas continuer d'annoncer une écoute qui n'a pas lieu.
            txtDiag.text = "🎧 Écoute Oreille en cours — le " +
                "micro est à elle. Le tuner reprendra tout " +
                "seul dès qu'elle aura fini."
        }
    }

    /**
     * Relit l'état d'écoute de l'Oreille et, s'il a changé, le
     * rapporte à l'arbitre puis applique. Appelée par l'écouteur
     * de la préférence (réaction immédiate à la pose/levée du
     * drapeau par le service) et par le sondage de l'onglet.
     */
    private fun majEtatOreille() {
        val enEcoute = oreilleEnEcouteMaintenant()
        if (enEcoute != arbitre.oreilleEnEcoute) {
            arbitre.ecouteOreilleChange(enEcoute)
            appliquerArbitrage()
        }
    }

    /**
     * L'Oreille écoute-t-elle (ou démarre-t-elle) en ce moment ?
     * Lecture de ses deux publications existantes, jamais un
     * état deviné :
     * - le fichier d'état du service tranche quand il est là :
     *   « ecoute » avec un « maj_ms » frais (le service le
     *   republie chaque seconde pendant l'écoute) ou
     *   « chargement » avec un fichier frais (la séance démarre,
     *   le modèle se charge — le micro est déjà réservé) ;
     *   ses phases terminales (« traitement » — l'enregistreur
     *   est déjà libéré —, « termine », « erreur », « idle »)
     *   disent non ;
     * - sinon, la préférence « ecoute_en_cours » que le service
     *   pose juste avant de démarrer son enregistreur et lève
     *   à l'arrêt.
     * Les fraîcheurs exigées empêchent un état figé (service
     * tué sans avoir pu écrire) de bloquer le tuner pour
     * toujours : le blocage, lui, reste visible (bandeau).
     */
    private fun oreilleEnEcouteMaintenant(): Boolean {
        var phase: String? = null
        var majMs = 0L
        var modifMs = 0L
        var fichierLu = false
        try {
            val f = File(context.filesDir, OreilleService.FICHIER_ETAT)
            if (f.exists()) {
                val json = JSONObject(f.readText())
                phase = json.optString("phase")
                majMs = json.optLong("maj_ms", 0)
                modifMs = f.lastModified()
                fichierLu = true
            }
        } catch (e: Exception) {
            // Fichier illisible : on s'en remet à la préférence.
            fichierLu = false
        }
        if (fichierLu) {
            val maintenant = System.currentTimeMillis()
            when (phase) {
                "ecoute" ->
                    if (maintenant - majMs < 4_000) return true
                "chargement" ->
                    if (maintenant - modifMs < 30_000) return true
                "traitement", "termine", "erreur", "idle" ->
                    return false
            }
        }
        return prefs.getBoolean(PreferencesApp.CLE_ECOUTE_EN_COURS, false)
    }

    private fun demarrerSondageOreille() {
        if (!sondageOreilleActif) {
            sondageOreilleActif = true
            gestionnaire.post(sondageOreille)
        }
    }

    private fun arreterSondageOreille() {
        sondageOreilleActif = false
        gestionnaire.removeCallbacks(sondageOreille)
    }

    private fun startMic() {
        txtErr.text = ""
        if (!routeur.permissionAccordee()) {
            demanderPermissionMicro()
            txtErr.text = "Permission micro non accordée — je la " +
                "demande à Android ; accorde-la : l'écoute " +
                "démarrera toute seule, sans autre geste."
            return
        }
        // Moteur neuf à chaque démarrage (comme le processeur worklet,
        // recréé avec son contexte audio dans la version web).
        moteur = TunerEngine(TunerCapture.TAUX)
        routeur.moteur = moteur
        seqAdoptionsVue = 0
        sendConfig()
        val erreur = routeur.demarrer()
        if (erreur != null) {
            txtErr.text = erreur
            return
        }
        micActif = true
        dernierTotalEch = 0
        dernierMouvementEchMs = SystemClock.elapsedRealtime()
        txtDiag.text = "Capture native AudioRecord · 48000 Hz · PCM " +
            "16 bits mono · micro sans traitement · permission " +
            "accordée · enregistrement actif · en écoute… joue une " +
            "note près du micro."
        demarrerBoucle()
    }

    private fun stopMic() {
        routeur.arreter()
        micActif = false
        afficherCentsIncertain()
        barre.fracAiguille = 0.5
        barre.aiguilleOk = false
        barre.aiguilleOpacite = 0.25f
        barre.invalidate()
        arreterBoucle()
    }

    private fun surErreurCapture(msg: String) {
        if (!micActif && !routeur.enCours) return
        micActif = false
        txtErr.text = msg
        if (parcoursOuvert) {
            parcours.afficherErreurCapture(msg)
        }
        afficherCentsIncertain()
        arreterBoucle()
    }

    /** Arrêt imposé de l'extérieur (activité détruite) : la
     *  capture ne survit pas à l'écran, quoi que dise l'arbitre. */
    fun arreterCapture() {
        arreterSondageOreille()
        if (micActif || routeur.enCours) stopMic()
    }

    /** L'activité signale l'accord de la permission micro :
     *  l'arbitre réévalue — l'écoute repart toute seule si la
     *  fenêtre affichée veut le micro et que l'Oreille ne le
     *  détient pas. */
    fun surPermissionMicroAccordee() {
        if (txtErr.text.isNotEmpty()) txtErr.text = ""
        appliquerArbitrage()
    }

    /**
     * L'app passe en arrière-plan (appelée par l'activité à
     * onPause) : l'arbitre retire le micro au tuner — la capture
     * du tuner ne roule jamais en arrière-plan (0.6), et c'est
     * désormais l'arbitre qui le garantit, comme pour le reste.
     */
    fun surArrierePlan() {
        arbitre.appPasseEnArrierePlan()
        appliquerArbitrage()
    }

    /**
     * L'app revient au premier plan (appelée par l'activité à
     * onResume, quel que soit l'onglet courant — le drapeau de
     * l'arbitre doit rester frais) : l'arbitre réévalue, avec
     * l'état d'écoute du moment. Si une écoute Oreille roule,
     * le tuner ne reprend rien.
     */
    fun surPremierPlan() {
        arbitre.appRevientAuPremierPlan()
        majEtatOreille()
        appliquerArbitrage()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            // L'onglet se cache : l'arbitre le sait AVANT la
            // fermeture du parcours, pour que celle-ci (qui
            // ramène l'écran principal, donc le vouloir-micro)
            // ne relance rien au passage.
            arbitre.ongletCache()
            arreterSondageOreille()
            if (parcoursOuvert) {
                // Parcours quitté en route : le brouillon (ancres
                // déjà mesurées) est sauvegardé à chaque ancre —
                // il sera proposé à la reprise.
                fermerParcours()
            }
            appliquerArbitrage()
        } else if (isShown) {
            majBannierePlantage()
            if (!parcoursOuvert) {
                TracePlantage.etape = "accordeur — écran principal"
            }
            // Fenêtre du tuner affichée : l'arbitre décide —
            // capture si le micro est libre, bandeau explicite
            // si l'Oreille le détient (0.13). Le sondage de
            // l'état de l'Oreille part avec l'onglet.
            arbitre.ongletMontre()
            demarrerSondageOreille()
            majEtatOreille()
            appliquerArbitrage()
            // Un log de télémétrie resté en file (arrêté hors
            // ligne) repart à l'ouverture de l'onglet, comme
            // le reste du pont repart à l'ouverture de l'app.
            Thread { Telemetrie.viderPieces(context) }.start()
        }
    }

    override fun onDetachedFromWindow() {
        arreterSondageOreille()
        prefs.unregisterOnSharedPreferenceChangeListener(ecouteurEtatOreille)
        super.onDetachedFromWindow()
    }

    // ----------------------------------------------------------------
    // Boucle d'affichage : aiguille et lecture numérique sur la
    // même valeur lissée (voir rendre()).
    // ----------------------------------------------------------------

    private var boucleActive = false
    private val boucleRendu = object : Runnable {
        override fun run() {
            if (!boucleActive) return
            rendre()
            gestionnaire.postDelayed(this, 16)
        }
    }

    private fun demarrerBoucle() {
        if (!boucleActive) {
            boucleActive = true
            gestionnaire.post(boucleRendu)
        }
    }

    private fun arreterBoucle() {
        boucleActive = false
        gestionnaire.removeCallbacks(boucleRendu)
    }

    private fun rendre() {
        if (!micActif) return
        // Adoption d'une note par le détecteur (message « note »).
        val seq = moteur.seqAdoptions
        if (seq != seqAdoptionsVue) {
            seqAdoptionsVue = seq
            val adoptee = moteur.derniereNoteAdoptee
            if (adoptee != null) {
                if (adoptee != key) setKey(adoptee, true)
                else {
                    txtBadge.text = "🎯 note détectée"
                    refreshMeta()
                }
                if (tele.enCours) {
                    tele.evenement(
                        teleTms(),
                        EnregistreurTelemetrie.EVT_NOTE_ADOPTEE,
                        mapOf("note" to adoptee)
                    )
                }
            }
        }
        // Chien de garde : une capture « active » mais muette se dit.
        val tot = routeur.echantillonsTotal
        val maintenant = SystemClock.elapsedRealtime()
        if (tot != dernierTotalEch) {
            dernierTotalEch = tot
            dernierMouvementEchMs = maintenant
        } else if (maintenant - dernierMouvementEchMs > 1500 &&
            txtErr.text.isEmpty()
        ) {
            txtErr.text = "Aucun échantillon reçu du micro depuis " +
                "plus de 1,5 s — la capture semble muette (état : " +
                (if (routeur.enCours) "actif" else "inactif") +
                ", échantillons reçus : $tot)."
        }
        val s = moteur.dernierEtat ?: return
        val latest = s.latest
        // Suivi continu (0.10) : ce qui décide si la lecture est
        // affichée, c'est la FRAÎCHEUR de la sortie du traqueur —
        // une estimation admise il y a moins que la tenue réglée
        // — et non la force de la trame courante. Tant que la
        // corde sonne et que le traqueur la suit, la valeur
        // affichée bouge en continu pendant le tour de cheville ;
        // si la trame courante est faible, elle est affichée
        // « tenue » (en retrait) mais CONTINUE de suivre. Une
        // valeur que le traqueur ne rafraîchit plus est lâchée
        // après la tenue (« signal incertain ») au lieu d'être
        // tenue figée jusqu'à 2,5 s comme avant — c'était
        // l'autre moitié du « le lecteur attend une frappe ».
        // La tenue est un des réglages fins de l'écran (0.14,
        // « Tenue si le son baisse », 750 ms par défaut).
        val suiviFrais = latest != null && s.ageSec <= params.tenueMs / 1000.0
        val live = suiviFrais && s.frameConf >= 0.35
        val holding = suiviFrais && !live
        if (latest != null && suiviFrais) {
            val c = latest.cents
            // Changement de cible du traqueur (adoption ou
            // désignation) : la valeur lissée repart de la lecture
            // brute au lieu de glisser depuis l'écart de la note
            // précédente.
            if (moteur.activeKey != cleDispCents) {
                dispCents = c
                cleDispCents = moteur.activeKey
            } else {
                dispCents += (c - dispCents) * smoothAlpha
            }
            // Chiffre et aiguille affichent LA MÊME valeur lissée.
            // Avant la 0.10, le chiffre montrait la lecture brute
            // du traqueur pendant que l'aiguille, lissée, traînait
            // derrière puis rattrapait d'un coup — le « −0,1…
            // woups +0,2 » de Grégoire. Le brut reste visible à la
            // ligne de diagnostic.
            afficherCentsMesure(dispCents, holding)
            barre.fracAiguille = centsToFrac(dispCents)
            barre.aiguilleOk = abs(dispCents) <= 0.1
            barre.aiguilleOpacite = if (holding) 0.55f else 1f
            barre.invalidate()
            txtDiag.text = "Mesure : ${fmt(latest.freqHz, 2)} Hz · brut " +
                "${fmtSigne(latest.rawCents, 2)} ¢ · RSB ${fmt(s.snrDb, 0)} dB · " +
                "niveau ${fmt(s.levelDb, 0)} dBFS · contexte 48000 Hz" +
                (if (holding) " · signal faiblissant — mesure tenue" else "")
        } else {
            afficherCentsIncertain()
            barre.aiguilleOk = false
            barre.aiguilleOpacite = 0.25f
            barre.invalidate()
            txtDiag.text = "Signal faible ou instable · RSB " +
                "${fmt(s.snrDb, 0)} dB · niveau ${fmt(s.levelDb, 0)} dBFS · " +
                "contexte 48000 Hz · éch. reçus $tot — rapproche le " +
                "téléphone des cordes."
        }
        // Télémétrie (0.14) : un échantillon par nouvel état du
        // moteur (≈ un par analyse du traqueur, ~85 ms).
        echantillonnerTelemetrie(s)
    }

    private fun afficherCentsMesure(c: Double, holding: Boolean) {
        txtCents.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
        txtCents.typeface = Typeface.DEFAULT_BOLD
        txtCents.setTextColor(if (abs(c) <= 0.1) COUL_OK else COUL_TXT)
        txtCents.alpha = if (holding) 0.55f else 1f
        txtCents.text = "${fmtSigne(c, 1)} ¢"
    }

    private fun afficherCentsIncertain() {
        txtCents.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        txtCents.typeface = Typeface.DEFAULT
        txtCents.setTextColor(COUL_DIM)
        txtCents.alpha = 1f
        txtCents.text = "signal incertain"
    }

    // ----------------------------------------------------------------
    // Réglages fins de l'affichage et télémétrie (0.14) — le bas
    // de l'écran accordeur. Les six valeurs sont validées par
    // ParametresAffichage (pur, testé), persistées en texte et
    // appliquées en direct ; le log de télémétrie part par le
    // pont à l'arrêt (gestion/Telemetrie.kt).
    // ----------------------------------------------------------------

    /** Relit les six valeurs persistées (défauts si absentes
     * ou invalides, champ par champ). */
    private fun chargerParametres(): ParametresAffichage.Valeurs =
        ParametresAffichage.depuisTextes(
            prefs.getString(PreferencesApp.CLE_TUNER_PIC_MS, null),
            prefs.getString(PreferencesApp.CLE_TUNER_PLEIN_POIDS_MS, null),
            prefs.getString(PreferencesApp.CLE_TUNER_MEDIANE_ECH, null),
            prefs.getString(PreferencesApp.CLE_TUNER_TENUE_MS, null),
            prefs.getString(PreferencesApp.CLE_TUNER_CONFIANCE_PCT, null),
            prefs.getString(PreferencesApp.CLE_TUNER_PORTE_CENTS, null)
        )

    /**
     * Applique les valeurs courantes au moteur et à SON
     * traqueur (la tenue, elle, est lue par rendre() à chaque
     * trame). Appelé par sendConfig() — donc aussi à chaque
     * démarrage du micro, le moteur étant recréé — et à chaque
     * modification d'un champ. Le parcours n'est pas touché.
     */
    private fun appliquerParametres() {
        moteur.fenetreAttaqueMs = params.picMs
        moteur.pleinPoidsMs = params.pleinPoidsMs
        moteur.porteBaseCents = params.porteCents
        moteur.tracker.seuilAdmission = params.seuilConfiance
        moteur.tracker.tailleHistorique = params.medianeEch
    }

    private fun persisterParam(champ: ParametresAffichage.Champ) {
        val cle = when (champ) {
            ParametresAffichage.Champ.PIC_MS ->
                PreferencesApp.CLE_TUNER_PIC_MS
            ParametresAffichage.Champ.PLEIN_POIDS_MS ->
                PreferencesApp.CLE_TUNER_PLEIN_POIDS_MS
            ParametresAffichage.Champ.MEDIANE_ECH ->
                PreferencesApp.CLE_TUNER_MEDIANE_ECH
            ParametresAffichage.Champ.TENUE_MS ->
                PreferencesApp.CLE_TUNER_TENUE_MS
            ParametresAffichage.Champ.CONFIANCE_PCT ->
                PreferencesApp.CLE_TUNER_CONFIANCE_PCT
            ParametresAffichage.Champ.PORTE_CENTS ->
                PreferencesApp.CLE_TUNER_PORTE_CENTS
        }
        prefs.edit()
            .putString(cle, ParametresAffichage.textePersistant(params, champ))
            .apply()
    }

    /** Nom machine d'un champ, pour le canal d'événements. */
    private fun nomChamp(champ: ParametresAffichage.Champ): String =
        when (champ) {
            ParametresAffichage.Champ.PIC_MS -> "pic_ms"
            ParametresAffichage.Champ.PLEIN_POIDS_MS -> "plein_poids_ms"
            ParametresAffichage.Champ.MEDIANE_ECH -> "mediane_ech"
            ParametresAffichage.Champ.TENUE_MS -> "tenue_ms"
            ParametresAffichage.Champ.CONFIANCE_PCT -> "confiance_pct"
            ParametresAffichage.Champ.PORTE_CENTS -> "porte_cents"
        }

    private fun separateurBas(): View = View(context).apply {
        setBackgroundColor(COUL_LINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
        ).apply { setMargins(0, dp(12f), 0, 0) }
    }

    private fun titreBas(texte: String): TextView = TextView(context).apply {
        text = texte
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COUL_TXT)
        setPadding(0, dp(8f), 0, dp(2f))
    }

    /** Construit le bloc des six champs, dans l'ordre convenu :
     * les trois de Grégoire d'abord, les trois ajoutés ensuite. */
    private fun construireBlocReglages() {
        vueAccord.addView(separateurBas())
        vueAccord.addView(titreBas("Réglages fins de l'affichage"))
        vueAccord.addView(TextView(context).apply {
            text = "Appliqués en direct et gardés d'une session " +
                "à l'autre. Une valeur illisible ou hors plage " +
                "est refusée : l'ancienne tient."
            textSize = 12f
            setTextColor(COUL_DIM)
            setPadding(0, 0, 0, dp(4f))
        })
        vueAccord.addView(rangeeParam(
            ParametresAffichage.Champ.PIC_MS,
            "Pic de départ ignoré (ms)", false
        ))
        vueAccord.addView(rangeeParam(
            ParametresAffichage.Champ.MEDIANE_ECH,
            "Échantillons de la médiane", false
        ))
        vueAccord.addView(rangeeParam(
            ParametresAffichage.Champ.TENUE_MS,
            "Tenue si le son baisse (ms)", false
        ))
        vueAccord.addView(rangeeParam(
            ParametresAffichage.Champ.PLEIN_POIDS_MS,
            "Plein poids d'attaque à (ms)", false
        ))
        vueAccord.addView(rangeeParam(
            ParametresAffichage.Champ.CONFIANCE_PCT,
            "Confiance minimale (%)", false
        ))
        vueAccord.addView(rangeeParam(
            ParametresAffichage.Champ.PORTE_CENTS,
            "Porte de continuité (± ¢)", true
        ))
    }

    /** Une rangée libellé + champ numérique éditable. La saisie
     * est validée à la perte de focus et sur « Terminé » du
     * clavier ; refusée, le champ réaffiche la valeur en
     * vigueur — aucun état ambigu. */
    private fun rangeeParam(
        champ: ParametresAffichage.Champ, libelle: String, decimal: Boolean
    ): LinearLayout {
        val rangee = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3f), 0, dp(3f))
        }
        val etiq = etiquette(context, libelle)
        etiq.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        rangee.addView(etiq)
        val saisie = EditText(context).apply {
            setText(ParametresAffichage.texteDe(params, champ))
            textSize = 15f
            setTextColor(COUL_TXT)
            isSingleLine = true
            inputType = if (decimal) {
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                dp(104f), ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        champsParams[champ] = saisie
        val valider: () -> Unit = {
            val nouveau = ParametresAffichage.appliquer(
                params, champ, saisie.text?.toString()
            )
            if (nouveau != null && nouveau != params) {
                params = nouveau
                persisterParam(champ)
                appliquerParametres()
                if (tele.enCours) {
                    tele.evenement(
                        teleTms(),
                        EnregistreurTelemetrie.EVT_PARAMETRE,
                        mapOf(
                            "champ" to nomChamp(champ),
                            "valeur" to
                                ParametresAffichage.texteDe(params, champ)
                        )
                    )
                }
            }
            // Valide ou non, le champ montre la valeur en
            // vigueur (forme canonique).
            saisie.setText(ParametresAffichage.texteDe(params, champ))
        }
        saisie.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                valider()
                true
            } else {
                false
            }
        }
        saisie.onFocusChangeListener =
            View.OnFocusChangeListener { _, aLeFocus ->
                if (!aLeFocus) valider()
            }
        rangee.addView(saisie)
        return rangee
    }

    /** Construit le bloc télémétrie : boutons start/stop et
     * « Marquer », ligne d'état toujours visible. */
    private fun construireBlocTelemetrie() {
        vueAccord.addView(separateurBas())
        vueAccord.addView(titreBas("Télémétrie de l'affichage"))
        vueAccord.addView(TextView(context).apply {
            text = "Enregistre chaque lecture du tuner (hauteur, " +
                "confiance, niveau, décisions d'admission) et " +
                "l'envoie au pont à l'arrêt — de quoi régler " +
                "l'affichage sur des chiffres. Pendant le log, " +
                "« Marquer » note le moment où quelque chose te " +
                "semble faux."
            textSize = 12f
            setTextColor(COUL_DIM)
            setPadding(0, 0, 0, dp(6f))
        })
        val rangee = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnTele = Button(context).apply {
            text = "● Démarrer le log"
            textSize = 15f
            isAllCaps = false
            setTextColor(COUL_TXT)
            background = fondArrondi(context, COUL_PANEL, COUL_ACCENT, 8f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(8f) }
            setOnClickListener { basculerTelemetrie() }
        }
        rangee.addView(btnTele)
        btnMarquer = Button(context).apply {
            text = "Marquer"
            textSize = 15f
            isAllCaps = false
            setTextColor(COUL_TXT)
            background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            visibility = View.GONE
            setOnClickListener { marquerTelemetrie() }
        }
        rangee.addView(btnMarquer)
        vueAccord.addView(rangee)
        txtTeleStatut = TextView(context).apply {
            text = "Aucun log en cours."
            textSize = 12.5f
            setTextColor(COUL_DIM)
            fontFeatureSettings = "\"tnum\""
            setPadding(0, dp(6f), 0, 0)
        }
        vueAccord.addView(txtTeleStatut)
    }

    /** Horloge relative du log en cours (ms depuis son départ). */
    private fun teleTms(): Long =
        tele.dureeMs(SystemClock.elapsedRealtime())

    private fun basculerTelemetrie() {
        if (tele.enCours) arreterTelemetrie() else demarrerTelemetrie()
    }

    private fun demarrerTelemetrie() {
        teleVersionApp = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        teleSession = "tele-" + SimpleDateFormat(
            "yyyyMMdd-HHmmss", Locale.CANADA_FRENCH
        ).format(Date())
        teleNbMarques = 0
        teleFrappesVues = moteur.frappesDetectees
        teleDernierEtat = null
        teleMicroPreempte = arbitre.tunerBloqueParOreille
        tele.demarrer(
            SystemClock.elapsedRealtime(),
            EnregistreurTelemetrie.Entete(
                versionApp = teleVersionApp,
                debutIso = SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CANADA_FRENCH
                ).format(Date()),
                picMs = params.picMs,
                pleinPoidsMs = params.pleinPoidsMs,
                pleinPoidsEffectifMs = params.pleinPoidsEffectifMs,
                medianeEch = params.medianeEch,
                tenueMs = params.tenueMs,
                confiancePct = params.confiancePct,
                porteCents = params.porteCents,
                slider = curseurReactivite,
                alphaSlider = alphaDepuisCurseur(curseurReactivite),
                accordNom = if (donnees != null) {
                    tousAccords.firstOrNull { it.id == tuningId }?.name ?: "?"
                } else {
                    "?"
                },
                accordId = tuningId,
                diapason = pitch.toDouble(),
                tauxHz = TunerCapture.TAUX
            )
        )
        btnTele.text = "■ Arrêter et envoyer"
        btnMarquer.visibility = View.VISIBLE
        majStatutTelemetrie()
    }

    private fun arreterTelemetrie() {
        val maintenant = SystemClock.elapsedRealtime()
        val duree = tele.dureeMs(maintenant)
        val nb = tele.nbEchantillons
        val contenu = tele.arreter(maintenant)
        teleDernierEtat = null
        btnTele.text = "● Démarrer le log"
        btnMarquer.visibility = View.GONE
        txtTeleStatut.text = "Envoi du log au pont…"
        Telemetrie.envoyer(
            context, teleSession, contenu, nb, duree, teleVersionApp
        ) { _, message ->
            gestionnaire.post {
                if (::txtTeleStatut.isInitialized) {
                    txtTeleStatut.text = message
                }
            }
        }
    }

    /** Le bouton « Marquer » : un marqueur horodaté tombe dans
     * le log — le chaînon entre le verdict de Grégoire (« là ça
     * a sauté ») et les chiffres. */
    private fun marquerTelemetrie() {
        if (!tele.enCours) return
        teleNbMarques++
        tele.evenement(
            teleTms(), EnregistreurTelemetrie.EVT_MARQUE,
            mapOf("n" to teleNbMarques)
        )
        majStatutTelemetrie()
    }

    /** Ligne d'état du log : pendant l'enregistrement, durée et
     * compteurs, en permanence — rien d'invisible. */
    private fun majStatutTelemetrie() {
        if (!::txtTeleStatut.isInitialized) return
        if (!tele.enCours) return
        val dureeS = teleTms() / 1000
        txtTeleStatut.text = "● Log en cours — " +
            String.format(Locale.ROOT, "%d:%02d", dureeS / 60, dureeS % 60) +
            " · ${tele.nbEchantillons} échantillons" +
            " · ${tele.nbEvenements} événements" +
            " · $teleNbMarques marque(s)"
    }

    /**
     * Un échantillon de télémétrie par nouvel état publié par
     * le moteur (≈ une analyse du traqueur, ~85 ms), appelé à
     * la fin de rendre() : les valeurs d'affichage (médiane,
     * aiguille lissée) y sont fraîches. Les frappes nouvelles
     * depuis le dernier échantillon tombent d'abord dans le
     * canal d'événements.
     */
    private fun echantillonnerTelemetrie(s: EtatTuner) {
        if (!tele.enCours) return
        if (s === teleDernierEtat) return
        teleDernierEtat = s
        val frappes = moteur.frappesDetectees
        if (frappes != teleFrappesVues) {
            if (frappes > teleFrappesVues) {
                tele.evenement(
                    teleTms(), EnregistreurTelemetrie.EVT_FRAPPE,
                    mapOf("n" to frappes)
                )
            }
            teleFrappesVues = frappes
        }
        val tr = moteur.tracker
        val l = s.latest
        tele.echantillon(
            EnregistreurTelemetrie.Echantillon(
                tMs = teleTms(),
                note = moteur.activeKey ?: key,
                hz = l?.freqHz,
                centsBrut = l?.rawCents,
                centsMediane = l?.cents,
                centsAiguille = dispCents,
                confiance = s.frameConf,
                decision = tr.derniereDecision,
                poids = tr.poidsSuivi,
                niveauDb = s.levelDb,
                nbRetenues = tr.nbRetenuesSortie,
                ageSec = s.ageSec,
                slider = curseurReactivite,
                mode = moteur.mode
            )
        )
        majStatutTelemetrie()
    }

    // ----------------------------------------------------------------
    // Mode « Nouveau piano » : l'écran et la mécanique vivent
    // dans tuner/parcours/ (ParcoursMesureView +
    // ParcoursMesureControleur) depuis le chantier structure
    // 0.8. Ici ne restent que la bascule de mode et les services
    // d'hôte que le parcours consomme (HoteParcours).
    // ----------------------------------------------------------------

    private fun afficherMode(mode: ModeTuner) {
        modeTuner = mode
        vueAccord.visibility =
            if (mode == ModeTuner.ACCORDEUR) View.VISIBLE else View.GONE
        vueParcours.visibility =
            if (mode == ModeTuner.PARCOURS) View.VISIBLE else View.GONE
        if (mode == ModeTuner.ACCORDEUR) {
            // L'écran principal veut le micro (0.13 : c'est un
            // vouloir rapporté à l'arbitre, pas un démarrage —
            // le parcours, lui, pilote le sien via l'hôte).
            arbitre.ecranVeutMicro(true)
        }
    }

    private fun ouvrirParcours() {
        afficherMode(ModeTuner.PARCOURS)
        parcours.ouvrir()
    }

    override fun fermerParcours() {
        parcours.fermer()
        afficherMode(ModeTuner.ACCORDEUR)
        // Retour à l'écran principal : l'arbitre réévalue —
        // l'écoute y reprend toute seule si la fenêtre est
        // active et le micro libre (0.13). Si c'est la
        // disparition de l'onglet qui a fermé le parcours,
        // l'arbitre sait déjà l'onglet caché : rien ne repart.
        appliquerArbitrage()
    }

    // --- Services d'hôte du parcours (HoteParcours) ---

    override fun assurerMicro(): Boolean {
        // Une étape de mesure veut le micro : le vouloir part à
        // l'arbitre, qui tranche (fenêtre active ? Oreille ?).
        // La demande de permission reste attachée à ce geste,
        // comme avant. Retourne si la capture roule pour vrai.
        arbitre.ecranVeutMicro(true)
        if (!routeur.permissionAccordee()) demanderPermissionMicro()
        appliquerArbitrage()
        if (!micActif && !parcours.mesuresVisibles) {
            // La mesure n'a pas démarré (permission à venir ou
            // Oreille) et aucune étape de mesure n'est à
            // l'écran — on est resté sur un menu : le vouloir
            // ne survit pas, sinon la capture repartirait toute
            // seule sur le menu quand le micro se libérerait.
            // (Si une étape de mesure EST affichée, le vouloir
            // tient : la capture y reprendra d'elle-même.)
            arbitre.ecranVeutMicro(false)
        }
        return micActif
    }

    override fun arreterMicroSiActif() {
        // Un menu du parcours (formulaire, résumé) : l'écran ne
        // veut plus le micro — l'arbitre arrête la capture.
        arbitre.ecranVeutMicro(false)
        appliquerArbitrage()
    }

    override fun nomNote(cle: Int): String =
        donnees?.tunings?.firstOrNull()?.notes?.getOrNull(cle - 1)?.name
            ?: "Touche $cle"

    override fun nomsNotes(): List<String> =
        donnees?.tunings?.firstOrNull()?.notes
            ?.map { it.name } ?: emptyList()

    override fun diapasonActuel(): Int = pitch

    override fun accordEnregistre(
        idFichier: String, nom: String, dateIso: String, pitch: Int
    ) {
        chargerMesAccords()
        val nouvelId = fichierParAccordId.entries
            .firstOrNull { it.value == idFichier }?.key
        if (nouvelId != null) tuningId = nouvelId
        construireSpinnerAccords()
        val pos = lignesAccords.indexOf(tuningId)
        if (pos >= 0) spinAccord.setSelection(pos)
        remplirSpinnerNotes()
        sendConfig()
        txtDiag.text = "🎹 Accord « $nom » enregistré dans " +
            "« Mes accords » ($dateIso, la à $pitch Hz) et " +
            "sélectionné — prêt à accorder ce piano."
    }

}
