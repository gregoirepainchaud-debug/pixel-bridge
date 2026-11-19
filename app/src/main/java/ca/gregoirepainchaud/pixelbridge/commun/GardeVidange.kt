package ca.gregoirepainchaud.pixelbridge.commun

import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------
// Garde des vidanges de files d'envoi (0.18, suite de la
// critique post-0.17 §2.6 ; reprise bornée ajoutée en 0.19,
// suite de la critique post-0.18 §3) : chaque file (pont,
// audio, GPS, scans) en tient une. Deux rôles :
//
// - SÉRIALISER : une seule vidange à la fois par file. Les
//   déclencheurs sont nombreux (ouverture de l'app, fin
//   d'envoi, lot plein, retour d'onglet) et partaient en
//   parallèle ; deux vidanges simultanées lisaient la même
//   liste et pouvaient poster le même fichier deux fois
//   avant que la première suppression ne tombe.
// - FREINER : après un échec réseau, la file se tait avant
//   le prochain essai, et la vidange s'arrête au premier
//   échec au lieu de payer le délai d'attente de chaque
//   fichier, réseau manifestement absent.
//
// REPRISE BORNÉE (0.19) : le frein grandit avec les échecs
// consécutifs — 30 s, puis 2 min, puis 10 min. Tant qu'un
// frein est armé, la file peut programmer UNE relance
// unique à son expiration (RepriseFiles s'en charge, si la
// file n'est pas vide). Après le troisième échec, plus
// aucune relance autonome : le frein se relâche et les
// événements naturels reprennent la main (ouverture de
// l'app, nouveau dépôt, retour de la connectivité — qui
// réinitialise la garde par surConnectiviteRetablie()).
// Aucun minuteur périodique ne vit ici : la garde ne fait
// que dater, elle ne réveille rien par elle-même.
//
// essayer() rend vrai si l'appelant peut vidanger (et
// devient le vidangeur) ; terminer() libère, en armant le
// frein échelonné si la vidange a buté sur le réseau, ou
// en réinitialisant la suite si elle a réussi.
// ---------------------------------------------------------------------------

class GardeVidange(
    private val horloge: () -> Long = { System.currentTimeMillis() }
) {

    private val enCours = AtomicBoolean(false)

    @Volatile
    private var prochainEssaiMs = 0L

    /** Échecs réseau consécutifs depuis le dernier succès
     *  (ou le dernier retour de connectivité). */
    @Volatile
    private var echecsConsecutifs = 0

    /** Vrai si l'appelant devient le vidangeur ; faux si une
     *  vidange roule déjà ou si le frein d'après-échec tient
     *  encore. */
    fun essayer(): Boolean {
        if (horloge() < prochainEssaiMs) {
            return false
        }
        return enCours.compareAndSet(false, true)
    }

    /** Libère la garde. echecReseau arme le frein échelonné
     *  (30 s, 2 min, 10 min) ; au-delà du troisième échec
     *  consécutif, plus de frein ni de relance autonome —
     *  les événements naturels décideront. Un succès remet
     *  la suite à zéro. */
    fun terminer(echecReseau: Boolean) {
        if (echecReseau) {
            echecsConsecutifs++
            prochainEssaiMs =
                if (echecsConsecutifs <= DELAIS_REPRISE_MS.size) {
                    horloge() + DELAIS_REPRISE_MS[echecsConsecutifs - 1]
                } else {
                    0L
                }
        } else {
            echecsConsecutifs = 0
            prochainEssaiMs = 0L
        }
        enCours.set(false)
    }

    /**
     * Temps restant avant la reprise programmable, en
     * millisecondes : > 0 si un frein tient encore, 0 si
     * l'heure de la relance est arrivée, -1 s'il n'y a
     * aucune relance à programmer (jamais d'échec, succès
     * depuis, ou reprises épuisées). RepriseFiles s'en
     * sert pour poser — ou ne pas poser — sa relance unique.
     */
    fun delaiAvantRepriseMs(): Long {
        if (prochainEssaiMs <= 0L) return -1L
        val restant = prochainEssaiMs - horloge()
        return if (restant > 0L) restant else 0L
    }

    /** Le réseau est revenu : la suite d'échecs est oubliée
     *  et le frein tombe — la vidange immédiate que le
     *  retour de connectivité déclenche doit pouvoir passer. */
    fun surConnectiviteRetablie() {
        echecsConsecutifs = 0
        prochainEssaiMs = 0L
    }

    companion object {
        /** Échelons du frein de reprise après échecs réseau
         *  consécutifs : 30 s, 2 min, 10 min — puis plus
         *  aucune relance autonome (0.19). */
        val DELAIS_REPRISE_MS =
            longArrayOf(30_000L, 120_000L, 600_000L)

        /** Délai de la première reprise après un échec. */
        const val DELAI_REPRISE_MS = 30_000L
    }
}
