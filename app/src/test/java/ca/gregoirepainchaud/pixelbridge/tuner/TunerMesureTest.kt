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

        // Garde-fou d'étirement (0.7) — référence d'oreille de
        // Grégoire : « mon oreille étire pas mal les basses ».
        // C'est LE territoire où le logiciel sera jugé : avec
        // le B grave fort de ce piano fictif (≈ 0,0011 à la
        // touche 4), la chaîne doit produire un étirement des
        // basses du même ordre, pas un résultat timide. La
        // mathématique exacte du module donne, pour cette
        // courbe : S(1) ≈ −56 ¢ et S(88) ≈ +2 ¢ (un B faible
        // de 0,0002 partout ne donnerait que ≈ −15 ¢ en bas).
        // Le seuil de −35 ¢ laisse passer le bruit
        // d'ajustement (±10 % sur les B retrouvés) tout en
        // attrapant une propagation qui écraserait les graves
        // — un tel résultat serait un symptôme à traiter, pas
        // un résultat à livrer.
        println(
            "Étirement synthétique (B grave ≈ 0,0011) : " +
                "S(1) = ${ecart(1)} ¢, S(88) = ${ecart(88)} ¢"
        )
        assertTrue(
            "S(1) = ${ecart(1)} ¢ : étirement des basses timide " +
                "pour un B grave fort (attendu ≤ −35 ¢)",
            ecart(1) <= -35.0
        )
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
        // (0.7) Ces partiels propres sont des partiels FIXÉS :
        // leur valeur ne bougera plus pour cette ancre.
        val nbFixes = analyseur.etatPartiels()
            .count { it.fixation != FixationPartiel.AUCUNE }
        assertTrue(
            "au moins 3 partiels fixés sur C1 (obtenu $nbFixes)",
            nbFixes >= 3
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

    // -----------------------------------------------------------------
    // Règles de fixation 0.7 — aides et tests dédiés.
    // -----------------------------------------------------------------

    /**
     * Génère un signal d'ancre par intégration de phase :
     * freqInstantanee(n, t) donne la fréquence instantanée du
     * partiel n à l'instant t (secondes) — permet les partiels
     * qui glissent, oscillent ou sautent, comme sur le terrain.
     */
    private fun genererSignal(
        taux: Int,
        dureeSec: Double,
        partiels: Set<Int>,
        freqInstantanee: (Int, Double) -> Double
    ): FloatArray {
        val nb = (taux * dureeSec).toInt()
        val signal = FloatArray(nb)
        val amps = doubleArrayOf(0.6, 1.0, 0.8, 0.65, 0.5, 0.4, 0.32, 0.26)
        val phases = DoubleArray(9)
        for (i in 0 until nb) {
            val t = i.toDouble() / taux
            var v = 0.0
            for (n in partiels) {
                phases[n] += 2 * Math.PI * freqInstantanee(n, t) / taux
                v += amps[n - 1] * kotlin.math.sin(phases[n] + n * 1.7)
            }
            signal[i] = (v * 0.22).toFloat()
        }
        return signal
    }

    /** Pousse un signal par blocs de 100 ms en sondant l'état
     *  entre les blocs, comme la boucle d'affichage du parcours
     *  (c'est ce sondage qui déclenche les fixations). */
    private fun pousser(analyseur: AnalyseurPartiels, signal: FloatArray, taux: Int) {
        val bloc = taux / 10
        var pos = 0
        while (pos < signal.size) {
            val fin = minOf(pos + bloc, signal.size)
            analyseur.push(signal.copyOfRange(pos, fin))
            analyseur.etatPartiels()
            pos = fin
        }
    }

    /**
     * Règles 0.7 nº 1 et 2, sur l'exemple de Grégoire (des
     * lectures qui oscillent sans conclure → le logiciel
     * décide) :
     *
     * Phase 1 — le partiel 3 d'un do médium est agité
     * pendant 2,2 s (il alterne +6 ¢ / −6 ¢ toutes les
     * 0,55 s : l'attaque instable qui pollue l'historique —
     * la dispersion globale ne passera jamais sous le seuil
     * strict, et aucune fixation précoce n'est possible,
     * les estimations précoces étant elles-mêmes dispersées),
     * puis il se pose sur sa vraie fréquence avec une
     * micro-oscillation de ±0,3 ¢. La fenêtre récente, elle,
     * devient propre : après le délai de 2,5 s, le logiciel
     * doit FIXER le partiel 3 par décision (MOYENNE), à sa
     * médiane récente — donc à moins de 0,75 ¢ de sa vraie
     * fréquence.
     *
     * Phase 2 — le partiel 3 saute de +10 ¢ : la valeur fixée
     * ne doit PAS bouger d'un iota (règle nº 1 : un partiel
     * fixé ne revient jamais en mesure pendant la mesure
     * initiale).
     */
    @Test
    fun analyseur_partielOscillant_fixationParMoyenne_puisImmuable() {
        val taux = 48000
        val cle = 28
        val f1 = 440.0 * 2.0.pow((cle - 49) / 12.0)
        val b = 0.0006
        fun fJeune(n: Int) = n * f1 * sqrt(1 + b * n * n)
        val analyseur = AnalyseurPartiels(taux, cle, f1)
        val phase1 = genererSignal(taux, 7.5, (1..8).toSet()) { n, t ->
            when {
                n == 3 && t < 2.2 -> {
                    val carre = if ((t / 0.55).toInt() % 2 == 0) 6.0 else -6.0
                    fJeune(3) * 2.0.pow(carre / 1200)
                }
                n == 3 -> fJeune(3) * 2.0.pow(
                    0.3 * kotlin.math.sin(2 * Math.PI * 0.7 * t) / 1200
                )
                else -> fJeune(n)
            }
        }
        pousser(analyseur, phase1, taux)
        val etat3 = analyseur.etatPartiels().first { it.n == 3 }
        assertEquals(
            "partiel 3 : fixation par décision (moyenne) attendue, " +
                "obtenu ${etat3.fixation}",
            FixationPartiel.MOYENNE, etat3.fixation
        )
        val ecartFixe = 1200 * log2(etat3.freqHz!! / fJeune(3))
        assertTrue(
            "valeur fixée du partiel 3 à $ecartFixe ¢ de sa vraie " +
                "fréquence (toléré ±0,75 ¢)",
            abs(ecartFixe) <= 0.75
        )
        val freqFixee = etat3.freqHz

        val phase2 = genererSignal(taux, 2.0, (1..8).toSet()) { n, _ ->
            if (n == 3) fJeune(3) * 2.0.pow(10.0 / 1200) else fJeune(n)
        }
        pousser(analyseur, phase2, taux)
        val etat3Apres = analyseur.etatPartiels().first { it.n == 3 }
        assertEquals(
            "valeur fixée immuable malgré le saut de +10 ¢",
            freqFixee, etat3Apres.freqHz
        )
        assertEquals(FixationPartiel.MOYENNE, etat3Apres.fixation)
    }

    /**
     * Règle 0.7 nº 3 : le partiel 1 n'est jamais requis. Une
     * ancre dont le signal ne contient QUE les partiels 2 à 8
     * (le fondamental est absent — le cas réel de C1, où le
     * 33 Hz est quasi inmesurable) doit conclure Pret, avec
     * un B retrouvé juste, et sans aucun partiel 1 dans la
     * mesure retenue.
     */
    @Test
    fun analyseur_sansPartiel1_conclut() {
        val taux = 48000
        val cle = 28
        val f1 = 440.0 * 2.0.pow((cle - 49) / 12.0)
        val b = 0.0006
        val analyseur = AnalyseurPartiels(taux, cle, f1)
        val signal = genererSignal(taux, 3.5, (2..8).toSet()) { n, _ ->
            n * f1 * sqrt(1 + b * n * n)
        }
        pousser(analyseur, signal, taux)
        when (val verdict = analyseur.evaluer("Do3")) {
            is AnalyseurPartiels.Verdict.Pret -> {
                assertTrue(
                    "aucun partiel 1 dans la mesure retenue",
                    verdict.mesure.partiels.none { it.n == 1 }
                )
                assertTrue(
                    "≥ 4 partiels retenus (obtenu " +
                        "${verdict.mesure.partiels.size})",
                    verdict.mesure.partiels.size >= 4
                )
                assertTrue(
                    "B retrouvé ${verdict.mesure.b} (vrai $b)",
                    abs(verdict.mesure.b - b) <= maxOf(0.15 * b, 0.00005)
                )
            }
            is AnalyseurPartiels.Verdict.Faible ->
                fail(
                    "ancre propre sans partiel 1 : Faible inattendu — " +
                        verdict.raisons.joinToString(" ; ")
                )
            is AnalyseurPartiels.Verdict.Insuffisant ->
                fail(
                    "ancre propre sans partiel 1 : Insuffisant " +
                        "inattendu — " +
                        verdict.raisons.joinToString(" ; ")
                )
        }
    }
}
