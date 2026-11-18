package ca.gregoirepainchaud.pixelbridge.tuner.parcours

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ca.gregoirepainchaud.pixelbridge.TracePlantage
import ca.gregoirepainchaud.pixelbridge.tuner.ANCRES_PARCOURS
import ca.gregoirepainchaud.pixelbridge.tuner.Accord
import ca.gregoirepainchaud.pixelbridge.tuner.AnalyseurPartiels
import ca.gregoirepainchaud.pixelbridge.tuner.BrouillonParcours
import ca.gregoirepainchaud.pixelbridge.tuner.MesureAncre
import ca.gregoirepainchaud.pixelbridge.tuner.NoteAccord
import ca.gregoirepainchaud.pixelbridge.tuner.TunerAudioRouteur
import ca.gregoirepainchaud.pixelbridge.tuner.TunerCapture
import ca.gregoirepainchaud.pixelbridge.tuner.chargerBrouillon
import ca.gregoirepainchaud.pixelbridge.tuner.courbeInharmonicite
import ca.gregoirepainchaud.pixelbridge.tuner.effacerBrouillon
import ca.gregoirepainchaud.pixelbridge.tuner.fmt
import ca.gregoirepainchaud.pixelbridge.tuner.fmtSigne
import ca.gregoirepainchaud.pixelbridge.tuner.genererTableAccord
import ca.gregoirepainchaud.pixelbridge.tuner.sauvegarderAccord
import ca.gregoirepainchaud.pixelbridge.tuner.sauvegarderBrouillon
import java.util.Locale

// ---------------------------------------------------------------------------
// Parcours « Nouveau piano » : capture guidée des partiels des
// ancres do-sol, calcul de l'accord sur mesure, enregistrement
// dans « Mes accords ». Les accords de Simon ne sont jamais
// touchés (lecture seule) ; tout ce qui s'écrit ici va dans
// les fichiers locaux de MesAccords.kt.
//
// Chantier structure 0.8 : la mécanique vivait dans
// TunerView.kt, mêlée à l'écran. Elle est ici, inchangée ;
// l'écran est ParcoursMesureView ; les services de l'hôte
// (micro, bascule de mode, liste des accords) passent par
// l'interface HoteParcours, remplie par TunerView.
// ---------------------------------------------------------------------------

