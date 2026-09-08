package com.samuelpart.iptvplayer

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.samuelpart.iptvplayer.databinding.ActivityThemeBinding

/**
 * Pantalla de TEMA (sistema / oscuro / claro). Guarda la preferencia y marca
 * [SettingsSync] para que MainActivity repinte los colores al volver.
 */
class ThemeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnThemeBack.setOnClickListener { finish() }

        binding.btnThemeSystem.setOnClickListener { pick("system") }
        binding.btnThemeDark.setOnClickListener { pick("dark") }
        binding.btnThemeLight.setOnClickListener { pick("light") }

        paintSelection()
    }

    private fun pick(value: String) {
        getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            .edit().putString("theme_pref", value).apply()
        SettingsSync.mark(this)
        Toast.makeText(this, "Tema aplicado ⚡", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun paintSelection() {
        val current = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            .getString("theme_pref", "system") ?: "system"
        binding.checkThemeSystem.visibility = if (current == "system") View.VISIBLE else View.GONE
        binding.checkThemeDark.visibility = if (current == "dark") View.VISIBLE else View.GONE
        binding.checkThemeLight.visibility = if (current == "light") View.VISIBLE else View.GONE
    }
}
