package com.cloudtv.iptv.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cloudtv.iptv.R
import com.cloudtv.iptv.model.ChannelGroup
import com.cloudtv.iptv.model.StreamRoute
import com.cloudtv.iptv.model.TvChannel

private val mainUiHandler = Handler(Looper.getMainLooper())

/**
 * 安全刷新 RecyclerView 适配器：
 * 在安卓 9 电视盒子 (D-Pad 遥控器焦点模式) 下，RecyclerView 在 onLayoutChildren 布局过程中会自动转移子 View 焦点，
 * 此时同步调用 notifyDataSetChanged() 会触发 IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling。
 * 通过 post 到下一帧或捕获异常延迟刷新，彻底杜绝电视盒子遥控器焦点切换闪退！
 */
private fun RecyclerView.Adapter<*>.safeNotifyDataSetChanged(rv: RecyclerView?) {
    if (rv != null && (rv.isComputingLayout || rv.scrollState != RecyclerView.SCROLL_STATE_IDLE)) {
        rv.post {
            try {
                notifyDataSetChanged()
            } catch (_: Exception) {
            }
        }
        return
    }
    try {
        notifyDataSetChanged()
    } catch (_: IllegalStateException) {
        mainUiHandler.post {
            try {
                notifyDataSetChanged()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * 一级抽屉：频道分类列表适配器（央视频道 / 卫视频道 / 地方台 / 我的收藏）
 */
class ChannelGroupAdapter(
    private var groups: List<ChannelGroup>,
    private val onGroupFocusedOrClicked: (Int, ChannelGroup) -> Unit
) : RecyclerView.Adapter<ChannelGroupAdapter.ViewHolder>() {

    var selectedPosition: Int = 0
    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    fun submitList(newGroups: List<ChannelGroup>, selectIndex: Int = 0) {
        groups = newGroups
        selectedPosition = selectIndex.coerceIn(0, (newGroups.size - 1).coerceAtLeast(0))
        safeNotifyDataSetChanged(attachedRecyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
        val isSelected = (position == selectedPosition)
        holder.tvGroupName.text = group.name
        holder.tvGroupCount.text = "${group.channels.size}台"

        holder.itemView.isSelected = isSelected
        holder.tvGroupName.setTextColor(
            if (isSelected) Color.parseColor("#38BDF8") else Color.parseColor("#F1F5F9")
        )

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.post {
                    val newPos = holder.bindingAdapterPosition
                    if (newPos != RecyclerView.NO_POSITION && newPos in groups.indices && newPos != selectedPosition) {
                        selectedPosition = newPos
                        safeNotifyDataSetChanged(attachedRecyclerView)
                        onGroupFocusedOrClicked(newPos, groups[newPos])
                    }
                }
            }
        }
        holder.itemView.setOnClickListener { v ->
            v.post {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in groups.indices) {
                    selectedPosition = pos
                    safeNotifyDataSetChanged(attachedRecyclerView)
                    onGroupFocusedOrClicked(pos, groups[pos])
                }
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvGroupName: TextView = view.findViewById(R.id.tvGroupName)
        val tvGroupCount: TextView = view.findViewById(R.id.tvGroupCount)
    }
}

/**
 * 二级抽屉：频道列表适配器（显示台号、频道名称、收藏星标、可用纯 IPv4 备用源数量）
 */
class ChannelListAdapter(
    private var channels: List<TvChannel>,
    private val onChannelFocused: (TvChannel) -> Unit,
    private val onChannelClicked: (TvChannel) -> Unit
) : RecyclerView.Adapter<ChannelListAdapter.ViewHolder>() {

    var playingChannelId: String = ""
    private var attachedRecyclerView: RecyclerView? = null
    private var lastFocusedChannelId: String = ""

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    fun submitList(newChannels: List<TvChannel>, activeChannelId: String) {
        channels = newChannels
        playingChannelId = activeChannelId
        safeNotifyDataSetChanged(attachedRecyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount(): Int = channels.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ch = channels[position]
        val isPlaying = (ch.id == playingChannelId)

        holder.tvChannelNum.text = String.format("%02d", ch.channelNumber)
        holder.tvChannelName.text = if (ch.isFavorite) "★ ${ch.name}" else ch.name
        holder.tvRouteCount.text = "${ch.routes.size}源"

        holder.tvChannelName.setTextColor(
            if (isPlaying) Color.parseColor("#10B981") else Color.parseColor("#F8FAFC")
        )

        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.post {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos in channels.indices) {
                        val focused = channels[pos]
                        if (focused.id != lastFocusedChannelId) {
                            lastFocusedChannelId = focused.id
                            onChannelFocused(focused)
                        }
                    }
                }
            }
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos in channels.indices) {
                onChannelClicked(channels[pos])
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvChannelNum: TextView = view.findViewById(R.id.tvChannelNum)
        val tvChannelName: TextView = view.findViewById(R.id.tvChannelName)
        val tvRouteCount: TextView = view.findViewById(R.id.tvRouteCount)
    }
}

/**
 * 三级抽屉：单个频道的多条纯 IPv4 信号源线路列表适配器
 */
class RouteListAdapter(
    private var routes: List<StreamRoute>,
    private var activeRouteIndex: Int,
    private val onRouteClicked: (Int, StreamRoute) -> Unit
) : RecyclerView.Adapter<RouteListAdapter.ViewHolder>() {

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        attachedRecyclerView = null
    }

    fun submitRoutes(newRoutes: List<StreamRoute>, currentIndex: Int) {
        routes = newRoutes
        activeRouteIndex = currentIndex
        safeNotifyDataSetChanged(attachedRecyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_route, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount(): Int = routes.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val route = routes[position]
        val isCurrent = (position == activeRouteIndex)

        holder.tvRouteTitle.text = if (isCurrent) {
            "▶ 线路 ${position + 1} (当前播放)"
        } else {
            "线路 ${position + 1}"
        }
        holder.tvRouteStatus.text = route.statusBadgeText()

        holder.tvRouteTitle.setTextColor(
            if (isCurrent) Color.parseColor("#38BDF8") else Color.parseColor("#E2E8F0")
        )
        holder.tvRouteStatus.setTextColor(
            when {
                route.latencyMs in 1..800 -> Color.parseColor("#10B981")
                route.latencyMs in 801..4999 -> Color.parseColor("#F59E0B")
                route.latencyMs >= 5000 -> Color.parseColor("#EF4444")
                else -> Color.parseColor("#94A3B8")
            }
        )

        holder.itemView.setOnClickListener {
            val idx = holder.bindingAdapterPosition
            if (idx != RecyclerView.NO_POSITION && idx in routes.indices) {
                activeRouteIndex = idx
                safeNotifyDataSetChanged(attachedRecyclerView)
                onRouteClicked(idx, routes[idx])
            }
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRouteTitle: TextView = view.findViewById(R.id.tvRouteTitle)
        val tvRouteStatus: TextView = view.findViewById(R.id.tvRouteStatus)
    }
}
