package ca.gregoirepainchaud.pixelbridge.tuner

// ---------------------------------------------------------------------------
// Registre explicite des modes de l'onglet Tuner (chantier
// structure 0.8). Un mode = un écran hébergé par TunerView, qui
// ne conserve que l'écran principal de l'accordeur, le choix de
// mode et l'hébergement du mode actif. Ajouter un mode demain
// (un « relevé », par exemple) = une entrée ici, un écran et un
// contrôleur dans leur propre paquet, et une bascule dans
// TunerView — sans retoucher aux modes existants.
// ---------------------------------------------------------------------------

enum class ModeTuner(val libelle: String) {
    /** L'accordeur lui-même : écran principal, toujours là. */
    ACCORDEUR("Accordeur"),

    /** Le parcours « Nouveau piano » (tuner/parcours/). */
    PARCOURS("Nouveau piano")
}

object ModesTuner {
    /** Tous les modes, dans l'ordre du registre. */
    val tous: List<ModeTuner> = ModeTuner.values().toList()

    /** Le mode affiché à l'ouverture de l'onglet. */
    val defaut: ModeTuner = ModeTuner.ACCORDEUR
}
