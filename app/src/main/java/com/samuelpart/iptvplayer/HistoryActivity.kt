package com.samuelpart.iptvplayer

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.samuelpart.iptvplayer.databinding.ActivityHistoryBinding

/**
 * Historial de listas IPTV cargadas. Al tocar una, se pide a MainActivity
 * que la cargue de nuevo.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: SearchHistoryAdapter

    private val historyKey = "PLAYLIST_URL_HISTORY"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHistoryBack.setOnClickListener { finish() }

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        adapter = SearchHistoryAdapter(
            getSearchHistory(),
            onItemClick = { url ->
                SettingsSync.requestLoadList(this, url)
                finish()
            },
            onDeleteClick = { query ->
                val current = getSearchHistory()
                current.remove(query)
                saveSearchHistory(current)
                refresh()
            }
        )
        binding.rvHistory.adapter = adapter

        binding.btnHistoryClear.setOnClickListener {
            saveSearchHistory(emptyList())
            refresh()
        }

        refresh()
    }

    private fun refresh() {
        val current = getSearchHistory()
        adapter.updateList(current)
        binding.rvHistory.visibility = if (current.isEmpty()) View.GONE else View.VISIBLE
        binding.txtHistoryEmpty.visibility = if (current.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun getSearchHistory(): MutableList<String> {
        val prefs = getSharedPreferences("IPTV_PREFS", Context.MODE_PRIVATE)
        val historyStr = prefs.getString(historyKey, "") ?: ""
        if (historyStr.isEmpty()) return mutableListOf()
        return historyStr.split("|||").filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveSearchHistory(list: List<String>) {
        val prefs = getSharedPreferences("IPTV_PREFS", Context.MODE_PRIVATE)
        prefs.edit().putString(historyKey, list.joinToString("|||")).apply()
    }
}
