package com.samuelpart.iptvplayer

import android.content.Context

/**
 * MI LISTA: los títulos que el usuario guarda con el botón + del héroe.
 * Simple y persistente (SharedPreferences, conjunto de URLs).
 */
object FavoritesStore {
    private const val PREFS = "cine_favoritos"
    private const val KEY = "favs_urls"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun urls(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()) ?: emptySet()

    fun isFav(context: Context, url: String): Boolean = urls(context).contains(url)

    /** Agrega/quita; devuelve el estado NUEVO (true = quedó en la lista). */
    fun toggle(context: Context, url: String): Boolean {
        val p = prefs(context)
        val set = LinkedHashSet(p.getStringSet(KEY, emptySet()) ?: emptySet())
        if (!set.remove(url)) set.add(url)
        p.edit().putStringSet(KEY, set).apply()
        return set.contains(url)
    }
}
