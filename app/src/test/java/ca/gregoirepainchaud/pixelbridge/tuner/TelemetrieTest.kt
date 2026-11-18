package ca.gregoirepainchaud.pixelbridge.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Machine d'enregistrement du log de télémétrie (0.14) :
 * démarrage/arrêt, comptage, sérialisation JSONL d'un
 * échantillon et d'événements, horloge relative, et le fait
 * que rien ne s'enregistre hors session.
 */
class TelemetrieTest {

    private fun entete() = EnregistreurTelemetrie.Entete(
        versionApp = "0.14",
        debutIso = "2026-11-18T13:20:00-05:00",
        picMs = 300,
        pleinPoidsMs = 1200,
        pleinPoidsEffectifMs = 1200,
        medianeEch = 16,
        tenueMs = 750,
        confiancePct = 8,
        porteCents = 3.0,
        slider = 88,
        alphaSlider = 0.83,
        accordNom = "Steinway D — Samuel",
        accordId = 9,
        diapason = 440.0,
        tauxHz = 48000
    )

    private fun echantillon(tMs: Long) =
        EnregistreurTelemetrie.Echantillon(
            tMs = tMs,
            note = 49,
            hz = 440.12,
            centsBrut = -3.42,
            centsMediane = -3.10,
            centsAiguille = -2.85,
            confiance = 0.87,
            decision = DECISION_FRANCHE,
            poids = 1.0,
            niveauDb = -38.2,
            nbRetenues = 14,
            ageSec = 0.04,
            slider = 88,
            mode = "auto"
        )

    @Test
    fun session_complete_serialisation() {
        val tele = EnregistreurTelemetrie()
        assertFalse(tele.enCours)
        tele.demarrer(10_000, entete())
        assertTrue(tele.enCours)
        tele.echantillon(echantillon(85))
        tele.echantillon(echantillon(170))
        tele.evenement(
            200, EnregistreurTelemetrie.EVT_MARQUE, mapOf("n" to 1)
        )
        tele.evenement(
            250, EnregistreurTelemetrie.EVT_FRAPPE, mapOf("n" to 3)
        )
        assertEquals(2, tele.nbEchantillons)
        assertEquals(2, tele.nbEvenements)
        assertEquals(5_000, tele.dureeMs(15_000))

        val log = tele.arreter(15_000)
        assertFalse(tele.enCours)
        val lignes = log.trim().lines()
        // entete + 2 échantillons + 2 événements + fin.
        assertEquals(6, lignes.size)

        val entete = lignes[0]
        assertTrue(entete.startsWith("{\"type\":\"entete\""))
        assertTrue(entete.contains("\"version_app\":\"0.14\""))
        assertTrue(entete.contains("\"debut_iso\":\"2026-11-18T13:20:00-05:00\""))
        assertTrue(entete.contains("\"pic_ms\":300"))
        assertTrue(entete.contains("\"plein_poids_effectif_ms\":1200"))
        assertTrue(entete.contains("\"confiance_pct\":8"))
        assertTrue(entete.contains("\"porte_cents\":3.00"))
        assertTrue(entete.contains("\"accord\":\"Steinway D — Samuel\""))
        assertTrue(entete.contains("\"diapason\":440.0"))
        assertTrue(entete.contains("\"taux_hz\":48000"))

        val ech = lignes[1]
        assertTrue(ech.startsWith("{\"type\":\"ech\""))
        assertTrue(ech.contains("\"t_ms\":85"))
        assertTrue(ech.contains("\"note\":49"))
        assertTrue(ech.contains("\"hz\":440.12"))
        assertTrue(ech.contains("\"cents_brut\":-3.42"))
        assertTrue(ech.contains("\"cents_mediane\":-3.10"))
        assertTrue(ech.contains("\"cents_aiguille\":-2.85"))
        assertTrue(ech.contains("\"confiance\":0.870"))
        assertTrue(ech.contains("\"decision\":\"franche\""))
        assertTrue(ech.contains("\"poids\":1.00"))
        assertTrue(ech.contains("\"niveau_db\":-38.2"))
        assertTrue(ech.contains("\"nb_retenues\":14"))
        assertTrue(ech.contains("\"age_s\":0.04"))
        assertTrue(ech.contains("\"slider\":88"))
        assertTrue(ech.contains("\"mode\":\"auto\""))

        val marque = lignes[3]
        assertTrue(marque.startsWith("{\"type\":\"evt\""))
        assertTrue(marque.contains("\"t_ms\":200"))
        assertTrue(marque.contains("\"evt\":\"marque\""))
        assertTrue(marque.contains("\"n\":1"))

        val fin = lignes[5]
        assertTrue(fin.startsWith("{\"type\":\"fin\""))
        assertTrue(fin.contains("\"t_ms\":5000"))
        assertTrue(fin.contains("\"nb_ech\":2"))
        assertTrue(fin.contains("\"nb_evt\":2"))
    }

    @Test
    fun horsSession_rienNeSEcrit() {
        val tele = EnregistreurTelemetrie()
        tele.echantillon(echantillon(0))
        tele.evenement(0, EnregistreurTelemetrie.EVT_MARQUE)
        assertEquals(0, tele.nbEchantillons)
        assertEquals(0, tele.nbEvenements)
        assertEquals("", tele.arreter(1_000))
        assertEquals(0, tele.dureeMs(5_000))
    }

    @Test
    fun echantillon_sansMesure_nullsSerialises() {
        val tele = EnregistreurTelemetrie()
        tele.demarrer(0, entete())
        tele.echantillon(
            EnregistreurTelemetrie.Echantillon(
                tMs = 85, note = null, hz = null, centsBrut = null,
                centsMediane = null, centsAiguille = null,
                confiance = 0.0, decision = DECISION_REJETEE,
                poids = 0.0, niveauDb = -91.0, nbRetenues = 0,
                ageSec = 3.25, slider = 88, mode = "bloque"
            )
        )
        val lignes = tele.arreter(1_000).trim().lines()
        val ech = lignes[1]
        assertTrue(ech.contains("\"note\":null"))
        assertTrue(ech.contains("\"hz\":null"))
        assertTrue(ech.contains("\"cents_mediane\":null"))
        assertTrue(ech.contains("\"decision\":\"rejetee\""))
        assertTrue(ech.contains("\"age_s\":3.25"))
    }

    @Test
    fun redemarrage_repartAZero() {
        val tele = EnregistreurTelemetrie()
        tele.demarrer(0, entete())
        tele.echantillon(echantillon(85))
        tele.arreter(1_000)
        tele.demarrer(50_000, entete())
        assertTrue(tele.enCours)
        assertEquals(0, tele.nbEchantillons)
        assertEquals(0, tele.nbEvenements)
        assertEquals(2_000, tele.dureeMs(52_000))
        val lignes = tele.arreter(52_000).trim().lines()
        // entete + fin seulement.
        assertEquals(2, lignes.size)
    }

    @Test
    fun evenement_detailsTypes() {
        val tele = EnregistreurTelemetrie()
        tele.demarrer(0, entete())
        tele.evenement(
            42, EnregistreurTelemetrie.EVT_PARAMETRE,
            mapOf(
                "champ" to "pic_ms",
                "valeur" to "450",
                "actif" to true,
                "alpha" to 0.64
            )
        )
        val evt = tele.arreter(100).trim().lines()[1]
        assertTrue(evt.contains("\"champ\":\"pic_ms\""))
        assertTrue(evt.contains("\"valeur\":\"450\""))
        assertTrue(evt.contains("\"actif\":true"))
        assertTrue(evt.contains("\"alpha\":0.640"))
    }
}
