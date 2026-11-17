package ca.gregoirepainchaud.pixelbridge.gestion

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * File d'attente du pont, partagée : les runs JSON attendent dans
 * filesDir/pont-attente et partent vers POST /pont/<jeton>/run
 * quand le réseau répond. C'est le même dossier et le même format
 * que ceux de l'écran Oreille (BenchActivity) — la mécanique
 * d'appel y écrit ses rendez-vous temporaires, le module textos
 * y écrit ses textos ; chacun vide la file à son tour.
 *
 * vider() fait du réseau : l'appeler hors du fil d'interface.
 */
object PontFile {

    fun dossierAttente(ctx: Context): File =
        File(ctx.filesDir, "pont-attente").apply { mkdirs() }

    /** Écrit un run dans la file locale. Retourne le fichier. */
    fun ecrireRun(ctx: Context, charge: JSONObject): File {
        val nom = "run-${System.currentTimeMillis()}-" +
            charge.optString("cas", "run") + ".json"
        val f = File(dossierAttente(ctx), nom)
        f.writeText(charge.toString())
        return f
    }

    fun compterEnAttente(ctx: Context): Int =
        dossierAttente(ctx).listFiles()
            ?.count { it.name.endsWith(".json") } ?: 0

    /**
     * Poste chaque fichier de la file vers l'adresse du pont
     * (préférence « pont_url », la même que l'écran Oreille) et
     * efface ceux acceptés (2xx). Retourne (envoyés, restants).
     * Sans adresse ou sans réseau, tout reste en file.
     */
    fun vider(ctx: Context): Pair<Int, Int> {
        val prefs = ctx.getSharedPreferences("bench", Context.MODE_PRIVATE)
        val url = prefs.getString("pont_url", "")?.trim().orEmpty()
        if (url.isEmpty()) return Pair(0, compterEnAttente(ctx))
        val fichiers = dossierAttente(ctx).listFiles()
            ?.filter { it.name.endsWith(".json") }?.sorted() ?: emptyList()
        var envoyes = 0
        for (f in fichiers) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.outputStream.use { it.write(f.readBytes()) }
                if (conn.responseCode in 200..299 && f.delete()) envoyes++
                conn.disconnect()
            } catch (e: Exception) {
                // Réseau absent : le fichier reste en file.
            }
        }
        return Pair(envoyes, compterEnAttente(ctx))
    }
}
