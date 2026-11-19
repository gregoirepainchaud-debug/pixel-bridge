package ca.gregoirepainchaud.pixelbridge.gestion

import android.content.Context
import ca.gregoirepainchaud.pixelbridge.commun.GardeVidange
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
 * - donnees.usage porte l'étiquette d'usage : « recu »,
 *   figée par le pont natif du scanner de reçus de la
 *   WebView Gestion (depuis le 2026-11-17 au soir, les
 *   reçus repartent par ici : à la sortie du scan ML Kit
 *   lancé depuis l'onglet Reçus du site, le natif envoie
 *   lui-même la session au pont, sans bouton « envoyer »).
 *   Les usages « livre » / « autre » de l'ancien onglet
 *   Scanner ont disparu avec lui en 0.17.
 *
 * Tout est écrit en file locale d'abord : 100 % hors ligne OK,
 * synchro opportuniste, comme le reste du pont (contrainte
 * fondatrice du projet). L'app confirme l'arrivée au pont,
 * jamais la lecture par Muse lui-même.
 *
 * État visible des envois (2026-11-17 au soir, après
 * l'incident du scan de 20 pages refusé par le plafond du
 * pont pendant que son run passait : la pièce attendait en
 * file et l'app n'annonçait rien — Grégoire a cru son scan
 * perdu) : piecesEnAttente() expose le contenu réel de la
 * file pour la section « Envois en attente », et
 * viderPieces() note l'heure et le code du dernier refus,
 * pour que l'écran puisse le dire franchement. Aucun état
 * invisible : sa règle.
 *
 * Retrait 0.17 (décision de Grégoire, 2026-11-18) :
 * l'onglet Scanner général (usages livre/autre) a disparu —
 * le scan général vit dans l'app autonome Scan-it. Ce
 * module ne sert plus que le scanner de REÇUS de la page
 * Reçus (pont natif de la WebView, usage « recu »,
 * envoyerDetail), et la section « Envois en attente » a
 * déménagé dans l'onglet Réglages : la file est la même,
 * un reçu en attente y reste visible comme avant.
 */
object Scans {

    // Une seule vidange des pièces à la fois (0.18).
    private val garde = GardeVidange()

