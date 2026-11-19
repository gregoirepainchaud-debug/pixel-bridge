package ca.gregoirepainchaud.pixelbridge.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Machine à états du suivi de trajet natif (0.16) : chaque
 * test est une séquence du monde réel — un réveil, des relevés
 * à des vitesses données, le temps qui passe — et l'état et
 * les points attendus au bout. Le cœur des décisions de
 * Grégoire : le réveil ne part JAMAIS le GPS tout seul (la
 * conduite se qualifie à 20 km/h soutenu), la marche retombe
 * en dormance sans un seul point, et l'arrêt prolongé éteint
 * tout (pause 180 s, veille 600 s, file vidangée).
 */
class GpsLogiqueTest {

    private val t0 = 1_700_000_000_000L

    /** Relevé à Québec, précision 20 m, vitesse portée. */
    private fun fix(
        tsMs: Long, vitesse: Double?,
        precision: Double? = 20.0,
        lat: Double = 46.8, lon: Double = -71.2
    ) = FixGps(tsMs, lat, lon, precision, vitesse)

    private fun List<ActionGps>.enregistres(): List<FixGps> =
        filterIsInstance<ActionGps.Enregistrer>().map { it.fix }

    /** Réveil + deux relevés rapides : la conduite qualifiée. */
    private fun logiqueEnConduite(): GpsLogique {
        val g = GpsLogique()
        g.surReveil(t0, SourceReveil.MOUVEMENT, signalFort = false)
        g.surFix(fix(t0 + 5_000, 15.0))
        val a = g.surFix(fix(t0 + 10_000, 15.0))
        assertEquals(EtatGps.CONDUITE, g.etat)
        assertEquals(1, a.enregistres().size)
        return g
    }

    @Test
    fun auReposAucuneActionAucunPoint() {
        val g = GpsLogique()
        assertEquals(EtatGps.VEILLE, g.etat)
        assertTrue(g.surTemps(t0 + 3_600_000).isEmpty())
        assertFalse(g.enEmission)
    }

    @Test
    fun leReveilOuvreUneVerificationPasLaCapture() {
        val g = GpsLogique()
        val a = g.surReveil(t0, SourceReveil.MOUVEMENT, signalFort = false)
        assertEquals(EtatGps.VERIFICATION, g.etat)
        assertEquals(
            listOf<ActionGps>(ActionGps.DemanderPositions), a
        )
        assertFalse(g.enEmission)
    }

    @Test
    fun laMarcheNeQualifieJamaisEtRetombeEnDormance() {
        val g = GpsLogique()
        g.surReveil(t0, SourceReveil.MOUVEMENT, signalFort = false)
        var points = 0
        // 80 s de marche à 1,4 m/s (~5 km/h), relevé aux 5 s
        // (sous la fenêtre de vérification de 90 s).
        for (i in 1..16) {
            points += g.surFix(fix(t0 + i * 5_000L, 1.4))
                .enregistres().size
        }
        assertEquals(0, points)
        assertEquals(EtatGps.VERIFICATION, g.etat)
        // La fenêtre (90 s) se referme : écoute coupée, veille.
        val a = g.surTemps(t0 + 95_000)
        assertEquals(EtatGps.VEILLE, g.etat)
        assertTrue(a.contains(ActionGps.ArreterPositions))
    }

    @Test
    fun leJoggingSous20KmhNeQualifiePasNonPlus() {
        val g = GpsLogique()
        g.surReveil(t0, SourceReveil.MOUVEMENT, signalFort = false)
        // 3,5 m/s = 12,6 km/h : rapide pour un joggeur, loin
        // du seuil de conduite de 20 km/h.
        repeat(10) { i ->
            g.surFix(fix(t0 + (i + 1) * 5_000L, 3.5))
        }
        assertEquals(EtatGps.VERIFICATION, g.etat)
        assertFalse(g.enEmission)
    }

    @Test
    fun deuxRelevesRapidesQualifientLaConduite() {
        val g = GpsLogique()
        g.surReveil(t0, SourceReveil.BLUETOOTH, signalFort = true)
        val a1 = g.surFix(fix(t0 + 5_000, 15.0))
        assertEquals(EtatGps.VERIFICATION, g.etat)
        assertTrue(a1.enregistres().isEmpty())
        val a2 = g.surFix(fix(t0 + 10_000, 15.0))
        assertEquals(EtatGps.CONDUITE, g.etat)
        assertTrue(g.enEmission)
        // Le relevé qui qualifie devient le premier point.
        assertEquals(1, a2.enregistres().size)
    }

    @Test
    fun unSeulReleveRapidePuisLentNeQualifiePas() {
        val g = GpsLogique()
        g.surReveil(t0, SourceReveil.MOUVEMENT, signalFort = false)
        g.surFix(fix(t0 + 5_000, 15.0))
        g.surFix(fix(t0 + 10_000, 1.0))   // série brisée
        g.surFix(fix(t0 + 15_000, 15.0))  // série à 1 seulement
        assertEquals(EtatGps.VERIFICATION, g.etat)
    }

    @Test
    fun enConduiteLesPointsSuiventLaCadence30s() {
        val g = logiqueEnConduite()
        var points = 0
        // Vitesse portée franche, position fixe (la cadence
        // seule est à l'épreuve : ni la distance ni le temps
        // ne doivent produire plus d'un point aux 30 s).
        for (i in 1..12) {
            points += g.surFix(fix(t0 + 10_000 + i * 5_000L, 15.0))
                .enregistres().size
        }
        // Fenêtre t0+15 s … t0+70 s : points à ~t0+40 s et
        // ~t0+70 s (30 s après le point d'entrée à t0+10 s).
        assertEquals(2, points)
    }

