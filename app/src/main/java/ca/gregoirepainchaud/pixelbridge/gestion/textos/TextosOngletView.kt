package ca.gregoirepainchaud.pixelbridge.gestion.textos

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// ---------------------------------------------------------------------------
// L'écran de l'onglet Textos (chantier structure 0.8) : le
// statut et le bouton de vérification. La mécanique (module
// gestion/Textos) et la permission READ_SMS restent du côté
// de l'activité : le bouton demande la vérification par le
// rappel, et l'activité pousse le statut par afficherStatut.
// ---------------------------------------------------------------------------

class TextosOngletView(
    context: Context,
    surVerifier: () -> Unit
) : LinearLayout(context) {

    private val statut: TextView

    init {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 32, 32, 32)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        visibility = View.GONE
        addView(TextView(context).apply {
            text = "Textos reçus → poussés à Muse par le pont.\n" +
                "Seuls les textos reçus après l'installation partent " +
                "(filigrane local) ; chacun est mis en file sur " +
                "l'appareil avant l'envoi, rien ne se perd hors ligne."
            textSize = 13f
        })
        statut = TextView(context).apply {
            textSize = 13f
            setPadding(0, 24, 0, 24)
        }
        addView(statut)
        val bouton = Button(context).apply {
            text = "Vérifier les textos"
            setOnClickListener { surVerifier() }
        }
        addView(bouton)
    }

    /** Le statut courant de l'onglet (résultat ou rappel). */
    fun afficherStatut(message: String) {
        statut.text = message
    }

    /** Le rappel affiché tant que la permission n'est pas accordée. */
    fun afficherPermissionManquante() {
        statut.text = "Textos : la permission de lire les " +
            "textos n'est pas encore accordée — pèse « Vérifier " +
            "les textos » pour la demander."
    }
}
