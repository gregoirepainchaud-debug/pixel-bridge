package ca.gregoirepainchaud.pixelbridge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Synchro OPPORTUNISTE : le travail n'exige rien tant que le réseau
 * est absent, et part dès qu'une connexion revient (contrainte
 * NetworkType.CONNECTED). On pousse d'abord les JSON extraits
 * (légers) ; l'audio ne suit que sur demande explicite.
 */
class Synchroniseur(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // TODO : pousser les éléments EN_FILE_SYNCHRO, marquer SYNCHRONISE.
        return Result.success()
    }

    companion object {
        fun planifier(context: Context) {
            val contraintes = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val requete = PeriodicWorkRequestBuilder<Synchroniseur>(1, TimeUnit.HOURS)
                .setConstraints(contraintes)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "synchro-pixel-bridge",
                ExistingPeriodicWorkPolicy.KEEP,
                requete,
            )
        }
    }
}
