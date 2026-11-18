package ca.gregoirepainchaud.pixelbridge.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Preuve du port Kotlin du DSP du tuner — UN cas synthétique combiné,
 * le plus représentatif des 16 cas du prototype (allègement de
 * Grégoire, 2026-11-18) : il exerce à la fois la détection de note,
 * le verrouillage sur le partiel de référence et le verdict en
 * cents, dans le flux réel du moteur (celui du worklet) :
 *
 *   ton « piano » de la note 25 (La2, table réelle du Bösendorfer
 *   SR3 de tunings.json), décalé de −7,5 ¢ → le NoteDetector doit
 *   adopter la clé 25, le PartialTracker se verrouille sur son
 *   partiel de référence (nº 5, 549,243 Hz) et la médiane des cents
 *   mesurés doit tomber à ±0,3 ¢ de −7,5 (dispersion p10–p90
 *   ≤ 1,2 ¢) — les critères mêmes de test-dsp.js.
 *
 * Verdicts du moteur JS sur ce cas exact (jumeau Node de ce test,
 * dsp.js du prototype) : note 25 adoptée à 0,51 s ; médiane
 * −7,499 ¢ (erreur +0,001) ; dispersion 0,001 ; détecteur
 * rel=767,3, marge=1,55.
 *
 * Le générateur de signal est repris tel quel de test-dsp.js
 * (genTone) : même PRNG déterministe (LCG), mêmes phases, même
 * bruit — les échantillons sont identiques à ceux du cas JS.
 */
class TunerDspTest {

    companion object {
        private const val SR = 48000
    }

