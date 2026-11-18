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
import ca.gregoirepainchaud.pixelbridge.tuner.brouillonCompatible
import ca.gregoirepainchaud.pixelbridge.tuner.chargerBrouillon
import ca.gregoirepainchaud.pixelbridge.tuner.courbeInharmonicite
import ca.gregoirepainchaud.pixelbridge.tuner.effacerBrouillon
import ca.gregoirepainchaud.pixelbridge.tuner.fmt
import ca.gregoirepainchaud.pixelbridge.tuner.fmtSigne
import ca.gregoirepainchaud.pixelbridge.tuner.genererTableAccord
import ca.gregoirepainchaud.pixelbridge.tuner.raisonsGenerationImpossible
import ca.gregoirepainchaud.pixelbridge.tuner.sauvegarderAccord
import ca.gregoirepainchaud.pixelbridge.tuner.sauvegarderBrouillon
import java.util.Locale

// ---------------------------------------------------------------------------
// Parcours « Nouveau piano » : capture guidée des partiels des
// ancres la-do (de La1 à Do6, schéma 0.9), calcul de l'accord
// sur mesure, enregistrement dans « Mes accords ». Les accords
// de Simon ne sont jamais touchés (lecture seule) ; tout ce
// qui s'écrit ici va dans les fichiers locaux de
// MesAccords.kt.
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
    // Réparation ciblée (0.9) : les partiels aberrants nommés
    // par le dernier verdict Insuffisant de l'ancre en cours,
    // que le bouton « Refaire le(s) partiel(s) » déverrouille.
    private var aberrantsCourants: List<Int> = emptyList()
    // Annonce de la compatibilité par partiel (0.15) : dite
    // une seule fois par ancre, quand la porte s'ouvre sur un
    // partiel de l'ancre plutôt que sur l'ancre elle-même.
    private var annoncePartielFaite = false
    // Navigation arrière (0.9) : pendant une reprise, on est
    // revenu sur l'ancre précédente (déjà traitée) ; ses
    // données sont intactes dans ancresMesurees/ancresPassees
    // tant qu'on ne les remplace pas, et retourIndex désigne
    // l'ancre en cours à laquelle on revient ensuite.
    private var enReprise = false
    private var retourIndex = 0
    private var repriseEtaitPassee = false
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
        val brouillon = chargerBrouillon(context)
        if (brouillon != null && !brouillonCompatible(brouillon.ancres)) {
            // Brouillon de l'ANCIEN schéma (do/sol, avant la
            // 0.9) : ses ancres ne correspondent plus aux
            // ancres la-do — on le CLOS franchement, avec
            // l'explication à l'écran ; la fiche est recopiée
            // dans le formulaire, aucune mesure n'est gardée,
            // rien ne disparaît en silence.
            effacerBrouillon(context)
            vue.remplirFiche(brouillon)
            vue.afficherBrouillon(null)
            vue.afficherMessage(
                "Le parcours a changé : les ancres mesurées " +
                    "sont maintenant les la et les do, de La1 " +
                    "à Do6. Le brouillon de l'ancien parcours " +
                    "(do et sol) a été clos — la fiche est " +
                    "recopiée ci-dessous, les mesures " +
                    "repartent à neuf."
            )
        } else {
            vue.afficherBrouillon(brouillon)
        }
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
        if (ancreValidee != null) return
        if (enReprise) {
            // Repasser une ancre rouverte (0.9) : sa mesure
            // éventuelle est retirée, elle redevient « passée »,
            // et on revient à l'ancre en cours.
            val cle = ANCRES_PARCOURS[ancreIndex]
            ancresMesurees.removeAll { it.key == cle }
            ancresPassees.add(cle)
            definirAnalyseur(null)
            sauvegarderBrouillon(context, brouillonCourant())
            retourAuxMesuresApresReprise()
            return
        }
        ancresPassees.add(ANCRES_PARCOURS[ancreIndex])
        definirAnalyseur(null)
        avancerAncre()
    }

    /**
     * « ← Précédente » (0.9) : rouvre l'ancre juste avant
     * l'ancre en cours, si elle a déjà été traitée (mesurée
     * ou passée). Ses données restent en place tant qu'on ne
     * les remplace pas : la remesurer écrase l'ancienne
     * mesure (validerAncre remplace par touche), « Garder
     * cette mesure » revient sans rien changer, « Passer »
     * la repasse. Aucune autre ancre ni la fiche ne bougent ;
     * le brouillon est resauvegardé à chaque sortie de
     * reprise.
     */
    fun surNotePrecedente() {
        if (ancreValidee != null || enReprise || ancreIndex <= 0) return
        val idxPrec = ancreIndex - 1
        val clePrec = ANCRES_PARCOURS[idxPrec]
        val mesuree = ancresMesurees.firstOrNull { it.key == clePrec }
        if (mesuree == null && clePrec !in ancresPassees) return
        enReprise = true
        retourIndex = ancreIndex
        repriseEtaitPassee = mesuree == null
        if (repriseEtaitPassee) ancresPassees.remove(clePrec)
        definirAnalyseur(null)
        ancreIndex = idxPrec
        configurerAncre()
        vue.afficherMessageAncre(
            if (mesuree != null) {
                "Retour sur ${mesuree.nom} : mesurée avec " +
                    "${mesuree.partiels.size} partiels " +
                    "(B = ${fmt(mesuree.b, 5)}). Rejoue-la pour " +
                    "la refaire — la nouvelle mesure remplacera " +
                    "l'ancienne — ou « Garder cette mesure » " +
                    "pour revenir sans rien changer."
            } else {
                "Retour sur ${hote.nomNote(clePrec)} : elle " +
                    "avait été passée. Mesure-la maintenant, " +
                    "ou « Garder cette mesure » pour la " +
                    "laisser passée et revenir."
            }
        )
    }

    /** « Garder cette mesure » (0.9) : sort de la reprise sans rien changer. */
    fun surGarderAncre() {
        if (!enReprise) return
        val cle = ANCRES_PARCOURS[ancreIndex]
        if (repriseEtaitPassee &&
            ancresMesurees.none { it.key == cle }
        ) {
            ancresPassees.add(cle)
        }
        definirAnalyseur(null)
        sauvegarderBrouillon(context, brouillonCourant())
        retourAuxMesuresApresReprise()
    }

    /**
     * « Refaire le(s) partiel(s) » (0.9) : déverrouille
     * seulement les partiels nommés aberrants par le verdict
     * et relance leur mesure — les autres restent fixés, la
     * frappe suivante les remesurera (la porte de l'analyseur
     * est déjà ouverte : la note sonne encore).
     */
    fun surRefairePartiels() {
        val analyseur = analyseurActif ?: return
        if (aberrantsCourants.isEmpty()) return
        val numeros = aberrantsCourants
        analyseur.defixerPartiels(numeros)
        aberrantsCourants = emptyList()
        vue.afficherRefairePartiels(emptyList())
        parcoursDebutMs = SystemClock.elapsedRealtime()
        vue.afficherMessageAncre(
            if (numeros.size == 1) {
                "Partiel ${numeros[0]} déverrouillé — rejoue " +
                    "la note franchement et laisse-la sonner : " +
                    "je ne remesure que lui."
            } else {
                "Partiels ${numeros.joinToString(", ")} " +
                    "déverrouillés — rejoue la note franchement " +
                    "et laisse-la sonner : je ne remesure " +
                    "que ceux-là."
            }
        )
    }

    /** Retour de reprise : l'ancre en cours reprend, fraîche. */
    private fun retourAuxMesuresApresReprise() {
        enReprise = false
        ancreIndex = retourIndex
        configurerAncre()
        vue.afficherMessageAncre(
            "De retour sur " +
                "${hote.nomNote(ANCRES_PARCOURS[ancreIndex])} — " +
                "joue-la franchement pour reprendre sa mesure."
        )
    }

    fun surInterrompre() {
        sauvegarderBrouillon(context, brouillonCourant())
        hote.fermerParcours()
    }

    fun surEnregistrer() {
        enregistrerAccordFinal()
    }

    fun surRevenirAuxMesures() {
        enReprise = false
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

    /** « le partiel 5 », « les partiels 5 et 7 », « les partiels 5, 7 et 8 ». */
    private fun nommerPartiels(numeros: List<Int>): String = when {
        numeros.size == 1 -> "le partiel ${numeros[0]}"
        numeros.size == 2 -> "les partiels ${numeros[0]} et ${numeros[1]}"
        else -> "les partiels " +
            numeros.dropLast(1).joinToString(", ") +
            " et " + numeros.last()
    }

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
            // On ne reprend que des ancres du schéma courant
            // (0.9) — un brouillon d'ancien schéma a été clos
            // à l'ouverture ; ceinture et bretelles ici.
            if (brouillon != null &&
                brouillonCompatible(brouillon.ancres)
            ) {
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
        enReprise = false
        aberrantsCourants = emptyList()
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
        aberrantsCourants = emptyList()
        annoncePartielFaite = false
        vue.afficherAncre(
            nom = hote.nomNote(cle),
            progres = if (enReprise) {
                "Retour sur une ancre déjà traitée — " +
                    "${hote.nomNote(cle)} (touche $cle). Les " +
                    "autres ancres et la fiche sont intactes."
            } else {
                "Ancre " +
                    "${ancresMesurees.size + ancresPassees.size + 1} sur " +
                    "${ANCRES_PARCOURS.size} — ${hote.nomNote(cle)} (touche $cle)"
            }
        )
        vue.afficherGarder(enReprise)
        vue.afficherBoutonPrecedent(
            !enReprise && ancreIndex > 0 && run {
                val clePrec = ANCRES_PARCOURS[ancreIndex - 1]
                ancresMesurees.any { it.key == clePrec } ||
                    clePrec in ancresPassees
            }
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
            if (!analyseur.porteOuverte) {
                // Porte de frappe fermée (0.9) : état d'attente
                // explicite. On nomme ce qui est entendu quand
                // ce n'est pas la cible — la queue de la note
                // précédente, typiquement — et on n'évalue rien :
                // aucune mesure ne part avant la frappe de la
                // bonne note. La zone montre AUSSI les partiels
                // visés, en attente (0.15) : aucun état muet —
                // Grégoire comparait avec le temps où l'on
                // voyait les partiels essayer de se verrouiller.
                verdictFaible = null
                vue.afficherAccepterFaible(false)
                vue.afficherRefairePartiels(emptyList())
                val cleCible = ANCRES_PARCOURS[ancreIndex]
                val entendue = analyseur.cleEntendue
                val partielEntendu = analyseur.partielEntenduNumero
                vue.afficherPartielsAttente(
                    when {
                        entendue == null ->
                            "En attente de la frappe — joue " +
                                "${hote.nomNote(cleCible)} franchement, " +
                                "laisse-la sonner."
                        entendue == cleCible ->
                            "J'entends ${hote.nomNote(cleCible)}… " +
                                "encore un instant, la mesure démarre."
                        partielEntendu != null ->
                            // Compatibilité par partiel (0.15) :
                            // ce qui domine est un partiel de
                            // l'ancre — on le dit, ce n'est ni un
                            // refus ni la note précédente.
                            "J'entends ${hote.nomNote(entendue)} — " +
                                "c'est le ${partielEntendu}e partiel " +
                                "de ${hote.nomNote(cleCible)}… encore " +
                                "un instant, la mesure démarre sur " +
                                "${hote.nomNote(cleCible)}."
                        else ->
                            "J'entends encore " +
                                "${hote.nomNote(entendue)} — joue " +
                                "${hote.nomNote(cleCible)} franchement " +
                                "pour passer à cette ancre."
                    },
                    analyseur.partielsVises
                )
                return
            }
            // Porte ouverte par un partiel de l'ancre (0.15) :
            // on l'annonce une fois, franchement — le
            // fondamental sortait faible, le partiel entendu a
            // ouvert la porte, la mesure court sur l'ancre.
            val partielDecl = analyseur.partielDeclencheur
            if (partielDecl != null && !annoncePartielFaite) {
                annoncePartielFaite = true
                val cleCible = ANCRES_PARCOURS[ancreIndex]
                val nomEntendue = analyseur.cleDeclencheuse
                    ?.let { hote.nomNote(it) }
                vue.afficherMessageAncre(
                    "J'entends ${nomEntendue ?: "un partiel"} — " +
                        "c'est le ${partielDecl}e partiel de " +
                        "${hote.nomNote(cleCible)} : je continue " +
                        "sur ${hote.nomNote(cleCible)}."
                )
            }
            vue.rendrePartiels(analyseur.etatPartiels())
            when (val verdict = analyseur.evaluer(hote.nomNote(analyseur.cleAncre))) {
                is AnalyseurPartiels.Verdict.Pret -> {
                    vue.afficherRefairePartiels(emptyList())
                    validerAncre(verdict.mesure)
                }
                is AnalyseurPartiels.Verdict.Faible -> {
                    verdictFaible = verdict.mesure
                    vue.afficherRefairePartiels(emptyList())
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
                    if (verdict.aberrants.isNotEmpty() &&
                        analyseur.tousPartielsFixes()
                    ) {
                        // Échec réparable (0.9) : la série est
                        // complète mais ne tient pas ensemble, et
                        // le diagnostic nomme le(s) coupable(s) —
                        // on propose de ne refaire que lui/eux,
                        // tout de suite, pas après 12 s.
                        aberrantsCourants = verdict.aberrants
                        vue.afficherRefairePartiels(verdict.aberrants)
                        vue.afficherMessageAncre(
                            "La série ne tient pas ensemble : " +
                                nommerPartiels(verdict.aberrants) +
                                if (verdict.aberrants.size == 1) {
                                    " semble accroché au mauvais " +
                                        "pic. Refais seulement ce " +
                                        "partiel — le reste de la " +
                                        "mesure est conservé."
                                } else {
                                    " semblent accrochés aux " +
                                        "mauvais pics. Refais " +
                                        "seulement ces partiels — " +
                                        "le reste de la mesure " +
                                        "est conservé."
                                }
                        )
                    } else {
                        vue.afficherRefairePartiels(emptyList())
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
        // Remplacement par touche (0.9) : remesurer une ancre
        // en reprise ÉCRASE son ancienne mesure au lieu de
        // créer un doublon ; la liste reste dans l'ordre du
        // parcours.
        ancresMesurees.removeAll { it.key == mesure.key }
        ancresMesurees.add(mesure)
        ancresMesurees.sortBy { it.key }
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
        if (enReprise) {
            // L'ancre rouverte vient d'être remesurée (0.9) :
            // retour à l'ancre en cours, pas de progression
            // dans le parcours.
            retourAuxMesuresApresReprise()
            return
        }
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
        // Règle de génération 0.9 (partagée avec le module de
        // mesure) : ≥ 7 ancres sur 10 ET une ancre dans les
        // graves (≤ La2) ET une dans les aigus (≥ Do5) — les
        // raisons disent exactement ce qui manque.
        val raisons = raisonsGenerationImpossible(ancresMesurees)
        if (raisons.isNotEmpty()) {
            tableGeneree = null
            courbeGeneree = null
            vue.afficherResume(
                "Pas encore de quoi construire un accord " +
                    "fiable : " + raisons.joinToString(" ") +
                    " Reviens aux mesures pour continuer ce " +
                    "piano, ou abandonne-le.",
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
