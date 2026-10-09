package com.samuelpart.iptvplayer

import android.app.NotificationChannel
import android.net.Uri
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
            if (sync.addedTitles > 0) notifyNewContent(sync.addedTitles, sync.samples)
        }
        // El bot se vuelve a programar solo, con otro intervalo aleatorio.
        CatalogBot.scheduleNext(applicationContext)
        return Result.success()
    }

    private fun notifyNewContent(added: Int, samples: List<String> = emptyList()) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val channelId = "catalog_sync_v2"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // Borra el canal viejo para que el NUEVO sonido aplique al instante
            try { nm.deleteNotificationChannel("catalog_sync") } catch (_: Exception) {}
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                channelId, "Actualizaciones del catálogo",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Nuevas películas y series al instante"
                setSound(
                    Uri.parse("${android.content.ContentResolver.SCHEME_ANDROID_RESOURCE}://${ctx.packageName}/${R.raw.notif_chime}"),
                    attrs
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
            }
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(ctx, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Texto con los NOMBRES de los estrenos
        val text = when {
            samples.isEmpty() -> "$added ${if (added == 1) "título nuevo" else "títulos nuevos"} añadidos al catálogo"
            samples.size == 1 -> "Ya disponible: ${samples[0]}"
            else -> "Ya disponibles: ${samples.dropLast(1).joinToString()} y ${samples.last()}" +
                if (added > samples.size) " (+${added - samples.size} más)" else ""
        }
        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.drawable.ic_ios_movie)
            .setContentTitle("🎬 ¡Nuevo contenido!")
            .setContentText(text)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle()
                .bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(90, notif) } catch (_: Exception) {}
    }
}

object CatalogBot {

    const val KEY_CATALOG_CHANGED = "catalog_changed"
    private const val UNIQUE_WORK = "catalog_sync_bot"

    /** Primer lanzamiento (al abrir la app): arranca en ~1 min y luego se
     *  reprograma solo con intervalos cortos, para avisar CUANTO ANTES. */
    fun start(context: Context) {
        scheduleIn(context, TimeUnit.MINUTES.toMillis(1))
    }

    /** Reprograma el bot con un intervalo ALEATORIO CORTO (5–10 min): la
     *  notificacion de tu contenido nuevo llega sin abrir la app. */
    fun scheduleNext(context: Context) {
        val delayMinutes = Random.nextLong(5, 11) // 5..10 minutos
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
