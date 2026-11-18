package ca.gregoirepainchaud.pixelbridge.tuner

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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
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

    private var moteur: TunerEngine = TunerEngine(TunerCapture.TAUX)
    private val capture = TunerCapture(
        context,
        surEchantillons = { ech -> moteur.push(ech) },
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
    private lateinit var spinAccord: Spinner
    private lateinit var spinNote: Spinner
    private lateinit var segPitch: Segment
    private lateinit var segMode: Segment
    private lateinit var interrupteurPaysage: Switch
    private lateinit var texteOrientation: TextView
    private lateinit var btnMic: Button
    private lateinit var txtErr: TextView
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

        racine.addView(TextView(context).apply {
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
        racine.addView(rangeeAccord)

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
        racine.addView(rangeePitch)

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
        racine.addView(rangeeMode)

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
        racine.addView(rangeeOrientation)

        // Bouton micro + zone d'erreur franche.
        val rangeeMic = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        btnMic = Button(context).apply {
            text = "🎙 Activer le micro"
            textSize = 17f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COUL_SUR_ACCENT)
            background = fondArrondi(COUL_ACCENT, COUL_ACCENT, 10f)
            setPadding(dp(20f), dp(13f), dp(20f), dp(13f))
            setOnClickListener { if (micActif) stopMic() else startMic() }
        }
        rangeeMic.addView(btnMic)
        txtErr = TextView(context).apply {
            textSize = 14f
            setTextColor(COUL_BAD)
            setPadding(dp(10f), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rangeeMic.addView(txtErr)
        racine.addView(rangeeMic)

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
        racine.addView(rangeeNote)

        txtBadge = TextView(context).apply {
            textSize = 12.5f
            setTextColor(COUL_ACCENT)
            gravity = Gravity.CENTER
            minHeight = dp(20f)
        }
        racine.addView(txtBadge)
        txtMeta = TextView(context).apply {
            text = "—"
            textSize = 14f
            setTextColor(COUL_DIM)
            gravity = Gravity.CENTER
            minHeight = dp(20f)
        }
        racine.addView(txtMeta)

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
        racine.addView(rangeeSelNote)

        // Lecture numérique en cents (mesure brute).
        txtCents = TextView(context).apply {
            gravity = Gravity.CENTER
            minHeight = dp(84f)
            fontFeatureSettings = "\"tnum\""
            setPadding(0, dp(4f), 0, 0)
        }
        afficherCentsIncertain()
        racine.addView(txtCents)

        // Barre de mesure.
        barre = BarreAccordView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92f)
            ).apply { setMargins(0, dp(10f), 0, dp(4f)) }
        }
        racine.addView(barre)

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
        racine.addView(rangeeReactivite)

        // Ligne de diagnostic.
        racine.addView(View(context).apply {
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
        racine.addView(txtDiag)

        chargerDonnees()
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
            tuningId = d.tunings[0].id
            pitch = d.referencePitch
            spinAccord.adapter = adaptateurSpinner(
                d.tunings.map { "${it.name} (${it.serial})" }
            )
            spinAccord.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, v: View?, pos: Int, id: Long
                    ) {
                        val choisi = d.tunings[pos].id
                        if (choisi == tuningId) return
                        tuningId = choisi
                        remplirSpinnerNotes()
                        sendConfig()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            val idxPitch = d.pitchChoicesHz.indexOf(pitch)
            if (idxPitch > 0) segPitch.selectionner(idxPitch, false)
            remplirSpinnerNotes()
            refreshMeta()
        } catch (e: Exception) {
            txtErr.text = "Impossible de charger tunings.json : ${e.message}"
        }
    }

    private fun remplirSpinnerNotes() {
        val d = donnees ?: return
        val accord = d.tunings.first { it.id == tuningId }
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
        donnees!!.tunings.first { it.id == tuningId }

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
                "demande à Android ; accorde-la, puis pèse de nouveau " +
                "« Activer le micro »."
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
        btnMic.text = "⏹ Arrêter le micro"
        btnMic.setTextColor(Color.WHITE)
        btnMic.background = fondArrondi(COUL_BAD, COUL_BAD, 10f)
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
        btnMic.text = "🎙 Activer le micro"
        btnMic.setTextColor(COUL_SUR_ACCENT)
        btnMic.background = fondArrondi(COUL_ACCENT, COUL_ACCENT, 10f)
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
        btnMic.text = "🎙 Activer le micro"
        btnMic.setTextColor(COUL_SUR_ACCENT)
        btnMic.background = fondArrondi(COUL_ACCENT, COUL_ACCENT, 10f)
        txtErr.text = msg
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
        txtDiag.text = "Permission micro accordée — pèse « 🎙 Activer " +
            "le micro » pour commencer la mesure."
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && micActif) {
            // Onglet quitté : la capture cesse d'alimenter la mesure.
            stopMic()
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

    private fun fmt(v: Double, decimales: Int): String =
        String.format(Locale.ROOT, "%.${decimales}f", v)

    private fun fmtSigne(v: Double, decimales: Int): String =
        String.format(Locale.ROOT, "%+.${decimales}f", v)
}
