package ca.gregoirepainchaud.pixelbridge.gestion.web

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

// ---------------------------------------------------------------------------
// La WebView de l'onglet Gestion (chantier structure 0.8) : le
// site actuel, tel quel, en plein écran
// (app.gregoirepainchaud.ca). La WebView expose à la page un
// pont natif minimal, « GestionAndroid » (PontScannerRecu) :
// la section Reçus du site s'en sert, quand il est présent,
// pour lancer le scanner documentaire ML Kit. Sous Android
// seulement ; sur le web, la page ne voit pas le pont et son
// scanner reste intact.
// ---------------------------------------------------------------------------

object GestionWebView {

    const val URL_GESTION = "https://app.gregoirepainchaud.ca"

    fun creer(activite: Activity, pont: PontScannerRecu): WebView =
        WebView(activite).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            // Pont natif du scanner de reçus : présent
            // seulement dans l'app — la page web ne le
            // voit jamais (voir PontScannerRecu).
            addJavascriptInterface(pont, "GestionAndroid")
            loadUrl(URL_GESTION)
        }
}
