package com.samuelpart.iptvplayer

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.samuelpart.iptvplayer.databinding.ActivityStorageBinding
import java.io.File

/**
 * Pantalla de ALMACENAMIENTO: muestra la lista en caché y permite borrarla.
 * La limpieza real de canales se delega a MainActivity vía [SettingsSync].
 */
class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStorageBack.setOnClickListener { finish() }

        binding.btnClearCache.setOnClickListener {
            val prefs = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            prefs.edit().remove("last_url").apply()
            try { File(filesDir, "cached_playlist.m3u").delete() } catch (_: Exception) {}
            SettingsSync.requestClearList(this)
            Toast.makeText(this, "Lista eliminada del historial", Toast.LENGTH_SHORT).show()
            finish()
        }

        refreshInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
    }

    private fun refreshInfo() {
        val cacheFile = File(filesDir, "cached_playlist.m3u")
        val lastUrl = getSharedPreferences("iptv_pref", Context.MODE_PRIVATE)
            .getString("last_url", null)
        binding.txtCacheInfo.text = buildString {
            if (cacheFile.exists()) {
                append("M3U en caché: ${formatSize(cacheFile.length())}")
            } else {
                append("Sin lista en caché")
            }
            if (!lastUrl.isNullOrEmpty()) append("\nÚltima lista: $lastUrl")
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb >= 1024) String.format("%.1f MB", kb / 1024.0) else String.format("%.0f KB", kb)
    }
}
