package ca.gregoirepainchaud.pixelbridge.tuner

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Mesure d'inharmonicité et génération d'un accord sur mesure
// (2026-11-18, version 0.5) — demande de Grégoire : la mécanique
// « do-sol » qui produit un NOUVEL accord adapté au piano devant soi.
//
// Principe, en trois temps :
//   1. On mesure la série des partiels (1 à 8) d'une quinzaine de
//      notes ancres — tous les do et tous les sol du clavier.
//   2. Pour chaque ancre, on ajuste le coefficient d'inharmonicité
//      B par moindres carrés sur la formule de Young :
//          f_n = n · f_1 · √(1 + B·n²)
//      puis on interpole B (en log) sur les 88 touches.
//   3. On génère la table des cibles dans la MÊME structure que les
//      accords de Simon (NoteAccord) : le la au diapason choisi,
//      puis propagation octave par octave par coïncidences de
//      partiels (6:3 dans les graves, 4:2 au centre, 2:1 dans les
//      aigus, transitions fondues) — l'affichage existant consomme
//      l'accord généré sans modification.
//
// Les accords de Simon (tunings.json) ne sont JAMAIS touchés par ce
// module : il ne lit que des fréquences mesurées au micro.
// ---------------------------------------------------------------------------

/** Un partiel mesuré d'une note (fréquence absolue + confiance 0..1). */
data class PartielMesure(
    val n: Int,
    val freqHz: Double,
    val confiance: Double
)

/** Une ancre mesurée : la note, son fondamental ajusté, son B. */
data class MesureAncre(
    val key: Int,
    val nom: String,
    val f1Hz: Double,
    val b: Double,
    /** Écart du fondamental mesuré vs sa hauteur nominale (cents). */
    val ecartCents: Double,
    val partiels: List<PartielMesure>
)

/** Résultat de l'ajustement de Young sur une série de partiels. */
data class ResultatAjustement(
    val f1Hz: Double,
    val b: Double,
    /** Écart-type des résidus des partiels retenus (cents). */
    val rmsCents: Double,
    val partielsRetenus: List<PartielMesure>
)

/**
 * Ajustement de B par moindres carrés, linéarisé :
 *   (f_n / n)² = f_1² + f_1²·B·n²
 * donc, avec y = (f_n/n)² et x = n², une droite y = a + c·x donne
 * f_1 = √a et B = c/a. Régression pondérée par la confiance, puis
 * rejet itératif des partiels aberrants (résidu > max(2,5 ¢,
 * 2,2 × RMS)) et nouvel ajustement. Retourne null si la série est
 * inexploitable (moins de 3 partiels, pente ou ordonnée impossible).
 */
fun ajusterInharmonicite(partiels: List<PartielMesure>): ResultatAjustement? {
    // Filtre sanitaire : une fréquence non finie (NaN, infini)
    // ou absurde empoisonnerait toute la régression (log2 d'un
    // rapport négatif = NaN, résidus infinis) — on l'écarte.
    var serie = partiels.filter {
        it.n >= 1 && it.freqHz.isFinite() && it.freqHz > 0 &&
            it.freqHz < 100000 && it.confiance.isFinite()
    }.sortedBy { it.n }
    if (serie.size < 3) return null
    var resultat: ResultatAjustement? = null
    for (iteration in 0 until 3) {
        // Régression pondérée y = a + c·x.
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        for (p in serie) {
            val w = max(0.05, p.confiance)
            val x = (p.n * p.n).toDouble()
            val y = (p.freqHz / p.n) * (p.freqHz / p.n)
            sw += w
            sx += w * x
            sy += w * y
        }
        val mx = sx / sw
        val my = sy / sw
        var sxx = 0.0
        var sxy = 0.0
        for (p in serie) {
            val w = max(0.05, p.confiance)
            val x = (p.n * p.n).toDouble()
            val y = (p.freqHz / p.n) * (p.freqHz / p.n)
            sxx += w * (x - mx) * (x - mx)
            sxy += w * (x - mx) * (y - my)
        }
        if (sxx <= 0) return null
        val c = sxy / sxx
        val a = my - c * mx
        if (a <= 0 || c <= 0) return null
        val f1 = sqrt(a)
        val b = c / a
        if (b > 0.02) return null
        // Résidus en cents.
        val residus = serie.map { p ->
            val pred = p.n * f1 * sqrt(1 + b * p.n * p.n)
            1200 * log2(p.freqHz / pred)
        }
        val rms = sqrt(residus.sumOf { it * it } / residus.size)
        resultat = ResultatAjustement(f1, b, rms, serie)
        // Rejet des aberrants, puis on refait l'ajustement.
        val seuil = max(2.5, 2.2 * rms)
        val gardees = serie.filterIndexed { idx, _ -> abs(residus[idx]) <= seuil }
        if (gardees.size == serie.size || gardees.size < 3) break
        serie = gardees
    }
    return resultat
}

