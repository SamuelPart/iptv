package com.samuelpart.iptvplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Aviso de estrenos: calcula el diff entre lo que el catálogo tenía la vez
 * anterior y el actual; por cada película/serie NUEVA (hasta 3) lanza una
 * notificación con titulo, poster destacado y genero.
 */
object CineNewNotifier {

    private const val CHANNEL_ID = "lumen_cine_nuevo_sound"
    private const val LEGACY_CHANNEL_ID = "lumen_cine_nuevo"
    private const val PREFS = "cine_latest_state"
    private const val KEY_SEEN = "seen_titles_v1"

    /** URI del sonido unico de estreno (campanilla propia de la app). */
    private fun cineSoundUri(context: Context): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.cine_new_sound}")

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Borra el canal viejo (sin sonido propio) para que el nuevo con
            // sonido unico aplique de inmediato en instalaciones existentes.
            try { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) } catch (_: Exception) { }
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Estrenos de Lumen", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Avísanos cuando una película o serie nueva llega al catálogo"
                        setSound(cineSoundUri(context), attrs)
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 220, 140, 220)
                    }
                )
            }
        }
    }

    /** Primera ejecución solo guarda la foto; despues, solo notifica lo NUEVO. */
    fun onCatalogLoaded(context: Context, catalog: List<CineMedia>) {
        // El catálogo tiene ~9.5k titulos: leer/escribir el set en SharedPreferences
        // serializa XML pesado. Se hace en IO para NO congelar la UI al arrancar.
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet(KEY_SEEN, null)
            val current = catalog.map { it.title }.toSet()
            if (seen == null) {
                prefs.edit().putStringSet(KEY_SEEN, current).apply()
                return@launch
            }
            val fresh = catalog.filter { it.title !in seen }
            prefs.edit().putStringSet(KEY_SEEN, current).apply()
            if (fresh.isEmpty()) return@launch
            if (Build.VERSION.SDK_INT >= 33 &&
                ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return@launch

            ensureChannel(context)
            fresh.take(3).forEachIndexed { i, media ->
                postNotification(context, media, 100 + i)
            }
        }
    }

    private fun postNotification(context: Context, media: CineMedia, id: Int) {
        val genre = TasteProfile.genreKeysOf(media).firstOrNull()
            ?.replaceFirstChar { it.uppercase() } ?: media.group
        CoroutineScope(Dispatchers.Default).launch {
            var bmp: Bitmap? = null
            try {
                bmp = Glide.with(context).asBitmap().load(media.posterUrl).submit().get()
            } catch (_: Exception) { /* sin imagen, sigue con texto */ }
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ios_movie)
                .setContentTitle("✨ Estreno en Lumen")
                .setContentText("${media.title} · $genre")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                // Sonido unico de estreno (en Android 7- el canal no existe,
                // asi que se fija aqui directamente)
                .setSound(cineSoundUri(context), android.media.AudioManager.STREAM_NOTIFICATION)
                .setVibrate(longArrayOf(0, 220, 140, 220))
            if (bmp != null) {
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).setSummaryText(genre))
            }
            // Registro historico de alertas (para el menu: Historico de estrenos)
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val prev = prefs.getString("alerts_log_v1", "") ?: ""
                val entry = "${media.title}|${genre}|${media.posterUrl ?: ""}"
                val lines = (listOf(entry) + prev.split("\n").filter { it.isNotBlank() }).take(30)
                prefs.edit().putString("alerts_log_v1", lines.joinToString("\n")).apply()
            } catch (_: Exception) { }
            try {
                NotificationManagerCompat.from(context).notify(id, builder.build())
            } catch (_: SecurityException) { /* permiso revocado */ }
        }
    }
}
