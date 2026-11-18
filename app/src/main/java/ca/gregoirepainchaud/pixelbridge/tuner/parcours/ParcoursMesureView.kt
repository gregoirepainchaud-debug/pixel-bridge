package ca.gregoirepainchaud.pixelbridge.tuner.parcours

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ca.gregoirepainchaud.pixelbridge.tuner.ANCRES_PARCOURS
import ca.gregoirepainchaud.pixelbridge.tuner.BrouillonParcours
import ca.gregoirepainchaud.pixelbridge.tuner.COUL_ACCENT
import ca.gregoirepainchaud.pixelbridge.tuner.COUL_BAD
import ca.gregoirepainchaud.pixelbridge.tuner.COUL_DIM
import ca.gregoirepainchaud.pixelbridge.tuner.COUL_LINE
import ca.gregoirepainchaud.pixelbridge.tuner.COUL_PANEL
import ca.gregoirepainchaud.pixelbridge.tuner.COUL_SUR_ACCENT
import ca.gregoirepainchaud.pixelbridge.tuner.COUL_TXT
import ca.gregoirepainchaud.pixelbridge.tuner.EtatPartiel
import ca.gregoirepainchaud.pixelbridge.tuner.FixationPartiel
import ca.gregoirepainchaud.pixelbridge.tuner.etiquette
import ca.gregoirepainchaud.pixelbridge.tuner.fmt
import ca.gregoirepainchaud.pixelbridge.tuner.fondArrondi

// ---------------------------------------------------------------------------
// Écran du parcours « Nouveau piano » (chantier structure 0.8) :
// les trois étapes — formulaire de la fiche, ancres une à une,
// résumé — construites et affichées ici ; toute la mécanique
// (état, progression, brouillons, calcul, enregistrement) vit
// dans ParcoursMesureControleur, à qui les boutons délèguent.
// Avant le chantier, écran et mécanique vivaient ensemble dans
// TunerView.kt ; les textes et la disposition sont inchangés.
// ---------------------------------------------------------------------------

