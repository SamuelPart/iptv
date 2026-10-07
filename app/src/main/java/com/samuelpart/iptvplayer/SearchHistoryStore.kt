package com.samuelpart.iptvplayer

import android.content.Context

/**
 * Almacen compartido del historial de BUSQUEDAS de cine (mismo prefs y clave
 * que usa el rotador de Inicio: IPTV_PREFS / CINE_SEARCH_HISTORY, separadas
 * por "|||"). Guarda las ultimas 10 busquedas, sin duplicados, mas reciente
 * primero. Permite borrar una a una (papelera) o limpiar todo.
 */
object SearchHistoryStore {

    private const val PREFS = "IPTV_PREFS"
    const val KEY_CINE = "CINE_SEARCH_HISTORY"
    private const val SEP = "|||"
    private const val MAX = 10

    fun get(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CINE, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEP).filter { it.isNotBlank() }.take(MAX)
    }

    fun add(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = get(context).toMutableList()
        list.remove(q)
        list.add(0, q)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(context, list)
    }

    fun remove(context: Context, query: String) {
        val list = get(context).toMutableList()
        if (list.remove(query)) save(context, list)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CINE, "").apply()
    }

    private fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CINE, list.joinToString(SEP)).apply()
    }
}