    @Test
    fun enConduite75mForcentUnPointAvantLaCadence() {
        val g = logiqueEnConduite()
        // +0,001° de latitude ≈ 111 m, 10 s après l'entrée.
        val a = g.surFix(
            fix(t0 + 20_000, 15.0, lat = 46.801)
        )
        assertEquals(1, a.enregistres().size)
    }

    @Test
    fun unPointTropImprecisNestPasConserve() {
        val g = logiqueEnConduite()
        val a = g.surFix(
            fix(t0 + 60_000, 15.0, precision = 120.0)
        )
        assertTrue(a.enregistres().isEmpty())
        // Mais le mouvement, lui, est bien vu (pas de pause).
        val b = g.surTemps(t0 + 60_000 + 179_000)
        assertEquals(EtatGps.CONDUITE, g.etat)
        assertTrue(b.isEmpty())
    }

    @Test
    fun unReleveTresImprecisEstEcarteDesLentree() {
        val g = logiqueEnConduite()
        // 250 m de précision : même pas la vitesse — sans lui,
        // l'immobilité court depuis le dernier vrai mouvement.
        g.surFix(fix(t0 + 100_000, 15.0, precision = 250.0))
        val a = g.surTemps(t0 + 10_000 + 180_000)
        assertEquals(EtatGps.PAUSE, g.etat)
        assertTrue(a.isEmpty())
    }

    @Test
    fun arret180sDonnePausePuis600sDonneVeilleEtVidange() {
        val g = logiqueEnConduite()
        // Arrêt complet : relevés à vitesse nulle.
        g.surFix(fix(t0 + 20_000, 0.0))
        val a1 = g.surTemps(t0 + 10_000 + 180_000)
        assertEquals(EtatGps.PAUSE, g.etat)
        assertTrue(a1.isEmpty())
        assertTrue(g.enEmission)
        val a2 = g.surTemps(t0 + 10_000 + 600_000)
        assertEquals(EtatGps.VEILLE, g.etat)
        assertTrue(a2.contains(ActionGps.ArreterPositions))
        assertTrue(a2.contains(ActionGps.Vidanger))
        assertFalse(g.enEmission)
    }

    @Test
    fun enPauseUnPointParMinuteEtLeTraficRepart() {
        val g = logiqueEnConduite()
        g.surFix(fix(t0 + 20_000, 0.0))
        g.surTemps(t0 + 10_000 + 180_000)
        assertEquals(EtatGps.PAUSE, g.etat)
        // Point de pause : le dernier point date de l'entrée
        // en conduite (t0+10 s) — à t0+250 s, il est dû.
        val a = g.surFix(fix(t0 + 250_000, 0.0))
        assertEquals(1, a.enregistres().size)
        // 30 s plus tard : pas encore dû.
        val b = g.surFix(fix(t0 + 280_000, 0.0))
        assertTrue(b.enregistres().isEmpty())
        // Le trafic repart : deux relevés mobiles → conduite.
        g.surFix(fix(t0 + 290_000, 8.0))
        g.surFix(fix(t0 + 295_000, 8.0))
        assertEquals(EtatGps.CONDUITE, g.etat)
    }

    @Test
    fun laVitesseSeDeriveDesPositionsQuandElleManque() {
        val g = GpsLogique()
        g.surReveil(t0, SourceReveil.MOUVEMENT, signalFort = false)
        // Aucune vitesse portée ; les positions avancent de
        // ~100 m aux 5 s = 20 m/s. Il faut trois relevés :
        // le premier pose la référence, les deux suivants
        // dérivent chacun une vitesse rapide (série de 2).
        g.surFix(fix(t0, null, lat = 46.8000))
        g.surFix(fix(t0 + 5_000, null, lat = 46.8009))
        assertEquals(EtatGps.VERIFICATION, g.etat)
        g.surFix(fix(t0 + 10_000, null, lat = 46.8018))
        assertEquals(EtatGps.CONDUITE, g.etat)
    }

    @Test
    fun leBluetoothFortAllongeLaFenetreDeVerification() {
        val fort = GpsLogique()
        fort.surBluetoothAuto(true, t0, signalFort = true)
        assertEquals(EtatGps.VERIFICATION, fort.etat)
        // À 100 s : la fenêtre ordinaire (90 s) serait close,
        // la forte (150 s) tient encore.
        assertTrue(fort.surTemps(t0 + 100_000).isEmpty())
        assertEquals(EtatGps.VERIFICATION, fort.etat)
        fort.surTemps(t0 + 151_000)
        assertEquals(EtatGps.VEILLE, fort.etat)

        val ordinaire = GpsLogique()
        ordinaire.surReveil(t0, SourceReveil.MOUVEMENT, false)
        ordinaire.surTemps(t0 + 91_000)
        assertEquals(EtatGps.VEILLE, ordinaire.etat)
    }

    @Test
    fun laDeconnexionBluetoothNeCoupeRien() {
        val g = logiqueEnConduite()
        val a = g.surBluetoothAuto(false, t0 + 30_000, false)
        assertTrue(a.isEmpty())
        assertEquals(EtatGps.CONDUITE, g.etat)
    }
}
