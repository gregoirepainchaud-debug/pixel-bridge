package ca.gregoirepainchaud.pixelbridge.gps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import ca.gregoirepainchaud.pixelbridge.GestionActivity
import ca.gregoirepainchaud.pixelbridge.R
import ca.gregoirepainchaud.pixelbridge.commun.PreferencesApp

/**
 * Service d'avant-plan du suivi de trajet natif (0.16) : il
 * tient la machine GpsLogique et branche le monde réel dessus.
 * Toute la discipline d'énergie est dans la machine ; le
 * service ne fait qu'exécuter ses actions :
 *
 * - VEILLE : dormance complète. Aucun abonnement de position,
 *   aucun minuteur, aucun verrou de réveil. Il ne reste que
 *   deux gardes événementielles à consommation négligeable :
 *   le capteur de mouvement significatif (déclencheur matériel,
 *   un coup) et le récepteur des connexions Bluetooth. La
 *   notification d'avant-plan, discrète (importance minimale),
 *   est la seule trace visible — avec l'état publié dans les
 *   préférences, que l'onglet Réglages affiche.
 * - Un réveil (mouvement, Bluetooth de l'auto) ouvre une
 *   VÉRIFICATION : écoute des positions bornée (90 s, 150 s si
 *   le signal est fort), rien n'est enregistré. La conduite
 *   doit se qualifier à 20 km/h soutenu — sinon tout se
 *   rendort, sans un point et sans trajet fantôme.
 * - CONDUITE/PAUSE : positions GPS + réseau + passif, points
 *   selon la machine, lots de 10 vers le serveur Gestion
 *   (GpsFichier), verrou de réveil bref et borné autour des
 *   envois seulement.
 *
 * GrapheneOS sans Play services : LocationManager natif
 * uniquement, jamais de FusedLocationProvider.
 *
 * L'auto s'apprend : l'appareil Bluetooth connecté pendant
 * une conduite qualifiée est retenu (préférences) et devient
 * un signal fort pour les fois suivantes, au même titre que
 * les noms connus (Subaru, smartbox).
 */
class GpsService : Service() {

    private val machine = GpsLogique()
    private val tampon = ArrayList<FixGps>()

    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var capteurMouvement: Sensor? = null

    @Volatile
    private var ecouteOuverte = false
    private var minuteurActif = false
    private val horloge = Handler(Looper.getMainLooper())

    // Appareil Bluetooth courant (le dernier connecté, pas
    // encore déconnecté) — sert à apprendre l'auto.
    @Volatile
    private var btNom: String? = null
    @Volatile
    private var btAdresse: String? = null

    private val tacheMinuteur = object : Runnable {
        override fun run() {
            appliquer(machine.surTemps(System.currentTimeMillis()))
            if (machine.etat != EtatGps.VEILLE) {
                horloge.postDelayed(this, 5_000L)
            } else {
                minuteurActif = false
            }
        }
    }

