package ca.gregoirepainchaud.pixelbridge.tuner

// ---------------------------------------------------------------------------
// Arbitre du micro (0.13) — LE point d'arbitrage unique entre les
// deux consommateurs de micro de l'app : le tuner (la capture
// AudioRecord de l'onglet) et l'Oreille (le service d'avant-plan).
//
// Règle de Grégoire : « Accaparer le micro doit se faire
// seulement quand la fenêtre du tuner est active. » Et
// symétriquement : l'Oreille — une séance délibérée, tenue par
// son service — a toujours priorité. Si elle démarre ou roule,
// le tuner libère le micro et ne le reprend que lorsqu'elle a
// fini ; le tuner ne démarre jamais sa capture pendant une
// écoute.
//
// Avant la 0.13, le tuner ne connaissait que deux signaux
// d'arrêt : le changement d'onglet (la visibilité de la vue) et
// la pause de l'activité hôte. Une écoute démarrée par la tuile
// des réglages rapides ou par le bouton de la notification ne
// produit NI l'un NI l'autre — l'activité reste au premier
// plan, l'onglet Tuner reste visible : la capture du tuner
// survivait et les deux AudioRecord se disputaient le micro,
// l'Oreille enregistrant du silence. Et à l'inverse, ouvrir
// l'onglet Tuner pendant une écoute démarrait une deuxième
// capture sans un mot.
//
// Cette classe est une machine à états PURE (aucun Android) :
// les événements du monde y entrent — fenêtre du tuner montrée
// ou cachée, app au premier plan ou en arrière-plan, écran qui
// veut ou non le micro, état d'écoute de l'Oreille — et le
// propriétaire du micro en sort. TunerView se contente
// d'appliquer la décision (appliquerArbitrage) ; les tests JVM
// couvrent les transitions (ArbitreMicroTest).
// ---------------------------------------------------------------------------

class ArbitreMicro {

    enum class Proprietaire { AUCUN, TUNER, OREILLE }

    /** L'onglet Tuner est l'onglet affiché de l'app. */
    var ongletAffiche: Boolean = false
        private set

    /**
     * L'app est au premier plan (activité reprise). Vrai à la
     * construction : la vue du tuner naît toujours dans une
     * activité au premier plan (créée à l'ouverture de l'app ou
     * au premier tap sur l'onglet) ; les pauses et reprises de
     * l'activité le font ensuite vivre.
     */
    var appAuPremierPlan: Boolean = true
        private set

    /**
     * L'écran visible du tuner veut le micro : l'écran principal
     * de l'accordeur et les étapes de mesure du parcours — mais
     * PAS les menus du parcours (formulaire, résumé), règle de
     * la 0.6 que l'arbitre reprend telle quelle.
     */
    var ecranVeutMicro: Boolean = false
        private set

    /**
     * Une séance de l'Oreille démarre ou roule. État LU, jamais
     * deviné : la préférence « ecoute_en_cours » que le service
     * tient, et son fichier d'état (phase « chargement » ou
     * « ecoute ») — voir TunerView.oreilleEnEcouteMaintenant.
     */
    var oreilleEnEcoute: Boolean = false
        private set

    /** Le propriétaire du micro, décidé des seuls événements. */
    val proprietaire: Proprietaire
        get() = when {
            oreilleEnEcoute -> Proprietaire.OREILLE
            ongletAffiche && appAuPremierPlan && ecranVeutMicro ->
                Proprietaire.TUNER
            else -> Proprietaire.AUCUN
        }

    /** Le tuner doit-il tenir sa capture en ce moment ? */
    val tunerDoitCapturer: Boolean
        get() = proprietaire == Proprietaire.TUNER

    /**
     * L'écran du tuner est affiché mais le micro est à
     * l'Oreille : un état à DIRE en toutes lettres (bandeau de
     * TunerView), jamais un silence mort.
     */
    val tunerBloqueParOreille: Boolean
        get() = oreilleEnEcoute && ongletAffiche

    // --- Événements du monde ---

    fun ongletMontre() {
        ongletAffiche = true
    }

    fun ongletCache() {
        ongletAffiche = false
    }

    fun appPasseEnArrierePlan() {
        appAuPremierPlan = false
    }

    fun appRevientAuPremierPlan() {
        appAuPremierPlan = true
    }

    fun ecranVeutMicro(veut: Boolean) {
        ecranVeutMicro = veut
    }

    fun ecouteOreilleChange(enEcoute: Boolean) {
        oreilleEnEcoute = enEcoute
    }
}
