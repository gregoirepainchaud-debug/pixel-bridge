package ca.gregoirepainchaud.pixelbridge.tuner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------------------
// Enregistrement des accords sur mesure (2026-11-18, version 0.5) —
// la « base de données » locale des accords produits par la mesure
// d'inharmonicité : un fichier JSON structuré par accord, dans
// filesDir/mes-accords/, avec ses métadonnées (client, piano,
// série, date, diapason), ses ancres mesurées et leur B, la courbe
// de B complète et la table des 88 notes générée.
//
// Les accords de Simon vivent dans l'asset tunings.json et ne sont
// JAMAIS écrits par ce module — lecture seule, pour toujours.
// ---------------------------------------------------------------------------

/** Un accord sur mesure enregistré, avec toute sa provenance. */
data class AccordSauvegarde(
    /** Identifiant stable = nom du fichier (sans dossier). */
    val idFichier: String,
    val client: String,
    val piano: String,
    val serie: String,
    /** Date de la mesure, ISO (yyyy-MM-dd). */
    val dateIso: String,
    val pitch: Int,
    val ancres: List<MesureAncre>,
    /** Courbe de B, indexée 0..88 (l'indice 0 est inutilisé). */
    val bParCle: List<Double>,
    /** La table consommée par l'affichage (même forme que Simon). */
    val accord: Accord
)

/** Brouillon d'un parcours interrompu : reprenable tel quel. */
data class BrouillonParcours(
    val client: String,
    val piano: String,
    val serie: String,
    val pitch: Int,
    val ancres: List<MesureAncre>
)

private fun dossierMesAccords(context: Context): File =
    File(context.filesDir, "mes-accords").apply { mkdirs() }

private fun ancresVersJson(ancres: List<MesureAncre>): JSONArray {
    val arr = JSONArray()
    for (a in ancres) {
        val partiels = JSONArray()
        for (p in a.partiels) {
            partiels.put(JSONObject().apply {
                put("n", p.n)
                put("freqHz", p.freqHz)
                put("confiance", p.confiance)
            })
        }
        arr.put(JSONObject().apply {
            put("key", a.key)
            put("nom", a.nom)
            put("f1Hz", a.f1Hz)
            put("b", a.b)
            put("ecartCents", a.ecartCents)
            put("partiels", partiels)
        })
    }
    return arr
}

private fun ancresDepuisJson(arr: JSONArray): List<MesureAncre> =
    List(arr.length()) { i ->
        val a = arr.getJSONObject(i)
        val arrP = a.getJSONArray("partiels")
        MesureAncre(
            key = a.getInt("key"),
            nom = a.getString("nom"),
            f1Hz = a.getDouble("f1Hz"),
            b = a.getDouble("b"),
            ecartCents = a.getDouble("ecartCents"),
            partiels = List(arrP.length()) { j ->
                val p = arrP.getJSONObject(j)
                PartielMesure(
                    n = p.getInt("n"),
                    freqHz = p.getDouble("freqHz"),
                    confiance = p.getDouble("confiance")
                )
            }
        )
    }

/** Sauvegarde un accord terminé ; retourne son identifiant fichier. */
fun sauvegarderAccord(
    context: Context,
    client: String,
    piano: String,
    serie: String,
    dateIso: String,
    pitch: Int,
    ancres: List<MesureAncre>,
    bParCle: DoubleArray,
    accord: Accord
): String {
    val idFichier = "accord-" + System.currentTimeMillis() + ".json"
    val notes = JSONArray()
    for (n in accord.notes) {
        notes.put(JSONObject().apply {
            put("key", n.key)
            put("name", n.name)
            put("fundamentalHz440", n.fundamentalHz440)
            put("partialTargetHz440", n.partialTargetHz440)
            put("listenPartial", n.listenPartial)
        })
    }
    val courbe = JSONArray()
    for (k in 0..88) courbe.put(if (k == 0) 0.0 else bParCle[k])
    val racine = JSONObject().apply {
        put("client", client)
        put("piano", piano)
        put("serie", serie)
        put("date", dateIso)
        put("pitch", pitch)
        put("ancres", ancresVersJson(ancres))
        put("bParCle", courbe)
        put("notes", notes)
    }
    File(dossierMesAccords(context), idFichier)
        .writeText(racine.toString(1))
    return idFichier
}

