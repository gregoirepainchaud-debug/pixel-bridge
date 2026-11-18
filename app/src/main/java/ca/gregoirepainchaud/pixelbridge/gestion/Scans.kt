package ca.gregoirepainchaud.pixelbridge.gestion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Module scans de Gestion Android (V2, décision de Grégoire,
 * 2026-11-17) : le scan de l'onglet Scanner (ML Kit) part à
 * Muse par le pont, comme les textos (cas « texto ») et les
 * appels (cas « appel-ecoute »).
 *
 * Mécanique copiée sur celle des clips de l'Oreille :
 * - la pièce — le PDF de session, seul depuis la V3
 *   (2026-11-17 : le test terrain a prouvé que l'image
 *   embarquée dans le PDF ML Kit est plus nette que les JPEG
 *   séparés, qui sont abandonnés) — est copiée dans
 *   filesDir/scans-attente/<scan>/, puis poussée en POST brut
 *   vers <base>/scan/<scan>/<fichier> (base = adresse du pont
 *   sans son suffixe « /run ») ; une pièce acceptée (2xx) est
 *   effacée, sinon elle reste en file et repart à la
 *   prochaine occasion. La vidange accepte encore les .jpg :
 *   des pièces mises en file par la V2 doivent pouvoir
 *   partir, la route serveur les accepte toujours ;
 * - le run JSON part par la file partagée (PontFile) vers
 *   POST /run : {cas: "scan", contexte: false, resultat,
 *   donnees} ; donnees.scan nomme le dossier de la pièce,
 *   comme donnees.appel nomme celui des clips d'un appel ;
 *   donnees.pieces vaut ["session.pdf"] et donnees.nb_pages
 *   est le nombre de pages du PDF, lu par ML Kit
 *   (GmsDocumentScanningResult.Pdf.pageCount) ;
 * - donnees.usage porte l'étiquette d'usage : « livre » ou
 *   « autre », choisies à l'écran de l'onglet Scanner, ou
 *   « recu », figée par le pont natif du scanner de reçus de
 *   la WebView Gestion (depuis le 2026-11-17 au soir, les
 *   reçus repartent par ici : à la sortie du scan ML Kit
 *   lancé depuis l'onglet Reçus du site, le natif envoie
 *   lui-même la session au pont, sans bouton « envoyer »).
 *
 * Tout est écrit en file locale d'abord : 100 % hors ligne OK,
 * synchro opportuniste, comme le reste du pont (contrainte
 * fondatrice du projet). L'app confirme l'arrivée au pont,
 * jamais la lecture par Muse lui-même.
 */
object Scans {