/**
 * Courbe de B sur les 88 touches : interpolation linéaire de log B
 * entre les ancres (B varie de façon à peu près géométrique le long
 * du clavier), extrapolation plate aux extrémités, bornée à
 * [1e-5, 0,008]. Tableau indexé par la touche (1..88 ; l'indice 0
 * est inutilisé).
 */
fun courbeInharmonicite(ancres: List<MesureAncre>): DoubleArray {
    // Ancres nettoyées d'abord : une seule par touche (la
    // dernière gagne — deux mesures de la même touche rendraient
    // le dénominateur de l'interpolation nul), et chaque B est
    // ramené dans la plage physique AVANT le logarithme : un B
    // nul, négatif ou NaN (ancre corrompue, vieux brouillon)
    // donnerait ln(≤ 0) = NaN et empoisonnerait toute la courbe.
    val parCle = LinkedHashMap<Int, Double>()
    for (a in ancres) {
        parCle[a.key] =
            if (a.b.isFinite()) a.b.coerceIn(0.00001, 0.008) else 0.0004
    }
    val triees = parCle.entries.sortedBy { it.key }
    val courbe = DoubleArray(89)
    if (triees.isEmpty()) {
        // Aucune ancre : courbe plate de repli plutôt qu'une
        // exception. L'appelant exige déjà ≥ 8 ancres avant de
        // générer un accord — ceci n'est qu'un filet.
        for (k in 1..88) courbe[k] = 0.0004
        return courbe
    }
    for (k in 1..88) {
        val b = when {
            k <= triees.first().key -> triees.first().value
            k >= triees.last().key -> triees.last().value
            else -> {
                var i = 0
                while (i < triees.size - 2 && triees[i + 1].key < k) i++
                val a0 = triees[i]
                val a1 = triees[i + 1]
                val frac = (k - a0.key).toDouble() / (a1.key - a0.key)
                val logB = ln(a0.value) + frac * (ln(a1.value) - ln(a0.value))
                kotlin.math.exp(logB)
            }
        }
        courbe[k] = min(0.008, max(0.00001, b))
    }
    return courbe
}

/**
 * Étirement d'octave (cents) à la touche k, par coïncidence de
 * partiels entre la note k et la note k+12 :
 *   paire m:(m/2) → Δ = 600 · log2( (1 + B_k·m²) / (1 + B_{k+12}·(m/2)²) )
 * Registres : 6:3 pour les graves (k ≤ 23), 4:2 au centre, 2:1
 * pour les aigus (k ≥ 51) ; les transitions sont fondues sur
 * ±2 touches pour garder une courbe lisse.
 */
fun deltaOctaveCents(bParCle: DoubleArray, k: Int): Double {
    val bk = bParCle[k]
    val bk12 = bParCle[k + 12]
    fun delta(m: Int): Double {
        val haut = m / 2
        return 600 * log2((1 + bk * m * m) / (1 + bk12 * haut * haut))
    }
    val d6 = delta(6)
    val d4 = delta(4)
    val d2 = delta(2)
    val w6 = when {
        k <= 23 -> 1.0
        k >= 27 -> 0.0
        else -> (27 - k) / 4.0
    }
    val w2 = when {
        k <= 47 -> 0.0
        k >= 51 -> 1.0
        else -> (k - 47) / 4.0
    }
    val w4 = 1.0 - w6 - w2
    return w6 * d6 + w4 * d4 + w2 * d2
}

