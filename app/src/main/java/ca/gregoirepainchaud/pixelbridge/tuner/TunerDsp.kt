package ca.gregoirepainchaud.pixelbridge.tuner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Port Kotlin fidèle de dsp.js (moteur du prototype web prouvé sur le
// terrain, 16/16 cas synthétiques) — voir docs/reference-tuner-web/.
// Aucun écart d'algorithme : mêmes fenêtres, mêmes seuils, mêmes
// constantes. Le chemin d'exécution visé est celui du worklet
// (flux continu, raffinement par avance de phase activé).
// ---------------------------------------------------------------------------

/** FFT radix-2 itérative, port de makeFFT (dsp.js). */
class Fft(val size: Int) {
    private val rev: IntArray
    private val cosT: DoubleArray
    private val sinT: DoubleArray

    init {
        val levels = Math.round(log2(size.toDouble())).toInt()
        require(1 shl levels == size) { "FFT: taille non puissance de 2" }
        rev = IntArray(size)
        for (i in 0 until size) {
            var r = 0
            var x = i
            for (j in 0 until levels) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
        cosT = DoubleArray(size / 2)
        sinT = DoubleArray(size / 2)
        for (i in 0 until size / 2) {
            cosT[i] = cos(2 * PI * i / size)
            sinT[i] = -sin(2 * PI * i / size)
        }
    }

    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = size
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var tw = 0
                for (j in 0 until half) {
                    val wr = cosT[tw]
                    val wi = sinT[tw]
                    val a = i + j
                    val b = i + j + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    tw += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}

fun wrapPi(x0: Double): Double {
    var x = x0
    while (x > PI) x -= 2 * PI
    while (x < -PI) x += 2 * PI
    return x
}

/** Dernière mesure du traqueur de partiel (objet `latest` de dsp.js). */
data class MesurePartiel(
    val freqHz: Double,
    val cents: Double,
    val confidence: Double,
    val snrDb: Double,
    val levelDb: Double,
    val peakBin: Int,
    val rawCents: Double
)

/**
 * Traqueur du partiel de référence — port de PartialTracker (dsp.js).
 * FFT fenêtrée (Hann, 32768) sur le flux audio, recherche du pic dans
 * une fenêtre de ±60 cents autour du partiel attendu, raffinement par
 * interpolation complexe (Jacobsen) puis par l'avance de phase entre
 * trames, lissage pondéré par la confiance (RSB spectral local).
 *
 * Admission dans l'historique de sortie : au-dessus d'une confiance
 * de 0,08, comme dsp.js. En dessous, la PORTE DE CONTINUITÉ (0.12) :
 * l'estimation n'entre que si elle se trouve à ±3 ¢ de la médiane de
 * l'historique retenu — elle prolonge alors le suivi pendant
 * l'extinction de la corde et les glissements de cheville (la
 * lecture ne gèle plus en attendant une frappe), sans pouvoir tirer
 * la lecture vers une valeur étrangère. Leçon de la 0.10, retirée :
 * abaisser le seuil d'admission sans condition (0,03) a rendu la
 * hauteur non fiable sur un vrai piano — estimations faibles et
 * aléatoires admises telles quelles (verdict terrain du 2026-11-18,
 * PianoMeter en référence).
 */
class PartialTracker(
    val sampleRate: Int,
    fftSize: Int = 32768,
    hopSize: Int = 4096,
    phaseRefine: Boolean = true
) {
    val n: Int = fftSize
    val hop: Int = hopSize
    val phaseEnabled: Boolean = phaseRefine
    private val buf = FloatArray(n)
    private var filled = 0        // échantillons reçus (≤ N au début)
    private var sinceHop = 0      // échantillons depuis la dernière analyse
    var total: Long = 0           // échantillons reçus au total
        private set
    private val fftObj = Fft(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val win = DoubleArray(n)

    var targetHz: Double? = null
        private set

    /**
     * Poids du suivi (1,0 = normal) : réglé par le moteur de
     * l'écran accordeur après une frappe — 0 pendant la fenêtre
     * de dépondération forte du transitoire d'attaque, puis en
     * rampe jusqu'à 1 (voir TunerEngine). Les estimations
     * continuent d'être calculées (confiance, niveau, RSB restent
     * à jour) ; c'est leur confiance effective (confiance ×
     * poids) qui décide de l'admission et du poids dans
     * l'historique. Jamais réglé par le parcours de mesure :
     * ses traqueurs restent à 1,0, le comportement historique.
     */
    var poidsSuivi: Double = 1.0

    /**
     * Demi-largeur de la porte de continuité (¢) : 3,0 en régime
     * normal. Le moteur de l'écran accordeur la resserre à 1,5
     * pendant la rampe du transitoire d'attaque, où elle garde
     * alors toutes les admissions, franches comprises : la queue
     * du transitoire (encore à +1,5..+2,7 ¢ de la hauteur
     * établie) reste dehors, seul ce qui prolonge de près la
     * hauteur établie entre — un glissement de cheville pendant
     * la rampe, lui, passe. Jamais réglée par le parcours de
     * mesure.
     */
    var porteContinuiteCents: Double = 3.0
    private var lastBin = -1
    private var lastPhase = 0.0
    private var phaseOk = 0       // trames consécutives avec phase cohérente
    private data class EntreeHist(val f: Double, val conf: Double)
    private val hist = mutableListOf<EntreeHist>()
    var latest: MesurePartiel? = null
        private set
    var frameConf = 0.0
        private set
    var frameLevelDb = -120.0
        private set
    var frameSnrDb = 0.0
        private set
    private var lastAcceptedTotal: Long = -1

    init {
        for (i in 0 until n) {
            win[i] = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
        }
    }

    fun setTarget(hz: Double) {
        targetHz = hz
        lastBin = -1
        phaseOk = 0
        hist.clear()
        latest = null
        lastAcceptedTotal = -1
    }

    /** Âge (secondes) de la dernière trame acceptée dans l'historique. */
    fun latestAgeSec(): Double =
        if (lastAcceptedTotal < 0) 999.0
        else (total - lastAcceptedTotal).toDouble() / sampleRate

    fun push(samples: FloatArray) {
        val m = samples.size
        if (m >= n) {
            System.arraycopy(samples, m - n, buf, 0, n)
            filled = n
        } else {
            System.arraycopy(buf, m, buf, 0, n - m)
            System.arraycopy(samples, 0, buf, n - m, m)
            filled = min(n, filled + m)
        }
        total += m
        sinceHop += m
        if (targetHz != null && filled >= n && sinceHop >= hop) {
            sinceHop = 0
            analyze()
        }
    }

    private fun analyze() {
        val sr = sampleRate
        for (i in 0 until n) {
            re[i] = buf[i] * win[i]
            im[i] = 0.0
        }
        fftObj.fft(re, im)

        val binHz = sr.toDouble() / n
        val fT = targetHz ?: return
        val lo = max(2, floor(fT * Math.pow(2.0, -60.0 / 1200) / binHz).toInt())
        val hi = min(n / 2 - 2, ceil(fT * Math.pow(2.0, 60.0 / 1200) / binHz).toInt())

        var k = -1
        var best = -1.0
        for (b in lo..hi) {
            val mag = re[b] * re[b] + im[b] * im[b]
            if (mag > best) {
                best = mag
                k = b
            }
        }
        if (k < 0) return
        val peakMag = sqrt(best)

        // Plancher de bruit : 30e percentile des magnitudes sur
        // [fT/2, fT*2.5], hors pic ±4 (voir dsp.js pour le pourquoi).
        val nlo = max(2, floor(fT / 2 / binHz).toInt())
        val nhi = min(n / 2 - 1, ceil(fT * 2.5 / binHz).toInt())
        val mags = mutableListOf<Double>()
        var b = nlo
        while (b <= nhi) {
            if (abs(b - k) > 4) {
                mags.add(sqrt(re[b] * re[b] + im[b] * im[b]))
            }
            b += 3
        }
        mags.sort()
        val floorV = if (mags.isNotEmpty()) mags[floor(mags.size * 0.3).toInt()] else 0.0
        val snrDb = if (floorV > 0) 20 * log10(peakMag / floorV) else 60.0

        // Niveau absolu (RMS de la fenêtre) pour le seuil « signal faible ».
        var s = 0.0
        var i = 0
        while (i < n) {
            s += buf[i] * buf[i]
            i += 16
        }
        val rms = sqrt(s / (n / 16))
        val levelDb = 20 * log10(rms + 1e-9)

        // Confiance : le RSB spectral décide ; le niveau absolu ne sert
        // qu'à écarter le quasi-silence numérique.
        val cSnr = min(1.0, max(0.0, (snrDb - 8) / 22))
        val cLvl = min(1.0, max(0.0, (levelDb + 70) / 30))
        val conf = cSnr * cLvl

        // --- Interpolation complexe (Jacobsen) autour du pic ---
        val xm1r = re[k - 1]; val xm1i = im[k - 1]
        val x0r = re[k]; val x0i = im[k]
        val xp1r = re[k + 1]; val xp1i = im[k + 1]
        val numR = xm1r - xp1r
        val numI = xm1i - xp1i
        val denR = xm1r - 2 * x0r + xp1r
        val denI = xm1i - 2 * x0i + xp1i
        val denMag = denR * denR + denI * denI
        var delta = 0.0
        if (denMag > 0) delta = (numR * denR + numI * denI) / denMag
        if (!delta.isFinite() || abs(delta) > 1) delta = 0.0
        // Correction empirique (calibrate.js, 2026-10-30) : δ ← −2δ.
        delta = max(-0.6, min(0.6, -2 * delta))
        val fInterp = (k + delta) * binHz

        // --- Avance de phase (raffinement, si le pic est stable) ---
        val phase = atan2(x0i, x0r)
        var fUsed = fInterp
        if (phaseEnabled && k == lastBin) {
            val expected = 2 * PI * k * hop / n
            val dphi = wrapPi(phase - lastPhase - expected)
            val fPhase = (k + dphi / (2 * PI) * (n.toDouble() / hop)) * binHz
            if (abs(fPhase - fInterp) < binHz * 0.75) {
                phaseOk = min(phaseOk + 1, 10)
                if (phaseOk >= 2) fUsed = fPhase
            } else {
                phaseOk = 0
            }
        } else {
            phaseOk = 0
        }
        lastBin = k
        lastPhase = phase

        frameConf = conf
        frameLevelDb = levelDb
        frameSnrDb = snrDb
        // Confiance effective : dépondérée par le moteur de
        // l'écran accordeur pendant le transitoire d'attaque
        // (poidsSuivi, voir plus haut) ; vaut conf partout
        // ailleurs, parcours de mesure compris.
        val confEff = conf * poidsSuivi
        if (confEff <= 0.0) {
            // Fenêtre de dépondération forte du transitoire
            // d'attaque (moteur de l'écran accordeur seulement) :
            // aucune admission, ni franche ni par la porte de
            // continuité — l'historique, donc la sortie, reste
            // sur la hauteur établie d'avant la frappe.
        } else {
            // Médiane de l'historique retenu, s'il existe : la
            // référence des portes ci-dessous (la même médiane
            // qui ancre la sortie).
            val medPorte = if (hist.isNotEmpty()) {
                val fsPorte = hist.map { it.f }.sorted()
                fsPorte[floor(fsPorte.size / 2.0).toInt()]
            } else null
            val ecartPorte = medPorte?.let { abs(1200 * log2(fUsed / it)) }
            // Pendant la rampe d'attaque (poidsSuivi < 1), la
            // porte resserrée garde AUSSI les admissions franches :
            // les estimations de la queue du transitoire restent
            // franches malgré leur dépondération et, sans elle,
            // elles migreraient la médiane vers le dièse.
            val dansLaPorte = ecartPorte == null ||
                ecartPorte <= porteContinuiteCents || poidsSuivi >= 1.0
            if (confEff > 0.08) {
                if (dansLaPorte) {
                    hist.add(EntreeHist(fUsed, confEff))
                    if (hist.size > 16) hist.removeAt(0)
                    lastAcceptedTotal = total
                }
            } else if (ecartPorte != null && ecartPorte <= porteContinuiteCents) {
                // Porte de continuité (0.12, voir l'en-tête de
                // la classe) : sous le seuil franc, seule une
                // estimation voisine de la sortie courante
                // prolonge le suivi. Sans historique (début de
                // note), rien n'entre : comportement historique
                // inchangé.
                hist.add(EntreeHist(fUsed, confEff))
                if (hist.size > 16) hist.removeAt(0)
                lastAcceptedTotal = total
            }
        }

        // Sortie : médiane pondérée simple (moyenne des estimations à
        // ±0,5 cent de la médiane brute, pondérée par la confiance).
        if (hist.isNotEmpty()) {
            val fs = hist.map { it.f }.sorted()
            val med = fs[floor(fs.size / 2.0).toInt()]
            var sw = 0.0
            var sf = 0.0
            for (h in hist) {
                val dc = abs(1200 * log2(h.f / med))
                if (dc <= 0.5) {
                    sw += h.conf
                    sf += h.conf * h.f
                }
            }
            val fOut = if (sw > 0) sf / sw else med
            val confOut = hist.sumOf { it.conf } / hist.size
            latest = MesurePartiel(
                freqHz = fOut,
                cents = 1200 * log2(fOut / fT),
                confidence = min(1.0, confOut * (if (conf > 0.08) 1.0 else 0.4)),
                snrDb = snrDb,
                levelDb = levelDb,
                peakBin = k,
                rawCents = 1200 * log2(fUsed / fT)
            )
        }
    }
}

/** Note brute de la table (fréquences déjà mises à l'échelle du diapason). */
data class NoteBrute(
    val key: Int,
    val fundamentalHz: Double,
    val partialTargetHz: Double,
    val listenPartial: Int
)

/** Diagnostic du détecteur (objet `debug` de dsp.js). */
data class DebugDetecteur(val bestKey: Int, val rel: Double, val margin: Double)

/**
 * Détecteur de note — port de NoteDetector (dsp.js). FFT 16384 ; pour
 * chacune des 88 notes, score = somme des pics spectraux qui COÏNCIDENT
 * (en cents, noyau triangulaire ±75 ¢) avec les partiels 1..6 attendus +
 * bonus au partiel de référence quand il est au-delà du 6e.
 * Adoption : meilleur score > 9× le plancher spectral et > marge× le
 * second, trois analyses de suite.
 */
class NoteDetector(
    val sampleRate: Int,
    fftSize: Int = 16384,
    hopSize: Int = 4096
) {
    val n: Int = fftSize
    val hop: Int = hopSize
    private val buf = FloatArray(n)
    private var filled = 0
    private var sinceHop = 0
    private val fftObj = Fft(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val win = DoubleArray(n)

    private class NoteDet(
        val key: Int, val f0: Double, val fPart: Double, val l: Int, val b: Double
    )

    private var notes: List<NoteDet>? = null
    private var notesParCle: Map<Int, NoteDet> = emptyMap()
    var candKey: Int? = null
        private set
    var candCount = 0
        private set
    var adoptedKey: Int? = null
        private set
    var debug: DebugDetecteur? = null
        private set
    private val scoresByKey = DoubleArray(89)

    init {
        for (i in 0 until n) {
            win[i] = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
        }
    }

    fun setNotes(raw: List<NoteBrute>) {
        val liste = raw.map { note ->
            val l = note.listenPartial
            var b = 0.0002
            if (l > 1) {
                val r = note.partialTargetHz / (l * note.fundamentalHz)
                val cand = (r * r - 1) / (l * l)
                if (cand > 0 && cand < 0.01) b = cand
            }
            NoteDet(note.key, note.fundamentalHz, note.partialTargetHz, l, b)
        }
        notes = liste
        notesParCle = liste.associateBy { it.key }
        candKey = null
        candCount = 0
        adoptedKey = null
    }

    private fun partialFreq(note: NoteDet, m: Int): Double =
        m * note.f0 * sqrt(1 + note.b * m * m)

    fun push(samples: FloatArray) {
        val m = samples.size
        if (m >= n) {
            System.arraycopy(samples, m - n, buf, 0, n)
            filled = n
        } else {
            System.arraycopy(buf, m, buf, 0, n - m)
            System.arraycopy(samples, 0, buf, n - m, m)
            filled = min(n, filled + m)
        }
        sinceHop += m
        if (notes != null && filled >= n && sinceHop >= hop) {
            sinceHop = 0
            analyze()
        }
    }

    // Contribution d'un partiel attendu : magnitude du pic spectral le
    // plus proche, pondérée par la coïncidence en cents (1 à 0 ¢,
    // 0 à ±75 ¢).
    private fun peakMatch(mags: DoubleArray, f: Double): Double {
        val binHz = sampleRate.toDouble() / n
        val c = f / binHz
        val lo = max(1, floor(c * 0.96).toInt())
        val hi = min(mags.size - 2, ceil(c * 1.04).toInt())
        var kb = -1
        var best = -1.0
        for (b in lo..hi) {
            if (mags[b] > best) {
                best = mags[b]
                kb = b
            }
        }
        if (kb < 0) return 0.0
        val a = mags[kb - 1]
        val b0 = mags[kb]
        val g = mags[kb + 1]
        val den = a - 2 * b0 + g
        var d = if (den != 0.0) 0.5 * (a - g) / den else 0.0
        if (!d.isFinite() || abs(d) > 1) d = 0.0
        val fPeak = (kb + d) * binHz
        val centsDist = abs(1200 * log2(fPeak / f))
        return best * max(0.0, 1 - centsDist / 75)
    }

    private fun analyze() {
        val table = notes ?: return
        val sr = sampleRate
        for (i in 0 until n) {
            re[i] = buf[i] * win[i]
            im[i] = 0.0
        }
        fftObj.fft(re, im)
        val half = n / 2
        val mags = DoubleArray(half)
        for (b in 0 until half) {
            mags[b] = sqrt(re[b] * re[b] + im[b] * im[b])
        }

        // Plancher spectral : 70e percentile des magnitudes entre 40 et 6000 Hz.
        val binHz = sr.toDouble() / n
        val b0 = floor(40 / binHz).toInt()
        val b1 = min(half - 1, ceil(6000 / binHz).toInt())
        val band = mutableListOf<Double>()
        var bb = b0
        while (bb <= b1) {
            band.add(mags[bb])
            bb += 2
        }
        band.sort()
        val floorV = if (band.isNotEmpty()) band[floor(band.size * 0.7).toInt()] else 0.0

        val w = doubleArrayOf(1.0, 0.85, 0.7, 0.55, 0.45, 0.35)
        val sumW = w.sum()
        scoresByKey.fill(0.0)
        var best: NoteDet? = null
        var bestScore = 0.0
        for (note in table) {
            var s = 0.0
            for (m in 1..6) {
                val f = partialFreq(note, m)
                if (f > sr * 0.47) break
                s += w[m - 1] * peakMatch(mags, f)
            }
            if (note.l > 6) s += 0.8 * peakMatch(mags, note.fPart)
            val score = s / sumW
            scoresByKey[note.key] = score
            if (score > bestScore) {
                bestScore = score
                best = note
            }
        }
        var gagnant = best ?: return
        // Désambiguïsation d'octave (voir dsp.js).
        while (gagnant.key > 12 && scoresByKey[gagnant.key - 12] >= bestScore / 1.35) {
            val dessous = notesParCle[gagnant.key - 12] ?: break
            gagnant = dessous
            bestScore = scoresByKey[gagnant.key]
        }
        var secondScore = 0.0
        var secondKey: Int? = null
        for (note in table) {
            if (note.key != gagnant.key && scoresByKey[note.key] > secondScore) {
                secondScore = scoresByKey[note.key]
                secondKey = note.key
            }
        }
        val rel = bestScore / (floorV + 1e-12)
        val okLevel = rel > 9
        // Marge réduite quand le rival est l'octave (±12).
        val seuilMarge =
            if (secondKey != null && abs(secondKey - gagnant.key) == 12) 1.10 else 1.22
        val okMargin = bestScore > seuilMarge * secondScore
        debug = DebugDetecteur(
            bestKey = gagnant.key,
            rel = rel,
            margin = if (secondScore > 0) bestScore / secondScore else 99.0
        )
        if (okLevel && okMargin) {
            if (gagnant.key == candKey) {
                candCount++
            } else {
                candKey = gagnant.key
                candCount = 1
            }
            if (candCount >= 3) adoptedKey = gagnant.key
        } else {
            candKey = null
            candCount = 0
        }
    }
}
