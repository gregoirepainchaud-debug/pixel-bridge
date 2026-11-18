package ca.gregoirepainchaud.pixelbridge.reglages

import android.content.Context
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp

// ---------------------------------------------------------------------------
// L'écran de l'onglet Réglages (chantier structure 0.8) :
// pont, modèles, fils, mise à jour. Chaque champ s'enregistre
// dans les préférences à mesure qu'on tape — l'écran Oreille
// lit les mêmes clés (PreferencesApp). Le bouton de mise à
// jour délègue à MiseAJourApp, qui rend compte ici par
// afficherStatutMaj et setBoutonMajActif.
// ---------------------------------------------------------------------------

class ReglagesOngletView(
    context: Context,
    private val prefs: SharedPreferences,
    surMettreAJour: () -> Unit
) : ScrollView(context) {

    private val boutonMaj: Button
    private val statutMaj: TextView

    init {
        val colonne = LinearLayout(context).apply {
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
        ajout(TextView(context).apply {
            text = "Réglages — enregistrés à mesure, partagés avec " +
                "l'écran Oreille. Les fichiers de modèles se lisent " +
                "dans Téléchargements ou le dossier de l'app : aucun " +
                "déplacement nécessaire."
            textSize = 13f
        })
        ajout(champReglage(
            prefs.getString(PreferencesApp.CLE_PONT_URL, "") ?: "",
            "Adresse du pont (URL complète, à coller une fois)",
            PreferencesApp.CLE_PONT_URL
        ))
        ajout(champReglage(
            prefs.getString(PreferencesApp.CLE_MODELE_FLUX, PreferencesApp.DEFAUT_MODELE_FLUX) ?: PreferencesApp.DEFAUT_MODELE_FLUX,
            "Modèle de flux (.bin) — transcription en direct",
            PreferencesApp.CLE_MODELE_FLUX
        ))
        ajout(champReglage(
            prefs.getString(PreferencesApp.CLE_MODELE_CORRECTION, PreferencesApp.DEFAUT_MODELE_CORRECTION)
                ?: PreferencesApp.DEFAUT_MODELE_CORRECTION,
            "Modèle de correction (.bin) — première passe sur les épisodes",
            PreferencesApp.CLE_MODELE_CORRECTION
        ))
        ajout(champReglage(
            prefs.getString(PreferencesApp.CLE_MODELE_RELECTURE, PreferencesApp.DEFAUT_MODELE_RELECTURE)
                ?: PreferencesApp.DEFAUT_MODELE_RELECTURE,
            "Modèle de relecture (.bin) — back door, passages doutés",
            PreferencesApp.CLE_MODELE_RELECTURE
        ))
        ajout(champReglage(
            prefs.getString(PreferencesApp.CLE_FILS, PreferencesApp.DEFAUT_FILS) ?: PreferencesApp.DEFAUT_FILS,
            "Nombre de fils pour Whisper",
            PreferencesApp.CLE_FILS, numerique = true
        ))
        boutonMaj = Button(context).apply {
            text = "Mettre à jour l'app (dernier build)"
            setOnClickListener { surMettreAJour() }
        }
        ajout(boutonMaj)
        statutMaj = TextView(context).apply {
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        ajout(statutMaj)

        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        visibility = View.GONE
        addView(colonne)
    }

    private fun champReglage(
        valeur: String, hint: String, cle: String, numerique: Boolean = false
    ): EditText {
        val e = EditText(context).apply {
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

    /** Le statut de la mise à jour (téléchargement, échec…). */
    fun afficherStatutMaj(message: String) {
        statutMaj.text = message
    }

    /** Le bouton de mise à jour, actif ou non pendant le téléchargement. */
    fun setBoutonMajActif(actif: Boolean) {
        boutonMaj.isEnabled = actif
    }
}
