package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.samuelpart.iptvplayer.databinding.ItemSearchHistoryBinding

class SearchHistoryAdapter(
    private var historyList: List<String>,
    private val onItemClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemSearchHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(query: String) {
            // Display only a clean shortened name if it's a URL, otherwise show text
            binding.txtHistoryQuery.text = getShortUrlDisplay(query)
            
            binding.root.setOnClickListener {
                onItemClick(query)
            }
            
            binding.btnHistoryDelete.setOnClickListener {
                onDeleteClick(query)
            }
        }
    }

    private fun getShortUrlDisplay(url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url // Keep standard text searches intact
        }
        try {
            val decoded = java.net.URLDecoder.decode(url, "UTF-8")
            val cleanUrl = decoded.split("?")[0].trimEnd('/')
            val lastPart = cleanUrl.substringAfterLast('/')
            if (lastPart.isNotEmpty()) {
                return lastPart
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return if (url.length > 25) url.substring(0, 22) + "..." else url
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSearchHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(historyList[position])
    }

    override fun getItemCount(): Int = historyList.size

    fun updateList(newList: List<String>) {
        historyList = newList
        notifyDataSetChanged()
    }
}
