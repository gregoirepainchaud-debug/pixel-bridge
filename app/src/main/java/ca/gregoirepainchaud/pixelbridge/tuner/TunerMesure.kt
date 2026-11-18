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
// (2026-11-18, version 0.5 ; schéma et portes refaits en 0.9) —
// demande de Grégoire : la mécanique qui produit un NOUVEL accord
// adapté au piano devant soi.
//
// Principe, en trois temps :
//   1. On mesure la série des partiels d'une dizaine de notes
//      ancres — les la et les do, de La1 à Do6 (schéma 0.9 :
//      c'est le périmètre que mesurent les méthodes établies,
//      FAC s'arrête à Do6 et ne descend pas sous Fa3, CyberTuner
//      échantillonne les la ; les extrêmes sont extrapolés, et
//      l'alternance la-do évite les coïncidences de partiels des
//      quintes do-sol, qui faisaient mesurer la queue de la note
//      précédente à la place de l'ancre).
//   2. Pour chaque ancre, on ajuste le coefficient d'inharmonicité
//      B par moindres carrés sur la formule de Young :
//          f_n = n · f_1 · √(1 + B·n²)
//      puis on interpole B (en log) sur les 88 touches — sous la
//      première ancre, on PROLONGE LA PENTE de log B (B croît
//      vite dans les graves ; une extrapolation plate y
//      sous-estimerait l'étirement), au-dessus de la dernière
//      ancre on extrapole plat.
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

/** B de la graine robuste du diagnostic (le B nominal du module). */
private const val B_GRAINE = 0.0004

/**
 * Diagnostic d'aberrants : nomme les partiels « accrochés au
 * mauvais pic » d'une série qui ne s'ajuste pas sainement.
 *
 * Le retrait unitaire ne suffit pas : dans le cas vécu (Sol2,
 * 0.7), TROIS partiels (5, 7, 8) étaient accrochés à la série
 * du Do2 précédent, et le partiel 1 lui-même (99,7 Hz,
 * confiance 0,42) suffisait à faire basculer la pente de la
 * régression en négatif — aucun retrait seul ne sauvait
 * l'ajustement, qui retournait null de partout. D'où une
 * graine ROBUSTE, insensible aux points faux :
 *
 *  1. Si la série s'ajuste déjà sainement, rien à nommer.
 *  2. Graine : (f1, B) de l'ajustement complet s'il existe,
 *     sinon f1 = médiane des f/n corrigés au B nominal (la
 *     médiane ignore les points faux tant qu'ils sont
 *     minoritaires) et B = B nominal.
 *  3. Noyau cohérent : les partiels à ≤ 15 ¢ de la graine.
 *     S'ils sont ≥ 3 et s'ajustent sainement entre eux, les
 *     aberrants sont les partiels à > 15 ¢ de CE noyau, triés
 *     par écart décroissant, au plus 3 nommés (au-delà, c'est
 *     l'ancre entière qu'on refait, pas un partiel).
 *  4. Repli : retrait unitaire classique (le retrait dont
 *     l'ajustement restant est le plus sain).
 *
 * Liste vide si rien ne se dégage (série < 4, ou chaos
 * complet) — l'écran retombe alors sur le message général.
 */
fun diagnostiquerPartielsAberrants(
    partiels: List<PartielMesure>
): List<Int> {
    if (partiels.size < 4) return emptyList()
    val fitComplet = ajusterInharmonicite(partiels)
    if (fitComplet != null && fitComplet.rmsCents <= 2.5 &&
        fitComplet.b in 0.00001..0.01
    ) {
        return emptyList()
    }
    fun prediction(n: Int, f1: Double, b: Double) =
        n * f1 * sqrt(1 + b * n * n)
    val bGraine = fitComplet?.b ?: B_GRAINE
    val f1Graine = fitComplet?.f1Hz ?: run {
        val corriges = partiels.map { p ->
            p.freqHz / (p.n * sqrt(1 + B_GRAINE * p.n * p.n))
        }.sorted()
        val m = corriges.size / 2
        if (corriges.size % 2 == 1) corriges[m]
        else (corriges[m - 1] + corriges[m]) / 2
    }
    val noyau = partiels.filter { p ->
        abs(1200 * log2(p.freqHz / prediction(p.n, f1Graine, bGraine))) <= 15.0
    }
    if (noyau.size >= 3) {
        val fitNoyau = ajusterInharmonicite(noyau)
        if (fitNoyau != null && fitNoyau.rmsCents <= 2.5 &&
            fitNoyau.b in 0.00001..0.01
        ) {
            return partiels.map { p ->
                p.n to abs(
                    1200 * log2(
                        p.freqHz / prediction(p.n, fitNoyau.f1Hz, fitNoyau.b)
                    )
                )
            }.filter { it.second > 15.0 }
                .sortedByDescending { it.second }
                .take(3)
                .map { it.first }
        }
    }
    var meilleur: Int? = null
    var meilleurRms = Double.MAX_VALUE
    for (candidat in partiels) {
        val reste = partiels.filter { it.n != candidat.n }
        val fit = ajusterInharmonicite(reste) ?: continue
        if (fit.rmsCents <= 2.5 && fit.b in 0.00001..0.01 &&
            fit.rmsCents < meilleurRms
        ) {
            meilleurRms = fit.rmsCents
            meilleur = candidat.n
        }
    }
    return meilleur?.let { listOf(it) } ?: emptyList()
}

