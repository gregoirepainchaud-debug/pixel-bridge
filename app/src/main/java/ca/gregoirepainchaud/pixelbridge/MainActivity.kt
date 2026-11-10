package ca.gregoirepainchaud.pixelbridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import ca.gregoirepainchaud.pixelbridge.service.BridgeService

/**
 * Écran minimal du squelette : état de la file. L'interface réelle
 * (validation des extractions d'un coup d'œil) viendra avec l'usage.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, BridgeService::class.java))

        val texte = TextView(this).apply {
            text = "Pixel Bridge\n\n" +
                "Pipeline local : appels et messages vocaux → transcription → " +
                "extraction structurée → synchro quand le réseau revient.\n\n" +
                "Tout fonctionne hors ligne."
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        setContentView(texte)
    }
}
