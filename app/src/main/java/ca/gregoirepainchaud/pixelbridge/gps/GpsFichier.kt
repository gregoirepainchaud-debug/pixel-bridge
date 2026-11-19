package ca.gregoirepainchaud.pixelbridge.gps

import android.content.Context
import android.webkit.CookieManager
import ca.gregoirepainchaud.pixelbridge.gestion.web.GestionWebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * File locale et envoi des points de trajet (0.16) — le même
 * patron que PontFile : les points attendent dans
 * filesDir/gps-attente (un fichier JSON par lot) et partent
 * vers POST /api/gps/points du serveur Gestion dès que le
 * réseau et la session le permettent. Hors ligne ou session
 * expirée (401), tout reste en file et repart à la prochaine
 * occasion — ouverture de l'app, prochain lot, retour de la
 * conduite. Aucun point ne se perd en silence.
 *
 * Format d'envoi : exactement celui de la page web —
 * {"points": [{ts (secondes), lat, lon, precision, vitesse}]}
 * — et la même authentification : le cookie de session que la
 * WebView de l'onglet Gestion détient pour
 * app.gregoirepainchaud.ca (CookieManager les partage à
 * toute l'app). vider() fait du réseau : l'appeler hors du
 * fil d'interface.
 */
object GpsFichier {

    const val URL_POINTS =
        "https://app.gregoirepainchaud.ca/api/gps/points"

    fun dossierAttente(ctx: Context): File =
        File(ctx.filesDir, "gps-attente").apply { mkdirs() }

    /** Écrit un lot de points dans la file. Retourne le fichier. */
    fun ecrireLot(ctx: Context, points: List<FixGps>): File {
        val tableau = JSONArray()
        for (p in points) {
            val o = JSONObject()
            o.put("ts", p.tsMs / 1000)
            o.put("lat", p.lat)
            o.put("lon", p.lon)
            o.put(
                "precision",
                p.precisionM?.let { Math.round(it) } ?: JSONObject.NULL
            )
            o.put("vitesse", p.vitesseMps ?: JSONObject.NULL)
            tableau.put(o)
        }
        val corps = JSONObject().put("points", tableau).toString()
        val f = File(
            dossierAttente(ctx),
            "points-${System.currentTimeMillis()}.json"
        )
        f.writeText(corps)
        GpsEtat.publierPointsEnFile(ctx, compterPoints(ctx))
        return f
    }

    /** Nombre de fichiers de lots en attente. */
    fun compterLots(ctx: Context): Int =
        dossierAttente(ctx).listFiles()
            ?.count { it.name.endsWith(".json") } ?: 0

    /** Nombre total de points en attente (pour les Réglages). */
    fun compterPoints(ctx: Context): Int {
        var total = 0
        val fichiers = dossierAttente(ctx).listFiles()
            ?.filter { it.name.endsWith(".json") } ?: emptyList()
        for (f in fichiers) {
            try {
                total += JSONObject(f.readText())
                    .optJSONArray("points")?.length() ?: 0
            } catch (e: Exception) {
                // Fichier illisible : il ne bloque pas le compte.
            }
        }
        return total
    }

    /**
     * Poste chaque lot en attente et efface ceux acceptés
     * (2xx). Retourne (points envoyés, points restants).
     * Sans cookie de session ou sans réseau, tout reste.
     */
    fun vider(ctx: Context): Pair<Int, Int> {
        val fichiers = dossierAttente(ctx).listFiles()
            ?.filter { it.name.endsWith(".json") }?.sorted()
            ?: emptyList()
        if (fichiers.isEmpty()) return Pair(0, 0)
        val cookie = try {
            CookieManager.getInstance()
                .getCookie(GestionWebView.URL_GESTION)
        } catch (e: Exception) {
            null
        }
        if (cookie.isNullOrEmpty()) {
            // Pas de session Gestion ouverte : les points
            // attendront une ouverture de l'app connecté.
            return Pair(0, compterPoints(ctx))
        }
        var envoyes = 0
        for (f in fichiers) {
            try {
                val corps = f.readText()
                val nb = JSONObject(corps)
                    .optJSONArray("points")?.length() ?: 0
                val conn = URL(URL_POINTS).openConnection()
                    as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty(
                    "Content-Type", "application/json"
                )
                conn.setRequestProperty("Cookie", cookie)
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.outputStream.use {
                    it.write(corps.toByteArray(Charsets.UTF_8))
                }
                if (conn.responseCode in 200..299 && f.delete()) {
                    envoyes += nb
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Réseau absent : le lot reste en file.
            }
        }
        val restants = compterPoints(ctx)
        if (envoyes > 0) {
            GpsEtat.publierDernierEnvoi(
                ctx, System.currentTimeMillis()
            )
        }
        GpsEtat.publierPointsEnFile(ctx, restants)
        return Pair(envoyes, restants)
    }
}
