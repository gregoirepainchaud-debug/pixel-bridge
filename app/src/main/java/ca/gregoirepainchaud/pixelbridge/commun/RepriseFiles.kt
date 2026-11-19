package ca.gregoirepainchaud.pixelbridge.commun

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.HandlerThread

// ---------------------------------------------------------------------------
// Reprise bornée des vidanges de files (0.19, suite de la
// critique post-0.18 §3) : la GardeVidange datait le frein
// d'après-échec, mais rien ne re-déclenchait la vidange à
// son expiration — un envoi pouvait attendre le prochain
// événement sans rapport (réouverture, autre dépôt) dans
// une app restée ouverte. Ce planificateur pose la relance
// qui manquait, sous la contrainte d'énergie de Grégoire :
// AUCUN minuteur périodique permanent.
//
// Mécanique :
// - Chaque file (PontFile runs et audio, GpsFichier,
//   Scans), à la fin de sa vidange gardée, appelle
//   planifier() avec sa garde, son test « non vide » et sa
//   vidange. Si la garde a armé un frein (échec réseau) et
//   qu'il reste du cargo, UNE relance unique est posée à
//   l'expiration du frein, sur un fil d'arrière-plan dédié.
// - La relance rejoue la vidange ; si elle échoue encore,
//   la vidange elle-même replanifie à l'échelon suivant
//   (30 s → 2 min → 10 min, échelons de GardeVidange).
//   Après le troisième échec, la garde ne propose plus de
//   délai : plus rien n'est posé, les événements naturels
//   reprennent la main.
// - Tant qu'une relance attend, un rappel de connectivité
//   est enregistré : dès que le réseau revient, les freins
//   des files en attente tombent (surConnectiviteRetablie)
//   et leurs vidanges partent immédiatement.
// - Le fil et le rappel n'existent QUE pendant qu'une
//   relance attend : dès qu'il n'en reste aucune, le fil
//   s'arrête et le rappel se désenregistre. File vide ou
//   app au repos = zéro minuteur, zéro rappel, zéro réveil.
//   Si le processus meurt avant la relance, le cargo — des
//   fichiers sur disque — repartira au prochain événement
//   naturel, comme avant 0.19 : aucune perte, au pire le
//   délai d'avant.
// ---------------------------------------------------------------------------

object RepriseFiles {

    /** Une file participante : sa garde, comment savoir
     *  s'il lui reste du cargo, comment la vidanger. Les
     *  fermetures capturent le contexte d'application. */
    private class Inscrite(
        val nonVide: () -> Boolean,
        val vider: () -> Unit
    )

    private val verrou = Object()
    private val inscrites = HashMap<GardeVidange, Inscrite>()
    private val relances = HashMap<GardeVidange, Runnable>()

    private var fil: HandlerThread? = null
    private var horloge: Handler? = null
    private var gestionnaire: ConnectivityManager? = null
    private var rappelReseau: ConnectivityManager.NetworkCallback? = null

    /**
     * À appeler à la fin de chaque vidange gardée d'une
     * file : (ré)inscrit la file, puis pose sa relance
     * unique si la garde l'autorise (frein armé) et qu'il
     * reste du cargo — ou annule celle qui attendait si la
     * vidange a réussi ou si la file s'est vidée. Les
     * fermetures reçoivent le contexte d'APPLICATION,
     * lié ici une seule fois : aucune activité détruite
     * n'est retenue par une relance en attente.
     */
    fun planifier(
        ctx: Context,
        garde: GardeVidange,
        nonVide: (Context) -> Boolean,
        vider: (Context) -> Unit
    ) {
        val appCtx = ctx.applicationContext
        synchronized(verrou) {
            inscrites[garde] = Inscrite(
                { nonVide(appCtx) }, { vider(appCtx) }
            )
            val delai = garde.delaiAvantRepriseMs()
            if (delai < 0L || !nonVide(appCtx)) {
                annulerRelanceVerrou(garde)
                arreterSiPlusRienVerrou()
                return
            }
            assurerMachinerieVerrou(appCtx)
            annulerRelanceVerrou(garde)
            val tache = Runnable { relanceArrivee(garde) }
            relances[garde] = tache
            horloge?.postDelayed(tache, delai)
        }
    }

    /** L'heure de la relance a sonné : la file rejoue sa
     *  vidange si le cargo est toujours là. La vidange, en
     *  se terminant, replanifiera elle-même l'échelon
     *  suivant si elle échoue encore. */
    private fun relanceArrivee(garde: GardeVidange) {
        val inscrite: Inscrite?
        synchronized(verrou) {
            relances.remove(garde)
            inscrite = inscrites[garde]
            arreterSiPlusRienVerrou()
        }
        if (inscrite == null) return
        try {
            if (inscrite.nonVide()) {
                inscrite.vider()
            }
        } catch (e: Exception) {
            // La vidange gère ses échecs elle-même (garde) ;
            // une exception qui remonte jusqu'ici ne doit
            // pas tuer le fil des relances.
        }
    }

    /** Le réseau est revenu : les files qui attendaient une
     *  relance voient leur frein tomber et partent tout de
     *  suite, sans attendre l'échéance posée. */
    private fun surReseauRetabli() {
        val aVidanger: List<Inscrite>
        synchronized(verrou) {
            val gardes = relances.keys.toList()
            if (gardes.isEmpty()) return
            aVidanger = gardes.mapNotNull { garde ->
                annulerRelanceVerrou(garde)
                garde.surConnectiviteRetablie()
                inscrites[garde]
            }
            arreterSiPlusRienVerrou()
        }
        // La machinerie a pu s'arrêter au-dessus (plus
        // aucune relance) : les vidanges partent sur un fil
        // éphémère, le réseau vient de revenir.
        Thread {
            for (inscrite in aVidanger) {
                try {
                    if (inscrite.nonVide()) inscrite.vider()
                } catch (e: Exception) {
                    // Même discipline qu'à la relance.
                }
            }
        }.start()
    }

    /** Pose le fil des relances et le rappel de
     *  connectivité, s'ils n'existent pas déjà. À appeler
     *  sous verrou. */
    private fun assurerMachinerieVerrou(appCtx: Context) {
        if (fil == null) {
            val t = HandlerThread("reprise-files")
            t.start()
            fil = t
            horloge = Handler(t.looper)
        }
        if (rappelReseau == null) {
            val cm = appCtx.getSystemService(
                ConnectivityManager::class.java
            )
            val rappel = object :
                ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    surReseauRetabli()
                }
            }
            try {
                cm?.registerDefaultNetworkCallback(rappel)
                gestionnaire = cm
                rappelReseau = rappel
            } catch (e: Exception) {
                // Sans rappel, les relances datées restent :
                // le mécanisme survit en dégradé.
            }
        }
    }

    /** Retire la relance d'une file, si elle attendait.
     *  À appeler sous verrou. */
    private fun annulerRelanceVerrou(garde: GardeVidange) {
        val tache = relances.remove(garde) ?: return
        horloge?.removeCallbacks(tache)
    }

    /** Plus aucune relance en attente : le fil s'arrête et
     *  le rappel se désenregistre — l'app retombe à zéro
     *  minuteur. À appeler sous verrou. */
    private fun arreterSiPlusRienVerrou() {
        if (relances.isNotEmpty()) return
        rappelReseau?.let { rappel ->
            try {
                gestionnaire?.unregisterNetworkCallback(rappel)
            } catch (e: Exception) {
                // Déjà désenregistré.
            }
        }
        rappelReseau = null
        gestionnaire = null
        horloge = null
        fil?.quitSafely()
        fil = null
    }
}
