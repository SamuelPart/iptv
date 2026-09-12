package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.samuelpart.iptvplayer.databinding.ItemCastDeviceBinding

class CastDeviceAdapter(
    private var devices: List<CastDevice>,
    private val onDeviceClick: (CastDevice) -> Unit
) : RecyclerView.Adapter<CastDeviceAdapter.CastDeviceViewHolder>() {

    inner class CastDeviceViewHolder(private val binding: ItemCastDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: CastDevice) {
            binding.txtDeviceName.text = device.name
            binding.txtDeviceType.text = when (device.type) {
                "Google Cast" -> "Chromecast / Google Cast"
                "Roku" -> "Roku / Roku TV / Roku Stick"
                "DLNA" -> "Smart TV (Samsung, LG, Xbox, Sony)"
                else -> device.type
            }

            // Set appropriate icon based on device type
            val iconRes = when (device.type) {
                "Google Cast" -> R.drawable.ic_cast
                "Roku" -> R.drawable.ic_tv
                "DLNA" -> R.drawable.ic_tv
                else -> R.drawable.ic_tv
            }
            binding.imgDeviceIcon.setImageResource(iconRes)

            binding.root.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CastDeviceViewHolder {
        val binding = ItemCastDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CastDeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CastDeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    fun updateList(newDevices: List<CastDevice>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}