    private val declencheurMouvement = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            // Le capteur est à un coup : la machine décide —
            // vérification si on dormait, rien sinon. Le
            // réarmement se fait au retour en VEILLE.
            if (machine.etat == EtatGps.VEILLE) {
                appliquer(
                    machine.surReveil(
                        System.currentTimeMillis(),
                        SourceReveil.MOUVEMENT,
                        signalFort = btAdresse != null
                    )
                )
            }
        }
    }

    private val ecouteurPositions = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val ts = if (location.time > 0) location.time
            else System.currentTimeMillis()
            val fix = FixGps(
                tsMs = ts,
                lat = location.latitude,
                lon = location.longitude,
                precisionM =
                if (location.hasAccuracy()) {
                    location.accuracy.toDouble()
                } else {
                    null
                },
                vitesseMps =
                if (location.hasSpeed()) {
                    location.speed.toDouble()
                } else {
                    null
                }
            )
            appliquer(machine.surFix(fix))
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val recepteurBluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val appareil = try {
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } catch (e: Exception) {
                null
            } ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    surBluetoothConnecte(appareil)
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    surBluetoothDeconnecte(appareil)
            }
        }
    }

    // ------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        if (!GpsEtat.actif(this)) {
            stopSelf()
            return
        }
        locationManager =
            getSystemService(LocationManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        capteurMouvement = sensorManager.getDefaultSensor(
            Sensor.TYPE_SIGNIFICANT_MOTION
        )
        creerCanal()
        passerAvantPlan()
        GpsEtat.publierEtat(this, machine.etat)
        ContextCompat.registerReceiver(
            this,
            recepteurBluetooth,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        armerCapteurMouvement()
        // Lots restés en file d'une session précédente.
        Thread { GpsFichier.vider(this) }.start()
    }

    override fun onStartCommand(
        intent: Intent?, flags: Int, startId: Int
    ): Int {
        if (!GpsEtat.actif(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Rien ne se perd : le tampon non envoyé part en file.
        synchronized(tampon) {
            if (tampon.isNotEmpty()) {
                try {
                    GpsFichier.ecrireLot(this, ArrayList(tampon))
                } catch (e: Exception) {
                    // Le service meurt quand même proprement.
                }
                tampon.clear()
            }
        }
        try {
            unregisterReceiver(recepteurBluetooth)
        } catch (e: Exception) {
            // Récepteur jamais enregistré (arrêt précoce).
        }
        desarmerCapteurMouvement()
        fermerEcoutePositions()
        horloge.removeCallbacks(tacheMinuteur)
        minuteurActif = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------
    // Application des actions de la machine
    // ------------------------------------------------------------

    private fun appliquer(actions: List<ActionGps>) {
        val etatAvant = machine.etat
        for (action in actions) {
            when (action) {
                ActionGps.DemanderPositions -> ouvrirEcoutePositions()
                ActionGps.ArreterPositions -> fermerEcoutePositions()
                is ActionGps.Enregistrer -> {
                    synchronized(tampon) { tampon.add(action.fix) }
                    GpsEtat.publierDernierPoint(this, action.fix.tsMs)
                    if (tampon.size >= TAILLE_LOT) vidanger()
                }
                ActionGps.Vidanger -> vidanger()
            }
        }
        val etatApres = machine.etat
        if (etatApres != etatAvant) {
            GpsEtat.publierEtat(this, etatApres)
            mettreAJourNotification()
            if (etatApres == EtatGps.CONDUITE &&
                btAdresse != null
            ) {
                // Conduite qualifiée pendant que cet appareil
                // est connecté : c'est l'auto, on l'apprend.
                GpsEtat.publierAutoApprise(this, btNom, btAdresse!!)
            }
            if (etatApres == EtatGps.VEILLE) {
                armerCapteurMouvement()
            }
        }
        // Le minuteur d'échéances ne tourne que hors VEILLE.
        if (etatApres != EtatGps.VEILLE && !minuteurActif) {
            minuteurActif = true
            horloge.postDelayed(tacheMinuteur, 5_000L)
        }
    }

    // ------------------------------------------------------------
    // Positions
    // ------------------------------------------------------------

    private fun ouvrirEcoutePositions() {
        if (ecouteOuverte) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Sans permission, la vérification ne peut rien
            // entendre : l'état reste publié (Réglages le
            // montre) et la fenêtre se refermera toute seule.
            return
        }
        ecouteOuverte = true
        val fournisseurs = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (fournisseur in fournisseurs) {
            try {
                if (locationManager.isProviderEnabled(fournisseur)) {
                    locationManager.requestLocationUpdates(
                        fournisseur, 3_000L, 0f,
                        ecouteurPositions, Looper.getMainLooper()
                    )
                }
            } catch (e: Exception) {
                // Fournisseur absent ou refusé : les autres
                // continuent.
            }
        }
    }

    private fun fermerEcoutePositions() {
        if (!ecouteOuverte) return
        ecouteOuverte = false
        try {
            locationManager.removeUpdates(ecouteurPositions)
        } catch (e: Exception) {
            // Déjà fermée.
        }
    }

    // ------------------------------------------------------------
    // Mouvement significatif (garde de la dormance)
    // ------------------------------------------------------------

    private fun armerCapteurMouvement() {
        val capteur = capteurMouvement ?: return
        try {
            sensorManager.requestTriggerSensor(
                declencheurMouvement, capteur
            )
        } catch (e: Exception) {
            // Capteur absent : le Bluetooth reste une garde.
        }
    }

    private fun desarmerCapteurMouvement() {
        val capteur = capteurMouvement ?: return
        try {
            sensorManager.cancelTriggerSensor(
                declencheurMouvement, capteur
            )
        } catch (e: Exception) {
            // Rien à annuler.
        }
    }

    // ------------------------------------------------------------
    // Bluetooth de l'auto
    // ------------------------------------------------------------

    private fun surBluetoothConnecte(appareil: BluetoothDevice) {
        val adresse = try {
            appareil.address
        } catch (e: Exception) {
            null
        } ?: return
        val nom = try {
            appareil.name
        } catch (e: Exception) {
            null
        }
        // Seuls les appareils déjà jumelés comptent (les siens).
        val jumele = try {
            getSystemService(BluetoothManager::class.java)
                ?.adapter?.bondedDevices
                ?.any { it.address == adresse } == true
        } catch (e: Exception) {
            false
        }
        if (!jumele) return
        btNom = nom
        btAdresse = adresse
        GpsEtat.publierBluetooth(this, true, nom, adresse)
        appliquer(
            machine.surBluetoothAuto(
                connectee = true,
                nowMs = System.currentTimeMillis(),
                signalFort = estSignalFort(nom, adresse)
            )
        )
    }

    private fun surBluetoothDeconnecte(appareil: BluetoothDevice) {
        val adresse = try {
            appareil.address
        } catch (e: Exception) {
            null
        }
        if (adresse != null && adresse == btAdresse) {
            btNom = null
            btAdresse = null
            GpsEtat.publierBluetooth(this, false, null, null)
        }
        // La machine ignore la déconnexion : la vitesse décide.
        appliquer(
            machine.surBluetoothAuto(
                connectee = false,
                nowMs = System.currentTimeMillis(),
                signalFort = false
            )
        )
    }

    /** Signal fort : nom connu (Subaru, smartbox) ou l'auto
     *  apprise lors d'une conduite précédente. */
    private fun estSignalFort(nom: String?, adresse: String): Boolean {
        val n = nom?.lowercase().orEmpty()
        if (n.contains("subaru") || n.contains("smartbox")) {
            return true
        }
        val apprise = PreferencesApp.prefs(this).getString(
            PreferencesApp.CLE_GPS_AUTO_ADRESSE, ""
        )
        return !apprise.isNullOrEmpty() && apprise == adresse
    }

    // ------------------------------------------------------------
    // Envois
    // ------------------------------------------------------------

    /**
     * Le tampon part en file, puis la file tente l'envoi — sur
     * un fil d'arrière-plan, sous un verrou de réveil borné
     * (60 s maximum, relâché dès la fin) : jamais de verrou
     * tenu au-delà du relevé + envoi.
     */
    private fun vidanger() {
        val lot: List<FixGps>
        synchronized(tampon) {
            lot = ArrayList(tampon)
            tampon.clear()
        }
        if (lot.isNotEmpty()) {
            try {
                GpsFichier.ecrireLot(this, lot)
            } catch (e: Exception) {
                // Écriture impossible : les points restent au
                // tampon pour la prochaine occasion.
                synchronized(tampon) { tampon.addAll(0, lot) }
                return
            }
        }
        Thread {
            val pm = getSystemService(PowerManager::class.java)
            val verrou = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "gestion:gps-envoi"
            )
            verrou.setReferenceCounted(false)
            try {
                verrou.acquire(60_000L)
                GpsFichier.vider(this)
            } catch (e: Exception) {
                // L'envoi réessaiera à la prochaine occasion.
            } finally {
                if (verrou.isHeld) verrou.release()
            }
        }.start()
    }

    // ------------------------------------------------------------
    // Notification discrète
    // ------------------------------------------------------------

    private fun creerCanal() {
        val canal = NotificationChannel(
            CANAL_ID, "Suivi de trajet",
            NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(canal)
    }

    private fun construireNotification(): Notification {
        val ouvrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, GestionActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CANAL_ID)
            .setContentTitle("Gestion — suivi de trajet")
            .setContentText(GpsEtat.libelle(machine.etat.name))
            .setSmallIcon(R.drawable.ic_gps)
            .setContentIntent(ouvrir)
            .setOngoing(true)
            .build()
    }

    private fun passerAvantPlan() {
        val notif = construireNotification()
        try {
            if (checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIF_ID, notif)
            } catch (e2: Exception) {
                // Avant-plan refusé (permission d'arrière-plan
                // absente, p. ex.) : le service continue sans,
                // l'état reste visible dans les Réglages.
            }
        }
    }

    private fun mettreAJourNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, construireNotification())
    }

    companion object {
        private const val CANAL_ID = "gestion-gps-trajet"
        private const val NOTIF_ID = 2
        private const val TAILLE_LOT = 10

        /** Démarrage tolérant : un refus du système (arrière-
         *  plan restreint, p. ex.) ne plante jamais l'appelant —
         *  l'état publié dans les Réglages dira la vérité. */
        fun demarrer(ctx: Context) {
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, GpsService::class.java)
                )
            } catch (e: Exception) {
                // Le démarrage au boot réessaiera au prochain
                // démarrage de l'appareil ou de l'app.
            }
        }
    }
}

/** Démarrage automatique au boot (le manifeste porte déjà
 *  RECEIVE_BOOT_COMPLETED) : le service se relance tout seul,
 *  en dormance, sans ouvrir l'app. */
class GpsDemarrageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            GpsEtat.actif(context)
        ) {
            GpsService.demarrer(context)
        }
    }
}
