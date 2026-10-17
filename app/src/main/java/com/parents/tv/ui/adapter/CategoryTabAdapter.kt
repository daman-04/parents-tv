package com.parents.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.parents.tv.databinding.ItemCategoryTabBinding

data class CategoryTab(
    val id: String,
    val title: String,
    val count: Int = 0
)

class CategoryTabAdapter(
    private var tabs: List<CategoryTab>,
    private var selectedIndex: Int = 0,
    private val onTabClick: (CategoryTab, Int) -> Unit
) : RecyclerView.Adapter<CategoryTabAdapter.TabViewHolder>() {

    fun setTabs(newTabs: List<CategoryTab>, selectIndex: Int = 0) {
        tabs = newTabs
        selectedIndex = selectIndex
        notifyDataSetChanged()
    }

    fun setSelectedTab(index: Int) {
        val prev = selectedIndex
        selectedIndex = index
        notifyItemChanged(prev)
        notifyItemChanged(selectedIndex)
    }

    fun getSelectedIndex(): Int = selectedIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemCategoryTabBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(tabs[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = tabs.size

    inner class TabViewHolder(private val binding: ItemCategoryTabBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: CategoryTab, isSelected: Boolean) {
            binding.tabText.text = if (tab.count > 0) "${tab.title} (${tab.count})" else tab.title
            binding.tabText.isSelected = isSelected

            binding.tabText.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    setSelectedTab(pos)
                    onTabClick(tab, pos)
                }
            }

            // Scale on focus and auto-select for TV remote navigation
            binding.tabText.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos != selectedIndex) {
                        setSelectedTab(pos)
                        onTabClick(tab, pos)
                    }
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
        }
    }
}
