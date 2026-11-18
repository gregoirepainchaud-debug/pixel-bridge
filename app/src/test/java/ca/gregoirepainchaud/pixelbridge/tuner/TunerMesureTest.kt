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
 * Preuve du calcul d'accord sur mesure (version 0.5, schéma
 * d'ancres refait en 0.9) — UN cas synthétique complet, dans
 * l'esprit de TunerDspTest (même PRNG déterministe) :
 *
 *   on fabrique les 10 ancres la-do (de La1 à Do6) d'un piano
 *   fictif dont la courbe d'inharmonicité B(k) est CONNUE (en
 *   U : forte dans les graves, creuse au centre, remontante
 *   dans les aigus — la forme réelle des pianos), on génère
 *   leurs partiels 1 à 8 par la formule de Young avec un bruit
 *   de mesure uniforme de ±0,9 ¢, puis on vérifie que la
 *   chaîne du build — ajusterInharmonicite →
 *   courbeInharmonicite → genererTableAccord — retrouve les B
 *   des ancres et produit une table saine. Sous La1, la courbe
 *   est extrapolée en PENTE de log B (0.9) : l'étirement des
 *   basses ne doit pas devenir timide pour autant.
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
        // Les ancres du parcours sont bien les 10 la-do annoncés
        // (schéma 0.9 : de La1 à Do6).
        assertEquals(
            listOf(13, 16, 25, 28, 37, 40, 49, 52, 61, 64),
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
        // Extrapolations (0.9) : sous La1 (touche 13), la pente
        // de log B est prolongée — B continue de CROÎTRE en
        // descendant vers la touche 1, sans dépasser le plafond
        // physique ; au-dessus de Do6 (touche 64), plat.
        assertTrue(
            "extrapolation en pente : B(1) = ${courbe[1]} doit " +
                "dépasser nettement B(13) = ${courbe[13]}",
            courbe[1] > 1.2 * courbe[13]
        )
        assertTrue(
            "B(1) = ${courbe[1]} sous le plafond physique",
            courbe[1] <= 0.008
        )
        assertEquals(
            "extrapolation plate au-dessus de Do6",
            courbe[64], courbe[88], 1e-12
        )
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
     * 0.6) : la première ancre du parcours d'alors, C1 (touche
     * 4, ≈ 32,7 Hz), a fait chasser l'analyseur quelques
     * secondes puis l'app est morte (accès concurrents aux
     * listes de l'analyseur entre le fil de capture et le
     * sondage d'affichage, critères « propre » inatteignables
     * dans les graves profonds). C1 n'est plus une ancre du
     * parcours depuis le schéma 0.9 (qui commence à La1), mais
     * le test reste comme preuve de robustesse du grave
     * profond — et il traverse maintenant les portes 0.9
     * (frappe + hauteur) : un C1 synthétique propre (partiels
     * de Young, B = 0,0012) poussé par blocs de 100 ms avec un
     * sondage etatPartiels() entre les blocs, comme sur le
     * terrain — l'analyseur doit ouvrir sa porte, CONCLURE
     * (Pret ou Faible), jamais rester Insuffisant, et ne
     * jamais lever.
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
     * décide) — modèle refait en 0.9.
     *
     * Le modèle d'attaque en créneau de la 0.7 est retiré :
     * la porte de frappe (0.9) absorbe l'attaque, et le
     * traqueur intègre les oscillations rapides en une
     * lecture stable (vérifié : carré, balancement, dérive
     * lente — tout finit « propre » sur signal synthétique,
     * et c'est une BONNE mesure à chaque fois). Le modèle
     * fidèle de l'oscillation réelle est le BATTEMENT
     * D'UNISSON : deux cordes à ±6 ¢ l'une de l'autre, dont
     * le pic combiné vagabonde — la dispersion des
     * estimations dépasse alors durablement le seuil strict.
     *
     * Phase A (3,5 s de battement) : le partiel 3 ne doit
     * PAS être fixé — ni propre (dispersion trop grande),
     * ni par décision (la fenêtre récente oscille autant
     * que l'historique).
     *
     * Phase B (l'unisson se pose, ton unique à la vraie
     * fréquence) : le partiel doit être DÉCIDÉ — fixé, à
     * moins de 3 ¢ de sa vraie fréquence (le centroïde du
     * battement laisse un biais de l'ordre de 1 à 2 ¢) — et
     * l'ancre conclut. Sur l'appareil, quand la dispersion
     * réelle (chaîne audio du téléphone, salle, cordes
     * vraies) persiste dans l'historique alors que la
     * fenêtre récente s'est posée, c'est la décision par
     * moyenne (règle nº 2, logique inchangée depuis la 0.7)
     * qui tranche ; sur signal synthétique, la dilution de
     * l'historique fait généralement conclure « propre »
     * d'abord — les deux issues satisfont la règle.
     *
     * Phase C — le partiel 3 saute de +10 ¢ : la valeur
     * fixée ne doit PAS bouger d'un iota (règle nº 1 : un
     * partiel fixé ne revient jamais en mesure pendant la
     * mesure initiale).
     */
    @Test
    fun analyseur_partielBattant_pasDeFixation_puisDecisionEtImmuable() {
        val taux = 48000
        val cle = 28
        val f1 = 440.0 * 2.0.pow((cle - 49) / 12.0)
        val b = 0.0006
        fun fJeune(n: Int) = n * f1 * sqrt(1 + b * n * n)
        val analyseur = AnalyseurPartiels(taux, cle, f1)
        val amps = doubleArrayOf(0.6, 1.0, 0.8, 0.65, 0.5, 0.4, 0.32, 0.26)

        // Signal construit à la main (deux oscillateurs pour
        // le partiel 3 pendant le battement) + un souffle de
        // bruit déterministe (LCG) pour la matière.
        fun construire(duree: Double, battement: Boolean): FloatArray {
            val nb = (taux * duree).toInt()
            val signal = FloatArray(nb)
            val phases = DoubleArray(10)
            var lcg = 987654321L
            fun bruit(): Double {
                lcg = (lcg * 1103515245L + 12345L) and 0x7fffffffL
                return (lcg.toDouble() / 0x7fffffffL) * 2 - 1
            }
            val fBas = fJeune(3) * 2.0.pow(-6.0 / 1200)
            val fHaut = fJeune(3) * 2.0.pow(6.0 / 1200)
            for (i in 0 until nb) {
                var v = 0.0
                for (n in 1..8) {
                    if (n == 3) continue
                    phases[n] += 2 * Math.PI * fJeune(n) / taux
                    v += amps[n - 1] * kotlin.math.sin(phases[n] + n * 1.7)
                }
                if (battement) {
                    phases[3] += 2 * Math.PI * fBas / taux
                    phases[9] += 2 * Math.PI * fHaut / taux
                    v += 0.45 * kotlin.math.sin(phases[3] + 3 * 1.7) +
                        0.45 * kotlin.math.sin(phases[9] + 0.7)
                } else {
                    phases[3] += 2 * Math.PI * fJeune(3) / taux
                    v += amps[2] * kotlin.math.sin(phases[3] + 3 * 1.7)
                }
                v += bruit() * 0.03
                signal[i] = (v * 0.22).toFloat()
            }
            return signal
        }

        // Phase A : battement — aucune fixation du partiel 3.
        pousser(analyseur, construire(3.5, battement = true), taux)
        val etatA = analyseur.etatPartiels().first { it.n == 3 }
        assertEquals(
            "pendant le battement, le partiel 3 ne doit pas " +
                "être fixé (fixation = ${etatA.fixation}, " +
                "dispersion = ${etatA.dispersionCents} ¢, " +
                "estimations = ${etatA.nbEstimations})",
            FixationPartiel.AUCUNE, etatA.fixation
        )
        assertTrue(
            "des estimations ont bien été accumulées pendant " +
                "le battement (${etatA.nbEstimations})",
            etatA.nbEstimations >= 10
        )

        // Phase B : l'unisson se pose — décision attendue.
        pousser(analyseur, construire(6.0, battement = false), taux)
        val etatB = analyseur.etatPartiels().first { it.n == 3 }
        assertTrue(
            "après la pose, le partiel 3 est fixé " +
                "(fixation = ${etatB.fixation})",
            etatB.fixation != FixationPartiel.AUCUNE
        )
        val ecartFixe = 1200 * log2(etatB.freqHz!! / fJeune(3))
        assertTrue(
            "valeur fixée du partiel 3 à $ecartFixe ¢ de sa " +
                "vraie fréquence (toléré ±3 ¢ : biais du " +
                "centroïde de battement)",
            abs(ecartFixe) <= 3.0
        )
        val freqFixee = etatB.freqHz
        when (val verdict = analyseur.evaluer("Do3")) {
            is AnalyseurPartiels.Verdict.Pret,
            is AnalyseurPartiels.Verdict.Faible -> Unit
            is AnalyseurPartiels.Verdict.Insuffisant ->
                fail("l'ancre doit conclure : ${verdict.raisons}")
        }

        // Phase C : saut de +10 ¢ — immuabilité.
        val phaseC = genererSignal(taux, 2.0, (1..8).toSet()) { n, _ ->
            if (n == 3) fJeune(3) * 2.0.pow(10.0 / 1200) else fJeune(n)
        }
        pousser(analyseur, phaseC, taux)
        val etatC = analyseur.etatPartiels().first { it.n == 3 }
        assertEquals(
            "valeur fixée immuable malgré le saut de +10 ¢",
            freqFixee, etatC.freqHz
        )
        assertEquals(etatB.fixation, etatC.fixation)
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

    // -----------------------------------------------------------------
    // Correctifs 0.9 — portes, cohérence, schéma la-do.
    // -----------------------------------------------------------------

    /** Fabrique une ancre minimale pour les fonctions pures. */
    private fun ancrePure(cle: Int): MesureAncre =
        MesureAncre(cle, "A$cle", 100.0, 0.0008, 0.0, emptyList())

    /**
     * Porte de cohérence (0.9) — le cas VÉCU du Sol2 (0.7) :
     * les partiels 1 à 4 sont ceux du Sol2 (piano à −9 ¢,
     * B = 0,0005), mais les partiels 5, 7 et 8 du signal sont
     * aux fréquences photographiées ce jour-là (521,6 /
     * 653,6 / 719,8 Hz — les partiels 8, 10 et 11 du Do2
     * précédent, encore en train de sonner), et le 6 à
     * 587,6 Hz. Les vrais partiels 5, 7 et 8 du Sol2 sont
     * ABSENTS du signal : le traqueur n'a que le mauvais pic
     * à se mettre sous la dent. La porte doit refuser de les
     * fixer là (rejets ≥ 1), et l'ancre doit néanmoins
     * conclure sur ses partiels sains.
     */
    @Test
    fun analyseur_serieContaminee_porteCoherence() {
        val taux = 48000
        val cle = 23 // Sol2
        val f1Nominale = 440.0 * 2.0.pow((cle - 49) / 12.0)
        val f1Reel = f1Nominale * 2.0.pow(-9.0 / 1200)
        val b = 0.0005
        fun fJeune(n: Int) = n * f1Reel * sqrt(1 + b * n * n)
        val analyseur = AnalyseurPartiels(taux, cle, f1Nominale)
        val signal = genererSignal(taux, 6.0, (1..8).toSet()) { n, _ ->
            when (n) {
                5 -> 521.6
                6 -> 587.6
                7 -> 653.6
                8 -> 719.8
                else -> fJeune(n)
            }
        }
        pousser(analyseur, signal, taux)
        assertTrue(
            "la porte de cohérence a refusé au moins une " +
                "fixation (rejets = ${analyseur.rejetsCoherence})",
            analyseur.rejetsCoherence >= 1
        )
        val etats = analyseur.etatPartiels()
        for (n in listOf(5, 7, 8)) {
            val e = etats.first { it.n == n }
            assertEquals(
                "partiel $n : ne doit pas être fixé sur le " +
                    "mauvais pic (fixation = ${e.fixation}, " +
                    "freq = ${e.freqHz})",
                FixationPartiel.AUCUNE, e.fixation
            )
        }
        when (val verdict = analyseur.evaluer("Sol2")) {
            is AnalyseurPartiels.Verdict.Pret,
            is AnalyseurPartiels.Verdict.Faible -> {
                val mesure = when (verdict) {
                    is AnalyseurPartiels.Verdict.Pret -> verdict.mesure
                    is AnalyseurPartiels.Verdict.Faible -> verdict.mesure
                    else -> error("impossible")
                }
                assertTrue(
                    "aucun partiel contaminé (5, 7, 8) dans la " +
                        "mesure retenue",
                    mesure.partiels.none { it.n in listOf(5, 7, 8) }
                )
                assertTrue(
                    "B plausible : ${mesure.b} (vrai $b)",
                    mesure.b in 0.0001..0.003
                )
            }
            is AnalyseurPartiels.Verdict.Insuffisant ->
                fail(
                    "l'ancre doit conclure sur ses partiels " +
                        "sains : ${verdict.raisons}"
                )
        }
    }

    /**
     * Portes de frappe et de hauteur (0.9) — le cas VÉCU du
     * Sol1 (0.7) qui « concluait » sur la queue du Do1 :
     *
     * Phase 1 (2,5 s) : seul La1 sonne, en décroissance —
     * l'analyseur de Do2 ne doit RIEN accumuler (porte
     * fermée, aucune estimation), et il doit pouvoir NOMMER
     * ce qu'il entend (cleEntendue = 13, La1).
     *
     * Phase 2 (4 s) : Do2 est frappé franchement par-dessus
     * la queue — la porte s'ouvre (frappe fraîche + bonne
     * hauteur) et l'ancre conclut juste.
     */
    @Test
    fun analyseur_queuePrecedente_rienAvantFrappe_puisMesure() {
        val taux = 48000
        val cle = 16 // Do2
        val f1Nominale = 440.0 * 2.0.pow((cle - 49) / 12.0)
        val analyseur = AnalyseurPartiels(taux, cle, f1Nominale)
        val amps = doubleArrayOf(0.6, 1.0, 0.8, 0.65, 0.5, 0.4, 0.32, 0.26)

        // Segment 1 : La1 (55 Hz, B = 0,0008) en décroissance
        // exponentielle (τ ≈ 1,1 s) — la queue d'une ancre
        // précédente encore bien audible.
        val duree1 = 2.5
        val seg1 = FloatArray((taux * duree1).toInt())
        val phases1 = DoubleArray(9)
        for (i in seg1.indices) {
            val t = i.toDouble() / taux
            var v = 0.0
            for (n in 1..8) {
                val f = n * 55.0 * sqrt(1 + 0.0008 * n * n)
                phases1[n] += 2 * Math.PI * f / taux
                v += amps[n - 1] * kotlin.math.sin(phases1[n] + n * 1.7) *
                    kotlin.math.exp(-t / 1.1)
            }
            seg1[i] = (v * 0.22).toFloat()
        }
        pousser(analyseur, seg1, taux)
        assertTrue(
            "porte fermée tant que seule la note précédente sonne",
            !analyseur.porteOuverte
        )
        assertTrue(
            "aucune estimation accumulée avant la frappe",
            analyseur.etatPartiels().all { it.nbEstimations == 0 }
        )
        assertEquals(
            "la note entendue pendant l'attente est La1 (13)",
            13, analyseur.cleEntendue
        )

        // Segment 2 : Do2 frappé (B = 0,0007), pleine force.
        val seg2 = genererSignal(taux, 4.0, (1..8).toSet()) { n, _ ->
            n * f1Nominale * sqrt(1 + 0.0007 * n * n)
        }
        pousser(analyseur, seg2, taux)
        assertTrue(
            "porte ouverte après la frappe de la bonne note",
            analyseur.porteOuverte
        )
        when (val verdict = analyseur.evaluer("Do2")) {
            is AnalyseurPartiels.Verdict.Pret,
            is AnalyseurPartiels.Verdict.Faible -> {
                val mesure = when (verdict) {
                    is AnalyseurPartiels.Verdict.Pret -> verdict.mesure
                    is AnalyseurPartiels.Verdict.Faible -> verdict.mesure
                    else -> error("impossible")
                }
                assertTrue(
                    "B retrouvé ${mesure.b} (vrai 0,0007)",
                    mesure.b in 0.0002..0.002
                )
            }
            is AnalyseurPartiels.Verdict.Insuffisant ->
                fail(
                    "Do2 frappé doit conclure : ${verdict.raisons}"
                )
        }
    }

    /**
     * Porte de hauteur, tolérance (0.9) : un piano très
     * désaccordé (−45 ¢ partout) qui joue la BONNE touche est
     * accepté — la comparaison se fait en note la plus
     * proche, pas en cents serrés — et l'écart mesuré
     * reflète les −45 ¢.
     */
    @Test
    fun analyseur_bonneNoteDesaccordee_acceptee() {
        val taux = 48000
        val cle = 25 // La2
        val f1Nominale = 440.0 * 2.0.pow((cle - 49) / 12.0)
        val f1Reel = f1Nominale * 2.0.pow(-45.0 / 1200)
        val b = 0.0005
        val analyseur = AnalyseurPartiels(taux, cle, f1Nominale)
        val signal = genererSignal(taux, 4.5, (1..8).toSet()) { n, _ ->
            n * f1Reel * sqrt(1 + b * n * n)
        }
        pousser(analyseur, signal, taux)
        assertTrue(
            "porte ouverte pour la bonne note désaccordée",
            analyseur.porteOuverte
        )
        when (val verdict = analyseur.evaluer("La2")) {
            is AnalyseurPartiels.Verdict.Pret -> {
                assertTrue(
                    "écart mesuré ${verdict.mesure.ecartCents} ¢ " +
                        "(attendu ≈ −45 ¢)",
                    verdict.mesure.ecartCents in -55.0..-35.0
                )
            }
            is AnalyseurPartiels.Verdict.Faible ->
                fail(
                    "un piano propre à −45 ¢ doit conclure " +
                        "Pret : ${verdict.raisons}"
                )
            is AnalyseurPartiels.Verdict.Insuffisant ->
                fail(
                    "un piano propre à −45 ¢ ne doit pas être " +
                        "insuffisant : ${verdict.raisons}"
                )
        }
    }

    /**
     * Partiels visés selon la hauteur (0.9) : seuls les
     * partiels sous ~14 kHz sont cherchés. Do6 (l'ancre la
     * plus haute du parcours) vise encore 1 à 8 ; Do7 ne
     * viserait que 1 à 6 ; Do8, 1 à 3.
     */
    @Test
    fun analyseur_partielsVises_selonHauteur() {
        fun f1(cle: Int) = 440.0 * 2.0.pow((cle - 49) / 12.0)
        assertEquals(
            (1..8).toList(),
            AnalyseurPartiels(48000, 64, f1(64)).partielsVises
        )
        assertEquals(
            (1..6).toList(),
            AnalyseurPartiels(48000, 76, f1(76)).partielsVises
        )
        assertEquals(
            listOf(1, 2, 3),
            AnalyseurPartiels(48000, 88, f1(88)).partielsVises
        )
    }

    /**
     * Diagnostic d'aberrants (0.9) sur la série photographiée
     * du Sol2 de la 0.7 : le retrait unitaire doit désigner
     * un des partiels accrochés à la série du Do2 (5, 7 ou 8),
     * et la série restante doit s'ajuster sainement.
     */
    @Test
    fun diagnostiquerPartielsAberrants_casVecuSol2() {
        val vecue = listOf(
            PartielMesure(1, 99.7, 0.42),
            PartielMesure(2, 195.0, 1.0),
            PartielMesure(3, 292.9, 0.97),
            PartielMesure(4, 390.8, 1.0),
            PartielMesure(5, 521.6, 1.0),
            PartielMesure(6, 587.6, 1.0),
            PartielMesure(7, 653.6, 1.0),
            PartielMesure(8, 719.8, 1.0)
        )
        val aberrants = diagnostiquerPartielsAberrants(vecue)
        assertTrue(
            "au moins un aberrant nommé (obtenu $aberrants)",
            aberrants.isNotEmpty()
        )
        assertTrue(
            "les aberrants sont parmi les partiels accrochés " +
                "(5, 7, 8) : $aberrants",
            aberrants.all { it in listOf(5, 7, 8) }
        )
        // Le noyau sain de la série (2, 3, 4, 6 — les partiels
        // qui tenaient ensemble ce jour-là) s'ajuste, lui,
        // proprement : c'est l'existence de ce noyau qui rend
        // les accrochés nommables. (Le partiel 1 vécu, à
        // 99,7 Hz pour un Sol2 dont le partiel 2 dit
        // f1 = 97,5 Hz, empoisonne à lui seul la régression
        // des moindres carrés — c'est pour ça que le
        // diagnostic passe par une graine robuste, pas par
        // l'ajustement brut.)
        val noyau = vecue.filter { it.n in listOf(2, 3, 4, 6) }
        val fit = ajusterInharmonicite(noyau)
        assertTrue("le noyau sain s'ajuste", fit != null)
        assertTrue(
            "RMS du noyau sain : ${fit!!.rmsCents} ¢",
            fit.rmsCents <= 2.5
        )
    }

    /**
     * Déverrouillage ciblé (0.9) : un partiel déverrouillé
     * repart en mesure (fixation effacée, estimations
     * vidées), les autres restent figés — puis il peut se
     * refixer proprement quand la note rejoue.
     */
    @Test
    fun analyseur_defixerPartiels_reprendLaMesure() {
        val taux = 48000
        val cle = 16 // Do2
        val f1 = 440.0 * 2.0.pow((cle - 49) / 12.0)
        val b = 0.0007
        val analyseur = AnalyseurPartiels(taux, cle, f1)
        val signal = genererSignal(taux, 3.5, (1..8).toSet()) { n, _ ->
            n * f1 * sqrt(1 + b * n * n)
        }
        pousser(analyseur, signal, taux)
        val fixesAvant = analyseur.etatPartiels()
            .filter { it.fixation != FixationPartiel.AUCUNE }
            .map { it.n }
        assertTrue(
            "au moins 3 partiels fixés avant déverrouillage " +
                "(obtenu $fixesAvant)",
            fixesAvant.size >= 3
        )
        val cible = fixesAvant.first()
        analyseur.defixerPartiels(listOf(cible))
        val etatCible = analyseur.etatPartiels().first { it.n == cible }
        assertEquals(
            "le partiel déverrouillé repart en mesure",
            FixationPartiel.AUCUNE, etatCible.fixation
        )
        assertEquals(
            "ses estimations sont vidées",
            0, etatCible.nbEstimations
        )
        assertTrue(
            "les autres partiels restent fixés",
            analyseur.etatPartiels()
                .filter { it.n != cible }
                .count { it.fixation != FixationPartiel.AUCUNE } ==
                fixesAvant.size - 1
        )
        pousser(analyseur, signal, taux)
        val refixe = analyseur.etatPartiels().first { it.n == cible }
        assertTrue(
            "le partiel déverrouillé se refixe quand la note " +
                "rejoue (fixation = ${refixe.fixation})",
            refixe.fixation != FixationPartiel.AUCUNE
        )
    }

    /**
     * Brouillons d'ancien schéma (0.9) : un brouillon do-sol
     * (touches 4, 23, 71…) est incompatible et sera clos ; un
     * brouillon la-do — ou limité aux touches communes aux
     * deux schémas (16, 28, 40…) — reste valable ; un
     * brouillon sans ancre (fiche seule) est compatible.
     */
    @Test
    fun brouillonCompatible_ancienEtNouveauSchema() {
        assertTrue(
            !brouillonCompatible(listOf(ancrePure(4), ancrePure(23)))
        )
        assertTrue(
            !brouillonCompatible(listOf(ancrePure(13), ancrePure(71)))
        )
        assertTrue(
            brouillonCompatible(
                listOf(ancrePure(13), ancrePure(16), ancrePure(64))
            )
        )
        assertTrue(brouillonCompatible(listOf(ancrePure(16))))
        assertTrue(brouillonCompatible(emptyList()))
    }

    /**
     * Règle de génération (0.9) : ≥ 7 ancres sur 10 ET une
     * couverture des deux bouts du clavier.
     */
    @Test
    fun raisonsGenerationImpossible_couverture() {
        val complet = listOf(13, 16, 25, 37, 49, 61, 64)
            .map { ancrePure(it) }
        assertTrue(raisonsGenerationImpossible(complet).isEmpty())

        val sansGraves = listOf(37, 40, 49, 52, 61, 64, 28 + 12)
            .map { ancrePure(it) }
        assertTrue(
            "7 ancres sans grave → raison « graves »",
            raisonsGenerationImpossible(sansGraves)
                .any { it.contains("graves") }
        )

        val sansAigus = listOf(13, 16, 25, 28, 37, 40, 49)
            .map { ancrePure(it) }
        assertTrue(
            "7 ancres sans aigu → raison « aigus »",
            raisonsGenerationImpossible(sansAigus)
                .any { it.contains("aigus") }
        )

        val tropPeu = listOf(13, 28, 49, 64).map { ancrePure(it) }
        val raisons = raisonsGenerationImpossible(tropPeu)
        assertEquals(
            "4 ancres couvrantes → seule la raison du nombre",
            1, raisons.size
        )
        assertTrue(raisons[0].contains("au moins"))
    }
}