/**
 * Partiel écouté d'une note générée : celui dont la fréquence
 * (avec inharmonicité) tombe le plus près de 500 Hz en échelle
 * logarithmique, parmi les partiels 1 à 10 sous 14 kHz — la zone
 * où la mesure du traqueur est la plus précise et la plus
 * audible. (Les accords de Simon suivent la même idée : partiel
 * 10 tout en bas, 1 dans les aigus.)
 */
fun partielEcouteGenere(f1Hz: Double, b: Double): Int {
    var meilleur = 1
    var meilleurScore = Double.MAX_VALUE
    for (p in 1..10) {
        val f = p * f1Hz * sqrt(1 + b * p * p)
        if (f > 14000) break
        val score = abs(log2(f / 500.0))
        if (score < meilleurScore) {
            meilleurScore = score
            meilleur = p
        }
    }
    return meilleur
}

/**
 * Génère la table des 88 notes (structure NoteAccord, fréquences
 * normalisées à 440 comme dans tunings.json) à partir de la
 * courbe de B et du diapason choisi.
 *
 * Construction des écarts S(k) (cents ajoutés au tempérament
 * égal) : S(49) = 0 ; la chaîne des la est propagée octave par
 * octave avec deltaOctaveCents ; dans l'octave de tempérament
 * (touches 37 à 48), S suit une rampe linéaire entre S(37) et
 * S(49) — l'octave de tempérament reste égale, l'étirement
 * s'accumule d'octave en octave, comme dans un accord à
 * l'oreille et dans les accords calculés des accordeurs
 * électroniques. Chaque chaîne de classe de hauteur accumule
 * ensuite les Δ de ses propres octaves : pour toute touche k,
 * S(k+12) − S(k) = Δ(k) exactement.
 */
fun genererTableAccord(
    bParCle: DoubleArray,
    pitch: Int,
    noms: List<String>
): List<NoteAccord> {
    val s = DoubleArray(89)
    // Chaîne des la (touches 1, 13, 25, 37, 49, 61, 73, 85).
    s[49] = 0.0
    var k = 49
    while (k + 12 <= 88) {
        s[k + 12] = s[k] + deltaOctaveCents(bParCle, k)
        k += 12
    }
    k = 49
    while (k - 12 >= 1) {
        s[k - 12] = s[k] - deltaOctaveCents(bParCle, k - 12)
        k -= 12
    }
    // Rampe de l'octave de tempérament (touches 37..48).
    val base = DoubleArray(89)
    for (a in 37..48) {
        base[a] = s[37] + (a - 37) * (s[49] - s[37]) / 12.0
    }
    // Chaînes des autres classes de hauteur.
    for (cle in 1..88) {
        val a = 37 + ((cle - 37) % 12 + 12) % 12
        var v = base[a]
        var j = a
        if (cle > a) {
            while (j < cle) {
                v += deltaOctaveCents(bParCle, j)
                j += 12
            }
        } else if (cle < a) {
            while (j > cle) {
                v -= deltaOctaveCents(bParCle, j - 12)
                j -= 12
            }
        }
        s[cle] = v
    }
    // Table finale, normalisée à 440 (la vue met à l'échelle du
    // diapason choisi, exactement comme pour les accords de Simon).
    return (1..88).map { cle ->
        val f1 = 440.0 * Math.pow(2.0, (cle - 49) / 12.0 + s[cle] / 1200.0)
        val b = bParCle[cle]
        val p = partielEcouteGenere(f1 * pitch / 440.0, b)
        NoteAccord(
            key = cle,
            name = noms.getOrElse(cle - 1) { "Note $cle" },
            fundamentalHz440 = f1,
            partialTargetHz440 = p * f1 * sqrt(1 + b * p * p),
            listenPartial = p
        )
    }
}

