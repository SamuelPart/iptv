package com.samuelpart.iptvplayer

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/** Local Favorites store: channels + cine media persisted in SharedPreferences (Base64 serialized). */
object FavoritesManager {

    data class FavoriteItem(
        val url: String,
        val isChannel: Boolean,
        val channel: Channel? = null,
        val media: CineMedia? = null
    ) : Serializable

    private const val KEY_ITEMS = "favorites_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)

    private fun toB64(obj: Serializable): String {
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

    fun getAll(context: Context): List<FavoriteItem> {
        val str = prefs(context).getString(KEY_ITEMS, "") ?: ""
        return fromB64<List<FavoriteItem>>(str) ?: emptyList()
    }

    private fun saveAll(context: Context, items: List<FavoriteItem>) {
        prefs(context).edit().putString(KEY_ITEMS, toB64(items.toList())).apply()
    }

    fun isFavorite(context: Context, url: String): Boolean =
        getAll(context).any { it.url == url }

    /** Toggles a channel favorite. Returns the new state (true = added). */
    fun toggleChannel(context: Context, channel: Channel): Boolean {
        val items = getAll(context).toMutableList()
        val idx = items.indexOfFirst { it.url == channel.url }
        return if (idx >= 0) {
            items.removeAt(idx)
            saveAll(context, items)
            false
        } else {
            items.add(FavoriteItem(url = channel.url, isChannel = true, channel = channel))
            saveAll(context, items)
            true
        }
    }

    /** Toggles a cine media favorite. Returns the new state (true = added). */
    fun toggleMedia(context: Context, media: CineMedia): Boolean {
        val items = getAll(context).toMutableList()
        val idx = items.indexOfFirst { it.url == media.url }
        return if (idx >= 0) {
            items.removeAt(idx)
            saveAll(context, items)
            false
        } else {
            items.add(FavoriteItem(url = media.url, isChannel = false, media = media))
            saveAll(context, items)
            true
        }
    }

    fun remove(context: Context, url: String) {
        val items = getAll(context).filter { it.url != url }
        saveAll(context, items)
    }

    fun count(context: Context): Int = getAll(context).size
}
