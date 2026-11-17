package ca.gregoirepainchaud.pixelbridge.gestion

import android.content.Context
import android.provider.Telephony
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Module textos de Gestion Android (décision de Grégoire,
 * 2026-11-17) : l'app lit les textos reçus et les pousse à Muse
 * par le pont, comme des runs (cas « texto »).
 *
 * Filigrane local : seuls les textos reçus APRÈS le point de
 * départ partent. À la première installation, le filigrane est
 * posé à « maintenant » (le texto le plus récent de la boîte) —
 * l'historique ne se déverse jamais. Chaque texto poussé avance
 * le filigrane ; le texto est d'abord écrit dans la file locale
 * du pont, donc un texto lu est un texto qui partira, même si le
 * réseau manque à cet instant.
 *
 * La permission READ_SMS est demandée par l'écran qui appelle
 * (GestionActivity) ; verifier() suppose qu'elle est accordée.
 */
object Textos {

    private const val CLE_INIT = "texto_init"
    private const val CLE_DERNIER_ID = "texto_dernier_id"
    private const val CLE_DERNIERE_DATE = "texto_derniere_date"
    private const val CLE_TOTAL = "texto_total_pousses"
    private const val CLE_DERNIER_ENVOI = "texto_dernier_envoi"

    private val PROJECTION = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.DATE,
        Telephony.Sms.BODY
    )

    private fun formatIso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CANADA_FRENCH)
            .format(Date(ms))

    private fun formatCourt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CANADA_FRENCH)
            .format(Date(ms))

    /**
     * Vérifie la boîte de réception et pousse les nouveaux textos.
     * Travail réseau + base de textos : roule sur un fil d'arrière-
     * plan ; majStatut est appelé depuis ce fil (à l'appelant de
     * repasser au fil d'interface pour afficher).
     */
    fun verifier(ctx: Context, majStatut: (String) -> Unit) {
        Thread {
            val prefs = ctx.getSharedPreferences("bench", Context.MODE_PRIVATE)
            val resolver = ctx.contentResolver
            val boite = Telephony.Sms.Inbox.CONTENT_URI

            if (!prefs.getBoolean(CLE_INIT, false)) {
                // Filigrane initial : le texto le plus récent actuel
                // devient le point de départ ; rien avant ne part.
                var maxDate = System.currentTimeMillis()
                var maxId = -1L
                try {
                    resolver.query(
                        boite, PROJECTION, null, null,
                        "${Telephony.Sms.DATE} DESC"
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            maxDate = c.getLong(
                                c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                            )
                            maxId = c.getLong(
                                c.getColumnIndexOrThrow(Telephony.Sms._ID)
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Boîte illisible : le filigrane reste « maintenant ».
                }
                prefs.edit()
                    .putBoolean(CLE_INIT, true)
                    .putLong(CLE_DERNIERE_DATE, maxDate)
                    .putLong(CLE_DERNIER_ID, maxId)
                    .apply()
                majStatut(
                    "Textos : filigrane posé à maintenant — seuls les " +
                        "textos reçus à partir d'ici partiront au pont."
                )
                return@Thread
            }

            val filigraneDate = prefs.getLong(CLE_DERNIERE_DATE, 0)
            val dernierId = prefs.getLong(CLE_DERNIER_ID, -1)
            var nouveaux = 0
            var nouvDate = filigraneDate
            var nouvId = dernierId
            try {
                resolver.query(
                    boite, PROJECTION,
                    // >= et non > : un texto de la même milliseconde
                    // que le filigrane doit passer — le garde d'id
                    // ci-dessous écarte ceux déjà poussés.
                    "${Telephony.Sms.DATE} >= ?",
                    arrayOf(filigraneDate.toString()),
                    "${Telephony.Sms.DATE} ASC"
                )?.use { c ->
                    val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                    val iAdr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val iCorps = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        // Même milliseconde que le filigrane : l'id
                        // tranche — un texto déjà poussé ne repart pas.
                        if (id <= dernierId) continue
                        val expediteur = c.getString(iAdr) ?: "inconnu"
                        val dateMs = c.getLong(iDate)
                        val corps = c.getString(iCorps) ?: ""
                        val donnees = JSONObject()
                            .put("expediteur", expediteur)
                            .put("date_iso", formatIso(dateMs))
                            .put("corps", corps)
                        val charge = JSONObject()
                            .put("cas", "texto")
                            .put("contexte", false)
                            .put(
                                "resultat",
                                "TEXTO $expediteur (${formatIso(dateMs)}) : " +
                                    corps.take(120)
                            )
                            .put("donnees", donnees)
                        PontFile.ecrireRun(ctx, charge)
                        nouveaux++
                        if (dateMs > nouvDate) nouvDate = dateMs
                        if (id > nouvId) nouvId = id
                    }
                }
            } catch (e: Exception) {
                majStatut("Textos : lecture impossible (${e.message}).")
                return@Thread
            }

            if (nouveaux > 0) {
                prefs.edit()
                    .putLong(CLE_DERNIERE_DATE, nouvDate)
                    .putLong(CLE_DERNIER_ID, nouvId)
                    .putInt(
                        CLE_TOTAL,
                        prefs.getInt(CLE_TOTAL, 0) + nouveaux
                    )
                    .putString(
                        CLE_DERNIER_ENVOI,
                        formatCourt(System.currentTimeMillis())
                    )
                    .apply()
            }
            val (_, restants) = PontFile.vider(ctx)
            val total = prefs.getInt(CLE_TOTAL, 0)
            val dernierEnvoi = prefs.getString(CLE_DERNIER_ENVOI, null)
            majStatut(
                when {
                    nouveaux == 0 ->
                        "Textos : aucun nouveau texto. Total poussé : " +
                            "$total" +
                            (dernierEnvoi?.let { ", dernier envoi $it." }
                                ?: ".")
                    restants > 0 ->
                        "Textos : $nouveaux nouveau(x) en file — ils " +
                            "partiront au retour du réseau. " +
                            "Total poussé : $total."
                    else ->
                        "Textos : $nouveaux nouveau(x) poussé(s) ✓ — " +
                            "total $total, dernier envoi " +
                            (dernierEnvoi ?: "à l'instant") + "."
                }
            )
        }.start()
    }
}