class ParcoursMesureView(
    context: Context,
    private val actions: ParcoursMesureControleur
) : LinearLayout(context) {

    private fun dp(v: Float): Int =
        (v * resources.displayMetrics.density).toInt()

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
    private lateinit var btnRefairePartiels: Button
    private lateinit var btnGarder: Button
    private lateinit var btnPrecedente: Button
    private lateinit var groupeResume: LinearLayout
    private lateinit var txtResume: TextView
    private lateinit var btnEnregistrerAccord: Button

    init {
        orientation = VERTICAL
        construire()
    }

    private fun boutonParcours(texte: String, action: () -> Unit): Button =
        Button(context).apply {
            text = texte
            textSize = 15f
            isAllCaps = false
            setTextColor(COUL_TXT)
            background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
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
            background = fondArrondi(context, COUL_ACCENT, COUL_ACCENT, 8f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LayoutParams(
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
        background = fondArrondi(context, COUL_PANEL, COUL_LINE, 8f)
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(2f), 0, dp(6f)) }
    }

    private fun construire() {
        addView(TextView(context).apply {
            text = "🎹 Nouveau piano"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COUL_TXT)
            setPadding(0, dp(2f), 0, dp(4f))
        })
        addView(TextView(context).apply {
            text = "Mesure de l'inharmonicité de CE piano : l'app te " +
                "demande les la et les do (de La1 à Do6), un par un, " +
                "écoute leurs partiels, calcule le coefficient B de " +
                "chaque ancre et construit un accord sur mesure, " +
                "enregistré dans « Mes accords » — à côté de ceux de " +
                "Simon, qui ne changent jamais."
            textSize = 13f
            setTextColor(COUL_DIM)
            setLineSpacing(0f, 1.45f)
        })
        txtParcoursMessage = TextView(context).apply {
            textSize = 14f
            setTextColor(COUL_BAD)
            setPadding(0, dp(6f), 0, 0)
        }
        addView(txtParcoursMessage)

        // --- Étape 1 : le formulaire (la fiche du piano) ---
        groupeFormulaire = LinearLayout(context).apply {
            orientation = VERTICAL
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
            orientation = HORIZONTAL
            visibility = View.GONE
        }
        rangeeBrouillon.addView(boutonParcours("Reprendre ce piano") {
            actions.surReprendre()
        })
        rangeeBrouillon.addView(boutonParcours("Recommencer à zéro") {
            actions.surRecommencerZero()
        })
        groupeFormulaire.addView(rangeeBrouillon)
        groupeFormulaire.addView(etiquette(context, "Nom du client"))
        champClient = champParcours("ex. Benoît Blanchette")
        groupeFormulaire.addView(champClient)
        groupeFormulaire.addView(etiquette(context, "Nom du piano (marque / modèle)"))
        champPiano = champParcours("ex. Yamaha U1")
        groupeFormulaire.addView(champPiano)
        groupeFormulaire.addView(etiquette(context, "Numéro de série"))
        champSerie = champParcours("ex. 6E34586")
        groupeFormulaire.addView(champSerie)
        txtPitchParcours = TextView(context).apply {
            textSize = 13f
            setTextColor(COUL_DIM)
            setPadding(0, dp(2f), 0, dp(2f))
        }
        groupeFormulaire.addView(txtPitchParcours)
        groupeFormulaire.addView(boutonParcoursPrincipal("Commencer les mesures") {
            actions.surCommencer()
        })
        groupeFormulaire.addView(boutonParcours("Annuler") {
            actions.surFermer()
        })
        addView(groupeFormulaire)

        // --- Étape 2 : les ancres, une à une ---
        groupeAncres = LinearLayout(context).apply {
            orientation = VERTICAL
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
            actions.surAccepterFaible()
        }
        btnAccepterFaible.visibility = View.GONE
        groupeAncres.addView(btnAccepterFaible)
        // Réparation ciblée (0.9) : visible seulement quand
        // l'ajustement a nommé des partiels aberrants ; le
        // libellé les nomme (posé par afficherRefairePartiels).
        btnRefairePartiels = boutonParcoursPrincipal("Refaire ce partiel") {
            actions.surRefairePartiels()
        }
        btnRefairePartiels.visibility = View.GONE
        groupeAncres.addView(btnRefairePartiels)
        // Retour sur une ancre déjà traitée (0.9) : garder
        // l'ancienne mesure et revenir à l'ancre en cours.
        btnGarder = boutonParcoursPrincipal("Garder cette mesure") {
            actions.surGarderAncre()
        }
        btnGarder.visibility = View.GONE
        groupeAncres.addView(btnGarder)
        val rangeeAncre = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
        }
        btnPrecedente = boutonParcours("← Précédente") {
            actions.surNotePrecedente()
        }
        btnPrecedente.visibility = View.GONE
        rangeeAncre.addView(btnPrecedente)
        rangeeAncre.addView(boutonParcours("↺ Rejouer cette note") {
            actions.surRejouerNote()
        })
        rangeeAncre.addView(boutonParcours("Passer cette note →") {
            actions.surPasserNote()
        })
        groupeAncres.addView(rangeeAncre)
        groupeAncres.addView(boutonParcours("Interrompre — reprendre plus tard") {
            actions.surInterrompre()
        })
        addView(groupeAncres)

        // --- Étape 3 : résumé et enregistrement ---
        groupeResume = LinearLayout(context).apply {
            orientation = VERTICAL
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
            actions.surEnregistrer()
        }
        groupeResume.addView(btnEnregistrerAccord)
        groupeResume.addView(boutonParcours("← Revenir aux mesures") {
            actions.surRevenirAuxMesures()
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
                    actions.surAbandonner()
                }
                .setNegativeButton("Retour", null)
                .show()
        })
        addView(groupeResume)
    }

    // ----------------------------------------------------------------
    // Affichage, piloté par le contrôleur.
    // ----------------------------------------------------------------

    /** L'étape des ancres est-elle à l'écran ? */
    val ancresVisibles: Boolean
        get() = ::groupeAncres.isInitialized &&
            groupeAncres.visibility == View.VISIBLE

    fun etapeFormulaire() {
        groupeFormulaire.visibility = View.VISIBLE
        groupeAncres.visibility = View.GONE
        groupeResume.visibility = View.GONE
    }

    fun etapeAncres() {
        groupeFormulaire.visibility = View.GONE
        groupeAncres.visibility = View.VISIBLE
        groupeResume.visibility = View.GONE
    }

    fun etapeResume() {
        groupeAncres.visibility = View.GONE
        groupeFormulaire.visibility = View.GONE
        groupeResume.visibility = View.VISIBLE
    }

    fun afficherMessage(msg: String) {
        txtParcoursMessage.text = msg
    }

    fun afficherDiapason(pitch: Int) {
        txtPitchParcours.text = "Diapason : $pitch Hz (celui réglé à " +
            "l'écran d'accord — change-le là avant de commencer si " +
            "besoin)."
    }

    /**
     * La bannière de reprise : proposée dès qu'un brouillon
     * EXISTE — la fiche seule suffit, même sans aucune ancre
     * validée (correctif 0.6) — et la fiche est toujours
     * recopiée dans les champs.
     */
    fun afficherBrouillon(brouillon: BrouillonParcours?) {
        if (brouillon != null) {
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
    }

    /** « Recommencer à zéro » : champs et bannière repartent vides. */
    fun reinitialiserFormulaire() {
        champClient.text.clear()
        champPiano.text.clear()
        champSerie.text.clear()
        txtBrouillon.visibility = View.GONE
        rangeeBrouillon.visibility = View.GONE
        txtParcoursMessage.text = ""
    }

    /** La fiche telle que tapée (client, piano, série), sans retouche. */
    fun fiche(): Triple<String, String, String> = Triple(
        champClient.text.toString(),
        champPiano.text.toString(),
        champSerie.text.toString()
    )

    fun afficherAncre(nom: String, progres: String) {
        btnAccepterFaible.visibility = View.GONE
        btnRefairePartiels.visibility = View.GONE
        btnGarder.visibility = View.GONE
        btnPrecedente.visibility = View.GONE
        txtAncreNom.text = nom
        txtAncreProgres.text = progres
        txtAncreMessage.text = ""
        txtPartiels.text = "En écoute… joue la note."
    }

    /** Bouton « ← Précédente » (0.9) : offert quand l'ancre précédente peut être rouverte. */
    fun afficherBoutonPrecedent(visible: Boolean) {
        btnPrecedente.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Bouton « Garder cette mesure » (0.9) : visible seulement en reprise d'une ancre déjà traitée. */
    fun afficherGarder(visible: Boolean) {
        btnGarder.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Bouton de réparation ciblée (0.9) : liste vide = caché ;
     * sinon le libellé nomme le ou les partiels à refaire.
     */
    fun afficherRefairePartiels(numeros: List<Int>) {
        if (numeros.isEmpty()) {
            btnRefairePartiels.visibility = View.GONE
            return
        }
        btnRefairePartiels.text = when {
            numeros.size == 1 -> "Refaire le partiel ${numeros[0]}"
            numeros.size == 2 ->
                "Refaire les partiels ${numeros[0]} et ${numeros[1]}"
            else -> "Refaire les partiels " +
                numeros.dropLast(1).joinToString(", ") +
                " et " + numeros.last()
        }
        btnRefairePartiels.visibility = View.VISIBLE
    }

    /**
     * Zone des partiels pendant l'attente de la frappe (0.9,
     * complétée en 0.15) : le message d'attente ET la liste des
     * partiels visés, chacun marqué « en attente » — l'écran ne
     * montre jamais rien (en 0.14, le message seul occupait la
     * zone et les partiels semblaient absents). Ce n'est pas
     * une fausse liste de partiels en cours : la queue de la
     * note précédente n'y apparaît jamais comme une mesure,
     * les lignes disent seulement ce qui sera cherché dès que
     * la porte s'ouvrira.
     */
    fun afficherPartielsAttente(texte: String, partielsVises: List<Int>) {
        txtPartiels.text = texte + "\n\n" +
            partielsVises.joinToString("\n") { n ->
                "Partiel $n · en attente"
            }
    }

    /**
     * Recopie la fiche d'un brouillon dans les champs, sans
     * afficher la bannière de reprise — sert à la clôture
     * franche d'un brouillon d'ancien schéma (0.9) : la fiche
     * survit, les mesures repartent à neuf.
     */
    fun remplirFiche(brouillon: BrouillonParcours) {
        champClient.setText(brouillon.client)
        champPiano.setText(brouillon.piano)
        champSerie.setText(brouillon.serie)
    }

    fun rendrePartiels(etats: List<EtatPartiel>) {
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
    }

    fun afficherMessageAncre(msg: String) {
        txtAncreMessage.text = msg
    }

    fun afficherAccepterFaible(visible: Boolean) {
        btnAccepterFaible.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    fun afficherResume(texte: String, enregistrerVisible: Boolean) {
        txtResume.text = texte
        btnEnregistrerAccord.visibility =
            if (enregistrerVisible) View.VISIBLE else View.GONE
    }
}
