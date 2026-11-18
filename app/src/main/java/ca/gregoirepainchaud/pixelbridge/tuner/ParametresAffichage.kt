package ca.gregoirepainchaud.pixelbridge.tuner

import kotlin.math.max

// ---------------------------------------------------------------------------
// Réglages fins de l'affichage de l'accordeur (0.14) — demande de
// Grégoire : « je veux dans le bas du tuner des fenêtres pour
// entrer des valeurs, pour que l'affichage fonctionne à mon
// goût ». Six valeurs, éditables au bas de l'écran accordeur,
// persistées (PreferencesApp), appliquées en direct au moteur et
// au traqueur de l'écran accordeur — le parcours de mesure garde
// ses propres constantes, intactes.
//
// Cette classe est PURE (aucun Android) : elle porte les défauts
// (les comportements historiques de la 0.12/0.13), les bornes, et
// la validation des textes saisis. Une saisie vide, non numérique
// ou hors plage est rejetée en douceur (appliquer() retourne
// null) : l'ancienne valeur tient, aucun plantage, aucun état
// ambigu. Les tests JVM couvrent bornes, défauts et rejets
// (ParametresAffichageTest).
// ---------------------------------------------------------------------------

object ParametresAffichage {

    /** Les six champs, dans l'ordre d'affichage à l'écran. */
    enum class Champ {
        PIC_MS, PLEIN_POIDS_MS, MEDIANE_ECH, TENUE_MS, CONFIANCE_PCT, PORTE_CENTS
    }

    // Défauts = comportements en vigueur avant la 0.14.
    const val PIC_DEFAUT = 300
    const val PIC_MIN = 0
    const val PIC_MAX = 2000

    const val PLEIN_POIDS_DEFAUT = 1200
    const val PLEIN_POIDS_MIN = 300
    const val PLEIN_POIDS_MAX = 4000

    const val MEDIANE_DEFAUT = 16
    const val MEDIANE_MIN = 1
    const val MEDIANE_MAX = 32

    const val TENUE_DEFAUT = 750
    const val TENUE_MIN = 0
    const val TENUE_MAX = 5000

    /** Confiance minimale d'admission franche, en POURCENT
     * (le seuil du traqueur est 0,08 → 8 % à l'écran). */
    const val CONFIANCE_DEFAUT = 8
    const val CONFIANCE_MIN = 1
    const val CONFIANCE_MAX = 30

    /** Demi-largeur de la porte de continuité, en cents. */
    const val PORTE_DEFAUT = 3.0
    const val PORTE_MIN = 0.5
    const val PORTE_MAX = 10.0

    /** Les six valeurs courantes, déjà validées. */
    data class Valeurs(
        val picMs: Int = PIC_DEFAUT,
        val pleinPoidsMs: Int = PLEIN_POIDS_DEFAUT,
        val medianeEch: Int = MEDIANE_DEFAUT,
        val tenueMs: Int = TENUE_DEFAUT,
        val confiancePct: Int = CONFIANCE_DEFAUT,
        val porteCents: Double = PORTE_DEFAUT
    ) {
        /**
         * Fin effective de la rampe d'attaque : jamais avant la
         * fin du pic ignoré. Si le pic ignoré dépasse le plein
         * poids réglé, le plein poids suit le pic (la rampe est
         * alors de longueur nulle : poids 0 sous le pic, plein
         * poids dès le pic passé).
         */
        val pleinPoidsEffectifMs: Int
            get() = max(pleinPoidsMs, picMs)

        /** Seuil franc d'admission du traqueur (0,08 pour 8 %). */
        val seuilConfiance: Double
            get() = confiancePct / 100.0

        /**
         * Porte resserrée pendant la rampe d'attaque : la
         * moitié de la porte de continuité — la proportion
         * historique (1,5 ¢ pour une porte de 3 ¢, 0.12).
         */
        val porteResserreeCents: Double
            get() = porteCents / 2.0
    }

    val DEFAUTS = Valeurs()