/** Les ancres du parcours guidé : tous les do et tous les sol. */
val ANCRES_PARCOURS: List<Int> = listOf(
    4, 11, 16, 23, 28, 35, 40, 47, 52, 59, 64, 71, 76, 83, 88
)

// ---------------------------------------------------------------------------
// Analyseur de partiels : généralisation du PartialTracker prouvé
// (TunerDsp.kt) à la mesure SIMULTANÉE des partiels 1 à 8 d'une
// note. Une seule FFT (Hann 32768) par saut de 4096 échantillons ;
// pour chaque partiel, exactement la même mathématique que le
// traqueur : recherche du pic autour de la fréquence attendue,
// plancher de bruit au 30e percentile, confiance RSB × niveau,
// interpolation complexe de Jacobsen (δ ← −2δ), raffinement par
// l'avance de phase, sortie médiane pondérée.
//
// Les fenêtres de recherche se recentrent en continu sur un écart
// global et un B provisoire ajustés dès que des partiels sont
// mesurés : un piano très faux (écart de plusieurs dizaines de
// cents) se mesure quand même — l'écart commun est absorbé par le
// recentrage, et B ne dépend que des rapports entre partiels.
// ---------------------------------------------------------------------------

/** État d'un partiel pendant la capture d'une ancre (pour l'écran). */
data class EtatPartiel(
    val n: Int,
    val freqHz: Double?,
    val nbEstimations: Int,
    val confianceMoyenne: Double,
    val dispersionCents: Double,
    val propre: Boolean
)