/**
 * Courbe de B sur les 88 touches : interpolation linéaire de log B
 * entre les ancres (B varie de façon à peu près géométrique le long
 * du clavier), bornée à [1e-5, 0,008]. Extrapolations (0.9) :
 * au-dessus de la dernière ancre, plate (B y est petit et lent) ;
 * SOUS la première ancre, on prolonge la PENTE de log B du premier
 * segment — dans les graves B croît à peu près exponentiellement
 * en descendant, et l'ancienne extrapolation plate y produisait
 * des basses timides, sous l'étirement que l'oreille de Grégoire
 * met (« mon oreille étire pas mal les basses »). Tableau indexé
 * par la touche (1..88 ; l'indice 0 est inutilisé).
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
            k < triees.first().key -> {
                // Sous la première ancre : pente de log B du
                // premier segment, prolongée (0.9). Une seule
                // ancre ne donne aucune pente → plat.
                if (triees.size >= 2) {
                    val a0 = triees[0]
                    val a1 = triees[1]
                    val pente = (ln(a1.value) - ln(a0.value)) /
                        (a1.key - a0.key)
                    kotlin.math.exp(ln(a0.value) + (k - a0.key) * pente)
                } else {
                    triees.first().value
                }
            }
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

/**
 * Les ancres du parcours guidé (schéma 0.9, décidé par Grégoire
 * après ses tests terrain de la 0.7) : les la et les do, de La1
 * à Do6 — touches 13, 16, 25, 28, 37, 40, 49, 52, 61, 64.
 *
 * Pourquoi ce périmètre : les méthodes établies ne mesurent pas
 * les extrêmes (FAC : Fa3-La4-Do6 ; CyberTuner : les la) — sous
 * La1 et sur Do6, B est extrapolé (en pente dans les graves,
 * plat dans les aigus, voir courbeInharmonicite). L'ancien
 * schéma do-sol commençait à Do1, la note la moins mesurable du
 * piano (fondamental ~33 Hz), et enchaînait les ancres en
 * quintes : les partiels de la note précédente, encore en train
 * de sonner, coïncident à ~2 ¢ près avec ceux de la suivante et
 * la faisaient « mesurer » à sa place. La tierce la-do laisse
 * ~16 ¢ entre les partiels voisins — l'analyseur les distingue.
 */
val ANCRES_PARCOURS: List<Int> = listOf(
    13, 16, 25, 28, 37, 40, 49, 52, 61, 64
)

/**
 * Minimum d'ancres mesurées pour générer un accord (0.9) : 7
 * sur 10 — en proportion de l'ancien 8 sur 15, avec en plus une
 * exigence de couverture (voir raisonsGenerationImpossible) :
 * un accord calculé sans aucune ancre grave ni aiguë serait
 * deviné aux deux bouts.
 */
const val MIN_ANCRES_GENERATION = 7

/**
 * Pourquoi la génération d'un accord n'est pas (encore)
 * possible avec ces ancres — liste vide = possible. Les raisons
 * sont des phrases prêtes à afficher.
 */
fun raisonsGenerationImpossible(ancres: List<MesureAncre>): List<String> {
    val raisons = mutableListOf<String>()
    if (ancres.size < MIN_ANCRES_GENERATION) {
        raisons.add(
            "seulement ${ancres.size} ancre(s) mesurée(s) — " +
                "il en faut au moins $MIN_ANCRES_GENERATION " +
                "sur ${ANCRES_PARCOURS.size}"
        )
    }
    if (ancres.isNotEmpty()) {
        if (ancres.minOf { it.key } > 25) {
            raisons.add(
                "aucune ancre dans les graves (La2 ou plus bas) — " +
                    "la courbe des graves serait devinée"
            )
        }
        if (ancres.maxOf { it.key } < 52) {
            raisons.add(
                "aucune ancre dans les aigus (Do5 ou plus haut) — " +
                    "la courbe des aigus serait devinée"
            )
        }
    }
    return raisons
}

/**
 * Un brouillon de parcours est-il du schéma courant (0.9) ?
 * Les ancres mesurées sont des mesures de TOUCHES : un brouillon
 * dont toutes les ancres appartiennent au nouveau schéma reste
 * valable tel quel ; un brouillon qui contient une touche de
 * l'ancien schéma do-sol (4, 11, 23, 35, …) est périmé — le
 * parcours le clos franchement au lieu de le reprendre en
 * silence (voir ParcoursMesureControleur.ouvrir).
 */
fun brouillonCompatible(ancres: List<MesureAncre>): Boolean =
    ancres.all { it.key in ANCRES_PARCOURS }

