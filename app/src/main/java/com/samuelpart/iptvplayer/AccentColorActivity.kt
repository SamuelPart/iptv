package com.samuelpart.iptvplayer

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.samuelpart.iptvplayer.databinding.ActivityAccentColorBinding

/**
 * Pantalla de COLOR DE ACENTO: Menta / Champagne / Coral / Perla.
 * Muestra el swatch de cada color y guarda la elección en [AccentManager].
 */
class AccentColorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccentColorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccentColorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAccentBack.setOnClickListener { finish() }

        buildOptions()
    }

    private fun buildOptions() {
        val container = binding.containerAccents
        container.removeAllViews()

        val currentKey = AccentManager.getKey(this)

        AccentManager.OPTIONS.forEachIndexed { index, opt ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
            }

            val swatch = View(this).apply {
                val shape = GradientDrawable()
                shape.shape = GradientDrawable.OVAL
                shape.setColor(opt.color)
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
            }
            row.addView(swatch)

            val label = TextView(this).apply {
                text = opt.label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setPadding(dp(14), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)

            val check = TextView(this).apply {
                text = "✓"
                setTextColor(0xFFC9A96E.toInt())
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                visibility = if (opt.key == currentKey) View.VISIBLE else View.GONE
            }
            row.addView(check)

            row.setOnClickListener {
                AccentManager.set(this@AccentColorActivity, opt.key)
                SettingsSync.mark(this@AccentColorActivity)
                Toast.makeText(this@AccentColorActivity, "🎨 Acento: ${opt.label}", Toast.LENGTH_SHORT).show()
                finish()
            }

            container.addView(row)

            if (index < AccentManager.OPTIONS.size - 1) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    )
                    setBackgroundColor(0x14FFFFFF)
                })
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