    /** PRNG de test-dsp.js : s = (s * 1664525 + 1013904223) >>> 0. */
    private class Prng(seed: Int) {
        private var s: Int = seed
        fun rnd(): Double {
            s = s * 1664525 + 1013904223
            return (s.toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    /** genTone de test-dsp.js, repris tel quel. */
    private fun genTone(
        f0: Double,
        b: Double,
        cents: Double = 0.0,
        fundScale: Double = 1.0,
        dur: Double = 2.2,
        tau: Double = 1.8,
        noiseDb: Double = -42.0,
        amp: Double = 0.35,
        seed: Int = 11
    ): FloatArray {
        val n = (SR * dur).toInt()
        val out = FloatArray(n)
        val prng = Prng(seed)
        val ratio = 2.0.pow(cents / 1200)
        val phases = DoubleArray(17)
        for (m in 1..16) phases[m] = prng.rnd() * 2 * Math.PI
        val noiseAmp = 10.0.pow(noiseDb / 20)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            var v = 0.0
            for (m in 1..16) {
                val f = m * f0 * sqrt(1 + b * m * m) * ratio
                if (f > SR * 0.48) break
                v += (0.55 / m.toDouble().pow(0.9)) *
                    (if (m == 1) fundScale else 1.0) *
                    sin(2 * Math.PI * f * t + phases[m])
            }
            v *= exp(-t / tau) * amp
            v += (prng.rnd() * 2 - 1) * noiseAmp * amp
            out[i] = v.toFloat()
        }
        return out
    }

    /** bFor de test-dsp.js : B déduit des données de la note. */
    private fun bFor(note: NoteBrute): Double {
        val l = note.listenPartial
        if (l <= 1) return 0.0002
        val r = note.partialTargetHz / (l * note.fundamentalHz)
        val b = (r * r - 1) / (l * l)
        return if (b > 0 && b < 0.01) b else 0.0002
    }

    /**
     * Table des 88 notes du premier accord de tunings.json (le
     * Bösendorfer SR3), extraite du fichier réel (ressource de
     * test, copie octet pour octet de l'asset de l'app) — sans
     * org.json, absent des tests JVM.
     */
    private fun chargerTable(): List<NoteBrute> {
        val json = javaClass.getResourceAsStream("/tunings.json")!!
            .readBytes().decodeToString()
        val motif = Regex(
            """"key": (\d+),\s*"midi": \d+,\s*"name": "[^"]*",\s*""" +
                """"fundamentalCents": [-\d.]+,\s*""" +
                """"fundamentalHz440": ([\d.eE+-]+),\s*""" +
                """"listenPartial": (\d+),\s*""" +
                """"partialTargetHz440": ([\d.eE+-]+)"""
        )
        val trouvailles = motif.findAll(json).take(88).toList()
        assertEquals("88 notes attendues pour le premier accord", 88, trouvailles.size)
        return trouvailles.mapIndexed { idx, m ->
            val note = NoteBrute(
                key = m.groupValues[1].toInt(),
                fundamentalHz = m.groupValues[2].toDouble(),
                partialTargetHz = m.groupValues[4].toDouble(),
                listenPartial = m.groupValues[3].toInt()
            )
            assertEquals("notes en ordre de clé", idx + 1, note.key)
            note
        }
    }

    @Test
    fun casCombine_detectionVerrouillageCents_la2Moins7_5() {
        val table = chargerTable()
        val note25 = table[24]
        assertEquals(25, note25.key)
        val centsAttendus = -7.5
        val samples = genTone(
            f0 = note25.fundamentalHz,
            b = bFor(note25),
            cents = centsAttendus
        )

        // Flux du moteur (chemin worklet) : mode auto, poussées de
        // 128 échantillons comme les quanta du processeur.
        val moteur = TunerEngine(SR)
        moteur.config("auto", 49, "test@440", table)
        val centsSeries = mutableListOf<Double>()
        var fed = 0
        var i = 0
        while (i < samples.size) {
            val len = minOf(128, samples.size - i)
            moteur.push(samples.copyOfRange(i, i + len))
            fed += len
            val t = fed.toDouble() / SR
            val latest = moteur.tracker.latest
            if (latest != null && t >= 1.0) centsSeries.add(latest.cents)
            i += len
        }

        // 1) La détection adopte la bonne note et le traqueur s'y
        //    verrouille.
        assertEquals(
            "note adoptée par le détecteur", 25, moteur.detector.adoptedKey
        )
        assertEquals(
            "clé active du traqueur", 25, moteur.activeKey
        )
        // 2) Le verdict en cents : médiane à ±0,3 ¢ de l'écart
        //    connu, dispersion p10–p90 ≤ 1,2 ¢ (critères de
        //    test-dsp.js pour le chemin avec raffinement de phase).
        assertTrue("des mesures doivent exister", centsSeries.isNotEmpty())
        val triees = centsSeries.sorted()
        val med = triees[triees.size / 2]
        val p10 = triees[(triees.size * 0.1).toInt()]
        val p90 = triees[(triees.size * 0.9).toInt()]
        assertTrue(
            "médiane $med ¢ vs attendu $centsAttendus ¢ " +
                "(erreur ${med - centsAttendus} ¢)",
            abs(med - centsAttendus) <= 0.3
        )
        assertTrue(
            "dispersion p10-p90 ${p90 - p10} ¢ > 1,2 ¢",
            p90 - p10 <= 1.2
        )
    }

    // ------------------------------------------------------------------
    // Porte de continuité et transitoire d'attaque (0.12)
    // ------------------------------------------------------------------

    /**
     * Série de partiels (Young, phases accumulées) dont la hauteur
     * suit [decalageCents] (t) — glissement continu, transitoire de
     * frappe — sous l'enveloppe [amp] (t), plus un bruit blanc
     * constant de niveau [noiseDb] relatif à 0,35 (même convention
     * que genTone). Générateur des trois tests ci-dessous.
     */
    private fun genGlide(
        f0: Double,
        b: Double,
        dur: Double,
        decalageCents: (Double) -> Double,
        amp: (Double) -> Double,
        noiseDb: Double = -42.0,
        seed: Int = 11
    ): FloatArray {
        val n = (SR * dur).toInt()
        val out = FloatArray(n)
        val prng = Prng(seed)
        val noiseAmp = 10.0.pow(noiseDb / 20) * 0.35
        val phases = DoubleArray(17)
        for (m in 1..16) phases[m] = prng.rnd() * 2 * Math.PI
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val ratio = 2.0.pow(decalageCents(t) / 1200)
            var v = 0.0
            for (m in 1..10) {
                val f = m * f0 * sqrt(1 + b * m * m) * ratio
                phases[m] += 2 * Math.PI * f / SR
                v += (0.55 / m.toDouble().pow(0.9)) * sin(phases[m])
            }
            v *= amp(t)
            v += (prng.rnd() * 2 - 1) * noiseAmp
            out[i] = v.toFloat()
        }
        return out
    }

    /** Pousse par tranches de 4096 échantillons (une analyse du
     * traqueur par tranche) en échantillonnant la confiance de
     * trame après chaque tranche. */
    private fun pousserParTranches(
        tracker: PartialTracker,
        samples: FloatArray,
        confs: MutableList<Double>? = null
    ) {
        var i = 0
        while (i < samples.size) {
            val len = minOf(4096, samples.size - i)
            tracker.push(samples.copyOfRange(i, i + len))
            confs?.add(tracker.frameConf)
            i += len
        }
    }

    private fun mediane(l: List<Double>): Double {
        val s = l.sorted()
        return s[s.size / 2]
    }

