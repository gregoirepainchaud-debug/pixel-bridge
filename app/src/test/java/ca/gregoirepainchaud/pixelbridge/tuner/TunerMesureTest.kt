package ca.gregoirepainchaud.pixelbridge.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Preuve du calcul d'accord sur mesure (version 0.5) — UN cas
 * synthétique complet, dans l'esprit de TunerDspTest (même PRNG
 * déterministe) :
 *
 *   on fabrique les 15 ancres do-sol d'un piano fictif dont la
 *   courbe d'inharmonicité B(k) est CONNUE (en U : forte dans les
 *   graves, creuse au centre, remontante dans les aigus — la
 *   forme réelle des pianos), on génère leurs partiels 1 à 8 par
 *   la formule de Young avec un bruit de mesure uniforme de
 *   ±0,9 ¢, puis on vérifie que la chaîne du build —
 *   ajusterInharmonicite → courbeInharmonicite →
 *   genererTableAccord — retrouve les B des ancres et produit
 *   une table saine.
 *
 * Tolérances énoncées :
 *   - B retrouvé à ±10 % relatif ou ±3×10⁻⁵ absolu (le plus
 *     généreux des deux) ;
 *   - fondamental de la touche 49 (le la) = 440 Hz à ±0,05 Hz ;
 *   - étirement : S(1) ≤ −10 ¢, S(88) ≥ +1 ¢, et aucun recul
 *     d'octave S(k+12) − S(k) sous −0,6 ¢.
 */
class TunerMesureTest {

    /** PRNG de test-dsp.js : s = (s * 1664525 + 1013904223) >>> 0. */
    private class Prng(seed: Int) {
        private var s: Int = seed
        fun rnd(): Double {
            s = s * 1664525 + 1013904223
            return (s.toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    /** La courbe de B « vraie » du piano fictif. */
    private fun bVrai(k: Int): Double =
        0.0011 * exp(-(k - 1) / 16.0) + 0.00016 +
            0.0016 * exp((k - 88) / 9.0)

    @Test
    fun chaineComplete_ancresSynthetiques_tableSaine() {
        // Les ancres du parcours sont bien les 15 do-sol annoncés.
        assertEquals(
            listOf(4, 11, 16, 23, 28, 35, 40, 47, 52, 59, 64, 71, 76, 83, 88),
            ANCRES_PARCOURS
        )

        // 1) Fabrication des ancres synthétiques + ajustement.
        val prng = Prng(20261118)
        val ancres = mutableListOf<MesureAncre>()
        for (cle in ANCRES_PARCOURS) {
            val f1 = 440.0 * 2.0.pow((cle - 49) / 12.0)
            val b = bVrai(cle)
            val partiels = (1..8).map { n ->
                val fExact = n * f1 * sqrt(1 + b * n * n)
                val bruitCents = (prng.rnd() * 2 - 1) * 0.9
                PartielMesure(n, fExact * 2.0.pow(bruitCents / 1200), 0.8)
            }
            val fit = ajusterInharmonicite(partiels)
            assertTrue("ancre $cle : ajustement possible", fit != null)
            val ecartTolere = maxOf(0.10 * b, 0.00003)
            assertTrue(
                "ancre $cle : B retrouvé ${fit!!.b} vs vrai $b " +
                    "(écart ${abs(fit.b - b)}, toléré $ecartTolere)",
                abs(fit.b - b) <= ecartTolere
            )
            ancres.add(
                MesureAncre(
                    key = cle,
                    nom = "Ancre $cle",
                    f1Hz = fit.f1Hz,
                    b = fit.b,
                    ecartCents = 1200 * log2(fit.f1Hz / f1),
                    partiels = fit.partielsRetenus
                )
            )
        }

        // 2) Courbe + génération de la table au diapason 440.
        val courbe = courbeInharmonicite(ancres)
        val noms = List(88) { "Note ${it + 1}" }
        val table = genererTableAccord(courbe, 440, noms)
        assertEquals("88 notes générées", 88, table.size)

        // Le la (touche 49) tombe exactement au diapason choisi.
        assertEquals(440.0, table[48].fundamentalHz440, 0.05)

        // Étirement sain : négatif dans les graves, positif dans
        // les aigus, jamais de recul d'octave franc.
        fun ecart(cle: Int): Double {
            val f1Et = 440.0 * 2.0.pow((cle - 49) / 12.0)
            return 1200 * log2(table[cle - 1].fundamentalHz440 / f1Et)
        }
        assertTrue("S(1) = ${ecart(1)} ¢, attendu ≤ −10 ¢", ecart(1) <= -10.0)
        assertTrue("S(88) = ${ecart(88)} ¢, attendu ≥ +1 ¢", ecart(88) >= 1.0)
        for (cle in 1..76) {
            val recul = ecart(cle + 12) - ecart(cle)
            assertTrue(
                "octave $cle→${cle + 12} : Δ $recul ¢ < −0,6 ¢",
                recul >= -0.6
            )
        }

        // Chaque note a un partiel écouté valide et une cible de
        // partiel au-dessus de son fondamental.
        for (note in table) {
            assertTrue(
                "touche ${note.key} : partiel écouté ${note.listenPartial}",
                note.listenPartial in 1..10
            )
            assertTrue(
                "touche ${note.key} : cible du partiel sous le fondamental",
                note.partialTargetHz440 > note.fundamentalHz440
            )
        }
    }
}
