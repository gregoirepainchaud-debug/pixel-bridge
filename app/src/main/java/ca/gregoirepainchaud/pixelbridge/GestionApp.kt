package ca.gregoirepainchaud.pixelbridge

import android.app.Application

/**
 * Point d'entrée de l'app (2026-11-18, version 0.6) : son seul
 * rôle est d'installer, dès le démarrage du processus, le
 * gestionnaire qui persiste la trace d'un plantage éventuel
 * (voir TracePlantage.kt). Aucun autre comportement.
 */
class GestionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TracePlantage.installer(this)
    }
}