    /**
     * (a) Glissement de cheville pendant l'extinction : la hauteur
     * glisse de −10 ¢ pendant que la confiance tombe sous 0,08.
     * Sans la porte de continuité, la sortie gèlerait à ~0 ¢ en
     * attendant une frappe ; avec, elle suit le glissement et
     * reste fraîche.
     */
    @Test
    fun porteContinuite_glissementSousLeSeuil_suiviProlonge() {
        val f0 = 110.0
        val b = 0.0003
        val cible = 5 * f0 * sqrt(1 + b * 25) // partiel 5 : 552,06 Hz
        val tracker = PartialTracker(SR)
        tracker.setTarget(cible)

        // Phase 1 : note franche à 0 ¢ — la sortie s'établit.
        pousserParTranches(tracker, genGlide(f0, b, 1.2, { 0.0 }, { 0.35 }))
        val centsDepart = tracker.latest!!.cents
        assertTrue(
            "sortie établie à ~0 ¢ (obtenu $centsDepart)",
            abs(centsDepart) <= 1.0
        )

        // Phase 2 : glissement 0 → −10 ¢ pendant que le signal
        // s'éteint vite vers le régime d'extinction réelle : niveau
        // absolu très faible (c'est lui qui écrase la confiance,
        // via le facteur de niveau) mais bruit de salle encore
        // plus faible — le pic reste trouvable, les estimations
        // restent justes, seule la confiance passe sous 0,08.
        // (Régime calibré empiriquement : à bruit blanc fort, la
        // confiance ne descend pas sous le seuil tant que le pic
        // est trouvable — le RSB y reste trop bon.)
        val confs = mutableListOf<Double>()
        pousserParTranches(
            tracker,
            genGlide(
                f0, b, 3.5,
                { t -> -10.0 * t / 3.5 },
                { t -> max(0.35 * exp(-t / 0.3), 0.0007) },
                -65.0, seed = 23
            ),
            confs
        )
        // Le régime faible doit être réel pendant la fin du
        // glissement (amplitude au plancher depuis ~1,9 s), sinon
        // le test ne prouve rien.
        val confMed = mediane(confs.subList(24, confs.size))
        assertTrue(
            "confiance médiane en régime faible < 0,08 (obtenu $confMed)",
            confMed < 0.08
        )
        val centsApresGlissement = tracker.latest!!.cents
        assertTrue(
            "le suivi a accompagné le glissement (obtenu $centsApresGlissement)",
            centsApresGlissement <= -5.0
        )

        // Phase 3 : tenue à −10 ¢, toujours faible — la sortie
        // converge et reste fraîche (pas de gel en attente d'une
        // frappe).
        pousserParTranches(
            tracker,
            genGlide(f0, b, 2.0, { -10.0 }, { 0.0007 }, -65.0, seed = 37)
        )
        val centsFin = tracker.latest!!.cents
        assertTrue(
            "sortie convergée à −10 ± 2,5 ¢ (obtenu $centsFin)",
            abs(centsFin + 10.0) <= 2.5
        )
        assertTrue(
            "sortie fraîche (âge ${tracker.latestAgeSec()} s)",
            tracker.latestAgeSec() <= 0.75
        )
    }

    /**
     * (b) Rafale étrangère basse-confiance : un signal à +40 ¢,
     * dans le même régime faible que le test (a), ne doit pas
     * tirer la lecture établie à ~0 ¢ — la porte de continuité
     * rejette ce qui est loin de la sortie courante.
     */
    @Test
    fun porteContinuite_estimationLointaineFaible_rejetee() {
        val f0 = 110.0
        val b = 0.0003
        val cible = 5 * f0 * sqrt(1 + b * 25)
        val tracker = PartialTracker(SR)
        tracker.setTarget(cible)

        pousserParTranches(tracker, genGlide(f0, b, 1.2, { 0.0 }, { 0.35 }))
        val centsAvant = tracker.latest!!.cents
        assertTrue(
            "sortie établie à ~0 ¢ (obtenu $centsAvant)",
            abs(centsAvant) <= 1.0
        )

        val confs = mutableListOf<Double>()
        pousserParTranches(
            tracker,
            genGlide(f0, b, 2.0, { 40.0 }, { 0.0007 }, -65.0, seed = 41),
            confs
        )
        val confMed = mediane(confs)
        assertTrue(
            "confiance médiane de la rafale < 0,08 (obtenu $confMed)",
            confMed < 0.08
        )
        val centsApres = tracker.latest!!.cents
        assertTrue(
            "la lecture n'a pas bougé (avant $centsAvant, après $centsApres)",
            abs(centsApres - centsAvant) <= 1.5
        )
    }

