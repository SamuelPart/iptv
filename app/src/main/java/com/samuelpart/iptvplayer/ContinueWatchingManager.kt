package com.samuelpart.iptvplayer

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** Local "Continue Watching" store: last watched channels (and cine position) persisted in SharedPreferences. */
object ContinueWatchingManager {

    data class ResumeEntry(
        val url: String,
        val title: String,
        val isChannel: Boolean,
        val channelLogo: String? = null,
        val media: CineMedia? = null,
        val savedAt: Long,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    ) : Serializable

    private const val KEY_ITEMS = "continue_watching_v1"
    private const val MAX_ITEMS = 12

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)

    private fun toB64(obj: Any): String {
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { it.writeObject(obj) }
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> fromB64(str: String): T? = try {
        if (str.isEmpty()) null
        else {
            val bytes = android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
            ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as T }
        }
    } catch (_: Exception) {
        null
    }

    /** Most recent first. */
    fun getAll(context: Context): List<ResumeEntry> {
        val str = prefs(context).getString(KEY_ITEMS, "") ?: ""
        return fromB64<List<ResumeEntry>>(str) ?: emptyList()
    }

    private fun saveAll(context: Context, items: List<ResumeEntry>) {
        prefs(context).edit().putString(KEY_ITEMS, toB64(items.take(MAX_ITEMS))).apply()
    }

    /** Upserts by url and moves it to the front. */
    fun save(context: Context, entry: ResumeEntry) {
        val items = getAll(context).filter { it.url != entry.url }.toMutableList()
        items.add(0, entry)
        saveAll(context, items)
    }

    fun remove(context: Context, url: String) {
        saveAll(context, getAll(context).filter { it.url != url })
    }
}
