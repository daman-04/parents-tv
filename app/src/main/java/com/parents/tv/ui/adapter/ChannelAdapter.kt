package com.parents.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.parents.tv.R
import com.parents.tv.data.model.Channel
import com.parents.tv.databinding.ItemChannelGridBinding
import com.parents.tv.databinding.ItemChannelListBinding

class ChannelAdapter(
    private var channels: List<Channel>,
    private var isGridMode: Boolean = true,
    private val onChannelClick: (Channel, Int) -> Unit,
    private val onChannelLongClick: ((Channel, Int) -> Boolean)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var currentPlayingId: String? = null

    companion object {
        const val VIEW_TYPE_GRID = 1
        const val VIEW_TYPE_LIST = 2
    }

    fun setCurrentPlayingChannelId(id: String?) {
        val oldId = currentPlayingId
        if (oldId != id) {
            currentPlayingId = id
            val oldIdx = channels.indexOfFirst { it.id == oldId }
            val newIdx = channels.indexOfFirst { it.id == id }
            if (oldIdx != -1) notifyItemChanged(oldIdx)
            if (newIdx != -1) notifyItemChanged(newIdx)
        }
    }

    fun setChannels(newChannels: List<Channel>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = channels.size
            override fun getNewListSize(): Int = newChannels.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return channels[oldItemPosition].id == newChannels[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldCh = channels[oldItemPosition]
                val newCh = newChannels[newItemPosition]
                return oldCh.id == newCh.id &&
                        oldCh.name == newCh.name &&
                        oldCh.isFavorite == newCh.isFavorite &&
                        oldCh.streams.size == newCh.streams.size
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        channels = newChannels
        diffResult.dispatchUpdatesTo(this)
    }

    fun getChannels(): List<Channel> = channels

    fun setGridMode(isGrid: Boolean) {
        if (isGridMode != isGrid) {
            isGridMode = isGrid
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (isGridMode) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val binding = ItemChannelGridBinding.inflate(inflater, parent, false)
            GridViewHolder(binding)
        } else {
            val binding = ItemChannelListBinding.inflate(inflater, parent, false)
            ListViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val channel = channels[position]
        if (holder is GridViewHolder) {
            holder.bind(channel, position)
        } else if (holder is ListViewHolder) {
            holder.bind(channel, position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1.0f
        holder.itemView.scaleY = 1.0f
    }

    override fun getItemCount(): Int = channels.size

    inner class GridViewHolder(private val binding: ItemChannelGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel, position: Int) {
            binding.cardRoot.animate().cancel()
            binding.cardRoot.scaleX = 1.0f
            binding.cardRoot.scaleY = 1.0f

            binding.cardChannelNumber.text = "CH ${position + 1}"
            binding.cardName.text = channel.name
            binding.cardChannelCategory.text = channel.category?.uppercase() ?: "LIVE"
            binding.cardFavoriteStar.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE

            val isFocused = binding.cardRoot.hasFocus()
            binding.cardName.setTextColor(if (isFocused) 0xFF0C0E17.toInt() else 0xFFFFFFFF.toInt())
            binding.cardChannelCategory.setTextColor(if (isFocused) 0xFF4A526A.toInt() else 0xFFA2A8BE.toInt())

            if (!channel.logo.isNullOrEmpty()) {
                binding.cardLogo.visibility = View.VISIBLE
                binding.cardLogo.load(channel.logo) {
                    crossfade(false)
                }
            } else {
                binding.cardLogo.visibility = View.GONE
            }

            setupClicks(this, binding.cardRoot)
            binding.cardRoot.setOnFocusChangeListener { view, hasFocus ->
                view.animate().cancel()
                if (hasFocus) {
                    view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(80).start()
                    binding.cardName.setTextColor(0xFF0C0E17.toInt())
                    binding.cardChannelCategory.setTextColor(0xFF4A526A.toInt())
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    binding.cardName.setTextColor(0xFFFFFFFF.toInt())
                    binding.cardChannelCategory.setTextColor(0xFFA2A8BE.toInt())
                }
            }
        }
    }

    inner class ListViewHolder(private val binding: ItemChannelListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel, position: Int) {
            binding.cardRoot.animate().cancel()
            binding.cardRoot.scaleX = 1.0f
            binding.cardRoot.scaleY = 1.0f

            binding.cardChannelNumber.text = "CH ${position + 1}"
            binding.cardName.text = channel.name
            binding.cardChannelCategory.text = channel.category?.uppercase() ?: "LIVE"
            val isPlaying = channel.id == currentPlayingId
            binding.cardPlayingIndicator.visibility = if (isPlaying) View.VISIBLE else View.GONE

            val isFocused = binding.cardRoot.hasFocus()
            binding.cardName.setTextColor(if (isFocused) 0xFF0C0E17.toInt() else 0xFFFFFFFF.toInt())
            binding.cardChannelCategory.setTextColor(if (isFocused) 0xFF334155.toInt() else 0xFFCBD5E1.toInt())

            if (!channel.logo.isNullOrEmpty()) {
                binding.cardLogo.visibility = View.VISIBLE
                binding.cardLogo.load(channel.logo) {
                    crossfade(false)
                }
            } else {
                binding.cardLogo.visibility = View.GONE
            }

            setupClicks(this, binding.cardRoot)
            binding.cardRoot.setOnFocusChangeListener { view, hasFocus ->
                view.animate().cancel()
                if (hasFocus) {
                    view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(80).start()
                    binding.cardName.setTextColor(0xFF0C0E17.toInt())
                    binding.cardChannelCategory.setTextColor(0xFF334155.toInt())
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    binding.cardName.setTextColor(0xFFFFFFFF.toInt())
                    binding.cardChannelCategory.setTextColor(0xFFCBD5E1.toInt())
                }
            }
        }
    }

    private fun setupClicks(holder: RecyclerView.ViewHolder, cardRoot: View) {
        cardRoot.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                onChannelClick(channels[pos], pos)
            }
        }

        cardRoot.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < channels.size) {
                onChannelLongClick?.invoke(channels[pos], pos) ?: false
            } else false
        }
    }
}
