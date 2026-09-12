package com.samuelpart.iptvplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * BOT del catálogo: refresca cine_catalog.m3u desde GitHub en segundo plano,
 * a intervalos ALEATORIOS (para que GitHub/Gradle no lo vean como un cron fijo),
 * sin necesidad de abrir la app ni de reinstalar.
 *
 *  - Si detecta películas/series nuevas, avisa con una notificación.
 *  - Guarda la copia fresca en disco para que la app la use al abrir.
 *  - Marca "catalog_changed" para que MainActivity repinte el Cine al volver.
 */
class CatalogSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = try {
            CineRepository.syncCatalogFromGithub(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        // Cualquier cambio (título nuevo o editado) marca la bandera para que
        // MainActivity repinte el catálogo sin reinstalar.
        if (sync?.changed == true) {
            applicationContext.getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
                .edit().putBoolean(CatalogBot.KEY_CATALOG_CHANGED, true).apply()
            if (sync.addedTitles > 0) notifyNewContent(sync.addedTitles)
        }
        // El bot se vuelve a programar solo, con otro intervalo aleatorio.
        CatalogBot.scheduleNext(applicationContext)
        return Result.success()
    }

    private fun notifyNewContent(added: Int) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val channelId = "catalog_sync"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                channelId, "Actualizaciones del catálogo",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Nuevas películas y series" }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val intent = Intent(ctx, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_ios_movie)
            .setContentTitle("🎬 ¡Nuevo contenido!")
            .setContentText("$added ${if (added == 1) "título nuevo" else "títulos nuevos"} añadidos al catálogo")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(90, notif) } catch (_: Exception) {}
    }
}

object CatalogBot {

    const val KEY_CATALOG_CHANGED = "catalog_changed"
    private const val UNIQUE_WORK = "catalog_sync_bot"

    /** Primer lanzamiento (al abrir la app): arranca en ~2 min y luego se
     *  reprograma solo con intervalos aleatorios. */
    fun start(context: Context) {
        scheduleIn(context, TimeUnit.MINUTES.toMillis(2))
    }

    /** Reprograma el bot con un intervalo ALEATORIO (25 min – 2 h). */
    fun scheduleNext(context: Context) {
        val delayMinutes = Random.nextLong(25, 121) // 25..120 minutos
        scheduleIn(context, TimeUnit.MINUTES.toMillis(delayMinutes))
    }

    private fun scheduleIn(context: Context, delayMs: Long) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun consumeChangedFlag(context: Context): Boolean {
        val prefs = context.getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_CATALOG_CHANGED, false)) {
            prefs.edit().remove(KEY_CATALOG_CHANGED).apply()
            true
        } else false
    }
}