    private fun formatIso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CANADA_FRENCH)
            .format(Date(ms))

    /** États d'un envoi, pour les appelants qui veulent un
     *  statut structuré plutôt qu'un message tout fait. */
    const val ENVOI_ERREUR = 0
    const val ENVOI_EN_FILE = 1
    const val ENVOI_ENVOYE = 2

    /**
     * Met un scan en file pour Muse, puis tente l'envoi
     * immédiat (la pièce, puis les runs en attente). Travail
     * fichiers + réseau : roule sur un fil d'arrière-plan ;
     * majStatut est appelé depuis ce fil avec (misEnFile,
     * message) — à l'appelant de repasser au fil d'interface
     * pour afficher. misEnFile est faux si l'écriture en file
     * locale elle-même a échoué, ou si le PDF de session est
     * absent : sans PDF, rien n'est mis en file.
     *
     * scan : identifiant de la session, le nom de son dossier
     * (« scan-<horodatage> »), celui que Grégoire voit à
     * l'écran de résultat. pdf : le session.pdf de la session.
     * nbPages : son nombre de pages, lu par ML Kit.
     */
    fun envoyer(
        ctx: Context, scan: String, pdf: File,
        nbPages: Int, usage: String,
        majStatut: (Boolean, String) -> Unit
    ) {
        envoyerDetail(ctx, scan, pdf, nbPages, usage) { etat, message ->
            majStatut(etat != ENVOI_ERREUR, message)
        }
    }

    /**
     * Même mécanique que envoyer(), mais le statut revient
     * structuré : ENVOI_ENVOYE seulement si la pièce ET le run
     * ont été acceptés par le pont (files vides après la
     * tentative) ; ENVOI_EN_FILE si tout est en file locale et
     * partira au retour du réseau — jamais un faux « envoyé » ;
     * ENVOI_ERREUR si la mise en file locale elle-même a
     * échoué, ou si le PDF de session est absent. Sert au pont
     * natif du scanner de reçus de la WebView Gestion, dont la
     * page ne doit recevoir que le statut.
     */
    fun envoyerDetail(
        ctx: Context, scan: String, pdf: File,
        nbPages: Int, usage: String,
        majStatut: (Int, String) -> Unit
    ) {
        Thread {
            if (!pdf.isFile) {
                majStatut(
                    ENVOI_ERREUR,
                    "Scan : PDF de session introuvable — " +
                        "rien n'a été mis en file."
                )
                return@Thread
            }
            try {
                val dossier = File(ctx.filesDir, "scans-attente/$scan")
                    .apply { mkdirs() }
                pdf.copyTo(
                    File(dossier, "session.pdf"), overwrite = true
                )
                val noms = JSONArray().put("session.pdf")
                val donnees = JSONObject()
                    .put("scan", scan)
                    .put("usage", usage)
                    .put("nb_pages", nbPages)
                    .put("pieces", noms)
                    .put("horodatage", formatIso(System.currentTimeMillis()))
                val charge = JSONObject()
                    .put("cas", "scan")
                    .put("contexte", false)
                    .put(
                        "resultat",
                        "SCAN $usage — $nbPages page(s), session $scan"
                    )
                    .put("donnees", donnees)
                PontFile.ecrireRun(ctx, charge)
            } catch (e: Exception) {
                majStatut(
                    ENVOI_ERREUR,
                    "Scan : mise en file impossible (${e.message})."
                )
                return@Thread
            }
            val piecesRestantes = viderPieces(ctx)
            val (_, runsRestants) = PontFile.vider(ctx)
            if (piecesRestantes == 0 && runsRestants == 0) {
                majStatut(
                    ENVOI_ENVOYE,
                    "Envoyé au pont ✓ — Muse ramassera le PDF " +
                        "à son prochain passage."
                )
            } else {
                majStatut(
                    ENVOI_EN_FILE,
                    "En file — partira dès que le pont répond " +
                        "(pièces en attente : $piecesRestantes, " +
                        "runs en attente : $runsRestants)."
                )
            }
        }.start()
    }

    /**
     * Pousse les pièces en attente vers /pont/<jeton>/scan/…,
     * même forme que la vidange des clips WAV de l'écran
     * Oreille. Retourne le nombre de pièces encore en attente.
     * Sans adresse de pont ou sans réseau, tout reste en file.
     * Les .jpg encore en file (pièces mises là par la V2)
     * partent aussi — la route serveur les accepte toujours.
     */
    fun viderPieces(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences("bench", Context.MODE_PRIVATE)
        val urlRun = prefs.getString("pont_url", "")?.trim().orEmpty()
        if (!urlRun.endsWith("/run")) return compterPieces(ctx)
        val base = urlRun.removeSuffix("/run")
        val racine = File(ctx.filesDir, "scans-attente")
        val dossiers = racine.listFiles()
            ?.filter { it.isDirectory }?.sorted() ?: emptyList()
        for (dossier in dossiers) {
            val fichiers = dossier.listFiles()
                ?.filter {
                    it.name.endsWith(".jpg") || it.name.endsWith(".pdf")
                }?.sorted() ?: emptyList()
            for (f in fichiers) {
                try {
                    val cible = URL("$base/scan/${dossier.name}/${f.name}")
                    val conn = cible.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty(
                        "Content-Type",
                        if (f.name.endsWith(".pdf")) {
                            "application/pdf"
                        } else {
                            "image/jpeg"
                        }
                    )
                    conn.connectTimeout = 10000
                    conn.readTimeout = 30000
                    conn.doOutput = true
                    conn.outputStream.use { it.write(f.readBytes()) }
                    if (conn.responseCode in 200..299) f.delete()
                    conn.disconnect()
                } catch (e: Exception) {
                    // Réseau absent : la pièce reste en file.
                }
            }
            if (dossier.listFiles()?.isEmpty() == true) dossier.delete()
        }
        return compterPieces(ctx)
    }

    private fun compterPieces(ctx: Context): Int {
        val racine = File(ctx.filesDir, "scans-attente")
        return racine.listFiles()
            ?.filter { it.isDirectory }
            ?.sumOf { d -> d.listFiles()?.size ?: 0 } ?: 0
    }
}
