package ca.gregoirepainchaud.pixelbridge.tuner

import ca.gregoirepainchaud.pixelbridge.tuner.ArbitreMicro.Proprietaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arbitre du micro (0.13) : la machine à états qui décide qui
 * détient le micro — le tuner, l'Oreille, ou personne. Règle de
 * Grégoire : le tuner n'accapare le micro que lorsque sa fenêtre
 * est active, et l'Oreille a toujours priorité. Chaque test est
 * une séquence d'événements du monde réel (onglet montré ou
 * caché, app en arrière-plan, menus du parcours, écoute qui
 * démarre ou finit) et le propriétaire attendu au bout.
 */
class ArbitreMicroTest {

    /** Un arbitre « au repos dans l'onglet Tuner » : fenêtre
     *  montrée, app au premier plan, écran principal qui veut le
     *  micro — l'état de travail normal de l'accordeur. */
    private fun arbitreAuTuner(): ArbitreMicro {
        val a = ArbitreMicro()
        a.ecranVeutMicro(true)
        a.ongletMontre()
        return a
    }

    @Test
    fun auReposPersonneNeDetientLeMicro() {
        val a = ArbitreMicro()
        assertEquals(Proprietaire.AUCUN, a.proprietaire)
        assertFalse(a.tunerDoitCapturer)
        assertFalse(a.tunerBloqueParOreille)
    }

    @Test
    fun fenetreDuTunerActiveLeTunerCapture() {
        val a = arbitreAuTuner()
        assertEquals(Proprietaire.TUNER, a.proprietaire)
        assertTrue(a.tunerDoitCapturer)
    }

    @Test
    fun ongletCacheLeTunerLibere() {
        val a = arbitreAuTuner()
        a.ongletCache()
        assertEquals(Proprietaire.AUCUN, a.proprietaire)
        assertFalse(a.tunerDoitCapturer)
        // Et il reprend tout seul au retour sur l'onglet.
        a.ongletMontre()
        assertEquals(Proprietaire.TUNER, a.proprietaire)
    }

    @Test
    fun arrierePlanLeTunerLibereEtReprendAuRetour() {
        val a = arbitreAuTuner()
        a.appPasseEnArrierePlan()
        assertEquals(Proprietaire.AUCUN, a.proprietaire)
        a.appRevientAuPremierPlan()
        assertEquals(Proprietaire.TUNER, a.proprietaire)
    }

    @Test
    fun menuDuParcoursLeTunerNeCapturePas() {
        // Formulaire ou résumé du parcours : la fenêtre du tuner
        // est affichée, mais l'écran ne veut pas le micro (0.6).
        val a = ArbitreMicro()
        a.ongletMontre()
        a.ecranVeutMicro(false)
        assertEquals(Proprietaire.AUCUN, a.proprietaire)
        // Étape de mesure : l'écran veut le micro, la capture part.
        a.ecranVeutMicro(true)
        assertEquals(Proprietaire.TUNER, a.proprietaire)
        // Retour au résumé : elle s'arrête.
        a.ecranVeutMicro(false)
        assertEquals(Proprietaire.AUCUN, a.proprietaire)
    }

    @Test
    fun oreilleQuiDemarrePreempteLeTunerEnPleineFenetre() {
        // LE cas vécu : écoute démarrée par la tuile pendant que
        // l'onglet Tuner est affiché et capture — ni changement
        // d'onglet, ni arrière-plan. L'Oreille prend le micro.
        val a = arbitreAuTuner()
        assertEquals(Proprietaire.TUNER, a.proprietaire)
        a.ecouteOreilleChange(true)
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        assertFalse(a.tunerDoitCapturer)
        // L'écran du tuner est là, bloqué : un état à afficher.
        assertTrue(a.tunerBloqueParOreille)
    }

    @Test
    fun oreilleQuiFinitLeTunerReprendToutSeul() {
        val a = arbitreAuTuner()
        a.ecouteOreilleChange(true)
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        a.ecouteOreilleChange(false)
        assertEquals(Proprietaire.TUNER, a.proprietaire)
        assertTrue(a.tunerDoitCapturer)
        assertFalse(a.tunerBloqueParOreille)
    }

    @Test
    fun oreilleDejaEnCoursLeTunerNeDemarreJamais() {
        // L'écoute roule déjà (service d'avant-plan) et on ouvre
        // l'onglet Tuner : la capture du tuner ne démarre pas.
        val a = ArbitreMicro()
        a.ecouteOreilleChange(true)
        a.ecranVeutMicro(true)
        a.ongletMontre()
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        assertFalse(a.tunerDoitCapturer)
        assertTrue(a.tunerBloqueParOreille)
    }

    @Test
    fun oreilleEnCoursRetourAuPremierPlanNeRelancePasLeTuner() {
        val a = arbitreAuTuner()
        a.ecouteOreilleChange(true)
        a.appPasseEnArrierePlan()
        a.appRevientAuPremierPlan()
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        assertFalse(a.tunerDoitCapturer)
    }

    @Test
    fun oreilleEnCoursOngletCacheAucunBlocageAffiche() {
        // L'Oreille roule mais l'écran du tuner n'est pas là :
        // rien à afficher, personne ne capture côté tuner.
        val a = ArbitreMicro()
        a.ecouteOreilleChange(true)
        a.ecranVeutMicro(true)
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        assertFalse(a.tunerBloqueParOreille)
    }

    @Test
    fun oreillePendantUnMenuDuParcoursPuisFinRienNeRepart() {
        // Écoute pendant le formulaire du parcours ; à sa fin,
        // le menu ne veut toujours pas le micro : rien ne repart.
        val a = ArbitreMicro()
        a.ongletMontre()
        a.ecranVeutMicro(false)
        a.ecouteOreilleChange(true)
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        a.ecouteOreilleChange(false)
        assertEquals(Proprietaire.AUCUN, a.proprietaire)
        assertFalse(a.tunerDoitCapturer)
    }

    @Test
    fun oreillePuisOngletCachePuisRetourToujoursOreille() {
        // Pendant l'écoute, on quitte l'onglet et on y revient :
        // le tuner ne profite pas du détour pour prendre le micro.
        val a = arbitreAuTuner()
        a.ecouteOreilleChange(true)
        a.ongletCache()
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        a.ongletMontre()
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        assertTrue(a.tunerBloqueParOreille)
    }

    @Test
    fun sequenceCompleteUneJourneeDAccords() {
        // Ouverture de l'onglet, mesure d'un piano, écoute
        // Oreille en plein milieu, fin de l'écoute, menu du
        // parcours, fermeture de l'onglet.
        val a = ArbitreMicro()
        a.ecranVeutMicro(true)
        a.ongletMontre()
        assertEquals(Proprietaire.TUNER, a.proprietaire)
        a.ecouteOreilleChange(true)
        assertEquals(Proprietaire.OREILLE, a.proprietaire)
        a.ecouteOreilleChange(false)
        assertEquals(Proprietaire.TUNER, a.proprietaire)
        a.ecranVeutMicro(false) // résumé du parcours
        assertEquals(Proprietaire.AUCUN, a.proprietaire)
        a.ecranVeutMicro(true) // retour aux mesures
        assertEquals(Proprietaire.TUNER, a.proprietaire)
        a.ongletCache()
        assertEquals(Proprietaire.AUCUN, a.proprietaire)
    }
}