    private fun formatIso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CANADA_FRENCH)
            .format(Date(ms))

    /** États d'un envoi, pour les appelants qui veulent un
     *  statut structuré plutôt qu'un message tout fait. */
    const val ENVOI_ERREUR = 0
    const val ENVOI_EN_FILE = 1
    const val ENVOI_ENVOYE = 2

    /**
     * Envoi d'une session de scan, avec un statut structuré :
     * ENVOI_ENVOYE seulement si la pièce ET le run
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
        val prefs = PreferencesApp.prefs(ctx)
        val urlRun = PreferencesApp.pontUrl(prefs)
        if (!urlRun.endsWith("/run")) return compterPieces(ctx)
        // Une seule vidange à la fois, frein après échec
        // réseau (0.18) — voir GardeVidange.
        if (!garde.essayer()) return compterPieces(ctx)
        var echecReseau = false
        try {
            val base = urlRun.removeSuffix("/run")
            val racine = File(ctx.filesDir, "scans-attente")
            val dossiers = racine.listFiles()
                ?.filter { it.isDirectory }?.sorted() ?: emptyList()
            // Instrumentation d'affichage seulement : la
            // mécanique d'envoi (ordre, formats, déclencheurs)
            // ne change pas — on note juste, quand une pièce
            // tentée ne part pas, l'heure de l'essai et le
            // dernier code de refus du pont s'il y en a un,
            // pour que l'écran puisse le dire franchement
            // (dernierEssai).
            var tentative = false
            var echec = false
            var codeRefus = -1
            var arreter = false
            for (dossier in dossiers) {
                if (arreter) break
                val fichiers = dossier.listFiles()
                    ?.filter {
                        it.name.endsWith(".jpg") ||
                            it.name.endsWith(".pdf")
                    }?.sorted() ?: emptyList()
                for (f in fichiers) {
                    tentative = true
                    try {
                        val cible =
                            URL("$base/scan/${dossier.name}/${f.name}")
                        val conn = cible.openConnection()
                            as HttpURLConnection
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
                        val code = conn.responseCode
                        if (code in 200..299) {
                            f.delete()
                        } else {
                            echec = true
                            codeRefus = code
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        echec = true
                        // Réseau absent : la pièce reste en
                        // file et la vidange s'arrête au
                        // premier échec réseau (0.18).
                        echecReseau = true
                        arreter = true
                        break
                    }
                }
                if (dossier.listFiles()?.isEmpty() == true) {
                    dossier.delete()
                }
            }
            val restants = compterPieces(ctx)
            if (tentative) {
                if (echec && restants > 0) {
                    prefs.edit()
                        .putLong(
                            PreferencesApp.CLE_SCANS_ESSAI_MS,
                            System.currentTimeMillis()
                        )
                        .putInt(
                            PreferencesApp.CLE_SCANS_ESSAI_CODE,
                            codeRefus
                        )
                        .apply()
                } else if (restants == 0) {
                    // File vidée : la note du dernier refus ne
                    // doit pas survivre à la réussite.
                    prefs.edit()
                        .remove(PreferencesApp.CLE_SCANS_ESSAI_MS)
                        .remove(PreferencesApp.CLE_SCANS_ESSAI_CODE)
                        .apply()
                }
            }
            return restants
        } finally {
            garde.terminer(echecReseau)
        }
    }

    /** Une pièce de scan encore en file locale, pour
     *  l'affichage de l'état des envois (onglet Réglages). */
    data class PieceEnAttente(
        val session: String,
        val fichier: String,
        val tailleOctets: Long,
        /** Heure de la mise en file (mtime de la copie). */
        val depuisMs: Long,
        /** Nombre de pages du PDF, lu dans la pièce elle-même ;
         *  null si inconnu (pièce .jpg, PDF illisible). */
        val nbPages: Int?
    )

    /**
     * Le contenu RÉEL de la file des pièces de scans
     * (filesDir/scans-attente, partagée avec les reçus de
     * l'onglet Reçus), oldest first. Le nombre de pages est
     * lu dans le PDF en attente lui-même (PdfRenderer) : il
     * reste donc connu même quand le run de la session est
     * déjà parti — exactement le cas de l'incident du
     * 2026-11-17. Lecture fichiers : hors du fil d'interface.
     */
    fun piecesEnAttente(ctx: Context): List<PieceEnAttente> {
        val racine = File(ctx.filesDir, "scans-attente")
        val dossiers = racine.listFiles()
            ?.filter { it.isDirectory }?.sorted() ?: emptyList()
        val pieces = mutableListOf<PieceEnAttente>()
        for (dossier in dossiers) {
            val fichiers = dossier.listFiles()
                ?.filter {
                    it.name.endsWith(".jpg") || it.name.endsWith(".pdf")
                }?.sorted() ?: emptyList()
            for (f in fichiers) {
                pieces.add(
                    PieceEnAttente(
                        session = dossier.name,
                        fichier = f.name,
                        tailleOctets = f.length(),
                        depuisMs = f.lastModified(),
                        nbPages = if (f.name.endsWith(".pdf")) {
                            compterPagesPdf(f)
                        } else {
                            null
                        }
                    )
                )
            }
        }
        return pieces.sortedBy { it.depuisMs }
    }

    private fun compterPagesPdf(f: File): Int? = try {
        ParcelFileDescriptor.open(
            f, ParcelFileDescriptor.MODE_READ_ONLY
        ).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Le texte de la section « Envois en attente » (onglet
     * Réglages depuis 0.17 — elle vivait dans l'onglet
     * Scanner avant son retrait) : une ligne par pièce
     * (session, taille, pages, depuis quand), puis la note
     * du dernier essai de vidange. Null quand la file est
     * vide : la section se cache. Lecture fichiers (dont le
     * nombre de pages de chaque PDF) : à appeler hors du
     * fil d'interface.
     */
    fun texteEnvoisEnAttente(ctx: Context): String? {
        val pieces = piecesEnAttente(ctx)
        if (pieces.isEmpty()) return null
        val essai = dernierEssai(ctx)
        val sb = StringBuilder()
        for (p in pieces) {
            val taille = String.format(
                Locale.CANADA_FRENCH, "%.1f",
                p.tailleOctets / 1_000_000.0
            )
            sb.append("• ").append(p.session)
                .append(" — ").append(taille).append(" Mo")
            if (p.nbPages != null) {
                sb.append(", ").append(p.nbPages)
                    .append(" page(s)")
            }
            sb.append(", en attente depuis ")
                .append(formatDepuis(p.depuisMs))
                .append("\n")
        }
        if (essai != null) {
            val heure = SimpleDateFormat(
                "HH:mm", Locale.CANADA_FRENCH
            ).format(Date(essai.first))
            if (essai.second > 0) {
                sb.append(
                    "Dernier essai : refusé par le pont " +
                        "(code ${essai.second}) à $heure."
                )
            } else {
                sb.append(
                    "Dernier essai à $heure : échec " +
                        "d'envoi (réseau ou pont " +
                        "injoignable)."
                )
            }
        } else {
            sb.append("En attente d'envoi.")
        }
        return sb.toString().trimEnd()
    }

    // « 21:12 » si c'est aujourd'hui, « 16 nov. à 21:12 »
    // sinon — pour le « en attente depuis … » de la section.
    private fun formatDepuis(ms: Long): String {
        val maintenant = java.util.Calendar.getInstance()
        val depuis = java.util.Calendar.getInstance().apply {
            timeInMillis = ms
        }
        return if (maintenant.get(java.util.Calendar.YEAR) ==
            depuis.get(java.util.Calendar.YEAR) &&
            maintenant.get(java.util.Calendar.DAY_OF_YEAR) ==
            depuis.get(java.util.Calendar.DAY_OF_YEAR)
        ) {
            SimpleDateFormat(
                "HH:mm", Locale.CANADA_FRENCH
            ).format(Date(ms))
        } else {
            SimpleDateFormat(
                "d MMM 'à' HH:mm", Locale.CANADA_FRENCH
            ).format(Date(ms))
        }
    }

    /**
     * Dernier essai de vidange noté par viderPieces qui a
     * laissé des pièces en file : (heure, code de refus du
     * pont — ou -1 si l'échec n'a pas donné de code, réseau
     * absent par exemple). Null si aucun essai échoué n'est
     * noté, ou si la file s'est vidée depuis.
     */
    fun dernierEssai(ctx: Context): Pair<Long, Int>? {
        val prefs = PreferencesApp.prefs(ctx)
        val ms = prefs.getLong(PreferencesApp.CLE_SCANS_ESSAI_MS, 0L)
        if (ms <= 0L) return null
        return Pair(ms, prefs.getInt(PreferencesApp.CLE_SCANS_ESSAI_CODE, -1))
    }

    private fun compterPieces(ctx: Context): Int {
        val racine = File(ctx.filesDir, "scans-attente")
        return racine.listFiles()
            ?.filter { it.isDirectory }
            ?.sumOf { d -> d.listFiles()?.size ?: 0 } ?: 0
    }
}
