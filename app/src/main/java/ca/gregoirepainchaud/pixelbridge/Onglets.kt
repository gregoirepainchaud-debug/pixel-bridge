package ca.gregoirepainchaud.pixelbridge

// ---------------------------------------------------------------------------
// Registre des onglets de l'app (chantier structure 0.8) : la
// clé, le libellé et l'ordre des onglets vivent ici, en un
// seul endroit. GestionActivity construit sa barre depuis ce
// registre et route l'affichage par clé ; chaque onglet a son
// écran et sa mécanique dans son propre paquet (gestion/web,
// gestion/textos, reglages) — l'activité ne garde que la
// coquille, la navigation et le dispatch du cycle de vie, des
// permissions et des résultats d'activité.
// « Oreille » est un cas à part : l'onglet lance l'activité
// OreilleActivity, le retour système ramène ici.
// Retraits 0.17 (décisions de Grégoire, 2026-11-18) :
// l'onglet Tuner et l'onglet Scanner général ont disparu —
// l'accordeur et le scan général vivent dans leurs apps
// autonomes du bac à sable ; le scanner de reçus, lui, reste
// dans l'onglet Gestion (page Reçus du site, pont natif).
// ---------------------------------------------------------------------------

data class Onglet(val cle: String, val libelle: String)

object Onglets {
    const val GESTION = "gestion"
    const val OREILLE = "oreille"
    const val TEXTOS = "textos"
    const val REGLAGES = "reglages"

    /** Les onglets, dans l'ordre de la barre. */
    val tous: List<Onglet> = listOf(
        Onglet(GESTION, "Gestion"),
        Onglet(OREILLE, "Oreille"),
        Onglet(TEXTOS, "Textos"),
        Onglet(REGLAGES, "Réglages")
    )

    /** L'onglet affiché à l'ouverture de l'app. */
    val defaut: String = GESTION
}
