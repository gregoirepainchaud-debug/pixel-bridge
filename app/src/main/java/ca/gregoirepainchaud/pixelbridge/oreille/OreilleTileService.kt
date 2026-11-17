package ca.gregoirepainchaud.pixelbridge.oreille

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService


/**
 * Tuile « Oreille » du panneau de réglages rapides (design de
 * Grégoire, 2026-11-17) : swipe down (deux fois sur Pixel pour le
 * panneau complet — la tuile placée dans la première rangée se
 * voit dès le premier swipe), un tap, et le téléphone écoute ;
 * un autre tap, et l'écoute arrête et part au pont toute seule.
 *
 * Depuis le service robuste (2026-11-17 au soir), le tap pilote
 * OreilleService DIRECTEMENT — la séance vit dans le service
 * d'avant-plan, pas dans l'écran : plus besoin d'ouvrir l'app,
 * et fermer l'écran ne coupe plus l'écoute. Si le système refuse
 * le démarrage en arrière-plan ou si le micro n'est pas encore
 * autorisé, repli honnête : le tap ouvre l'écran Oreille, qui
 * demande la permission ou démarre par le chemin visible.
 * L'état de la tuile (allumée/éteinte) reflète la préférence
 * « ecoute_en_cours » que le service tient à jour — aucun état
 * invisible.
 */
class OreilleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = getSharedPreferences("bench", MODE_PRIVATE)
        val enCours = prefs.getBoolean("ecoute_en_cours", false)
        qsTile?.let { t ->
            t.label = "Oreille"
            t.icon = Icon.createWithResource(
                this, android.R.drawable.ic_btn_speak_now
            )
            t.state = if (enCours) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            t.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = getSharedPreferences("bench", MODE_PRIVATE)
        val enCours = prefs.getBoolean("ecoute_en_cours", false)
        if (enCours) {
            // Arrêt : le service roule déjà en avant-plan, lui
            // livrer l'ordre suffit — aucun écran ne s'ouvre.
            try {
                startService(OreilleService.intentionArreter(this, "tuile"))
            } catch (e: Exception) {
                ouvrirEcran(demarrer = false, arreter = true)
            }
            return
        }
        // Démarrage : micro déjà autorisé seulement, sinon
        // l'écran s'en charge (demande de permission visible).
        val microOk = checkSelfPermission(
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!microOk) {
            ouvrirEcran(demarrer = true, arreter = false)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(
                    OreilleService.intentionDemarrer(this, "tuile")
                )
            } else {
                startService(OreilleService.intentionDemarrer(this, "tuile"))
            }
        } catch (e: Exception) {
            // Démarrage d'arrière-plan refusé par le système :
            // repli sur l'écran, qui démarre au premier plan.
            ouvrirEcran(demarrer = true, arreter = false)
        }
    }

    private fun ouvrirEcran(demarrer: Boolean, arreter: Boolean) {
        val intent = Intent(this, OreilleActivity::class.java).apply {
            if (demarrer) {
                putExtra(OreilleActivity.EXTRA_DEMARRER_ECOUTE, true)
            }
            if (arreter) {
                putExtra(OreilleActivity.EXTRA_ARRETER_ECOUTE, true)
            }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        val enAttente = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startActivityAndCollapse(enAttente)
    }
}
