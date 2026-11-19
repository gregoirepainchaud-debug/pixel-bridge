package ca.gregoirepainchaud.pixelbridge.commun

import android.content.Context
import android.content.SharedPreferences

// ---------------------------------------------------------------------------
// Préférences partagées de l'app (chantier structure 0.8) : le
// fichier « bench » — historique, il précède l'app Gestion —
// s'ouvre partout par ce seul point, et ses clés vivent ici en
// constantes. Avant le chantier, 9 fichiers ouvraient « bench »
// directement, avec des clés en chaînes libres écrites à chaque
// endroit : une faute de frappe passait la compilation en
// silence et créait une préférence orpheline.
// Les NOMS de clés et les valeurs par défaut sont inchangés :
// les préférences déjà enregistrées sur l'appareil restent
// valides telles quelles.
// ---------------------------------------------------------------------------

object PreferencesApp {

    /** Le fichier historique, partagé par tous les modules. */
    const val NOM_FICHIER = "bench"

    // Pont et modèles (onglet Réglages, Oreille).
    const val CLE_PONT_URL = "pont_url"
    const val CLE_MODELE_FLUX = "modele_flux"
    const val CLE_MODELE_CORRECTION = "modele_correction"
    const val CLE_MODELE_RELECTURE = "modele_relecture"
    const val CLE_FILS = "fils"

    // État d'écoute (service Oreille, tuile).
    const val CLE_ECOUTE_EN_COURS = "ecoute_en_cours"

    // (Les clés du tuner ont été retirées en 0.17 avec
    // l'onglet Tuner lui-même — décision de Grégoire du
    // 2026-11-18 : l'accordeur vit dans son app autonome.
    // Les valeurs déjà rangées sous « tuner_* » dans le
    // fichier restent inertes, jamais relues.)

    // Scans : la note du dernier essai de vidange des pièces.
    const val CLE_SCANS_ESSAI_MS = "scans_essai_ms"
    const val CLE_SCANS_ESSAI_CODE = "scans_essai_code"

    // Suivi de trajet natif (0.16, paquet gps) : interrupteur,
    // état publié par le service (lu par les Réglages et le
    // pont JS anti-doublon), compteurs et auto apprise.
    const val CLE_GPS_ACTIF = "gps_actif"
    const val CLE_GPS_ETAT = "gps_etat"
    const val CLE_GPS_ETAT_MS = "gps_etat_ms"
    const val CLE_GPS_DERNIER_POINT_MS = "gps_dernier_point_ms"
    const val CLE_GPS_DERNIER_ENVOI_MS = "gps_dernier_envoi_ms"
    const val CLE_GPS_POINTS_EN_FILE = "gps_points_en_file"
    const val CLE_GPS_BT_CONNECTE = "gps_bt_connecte"
    const val CLE_GPS_BT_NOM = "gps_bt_nom"
    const val CLE_GPS_AUTO_NOM = "gps_auto_nom"
    const val CLE_GPS_AUTO_ADRESSE = "gps_auto_adresse"

    // Textos : filigrane et compteurs.
    const val CLE_TEXTO_INIT = "texto_init"
    const val CLE_TEXTO_DERNIER_ID = "texto_dernier_id"
    const val CLE_TEXTO_DERNIERE_DATE = "texto_derniere_date"
    const val CLE_TEXTO_TOTAL_POUSSES = "texto_total_pousses"
    const val CLE_TEXTO_DERNIER_ENVOI = "texto_dernier_envoi"

    // Valeurs par défaut historiques des modèles et des fils.
    const val DEFAUT_MODELE_FLUX = "ggml-base.bin"
    const val DEFAUT_MODELE_CORRECTION = "ggml-small.bin"
    const val DEFAUT_MODELE_RELECTURE = "ggml-large-v3-turbo.bin"
    const val DEFAUT_FILS = "4"

    /** Le fichier de préférences partagé, ouvert par l'unique porte. */
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NOM_FICHIER, Context.MODE_PRIVATE)

    /** L'adresse du pont, nettoyée — "" si elle n'est pas réglée. */
    fun pontUrl(prefs: SharedPreferences): String =
        prefs.getString(CLE_PONT_URL, "")?.trim().orEmpty()

    /** Le nombre de fils Whisper choisi, borné comme avant (1 à 9). */
    fun nombreFils(prefs: SharedPreferences): Int =
        prefs.getString(CLE_FILS, DEFAUT_FILS)?.toIntOrNull()
            ?.coerceIn(1, 9) ?: 4
}
