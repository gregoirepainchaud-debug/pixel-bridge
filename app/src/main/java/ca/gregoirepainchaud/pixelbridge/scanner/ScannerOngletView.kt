package ca.gregoirepainchaud.pixelbridge.scanner

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// ---------------------------------------------------------------------------
// L'écran de l'onglet Scanner (chantier structure 0.8) : il ne
// fait qu'afficher et recueillir les gestes — toute la
// mécanique (ML Kit, copie de session, partage, envois au
// pont) vit dans ScannerControleur, qui reçoit les actions
// des boutons et pilote cet écran par ses méthodes
// d'affichage. Textes et disposition : ceux de l'écran
// d'origine, inchangés.
// ---------------------------------------------------------------------------

class ScannerOngletView(
    context: Context,
    private val controleur: ScannerControleur
) : ScrollView(context) {

    private val statut: TextView
    private val vueEnvois: LinearLayout
    private val texteEnvois: TextView
    private val boutonPartager: Button
    private val vueEnvoi: LinearLayout
    private val boutonEnvoyer: Button
    private val boutonsUsage = LinkedHashMap<String, Button>()

    init {
        val colonne = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun ajout(vue: View) {
            vue.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            colonne.addView(vue)
        }
        ajout(TextView(context).apply {
            text = "Scanner un document, page par page (20 pages " +
                "max par session). Le scanner de Google détecte " +
                "les bords, redresse, nettoie les ombres et " +
                "applique ses filtres. Le PDF reste sur " +
                "l'appareil, dans un dossier de session " +
                "horodaté, prêt à partager ou à envoyer à " +
                "Muse par le pont."
            textSize = 14f
        })
        val boutonScan = Button(context).apply {
            text = "Scanner"
            textSize = 22f
            setPadding(0, 40, 0, 40)
            setOnClickListener { controleur.lancerScan() }
        }
        ajout(boutonScan)
        statut = TextView(context).apply {
            textSize = 14f
            setPadding(0, 24, 0, 24)
        }
        ajout(statut)

        // État visible des envois de scans en attente
        // (2026-11-17 au soir, après l'incident du scan de
        // 20 pages refusé par le plafond du pont pendant que
        // son run passait : la pièce attendait en file et
        // l'app n'annonçait rien — Grégoire a cru son scan
        // perdu ; sa règle : aucun état invisible ou ambigu).
        // Alimentée par le contenu RÉEL de la file des pièces
        // (filesDir/scans-attente — la même file que les
        // reçus de l'onglet Reçus, qui passent par
        // Scans.envoyerDetail) ; elle n'apparaît que quand la
        // file est non vide. Affichage seulement : le
        // mécanisme d'envoi ne change pas.
        vueEnvois = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 16)
        }
        vueEnvois.addView(TextView(context).apply {
            text = "Envois en attente"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        })
        texteEnvois = TextView(context).apply {
            textSize = 14f
        }
        vueEnvois.addView(texteEnvois)
        ajout(vueEnvois)
        boutonPartager = Button(context).apply {
            text = "Partager le PDF"
            isEnabled = false
            setOnClickListener { controleur.partagerDerniereSession() }
        }
        ajout(boutonPartager)
        ajout(Button(context).apply {
            text = "Scanner à nouveau"
            setOnClickListener { controleur.lancerScan() }
        })

        // Envoi à Muse par le pont (V2) : visible seulement
        // quand un scan est enregistré. Choix d'usage en deux
        // gros boutons (« Livre » par défaut), puis l'envoi —
        // tout part en file locale d'abord, donc hors ligne OK.
        // L'usage « Reçu » n'existe plus ici (2026-11-17) :
        // les reçus se scannent depuis l'onglet Reçus du site,
        // par le pont natif de la WebView.
        vueEnvoi = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 32, 0, 0)
        }
        vueEnvoi.addView(TextView(context).apply {
            text = "Envoyer ce scan à Muse par le pont " +
                "(PDF pleine résolution, même file que les " +
                "textos et les appels). Pour quel usage ?"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })
        val rangeeUsage = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        for ((valeur, libelle) in listOf(
            "livre" to "Livre",
            "autre" to "Autre"
        )) {
            val b = Button(context).apply {
                text = libelle
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener { controleur.choisirUsage(valeur) }
            }
            boutonsUsage[valeur] = b
            rangeeUsage.addView(b)
        }
        vueEnvoi.addView(rangeeUsage)
        boutonEnvoyer = Button(context).apply {
            text = "Envoyer à Muse"
            textSize = 20f
            setPadding(0, 32, 0, 32)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { controleur.envoyerScanAMuse() }
        }
        vueEnvoi.addView(boutonEnvoyer)
        ajout(vueEnvoi)
        afficherUsage(controleur.usageActuel)

        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        visibility = View.GONE
        addView(colonne)
    }

    /** Le statut courant de l'onglet (scan, enregistrement, envoi…). */
    fun afficherStatut(message: String) {
        statut.text = message
    }

    /** Un scan vient d'être enregistré : partage et envoi deviennent possibles. */
    fun sessionEnregistree(nbPages: Int, nomSession: String) {
        boutonPartager.isEnabled = true
        vueEnvoi.visibility = View.VISIBLE
        statut.text = "Scan enregistré : " +
            "$nbPages page(s) (PDF).\n" +
            "Dossier : $nomSession\n" +
            "Le PDF est sur l'appareil, " +
            "prêt à partager."
    }

    /** La section « Envois en attente » — null : file vide, section cachée. */
    fun afficherEnvoisEnAttente(texte: String?) {
        if (texte == null) {
            vueEnvois.visibility = View.GONE
        } else {
            texteEnvois.text = texte
            vueEnvois.visibility = View.VISIBLE
        }
    }

    /** L'usage choisi (livre/autre), en gras dans la paire de boutons. */
    fun afficherUsage(usage: String) {
        for ((valeur, b) in boutonsUsage) {
            b.typeface =
                if (valeur == usage) {
                    Typeface.DEFAULT_BOLD
                } else {
                    Typeface.DEFAULT
                }
        }
    }

    /** Le bouton d'envoi, actif ou non pendant la mise en file. */
    fun setBoutonEnvoyerActif(actif: Boolean) {
        boutonEnvoyer.isEnabled = actif
    }
}
