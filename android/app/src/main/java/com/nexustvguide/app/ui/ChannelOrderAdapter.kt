package com.nexustvguide.app.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.egeniq.androidtvprogramguide.R as LibraryR
import com.nexustvguide.app.R

class ChannelOrderAdapter(
    private val onItemGrabbed: (position: Int) -> Unit,
    private val onItemReleased: (position: Int) -> Unit,
    private val onItemMove: (fromPos: Int, toPos: Int) -> Unit,
    private val onVisibilityToggle: (channelId: String) -> Unit
) : RecyclerView.Adapter<ChannelOrderAdapter.ChannelOrderViewHolder>() {

    private var items: List<ChannelOrderItem> = emptyList()
    private val idMap = mutableMapOf<String, Long>()
    private var nextStableId = 1L

    init {
        setHasStableIds(true)
    }

    fun submitList(newItems: List<ChannelOrderItem>) {
        val oldItems = items
        items = newItems

        // Allocate stable monotonic IDs
        for (item in newItems) {
            if (!idMap.containsKey(item.channel.id)) {
                idMap[item.channel.id] = nextStableId++
            }
        }

        if (oldItems.isEmpty() || oldItems.size != newItems.size) {
            notifyDataSetChanged()
        } else {
            for (i in newItems.indices) {
                if (i >= oldItems.size || oldItems[i] != newItems[i]) {
                    notifyItemChanged(i)
                }
            }
        }
    }

    fun getItems(): List<ChannelOrderItem> = items

    override fun getItemId(position: Int): Long {
        val item = items[position]
        return idMap[item.channel.id] ?: item.channel.id.hashCode().toLong()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelOrderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_order, parent, false)
        return ChannelOrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelOrderViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    inner class ChannelOrderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvPosition: TextView = itemView.findViewById(R.id.tv_channel_position)
        private val ivLogo: ImageView = itemView.findViewById(R.id.iv_channel_logo)
        private val tvName: TextView = itemView.findViewById(R.id.tv_channel_name)
        private val btnVisibility: ImageButton = itemView.findViewById(R.id.btn_channel_visibility)

        init {
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos !in items.indices) {
                    return@setOnKeyListener false
                }

                val item = items[pos]

                if (item.isGrabbed) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (pos > 0) {
                                onItemMove(pos, pos - 1)
                                itemView.post {
                                    itemView.announceForAccessibility(
                                        itemView.context.getString(
                                            R.string.channel_order_a11y_moved,
                                            item.channel.name,
                                            pos,
                                            items.size
                                        )
                                    )
                                }
                            }
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (pos < items.size - 1) {
                                onItemMove(pos, pos + 1)
                                itemView.post {
                                    itemView.announceForAccessibility(
                                        itemView.context.getString(
                                            R.string.channel_order_a11y_moved,
                                            item.channel.name,
                                            pos + 2,
                                            items.size
                                        )
                                    )
                                }
                            }
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            onItemReleased(pos)
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            return@setOnKeyListener true
                        }
                    }
                } else {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            btnVisibility.requestFocus()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            onItemGrabbed(pos)
                            return@setOnKeyListener true
                        }
                    }
                }

                false
            }

            btnVisibility.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener false
                }

                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos !in items.indices) {
                    return@setOnKeyListener false
                }

                val item = items[pos]

                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        itemView.requestFocus()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onVisibilityToggle(item.channel.id)
                        return@setOnKeyListener true
                    }
                }
                false
            }

            btnVisibility.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                    onVisibilityToggle(items[pos].channel.id)
                }
            }
        }

        fun bind(item: ChannelOrderItem, position: Int) {
            tvPosition.text = (position + 1).toString()
            tvName.text = item.channel.name

            if (!item.channel.logoUrl.isNullOrBlank()) {
                Glide.with(ivLogo)
                    .load(item.channel.logoUrl)
                    .placeholder(LibraryR.drawable.programguide_icon_placeholder)
                    .error(LibraryR.drawable.programguide_icon_placeholder)
                    .into(ivLogo)
            } else {
                ivLogo.setImageResource(LibraryR.drawable.programguide_icon_placeholder)
            }

            // Visibility eye icon & content description
            if (item.isHidden) {
                btnVisibility.setImageResource(R.drawable.ic_visibility_off)
                btnVisibility.contentDescription = itemView.context.getString(
                    R.string.channel_order_a11y_show,
                    item.channel.name
                )
            } else {
                btnVisibility.setImageResource(R.drawable.ic_visibility)
                btnVisibility.contentDescription = itemView.context.getString(
                    R.string.channel_order_a11y_hide,
                    item.channel.name
                )
            }

            val contentAlpha = if (item.isHidden) 0.4f else 1.0f
            tvPosition.alpha = contentAlpha
            ivLogo.alpha = contentAlpha
            tvName.alpha = contentAlpha
            btnVisibility.alpha = if (item.isHidden) 0.6f else 1.0f

            itemView.isSelected = item.isGrabbed
            if (item.isGrabbed) {
                itemView.scaleX = 1.02f
                itemView.scaleY = 1.02f
                itemView.translationZ = 8f
            } else {
                itemView.scaleX = 1.0f
                itemView.scaleY = 1.0f
                itemView.translationZ = 0f
            }
        }
    }
}
