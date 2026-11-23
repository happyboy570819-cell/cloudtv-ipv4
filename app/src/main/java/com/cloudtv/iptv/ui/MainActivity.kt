package com.cloudtv.iptv.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudtv.iptv.R
import com.cloudtv.iptv.model.ChannelGroup
import com.cloudtv.iptv.model.StreamRoute
import com.cloudtv.iptv.model.TvChannel
import com.cloudtv.iptv.player.BackgroundSpeedTester
import com.cloudtv.iptv.player.PlayerManager
import com.cloudtv.iptv.repository.ChannelRepository
import com.cloudtv.iptv.server.LanConfigServer
import kotlinx.coroutines.launch

/**
 * 云视 TV (纯 IPv4 防卡顿专版) - 电视盒子主界面 (MainActivity)
 * ====================================================================================
 * 专为安卓机顶盒遥控器 (D-Pad) 设计的交互逻辑：
 * - 【上下键 (DPAD_UP / DPAD_DOWN)】：全屏观看时直接上下秒换台；
 * - 【左右键 (DPAD_LEFT / DPAD_RIGHT)】：全屏观看时一键秒切当前频道的备用纯 IPv4 信号源（线路1 / 线路2 / 线路3...）；
 * - 【OK 键 / 确认键 (DPAD_CENTER / ENTER)】：呼出左侧半透明三级选台与多源切换抽屉；
 * - 【菜单键 (KEYCODE_MENU) 或长按 OK 键】：呼出右侧防卡顿设置、全量 IPv4 测速与局域网手机配源面板；
 * - 【数字键 (0 ~ 9)】：直接输入频道号选台（如按 13 自动切换到 13 号台 CCTV-13 新闻）；
 * - 【返回键 (KEYCODE_BACK)】：先关闭打开的选台抽屉或设置面板，再按两次退出应用。
 */
class MainActivity : AppCompatActivity(), PlayerManager.Listener, LanConfigServer.Callback {

    private lateinit var repository: ChannelRepository
    private lateinit var playerManager: PlayerManager
    private lateinit var speedTester: BackgroundSpeedTester
    private lateinit var lanConfigServer: LanConfigServer

    // 界面核心视图
    private lateinit var playerView: PlayerView
    private lateinit var topInfoBanner: LinearLayout
    private lateinit var tvChannelNumAndName: TextView
    private lateinit var tvRouteBadge: TextView
    private lateinit var tvVideoStats: TextView
    private lateinit var centerBufferingOverlay: LinearLayout
    private lateinit var tvBufferingStatus: TextView
    private lateinit var tvNumericInputBadge: TextView

    // 左侧三级选台与线路抽屉
    private lateinit var leftChannelDrawer: LinearLayout
    private lateinit var rvGroups: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var rvRoutes: RecyclerView
    private lateinit var groupAdapter: ChannelGroupAdapter
    private lateinit var channelAdapter: ChannelListAdapter
    private lateinit var routeAdapter: RouteListAdapter

    // 右侧防卡顿设置与局域网配源面板
    private lateinit var rightSettingsPanel: LinearLayout
    private lateinit var tvLanServerUrl: TextView
    private lateinit var btnSwitchRoute: Button
    private lateinit var btnSpeedTestAll: Button
    private lateinit var btnToggleBufferMode: Button
    private lateinit var btnToggleDecoder: Button
    private lateinit var btnToggleFavorite: Button
    private lateinit var btnSyncRemoteUrl: Button
    private lateinit var btnInputCustomUrl: Button
    private lateinit var btnToggleBootStart: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentVideoResolution: String = "HD"
    private var currentNetSpeedKbps: Long = 0L
    private var numericInputBuffer = StringBuilder()
    private var lastBackPressTime = 0L

    private val hideTopBannerRunnable = Runnable {
        if (!isDrawerOpen() && !isSettingsOpen()) {
            topInfoBanner.visibility = View.GONE
        }
    }