// ---------------------------------------------------------------------------
// Analyseur de partiels : généralisation du PartialTracker prouvé
// (TunerDsp.kt) à la mesure SIMULTANÉE des partiels d'une note —
// ceux qui EXISTENT à sa hauteur (0.9 : seuls les partiels sous
// ~14 kHz sont visés ; dans le haut du clavier, les partiels 7-8
// dépassent le plafond utile, voire Nyquist, et les chasser
// condamnait l'ancre à ne jamais conclure). Une seule FFT (Hann
// 32768) par saut de 4096 échantillons ; pour chaque partiel,
// exactement la même mathématique que le traqueur : recherche du
// pic autour de la fréquence attendue, plancher de bruit au 30e
// percentile, confiance RSB × niveau, interpolation complexe de
// Jacobsen (δ ← −2δ), raffinement par l'avance de phase, sortie
// médiane pondérée.
//
// Les fenêtres de recherche se recentrent en continu sur un écart
// global et un B provisoire ajustés dès que des partiels sont
// mesurés : un piano très faux (écart de plusieurs dizaines de
// cents) se mesure quand même — l'écart commun est absorbé par le
// recentrage, et B ne dépend que des rapports entre partiels.
//
// PORTES D'ENTRÉE (0.9, vécu terrain de la 0.7) : l'analyseur
// n'accumule RIEN tant que deux portes ne sont pas passées —
//   · la FRAPPE : une attaque fraîche, postérieure à l'affichage
//     de l'ancre (montée nette du niveau sur la base récente) ;
//   · la HAUTEUR : le détecteur de note (coïncidence de
//     partiels, en mode grossier) entend la note CIBLE, en note
//     la plus proche — un piano très désaccordé passe, une autre
//     note (typiquement la précédente, encore en train de
//     sonner) est refusée et NOMMÉE pour l'écran (cleEntendue).
// Sans ces portes, l'ancre Sol1 de la 0.7 avait « conclu » en
// quelques secondes sur la queue du Do1 que Grégoire frappait
// encore — les ancres se suivaient en quintes et leurs partiels
// coïncident.
// ---------------------------------------------------------------------------

/**
 * Comment un partiel a été fixé pendant la mesure initiale
 * d'une ancre (règles 0.7, dictées par Grégoire après son test
 * terrain de la 0.6 sur C1).
 */
enum class FixationPartiel {
    /** Pas encore fixé : toujours en mesure. */
    AUCUNE,

    /** Fixé par les critères stricts « propre » de la 0.6. */
    PROPRE,

    /**
     * Fixé par DÉCISION du logiciel : après ~2,5 s de mesure,
     * les estimations récentes oscillaient dans une petite
     * plage bornée (la même que le seuil « propre » du
     * registre) sans jamais passer sous le seuil strict —
     * la valeur retenue est la MÉDIANE de la fenêtre récente.
     * Exemple de Grégoire : des lectures entre −12 et −13 ¢
     * depuis 2-3 secondes → on fixe −12,5, on n'attend plus.
     */
    MOYENNE
}

