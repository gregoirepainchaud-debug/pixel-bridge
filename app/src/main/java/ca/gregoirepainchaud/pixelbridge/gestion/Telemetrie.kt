package ca.gregoirepainchaud.pixelbridge.gestion

import android.content.Context
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Envoi des logs de télémétrie du tuner par le pont (0.14) —
 * même mécanique que les scans (Scans), pour que le log
 * voyage comme le reste : fichier d'abord, run ensuite.
 *
 * - le fichier du log (JSONL, voir tuner/Telemetrie.kt) est
 *   écrit dans filesDir/telemetrie-attente/<session>/, puis
 *   poussé en POST brut vers <base>/telemetrie/<session>/
 *   <fichier> (base = adresse du pont sans son suffixe
 *   « /run ») ; accepté (2xx), il est effacé ; sinon il
 *   reste en file et repart à la prochaine occasion —
 *   ouverture de l'app (GestionActivity), ouverture de
 *   l'onglet Tuner, ou arrêt d'un log suivant ;
 * - le run part par la file partagée (PontFile) vers
 *   POST /run : {cas: "telemetrie", donnees: {session,
 *   fichier, nb_echantillons, duree_ms, version_app}} —
 *   c'est lui qui déclenche la sonnette courriel du pont,
 *   comme les autres cas. Le log lui-même ne voyage JAMAIS
 *   dans le run (plafond de 512 Ko côté serveur ; une
 *   session de quelques minutes le dépasserait).
 *
 * 100 % hors ligne OK, synchro opportuniste, comme tout le
 * pont. L'app confirme l'arrivée au pont, jamais la lecture
 * par Muse lui-même.
 */
object Telemetrie {

    const val NOM_FICHIER = "log.jsonl"

    private fun formatIso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CANADA_FRENCH)
            .format(Date(ms))

    /**
     * Met un log terminé en file, puis tente l'envoi immédiat
     * (le fichier, puis les runs en attente). Travail fichiers
     * + réseau : roule sur un fil d'arrière-plan ; majStatut
     * est appelé depuis ce fil avec un des états de Scans
     * (ENVOI_ENVOYE / ENVOI_EN_FILE / ENVOI_ERREUR) et un
     * message affichable — à l'appelant de repasser au fil
     * d'interface pour afficher.
     */
    fun envoyer(
        ctx: Context, session: String, contenu: String,
        nbEchantillons: Int, dureeMs: Long, versionApp: String,
        majStatut: (Int, String) -> Unit
    ) {
        Thread {
            try {
                val dossier = File(ctx.filesDir, "telemetrie-attente/$session")
                    .apply { mkdirs() }
                File(dossier, NOM_FICHIER).writeText(contenu)
                val donnees = JSONObject()
                    .put("session", session)
                    .put("fichier", NOM_FICHIER)
                    .put("nb_echantillons", nbEchantillons)
                    .put("duree_ms", dureeMs)
                    .put("version_app", versionApp)
                    .put("horodatage", formatIso(System.currentTimeMillis()))
                val charge = JSONObject()
                    .put("cas", "telemetrie")
                    .put("contexte", false)
                    .put(
                        "resultat",
                        "TELEMETRIE tuner — session $session, " +
                            "$nbEchantillons échantillon(s), " +
                            "${dureeMs / 1000} s"
                    )
                    .put("donnees", donnees)
                PontFile.ecrireRun(ctx, charge)
            } catch (e: Exception) {
                majStatut(
                    Scans.ENVOI_ERREUR,
                    "Log de télémétrie : mise en file impossible " +
                        "(${e.message})."
                )
                return@Thread
            }
            val piecesRestantes = viderPieces(ctx)
            val (_, runsRestants) = PontFile.vider(ctx)
            if (piecesRestantes == 0 && runsRestants == 0) {
                majStatut(
                    Scans.ENVOI_ENVOYE,
                    "Log envoyé au pont ✓ — Muse le recevra par " +
                        "la sonnette."
                )
            } else {
                majStatut(
                    Scans.ENVOI_EN_FILE,
                    "Log en file — il partira dès que le pont " +
                        "répond (fichiers en attente : " +
                        "$piecesRestantes, runs en attente : " +
                        "$runsRestants)."
                )
            }
        }.start()
    }

    /**
     * Pousse les logs en attente vers
     * /pont/<jeton>/telemetrie/… Retourne le nombre de
     * fichiers encore en attente. Sans adresse de pont ou
     * sans réseau, tout reste en file.
     */
    fun viderPieces(ctx: Context): Int {
        val prefs = PreferencesApp.prefs(ctx)
        val urlRun = PreferencesApp.pontUrl(prefs)
        if (!urlRun.endsWith("/run")) return compterPieces(ctx)
        val base = urlRun.removeSuffix("/run")
        val racine = File(ctx.filesDir, "telemetrie-attente")
        val dossiers = racine.listFiles()
            ?.filter { it.isDirectory }?.sorted() ?: emptyList()
        for (dossier in dossiers) {
            val fichiers = dossier.listFiles()
                ?.filter { it.isFile }?.sorted() ?: emptyList()
            for (f in fichiers) {
                try {
                    val cible = URL("$base/telemetrie/${dossier.name}/${f.name}")
                    val conn = cible.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    conn.doOutput = true
                    conn.outputStream.use { it.write(f.readBytes()) }
                    if (conn.responseCode in 200..299) f.delete()
                    conn.disconnect()
                } catch (e: Exception) {
                    // Réseau absent : le log reste en file.
                }
            }
            if (dossier.listFiles()?.isEmpty() == true) dossier.delete()
        }
        return compterPieces(ctx)
    }

    /** Nombre de fichiers de log encore en file locale. */
    fun piecesEnAttente(ctx: Context): Int = compterPieces(ctx)

    private fun compterPieces(ctx: Context): Int {
        val racine = File(ctx.filesDir, "telemetrie-attente")
        return racine.listFiles()
            ?.filter { it.isDirectory }
            ?.sumOf { d -> d.listFiles()?.count { it.isFile } ?: 0 } ?: 0
    }
}