    private val commitNumericChannelRunnable = Runnable {
        val numStr = numericInputBuffer.toString()
        numericInputBuffer.clear()
        tvNumericInputBadge.visibility = View.GONE
        val targetNumber = numStr.toIntOrNull() ?: return@Runnable
        val targetChannel = repository.getChannels().firstOrNull { it.channelNumber == targetNumber }
        if (targetChannel != null) {
            playSelectedChannel(targetChannel)
        } else {
            Toast.makeText(this, "未找到 $targetNumber 号频道", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        repository = ChannelRepository(this)
        speedTester = BackgroundSpeedTester(lifecycleScope)
        playerManager = PlayerManager(this, this)
        lanConfigServer = LanConfigServer(this, lifecycleScope, 8989, this)

        bindViews()
        setupAdapters()
        setupSettingsPanelActions()

        // 初始化播放器与频道列表
        playerManager.initialize(
            playerView = playerView,
            initialBufferMode = repository.bufferMode,
            useSoftwareDecoder = repository.preferSoftwareDecoder
        )

        val channels = repository.loadInitialChannels()
        refreshDrawerData()

        // 恢复上次观看的频道（默认 CCTV-1 综合）
        val initialChannel = channels.firstOrNull { it.id == repository.lastChannelId }
            ?: channels.firstOrNull()
        if (initialChannel != null) {
            playSelectedChannel(initialChannel)
        }

        // 启动局域网 8989 手机网页配源与遥控服务
        lanConfigServer.start()
        updateSettingsLabels()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        topInfoBanner = findViewById(R.id.topInfoBanner)
        tvChannelNumAndName = findViewById(R.id.tvChannelNumAndName)
        tvRouteBadge = findViewById(R.id.tvRouteBadge)
        tvVideoStats = findViewById(R.id.tvVideoStats)
        centerBufferingOverlay = findViewById(R.id.centerBufferingOverlay)
        tvBufferingStatus = findViewById(R.id.tvBufferingStatus)
        tvNumericInputBadge = findViewById(R.id.tvNumericInputBadge)

        leftChannelDrawer = findViewById(R.id.leftChannelDrawer)
        rvGroups = findViewById(R.id.rvGroups)
        rvChannels = findViewById(R.id.rvChannels)
        rvRoutes = findViewById(R.id.rvRoutes)

        rightSettingsPanel = findViewById(R.id.rightSettingsPanel)
        tvLanServerUrl = findViewById(R.id.tvLanServerUrl)
        btnSwitchRoute = findViewById(R.id.btnSwitchRoute)
        btnSpeedTestAll = findViewById(R.id.btnSpeedTestAll)
        btnToggleBufferMode = findViewById(R.id.btnToggleBufferMode)
        btnToggleDecoder = findViewById(R.id.btnToggleDecoder)
        btnToggleFavorite = findViewById(R.id.btnToggleFavorite)
        btnSyncRemoteUrl = findViewById(R.id.btnSyncRemoteUrl)
        btnInputCustomUrl = findViewById(R.id.btnInputCustomUrl)
        btnToggleBootStart = findViewById(R.id.btnToggleBootStart)

        // 支持触屏或鼠标点击画面呼出选台抽屉
        playerView.setOnClickListener {
            if (isSettingsOpen()) {
                hideSettingsPanel()
            } else if (isDrawerOpen()) {
                hideChannelDrawer()
            } else {
                showChannelDrawer()
            }
        }
        playerView.setOnLongClickListener {
            showSettingsPanel()
            true
        }
    }

    private fun setupAdapters() {
        rvGroups.layoutManager = LinearLayoutManager(this)
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvRoutes.layoutManager = LinearLayoutManager(this)

        groupAdapter = ChannelGroupAdapter(emptyList()) { _, group ->
            val currentId = playerManager.getCurrentChannel()?.id.orEmpty()
            channelAdapter.submitList(group.channels, currentId)
            val previewCh = group.channels.firstOrNull { it.id == currentId } ?: group.channels.firstOrNull()
            if (previewCh != null) {
                routeAdapter.submitRoutes(previewCh.routes, previewCh.currentRouteIndex)
            }
        }

        channelAdapter = ChannelListAdapter(
            channels = emptyList(),
            onChannelFocused = { focusedChannel ->
                routeAdapter.submitRoutes(focusedChannel.routes, focusedChannel.currentRouteIndex)
            },
            onChannelClicked = { clickedChannel ->
                playSelectedChannel(clickedChannel)
                hideChannelDrawer()
            }
        )

        routeAdapter = RouteListAdapter(emptyList(), 0) { routeIdx, _ ->
            val currentCh = playerManager.getCurrentChannel()
            if (currentCh != null) {
                playSelectedChannel(currentCh, routeIdx)
                hideChannelDrawer()
            }
        }

        rvGroups.adapter = groupAdapter
        rvChannels.adapter = channelAdapter
        rvRoutes.adapter = routeAdapter
    }

    private fun setupSettingsPanelActions() {
        btnSwitchRoute.setOnClickListener {
            val next = playerManager.switchRouteManually(1)
            if (next != null) {
                Toast.makeText(this, "已切换至 ${next.label} (${next.statusBadgeText()})", Toast.LENGTH_SHORT).show()
            }
        }

        btnSpeedTestAll.setOnClickListener {
            triggerFullIpv4SpeedTest()
        }

        btnToggleBufferMode.setOnClickListener {
            val nextMode = repository.bufferMode.next()
            repository.bufferMode = nextMode
            playerManager.updatePlaybackConfig(nextMode, repository.preferSoftwareDecoder)
            updateSettingsLabels()
            Toast.makeText(this, "已切换为：${nextMode.displayName}\n${nextMode.description}", Toast.LENGTH_LONG).show()
        }

        btnToggleDecoder.setOnClickListener {
            val newPref = !repository.preferSoftwareDecoder
            repository.preferSoftwareDecoder = newPref
            playerManager.updatePlaybackConfig(repository.bufferMode, newPref)
            updateSettingsLabels()
            val msg = if (newPref) "已切换为：软解扩展优先（解决部分老盒子硬解花屏）" else "已切换为：硬件解码优先（低功耗流畅）"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        btnToggleFavorite.setOnClickListener {
            val ch = playerManager.getCurrentChannel() ?: return@setOnClickListener
            val isFav = repository.toggleFavorite(ch)
            refreshDrawerData()
            updateSettingsLabels()
            Toast.makeText(
                this,
                if (isFav) "已将 [${ch.name}] 加入收藏频道" else "已取消收藏 [${ch.name}]",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSyncRemoteUrl.setOnClickListener {
            syncRemotePlaylistWithToast(repository.subscriptionUrl, mergeWithBuiltin = true)
        }

        btnInputCustomUrl.setOnClickListener {
            showSubscriptionUrlInputDialog()
        }

        btnToggleBootStart.setOnClickListener {
            repository.bootAutoStart = !repository.bootAutoStart
            updateSettingsLabels()
        }
    }

    private fun updateSettingsLabels() {
        tvLanServerUrl.text =
            "📱 手机/电脑免遥控器配源地址：\n${lanConfigServer.getServerAddress()}\n(同一WiFi下浏览器打开即可推送M3U/TXT或远程换源)"
        btnToggleBufferMode.text = "🛡️ 防卡顿缓冲：${repository.bufferMode.displayName}"
        btnToggleDecoder.text = if (repository.preferSoftwareDecoder) {
            "🎞️ 解码策略：软解优先 (兼容模式)"
        } else {
            "🎞️ 解码策略：硬解优先 (软解自动兜底)"
        }
        val activeCh = playerManager.getCurrentChannel()
        btnToggleFavorite.text = if (activeCh?.isFavorite == true) {
            "★ 已收藏当前频道 (${activeCh.name})"
        } else {
            "☆ 收藏当前频道 (${activeCh?.name ?: ""})"
        }
        btnToggleBootStart.text = if (repository.bootAutoStart) {
            "🔌 开机自启直播：已开启"
        } else {
            "🔌 开机自启直播：已关闭"
        }
    }

    private fun showSubscriptionUrlInputDialog() {
        val input = EditText(this).apply {
            setText(repository.subscriptionUrl)
            hint = "输入 http://电脑局域网IP:8123/ipv4_live.m3u 或公开源地址"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("配置纯 IPv4 直播源订阅地址")
            .setMessage("提示：您也可以在手机浏览器打开 ${lanConfigServer.getServerAddress()} 直接粘贴网址，免去遥控器打字。")
            .setView(input)
            .setPositiveButton("拉取并合并多线路") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    repository.subscriptionUrl = url
                    syncRemotePlaylistWithToast(url, mergeWithBuiltin = true)
                }
            }
            .setNeutralButton("恢复内置精选IPv4源") { _, _ ->
                val list = repository.resetToBuiltinPlaylist()
                refreshDrawerData()
                list.firstOrNull()?.let { playSelectedChannel(it) }
                Toast.makeText(this, "已恢复内置精选纯 IPv4 多线路直播源", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun syncRemotePlaylistWithToast(url: String, mergeWithBuiltin: Boolean) {
        Toast.makeText(this, "正在拉取并过滤纯 IPv4 直播源...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val res = repository.syncFromRemoteUrl(url, mergeWithBuiltin)
                refreshDrawerData()
                Toast.makeText(
                    this@MainActivity,
                    "更新成功！共载入 ${res.channels.size} 个频道 (${res.totalValidIpv4Routes} 条纯IPv4线路，自动拦截 ${res.filteredIpv6Count} 条IPv6源)",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "更新失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun triggerFullIpv4SpeedTest() {
        val channels = repository.getChannels()
        btnSpeedTestAll.isEnabled = false
        Toast.makeText(this, "开始后台并发实测所有纯 IPv4 线路延迟与速率...", Toast.LENGTH_SHORT).show()
        speedTester.startFullPlaylistSpeedTest(
            channels = channels,
            onProgress = { tested, total, available ->
                btnSpeedTestAll.text = "🚀 正在测速 ($tested/$total · 可用:$available)"
            },
            onComplete = { available, total ->
                btnSpeedTestAll.isEnabled = true
                btnSpeedTestAll.text = "🚀 一键全频道纯 IPv4 并发测速优选"
                refreshDrawerData()
                Toast.makeText(
                    this,
                    "纯 IPv4 测速优选完成！共 $available/$total 条高速源可用，已自动将最快线路设为主源",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun refreshDrawerData() {
        val groups = repository.getChannelGroups()
        val currentCh = playerManager.getCurrentChannel()
        val groupIdx = groups.indexOfFirst { grp -> grp.channels.any { it.id == currentCh?.id } }
            .coerceAtLeast(0)
        groupAdapter.submitList(groups, groupIdx)
        val activeGroup = groups.getOrNull(groupIdx)
        if (activeGroup != null) {
            channelAdapter.submitList(activeGroup.channels, currentCh?.id.orEmpty())
        }
        if (currentCh != null) {
            routeAdapter.submitRoutes(currentCh.routes, currentCh.currentRouteIndex)
        }
    }

    private fun playSelectedChannel(channel: TvChannel, routeIndex: Int? = null) {
        repository.lastChannelId = channel.id
        playerManager.playChannel(channel, routeIndex)
        updateSettingsLabels()
        // 切台后自动在后台静默测速该频道的所有备用线路，确保需要切源时秒切最快源
        speedTester.probeChannelRoutesAsync(channel) {
            if (playerManager.getCurrentChannel()?.id == channel.id) {
                routeAdapter.submitRoutes(channel.routes, channel.currentRouteIndex)
            }
        }
    }

    private fun switchChannelByOffset(offset: Int) {
        val all = repository.getChannels()
        if (all.isEmpty()) return
        val current = playerManager.getCurrentChannel()
        val curIdx = all.indexOfFirst { it.id == current?.id }.takeIf { it >= 0 } ?: 0
        val nextIdx = ((curIdx + offset) % all.size + all.size) % all.size
        playSelectedChannel(all[nextIdx])
    }

    // ==================== 电视盒子遥控器按键事件深度适配 ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

            // 1. 数字键 0-9 直接选台
            if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && !isDrawerOpen() && !isSettingsOpen()) {
                val digit = keyCode - KeyEvent.KEYCODE_0
                handleNumericKeyInput(digit)
                return true
            }

            // 2. 菜单键 (KEYCODE_MENU) 随时开关右侧防卡设置与局域网配源面板
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (isSettingsOpen()) hideSettingsPanel() else showSettingsPanel()
                return true
            }

            // 3. 当左侧选台抽屉和右侧设置面板都未打开时（全屏观看状态）：
            if (!isDrawerOpen() && !isSettingsOpen()) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                        switchChannelByOffset(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        switchChannelByOffset(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val route = playerManager.switchRouteManually(-1)
                        if (route != null) {
                            showTopBannerTemporarily()
                            Toast.makeText(this, "◀ 手动切换信号源：${route.label} (${route.statusBadgeText()})", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val route = playerManager.switchRouteManually(1)
                        if (route != null) {
                            showTopBannerTemporarily()
                            Toast.makeText(this, "▶ 手动切换信号源：${route.label} (${route.statusBadgeText()})", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (event.isLongPress) {
                            showSettingsPanel()
                        } else {
                            showChannelDrawer()
                        }
                        return true
                    }
                }
            }

            // 4. 返回键处理
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (isSettingsOpen()) {
                    hideSettingsPanel()
                    return true
                }
                if (isDrawerOpen()) {
                    hideChannelDrawer()
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressTime > 2000L) {
                    lastBackPressTime = now
                    Toast.makeText(this, "再按一次返回键退出云视 TV (按[菜单键]打开设置)", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleNumericKeyInput(digit: Int) {
        if (numericInputBuffer.length < 3) {
            numericInputBuffer.append(digit)
        }
        tvNumericInputBadge.text = "频道 ${numericInputBuffer}"
        tvNumericInputBadge.visibility = View.VISIBLE
        uiHandler.removeCallbacks(commitNumericChannelRunnable)
        uiHandler.postDelayed(commitNumericChannelRunnable, 1400L)
    }

    private fun isDrawerOpen(): Boolean = leftChannelDrawer.visibility == View.VISIBLE
    private fun isSettingsOpen(): Boolean = rightSettingsPanel.visibility == View.VISIBLE

    private fun showChannelDrawer() {
        hideSettingsPanel()
        refreshDrawerData()
        leftChannelDrawer.visibility = View.VISIBLE
        topInfoBanner.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideTopBannerRunnable)
        rvChannels.requestFocus()
    }

    private fun hideChannelDrawer() {
        leftChannelDrawer.visibility = View.GONE
        showTopBannerTemporarily()
    }

    private fun showSettingsPanel() {
        leftChannelDrawer.visibility = View.GONE
        updateSettingsLabels()
        rightSettingsPanel.visibility = View.VISIBLE
        btnSwitchRoute.requestFocus()
    }

    private fun hideSettingsPanel() {
        rightSettingsPanel.visibility = View.GONE
        showTopBannerTemporarily()
    }

    private fun showTopBannerTemporarily() {
        topInfoBanner.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideTopBannerRunnable)
        uiHandler.postDelayed(hideTopBannerRunnable, 5000L)
    }

    // ==================== PlayerManager.Listener 回调 ====================

    override fun onChannelPlaying(
        channel: TvChannel,
        route: StreamRoute,
        routeIndex: Int,
        totalRoutes: Int
    ) {
        tvChannelNumAndName.text = String.format("%02d  %s", channel.channelNumber, channel.name)
        tvRouteBadge.text = "纯IPv4 线路 ${routeIndex + 1}/$totalRoutes · ${route.statusBadgeText()}"
        tvVideoStats.text = "${currentVideoResolution} · ${repository.bufferMode.displayName}"
        showTopBannerTemporarily()
    }

    override fun onBufferingStateChanged(isBuffering: Boolean, bufferedPercent: Int, netSpeedKbps: Long) {
        currentNetSpeedKbps = netSpeedKbps
        val ch = playerManager.getCurrentChannel()
        val route = ch?.currentRoute()
        if (isBuffering) {
            centerBufferingOverlay.visibility = View.VISIBLE
            val routeText = if (ch != null) "线路 ${ch.currentRouteIndex + 1}/${ch.routes.size}" else "信号源"
            tvBufferingStatus.text = "正在缓冲 ${ch?.name ?: ""} ($routeText) · ${netSpeedKbps} KB/s"
        } else {
            centerBufferingOverlay.visibility = View.GONE
        }
        tvVideoStats.text =
            "$currentVideoResolution · ${repository.bufferMode.displayName} · 实时 ${netSpeedKbps} KB/s"
        if (route != null && ch != null) {
            tvRouteBadge.text =
                "纯IPv4 线路 ${ch.currentRouteIndex + 1}/${ch.routes.size} · ${route.statusBadgeText()}"
        }
    }

    override fun onVideoResolutionChanged(width: Int, height: Int) {
        currentVideoResolution = "${width}x${height}"
        tvVideoStats.text =
            "$currentVideoResolution · ${repository.bufferMode.displayName} · 实时 ${currentNetSpeedKbps} KB/s"
    }

    override fun onRouteFailover(
        channel: TvChannel,
        failedRoute: StreamRoute,
        nextRoute: StreamRoute,
        reason: String
    ) {
        showTopBannerTemporarily()
        Toast.makeText(
            this,
            "⚡ [${channel.name}] $reason，已自动秒切至备用 ${nextRoute.label}",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onAllRoutesExhausted(channel: TvChannel, reason: String) {
        centerBufferingOverlay.visibility = View.VISIBLE
        tvBufferingStatus.text = "[${channel.name}] $reason"
    }

    // ==================== LanConfigServer.Callback 局域网手机配源与遥控回调 ====================

    override fun onRemoteUrlSubmitted(url: String, mergeWithBuiltin: Boolean): String {
        repository.subscriptionUrl = url
        uiHandler.post {
            syncRemotePlaylistWithToast(url, mergeWithBuiltin)
        }
        return "已通知电视盒子拉取并过滤纯 IPv4 订阅源: $url"
    }

    override fun onCustomPlaylistSubmitted(content: String, mergeWithBuiltin: Boolean): String {
        return try {
            val res = repository.saveCustomPlaylistContent(content, mergeWithBuiltin)
            uiHandler.post {
                refreshDrawerData()
                res.channels.firstOrNull()?.let { playSelectedChannel(it) }
                Toast.makeText(
                    this,
                    "手机推送成功！共导入 ${res.channels.size} 个频道 (${res.totalValidIpv4Routes} 条纯IPv4线路)",
                    Toast.LENGTH_LONG
                ).show()
            }
            "成功导入 ${res.channels.size} 个频道 (${res.totalValidIpv4Routes} 条纯IPv4源，已自动剔除 ${res.filteredIpv6Count} 条IPv6源)"
        } catch (e: Exception) {
            "导入失败: ${e.message}"
        }
    }

    override fun onRemoteControlAction(action: String): String {
        uiHandler.post {
            when (action) {
                "prev_channel" -> switchChannelByOffset(-1)
                "next_channel" -> switchChannelByOffset(1)
                "next_route" -> {
                    val r = playerManager.switchRouteManually(1)
                    Toast.makeText(this, "手机遥控切源：${r?.label}", Toast.LENGTH_SHORT).show()
                }
                "speed_test" -> triggerFullIpv4SpeedTest()
                "toggle_buffer" -> btnToggleBufferMode.performClick()
                "reset_builtin" -> {
                    val list = repository.resetToBuiltinPlaylist()
                    refreshDrawerData()
                    list.firstOrNull()?.let { playSelectedChannel(it) }
                    Toast.makeText(this, "已恢复内置纯 IPv4 精选源", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return "电视盒子已执行指令: $action"
    }

    override fun getCurrentStatusSummary(): String {
        val ch = playerManager.getCurrentChannel()
        val route = ch?.currentRoute()
        return if (ch != null && route != null) {
            "正在播放 [${ch.name}] - 线路 ${ch.currentRouteIndex + 1}/${ch.routes.size} (${route.statusBadgeText()}) | 模式: ${repository.bufferMode.displayName}"
        } else {
            "待机中 | 纯 IPv4 防卡顿模式已启用"
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        lanConfigServer.stop()
        playerManager.release()
        super.onDestroy()
    }
}