/** Charge tous les accords enregistrés, du plus récent au plus ancien. */
fun chargerAccordsSauvegardes(context: Context): List<AccordSauvegarde> {
    val dossier = dossierMesAccords(context)
    val fichiers = dossier.listFiles { f ->
        f.name.startsWith("accord-") && f.name.endsWith(".json")
    }?.sortedByDescending { it.name } ?: return emptyList()
    val resultat = mutableListOf<AccordSauvegarde>()
    for (f in fichiers) {
        try {
            val racine = JSONObject(f.readText())
            val arrNotes = racine.getJSONArray("notes")
            val notes = List(arrNotes.length()) { i ->
                val n = arrNotes.getJSONObject(i)
                NoteAccord(
                    key = n.getInt("key"),
                    name = n.getString("name"),
                    fundamentalHz440 = n.getDouble("fundamentalHz440"),
                    partialTargetHz440 = n.getDouble("partialTargetHz440"),
                    listenPartial = n.getInt("listenPartial")
                )
            }
            val piano = racine.optString("piano")
            val client = racine.optString("client")
            val serie = racine.optString("serie")
            val nomAffiche = when {
                piano.isNotBlank() -> piano
                client.isNotBlank() -> client
                else -> "Piano sans nom"
            }
            val arrB = racine.getJSONArray("bParCle")
            resultat.add(
                AccordSauvegarde(
                    idFichier = f.name,
                    client = client,
                    piano = piano,
                    serie = serie,
                    dateIso = racine.optString("date"),
                    pitch = racine.optInt("pitch", 440),
                    ancres = ancresDepuisJson(racine.getJSONArray("ancres")),
                    bParCle = List(arrB.length()) { arrB.getDouble(it) },
                    accord = Accord(
                        id = 0, // remplacé par l'appelant (id négatif)
                        name = nomAffiche,
                        serial = serie,
                        notes = notes
                    )
                )
            )
        } catch (e: Exception) {
            // Un fichier illisible est ignoré, jamais fatal : les
            // autres accords restent disponibles.
        }
    }
    return resultat
}

/** Supprime définitivement un accord enregistré. */
fun supprimerAccordSauvegarde(context: Context, idFichier: String) {
    val f = File(dossierMesAccords(context), idFichier)
    if (f.exists()) f.delete()
}

// --- Brouillon de parcours (reprise après interruption) ---

private fun fichierBrouillon(context: Context): File =
    File(dossierMesAccords(context), "brouillon.json")

fun sauvegarderBrouillon(context: Context, brouillon: BrouillonParcours) {
    val racine = JSONObject().apply {
        put("client", brouillon.client)
        put("piano", brouillon.piano)
        put("serie", brouillon.serie)
        put("pitch", brouillon.pitch)
        put("ancres", ancresVersJson(brouillon.ancres))
    }
    fichierBrouillon(context).writeText(racine.toString(1))
}

fun chargerBrouillon(context: Context): BrouillonParcours? {
    val f = fichierBrouillon(context)
    if (!f.exists()) return null
    return try {
        val racine = JSONObject(f.readText())
        BrouillonParcours(
            client = racine.optString("client"),
            piano = racine.optString("piano"),
            serie = racine.optString("serie"),
            pitch = racine.optInt("pitch", 440),
            ancres = ancresDepuisJson(racine.getJSONArray("ancres"))
        )
    } catch (e: Exception) {
        null
    }
}

fun effacerBrouillon(context: Context) {
    val f = fichierBrouillon(context)
    if (f.exists()) f.delete()
}