class ParcoursMesureControleur(
    private val context: Context,
    private val routeur: TunerAudioRouteur,
    private val hote: HoteParcours
) {

    /**
     * Les services que l'écran principal du tuner fournit au
     * parcours : le micro (politique de démarrage arrêtée dans
     * TunerView), la fermeture du mode, les noms de notes des
     * données chargées, et le rafraîchissement de la liste des
     * accords après un enregistrement.
     */
    interface HoteParcours {
        /** Démarre le micro s'il est arrêté ; dit s'il écoute. */
        fun assurerMicro(): Boolean

        /** Arrête le micro s'il écoute (menus du parcours). */
        fun arreterMicroSiActif()

        /** Ferme le parcours et revient à l'écran principal. */
        fun fermerParcours()

        /** Nom d'une touche (1-88) selon les données chargées. */
        fun nomNote(cle: Int): String

        /** Les 88 noms de notes, pour la table générée. */
        fun nomsNotes(): List<String>

        /** Le diapason réglé à l'écran d'accord. */
        fun diapasonActuel(): Int

        /** Un accord vient d'être enregistré sous ce fichier. */
        fun accordEnregistre(
            idFichier: String, nom: String, dateIso: String, pitch: Int
        )
    }

    /** L'écran du parcours, hébergé par TunerView. */
    val vue: ParcoursMesureView = ParcoursMesureView(context, this)

    private val gestionnaire = Handler(Looper.getMainLooper())

    // --- État du parcours ---
    private var ouvert = false

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

    init {
        // Le routeur détache l'analyseur sur exception et le
        // signale ici : l'écran de l'ancre affiche l'erreur
        // franchement, le brouillon reste intact.
        routeur.surErreurAnalyseur = { e -> surErreurAnalyseur(e) }
    }

    /** L'étape des ancres est-elle à l'écran ? */
    val mesuresVisibles: Boolean get() = vue.ancresVisibles

    /** L'analyseur et le routeur restent toujours synchronisés. */
    private fun definirAnalyseur(a: AnalyseurPartiels?) {
        analyseurActif = a
        routeur.analyseur = a
    }

    private fun surErreurAnalyseur(e: Exception) {
        definirAnalyseur(null)
        gestionnaire.post {
            if (ouvert) {
                vue.afficherMessageAncre(
                    "Erreur d'analyse : " +
                        "${e.javaClass.simpleName} — " +
                        "${e.message}. Pèse « ↺ Rejouer " +
                        "cette note » pour relancer la mesure."
                )
            }
        }
    }

    /** Une erreur de capture signalée par l'hôte pendant le parcours. */
    fun afficherErreurCapture(msg: String) {
        vue.afficherMessageAncre(msg)
    }

    // ----------------------------------------------------------------
    // Ouverture et fermeture (la bascule d'écran est chez l'hôte).
    // ----------------------------------------------------------------

    fun ouvrir() {
        ouvert = true
        TracePlantage.etape = "parcours Nouveau piano — formulaire"
        vue.etapeFormulaire()
        vue.afficherMessage("")
        // Le formulaire est un MENU (aucune mesure ne s'y fait) :
        // la capture s'y arrête (0.6). Elle repartira toute
        // seule au lancement des mesures (demarrerMesures) et au
        // retour à l'écran principal du tuner.
        hote.arreterMicroSiActif()
        vue.afficherDiapason(hote.diapasonActuel())
        vue.afficherBrouillon(chargerBrouillon(context))
        demarrerBoucleParcours()
    }

    /** Nettoyage du parcours ; la bascule d'écran est chez l'hôte. */
    fun fermer() {
        ouvert = false
        definirAnalyseur(null)
        boucleParcoursActive = false
        gestionnaire.removeCallbacks(boucleParcours)
    }

    // ----------------------------------------------------------------
    // Actions de l'écran (boutons de ParcoursMesureView).
    // ----------------------------------------------------------------

    fun surCommencer() = demarrerMesures(reprendre = false)

    fun surReprendre() = demarrerMesures(reprendre = true)

    fun surRecommencerZero() {
        effacerBrouillon(context)
        ancresMesurees.clear()
        vue.reinitialiserFormulaire()
    }

    fun surFermer() {
        hote.fermerParcours()
    }

    fun surAccepterFaible() {
        verdictFaible?.let { validerAncre(it) }
    }

    fun surRejouerNote() {
        if (ancreValidee == null) configurerAncre()
    }

    fun surPasserNote() {
        if (ancreValidee == null) {
            ancresPassees.add(ANCRES_PARCOURS[ancreIndex])
            definirAnalyseur(null)
            avancerAncre()
        }
    }

    fun surInterrompre() {
        sauvegarderBrouillon(context, brouillonCourant())
        hote.fermerParcours()
    }

    fun surEnregistrer() {
        enregistrerAccordFinal()
    }

    fun surRevenirAuxMesures() {
        ancresPassees.clear()
        val idx = prochaineAncreIndex()
        if (idx < 0) {
            terminerMesures()
        } else {
            vue.etapeAncres()
            ancreIndex = idx
            configurerAncre()
            // Retour aux mesures : la capture repart (0.6) —
            // elle s'était arrêtée au résumé.
            if (!hote.assurerMicro()) {
                vue.afficherMessageAncre(
                    "Le micro n'est pas actif : accorde la " +
                        "permission micro, puis rejoue la note."
                )
            }
        }
    }

    fun surAbandonner() {
        effacerBrouillon(context)
        ancresMesurees.clear()
        hote.fermerParcours()
    }

    // ----------------------------------------------------------------
    // Mécanique du parcours (transcrite de TunerView, inchangée).
    // ----------------------------------------------------------------

    private fun brouillonCourant(): BrouillonParcours {
        val (client, piano, serie) = vue.fiche()
        return BrouillonParcours(
            client = client,
            piano = piano,
            serie = serie,
            pitch = parcoursPitch,
            ancres = ancresMesurees.toList()
        )
    }

    private fun demarrerMesures(reprendre: Boolean) {
        if (!hote.assurerMicro()) {
            vue.afficherMessage(
                "Le micro n'est pas actif : impossible de " +
                    "mesurer. Accorde la permission micro, " +
                    "puis réessaie."
            )
            return
        }
        vue.afficherMessage("")
        if (reprendre) {
            val brouillon = chargerBrouillon(context)
            if (brouillon != null) {
                ancresMesurees = brouillon.ancres.toMutableList()
                parcoursPitch = brouillon.pitch
            }
        } else {
            ancresMesurees.clear()
            parcoursPitch = hote.diapasonActuel()
        }
        ancresPassees.clear()
        ancreValidee = null
        verdictFaible = null
        sauvegarderBrouillon(context, brouillonCourant())
        val idx = prochaineAncreIndex()
        if (idx < 0) {
            terminerMesures()
            return
        }
        vue.etapeAncres()
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
            "${hote.nomNote(cle)} (touche $cle)"
        val f1Nominale = parcoursPitch * Math.pow(2.0, (cle - 49) / 12.0)
        definirAnalyseur(
            AnalyseurPartiels(TunerCapture.TAUX, cle, f1Nominale)
        )
        parcoursDebutMs = SystemClock.elapsedRealtime()
        ancreValidee = null
        verdictFaible = null
        vue.afficherAncre(
            nom = hote.nomNote(cle),
            progres = "Ancre " +
                "${ancresMesurees.size + ancresPassees.size + 1} sur " +
                "${ANCRES_PARCOURS.size} — ${hote.nomNote(cle)} (touche $cle)"
        )
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
        if (!ouvert || !vue.ancresVisibles) return
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
            vue.rendrePartiels(analyseur.etatPartiels())
            when (val verdict = analyseur.evaluer(hote.nomNote(analyseur.cleAncre))) {
                is AnalyseurPartiels.Verdict.Pret -> validerAncre(verdict.mesure)
                is AnalyseurPartiels.Verdict.Faible -> {
                    verdictFaible = verdict.mesure
                    vue.afficherAccepterFaible(true)
                    vue.afficherMessageAncre(
                        "Mesure faible : " +
                            verdict.raisons.joinToString(" ; ") +
                            ". Rejoue la note pour l'améliorer, ou accepte-la."
                    )
                }
                is AnalyseurPartiels.Verdict.Insuffisant -> {
                    verdictFaible = null
                    vue.afficherAccepterFaible(false)
                    val ecoule = SystemClock.elapsedRealtime() - parcoursDebutMs
                    if (ecoule > 12000) {
                        vue.afficherMessageAncre(
                            "Mesure insuffisante : " +
                                verdict.raisons.joinToString(" ; ") +
                                ". Rejoue la note franchement, laisse-la " +
                                "sonner, téléphone près des cordes — ou " +
                                "passe cette note."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            definirAnalyseur(null)
            verdictFaible = null
            vue.afficherAccepterFaible(false)
            vue.afficherMessageAncre(
                "Erreur pendant la mesure : " +
                    "${e.javaClass.simpleName} — ${e.message}. Tes " +
                    "ancres déjà mesurées sont sauvegardées ; pèse " +
                    "« ↺ Rejouer cette note » pour reprendre cette ancre."
            )
        }
    }

    private fun validerAncre(mesure: MesureAncre) {
        ancresMesurees.add(mesure)
        sauvegarderBrouillon(context, brouillonCourant())
        definirAnalyseur(null)
        ancreValidee = mesure
        ancreValideeMs = SystemClock.elapsedRealtime()
        vue.afficherAccepterFaible(false)
        vue.afficherMessageAncre(
            "✓ ${mesure.nom} mesurée — " +
                "${mesure.partiels.size} partiels · B = ${fmt(mesure.b, 5)} · " +
                "note à ${fmtSigne(mesure.ecartCents, 0)} ¢ de sa hauteur " +
                "nominale. Ancre suivante…"
        )
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
        definirAnalyseur(null)
        TracePlantage.etape = "parcours Nouveau piano — résumé"
        // Le résumé est un MENU (aucune mesure) : la capture s'y
        // arrête (0.6) — elle repart au retour aux mesures ou à
        // l'écran principal du tuner.
        hote.arreterMicroSiActif()
        vue.etapeResume()
        if (ancresMesurees.size < 8) {
            tableGeneree = null
            courbeGeneree = null
            vue.afficherResume(
                "Seulement ${ancresMesurees.size} ancre(s) " +
                    "mesurée(s) — il en faut au moins 8, réparties sur le " +
                    "clavier, pour construire un accord fiable. Reviens " +
                    "aux mesures pour continuer ce piano, ou abandonne-le.",
                enregistrerVisible = false
            )
            return
        }
        // Filet (0.6) : la génération de l'accord ne doit jamais
        // planter l'app — données absentes ou calcul fautif, on
        // l'affiche franchement et le brouillon conserve les
        // ancres. (En 0.5, donnees!! pouvait lever un NPE ici.)
        TracePlantage.etape = "parcours Nouveau piano — génération de l'accord"
        val noms = hote.nomsNotes()
        val table = try {
            val courbe = courbeInharmonicite(ancresMesurees)
            courbeGeneree = courbe
            genererTableAccord(courbe, parcoursPitch, noms)
        } catch (e: Exception) {
            courbeGeneree = null
            tableGeneree = null
            vue.afficherResume(
                "La génération de l'accord a " +
                    "échoué : ${e.javaClass.simpleName} — ${e.message}. " +
                    "Tes ${ancresMesurees.size} ancres mesurées sont " +
                    "conservées dans le brouillon — reviens aux " +
                    "mesures, puis reviens ici pour réessayer.",
                enregistrerVisible = false
            )
            return
        }
        tableGeneree = table
        val bMin = ancresMesurees.minOf { it.b }
        val bMax = ancresMesurees.maxOf { it.b }
        fun ecartTable(cle: Int): Double {
            val f1 = table[cle - 1].fundamentalHz440
            val f1Et = 440.0 * Math.pow(2.0, (cle - 49) / 12.0)
            return 1200 * kotlin.math.log2(f1 / f1Et)
        }
        val (client, piano, serie) = vue.fiche()
        vue.afficherResume(
            "Piano mesuré : ${ancresMesurees.size} ancres " +
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
                "ne changent jamais.",
            enregistrerVisible = true
        )
    }

    private fun enregistrerAccordFinal() {
        val table = tableGeneree ?: return
        val courbe = courbeGeneree ?: return
        val (clientBrut, pianoBrut, serieBrut) = vue.fiche()
        val client = clientBrut.trim()
        val piano = pianoBrut.trim()
        val serie = serieBrut.trim()
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
            vue.afficherMessage(
                "⚠ L'enregistrement de " +
                    "l'accord a échoué (écriture impossible sur " +
                    "l'appareil). Rien n'est perdu : le brouillon " +
                    "est conservé, réessaie le bouton « Enregistrer " +
                    "l'accord »."
            )
            return
        }
        effacerBrouillon(context)
        ancresMesurees.clear()
        hote.fermerParcours()
        hote.accordEnregistre(idFichier, nom, dateIso, parcoursPitch)
    }
}
