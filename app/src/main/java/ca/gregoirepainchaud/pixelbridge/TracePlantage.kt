package ca.gregoirepainchaud.pixelbridge

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Trace de plantage persistée (2026-11-18, version 0.6) — né du
// plantage terrain du mode « Nouveau piano » : l'app est morte
// chez un client sans laisser la moindre trace exploitable.
//
// Principe : un gestionnaire d'exception non attrapée, installé
// au démarrage de l'app, écrit la trace (horodatage, version de
// l'app, étape courante déclarée par l'écran actif, fil, exception
// et pile complète) dans filesDir/dernier-plantage.txt AVANT de
// laisser le gestionnaire précédent tuer le processus comme
// d'habitude. À l'ouverture suivante, l'onglet Tuner affiche la
// trace dans une bannière dismissible : Grégoire peut la lire à
// voix haute ou la montrer, et l'effacer.
//
// Le comportement normal de l'app n'est pas modifié : le
// gestionnaire ne fait qu'écrire un fichier puis déléguer.
// ---------------------------------------------------------------------------

object TracePlantage {

    private const val NOM_FICHIER = "dernier-plantage.txt"

    /**
     * Étape courante de l'app, déclarée par l'écran actif (p. ex.
     * « mesure d'inharmonicité — ancre touche 4 »). Volatile : lu
     * depuis le fil qui plante, quel qu'il soit.
     */
    @Volatile
    var etape: String = "démarrage"

    private var installe = false

    fun installer(app: Application) {
        if (installe) return
        installe = true
        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, e ->
            try {
                val version = try {
                    val infos = app.packageManager
                        .getPackageInfo(app.packageName, 0)
                    "${infos.versionName} (code ${infos.longVersionCode})"
                } catch (ignore: Exception) {
                    "inconnue"
                }
                val horodatage = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.ROOT
                ).format(Date())
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val texte = buildString {
                    appendLine("PLANTAGE de l'app Gestion")
                    appendLine("Quand : $horodatage")
                    appendLine("Version : $version")
                    appendLine("Étape : $etape")
                    appendLine("Fil : ${fil.name}")
                    appendLine("Exception : ${e.javaClass.name} : ${e.message}")
                    appendLine("Pile :")
                    append(sw.toString())
                }
                File(app.filesDir, NOM_FICHIER).writeText(texte)
            } catch (ignore: Exception) {
                // La trace ne doit jamais empêcher la mort normale
                // du processus ni planter à son tour.
            }
            if (precedent != null) {
                precedent.uncaughtException(fil, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** La dernière trace écrite, ou null s'il n'y en a pas. */
    fun lire(context: Context): String? = try {
        val f = File(context.filesDir, NOM_FICHIER)
        if (f.exists()) f.readText() else null
    } catch (e: Exception) {
        null
    }

    /** Efface la trace (bannière refermée par Grégoire). */
    fun effacer(context: Context) {
        try {
            val f = File(context.filesDir, NOM_FICHIER)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            // Sans gravité : la bannière réapparaîtra, c'est tout.
        }
    }
}
