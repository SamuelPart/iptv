package com.samuelpart.iptvplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64

/**
 * Enlaces profundos de Lumen: los contenidos compartidos viajan como
 *     lumen://ver?d=<titulo en Base64 url-safe>
 * Si quien recibe el mensaje tiene la app, al tocar el enlace se abre
 * DIRECTAMENTE ese titulo en el Cine. Si no la tiene, Android ofrece
 * abrirlo con la tienda (fallback elegante).
 *
 * Se usa Base64URL (no el titulo plano) para que WhatsApp/Telegram no
 * rompa el enlace por los espacios ni lo convierta en mention @algo.
 */
object DeepLink {

    const val SCHEME_HOST = "lumen://ver"

    /** Construye el enlace profundo a partir del titulo mostrado. */
    fun build(title: String): String {
        val raw = title.trim().toByteArray(Charsets.UTF_8)
        val b64 = Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "$SCHEME_HOST?d=$b64"
    }

    /** Decodifica el titulo desde el enlace (null si el enlace es invalido). */
    fun titleFromUri(uri: Uri?): String? {
        if (uri == null) return null
        if (!uri.toString().startsWith(SCHEME_HOST)) return null
        val d = uri.getQueryParameter("d") ?: return null
        return try {
            val bytes = Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val t = String(bytes, Charsets.UTF_8).trim()
            if (t.isEmpty()) null else t
        } catch (_: Exception) {
            null
        }
    }

    /** True si este intent de inicio trae un titulo que resolver. */
    fun fromIntent(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        return titleFromUri(uri)
    }

    /** Texto que acompania a la tarjeta compartida. */
    fun shareText(context: Context, title: String): String =
        "$title\n🎬 Mira esto en Lumen: ${build(title)}"
}