    /** Courbe de transitoire MESURÉE sur l'échantillon réel de
     * Grégoire (Fa5 du Steinway D du Palais, hauteur établie
     * 698,2 Hz, 2026-11-18) : +3,0 ¢ à 100 ms, plateau ≈ +2,7 ¢
     * jusqu'à ~400 ms, puis +1,8 ¢ à 500 ms, +1,4 ¢ à 700 ms,
     * +1,0 ¢ à 900 ms, établie vers 1,2 s. Interpolation linéaire
     * entre les points mesurés. */
    private fun transitoireMesure(t: Double): Double {
        val pts = listOf(
            0.0 to 3.0, 0.1 to 3.0, 0.4 to 2.7, 0.5 to 1.8,
            0.7 to 1.4, 0.9 to 1.0, 1.2 to 0.0
        )
        if (t <= pts.first().first) return pts.first().second
        for (i in 1 until pts.size) {
            if (t <= pts[i].first) {
                val (t0, v0) = pts[i - 1]
                val (t1, v1) = pts[i]
                return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
            }
        }
        return 0.0
    }

    /**
     * Transitoire d'attaque (moteur de l'écran accordeur), calibré
     * sur la courbe mesurée ci-dessus : la sortie publiée ne doit
     * jamais suivre le pic de +3 ¢ (résidu ≤ 1,5 ¢), ni à la
     * première frappe ni à la refrappe sur la hauteur établie ;
     * ensuite, un glissement de cheville sans frappe est suivi
     * normalement et ne déclenche aucun traitement d'attaque.
     */
    @Test
    fun attaque_picDeFrappeIgnore_puisGlissementSuivi() {
        val table = chargerTable()
        val note25 = table[24]
        val f0 = note25.fundamentalHz
        val b = bFor(note25)
        val moteur = TunerEngine(SR)
        moteur.config("bloque", 25, "test@440", table)

        fun frappe(dur: Double, seed: Int) = genGlide(
            f0, b, dur,
            { t -> transitoireMesure(t) },
            { t -> 0.35 * exp(-t / 0.8) },
            -42.0, seed
        )

        fun pousserMoteur(samples: FloatArray, lus: MutableList<Double>? = null) {
            var i = 0
            while (i < samples.size) {
                val len = minOf(1024, samples.size - i)
                moteur.push(samples.copyOfRange(i, i + len))
                val l = moteur.dernierEtat?.latest
                if (lus != null && l != null) lus.add(l.cents)
                i += len
            }
        }

        // Frappe 1 (froide) : la première lecture naît établie.
        val lus1 = mutableListOf<Double>()
        pousserMoteur(frappe(2.2, 51), lus1)
        assertTrue("des lectures publiées doivent exister", lus1.isNotEmpty())
        assertTrue(
            "pic de frappe jamais affiché (max ${lus1.maxOrNull()} ¢)",
            lus1.all { abs(it) <= 1.5 }
        )
        val frappesApres1 = moteur.frappesDetectees
        assertTrue(
            "la frappe 1 a été détectée ($frappesApres1)",
            frappesApres1 >= 1
        )
        val centsEtabli = moteur.dernierEtat!!.latest!!.cents
        assertTrue(
            "lecture établie à ~0 ¢ (obtenu $centsEtabli)",
            abs(centsEtabli) <= 1.0
        )

        // Frappe 2 : refrappe sur la corde qui s'éteint — la
        // valeur affichée reste la hauteur établie, le pic de
        // +3 ¢ ne paraît pas.
        val lus2 = mutableListOf<Double>()
        pousserMoteur(frappe(2.2, 67), lus2)
        assertTrue(
            "la frappe 2 a été détectée (${moteur.frappesDetectees})",
            moteur.frappesDetectees > frappesApres1
        )
        assertTrue(
            "pic de la refrappe jamais affiché (max ${lus2.maxOrNull()} ¢)",
            lus2.all { abs(it) <= 1.5 }
        )

        // Glissement de cheville SANS frappe (niveau continu) :
        // aucun déclenchement, suivi normal — puis tenue à −6 ¢,
        // le temps que la médiane de l'historique converge (la
        // sortie suit le glissement avec le retard propre à sa
        // fenêtre, puis se cale).
        val frappesAvantGlissement = moteur.frappesDetectees
        pousserMoteur(
            genGlide(f0, b, 2.5, { t -> -6.0 * t / 2.5 }, { 0.03 }, -42.0, seed = 71)
        )
        pousserMoteur(
            genGlide(f0, b, 1.5, { -6.0 }, { 0.03 }, -42.0, seed = 73)
        )
        assertEquals(
            "aucune frappe détectée pendant un glissement",
            frappesAvantGlissement, moteur.frappesDetectees
        )
        val centsFin = moteur.dernierEtat!!.latest!!.cents
        assertTrue(
            "glissement suivi à −6 ± 2 ¢ (obtenu $centsFin)",
            abs(centsFin + 6.0) <= 2.0
        )
    }
}
