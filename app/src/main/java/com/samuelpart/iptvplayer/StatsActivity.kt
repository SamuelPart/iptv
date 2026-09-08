package com.samuelpart.iptvplayer

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.samuelpart.iptvplayer.databinding.ActivityStatsBinding

/**
 * Pantalla de ESTADÍSTICAS: conteo local de la última semana.
 */
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStatsBack.setOnClickListener { finish() }
        refreshStats()
    }

    private fun refreshStats() {
        val opens = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            .getInt("app_open_count", 0)
        val weekMs = 7L * 24 * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        val week = ContinueWatchingManager.getAll(this).filter { now - it.savedAt < weekMs }
        val channels = week.count { it.isChannel }
        val cine = week.filter { !it.isChannel }
        val watchMin = cine.sumOf { it.positionMs / 60000 }
        val favs = FavoritesManager.count(this)

        binding.txtStatsBody.text =
            "Esta semana:\n\n📺 $channels canales vistos\n🎬 ${cine.size} películas/series\n⏱ Tiempo en cine: $watchMin min\n⭐ Favoritos: $favs\n📲 Aperturas de la app: $opens"
    }
}
