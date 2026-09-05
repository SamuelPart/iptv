package com.samuelpart.iptvplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.RecyclerView
import com.samuelpart.iptvplayer.databinding.ItemCountryDialogBinding

class CountryDialogAdapter(
    private val countries: List<String>,
    private val onCountryClick: (String) -> Unit
) : RecyclerView.Adapter<CountryDialogAdapter.CountryViewHolder>() {

    inner class CountryViewHolder(val binding: ItemCountryDialogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var sheenAnimator: ObjectAnimator? = null

        fun bind(country: String, position: Int) {
            binding.txtDialogCountryName.text = country
            
            // Extract flag emoji if present in the country string (e.g. "México 🇲🇽")
            val flag = extractFlagEmoji(country)
            binding.txtDialogFlag.text = flag ?: "🏳️"

            // 1. Load and start the waving/moving animation on the flag container!
            val waveAnim = AnimationUtils.loadAnimation(binding.root.context, R.anim.anim_wave)
            binding.cardFlagContainer.startAnimation(waveAnim)

            // 2. Start corner-to-corner diagonal glowing sheen translation animation!
            binding.viewDialogSheen.post {
                val width = binding.viewDialogSheen.width.toFloat()
                
                sheenAnimator?.cancel()
                sheenAnimator = ObjectAnimator.ofFloat(
                    binding.viewDialogSheen,
                    "translationX",
                    -width,
                    width
                ).apply {
                    duration = 1600
                    repeatCount = ValueAnimator.INFINITE
                    // Staggered delay for each card item makes the glowing pattern look organic!
                    startDelay = (position * 250).toLong() 
                }
                sheenAnimator?.start()
            }

            binding.root.setOnClickListener {
                onCountryClick(country)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountryViewHolder {
        val binding = ItemCountryDialogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CountryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CountryViewHolder, position: Int) {
        holder.bind(countries[position], position)
    }

    override fun getItemCount(): Int = countries.size

    private fun extractFlagEmoji(str: String): String? {
        val regex = Regex("""[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]""")
        val match = regex.find(str)
        return match?.value
    }
}