    /**
     * Applique une saisie à un champ. Retourne les nouvelles
     * valeurs, ou null si la saisie est invalide (vide, non
     * numérique, hors plage) — l'appelant garde alors les
     * anciennes valeurs et réaffiche la valeur en vigueur.
     * Entiers stricts pour les champs entiers ; la porte accepte
     * un décimal, point ou virgule (« 1,5 » comme « 1.5 »).
     */
    fun appliquer(actuel: Valeurs, champ: Champ, texteBrut: String?): Valeurs? {
        val texte = texteBrut?.trim().orEmpty()
        if (texte.isEmpty()) return null
        return when (champ) {
            Champ.PIC_MS ->
                entierDans(texte, PIC_MIN, PIC_MAX)
                    ?.let { actuel.copy(picMs = it) }
            Champ.PLEIN_POIDS_MS ->
                entierDans(texte, PLEIN_POIDS_MIN, PLEIN_POIDS_MAX)
                    ?.let { actuel.copy(pleinPoidsMs = it) }
            Champ.MEDIANE_ECH ->
                entierDans(texte, MEDIANE_MIN, MEDIANE_MAX)
                    ?.let { actuel.copy(medianeEch = it) }
            Champ.TENUE_MS ->
                entierDans(texte, TENUE_MIN, TENUE_MAX)
                    ?.let { actuel.copy(tenueMs = it) }
            Champ.CONFIANCE_PCT ->
                entierDans(texte, CONFIANCE_MIN, CONFIANCE_MAX)
                    ?.let { actuel.copy(confiancePct = it) }
            Champ.PORTE_CENTS -> {
                val v = texte.replace(',', '.').toDoubleOrNull()
                if (v == null || !v.isFinite() ||
                    v < PORTE_MIN || v > PORTE_MAX
                ) {
                    null
                } else {
                    actuel.copy(porteCents = v)
                }
            }
        }
    }

    /**
     * Relit les six valeurs depuis leurs textes persistés
     * (null = jamais réglé). Chaque champ retombe sur son
     * défaut s'il est absent ou invalide — indépendamment des
     * autres.
     */
    fun depuisTextes(
        pic: String?, pleinPoids: String?, mediane: String?,
        tenue: String?, confiance: String?, porte: String?
    ): Valeurs {
        var v = DEFAUTS
        v = appliquer(v, Champ.PIC_MS, pic) ?: v
        v = appliquer(v, Champ.PLEIN_POIDS_MS, pleinPoids) ?: v
        v = appliquer(v, Champ.MEDIANE_ECH, mediane) ?: v
        v = appliquer(v, Champ.TENUE_MS, tenue) ?: v
        v = appliquer(v, Champ.CONFIANCE_PCT, confiance) ?: v
        v = appliquer(v, Champ.PORTE_CENTS, porte) ?: v
        return v
    }

    /** Texte canonique d'un champ (réaffiché après validation). */
    fun texteDe(valeurs: Valeurs, champ: Champ): String = when (champ) {
        Champ.PIC_MS -> valeurs.picMs.toString()
        Champ.PLEIN_POIDS_MS -> valeurs.pleinPoidsMs.toString()
        Champ.MEDIANE_ECH -> valeurs.medianeEch.toString()
        Champ.TENUE_MS -> valeurs.tenueMs.toString()
        Champ.CONFIANCE_PCT -> valeurs.confiancePct.toString()
        Champ.PORTE_CENTS -> texteDecimal(valeurs.porteCents)
    }

    /** Texte à persister pour un champ (même forme que texteDe). */
    fun textePersistant(valeurs: Valeurs, champ: Champ): String =
        texteDe(valeurs, champ)

    private fun entierDans(texte: String, min: Int, max: Int): Int? {
        val v = texte.toIntOrNull() ?: return null
        return if (v in min..max) v else null
    }

    /** Décimal sans zéro inutile : 3 → « 3 », 1.5 → « 1.5 ». */
    private fun texteDecimal(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else v.toString()
}
