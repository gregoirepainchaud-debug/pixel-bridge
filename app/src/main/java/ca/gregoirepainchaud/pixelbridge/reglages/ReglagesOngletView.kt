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
    surMettreAJour: () -> Unit,
    private val surDemanderPermissionsGps: () -> Unit = {},
    private val surBasculerGps: (Boolean) -> Unit = {}
) : ScrollView(context) {

    private val boutonMaj: Button
    private val statutMaj: TextView
    private val statutGps: TextView

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

        // ----------------------------------------------------
        // Suivi de trajet natif (0.16) : interrupteur, état
        // complet et permissions — rien d'invisible. L'état
        // vécu (service, dernier point, file) vient des
        // préférences publiées par GpsService ; rafraichirGps()
        // le relit à chaque affichage de l'onglet.
        // ----------------------------------------------------
        ajout(TextView(context).apply {
            text = "Suivi de trajet (GPS natif)"
            textSize = 16f
            setPadding(0, 24, 0, 4)
        })
        ajout(TextView(context).apply {
            text = "Le trajet se capte écran fermé, par le " +
                "service natif : en repos, il ne consomme rien " +
                "(aucun relevé) ; le GPS ne part que sur une " +
                "conduite qualifiée (20 km/h soutenu) réveillée " +
                "par le mouvement ou le Bluetooth de l'auto. " +
                "Quand le natif émet, le suivi de la page web " +
                "se retire tout seul (aucun doublon)."
            textSize = 13f
        })
        val interrupteurGps = android.widget.Switch(context).apply {
            text = "Suivi de trajet natif actif"
            isChecked = prefs.getBoolean(
                PreferencesApp.CLE_GPS_ACTIF, true
            )
            setOnCheckedChangeListener { _, coche ->
                prefs.edit()
                    .putBoolean(PreferencesApp.CLE_GPS_ACTIF, coche)
                    .apply()
                surBasculerGps(coche)
                rafraichirGps()
            }
        }
        ajout(interrupteurGps)
        statutGps = TextView(context).apply {
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }
        ajout(statutGps)
        ajout(Button(context).apply {
            text = "Accorder / vérifier les permissions GPS"
            setOnClickListener { surDemanderPermissionsGps() }
        })
        ajout(TextView(context).apply {
            text = "Pour capter écran fermé, la position doit " +
                "être à « Toujours autoriser » : le bouton " +
                "ci-dessus ouvre les paramètres de l'app — " +
                "Autorisations → Position → Toujours autoriser."
            textSize = 13f
        })
        rafraichirGps()

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

    /**
     * Recompose l'état du suivi de trajet : permissions (lues
     * sur le champ), état publié par le service, dernier point,
     * dernier envoi accepté, points en file, Bluetooth de
     * l'auto. Appelé à l'affichage de l'onglet et après chaque
     * retour de permission — jamais de chiffre périmé affiché
     * comme s'il était frais.
     */
    fun rafraichirGps() {
        val ctx = context
        fun permise(permission: String): Boolean =
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val fine = permise(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val arrierePlan = permise(
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        val bluetooth = permise(
            android.Manifest.permission.BLUETOOTH_CONNECT
        )
        val actif = prefs.getBoolean(
            PreferencesApp.CLE_GPS_ACTIF, true
        )
        val etatNom = prefs.getString(
            PreferencesApp.CLE_GPS_ETAT, ""
        ) ?: ""
        val dernierPoint = prefs.getLong(
            PreferencesApp.CLE_GPS_DERNIER_POINT_MS, 0L
        )
        val dernierEnvoi = prefs.getLong(
            PreferencesApp.CLE_GPS_DERNIER_ENVOI_MS, 0L
        )
        val enFile = prefs.getInt(
            PreferencesApp.CLE_GPS_POINTS_EN_FILE, 0
        )
        val btConnecte = prefs.getBoolean(
            PreferencesApp.CLE_GPS_BT_CONNECTE, false
        )
        val btNom = prefs.getString(
            PreferencesApp.CLE_GPS_BT_NOM, ""
        ) ?: ""
        val autoNom = prefs.getString(
            PreferencesApp.CLE_GPS_AUTO_NOM, ""
        ) ?: ""

        fun ilYa(ms: Long): String {
            if (ms <= 0L) return "jamais"
            val minutes =
                (System.currentTimeMillis() - ms) / 60_000L
            return when {
                minutes < 1 -> "à l'instant"
                minutes < 60 -> "il y a $minutes min"
                minutes < 60 * 24 -> "il y a ${minutes / 60} h"
                else -> "il y a ${minutes / (60 * 24)} j"
            }
        }

        val lignes = ArrayList<String>()
        lignes += "Interrupteur : " + (if (actif) "actif" else "coupé")
        lignes += "Position précise : " +
            (if (fine) "accordée" else "MANQUANTE")
        lignes += "Position en arrière-plan : " +
            (if (arrierePlan) {
                "accordée (toujours)"
            } else {
                "MANQUANTE — sans elle, rien ne se capte " +
                    "écran fermé"
            })
        lignes += "Bluetooth : " +
            (if (bluetooth) "accordé" else "manquant") +
            (if (btConnecte) {
                " — appareil connecté" +
                    (if (btNom.isNotEmpty()) " ($btNom)" else "")
            } else {
                " — aucun appareil connecté"
            })
        if (autoNom.isNotEmpty()) {
            lignes += "Auto apprise : $autoNom"
        }
        lignes += "Service : " +
            ca.gregoirepainchaud.pixelbridge.gps.GpsEtat
                .libelle(etatNom)
        lignes += "Dernier point : ${ilYa(dernierPoint)}"
        lignes += "Dernier envoi accepté : ${ilYa(dernierEnvoi)}"
        lignes += "Points en file : $enFile"
        statutGps.text = lignes.joinToString("\n")
    }

    /** Le bouton de mise à jour, actif ou non pendant le téléchargement. */
    fun setBoutonMajActif(actif: Boolean) {
        boutonMaj.isEnabled = actif
    }
}
