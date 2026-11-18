package ca.gregoirepainchaud.pixelbridge.tuner

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

private val COUL_BG = Color.rgb(0x10, 0x14, 0x18)
private val COUL_PANEL = Color.rgb(0x17, 0x1d, 0x23)
private val COUL_LINE = Color.rgb(0x2a, 0x33, 0x3d)
private val COUL_TXT = Color.rgb(0xe8, 0xed, 0xf1)
private val COUL_DIM = Color.rgb(0x8f, 0xa0, 0xae)
private val COUL_ACCENT = Color.rgb(0xd9, 0xa4, 0x41)
private val COUL_OK = Color.rgb(0x2e, 0xcc, 0x71)
private val COUL_BAD = Color.rgb(0xe7, 0x4c, 0x3c)
private val COUL_SUR_ACCENT = Color.rgb(0x14, 0x10, 0x0a)
private val COUL_CENTRE = Color.rgb(0x3d, 0x4a, 0x56)
private val COUL_TICK = Color.rgb(0x46, 0x54, 0x5f)

// Barre −30..+30 ¢ à échelle segmentée dilatée au centre (SPEC v1,
// reprise telle quelle d'app.js).
private val SEG_BARRE = arrayOf(
    doubleArrayOf(-30.0, 0.0), doubleArrayOf(-10.0, 0.10),
    doubleArrayOf(-1.0, 0.25), doubleArrayOf(0.0, 0.50),
    doubleArrayOf(1.0, 0.75), doubleArrayOf(10.0, 0.90),
    doubleArrayOf(30.0, 1.00)
)

internal fun centsToFrac(c0: Double): Double {
    val c = max(-30.0, min(30.0, c0))
    for (i in 0 until SEG_BARRE.size - 1) {
        val cA = SEG_BARRE[i][0]
        val fA = SEG_BARRE[i][1]
        val cB = SEG_BARRE[i + 1][0]
        val fB = SEG_BARRE[i + 1][1]
        if (c >= cA && c <= cB) return fA + (fB - fA) * (c - cA) / (cB - cA)
    }
    return if (c < 0) 0.0 else 1.0
}

