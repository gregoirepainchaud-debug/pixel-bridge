package ca.gregoirepainchaud.pixelbridge.oreille

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ca.gregoirepainchaud.pixelbridge.bench.BenchActivity

/**
 * Tuile « Oreille » du panneau de réglages rapides (design de
 * Grégoire, 2026-11-17) : un geste système — swipe down, un tap
 * sur l'oreille — et le téléphone se met à écouter : vocal
 * Messenger joué à voix haute, réunion, conversation au comptoir.
 *
 * v1, simple et prévisible : le tap ouvre l'écran Oreille et y
 * démarre l'écoute si elle est arrêtée ; si l'écoute roule déjà,
 * le tap rouvre simplement l'écran (l'arrêt se fait au bouton de
 * l'écran). L'état de la tuile (allumée/éteinte) reflète la
 * préférence « ecoute_en_cours » que l'écran Oreille tient à
 * jour — aucun état invisible.
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
        val intent = Intent(this, BenchActivity::class.java).apply {
            putExtra(BenchActivity.EXTRA_DEMARRER_ECOUTE, true)
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
