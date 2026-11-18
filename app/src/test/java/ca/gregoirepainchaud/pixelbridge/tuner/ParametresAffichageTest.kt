package ca.gregoirepainchaud.pixelbridge.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Réglages fins de l'affichage (0.14) : défauts = comportements
 * historiques, bornes respectées, saisies invalides rejetées
 * en douceur (null → l'ancienne valeur tient), conversions
 * (confiance % → seuil, porte resserrée = moitié) et la règle
 * du plein poids effectif (jamais avant la fin du pic ignoré).
 */
class ParametresAffichageTest {

    private val d = ParametresAffichage.DEFAUTS
    private val C = ParametresAffichage.Champ::class.java
    private fun champ(nom: String): ParametresAffichage.Champ =
        ParametresAffichage.Champ.valueOf(nom)

    @Test
    fun defauts_comportementsHistoriques() {
        assertEquals(300, d.picMs)
        assertEquals(1200, d.pleinPoidsMs)
        assertEquals(16, d.medianeEch)
        assertEquals(750, d.tenueMs)
        assertEquals(8, d.confiancePct)
        assertEquals(3.0, d.porteCents, 0.0)
        assertEquals(0.08, d.seuilConfiance, 1e-12)
        assertEquals(1.5, d.porteResserreeCents, 0.0)
        assertEquals(1200, d.pleinPoidsEffectifMs)
        assertTrue(C.enumConstants.isNotEmpty())
    }

    @Test
    fun appliquer_valeursValides() {
        var v = d
        v = ParametresAffichage.appliquer(v, champ("PIC_MS"), "450")!!
        assertEquals(450, v.picMs)
        v = ParametresAffichage.appliquer(v, champ("PLEIN_POIDS_MS"), "2000")!!
        assertEquals(2000, v.pleinPoidsMs)
        v = ParametresAffichage.appliquer(v, champ("MEDIANE_ECH"), "1")!!
        assertEquals(1, v.medianeEch)
        v = ParametresAffichage.appliquer(v, champ("TENUE_MS"), "0")!!
        assertEquals(0, v.tenueMs)
        v = ParametresAffichage.appliquer(v, champ("CONFIANCE_PCT"), "12")!!
        assertEquals(12, v.confiancePct)
        assertEquals(0.12, v.seuilConfiance, 1e-12)
        v = ParametresAffichage.appliquer(v, champ("PORTE_CENTS"), "4.5")!!
        assertEquals(4.5, v.porteCents, 0.0)
        assertEquals(2.25, v.porteResserreeCents, 0.0)
        // Les autres champs n'ont pas bougé.
        assertEquals(450, v.picMs)
    }

    @Test
    fun appliquer_bornesIncluses() {
        assertEquals(0, ParametresAffichage.appliquer(d, champ("PIC_MS"), "0")!!.picMs)
        assertEquals(2000, ParametresAffichage.appliquer(d, champ("PIC_MS"), "2000")!!.picMs)
        assertEquals(300, ParametresAffichage.appliquer(d, champ("PLEIN_POIDS_MS"), "300")!!.pleinPoidsMs)
        assertEquals(4000, ParametresAffichage.appliquer(d, champ("PLEIN_POIDS_MS"), "4000")!!.pleinPoidsMs)
        assertEquals(32, ParametresAffichage.appliquer(d, champ("MEDIANE_ECH"), "32")!!.medianeEch)
        assertEquals(5000, ParametresAffichage.appliquer(d, champ("TENUE_MS"), "5000")!!.tenueMs)
        assertEquals(1, ParametresAffichage.appliquer(d, champ("CONFIANCE_PCT"), "1")!!.confiancePct)
        assertEquals(30, ParametresAffichage.appliquer(d, champ("CONFIANCE_PCT"), "30")!!.confiancePct)
        assertEquals(0.5, ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "0.5")!!.porteCents, 0.0)
        assertEquals(10.0, ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "10")!!.porteCents, 0.0)
    }

    @Test
    fun appliquer_horsPlageRejete() {
        assertNull(ParametresAffichage.appliquer(d, champ("PIC_MS"), "-1"))
        assertNull(ParametresAffichage.appliquer(d, champ("PIC_MS"), "2001"))
        assertNull(ParametresAffichage.appliquer(d, champ("PLEIN_POIDS_MS"), "299"))
        assertNull(ParametresAffichage.appliquer(d, champ("PLEIN_POIDS_MS"), "4001"))
        assertNull(ParametresAffichage.appliquer(d, champ("MEDIANE_ECH"), "0"))
        assertNull(ParametresAffichage.appliquer(d, champ("MEDIANE_ECH"), "33"))
        assertNull(ParametresAffichage.appliquer(d, champ("TENUE_MS"), "5001"))
        assertNull(ParametresAffichage.appliquer(d, champ("CONFIANCE_PCT"), "0"))
        assertNull(ParametresAffichage.appliquer(d, champ("CONFIANCE_PCT"), "31"))
        assertNull(ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "0.4"))
        assertNull(ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "10.1"))
    }

    @Test
    fun appliquer_invalideRejeteEnDouceur() {
        for (texte in listOf("", "   ", "abc", "1.5", "12a", "--3")) {
            assertNull(
                "« $texte » doit être rejeté pour un champ entier",
                ParametresAffichage.appliquer(d, champ("PIC_MS"), texte)
            )
        }
        assertNull(ParametresAffichage.appliquer(d, champ("PIC_MS"), null))
        assertNull(ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "abc"))
        assertNull(ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "NaN"))
        assertNull(ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "Infinity"))
        // Espaces autour : acceptés (saisie réelle au clavier).
        assertEquals(450, ParametresAffichage.appliquer(d, champ("PIC_MS"), " 450 ")!!.picMs)
    }

    @Test
    fun porte_decimaleVirguleOuPoint() {
        assertEquals(
            1.5,
            ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "1,5")!!.porteCents,
            0.0
        )
        assertEquals(
            1.5,
            ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "1.5")!!.porteCents,
            0.0
        )
        // Texte canonique réaffiché : sans zéro inutile.
        val v = ParametresAffichage.appliquer(d, champ("PORTE_CENTS"), "2,5")!!
        assertEquals("2.5", ParametresAffichage.texteDe(v, champ("PORTE_CENTS")))
        assertEquals(
            "3", ParametresAffichage.texteDe(d, champ("PORTE_CENTS"))
        )
    }

    @Test
    fun pleinPoidsEffectif_suitLePic() {
        // Pic sous le plein poids : le plein poids réglé tient.
        assertEquals(1200, d.copy(picMs = 300).pleinPoidsEffectifMs)
        // Pic au-delà du plein poids : le plein poids suit le
        // pic — la rampe ne finit jamais avant la fin du pic.
        val v = d.copy(picMs = 1800, pleinPoidsMs = 1200)
        assertEquals(1800, v.pleinPoidsEffectifMs)
        val v2 = ParametresAffichage.appliquer(d, champ("PIC_MS"), "2000")!!
        assertEquals(2000, v2.pleinPoidsEffectifMs)
    }

    @Test
    fun depuisTextes_champParChamp() {
        // Tout absent : les défauts.
        assertEquals(
            d, ParametresAffichage.depuisTextes(null, null, null, null, null, null)
        )
        // Un champ valide + un champ pourri : le valide tient,
        // le pourri retombe sur son défaut, les autres aussi.
        val v = ParametresAffichage.depuisTextes(
            "500", "pourri", null, "1250", "20", "6"
        )
        assertEquals(500, v.picMs)
        assertEquals(1200, v.pleinPoidsMs)
        assertEquals(16, v.medianeEch)
        assertEquals(1250, v.tenueMs)
        assertEquals(20, v.confiancePct)
        assertEquals(6.0, v.porteCents, 0.0)
        // Aller-retour par les textes persistants.
        val textes = ParametresAffichage.Champ.values().map {
            ParametresAffichage.textePersistant(v, it)
        }
        val relu = ParametresAffichage.depuisTextes(
            textes[0], textes[1], textes[2], textes[3], textes[4], textes[5]
        )
        assertEquals(v, relu)
    }
}
