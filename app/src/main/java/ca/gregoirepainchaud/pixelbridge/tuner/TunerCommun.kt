package ca.gregoirepainchaud.pixelbridge.tuner

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------
// Éléments partagés de l'onglet Tuner (chantier structure 0.8) :
// palette, échelle de la barre, formats d'affichage et petites
// fabriques d'interface communes à l'écran principal (TunerView),
// à la barre (BarreAccordView) et au parcours « Nouveau piano »
// (paquet tuner/parcours/). Déplacés tels quels de TunerView.kt,
// où ils vivaient en privé au niveau du fichier.
// ---------------------------------------------------------------------------

internal val COUL_BG = Color.rgb(0x10, 0x14, 0x18)
internal val COUL_PANEL = Color.rgb(0x17, 0x1d, 0x23)
internal val COUL_LINE = Color.rgb(0x2a, 0x33, 0x3d)
internal val COUL_TXT = Color.rgb(0xe8, 0xed, 0xf1)
internal val COUL_DIM = Color.rgb(0x8f, 0xa0, 0xae)
internal val COUL_ACCENT = Color.rgb(0xd9, 0xa4, 0x41)
internal val COUL_OK = Color.rgb(0x2e, 0xcc, 0x71)
internal val COUL_BAD = Color.rgb(0xe7, 0x4c, 0x3c)
internal val COUL_SUR_ACCENT = Color.rgb(0x14, 0x10, 0x0a)
internal val COUL_CENTRE = Color.rgb(0x3d, 0x4a, 0x56)
internal val COUL_TICK = Color.rgb(0x46, 0x54, 0x5f)

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

internal fun fondArrondi(
    ctx: Context, fill: Int, stroke: Int, rayonDp: Float
): GradientDrawable {
    val densite = ctx.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * densite).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(rayonDp).toFloat()
        setColor(fill)
        setStroke(dp(1f), stroke)
    }
}

internal fun etiquette(ctx: Context, texte: String): TextView =
    TextView(ctx).apply {
        text = texte
        textSize = 13f
        setTextColor(COUL_DIM)
        setPadding(0, 0, (6f * ctx.resources.displayMetrics.density).toInt(), 0)
    }

internal fun fmt(v: Double, decimales: Int): String =
    String.format(Locale.ROOT, "%.${decimales}f", v)

internal fun fmtSigne(v: Double, decimales: Int): String =
    String.format(Locale.ROOT, "%+.${decimales}f", v)
