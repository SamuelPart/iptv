package com.samuelpart.iptvplayer

import android.content.Context

/**
 * Puente entre las pantallas de Ajustes y MainActivity.
 *
 * Las sub-pantallas (lista IPTV, parental, tema, acento, almacenamiento...)
 * no tienen acceso al estado en memoria de MainActivity (canales, filtros...),
 * así que escriben "peticiones" en SharedPreferences y MainActivity las
 * consume en onResume() para aplicar los cambios de verdad.
 */
object SettingsSync {

    private const val PREFS = "iptv_pref"
    private const val KEY_DIRTY = "settings_dirty"
    private const val KEY_PENDING_LOAD = "pending_list_url"
    private const val KEY_PENDING_CLEAR = "pending_clear_list"

    /** Marca que algo cambió (tema, acento, parental) y MainActivity debe
     *  volver a aplicar filtros/colores al volver. */
    fun mark(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DIRTY, true).apply()
    }

    fun consume(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_DIRTY, false)) {
            prefs.edit().remove(KEY_DIRTY).apply()
            true
        } else {
            false
        }
    }

    /** Pide a MainActivity cargar una lista M3U (URL introducida o QR). */
    fun requestLoadList(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING_LOAD, url).apply()
    }

    fun consumePendingLoad(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_PENDING_LOAD, null)
        if (url != null) prefs.edit().remove(KEY_PENDING_LOAD).apply()
        return url
    }

    /** Pide a MainActivity limpiar la lista actual. */
    fun requestClearList(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING_CLEAR, true).apply()
    }

    fun consumePendingClear(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_PENDING_CLEAR, false)) {
            prefs.edit().remove(KEY_PENDING_CLEAR).apply()
            true
        } else {
            false
        }
    }
}
