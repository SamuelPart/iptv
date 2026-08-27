package com.samuelpart.iptvplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.samuelpart.iptvplayer.databinding.ItemCategoryBinding

class CategoryAdapter(
    private var categories: List<String>,
    private val onCategoryClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

    private var selectedPosition = 0

    inner class CategoryViewHolder(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(category: String, position: Int) {
            binding.root.springPress() // iPhone-style bounce on tap
            binding.txtCategoryName.text = category

            if (position == selectedPosition) {
                // Selected: iOS blue gradient-ish background, white text
                binding.cardCategory.setCardBackgroundColor(android.graphics.Color.parseColor("#5E5CE6"))
                binding.txtCategoryName.textColor = android.graphics.Color.parseColor("#FFFFFF")
            } else {
                // Unselected: iOS dark surface, white text
                binding.cardCategory.setCardBackgroundColor(android.graphics.Color.parseColor("#14141E"))
                binding.txtCategoryName.textColor = android.graphics.Color.parseColor("#FFFFFF")
            }

            binding.root.setOnClickListener {
                if (selectedPosition != position) {
                    val oldPosition = selectedPosition
                    selectedPosition = position
                    notifyItemChanged(oldPosition)
                    notifyItemChanged(selectedPosition)
                    onCategoryClick(category)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CategoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
        holder.bind(categories[position], position)
    }

    override fun getItemCount(): Int = categories.size

    fun updateCategories(newCategories: List<String>) {
        categories = newCategories
        selectedPosition = 0
        notifyDataSetChanged()
    }
}
// Extension property to set textColor on TextView directly in Kotlin without standard color resources
private var android.widget.TextView.textColor: Int
    get() = currentTextColor
    set(value) {
        setTextColor(value)
    }
