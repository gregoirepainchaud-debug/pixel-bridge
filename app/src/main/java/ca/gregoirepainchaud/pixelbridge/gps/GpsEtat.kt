package ca.gregoirepainchaud.pixelbridge.gps

import android.content.Context
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp

// ---------------------------------------------------------------------------
// État publié du suivi de trajet natif (0.16) : le service écrit
// son état dans les préférences partagées à chaque changement —
// l'onglet Réglages le lit pour l'afficher (rien d'invisible),
// et le pont JS de la WebView le consulte pour savoir si la
// page web doit se retirer (anti-doublon). Aucune logique ici :
// seulement la publication et la lecture d'un état décidé par
// GpsLogique et vécu par GpsService.
// ---------------------------------------------------------------------------

object GpsEtat {

    /** L'interrupteur de l'onglet Réglages (défaut : actif). */
    fun actif(ctx: Context): Boolean =
        PreferencesApp.prefs(ctx)
            .getBoolean(PreferencesApp.CLE_GPS_ACTIF, true)

    /** Nom de l'état courant publié par le service ("" si le
     *  service n'a jamais publié — pas encore démarré). */
    fun etatNom(ctx: Context): String =
        PreferencesApp.prefs(ctx)
            .getString(PreferencesApp.CLE_GPS_ETAT, "") ?: ""

    /**
     * Vrai quand le natif émet des points (conduite ou pause) :
     * la page web, qui le demande par le pont JS, s'arrête alors
     * d'émettre pour ne pas doubler les points du même trajet.
     *
     * Fraîcheur (0.18) : la revendication ne vaut que si elle
     * est récente. Le service rafraîchit l'horodatage de son
     * état tant qu'il émet ; s'il meurt en conduite ou en
     * pause sans republier, l'horodatage vieillit et, passé
     * FRAICHEUR_MAX_MS, la page web reprend son suivi au lieu
     * de rester retirée indéfiniment.
     */
    fun natifEmet(ctx: Context): Boolean {
        if (!actif(ctx)) return false
        val nom = etatNom(ctx)
        if (nom != EtatGps.CONDUITE.name && nom != EtatGps.PAUSE.name) {
            return false
        }
        val publieMs = PreferencesApp.prefs(ctx)
            .getLong(PreferencesApp.CLE_GPS_ETAT_MS, 0L)
        if (publieMs <= 0L) return false
        return System.currentTimeMillis() - publieMs <= FRAICHEUR_MAX_MS
    }

    /** Libellé lisible de l'état, pour la notification et les
     *  Réglages — les mêmes mots aux deux endroits. */
    fun libelle(etatNom: String): String = when (etatNom) {
        EtatGps.VEILLE.name ->
            "Au repos — aucun relevé en cours"
        EtatGps.VERIFICATION.name ->
            "Vérification du déplacement…"
        EtatGps.CONDUITE.name ->
            "En déplacement — suivi actif"
        EtatGps.PAUSE.name ->
            "En pause (immobile, session ouverte)"
        else -> "Service non démarré"
    }

    fun publierEtat(ctx: Context, etat: EtatGps) {
        PreferencesApp.prefs(ctx).edit()
            .putString(PreferencesApp.CLE_GPS_ETAT, etat.name)
            .putLong(
                PreferencesApp.CLE_GPS_ETAT_MS,
                System.currentTimeMillis()
            )
            .apply()
    }

    /** Rafraîchit le seul horodatage de l'état publié : le
     *  service l'appelle tant qu'il émet (conduite ou pause),
     *  pour que la revendication lue par la page web reste
     *  fraîche — et périme s'il meurt sans republier. */
    fun rafraichirFraicheur(ctx: Context) {
        PreferencesApp.prefs(ctx).edit()
            .putLong(
                PreferencesApp.CLE_GPS_ETAT_MS,
                System.currentTimeMillis()
            )
            .apply()
    }

    fun publierDernierPoint(ctx: Context, tsMs: Long) {
        PreferencesApp.prefs(ctx).edit()
            .putLong(PreferencesApp.CLE_GPS_DERNIER_POINT_MS, tsMs)
            .apply()
    }

    fun publierDernierEnvoi(ctx: Context, tsMs: Long) {
        PreferencesApp.prefs(ctx).edit()
            .putLong(PreferencesApp.CLE_GPS_DERNIER_ENVOI_MS, tsMs)
            .apply()
    }

    fun publierPointsEnFile(ctx: Context, nombre: Int) {
        PreferencesApp.prefs(ctx).edit()
            .putInt(PreferencesApp.CLE_GPS_POINTS_EN_FILE, nombre)
            .apply()
    }

    fun publierBluetooth(
        ctx: Context, connecte: Boolean, nom: String?
    ) {
        PreferencesApp.prefs(ctx).edit()
            .putBoolean(PreferencesApp.CLE_GPS_BT_CONNECTE, connecte)
            .putString(PreferencesApp.CLE_GPS_BT_NOM, nom ?: "")
            .apply()
    }

    /** Au-delà de cet âge, un état « conduite/pause » publié
     *  n'est plus cru : le service est présumé mort. Le service
     *  rafraîchit l'horodatage à chaque relevé et à chaque
     *  échéance (aux 5 s), très en deçà de ce seuil. */
    const val FRAICHEUR_MAX_MS = 120_000L

    /** L'auto apprise : l'appareil Bluetooth connecté pendant
     *  une conduite qualifiée — son adresse sert ensuite de
     *  signal fort au même titre que les noms connus. */
    fun publierAutoApprise(ctx: Context, nom: String?, adresse: String) {
        PreferencesApp.prefs(ctx).edit()
            .putString(PreferencesApp.CLE_GPS_AUTO_NOM, nom ?: "")
            .putString(PreferencesApp.CLE_GPS_AUTO_ADRESSE, adresse)
            .apply()
    }
}