/** La barre de mesure : fond, graduations ±(1, 5, 10, 20, 30) ¢, aiguille. */
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
) : ScrollView(context) {

    private val prefs = context.getSharedPreferences("bench", Context.MODE_PRIVATE)
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

    // --- État du parcours « Nouveau piano » (mesure des partiels) ---
    private var parcoursOuvert = false

    @Volatile
    private var analyseurActif: AnalyseurPartiels? = null
    private var ancresMesurees = mutableListOf<MesureAncre>()
    private var ancresPassees = mutableSetOf<Int>()
    private var ancreIndex = 0
    private var parcoursPitch = 440
    private var parcoursDebutMs = 0L
    private var ancreValidee: MesureAncre? = null
    private var ancreValideeMs = 0L
    private var verdictFaible: MesureAncre? = null
    private var tableGeneree: List<NoteAccord>? = null
    private var courbeGeneree: DoubleArray? = null
    private var boucleParcoursActive = false

    private var moteur: TunerEngine = TunerEngine(TunerCapture.TAUX)
    private val capture = TunerCapture(
        context,
        surEchantillons = { ech ->
            // Pendant le parcours, le flux micro alimente
            // l'analyseur de partiels plutôt que le traqueur.
            val a = analyseurActif
            if (parcoursOuvert && a != null) {
                // Filet (0.6) : ce code roule sur le fil de
                // capture — une exception de l'analyseur ne doit
                // jamais le tuer (donc tuer l'app). On détache
                // l'analyseur, la capture continue, et l'écran
                // de l'ancre affiche l'erreur franchement ; le
                // bouton « Rejouer cette note » relancera la
                // mesure. (TunerCapture a son propre filet, en
                // dernier recours, pour le moteur aussi.)
                try {
                    a.push(ech)
                } catch (e: Exception) {
                    analyseurActif = null
                    gestionnaire.post {
                        if (parcoursOuvert &&
                            ::txtAncreMessage.isInitialized
                        ) {
                            txtAncreMessage.text = "Erreur " +
                                "d'analyse : " +
                                "${e.javaClass.simpleName} — " +
                                "${e.message}. Pèse « ↺ Rejouer " +
                                "cette note » pour relancer la mesure."
                        }
                    }
                }
            } else {
                moteur.push(ech)
            }
        },
        surErreur = { msg ->
            gestionnaire.post { surErreurCapture(msg) }
        }
    )
    private var micActif = false
    private var dispCents = 0.0
    private var seqAdoptionsVue = 0
    private var dernierTotalEch = 0L
    private var dernierMouvementEchMs = 0L

    // Réactivité de la barre : même échelle et même défaut que la
    // version web (73 ≈ alpha 0,35), persistée (SharedPreferences —
    // l'équivalent natif du localStorage de la page).
    private var smoothAlpha: Double = 0.35
    private fun alphaDepuisCurseur(v: Int): Double = 0.02 * 50.0.pow(v / 100.0)

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
    private lateinit var bannierePlantage: LinearLayout
    private lateinit var txtPlantage: TextView
    private lateinit var rondNote: FrameLayout
    private lateinit var txtNomNote: TextView
    private lateinit var txtBadge: TextView
    private lateinit var txtMeta: TextView
    private lateinit var txtCents: TextView
    private lateinit var barre: BarreAccordView
    private lateinit var txtDiag: TextView

    // --- Éléments du parcours « Nouveau piano » ---
    private lateinit var txtParcoursMessage: TextView
    private lateinit var groupeFormulaire: LinearLayout
    private lateinit var txtBrouillon: TextView
    private lateinit var rangeeBrouillon: LinearLayout
    private lateinit var champClient: EditText
    private lateinit var champPiano: EditText
    private lateinit var champSerie: EditText
    private lateinit var txtPitchParcours: TextView
    private lateinit var groupeAncres: LinearLayout
    private lateinit var txtAncreProgres: TextView
    private lateinit var txtAncreNom: TextView
    private lateinit var txtPartiels: TextView
    private lateinit var txtAncreMessage: TextView
    private lateinit var btnAccepterFaible: Button
    private lateinit var groupeResume: LinearLayout
    private lateinit var txtResume: TextView
    private lateinit var btnEnregistrerAccord: Button

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
            vue.background = fondArrondi(COUL_PANEL, COUL_LINE, 8f)
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
                    b.background = fondArrondi(COUL_ACCENT, COUL_ACCENT, 6f)
                    b.setTextColor(COUL_SUR_ACCENT)
                    b.typeface = Typeface.DEFAULT_BOLD
                } else {
                    b.background = fondArrondi(COUL_PANEL, COUL_PANEL, 6f)
                    b.setTextColor(COUL_TXT)
                    b.typeface = Typeface.DEFAULT
                }
            }
        }
    }

    private fun fondArrondi(fill: Int, stroke: Int, rayonDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(rayonDp).toFloat()
            setColor(fill)
            setStroke(dp(1f), stroke)
        }

    private fun fondOvale(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2f), stroke)
        }

    private fun etiquette(texte: String): TextView = TextView(context).apply {
        text = texte
        textSize = 13f
        setTextColor(COUL_DIM)
        setPadding(0, 0, dp(6f), 0)
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
        vueParcours = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        // Bannière « dernier plantage » (0.6) : si le
        // gestionnaire de TracePlantage a écrit une trace à la
        // mort précédente de l'app, elle s'affiche ici — lisible
        // par Grégoire, dismissible. Remplie par
        // majBannierePlantage().
        bannierePlantage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = fondArrondi(COUL_PANEL, COUL_BAD, 8f)
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
            background = fondArrondi(COUL_PANEL, COUL_LINE, 8f)
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
        rangeeAccord.addView(etiquette("Accord"))
        spinAccord = Spinner(context).apply {
            background = fondArrondi(COUL_PANEL, COUL_LINE, 8f)
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
            background = fondArrondi(COUL_PANEL, COUL_LINE, 8f)
            visibility = View.GONE
            setOnClickListener { confirmerSuppressionAccord() }
        }
        rangeeAccord.addView(btnSupprimerAccord)
        vueAccord.addView(rangeeAccord)

        // Entrée du parcours « Nouveau piano » : mesurer
        // l'inharmonicité du piano devant soi (do-sol par octave)
        // et en tirer un accord sur mesure, enregistré à côté de
        // ceux de Simon.
        vueAccord.addView(Button(context).apply {
            text = "🎹 Nouveau piano — mesurer l'inharmonicité"
            textSize = 15f
            isAllCaps = false
            setTextColor(COUL_TXT)
            background = fondArrondi(COUL_PANEL, COUL_ACCENT, 8f)
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
        rangeePitch.addView(etiquette("Diapason"))
        segPitch = Segment(listOf("440", "441", "442"), 0) { idx ->
            pitch = listOf(440, 441, 442)[idx]
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
        rangeeMode.addView(etiquette("Mode"))
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
        rangeeOrientation.addView(etiquette("Écran"))
        interrupteurPaysage = Switch(context).apply {
            text = "Paysage"
            textSize = 15f
            setTextColor(COUL_TXT)
            isChecked = prefs.getBoolean("tuner_paysage", false)
        }
        texteOrientation = TextView(context).apply {
            textSize = 13f
            setTextColor(COUL_DIM)
            setPadding(dp(8f), 0, 0, 0)
        }
        majTexteOrientation()
        interrupteurPaysage.setOnCheckedChangeListener { _, coche ->
            prefs.edit().putBoolean("tuner_paysage", coche).apply()
            majTexteOrientation()
            appliquerOrientation(coche)
        }
        rangeeOrientation.addView(interrupteurPaysage)
        rangeeOrientation.addView(texteOrientation)
        vueAccord.addView(rangeeOrientation)

        // Zone d'erreur franche. (Le bouton « Activer le micro »
        // a disparu en 0.6, consigne de Grégoire : l'écoute est
        // permanente tant que l'écran principal du tuner est
        // affiché — démarrage automatique à l'affichage, arrêt
        // dans les menus du parcours, les autres onglets et
        // l'arrière-plan. Voir onVisibilityChanged/surPremierPlan.)
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
            background = fondArrondi(COUL_PANEL, COUL_LINE, 8f)
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
        rangeeReactivite.addView(etiquette("Réactivité de la barre"))
        rangeeReactivite.addView(TextView(context).apply {
            text = "lente · stable"
            textSize = 12f
            setTextColor(COUL_DIM)
        })
        var vInit = 73
        val sauve = prefs.getInt("tuner_reactivite", -1)
        if (sauve in 0..100) vInit = sauve
        smoothAlpha = alphaDepuisCurseur(vInit)
        val seek = SeekBar(context).apply {
            max = 100
            progress = vInit
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(8f), 0, dp(8f), 0) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: SeekBar?, progres: Int, parUtilisateur: Boolean
                ) {
                    smoothAlpha = alphaDepuisCurseur(progres)
                    prefs.edit().putInt("tuner_reactivite", progres).apply()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
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

        construireParcours()
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
            if (mode != "bloque") setMode("bloque") else sendConfig()
        }
    }

    // Gestes sur le rond central : glisser ← → = demi-ton (−1/+1),
    // glisser ↑ ↓ = octave (+12/−12). Sélection manuelle → Bloqué.
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
    // Capture micro.
    // ----------------------------------------------------------------

    private fun startMic() {
        txtErr.text = ""
        if (!capture.permissionAccordee()) {
            demanderPermissionMicro()
            txtErr.text = "Permission micro non accordée — je la " +
                "demande à Android ; accorde-la : l'écoute " +
                "démarrera toute seule, sans autre geste."
            return
        }
        // Moteur neuf à chaque démarrage (comme le processeur worklet,
        // recréé avec son contexte audio dans la version web).
        moteur = TunerEngine(TunerCapture.TAUX)
        seqAdoptionsVue = 0
        sendConfig()
        val erreur = capture.demarrer()
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
        capture.arreter()
        micActif = false
        afficherCentsIncertain()
        barre.fracAiguille = 0.5
        barre.aiguilleOk = false
        barre.aiguilleOpacite = 0.25f
        barre.invalidate()
        arreterBoucle()
    }

    private fun surErreurCapture(msg: String) {
        if (!micActif && !capture.enCours) return
        micActif = false
        txtErr.text = msg
        if (parcoursOuvert && ::txtAncreMessage.isInitialized) {
            txtAncreMessage.text = msg
        }
        afficherCentsIncertain()
        arreterBoucle()
    }

    /** Arrêt imposé de l'extérieur (activité détruite). */
    fun arreterCapture() {
        if (micActif || capture.enCours) stopMic()
    }

    /** L'activité signale l'accord de la permission micro. */
    fun surPermissionMicroAccordee() {
        if (txtErr.text.isNotEmpty()) txtErr.text = ""
        // Plus de bouton (0.6) : l'écoute repart toute seule si
        // l'écran affiché en a besoin — écran principal du tuner,
        // ou étape de mesure du parcours. Sur un menu du
        // parcours (formulaire, résumé), elle reste arrêtée.
        if (!isShown || micActif) return
        if (!parcoursOuvert) {
            startMic()
        } else if (::groupeAncres.isInitialized &&
            groupeAncres.visibility == View.VISIBLE
        ) {
            startMic()
        }
    }

    /**
     * L'app revient au premier plan (appelée par l'activité à
     * onResume) : l'écoute repart toute seule si l'écran affiché
     * en a besoin et que la permission est là. Sans la
     * permission, on n'insiste pas ici — la demande part à
     * l'ouverture de l'onglet et l'état reste affiché.
     */
    fun surPremierPlan() {
        if (!isShown || micActif || !capture.permissionAccordee()) return
        if (!parcoursOuvert) {
            startMic()
        } else if (::groupeAncres.isInitialized &&
            groupeAncres.visibility == View.VISIBLE
        ) {
            startMic()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            if (parcoursOuvert) {
                // Parcours quitté en route : le brouillon (ancres
                // déjà mesurées) est sauvegardé à chaque ancre —
                // il sera proposé à la reprise.
                fermerParcours()
            }
            if (micActif) {
                // Onglet quitté : la capture cesse d'alimenter la mesure.
                stopMic()
            }
        } else if (isShown) {
            majBannierePlantage()
            // Écoute permanente (0.6) : l'écran principal du
            // tuner affiché = micro actif, sans aucun geste. Si
            // la permission manque, l'état s'affiche franchement
            // dans la ligne de diagnostic, à la place de l'état
            // d'écoute — la demande système part à l'ouverture
            // de l'onglet, côté activité.
            if (!parcoursOuvert && !micActif) {
                TracePlantage.etape = "accordeur — écran principal"
                if (capture.permissionAccordee()) {
                    startMic()
                } else {
                    txtDiag.text = "🔇 Micro arrêté — la permission " +
                        "microphone n'est pas accordée à l'app " +
                        "Gestion. Accorde-la (Réglages Android → " +
                        "Applications → Gestion → Autorisations → " +
                        "Microphone) : l'écoute démarrera toute seule."
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Boucle d'affichage : aiguille amortie, lecture numérique brute.
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
            }
        }
        // Chien de garde : une capture « active » mais muette se dit.
        val tot = capture.echantillonsTotal
        val maintenant = SystemClock.elapsedRealtime()
        if (tot != dernierTotalEch) {
            dernierTotalEch = tot
            dernierMouvementEchMs = maintenant
        } else if (maintenant - dernierMouvementEchMs > 1500 &&
            txtErr.text.isEmpty()
        ) {
            txtErr.text = "Aucun échantillon reçu du micro depuis " +
                "plus de 1,5 s — la capture semble muette (état : " +
                (if (capture.enCours) "actif" else "inactif") +
                ", échantillons reçus : $tot)."
        }
        val s = moteur.dernierEtat ?: return
        val latest = s.latest
        val live = latest != null && s.frameConf >= 0.35
        val holding = !live && latest != null && s.frameConf >= 0.06 &&
            s.ageSec <= 2.5
        if (latest != null && (live || holding)) {
            val c = latest.cents
            dispCents += (c - dispCents) * smoothAlpha
            afficherCentsMesure(c, holding)
            barre.fracAiguille = centsToFrac(dispCents)
            barre.aiguilleOk = abs(c) <= 0.1
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
    // Parcours « Nouveau piano » : capture guidée des partiels des
    // ancres do-sol, calcul de l'accord sur mesure, enregistrement
    // dans « Mes accords ». Les accords de Simon ne sont jamais
    // touchés (lecture seule) ; tout ce qui s'écrit ici va dans
    // les fichiers locaux de MesAccords.kt.
    // ----------------------------------------------------------------

    private fun boutonParcours(texte: String, action: () -> Unit): Button =
        Button(context).apply {
            text = texte
            textSize = 15f
            isAllCaps = false
            setTextColor(COUL_TXT)
            background = fondArrondi(COUL_PANEL, COUL_LINE, 8f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setOnClickListener { action() }
        }

    private fun boutonParcoursPrincipal(texte: String, action: () -> Unit): Button =
        Button(context).apply {
            text = texte
            textSize = 16f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COUL_SUR_ACCENT)
            background = fondArrondi(COUL_ACCENT, COUL_ACCENT, 8f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6f), 0, dp(6f)) }
            setOnClickListener { action() }
        }

    private fun champParcours(hint: String): EditText = EditText(context).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(COUL_TXT)
        setHintTextColor(COUL_DIM)
        background = fondArrondi(COUL_PANEL, COUL_LINE, 8f)
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(2f), 0, dp(6f)) }
    }

    private fun construireParcours() {
        vueParcours.addView(TextView(context).apply {
            text = "🎹 Nouveau piano"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COUL_TXT)
            setPadding(0, dp(2f), 0, dp(4f))
        })
        vueParcours.addView(TextView(context).apply {
            text = "Mesure de l'inharmonicité de CE piano : l'app te " +
                "demande les do et les sol, un par un, écoute leurs " +
                "partiels, calcule le coefficient B de chaque ancre " +
                "et construit un accord sur mesure, enregistré dans " +
                "« Mes accords » — à côté de ceux de Simon, qui ne " +
                "changent jamais."
            textSize = 13f
            setTextColor(COUL_DIM)
            setLineSpacing(0f, 1.45f)
        })
        txtParcoursMessage = TextView(context).apply {
            textSize = 14f
            setTextColor(COUL_BAD)
            setPadding(0, dp(6f), 0, 0)
        }
        vueParcours.addView(txtParcoursMessage)

        // --- Étape 1 : le formulaire (la fiche du piano) ---
        groupeFormulaire = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, 0)
        }
        txtBrouillon = TextView(context).apply {
            textSize = 14f
            setTextColor(COUL_ACCENT)
            visibility = View.GONE
            setPadding(0, 0, 0, dp(4f))
        }
        groupeFormulaire.addView(txtBrouillon)
        rangeeBrouillon = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        rangeeBrouillon.addView(boutonParcours("Reprendre ce piano") {
            demarrerMesures(reprendre = true)
        })
        rangeeBrouillon.addView(boutonParcours("Recommencer à zéro") {
            effacerBrouillon(context)
            ancresMesurees.clear()
            champClient.text.clear()
            champPiano.text.clear()
            champSerie.text.clear()
            txtBrouillon.visibility = View.GONE
            rangeeBrouillon.visibility = View.GONE
            txtParcoursMessage.text = ""
        })
        groupeFormulaire.addView(rangeeBrouillon)
        groupeFormulaire.addView(etiquette("Nom du client"))
        champClient = champParcours("ex. Benoît Blanchette")
        groupeFormulaire.addView(champClient)
        groupeFormulaire.addView(etiquette("Nom du piano (marque / modèle)"))
        champPiano = champParcours("ex. Yamaha U1")
        groupeFormulaire.addView(champPiano)
        groupeFormulaire.addView(etiquette("Numéro de série"))
        champSerie = champParcours("ex. 6E34586")
        groupeFormulaire.addView(champSerie)
        txtPitchParcours = TextView(context).apply {
            textSize = 13f
            setTextColor(COUL_DIM)
            setPadding(0, dp(2f), 0, dp(2f))
        }
        groupeFormulaire.addView(txtPitchParcours)
        groupeFormulaire.addView(boutonParcoursPrincipal("Commencer les mesures") {
            demarrerMesures(reprendre = false)
        })
        groupeFormulaire.addView(boutonParcours("Annuler") { fermerParcours() })
        vueParcours.addView(groupeFormulaire)

        // --- Étape 2 : les ancres, une à une ---
        groupeAncres = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8f), 0, 0)
        }
        txtAncreProgres = TextView(context).apply {
            textSize = 14f
            setTextColor(COUL_DIM)
        }
        groupeAncres.addView(txtAncreProgres)
        txtAncreNom = TextView(context).apply {
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COUL_TXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, dp(2f))
        }
        groupeAncres.addView(txtAncreNom)
        groupeAncres.addView(TextView(context).apply {
            text = "Joue la note franchement, laisse-la sonner, " +
                "rejoue-la au besoin. Téléphone près des cordes. " +
                "L'ancre passe toute seule quand la mesure est solide."
            textSize = 13f
            setTextColor(COUL_DIM)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
        })
        txtPartiels = TextView(context).apply {
            textSize = 13.5f
            setTextColor(COUL_TXT)
            fontFeatureSettings = "\"tnum\""
            setLineSpacing(0f, 1.5f)
            setPadding(0, dp(10f), 0, 0)
        }
        groupeAncres.addView(txtPartiels)
        txtAncreMessage = TextView(context).apply {
            textSize = 14f
            setTextColor(COUL_ACCENT)
            setLineSpacing(0f, 1.4f)
            setPadding(0, dp(8f), 0, 0)
        }
        groupeAncres.addView(txtAncreMessage)
        btnAccepterFaible = boutonParcoursPrincipal("Accepter cette mesure quand même") {
            verdictFaible?.let { validerAncre(it) }
        }
        btnAccepterFaible.visibility = View.GONE
        groupeAncres.addView(btnAccepterFaible)
        val rangeeAncre = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        rangeeAncre.addView(boutonParcours("↺ Rejouer cette note") {
            if (ancreValidee == null) configurerAncre()
        })
        rangeeAncre.addView(boutonParcours("Passer cette note →") {
            if (ancreValidee == null) {
                ancresPassees.add(ANCRES_PARCOURS[ancreIndex])
                analyseurActif = null
                avancerAncre()
            }
        })
        groupeAncres.addView(rangeeAncre)
        groupeAncres.addView(boutonParcours("Interrompre — reprendre plus tard") {
            sauvegarderBrouillon(context, brouillonCourant())
            fermerParcours()
        })
        vueParcours.addView(groupeAncres)

        // --- Étape 3 : résumé et enregistrement ---
        groupeResume = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8f), 0, 0)
        }
        txtResume = TextView(context).apply {
            textSize = 14.5f
            setTextColor(COUL_TXT)
            setLineSpacing(0f, 1.55f)
            fontFeatureSettings = "\"tnum\""
        }
        groupeResume.addView(txtResume)
        btnEnregistrerAccord = boutonParcoursPrincipal("💾 Enregistrer l'accord") {
            enregistrerAccordFinal()
        }
        groupeResume.addView(btnEnregistrerAccord)
        groupeResume.addView(boutonParcours("← Revenir aux mesures") {
            ancresPassees.clear()
            val idx = prochaineAncreIndex()
            if (idx < 0) {
                terminerMesures()
            } else {
                groupeResume.visibility = View.GONE
                groupeAncres.visibility = View.VISIBLE
                ancreIndex = idx
                configurerAncre()
                // Retour aux mesures : la capture repart (0.6) —
                // elle s'était arrêtée au résumé.
                if (!micActif) {
                    startMic()
                    if (!micActif) {
                        txtAncreMessage.text = "Le micro n'est " +
                            "pas actif : accorde la permission " +
                            "micro, puis rejoue la note."
                    }
                }
            }
        })
        groupeResume.addView(boutonParcours("Abandonner sans enregistrer") {
            AlertDialog.Builder(context)
                .setTitle("Abandonner ce piano ?")
                .setMessage(
                    "Les ancres mesurées pour ce piano seront " +
                        "effacées. Les accords déjà enregistrés et " +
                        "ceux de Simon ne sont pas touchés."
                )
                .setPositiveButton("Abandonner") { _, _ ->
                    effacerBrouillon(context)
                    ancresMesurees.clear()
                    fermerParcours()
                }
                .setNegativeButton("Retour", null)
                .show()
        })
        vueParcours.addView(groupeResume)
    }

    private fun nomNote(cle: Int): String =
        donnees?.tunings?.firstOrNull()?.notes?.getOrNull(cle - 1)?.name
            ?: "Touche $cle"

    private fun brouillonCourant(): BrouillonParcours = BrouillonParcours(
        client = champClient.text.toString(),
        piano = champPiano.text.toString(),
        serie = champSerie.text.toString(),
        pitch = parcoursPitch,
        ancres = ancresMesurees.toList()
    )

    private fun ouvrirParcours() {
        parcoursOuvert = true
        TracePlantage.etape = "parcours Nouveau piano — formulaire"
        vueAccord.visibility = View.GONE
        vueParcours.visibility = View.VISIBLE
        groupeFormulaire.visibility = View.VISIBLE
        groupeAncres.visibility = View.GONE
        groupeResume.visibility = View.GONE
        txtParcoursMessage.text = ""
        // Le formulaire est un MENU (aucune mesure ne s'y fait) :
        // la capture s'y arrête (0.6). Elle repartira toute
        // seule au lancement des mesures (demarrerMesures) et au
        // retour à l'écran principal du tuner.
        if (micActif) stopMic()
        txtPitchParcours.text = "Diapason : $pitch Hz (celui réglé à " +
            "l'écran d'accord — change-le là avant de commencer si " +
            "besoin)."
        val brouillon = chargerBrouillon(context)
        if (brouillon != null) {
            // Correctif 0.6 : la reprise est proposée dès qu'un
            // brouillon EXISTE — la fiche seule suffit, même sans
            // aucune ancre validée. (En 0.5, la condition
            // ancres.isNotEmpty() laissait invisible un parcours
            // planté pendant la première ancre : le brouillon
            // était écrit dès le départ des mesures, mais rien
            // ne le montrait ni ne recopiait la fiche.) La fiche
            // est donc toujours recopiée dans les champs.
            champClient.setText(brouillon.client)
            champPiano.setText(brouillon.piano)
            champSerie.setText(brouillon.serie)
            val nomPiano = listOf(brouillon.piano, brouillon.client)
                .firstOrNull { it.isNotBlank() }.orEmpty()
            txtBrouillon.text = if (brouillon.ancres.isNotEmpty()) {
                "Un piano est en cours (" +
                    "${brouillon.ancres.size} ancre(s) mesurée(s) " +
                    "sur ${ANCRES_PARCOURS.size}) : $nomPiano"
            } else {
                "Un parcours est commencé (fiche remplie, " +
                    "aucune ancre encore mesurée) : $nomPiano"
            }
            txtBrouillon.visibility = View.VISIBLE
            rangeeBrouillon.visibility = View.VISIBLE
        } else {
            txtBrouillon.visibility = View.GONE
            rangeeBrouillon.visibility = View.GONE
        }
        demarrerBoucleParcours()
    }

    private fun fermerParcours() {
        parcoursOuvert = false
        analyseurActif = null
        boucleParcoursActive = false
        gestionnaire.removeCallbacks(boucleParcours)
        vueParcours.visibility = View.GONE
        vueAccord.visibility = View.VISIBLE
        // Retour à l'écran principal : l'écoute permanente y
        // reprend toute seule (0.6). Le post laisse la bascule
        // de visibilité se terminer : si c'est la disparition de
        // l'onglet qui a fermé le parcours, isShown sera faux et
        // rien ne repartira.
        gestionnaire.post {
            if (isShown && !parcoursOuvert && !micActif &&
                capture.permissionAccordee()
            ) {
                startMic()
            }
        }
    }

    private fun demarrerMesures(reprendre: Boolean) {
        if (!micActif) {
            startMic()
            if (!micActif) {
                txtParcoursMessage.text = "Le micro n'est pas " +
                    "actif : impossible de mesurer. Accorde la " +
                    "permission micro, puis réessaie."
                return
            }
        }
        txtParcoursMessage.text = ""
        if (reprendre) {
            val brouillon = chargerBrouillon(context)
            if (brouillon != null) {
                ancresMesurees = brouillon.ancres.toMutableList()
                parcoursPitch = brouillon.pitch
            }
        } else {
            ancresMesurees.clear()
            parcoursPitch = pitch
        }
        ancresPassees.clear()
        ancreValidee = null
        verdictFaible = null
        sauvegarderBrouillon(context, brouillonCourant())
        val idx = prochaineAncreIndex()
        groupeFormulaire.visibility = View.GONE
        if (idx < 0) {
            terminerMesures()
            return
        }
        groupeAncres.visibility = View.VISIBLE
        ancreIndex = idx
        configurerAncre()
    }

    /** Prochaine ancre ni mesurée ni passée (−1 s'il n'en reste pas). */
    private fun prochaineAncreIndex(): Int {
        val faites = ancresMesurees.map { it.key }.toSet()
        return ANCRES_PARCOURS.indices.firstOrNull { idx ->
            val cle = ANCRES_PARCOURS[idx]
            cle !in faites && cle !in ancresPassees
        } ?: -1
    }

    private fun configurerAncre() {
        val cle = ANCRES_PARCOURS[ancreIndex]
        TracePlantage.etape = "mesure d'inharmonicité — ancre " +
            "${nomNote(cle)} (touche $cle)"
        val f1Nominale = parcoursPitch * Math.pow(2.0, (cle - 49) / 12.0)
        analyseurActif = AnalyseurPartiels(TunerCapture.TAUX, cle, f1Nominale)
        parcoursDebutMs = SystemClock.elapsedRealtime()
        ancreValidee = null
        verdictFaible = null
        btnAccepterFaible.visibility = View.GONE
        txtAncreNom.text = nomNote(cle)
        txtAncreProgres.text = "Ancre " +
            "${ancresMesurees.size + ancresPassees.size + 1} sur " +
            "${ANCRES_PARCOURS.size} — ${nomNote(cle)} (touche $cle)"
        txtAncreMessage.text = ""
        txtPartiels.text = "En écoute… joue la note."
    }

    private val boucleParcours = object : Runnable {
        override fun run() {
            if (!boucleParcoursActive) return
            majAffichageAncre()
            gestionnaire.postDelayed(this, 150)
        }
    }

    private fun demarrerBoucleParcours() {
        if (!boucleParcoursActive) {
            boucleParcoursActive = true
            gestionnaire.post(boucleParcours)
        }
    }

    private fun majAffichageAncre() {
        if (!parcoursOuvert || groupeAncres.visibility != View.VISIBLE) return
        val analyseur = analyseurActif
        if (ancreValidee != null) {
            if (SystemClock.elapsedRealtime() - ancreValideeMs >= 1200) {
                avancerAncre()
            }
            return
        }
        if (analyseur == null) return
        // Filet (0.6) : ce sondage roule toutes les 150 ms sur le
        // fil d'interface pendant que l'analyseur vit sa vie —
        // une exception ici ne doit jamais tuer l'app en pleine
        // mesure. Le brouillon (ancres déjà validées) est intact
        // et le parcours reste ouvert : l'ancre peut être rejouée.
        try {
            val etats = analyseur.etatPartiels()
            txtPartiels.text = etats.joinToString("\n") { e ->
                if (e.freqHz == null) {
                    "Partiel ${e.n} · en écoute… (${e.nbEstimations} est.)"
                } else if (e.fixation != FixationPartiel.AUCUNE) {
                    // Partiel fixé pour cette ancre (0.7) : le
                    // crochet reste, avec sa valeur figée et la
                    // manière dont elle a été obtenue — propre
                    // (critères stricts) ou moyenné (décision
                    // après oscillation bornée).
                    "Partiel ${e.n} · ${fmt(e.freqHz, 1)} Hz · " +
                        (if (e.fixation == FixationPartiel.PROPRE)
                            "✓ fixé (propre)" else "✓ fixé (moyenné)") +
                        " (conf ${fmt(e.confianceMoyenne, 2)})"
                } else {
                    "Partiel ${e.n} · ${fmt(e.freqHz, 1)} Hz · " +
                        (if (e.propre) "✓ propre" else "en stabilisation") +
                        " (conf ${fmt(e.confianceMoyenne, 2)}, " +
                        "±${fmt(e.dispersionCents, 1)} ¢)"
                }
            }
            when (val verdict = analyseur.evaluer(nomNote(analyseur.cleAncre))) {
                is AnalyseurPartiels.Verdict.Pret -> validerAncre(verdict.mesure)
                is AnalyseurPartiels.Verdict.Faible -> {
                    verdictFaible = verdict.mesure
                    btnAccepterFaible.visibility = View.VISIBLE
                    txtAncreMessage.text = "Mesure faible : " +
                        verdict.raisons.joinToString(" ; ") +
                        ". Rejoue la note pour l'améliorer, ou accepte-la."
                }
                is AnalyseurPartiels.Verdict.Insuffisant -> {
                    verdictFaible = null
                    btnAccepterFaible.visibility = View.GONE
                    val ecoule = SystemClock.elapsedRealtime() - parcoursDebutMs
                    if (ecoule > 12000) {
                        txtAncreMessage.text = "Mesure insuffisante : " +
                            verdict.raisons.joinToString(" ; ") +
                            ". Rejoue la note franchement, laisse-la " +
                            "sonner, téléphone près des cordes — ou " +
                            "passe cette note."
                    }
                }
            }
        } catch (e: Exception) {
            analyseurActif = null
            verdictFaible = null
            btnAccepterFaible.visibility = View.GONE
            txtAncreMessage.text = "Erreur pendant la mesure : " +
                "${e.javaClass.simpleName} — ${e.message}. Tes " +
                "ancres déjà mesurées sont sauvegardées ; pèse " +
                "« ↺ Rejouer cette note » pour reprendre cette ancre."
        }
    }

    private fun validerAncre(mesure: MesureAncre) {
        ancresMesurees.add(mesure)
        sauvegarderBrouillon(context, brouillonCourant())
        analyseurActif = null
        ancreValidee = mesure
        ancreValideeMs = SystemClock.elapsedRealtime()
        btnAccepterFaible.visibility = View.GONE
        txtAncreMessage.text = "✓ ${mesure.nom} mesurée — " +
            "${mesure.partiels.size} partiels · B = ${fmt(mesure.b, 5)} · " +
            "note à ${fmtSigne(mesure.ecartCents, 0)} ¢ de sa hauteur " +
            "nominale. Ancre suivante…"
    }

    private fun avancerAncre() {
        ancreValidee = null
        val idx = prochaineAncreIndex()
        if (idx < 0) {
            terminerMesures()
        } else {
            ancreIndex = idx
            configurerAncre()
        }
    }

    private fun terminerMesures() {
        analyseurActif = null
        TracePlantage.etape = "parcours Nouveau piano — résumé"
        // Le résumé est un MENU (aucune mesure) : la capture s'y
        // arrête (0.6) — elle repart au retour aux mesures ou à
        // l'écran principal du tuner.
        if (micActif) stopMic()
        groupeAncres.visibility = View.GONE
        groupeFormulaire.visibility = View.GONE
        groupeResume.visibility = View.VISIBLE
        if (ancresMesurees.size < 8) {
            tableGeneree = null
            courbeGeneree = null
            btnEnregistrerAccord.visibility = View.GONE
            txtResume.text = "Seulement ${ancresMesurees.size} ancre(s) " +
                "mesurée(s) — il en faut au moins 8, réparties sur le " +
                "clavier, pour construire un accord fiable. Reviens " +
                "aux mesures pour continuer ce piano, ou abandonne-le."
            return
        }
        // Filet (0.6) : la génération de l'accord ne doit jamais
        // planter l'app — données absentes ou calcul fautif, on
        // l'affiche franchement et le brouillon conserve les
        // ancres. (En 0.5, donnees!! pouvait lever un NPE ici.)
        TracePlantage.etape = "parcours Nouveau piano — génération de l'accord"
        val noms = donnees?.tunings?.firstOrNull()?.notes
            ?.map { it.name } ?: emptyList()
        val table = try {
            val courbe = courbeInharmonicite(ancresMesurees)
            courbeGeneree = courbe
            genererTableAccord(courbe, parcoursPitch, noms)
        } catch (e: Exception) {
            courbeGeneree = null
            tableGeneree = null
            btnEnregistrerAccord.visibility = View.GONE
            txtResume.text = "La génération de l'accord a " +
                "échoué : ${e.javaClass.simpleName} — ${e.message}. " +
                "Tes ${ancresMesurees.size} ancres mesurées sont " +
                "conservées dans le brouillon — reviens aux " +
                "mesures, puis reviens ici pour réessayer."
            return
        }
        tableGeneree = table
        btnEnregistrerAccord.visibility = View.VISIBLE
        val bMin = ancresMesurees.minOf { it.b }
        val bMax = ancresMesurees.maxOf { it.b }
        fun ecartTable(cle: Int): Double {
            val f1 = table[cle - 1].fundamentalHz440
            val f1Et = 440.0 * Math.pow(2.0, (cle - 49) / 12.0)
            return 1200 * kotlin.math.log2(f1 / f1Et)
        }
        val client = champClient.text.toString()
        val piano = champPiano.text.toString()
        val serie = champSerie.text.toString()
        txtResume.text = "Piano mesuré : ${ancresMesurees.size} ancres " +
            "sur ${ANCRES_PARCOURS.size}.\n" +
            "Inharmonicité B : de ${fmt(bMin, 5)} (ancres les plus " +
            "douces) à ${fmt(bMax, 5)}.\n" +
            "Étirement calculé : ${fmtSigne(ecartTable(1), 0)} ¢ à la " +
            "première touche, ${fmtSigne(ecartTable(88), 0)} ¢ à la " +
            "dernière, la à $parcoursPitch Hz.\n\n" +
            "Client : ${client.ifBlank { "—" }}\n" +
            "Piano : ${piano.ifBlank { "—" }}\n" +
            "Série : ${serie.ifBlank { "—" }}\n\n" +
            "Enregistre-le : il apparaîtra dans « Mes accords », " +
            "sélectionnable comme un accord de Simon — qui, eux, " +
            "ne changent jamais."
    }

    private fun enregistrerAccordFinal() {
        val table = tableGeneree ?: return
        val courbe = courbeGeneree ?: return
        val client = champClient.text.toString().trim()
        val piano = champPiano.text.toString().trim()
        val serie = champSerie.text.toString().trim()
        val nom = piano.ifBlank { client.ifBlank { "Piano sans nom" } }
        val dateIso = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
            .format(java.util.Date())
        val accord = Accord(id = 0, name = nom, serial = serie, notes = table)
        TracePlantage.etape = "parcours Nouveau piano — enregistrement de l'accord"
        val idFichier = sauvegarderAccord(
            context, client, piano, serie, dateIso, parcoursPitch,
            ancresMesurees.toList(), courbe, accord
        )
        if (idFichier == null) {
            // Filet (0.6) : l'écriture a échoué — on le dit
            // franchement et on NE touche à rien : le brouillon
            // et les ancres restent là, l'enregistrement peut
            // être réessayé.
            txtParcoursMessage.text = "⚠ L'enregistrement de " +
                "l'accord a échoué (écriture impossible sur " +
                "l'appareil). Rien n'est perdu : le brouillon " +
                "est conservé, réessaie le bouton « Enregistrer " +
                "l'accord »."
            return
        }
        effacerBrouillon(context)
        ancresMesurees.clear()
        fermerParcours()
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
            "« Mes accords » ($dateIso, la à $parcoursPitch Hz) et " +
            "sélectionné — prêt à accorder ce piano."
    }

    private fun fmt(v: Double, decimales: Int): String =
        String.format(Locale.ROOT, "%.${decimales}f", v)

    private fun fmtSigne(v: Double, decimales: Int): String =
        String.format(Locale.ROOT, "%+.${decimales}f", v)
}
