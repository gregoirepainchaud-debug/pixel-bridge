package ca.gregoirepainchaud.pixelbridge.gestion

import android.content.Context
import ca.gregoirepainchaud.pixelbridge.commun.GardeVidange
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp
import ca.gregoirepainchaud.pixelbridge.commun.RepriseFiles
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Files d'attente du pont, partagées — PontFile en est
 * l'unique propriétaire (0.19, suite de la critique
 * post-0.18 §1) : DEUX files vivent ici, chacune sous sa
 * propre GardeVidange (une seule vidange à la fois, arrêt
 * au premier échec réseau, frein de reprise échelonné) :
 *
 * - les runs JSON, dans filesDir/pont-attente, qui partent
 *   vers POST /pont/<jeton>/run. Le service Oreille y écrit
 *   ses séances (appel-ecoute, reunion), le module textos
 *   ses textos, le scanner de reçus ses runs « scan » ;
 * - les clips audio de l'Oreille, dans
 *   filesDir/appels-attente/<id>/ (ep-<i>.wav des appels,
 *   fen-<i>.wav des appels et des réunions), qui partent
 *   vers POST /pont/<jeton>/audio/<id>/<fichier>.
 *
 * Avant 0.19, l'écran et le service Oreille tenaient
 * chacun leur copie de vidange, avec leurs propres
 * gardes : deux vidanges du même dossier pouvaient
 * partir en parallèle et poster le même fichier deux
 * fois. Les copies sont supprimées ; l'Oreille ne fait
 * plus que DÉPOSER (ecrireRun, dossierAppel) et demander
 * la vidange d'ici.
 *
 * À la fin de chaque vidange, la reprise bornée est
 * replanifiée (RepriseFiles) : si un échec réseau a armé
 * le frein et qu'il reste du cargo, une relance unique
 * partira à son expiration ; le retour du réseau fait
 * partir immédiatement.
 *
 * vider() et viderAudio() font du réseau : les appeler
 * hors du fil d'interface.
 */
object PontFile {

    private val garde = GardeVidange()
    private val gardeAudio = GardeVidange()

    fun dossierAttente(ctx: Context): File =
        File(ctx.filesDir, "pont-attente").apply { mkdirs() }

    /** Le dossier des clips audio en attente, tous appels
     *  et réunions confondus. */
    fun dossierAppels(ctx: Context): File =
        File(ctx.filesDir, "appels-attente").apply { mkdirs() }

    /** Le dossier d'attente des clips d'un appel ou d'une
     *  réunion — là où l'Oreille dépose ses WAV avant
     *  l'envoi. */
    fun dossierAppel(ctx: Context, id: String): File =
        File(dossierAppels(ctx), id).apply { mkdirs() }

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

    /** Nombre de clips audio encore en file. */
    fun compterAudioEnAttente(ctx: Context): Int {
        val dossiers = dossierAppels(ctx).listFiles()
            ?.filter { it.isDirectory } ?: return 0
        var total = 0
        for (d in dossiers) {
            total += d.listFiles()
                ?.count { it.name.endsWith(".wav") } ?: 0
        }
        return total
    }

    /**
     * Poste chaque run de la file vers l'adresse du pont
     * (préférence « pont_url », la même que l'écran Oreille)
     * et efface ceux acceptés (2xx). Retourne (envoyés,
     * restants). Sans adresse ou sans réseau, tout reste
     * en file.
     */
    fun vider(ctx: Context): Pair<Int, Int> {
        val url = PreferencesApp.pontUrl(PreferencesApp.prefs(ctx))
        if (url.isEmpty()) return Pair(0, compterEnAttente(ctx))
        if (!garde.essayer()) return Pair(0, compterEnAttente(ctx))
        var echecReseau = false
        try {
            val fichiers = dossierAttente(ctx).listFiles()
                ?.filter { it.name.endsWith(".json") }?.sorted()
                ?: emptyList()
            var envoyes = 0
            for (f in fichiers) {
                try {
                    val conn = URL(url).openConnection()
                        as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty(
                        "Content-Type", "application/json"
                    )
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.doOutput = true
                    conn.outputStream.use { it.write(f.readBytes()) }
                    if (conn.responseCode in 200..299 && f.delete()) {
                        envoyes++
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    // Réseau absent : le fichier reste en file
                    // et la vidange s'arrête là — inutile de
                    // payer le délai d'attente de chaque
                    // fichier restant (0.18).
                    echecReseau = true
                    break
                }
            }
            return Pair(envoyes, compterEnAttente(ctx))
        } finally {
            garde.terminer(echecReseau)
            RepriseFiles.planifier(
                ctx, garde,
                { compterEnAttente(it) > 0 },
                { vider(it) }
            )
        }
    }

    /**
     * Pousse les clips WAV en attente vers
     * /pont/<jeton>/audio/<id>/<fichier>, dans l'ordre des
     * dossiers. Retourne (envoyés, restants). Même
     * discipline que vider() : une seule vidange à la fois,
     * arrêt au premier échec réseau, reprise bornée.
     */
    fun viderAudio(ctx: Context): Pair<Int, Int> {
        val urlRun = PreferencesApp.pontUrl(PreferencesApp.prefs(ctx))
        if (!urlRun.endsWith("/run")) {
            return Pair(0, compterAudioEnAttente(ctx))
        }
        if (!gardeAudio.essayer()) {
            return Pair(0, compterAudioEnAttente(ctx))
        }
        var echecReseau = false
        try {
            val base = urlRun.removeSuffix("/run")
            val racine = dossierAppels(ctx)
            val dossiers = racine.listFiles()
                ?.filter { it.isDirectory }?.sorted() ?: emptyList()
            var envoyes = 0
            for (dossier in dossiers) {
                if (echecReseau) break
                val fichiers = dossier.listFiles()
                    ?.filter { it.name.endsWith(".wav") }?.sorted()
                    ?: emptyList()
                for (f in fichiers) {
                    try {
                        val cible = URL(
                            "$base/audio/${dossier.name}/${f.name}"
                        )
                        val conn = cible.openConnection()
                            as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.setRequestProperty(
                            "Content-Type", "audio/wav"
                        )
                        conn.connectTimeout = 10000
                        conn.readTimeout = 15000
                        conn.doOutput = true
                        conn.outputStream.use {
                            it.write(f.readBytes())
                        }
                        if (conn.responseCode in 200..299 &&
                            f.delete()
                        ) {
                            envoyes++
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        // Réseau absent : le clip reste en
                        // file, la vidange s'arrête (0.18).
                        echecReseau = true
                        break
                    }
                }
                if (dossier.listFiles()?.isEmpty() == true) {
                    dossier.delete()
                }
            }
            return Pair(envoyes, compterAudioEnAttente(ctx))
        } finally {
            gardeAudio.terminer(echecReseau)
            RepriseFiles.planifier(
                ctx, gardeAudio,
                { compterAudioEnAttente(it) > 0 },
                { viderAudio(it) }
            )
        }
    }
}
