package ca.gregoirepainchaud.pixelbridge.gps

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Logique pure du suivi de trajet natif (Gestion 0.16, chantier
// GPS d'arrière-plan — décision de Grégoire, 2026-11-18 au soir).
//
// La machine à états vit ici, sans Android : le service
// (GpsService) ne fait que brancher le monde réel dessus —
// capteur de mouvement, Bluetooth de l'auto, LocationManager —
// et exécuter les actions qu'elle rend. Même machine que le
// suivi web (app.js de Gestion : veille → conduite → pause →
// veille), avec les décisions propres au natif :
//
// - DÉCLENCHEMENT EN DEUX TEMPS : en VEILLE, aucun relevé GPS.
//   Un réveil (mouvement significatif, Bluetooth de l'auto)
//   ouvre une VÉRIFICATION : le GPS écoute
//   un court moment, mais rien n'est enregistré. La capture ne
//   s'engage que si la conduite se QUALIFIE : vitesse soutenue
//   ≥ 20 km/h (seuil fixé par Grégoire — il ne jog pas à
//   20 km/h : la marche et les déplacements à pied retombent
//   en dormance sans aucun point).
// - Le Bluetooth de l'auto est un signal de réveil FORT (il
//   allonge la fenêtre de vérification) mais ne décide jamais
//   seul : la vitesse reste le décideur final — il peut
//   conduire une autre auto.
// - CONDUITE : un point aux ~30 s (ou dès 75 m parcourus),
//   comme le web. Immobile 180 s → PAUSE (un point/minute,
//   la session reste ouverte — embouteillage). Immobile
//   600 s au total → VEILLE : tout s'arrête, la file part,
//   dormance complète jusqu'au prochain réveil événementiel.
//   Aucun minuteur ne tourne en VEILLE.
// ---------------------------------------------------------------------------

/** Un relevé de position, tel que le service le reçoit. */
data class FixGps(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    /** Précision en mètres ; null si le relevé n'en donne pas. */
    val precisionM: Double?,
    /** Vitesse en m/s ; null si le relevé n'en donne pas. */
    val vitesseMps: Double?
)

enum class EtatGps { VEILLE, VERIFICATION, CONDUITE, PAUSE }

enum class SourceReveil { MOUVEMENT, BLUETOOTH }

/** Ce que le service doit faire, décidé par la machine. */
sealed class ActionGps {
    /** Ouvrir l'écoute des positions (vérification ou conduite). */
    object DemanderPositions : ActionGps()

    /** Fermer l'écoute des positions (retour en dormance). */
    object ArreterPositions : ActionGps()

    /** Conserver ce relevé comme point de trajet. */
    data class Enregistrer(val fix: FixGps) : ActionGps()

    /** Tenter l'envoi des points en file. */
    object Vidanger : ActionGps()
}

class GpsLogique {

    var etat: EtatGps = EtatGps.VEILLE
        private set

    private var debutVerifMs = 0L
    private var verifForte = false
    private var serieRapide = 0
    private var serieMobile = 0
    private var dernierMouvementMs = 0L
    private var dernierPoint: FixGps? = null

    /** Dernier relevé assez précis pour dériver une vitesse. */
    private var dernierFixPrecis: FixGps? = null

    /** Vrai quand le natif émet (ou va émettre) des points :
     *  c'est l'état que la page web consulte pour se retirer. */
    val enEmission: Boolean
        get() = etat == EtatGps.CONDUITE || etat == EtatGps.PAUSE

    // ------------------------------------------------------------
    // Réveils
    // ------------------------------------------------------------

    /**
     * Un événement extérieur (mouvement significatif, Bluetooth
     * de l'auto) : en VEILLE, il ouvre une vérification —
     * jamais la capture directement. Un signal fort (Bluetooth
     * de l'auto) allonge la fenêtre.
     */
    fun surReveil(
        nowMs: Long, source: SourceReveil, signalFort: Boolean
    ): List<ActionGps> {
        if (etat == EtatGps.VEILLE) {
            etat = EtatGps.VERIFICATION
            debutVerifMs = nowMs
            verifForte = signalFort
            serieRapide = 0
            dernierFixPrecis = null
            return listOf(ActionGps.DemanderPositions)
        }
        if (etat == EtatGps.VERIFICATION && signalFort) {
            verifForte = true
        }
        return emptyList()
    }

    /**
     * Connexion/déconnexion du Bluetooth de l'auto. Connecté :
     * un réveil fort. Déconnecté : rien — la vitesse décide
     * seule de la suite (il peut conduire une autre auto, ou
     * le Bluetooth peut décrocher en pleine route).
     */
    fun surBluetoothAuto(
        connectee: Boolean, nowMs: Long, signalFort: Boolean
    ): List<ActionGps> {
        if (!connectee) return emptyList()
        return surReveil(nowMs, SourceReveil.BLUETOOTH, signalFort)
    }

    // ------------------------------------------------------------
    // Relevés
    // ------------------------------------------------------------

    fun surFix(fix: FixGps): List<ActionGps> {
        // Porte d'entrée, comme le web : un relevé trop
        // imprécis ne sert à rien, même pas à la vitesse.
        val prec = fix.precisionM
        if (prec != null && prec > PRECISION_INTAKE_MAX_M) {
            return emptyList()
        }
        val actions = ArrayList<ActionGps>()
        actions += verifierEcheances(fix.tsMs)
        if (etat == EtatGps.VEILLE) return actions

        val v = vitesseEffective(fix)
        if (prec != null && prec <= PRECISION_VITESSE_MAX_M) {
            dernierFixPrecis = fix
        }

        when (etat) {
            EtatGps.VERIFICATION -> {
                if (v != null) {
                    if (v >= SEUIL_CONDUITE_MPS) {
                        serieRapide++
                        if (serieRapide >= 2) {
                            entrerConduite(fix)
                            actions += ActionGps.Enregistrer(fix)
                        }
                    } else {
                        serieRapide = 0
                    }
                }
            }
            EtatGps.CONDUITE -> {
                if (v != null && v >= SEUIL_MOBILE_MPS) {
                    dernierMouvementMs = fix.tsMs
                }
                enregistrerSiDu(fix)?.let { actions += it }
            }
            EtatGps.PAUSE -> {
                if (v != null && v >= SEUIL_MOBILE_MPS) {
                    dernierMouvementMs = fix.tsMs
                    serieMobile++
                } else if (v != null) {
                    serieMobile = 0
                }
                if (serieMobile >= 2) {
                    // Le trafic repart : retour en conduite,
                    // le relevé courant suit la règle normale.
                    etat = EtatGps.CONDUITE
                    serieMobile = 0
                    enregistrerSiDu(fix)?.let { actions += it }
                } else if (pointEnregistrable(fix) &&
                    fix.tsMs - (dernierPoint?.tsMs ?: 0L) >=
                    CADENCE_POINT_PAUSE_MS
                ) {
                    dernierPoint = fix
                    actions += ActionGps.Enregistrer(fix)
                }
            }
            EtatGps.VEILLE -> Unit
        }
        return actions
    }

    // ------------------------------------------------------------
    // Échéances (le service appelle surTemps aux ~5 s hors VEILLE)
    // ------------------------------------------------------------

    fun surTemps(nowMs: Long): List<ActionGps> = verifierEcheances(nowMs)

    private fun verifierEcheances(nowMs: Long): List<ActionGps> {
        when (etat) {
            EtatGps.VERIFICATION -> {
                val fenetre =
                    if (verifForte) FENETRE_VERIF_FORTE_MS
                    else FENETRE_VERIF_MS
                if (nowMs - debutVerifMs >= fenetre) {
                    retourVeille()
                    return listOf(ActionGps.ArreterPositions)
                }
            }
            EtatGps.CONDUITE, EtatGps.PAUSE -> {
                if (dernierMouvementMs > 0L) {
                    val immobile = nowMs - dernierMouvementMs
                    if (immobile >= FIN_APRES_MS) {
                        retourVeille()
                        return listOf(
                            ActionGps.ArreterPositions,
                            ActionGps.Vidanger
                        )
                    }
                    if (etat == EtatGps.CONDUITE &&
                        immobile >= PAUSE_APRES_MS
                    ) {
                        etat = EtatGps.PAUSE
                        serieMobile = 0
                    }
                }
            }
            EtatGps.VEILLE -> Unit
        }
        return emptyList()
    }

    // ------------------------------------------------------------
    // Internes
    // ------------------------------------------------------------

    private fun entrerConduite(fix: FixGps) {
        etat = EtatGps.CONDUITE
        serieRapide = 0
        serieMobile = 0
        dernierMouvementMs = fix.tsMs
        dernierPoint = fix
    }

    private fun retourVeille() {
        etat = EtatGps.VEILLE
        serieRapide = 0
        serieMobile = 0
        dernierMouvementMs = 0L
        dernierPoint = null
        dernierFixPrecis = null
        verifForte = false
    }

    /** Un point ne se conserve que s'il est assez précis
     *  (le serveur rejette > 100 m ; le web filtre à 65 m). */
    private fun pointEnregistrable(fix: FixGps): Boolean {
        val p = fix.precisionM ?: return false
        return p <= PRECISION_POINT_MAX_M
    }

    /** Règle de conduite : un point aux ~30 s, ou dès 75 m. */
    private fun enregistrerSiDu(fix: FixGps): ActionGps.Enregistrer? {
        if (!pointEnregistrable(fix)) return null
        val d = dernierPoint
        val du = d == null ||
            distanceM(d.lat, d.lon, fix.lat, fix.lon) >= DIST_POINT_MIN_M ||
            fix.tsMs - d.tsMs >= CADENCE_POINT_MS
        if (!du) return null
        dernierPoint = fix
        return ActionGps.Enregistrer(fix)
    }

    /**
     * Vitesse d'un relevé : celle qu'il porte, sinon distance /
     * temps depuis le dernier relevé précis (≥ 5 s d'écart) —
     * la même dérivation que le suivi web.
     */
    private fun vitesseEffective(fix: FixGps): Double? {
        val portee = fix.vitesseMps
        if (portee != null && portee >= 0) return portee
        val prec = dernierFixPrecis ?: return null
        val precPrecision = prec.precisionM ?: return null
        val fixPrecision = fix.precisionM ?: return null
        if (precPrecision > PRECISION_VITESSE_MAX_M ||
            fixPrecision > PRECISION_VITESSE_MAX_M
        ) {
            return null
        }
        val dtMs = fix.tsMs - prec.tsMs
        if (dtMs < 5_000L) return null
        return distanceM(prec.lat, prec.lon, fix.lat, fix.lon) /
            (dtMs / 1000.0)
    }

    companion object {
        /** Classe Bluetooth « audio d'auto » (valeur de
         *  BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
         *  recopiée en entier pour garder cette logique pure,
         *  sans Android). */
        const val CLASSE_AUDIO_AUTO = 1056

        /**
         * Un appareil Bluetooth est-il « de type auto » — le
         * seul genre dont la connexion a le droit d'ouvrir une
         * vérification GPS (0.18) ? Oui si c'est l'auto apprise
         * (adresse retenue d'une conduite précédente), si son
         * nom évoque l'auto (Subaru, smartbox), ou si sa classe
         * Bluetooth est l'audio d'auto. Écouteurs, montres et
         * enceintes ne réveillent plus le GPS : le capteur de
         * mouvement reste la voie générale de réveil.
         */
        fun estAppareilAuto(
            nom: String?, adresse: String,
            adresseApprise: String?, classeAppareil: Int?
        ): Boolean {
            if (!adresseApprise.isNullOrEmpty() &&
                adresseApprise == adresse
            ) {
                return true
            }
            val n = nom?.lowercase().orEmpty()
            if (n.contains("subaru") || n.contains("smartbox")) {
                return true
            }
            return classeAppareil == CLASSE_AUDIO_AUTO
        }

        /** 20 km/h — seuil de qualification fixé par Grégoire. */
        const val SEUIL_CONDUITE_MPS = 20.0 / 3.6

        /** 5 km/h — en deçà, le véhicule est immobile. */
        const val SEUIL_MOBILE_MPS = 5.0 / 3.6

        /** Immobilité avant la pause : 180 s (comme le web). */
        const val PAUSE_APRES_MS = 180_000L

        /** Immobilité totale avant la veille : 600 s (web). */
        const val FIN_APRES_MS = 600_000L

        /** Fenêtre de vérification après un réveil ordinaire. */
        const val FENETRE_VERIF_MS = 90_000L

        /** Fenêtre après un signal fort (Bluetooth de l'auto). */
        const val FENETRE_VERIF_FORTE_MS = 150_000L

        /** Au-delà, un relevé est écarté dès l'entrée (web). */
        const val PRECISION_INTAKE_MAX_M = 200.0

        /** Précision exigée pour conserver un point (web). */
        const val PRECISION_POINT_MAX_M = 65.0

        /** Précision exigée pour dériver une vitesse. */
        const val PRECISION_VITESSE_MAX_M = 100.0

        /** Distance qui force un point avant la cadence. */
        const val DIST_POINT_MIN_M = 75.0

        /** Cadence des points en conduite : ~30 s (web). */
        const val CADENCE_POINT_MS = 30_000L

        /** Cadence des points en pause : 1 minute (web). */
        const val CADENCE_POINT_PAUSE_MS = 60_000L

        /** Distance haversine en mètres (la même que le web). */
        fun distanceM(
            lat1: Double, lon1: Double, lat2: Double, lon2: Double
        ): Double {
            val r = 6_371_000.0
            val rad = Math.PI / 180.0
            val dLat = (lat2 - lat1) * rad
            val dLon = (lon2 - lon1) * rad
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * rad) * cos(lat2 * rad) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(min(1.0, sqrt(h)))
        }
    }
}
