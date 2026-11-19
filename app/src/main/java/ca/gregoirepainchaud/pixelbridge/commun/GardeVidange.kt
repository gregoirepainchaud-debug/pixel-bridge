package ca.gregoirepainchaud.pixelbridge.commun

import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Garde des vidanges de files d'envoi (0.18, suite de la
// critique post-0.17 §2.6) : chaque file (pont, GPS, scans,
// clips de l'Oreille) en tient une. Deux rôles :
//
// - SÉRIALISER : une seule vidange à la fois par file. Les
//   déclencheurs sont nombreux (ouverture de l'app, fin
//   d'envoi, lot plein, retour d'onglet) et partaient en
//   parallèle ; deux vidanges simultanées lisaient la même
//   liste et pouvaient poster le même fichier deux fois
//   avant que la première suppression ne tombe.
// - FREINER : après un échec réseau, la file se tait
//   DELAI_REPRISE_MS avant le prochain essai, et la vidange
//   s'arrête au premier échec au lieu de payer le délai
//   d'attente de chaque fichier, réseau manifestement absent.
//
// essayer() rend vrai si l'appelant peut vidanger (et devient
// le vidangeur) ; terminer() libère, en armant le frein si la
// vidange a buté sur le réseau.
// ---------------------------------------------------------------------------

class GardeVidange {

    private val enCours = AtomicBoolean(false)

    @Volatile
    private var prochainEssaiMs = 0L

    /** Vrai si l'appelant devient le vidangeur ; faux si une
     *  vidange roule déjà ou si le frein d'après-échec tient
     *  encore. */
    fun essayer(): Boolean {
        if (System.currentTimeMillis() < prochainEssaiMs) {
            return false
        }
        return enCours.compareAndSet(false, true)
    }

    /** Libère la garde. echecReseau arme le frein. */
    fun terminer(echecReseau: Boolean) {
        if (echecReseau) {
            prochainEssaiMs =
                System.currentTimeMillis() + DELAI_REPRISE_MS
        }
        enCours.set(false)
    }

    companion object {
        /** Délai de reprise après un échec réseau. */
        const val DELAI_REPRISE_MS = 30_000L
    }
}
