package com.samuelpart.iptvplayer

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Tips contextuales inteligentes (idea #2): micro-ayudas de UNA linea que
 * aparecen SOLO la primera vez que el usuario llega a un lugar de la app.
 *
 *  - No interrumpen: no hay que tocarlos, los toques pasan de largo.
 *  - Se van solos a los ~5s con fundido.
 *  - Cada tip se muestra UNA vez por instalacion (marca en SharedPreferences).
 *  - Estilo vidrio Apple (misma familia del dock y del reproductor).
 */
object SmartTips {

    private const val PREFS = "iptv_pref"
    private const val PREFIX = "smarttip_"
    private val main = Handler(Looper.getMainLooper())

    /** Muestra [text] una unica vez. [bottomDp] = distancia desde el borde
     *  inferior (subelo si hay dock debajo: 104dp; reproductores: 64dp). */
    fun showOnce(activity: Activity, key: String, text: String, bottomDp: Int = 104, delayMs: Long = 0) {
        try {
            val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            if (prefs.getBoolean(PREFIX + key, false)) return
            // Marca ANTES de mostrar: aunque la app muera a mitad, no se repite.
            prefs.edit().putBoolean(PREFIX + key, true).apply()
            main.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                showBubble(activity, text, bottomDp)
            }, delayMs)
        } catch (_: Exception) {}
    }

    private fun showBubble(activity: Activity, text: String, bottomDp: Int) {
        try {
            val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
            val density = activity.resources.displayMetrics.density
            val tip = TextView(activity).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12.5f
                setLineSpacing(0f, 1.08f)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setBackgroundResource(R.drawable.bg_apple_glass_pill)
                setPadding(
                    (18 * density).toInt(), (11 * density).toInt(),
                    (18 * density).toInt(), (11 * density).toInt()
                )
                alpha = 0f
                elevation = 30f
                maxLines = 2
                // CLAVE: no clickable/focusable -> los toques atraviesan la burbuja
                // y llegan a la app. Nunca interrumpe.
                isClickable = false
                isFocusable = false
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            lp.bottomMargin = (bottomDp * density).toInt()
            lp.leftMargin = (16 * density).toInt()
            lp.rightMargin = (16 * density).toInt()
            content.addView(tip, lp)
            tip.animate().alpha(1f).setDuration(280).start()
            tip.postDelayed({
                try {
                    tip.animate().alpha(0f).setDuration(380).withEndAction {
                        try { (tip.parent as? FrameLayout)?.removeView(tip) } catch (_: Exception) {}
                    }.start()
                } catch (_: Exception) {}
            }, 4600)
        } catch (_: Exception) {}
    }
}