/** État d'un partiel pendant la capture d'une ancre (pour l'écran). */
data class EtatPartiel(
    val n: Int,
    val freqHz: Double?,
    val nbEstimations: Int,
    val confianceMoyenne: Double,
    val dispersionCents: Double,
    val propre: Boolean,
    /** Mode de fixation ; AUCUNE tant que le partiel est en mesure. */
    val fixation: FixationPartiel = FixationPartiel.AUCUNE
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

        // Fixation (règles 0.7 nº 1 et 2) : dès qu'un partiel
        // est fixé — propre ou moyenné — sa valeur est FIGÉE
        // pour cette ancre : il sort de la mesure (analyze()
        // le saute), son crochet reste affiché avec sa valeur,
        // et aucune estimation ultérieure ne peut le réviser
        // pendant cette mesure initiale. (La réévaluation des
        // partiels aura lieu pendant l'accord, dans le futur
        // mode adaptatif — pas ici.)
        var fixe = false
        var freqFixeeHz = 0.0
        var confFixee = 0.0
        var modeFixation = FixationPartiel.AUCUNE

        /** Compteur d'échantillons à la première estimation —
         *  l'horloge du délai de décision par moyenne. */
        var debutMesureEch = -1L
    }

    /**
     * Les partiels cherchés pour CETTE ancre (0.9) : ceux dont
     * la fréquence attendue (B nominal) reste sous le plafond
     * utile — au-delà, le partiel n'existe pratiquement plus
     * (énergie évanouie, micro sourd, Nyquist proche) et le
     * chercher empêchait de conclure. Exemples : Do6 (1046 Hz)
     * vise 1 à 8 (le 8e est à ~8,4 kHz) ; Do7 viserait 1 à 6 ;
     * Do8, 1 à 3 seulement.
     */
    val partielsVises: List<Int> = calculerPartielsVises()

    private val suivis: List<SuiviPartiel> =
        partielsVises.map { SuiviPartiel(it) }

    // --- Portes d'entrée (0.9) : voir l'en-tête de la classe ---
    private val detecteur = NoteDetector(sampleRate)

    /** La mesure a-t-elle commencé (frappe + bonne hauteur) ? */
    @Volatile
    var porteOuverte: Boolean = false
        private set

    /**
     * La touche que le détecteur entend pendant l'attente —
     * pour que l'écran puisse nommer ce qui sonne encore
     * (« J'entends encore La1 — joue Do2 »). Null si rien de
     * stable n'est entendu.
     */
    @Volatile
    var cleEntendue: Int? = null
        private set

    /** Nombre de fixations refusées par la porte de cohérence. */
    var rejetsCoherence: Int = 0
        private set

    /** Niveaux (dB) des derniers blocs reçus — la base de la
     *  détection de frappe (son minimum récent). */
    private val niveauxRecents = ArrayDeque<Double>()

    /** Échantillon (compteur total) de la dernière frappe vue. */
    private var derniereFrappeTotal: Long = -1

    /** Depuis quand la cible est entendue de façon stable. */
    private var adoptionCibleDepuis: Long = -1

    companion object {
        /**
         * Délai de décision par moyenne (règle 0.7 nº 2) : un
         * partiel « en mesure » depuis au moins ce nombre de
         * secondes peut être fixé à la médiane de sa fenêtre
         * récente si celle-ci oscille dans la plage bornée.
         * Grégoire : « ça fait 2-3 secondes qu'on est sur le
         * partiel » → 2,5 s.
         */
        const val DELAI_DECISION_SEC = 2.5

        /**
         * Fenêtre récente de la décision : les dernières
         * estimations retenues. Au pas d'analyse de 4096
         * échantillons à 48 kHz (≈ 85 ms), 30 estimations
         * couvrent ≈ 2,6 s — la fenêtre du délai ci-dessus.
         */
        const val FENETRE_DECISION = 30

        /** Minimum d'estimations dans la fenêtre pour décider. */
        const val MIN_ESTIMATIONS_FENETRE = 12

        /** Plafond utile des partiels visés (0.9) : ~14 kHz,
         *  nettement sous Nyquist (24 kHz à 48 kHz). */
        const val PLAFOND_PARTIEL_HZ = 14000.0

        /** B nominal pour décider quels partiels existent et
         *  pour la table du détecteur de hauteur. */
        const val B_NOMINAL = 0.0004

        /** Montée de niveau (dB) qui signe une frappe fraîche
         *  par-dessus la base récente (queue de la note
         *  précédente comprise). */
        const val SEUIL_MONTEE_FRAPPE_DB = 10.0

        /** Niveau absolu minimal (dB) d'une frappe : sous ce
         *  plancher, c'est du bruit de pièce, pas une note. */
        const val NIVEAU_MIN_FRAPPE_DB = -55.0

        /** Tolérance de la porte de cohérence (cents) entre la
         *  fréquence candidate d'un partiel et la position que
         *  prédit la série déjà fixée. Le cas vécu (Sol2, 0.7)
         *  accrochait ses partiels à +90, −84 et −145 ¢ de leur
         *  place ; une prédiction saine erre de ~10-15 ¢. */
        const val TOL_COHERENCE_CENTS = 40.0

        /** La même tolérance, élargie dans les graves profonds
         *  (ancres ≤ 16 : La1, Do2), où tout est plus brouillon. */
        const val TOL_COHERENCE_GRAVES_CENTS = 55.0

        /** Tolérance quand la série est encore jeune (moins de
         *  3 partiels fixés) : la prédiction part du nominal
         *  recentré, elle ne sert qu'à écarter le grossier. */
        const val TOL_COHERENCE_JEUNE_CENTS = 100.0
    }

    init {
        for (i in 0 until n) {
            win[i] = 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (n - 1))
        }
        // La table du détecteur de hauteur : les 88 notes au
        // tempérament égal du diapason du parcours (le
        // fondamental nominal de l'ancre est celui de SA
        // touche ; les autres s'en déduisent par demi-tons),
        // chacune avec son partiel d'écoute calculé comme pour
        // les accords générés (B nominal).
        val notesDetecteur = (1..88).map { k ->
            val f0 = f1Nominale * Math.pow(2.0, (k - cleAncre) / 12.0)
            val p = partielEcouteGenere(f0, B_NOMINAL)
            NoteBrute(k, f0, p * f0 * sqrt(1 + B_NOMINAL * p * p), p)
        }
        detecteur.setNotes(notesDetecteur)
    }

    /** Les partiels qui existent à la hauteur de cette ancre. */
    private fun calculerPartielsVises(): List<Int> {
        val vises = (1..8).filter { numero ->
            numero * f1Nominale *
                sqrt(1 + B_NOMINAL * numero * numero) < PLAFOND_PARTIEL_HZ
        }
        // Filet : une ancre très haute garderait au moins ses
        // trois premiers partiels à chercher.
        return if (vises.size >= 3) vises else (1..3).toList()
    }

    fun push(samples: FloatArray) {
        synchronized(verrou) {
            // Tant que la porte est fermée, le flux alimente le
            // détecteur de hauteur — c'est lui qui dira quelle
            // note sonne et si la cible est là.
            if (!porteOuverte) detecteur.push(samples)
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
            if (!porteOuverte) majPorte(samples)
            if (filled >= n && sinceHop >= hop) {
                sinceHop = 0
                analyze()
            }
        }
    }

    /**
     * Portes d'entrée (0.9) — appelée à chaque bloc tant que la
     * mesure n'a pas commencé. Deux conditions, dans l'ordre où
     * la physique les donne :
     *
     *  1. FRAPPE : le niveau du bloc dépasse le minimum des
     *     ~12 derniers blocs (la base — silence de la pièce ou
     *     queue décroissante de la note précédente) d'au moins
     *     SEUIL_MONTEE_FRAPPE_DB, à un niveau absolu audible.
     *     La base suit la décroissance de la queue : une frappe
     *     nette la dépasse toujours, même sans silence entre
     *     les ancres.
     *  2. HAUTEUR : le détecteur entend la note CIBLE de façon
     *     stable (3 analyses concordantes — son critère
     *     d'adoption), en note la plus proche : un piano très
     *     désaccordé passe (ses partiels restent plus près de
     *     la cible que des voisines), une AUTRE note est
     *     refusée — et nommée via cleEntendue.
     *
     * La porte ouvre quand la cible est entendue ET (frappe
     * fraîche ≤ 2 s, OU adoption soutenue ≥ 1 s — le cas où la
     * queue précédente, encore forte, masque la montée de
     * niveau mais où la cible domine déjà durablement le
     * spectre ; une queue étrangère ne peut pas produire cette
     * adoption-là, ses partiels ne coïncident pas).
     */
    private fun majPorte(samples: FloatArray) {
        // Niveau du bloc entrant (RMS, sous-échantillonné ×4).
        var somme = 0.0
        var i = 0
        while (i < samples.size) {
            val v = samples[i].toDouble()
            somme += v * v
            i += 4
        }
        val nb = max(1, samples.size / 4)
        val niveauDb = 20 * kotlin.math.log10(sqrt(somme / nb) + 1e-9)
        niveauxRecents.addLast(niveauDb)
        while (niveauxRecents.size > 12) niveauxRecents.removeFirst()
        val base = niveauxRecents.min()
        if (niveauDb >= base + SEUIL_MONTEE_FRAPPE_DB &&
            niveauDb >= NIVEAU_MIN_FRAPPE_DB
        ) {
            derniereFrappeTotal = total
        }
        // Ce qui sonne, pour l'écran : le candidat courant du
        // détecteur s'il est stable, sinon sa dernière adoption.
        val cand = detecteur.candKey
        cleEntendue = when {
            cand != null && detecteur.candCount >= 2 -> cand
            else -> detecteur.adoptedKey
        }
        val cibleStable = cand == cleAncre && detecteur.candCount >= 3
        if (cibleStable) {
            if (adoptionCibleDepuis < 0) adoptionCibleDepuis = total
            val frappeRecente = derniereFrappeTotal >= 0 &&
                total - derniereFrappeTotal <= (2.0 * sampleRate).toLong()
            val adoptionSoutenue =
                total - adoptionCibleDepuis >= sampleRate.toLong()
            if (frappeRecente || adoptionSoutenue) {
                porteOuverte = true
                cleEntendue = cleAncre
            }
        } else {
            adoptionCibleDepuis = -1
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
        // Porte fermée (0.9) : on n'accumule rien — ni
        // historique ni estimation. La queue de la note
        // précédente peut bien remplir le tampon, elle ne
        // deviendra jamais une « mesure » de cette ancre.
        if (!porteOuverte) return
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
            // Partiel déjà fixé pour cette ancre : il est sorti
            // de la mesure (règle 0.7 nº 1) — on ne le retouche
            // plus, ni son historique ni sa valeur.
            if (suivi.fixe) continue
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
                    if (suivi.debutMesureEch < 0) suivi.debutMesureEch = total
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
     *
     * (0.9) Dès que ≥ 3 partiels sont FIXÉS, le recentrage ne se
     * fait plus que sur eux : ce sont les seules valeurs
     * vérifiées (porte de cohérence passée). En 0.7, les
     * médianes libres — y compris celles accrochées à la série
     * d'une corde voisine — tiraient le recentrage vers elles,
     * et les fenêtres finissaient centrées sur la mauvaise
     * série : tout le monde se « confirmait » mutuellement.
     */
    private fun recentrer() {
        val fixes = suivis.filter { it.fixe }
        if (fixes.size >= 3) {
            val fit = ajusterInharmonicite(
                fixes.map {
                    PartielMesure(it.numero, it.freqFixeeHz, it.confFixee)
                }
            )
            if (fit != null && fit.b in 0.00001..0.01) {
                bCentre = fit.b
                ecartCentreCents = 1200 * log2(fit.f1Hz / f1Nominale)
                return
            }
        }
        val medianes = suivis.mapNotNull { suivi ->
            when {
                // Un partiel fixé recentre les autres à sa
                // valeur figée — la plus fiable qu'on ait.
                suivi.fixe ->
                    PartielMesure(suivi.numero, suivi.freqFixeeHz, suivi.confFixee)
                suivi.estimations.size < 3 -> null
                else -> {
                    val triees = suivi.estimations.map { it.first }.sorted()
                    val med = triees[triees.size / 2]
                    val conf = suivi.estimations.sumOf { it.second } /
                        suivi.estimations.size
                    PartielMesure(suivi.numero, med, conf)
                }
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

    /** Fige la valeur d'un partiel pour cette ancre (0.7). */
    private fun fixer(
        suivi: SuiviPartiel,
        freqHz: Double,
        confiance: Double,
        mode: FixationPartiel
    ) {
        suivi.fixe = true
        suivi.freqFixeeHz = freqHz
        suivi.confFixee = confiance
        suivi.modeFixation = mode
    }

    /**
     * Statistiques d'un partiel sur les estimations accumulées —
     * et, depuis la 0.7, sa FIXATION éventuelle. Deux chemins :
     *
     *  1. « propre » (critères 0.6, inchangés) → fixation
     *     PROPRE à la médiane de toutes les estimations.
     *  2. Sinon, si le partiel est en mesure depuis au moins
     *     DELAI_DECISION_SEC et que sa fenêtre récente oscille
     *     dans la plage bornée (voir essayerFixationMoyenne)
     *     → fixation MOYENNE à la médiane de la fenêtre.
     *
     * Un partiel déjà fixé retourne son état figé tel quel :
     * plus rien ne le réviser pendant cette mesure initiale.
     */
    private fun statistiques(suivi: SuiviPartiel): EtatPartiel {
        if (suivi.fixe) {
            return EtatPartiel(
                suivi.numero, suivi.freqFixeeHz, suivi.estimations.size,
                suivi.confFixee, 0.0, true, suivi.modeFixation
            )
        }
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
        // (Précision 0.7 de Grégoire : les basses à l'oreille
        // sont son REPLI, pas sa préférence — ces seuils sont
        // au niveau qui fait conclure, ni plus laxistes ni plus
        // stricts ; il compte se servir des valeurs si le
        // logiciel performe.)
        val seuilEstimations = if (cleAncre >= 76 || cleAncre <= 16) 3 else 5
        val dispersionMax = when {
            med < 80.0 -> 6.0
            med < 160.0 -> 3.5
            else -> 2.0
        }
        val confianceMin = if (med < 80.0) 0.22 else 0.30
        val propre = est.size >= seuilEstimations &&
            confMoy >= confianceMin && dispersion <= dispersionMax
        if (propre) {
            // Porte de cohérence (0.9) AVANT de figer : un
            // partiel « propre » en dispersion peut être propre
            // sur le MAUVAIS pic (voir coherent()).
            if (!coherent(suivi.numero, med)) {
                return rejeterIncoherent(suivi)
            }
            fixer(suivi, med, confMoy, FixationPartiel.PROPRE)
            return EtatPartiel(
                suivi.numero, med, est.size, confMoy, dispersion,
                true, FixationPartiel.PROPRE
            )
        }
        // Pas « propre » au sens strict : la décision par
        // moyenne peut encore le fixer (règle 0.7 nº 2) — même
        // porte de cohérence avant de figer sa médiane.
        val candidat = candidatMoyenne(suivi, dispersionMax, confianceMin)
        if (candidat != null) {
            if (!coherent(suivi.numero, candidat.first)) {
                return rejeterIncoherent(suivi)
            }
            fixer(
                suivi, candidat.first, candidat.second,
                FixationPartiel.MOYENNE
            )
            return EtatPartiel(
                suivi.numero, candidat.first, est.size,
                candidat.second, candidat.third,
                true, FixationPartiel.MOYENNE
            )
        }
        return EtatPartiel(suivi.numero, med, est.size, confMoy, dispersion, false)
    }

    /**
     * Porte de cohérence à la fixation (0.9) — le cas vécu : un
     * Sol2 dont les partiels 5, 7 et 8 s'étaient accrochés aux
     * partiels du Do2 précédent (+90, −84, −145 ¢ de leur vraie
     * place), s'étaient verrouillés un à un, puis avaient été
     * rejetés en bloc par l'ajustement final. Désormais, un
     * partiel ne se fixe que si sa fréquence est COMPATIBLE
     * avec la série en construction :
     *
     *  - dès que ≥ 3 partiels sont fixés, un ajustement de
     *    Young provisoire sur eux prédit la position du
     *    candidat ; écart toléré : TOL_COHERENCE_CENTS (55 ¢
     *    dans les graves profonds) — une prédiction saine erre
     *    de ~10-15 ¢, le mauvais pic du cas vécu errait de 90
     *    à 145 ¢ ;
     *  - série encore jeune : la prédiction part du nominal
     *    recentré (centreAttendu) avec une tolérance large —
     *    elle n'écarte que le grossier, le temps que la série
     *    existe.
     *
     * Si l'ajustement provisoire est impossible (partiels
     * fixés eux-mêmes incohérents — ne devrait pas arriver,
     * ils ont passé cette porte), on ne bloque pas : le verdict
     * final et le diagnostic d'aberrants restent le filet.
     */
    private fun coherent(numero: Int, freq: Double): Boolean {
        val fixes = suivis.filter { it.fixe && it.numero != numero }
        if (fixes.size >= 3) {
            val fit = ajusterInharmonicite(
                fixes.map {
                    PartielMesure(it.numero, it.freqFixeeHz, it.confFixee)
                }
            )
            if (fit != null) {
                val pred = numero * fit.f1Hz *
                    sqrt(1 + fit.b * numero * numero)
                val ecart = abs(1200 * log2(freq / pred))
                val tol = if (cleAncre <= 16) {
                    TOL_COHERENCE_GRAVES_CENTS
                } else {
                    TOL_COHERENCE_CENTS
                }
                return ecart <= tol
            }
        }
        val pred = centreAttendu(numero)
        return abs(1200 * log2(freq / pred)) <= TOL_COHERENCE_JEUNE_CENTS
    }

    /**
     * Un candidat refusé par la porte de cohérence : ses
     * estimations sont empoisonnées (elles médianent sur le
     * mauvais pic) — on les jette et la mesure de CE partiel
     * repart de zéro, fenêtres recentrées sur la série fixée.
     * Les autres partiels ne sont pas touchés.
     */
    private fun rejeterIncoherent(suivi: SuiviPartiel): EtatPartiel {
        rejetsCoherence++
        suivi.estimations.clear()
        suivi.hist.clear()
        suivi.debutMesureEch = -1
        suivi.lastBin = -1
        suivi.phaseOk = 0
        return EtatPartiel(suivi.numero, null, 0, 0.0, 999.0, false)
    }

    /**
     * Décision par moyenne (règle 0.7 nº 2, exemple de
     * Grégoire : des lectures qui oscillent entre −12 et
     * −13 ¢ depuis 2-3 secondes → « boum, −12,5 »).
     *
     * Conditions, toutes requises :
     *  - le partiel est en mesure depuis ≥ DELAI_DECISION_SEC
     *    (2,5 s, comptées depuis sa première estimation) ;
     *  - la fenêtre récente (FENETRE_DECISION dernières
     *    estimations, ≥ MIN_ESTIMATIONS_FENETRE) a une
     *    dispersion p90−p10 ≤ dispersionMax — la « petite
     *    plage » est donc EXACTEMENT le seuil « propre » du
     *    registre (6,0 ¢ sous 80 Hz, 3,5 ¢ sous 160 Hz,
     *    2,0 ¢ au-delà) : on ne relâche aucune borne, on juge
     *    la stabilité sur le RÉCENT plutôt que sur tout
     *    l'historique, dont le début (attaque, chasse du
     *    recentrage) reste bruyant longtemps ;
     *  - la confiance moyenne de la fenêtre atteint le seuil
     *    du registre (0,22 sous 80 Hz, 0,30 au-delà).
     *
     * La valeur candidate est la MÉDIANE des fréquences de la
     * fenêtre (pas la moyenne arithmétique) : robuste aux
     * lectures aberrantes qui subsistent dans la fenêtre —
     * dans les graves, où Grégoire compte se servir de ces
     * valeurs, c'est le choix qui reste digne de confiance.
     * Retourne (fréquence, confiance, dispersion de la
     * fenêtre), ou null si on continue de mesurer. La fixation
     * elle-même est faite par statistiques(), après la porte
     * de cohérence (0.9) — cette fonction ne fige plus rien.
     */
    private fun candidatMoyenne(
        suivi: SuiviPartiel,
        dispersionMax: Double,
        confianceMin: Double
    ): Triple<Double, Double, Double>? {
        if (suivi.debutMesureEch < 0) return null
        val dureeSec = (total - suivi.debutMesureEch).toDouble() / sampleRate
        if (dureeSec < DELAI_DECISION_SEC) return null
        val fenetre = suivi.estimations.takeLast(FENETRE_DECISION)
        if (fenetre.size < MIN_ESTIMATIONS_FENETRE) return null
        val freqs = fenetre.map { it.first }.sorted()
        val med = freqs[freqs.size / 2]
        val cents = fenetre.map { 1200 * log2(it.first / med) }.sorted()
        val p10 = cents[(cents.size * 0.1).toInt()]
        val p90 = cents[min(cents.size - 1, (cents.size * 0.9).toInt())]
        val dispersionFenetre = p90 - p10
        val confFenetre = fenetre.sumOf { it.second } / fenetre.size
        if (dispersionFenetre > dispersionMax || confFenetre < confianceMin) {
            return null
        }
        return Triple(med, confFenetre, dispersionFenetre)
    }

    /** État courant des 8 partiels (pour l'écran du parcours). */
    fun etatPartiels(): List<EtatPartiel> =
        synchronized(verrou) { suivis.map { statistiques(it) } }

    /** Partiels jugés propres, prêts pour l'ajustement final. */
    fun partielsPropres(): List<PartielMesure> =
        etatPartiels().filter { it.propre && it.freqHz != null }
            .map { PartielMesure(it.n, it.freqHz!!, it.confianceMoyenne) }

    /**
     * Déverrouille des partiels fixés (0.9) : leur mesure
     * repart de zéro, les autres restent figés. C'est le
     * remède ciblé de l'échec d'ajustement réparable —
     * l'écran nomme le partiel accroché au mauvais pic et
     * propose de ne refaire que lui (voir evaluer()).
     */
    fun defixerPartiels(numeros: Collection<Int>) {
        synchronized(verrou) {
            for (suivi in suivis) {
                if (suivi.fixe && suivi.numero in numeros) {
                    suivi.fixe = false
                    suivi.modeFixation = FixationPartiel.AUCUNE
                    suivi.estimations.clear()
                    suivi.hist.clear()
                    suivi.debutMesureEch = -1
                    suivi.lastBin = -1
                    suivi.phaseOk = 0
                }
            }
        }
    }

    /** Tous les partiels visés sont-ils fixés ? */
    fun tousPartielsFixes(): Boolean =
        synchronized(verrou) { suivis.all { it.fixe } }

    sealed class Verdict {
        /** Mesure solide : elle avance toute seule à l'ancre suivante. */
        data class Pret(val mesure: MesureAncre) : Verdict()

        /** Mesure exploitable mais faible : à Grégoire de l'accepter. */
        data class Faible(val mesure: MesureAncre, val raisons: List<String>) : Verdict()

        /**
         * Pas encore (ou pas du tout) mesurable en l'état.
         * [aberrants] : les partiels fixés identifiés comme
         * accrochés au mauvais pic quand l'ajustement échoue
         * (0.9) — l'écran les nomme et propose de les refaire.
         */
        data class Insuffisant(
            val raisons: List<String>,
            val nbPropres: Int,
            val aberrants: List<Int> = emptyList()
        ) : Verdict()
    }

    /**
     * Évalue la capture en cours.
     *
     * RECALIBRAGE 0.9 — sur le vrai piano, les quatre premières
     * ancres de la 0.7 avaient TOUTES fini en avertissement
     * jaune : un jaune systématique ne signale plus rien. Deux
     * coupables : le seuil Pret de RMS à 1,5 ¢ (un ajustement
     * réel, sur de vraies cordes qui ne suivent pas Young au
     * dixième près, vit plutôt à 2-3 ¢ — c'est une mesure
     * SAINE) et la règle « aucun partiel haut (5+) » qui
     * teintait des ancres dont l'ajustement était par ailleurs
     * solide. Désormais le verdict est gouverné par la qualité
     * réelle de l'ajustement :
     *
     *  - Pret : ≥ minRetenusPret partiels retenus (4 en
     *    général, 3 dans les graves profonds, proportionné au
     *    nombre de partiels visés), un partiel retenu ≥ 4 quand
     *    les visés le permettent (du levier sur B), RMS ≤ 2,5 ¢
     *    (3,5 ¢ dans les graves profonds), B dans [2e-5, 0,008].
     *  - Faible (le jaune, devenu rare et précis) : exploitable
     *    (≥ 3 retenus, RMS ≤ 5 ¢ — 6 ¢ graves —, B plausible)
     *    mais un des critères Pret manque, nommé dans raisons.
     *  - Les incidents informatifs ne teintent plus : un
     *    partiel fixé par décision (moyenné), un partiel 1
     *    absent (règle 0.7 nº 3 : jamais requis), un partiel
     *    écarté par le rejet d'aberrants de l'ajustement quand
     *    le reste tient — rien de tout ça n'est une faiblesse
     *    de l'ancre.
     *  - Les seuils de QUALITÉ des valeurs (fixation propre /
     *    moyennée, dispersion, confiance) ne sont PAS relâchés :
     *    seul le jugement porté sur l'ajustement change.
     *
     * ÉCHEC D'AJUSTEMENT RÉPARABLE (0.9) : si l'ajustement est
     * impossible ou inexploitable alors que des partiels sont
     * fixés, on diagnostique les aberrants (partiels rejetés
     * par l'ajustement, sinon retrait unitaire —
     * diagnostiquerPartielsAberrants) et le verdict les NOMME :
     * l'écran proposera « Refaire ce partiel » plutôt que le
     * « 8 propres sur 8, mais insuffisante » absurde de la 0.7.
     */
    fun evaluer(nom: String): Verdict =
        synchronized(verrou) { evaluerInterne(nom) }

    private fun evaluerInterne(nom: String): Verdict {
        val propres = partielsPropres()
        val fit = ajusterInharmonicite(propres)
        if (fit == null) {
            val raisons = if (propres.size >= 3) {
                listOf(
                    "les ${propres.size} partiels fixés ne tiennent " +
                        "pas ensemble : aucun ajustement " +
                        "d'inharmonicité ne les traverse " +
                        "(série incohérente)"
                )
            } else {
                listOf(
                    "série de partiels inexploitable pour l'instant " +
                        "(${propres.size} partiel(s) propre(s) " +
                        "sur ${partielsVises.size})"
                )
            }
            return Verdict.Insuffisant(
                raisons, propres.size,
                diagnostiquerPartielsAberrants(propres)
            )
        }
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
        val minRetenusPret = when {
            gravesProfonds -> 3
            partielsVises.size >= 7 -> 4
            partielsVises.size >= 4 -> 3
            else -> 2
        }
        val rmsMaxPret = if (gravesProfonds) 3.5 else 2.5
        val rmsMaxFaible = if (gravesProfonds) 6.0 else 5.0
        val raisonsFaible = mutableListOf<String>()
        if (retenus.size < minRetenusPret) {
            raisonsFaible.add(
                "${retenus.size} partiels retenus (on en vise $minRetenusPret+)"
            )
        }
        if (partielsVises.size >= 5 && retenus.maxOf { it.n } < 4) {
            raisonsFaible.add(
                "partiels retenus tous bas (le plus haut : " +
                    "nº ${retenus.maxOf { it.n }}) — peu de levier sur B"
            )
        }
        if (fit.rmsCents > rmsMaxPret) {
            raisonsFaible.add("résidus élevés (${fmt1(fit.rmsCents)} ¢)")
        }
        if (fit.b !in 0.00002..0.008) {
            raisonsFaible.add("B hors plage plausible")
        }
        if (raisonsFaible.isEmpty()) return Verdict.Pret(mesure)
        val exploitable = retenus.size >= 3 &&
            fit.rmsCents <= rmsMaxFaible && fit.b in 0.00001..0.01
        return if (exploitable) {
            Verdict.Faible(mesure, raisonsFaible)
        } else {
            // Les partiels que l'ajustement a rejetés sont les
            // premiers suspects ; sinon, diagnostic par retrait.
            val rejetes = propres
                .filter { p -> retenus.none { it.n == p.n } }
                .map { it.n }
            val aberrants = rejetes.ifEmpty {
                diagnostiquerPartielsAberrants(propres)
            }
            Verdict.Insuffisant(raisonsFaible, propres.size, aberrants)
        }
    }

    private fun fmt1(v: Double): String =
        String.format(java.util.Locale.ROOT, "%.1f", v)
}
