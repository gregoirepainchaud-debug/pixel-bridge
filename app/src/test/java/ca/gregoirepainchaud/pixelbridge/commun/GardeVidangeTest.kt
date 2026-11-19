package ca.gregoirepainchaud.pixelbridge.commun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde des vidanges de files : sérialisation (une seule
 * vidange à la fois) et reprise bornée (0.19) — après un
 * échec réseau, le frein grandit par échelons de 30 s,
 * 2 min, 10 min, puis les relances autonomes s'épuisent :
 * les événements naturels reprennent la main. L'horloge
 * est factice : chaque test avance le temps à la main.
 */
class GardeVidangeTest {

    private var maintenant = 1_700_000_000_000L

    private fun garde() = GardeVidange { maintenant }

    /** Une vidange complète : la garde s'ouvre, puis se
     *  referme avec le verdict donné. */
    private fun vidanger(g: GardeVidange, echecReseau: Boolean) {
        assertTrue(g.essayer())
        g.terminer(echecReseau)
    }

    @Test
    fun uneSeuleVidangeALaFois() {
        val g = garde()
        assertTrue(g.essayer())
        assertFalse(g.essayer())
        g.terminer(false)
        assertTrue(g.essayer())
        g.terminer(false)
    }

    @Test
    fun premierEchecFreinDe30Secondes() {
        val g = garde()
        vidanger(g, echecReseau = true)
        assertEquals(30_000L, g.delaiAvantRepriseMs())
        assertFalse(g.essayer())
        maintenant += 29_999L
        assertFalse(g.essayer())
        maintenant += 1L
        // L'heure de la relance est arrivée : délai échu,
        // la garde s'ouvre.
        assertEquals(0L, g.delaiAvantRepriseMs())
        assertTrue(g.essayer())
        g.terminer(false)
        assertEquals(-1L, g.delaiAvantRepriseMs())
    }

    @Test
    fun echecsRepetesEchelonsPuisEpuisement() {
        val g = garde()
        // 1er échec : 30 s.
        vidanger(g, echecReseau = true)
        assertEquals(30_000L, g.delaiAvantRepriseMs())
        maintenant += 30_000L
        // 2e échec : 2 min.
        vidanger(g, echecReseau = true)
        assertEquals(120_000L, g.delaiAvantRepriseMs())
        maintenant += 120_000L
        // 3e échec : 10 min.
        vidanger(g, echecReseau = true)
        assertEquals(600_000L, g.delaiAvantRepriseMs())
        maintenant += 600_000L
        // 4e échec : reprises épuisées — plus de délai
        // programmable, plus de frein ; les événements
        // naturels (ouverture, dépôt) repassent aussitôt.
        vidanger(g, echecReseau = true)
        assertEquals(-1L, g.delaiAvantRepriseMs())
        assertTrue(g.essayer())
        g.terminer(false)
    }

    @Test
    fun unSuccesReinitialiseLaSuite() {
        val g = garde()
        vidanger(g, echecReseau = true)
        maintenant += 30_000L
        vidanger(g, echecReseau = true)
        assertEquals(120_000L, g.delaiAvantRepriseMs())
        maintenant += 120_000L
        vidanger(g, echecReseau = false)
        assertEquals(-1L, g.delaiAvantRepriseMs())
        // La suite est oubliée : le prochain échec repart
        // au premier échelon.
        vidanger(g, echecReseau = true)
        assertEquals(30_000L, g.delaiAvantRepriseMs())
    }

    @Test
    fun retourDeConnectiviteTombeFreinEtSuite() {
        val g = garde()
        vidanger(g, echecReseau = true)
        maintenant += 30_000L
        vidanger(g, echecReseau = true)
        assertEquals(120_000L, g.delaiAvantRepriseMs())
        // Le réseau revient au milieu du frein : la garde
        // s'ouvre tout de suite et la suite repart à zéro.
        g.surConnectiviteRetablie()
        assertEquals(-1L, g.delaiAvantRepriseMs())
        assertTrue(g.essayer())
        g.terminer(echecReseau = true)
        assertEquals(30_000L, g.delaiAvantRepriseMs())
    }
}
