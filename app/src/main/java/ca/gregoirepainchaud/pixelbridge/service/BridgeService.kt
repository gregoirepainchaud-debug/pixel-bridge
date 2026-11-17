package ca.gregoirepainchaud.pixelbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import ca.gregoirepainchaud.pixelbridge.R
import ca.gregoirepainchaud.pixelbridge.data.FileTraitement
import ca.gregoirepainchaud.pixelbridge.ingestion.Guetteur
import ca.gregoirepainchaud.pixelbridge.ingestion.GuetteurAppels
import ca.gregoirepainchaud.pixelbridge.ingestion.GuetteurMessenger
import ca.gregoirepainchaud.pixelbridge.ingestion.GuetteurNotesVocales
import ca.gregoirepainchaud.pixelbridge.sync.Synchroniseur

/**
 * Service d'avant-plan discret qui tient les guetteurs en vie.
 * La téléphonie ne dépend jamais de lui : s'il meurt, le téléphone
 * continue de téléphoner, et START_STICKY le relance.
 */
class BridgeService : Service() {

    private val file = FileTraitement()
    private val guetteurs: List<Guetteur> = listOf(
        GuetteurAppels(),
        GuetteurNotesVocales(),
        GuetteurMessenger(),
    )

    override fun onCreate() {
        super.onCreate()
        creerCanal()
        startForeground(NOTIF_ID, construireNotification())
        guetteurs.forEach { g -> g.demarrer { element -> file.ajouter(element) } }
        Synchroniseur.planifier(this)
    }

    override fun onDestroy() {
        guetteurs.forEach { it.arreter() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun creerCanal() {
        val canal = NotificationChannel(
            CANAL_ID, "Pixel Bridge", NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun construireNotification(): Notification =
        Notification.Builder(this, CANAL_ID)
            .setContentTitle("Pixel Bridge")
            .setContentText("Écoute locale active — tout reste sur l'appareil")
            .setSmallIcon(R.drawable.ic_oreille)
            .build()

    companion object {
        private const val CANAL_ID = "pixel-bridge"
        private const val NOTIF_ID = 1
    }
}
