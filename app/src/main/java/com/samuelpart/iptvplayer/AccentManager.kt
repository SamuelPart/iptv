package com.samuelpart.iptvplayer

import android.content.Context
import android.content.res.ColorStateList
import android.view.View

/** App-wide accent color (cyan/violet/coral/green) — stored locally, re-tints nav, player and segment pickers. */
object AccentManager {

    data class AccentOption(val key: String, val label: String, val color: Int)

    val OPTIONS = listOf(
        AccentOption("cyan", "Cyan (por defecto)", 0xFF64D2FF.toInt()),
        AccentOption("violet", "Violeta", 0xFFBF5AF2.toInt()),
        AccentOption("coral", "Coral", 0xFFFF6E6E.toInt()),
        AccentOption("verde", "Verde", 0xFF30D158.toInt())
    )

    private const val KEY = "accent_key"

    fun getKey(ctx: Context): String =
        ctx.getSharedPreferences("iptv_pref", Context.MODE_PRIVATE).getString(KEY, "cyan") ?: "cyan"

    fun getLabel(ctx: Context): String = OPTIONS.firstOrNull { it.key == getKey(ctx) }?.label ?: OPTIONS[0].label

    fun color(ctx: Context): Int =
        OPTIONS.firstOrNull { it.key == getKey(ctx) }?.color ?: OPTIONS[0].color

    fun set(ctx: Context, key: String) {
        ctx.getSharedPreferences("iptv_pref", Context.MODE_PRIVATE).edit().putString(KEY, key).apply()
    }

    fun list(ctx: Context) = ColorStateList.valueOf(color(ctx))

    /** Selected = accent / unselected = iOS gray (bottom nav style). */
    fun navTintList(ctx: Context): ColorStateList {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val colors = intArrayOf(color(ctx), 0xFF8E8E93.toInt())
        return ColorStateList(states, colors)
    }

    /** Solid background tint for the accent preview circle in Settings. */
    fun applyPreviewTint(view: View, ctx: Context) {
        view.backgroundTintList = list(ctx)
    }
}
