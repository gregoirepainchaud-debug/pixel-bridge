package ca.gregoirepainchaud.pixelbridge.tuner

import android.content.Context

// ---------------------------------------------------------------------------
// Routeur audio de l'onglet Tuner (chantier structure 0.8) :
// propriétaire de l'unique TunerCapture de l'onglet, il
// distribue les échantillons du micro au consommateur actif —
// l'analyseur de partiels du parcours « Nouveau piano » quand
// une ancre est en mesure, sinon le moteur de l'accordeur.
// Avant le chantier, cette capture et son routage vivaient dans
// TunerView, qui gardait aussi toute la politique de démarrage
// (permission, chien de garde, boucle d'affichage) : celle-ci
// reste dans TunerView, seuls la détention de la capture et
// l'aiguillage des échantillons déménagent ici.
// ---------------------------------------------------------------------------

class TunerAudioRouteur(
    context: Context,
    private val surErreurCapture: (String) -> Unit
) {

    /**
     * L'analyseur de partiels en cours de mesure, quand le
     * parcours en a un ; null sinon (les échantillons vont
     * alors au moteur). Tenu par le contrôleur du parcours,
     * qui le pose à la configuration d'une ancre et le retire
     * dès qu'elle est conclue, passée ou abandonnée.
     */
    @Volatile
    var analyseur: AnalyseurPartiels? = null

    /** Le moteur de l'accordeur (recréé à chaque démarrage). */
    @Volatile
    var moteur: TunerEngine? = null

    /**
     * Une exception de l'analyseur pendant un push : le routeur
     * le détache et le signale ici ; le contrôleur du parcours
     * affiche l'erreur à l'écran de l'ancre. Posé par le
     * contrôleur à sa construction.
     */
    var surErreurAnalyseur: ((Exception) -> Unit)? = null

    private val capture = TunerCapture(
        context,
        surEchantillons = { ech ->
            // Pendant le parcours, le flux micro alimente
            // l'analyseur de partiels plutôt que le traqueur.
            val a = analyseur
            if (a != null) {
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
                    analyseur = null
                    surErreurAnalyseur?.invoke(e)
                }
            } else {
                moteur?.push(ech)
            }
        },
        surErreur = { msg -> surErreurCapture(msg) }
    )

    val enCours: Boolean get() = capture.enCours

    val echantillonsTotal: Long get() = capture.echantillonsTotal

    fun permissionAccordee(): Boolean = capture.permissionAccordee()

    fun demarrer(): String? = capture.demarrer()

    fun arreter() = capture.arreter()
}