class AnalyseurPartiels(
    val sampleRate: Int,
    val cleAncre: Int,
    /** Fondamental nominal (tempérament égal au diapason choisi). */
    val f1Nominale: Double,
    fftSize: Int = 32768,
    hopSize: Int = 4096
) {
    val n: Int = fftSize
    val hop: Int = hopSize

    // VERROU D'ACCÈS (correctif 0.6, plantage terrain du
    // 2026-11-18) : push() roule sur le fil de capture et mute
    // les listes hist/estimations des suivis ; etatPartiels() et
    // evaluer() les LISENT depuis le fil d'interface (sondage
    // toutes les 150 ms). Sans exclusion mutuelle, une lecture
    // pendant une mutation lève ConcurrentModificationException
    // ou IndexOutOfBounds — c'est ce qui a tué l'app pendant la
    // première ancre (C1, touche 4), après quelques secondes de
    // chasse où les deux fils se croisaient sans relâche. Tout
    // accès à l'état des suivis passe donc par ce verrou ; les
    // appels imbriqués (evaluer → partielsPropres →
    // etatPartiels) sont réentrants, comme tout synchronized.
    private val verrou = Any()

    private val buf = FloatArray(n)
    private var filled = 0
    private var sinceHop = 0
    var total: Long = 0
        private set
    private val fftObj = Fft(n)
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val win = DoubleArray(n)

    // Recentrage courant des fenêtres de recherche.
    private var ecartCentreCents = 0.0
    private var bCentre = 0.0004

    private inner class SuiviPartiel(val numero: Int) {
        var lastBin = -1
        var lastPhase = 0.0
        var phaseOk = 0
        val hist = mutableListOf<Pair<Double, Double>>() // (fréq, conf)
        /** Sorties médianes successives (fréq, conf) — la matière
         *  des statistiques de stabilité de la mesure. */
        val estimations = mutableListOf<Pair<Double, Double>>()
    }

    private val suivis: List<SuiviPartiel> = (1..8).map { SuiviPartiel(it) }

    init {
        for (i in 0 until n) {
            win[i] = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (n - 1))
        }
    }

    fun push(samples: FloatArray) {
        synchronized(verrou) {
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
            if (filled >= n && sinceHop >= hop) {
                sinceHop = 0
                analyze()
            }
        }
    }

    /** Fréquence attendue du partiel n, recentrage courant inclus. */
    private fun centreAttendu(numero: Int): Double =
        numero * f1Nominale * sqrt(1 + bCentre * numero * numero) *
            Math.pow(2.0, ecartCentreCents / 1200)

    /** Demi-largeur de la fenêtre de recherche (cents). */
    private fun demiFenetreCents(numero: Int): Double =
        min(170.0, 55.0 + 2.2 * numero * numero)

    private fun analyze() {
        val sr = sampleRate
        for (i in 0 until n) {
            re[i] = buf[i] * win[i]
            im[i] = 0.0
        }
        fftObj.fft(re, im)
        val binHz = sr.toDouble() / n

        // Niveau absolu de la trame (même recette que le traqueur).
        var somme = 0.0
        var i = 0
        while (i < n) {
            somme += buf[i] * buf[i]
            i += 16
        }
        val rms = sqrt(somme / (n / 16))
        val levelDb = 20 * kotlin.math.log10(rms + 1e-9)
        val cLvl = min(1.0, max(0.0, (levelDb + 70) / 30))

        for (suivi in suivis) {
            val numero = suivi.numero
            val fT = centreAttendu(numero)
            if (fT > sr * 0.47) continue
            val demi = demiFenetreCents(numero)
            val lo = max(
                2,
                floor(fT * Math.pow(2.0, -demi / 1200) / binHz).toInt()
            )
            val hi = min(
                n / 2 - 2,
                kotlin.math.ceil(fT * Math.pow(2.0, demi / 1200) / binHz).toInt()
            )
            if (hi <= lo) continue
            var k = -1
            var best = -1.0
            for (b in lo..hi) {
                val mag = re[b] * re[b] + im[b] * im[b]
                if (mag > best) {
                    best = mag
                    k = b
                }
            }
            if (k < 0) continue
            val peakMag = sqrt(best)

            // Plancher de bruit (recette du traqueur, par partiel).
            val nlo = max(2, floor(fT / 2 / binHz).toInt())
            val nhi = min(n / 2 - 1, kotlin.math.ceil(fT * 2.5 / binHz).toInt())
            val mags = mutableListOf<Double>()
            var b = nlo
            while (b <= nhi) {
                if (abs(b - k) > 4) {
                    mags.add(sqrt(re[b] * re[b] + im[b] * im[b]))
                }
                b += 3
            }
            mags.sort()
            val floorV =
                if (mags.isNotEmpty()) mags[floor(mags.size * 0.3).toInt()] else 0.0
            val snrDb = if (floorV > 0) 20 * kotlin.math.log10(peakMag / floorV) else 60.0
            val cSnr = min(1.0, max(0.0, (snrDb - 8) / 22))
            val conf = cSnr * cLvl

            // Interpolation complexe (Jacobsen), δ ← −2δ borné.
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
            delta = max(-0.6, min(0.6, -2 * delta))
            val fInterp = (k + delta) * binHz

            // Avance de phase (même logique que le traqueur).
            val phase = kotlin.math.atan2(x0i, x0r)
            var fUsed = fInterp
            if (k == suivi.lastBin) {
                val expected = 2 * Math.PI * k * hop / n
                val dphi = wrapPi(phase - suivi.lastPhase - expected)
                val fPhase = (k + dphi / (2 * Math.PI) * (n.toDouble() / hop)) * binHz
                if (abs(fPhase - fInterp) < binHz * 0.75) {
                    suivi.phaseOk = min(suivi.phaseOk + 1, 10)
                    if (suivi.phaseOk >= 2) fUsed = fPhase
                } else {
                    suivi.phaseOk = 0
                }
            } else {
                suivi.phaseOk = 0
            }
            suivi.lastBin = k
            suivi.lastPhase = phase

            if (conf > 0.08) {
                suivi.hist.add(fUsed to conf)
                if (suivi.hist.size > 16) suivi.hist.removeAt(0)
            }
            if (suivi.hist.isNotEmpty() && conf > 0.08) {
                val fs = suivi.hist.map { it.first }.sorted()
                val med = fs[floor(fs.size / 2.0).toInt()]
                var sw = 0.0
                var sf = 0.0
                for (h in suivi.hist) {
                    val dc = abs(1200 * log2(h.first / med))
                    if (dc <= 0.5) {
                        sw += h.second
                        sf += h.second * h.first
                    }
                }
                val fOut = if (sw > 0) sf / sw else med
                val confOut = suivi.hist.sumOf { it.second } / suivi.hist.size
                val confFinale = min(1.0, confOut * (if (conf > 0.08) 1.0 else 0.4))
                if (confFinale >= 0.15) {
                    suivi.estimations.add(fOut to confFinale)
                    if (suivi.estimations.size > 60) suivi.estimations.removeAt(0)
                }
            }
        }
        recentrer()
    }

    /**
     * Recentre les fenêtres (écart global + B provisoire) dès que
     * des médianes de partiels existent : l'ajustement de Young
     * sur les médianes courantes donne le recentrage.
     */
    private fun recentrer() {
        val medianes = suivis.mapNotNull { suivi ->
            if (suivi.estimations.size < 3) null
            else {
                val triees = suivi.estimations.map { it.first }.sorted()
                val med = triees[triees.size / 2]
                val conf = suivi.estimations.sumOf { it.second } /
                    suivi.estimations.size
                PartielMesure(suivi.numero, med, conf)
            }
        }
        if (medianes.size >= 3) {
            val fit = ajusterInharmonicite(medianes)
            if (fit != null && fit.b in 0.00001..0.01) {
                bCentre = fit.b
                ecartCentreCents = 1200 * log2(fit.f1Hz / f1Nominale)
            }
        } else if (medianes.isNotEmpty()) {
            val ecarts = medianes.map {
                1200 * log2(it.freqHz / (it.n * f1Nominale)) * it.confiance
            }
            val poids = medianes.sumOf { it.confiance }
            if (poids > 0) ecartCentreCents = ecarts.sum() / poids
        }
    }

    /** Statistiques d'un partiel sur les estimations accumulées. */
    private fun statistiques(suivi: SuiviPartiel): EtatPartiel {
        val est = suivi.estimations
        if (est.isEmpty()) {
            return EtatPartiel(suivi.numero, null, 0, 0.0, 999.0, false)
        }
        val freqs = est.map { it.first }.sorted()
        val med = freqs[freqs.size / 2]
        val confMoy = est.sumOf { it.second } / est.size
        val cents = est.map { 1200 * log2(it.first / med) }.sorted()
        val p10 = cents[(cents.size * 0.1).toInt()]
        val p90 = cents[min(cents.size - 1, (cents.size * 0.9).toInt())]
        val dispersion = p90 - p10
        // Critères « propre » adaptés au registre (correctif 0.6,
        // remarque terrain de Grégoire : l'analyseur était trop
        // exigeant pour les graves). En symétrie de
        // l'assouplissement des touches ≥ 76, les ancres graves
        // (≤ 16) se contentent de 3 estimations. Surtout, la
        // dispersion et la confiance exigées dépendent de la
        // FRÉQUENCE du partiel : la fenêtre FFT fait 1,46 Hz,
        // soit ~77 ¢ à 33 Hz — un partiel de C1 est par nature
        // brouillon et très inharmonique ; exiger de lui la
        // netteté d'un partiel à 500 Hz (2,0 ¢, conf 0,30)
        // condamnait l'analyseur à chasser indéfiniment une
        // perfection que le grave ne donne pas.
        val seuilEstimations = if (cleAncre >= 76 || cleAncre <= 16) 3 else 5
        val dispersionMax = when {
            med < 80.0 -> 6.0
            med < 160.0 -> 3.5
            else -> 2.0
        }
        val confianceMin = if (med < 80.0) 0.22 else 0.30
        val propre = est.size >= seuilEstimations &&
            confMoy >= confianceMin && dispersion <= dispersionMax
        return EtatPartiel(suivi.numero, med, est.size, confMoy, dispersion, propre)
    }

    /** État courant des 8 partiels (pour l'écran du parcours). */
    fun etatPartiels(): List<EtatPartiel> =
        synchronized(verrou) { suivis.map { statistiques(it) } }

    /** Partiels jugés propres, prêts pour l'ajustement final. */
    fun partielsPropres(): List<PartielMesure> =
        etatPartiels().filter { it.propre && it.freqHz != null }
            .map { PartielMesure(it.n, it.freqHz!!, it.confianceMoyenne) }

    sealed class Verdict {
        /** Mesure solide : elle avance toute seule à l'ancre suivante. */
        data class Pret(val mesure: MesureAncre) : Verdict()

        /** Mesure exploitable mais faible : à Grégoire de l'accepter. */
        data class Faible(val mesure: MesureAncre, val raisons: List<String>) : Verdict()

        /** Pas encore (ou pas du tout) mesurable en l'état. */
        data class Insuffisant(val raisons: List<String>, val nbPropres: Int) : Verdict()
    }

    /**
     * Évalue la capture en cours. Critères stricts (Pret) :
     * ajustement réussi avec ≥ 4 partiels retenus dont un ≤ 3 et
     * un ≥ 5 (sauf tout en haut du clavier, où les partiels hauts
     * n'existent presque plus), RMS ≤ 1,5 ¢, B dans [2e-5, 0,008].
     * Critères faibles (Faible) : ≥ 3 partiels retenus, RMS ≤ 2,5 ¢.
     *
     * Registre grave profond (ancres ≤ 16, correctif 0.6) : les
     * seuils sont assouplis en symétrie des aigus — ≥ 3 partiels
     * retenus et RMS ≤ 2,5 ¢ suffisent pour Pret, RMS ≤ 4,0 ¢
     * pour Faible. Les partiels y sont par nature plus dispersés
     * et l'ajustement de Young y laisse des résidus plus grands ;
     * mieux vaut conclure (solide, ou faible avec ses raisons)
     * que chasser sans fin.
     */
    fun evaluer(nom: String): Verdict =
        synchronized(verrou) { evaluerInterne(nom) }

    private fun evaluerInterne(nom: String): Verdict {
        val propres = partielsPropres()
        val fit = ajusterInharmonicite(propres)
            ?: return Verdict.Insuffisant(
                listOf(
                    "série de partiels inexploitable pour l'instant " +
                        "(${propres.size} partiel(s) propre(s) sur 8)"
                ),
                propres.size
            )
        val retenus = fit.partielsRetenus
        val mesure = MesureAncre(
            key = cleAncre,
            nom = nom,
            f1Hz = fit.f1Hz,
            b = fit.b,
            ecartCents = 1200 * log2(fit.f1Hz / f1Nominale),
            partiels = retenus
        )
        val gravesProfonds = cleAncre <= 16
        val minRetenusPret = if (gravesProfonds) 3 else 4
        val rmsMaxPret = if (gravesProfonds) 2.5 else 1.5
        val rmsMaxFaible = if (gravesProfonds) 4.0 else 2.5
        val raisonsFaible = mutableListOf<String>()
        if (retenus.size < minRetenusPret) {
            raisonsFaible.add(
                "${retenus.size} partiels retenus (on en vise $minRetenusPret+)"
            )
        }
        if (retenus.minOf { it.n } > 3) raisonsFaible.add("aucun partiel bas (1 à 3) retenu")
        if (retenus.maxOf { it.n } < 5 && cleAncre < 76) {
            raisonsFaible.add("aucun partiel haut (5+) retenu")
        }
        if (fit.rmsCents > rmsMaxPret) raisonsFaible.add("résidus élevés (${fmt1(fit.rmsCents)} ¢)")
        if (fit.b !in 0.00002..0.008) raisonsFaible.add("B hors plage plausible")
        if (raisonsFaible.isEmpty()) return Verdict.Pret(mesure)
        val exploitable = retenus.size >= 3 && fit.rmsCents <= rmsMaxFaible &&
            fit.b in 0.00001..0.01
        return if (exploitable) {
            Verdict.Faible(mesure, raisonsFaible)
        } else {
            Verdict.Insuffisant(raisonsFaible, propres.size)
        }
    }

    private fun fmt1(v: Double): String =
        String.format(java.util.Locale.ROOT, "%.1f", v)
}
