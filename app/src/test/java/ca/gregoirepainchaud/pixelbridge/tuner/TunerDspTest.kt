package ca.gregoirepainchaud.pixelbridge.tuner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
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
}
