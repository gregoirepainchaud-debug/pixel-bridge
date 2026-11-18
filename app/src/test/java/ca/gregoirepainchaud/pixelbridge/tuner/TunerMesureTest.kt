package ca.gregoirepainchaud.pixelbridge.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /**
     * Régression du plantage terrain du 2026-11-18 (version
     * 0.6) : la première ancre du parcours, C1 (touche 4,
     * ≈ 32,7 Hz), a fait chasser l'analyseur quelques secondes
     * puis l'app est morte (accès concurrents aux listes de
     * l'analyseur entre le fil de capture et le sondage
     * d'affichage, critères « propre » inatteignables dans les
     * graves profonds). Ici : un C1 synthétique propre (partiels
     * de Young, B = 0,0012) poussé par blocs de 100 ms avec un
     * sondage etatPartiels() entre les blocs, comme sur le
     * terrain — l'analyseur doit CONCLURE (Pret ou Faible),
     * jamais rester Insuffisant, et ne jamais lever.
     */
    @Test
    fun analyseur_graveProfond_C1_conclut() {
        val taux = 48000
        val cle = 4
        val f1 = 440.0 * 2.0.pow((cle - 49) / 12.0) // ≈ 32,7 Hz
        val bVrai = 0.0012
        val analyseur = AnalyseurPartiels(taux, cle, f1)
        val signal = FloatArray(taux * 3)
        val amps = doubleArrayOf(0.6, 1.0, 0.8, 0.65, 0.5, 0.4, 0.32, 0.26)
        for (i in signal.indices) {
            var v = 0.0
            for (n in 1..8) {
                val f = n * f1 * sqrt(1 + bVrai * n * n)
                v += amps[n - 1] *
                    kotlin.math.sin(2 * Math.PI * f * i / taux + n * 1.7)
            }
            signal[i] = (v * 0.22).toFloat()
        }
        var pos = 0
        while (pos < signal.size) {
            val fin = if (pos + 4800 < signal.size) pos + 4800 else signal.size
            analyseur.push(signal.copyOfRange(pos, fin))
            analyseur.etatPartiels() // le sondage d'affichage, comme en vrai
            pos = fin
        }
        val nbPropres = analyseur.etatPartiels().count { it.propre }
        assertTrue(
            "au moins 3 partiels propres sur C1 (obtenu $nbPropres)",
            nbPropres >= 3
        )
        when (val verdict = analyseur.evaluer("Do1")) {
            is AnalyseurPartiels.Verdict.Pret ->
                assertTrue(
                    "B retrouvé ${verdict.mesure.b} (vrai $bVrai)",
                    verdict.mesure.b in 0.0003..0.004
                )
            is AnalyseurPartiels.Verdict.Faible ->
                assertTrue(
                    "B plausible ${verdict.mesure.b} (vrai $bVrai)",
                    verdict.mesure.b in 0.0001..0.008
                )
            is AnalyseurPartiels.Verdict.Insuffisant ->
                fail(
                    "C1 synthétique propre ne doit pas rester " +
                        "insuffisant : ${verdict.raisons}"
                )
        }
    }

    /**
     * Filets de calcul (0.6) : des ancres dégénérées (B nul, B
     * NaN, B négatif, touche en double, liste vide) ne doivent
     * produire ni exception ni valeur non finie — ni dans la
     * courbe, ni dans la table générée derrière.
     */
    @Test
    fun courbeInharmonicite_ancresDegenerees_courbeFinie() {
        val bonnes = ANCRES_PARCOURS.map { cle ->
            MesureAncre(cle, "A$cle", 100.0, 0.0008, 0.0, emptyList())
        }
        val degenerees = bonnes + listOf(
            MesureAncre(4, "A4bis", 100.0, 0.0, 0.0, emptyList()),
            MesureAncre(52, "A52nan", 100.0, Double.NaN, 0.0, emptyList()),
            MesureAncre(88, "A88neg", 100.0, -0.5, 0.0, emptyList())
        )
        val courbe = courbeInharmonicite(degenerees)
        for (k in 1..88) {
            assertTrue(
                "courbe[$k] = ${courbe[k]} doit être finie et bornée",
                courbe[k].isFinite() && courbe[k] in 0.00001..0.008
            )
        }
        val vide = courbeInharmonicite(emptyList())
        for (k in 1..88) {
            assertTrue("courbe vide[$k] finie", vide[k].isFinite())
        }
        val table = genererTableAccord(
            courbe, 440, List(88) { "Note ${it + 1}" }
        )
        for (note in table) {
            assertTrue(
                "touche ${note.key} : fondamental fini",
                note.fundamentalHz440.isFinite() && note.fundamentalHz440 > 0
            )
            assertTrue(
                "touche ${note.key} : cible de partiel finie",
                note.partialTargetHz440.isFinite() && note.partialTargetHz440 > 0
            )
        }
    }

    /**
     * Filet d'ajustement (0.6) : des partiels non finis ou
     * négatifs dans la série sont ignorés, l'ajustement des
     * partiels sains passe comme avant.
     */
    @Test
    fun ajusterInharmonicite_partielsNonFinis_ignores() {
        val f1 = 110.0
        val b = 0.0006
        val propres = (1..8).map { n ->
            PartielMesure(n, n * f1 * sqrt(1 + b * n * n), 0.8)
        }
        val pollues = propres + listOf(
            PartielMesure(9, Double.NaN, 0.9),
            PartielMesure(10, Double.POSITIVE_INFINITY, 0.9),
            PartielMesure(11, -50.0, 0.9)
        )
        val fit = ajusterInharmonicite(pollues)
        assertTrue("ajustement possible malgré la pollution", fit != null)
        assertTrue(
            "B retrouvé ${fit!!.b} vs vrai $b",
            abs(fit.b - b) <= maxOf(0.10 * b, 0.00003)
        )
    }
}
