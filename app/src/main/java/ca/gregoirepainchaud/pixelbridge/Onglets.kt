package ca.gregoirepainchaud.pixelbridge

// ---------------------------------------------------------------------------
// Registre des onglets de l'app (chantier structure 0.8) : la
// clé, le libellé et l'ordre des six onglets vivent ici, en un
// seul endroit. GestionActivity construit sa barre depuis ce
// registre et route l'affichage par clé ; chaque onglet a son
// écran et sa mécanique dans son propre paquet (gestion/web,
// gestion/textos, scanner, tuner, reglages) — l'activité ne
// garde que la coquille, la navigation et le dispatch du cycle
// de vie, des permissions et des résultats d'activité.
// « Oreille » est un cas à part : l'onglet lance l'activité
// OreilleActivity, le retour système ramène ici.
// ---------------------------------------------------------------------------

data class Onglet(val cle: String, val libelle: String)

object Onglets {
    const val GESTION = "gestion"
    const val OREILLE = "oreille"
    const val TEXTOS = "textos"
    const val SCANNER = "scanner"
    const val TUNER = "tuner"
    const val REGLAGES = "reglages"

    /** Les onglets, dans l'ordre de la barre. */
    val tous: List<Onglet> = listOf(
        Onglet(GESTION, "Gestion"),
        Onglet(OREILLE, "Oreille"),
        Onglet(TEXTOS, "Textos"),
        Onglet(SCANNER, "Scanner"),
        Onglet(TUNER, "Tuner"),
        Onglet(REGLAGES, "Réglages")
    )

    /** L'onglet affiché à l'ouverture de l'app. */
    val defaut: String = GESTION
}
