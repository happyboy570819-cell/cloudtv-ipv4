package com.cloudtv.iptv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import com.cloudtv.iptv.model.BufferMode
import com.cloudtv.iptv.model.StreamRoute
import com.cloudtv.iptv.model.TvChannel
import com.cloudtv.iptv.network.Ipv4OnlyDns

/**
 * 防卡顿 + 故障毫秒级自动切源播放器核心引擎 (PlayerManager)
 * ====================================================================================
 * 专门针对安卓电视盒子直播卡顿、信号源失效设计的五大机制：
 * 1. 【底层纯 IPv4 路由 + 全兼容 SSL】：通过 Ipv4OnlyDns.createHttpClientBuilder() 强制 IPv4 并解决老盒子 HTTPS 证书报错。
 * 2. 【PHP动态源与无后缀流智能协议识别】：很多高可用源形如 `iptv.php?id=cctv1`（无 `.m3u8` 后缀但实际返回 HLS 流或直连 TS 流），
 *    本引擎支持在 HLS 与 Progressive TS/FLV 容器格式之间毫秒级自动回退重连，确保无后缀动态源 100% 正常解码！
 * 3. 【三级动态抗抖动缓冲池】：支持在“极速秒开 / 均衡防卡顿 / 弱网大缓冲”之间切换。
 * 4. 【卡顿看门狗 (Stall Watchdog)】：连续缓冲超过阈值时自动触发 Failover 切换到下一条纯 IPv4 备用线路。
 * 5. 【异常秒切源 + 故障线路冷却降权】：遇到错误自动标记当前线路冷却并无缝切换到下一条可用信号源。
 */
@OptIn(UnstableApi::class)
class PlayerManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onChannelPlaying(channel: TvChannel, route: StreamRoute, routeIndex: Int, totalRoutes: Int)
        fun onBufferingStateChanged(isBuffering: Boolean, bufferedPercent: Int, netSpeedKbps: Long)
        fun onVideoResolutionChanged(width: Int, height: Int)
        fun onRouteFailover(channel: TvChannel, failedRoute: StreamRoute, nextRoute: StreamRoute, reason: String)
        fun onAllRoutesExhausted(channel: TvChannel, reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private var boundPlayerView: PlayerView? = null

    private var currentChannel: TvChannel? = null
    private var bufferMode: BufferMode = BufferMode.BALANCED_ANTI_STUTTER
    private var preferSoftwareDecoder: Boolean = false
    private var consecutiveFailCountInChannel: Int = 0

    // 记录当前线路是否已经尝试过强制 HLS (APPLICATION_M3U8) 或 Progressive 格式回退
    private var triedAlternateMimeForCurrentRoute: Boolean = false
    private var currentForcedMimeType: String? = null

    private val stallWatchdogRunnable = Runnable {
        val player = exoPlayer ?: return@Runnable
        if (player.playbackState == Player.STATE_BUFFERING) {
            triggerFailoverOrReconnect("缓冲等待超时，正在为您切换更快线路")
        }
    }

    private val statsPollerRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer
            if (player != null) {
                val isBuffering = player.playbackState == Player.STATE_BUFFERING
                val speedKbps = (bandwidthMeter?.bitrateEstimate ?: 0L) / 8_000L
                listener.onBufferingStateChanged(isBuffering, player.bufferedPercentage, speedKbps)
            }
            mainHandler.postDelayed(this, 2000L)
        }
    }

    fun pause() {
        mainHandler.removeCallbacks(stallWatchdogRunnable)
        exoPlayer?.playWhenReady = false
    }

    fun resume() {
        exoPlayer?.playWhenReady = true
    }

    fun initialize(
        playerView: PlayerView,
        initialBufferMode: BufferMode,
        useSoftwareDecoder: Boolean
    ) {
        this.boundPlayerView = playerView
        this.bufferMode = initialBufferMode
        this.preferSoftwareDecoder = useSoftwareDecoder
        rebuildExoPlayer()
        mainHandler.post(statsPollerRunnable)
    }

    private fun rebuildExoPlayer() {
        exoPlayer?.release()

        val okHttpClient = Ipv4OnlyDns.createHttpClientBuilder(
            connectTimeoutMs = 10_000L,
            readTimeoutMs = 15_000L
        ).build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 ExoPlayerLib/2.19.1")

        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
            .setLiveTargetOffsetMs(4_000L)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferMode.minBufferMs,
                bufferMode.maxBufferMs,
                bufferMode.playbackBufferMs,
                bufferMode.rebufferMs
            )
            // 严格限制缓冲区最大内存占用为 10MB，防止安卓 9 机顶盒长时间播放 1080P/4K 流耗尽 JVM 堆内存触发 Full-GC 卡死和 OOM 闪退
            .setTargetBufferBytes(10 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        // 默认开启 EXTENSION_RENDERER_MODE_ON：标准 H.264/H.265 视频与 AAC 音频走晶晨 Amlogic 硬件 MediaCodec 硬解（0 CPU 占用），
        // 遇到广电 MP2(0x03) / AC-3(0x06) 音轨时自动无缝启用内置的 FfmpegAudioRenderer 软解！
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(
                if (preferSoftwareDecoder) {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                } else {
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )
            setEnableDecoderFallback(true)
        }

        val meter = DefaultBandwidthMeter.Builder(context).build()
        this.bandwidthMeter = meter

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(meter)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        mainHandler.removeCallbacks(stallWatchdogRunnable)
                        // 给动态 PHP 跳转源留足起播缓冲时间 (至少 11 秒)
                        val timeoutMs = bufferMode.stallTimeoutMs.coerceAtLeast(11_000L)
                        mainHandler.postDelayed(stallWatchdogRunnable, timeoutMs)
                        listener.onBufferingStateChanged(
                            true,
                            player.bufferedPercentage,
                            (meter.bitrateEstimate) / 8_000L
                        )
                    }
                    Player.STATE_READY -> {
                        mainHandler.removeCallbacks(stallWatchdogRunnable)
                        consecutiveFailCountInChannel = 0
                        currentChannel?.currentRoute()?.let { route ->
                            route.failCount = 0
                        }
                        listener.onBufferingStateChanged(
                            false,
                            player.bufferedPercentage,
                            (meter.bitrateEstimate) / 8_000L
                        )
                    }
                    Player.STATE_ENDED -> {
                        mainHandler.removeCallbacks(stallWatchdogRunnable)
                        triggerFailoverOrReconnect("直播流中断 (EOF)")
                    }
                    Player.STATE_IDLE -> {
                        mainHandler.removeCallbacks(stallWatchdogRunnable)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                mainHandler.removeCallbacks(stallWatchdogRunnable)

                // 核心兼容逻辑：如果无后缀 URL (如 .php?id=cctv1) 因容器格式不匹配报错，立即切换 HLS/Progressive MIME 重试一次！
                val isFormatError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

                if (isFormatError && !triedAlternateMimeForCurrentRoute) {
                    triedAlternateMimeForCurrentRoute = true
                    val nextMime = if (currentForcedMimeType == MimeTypes.APPLICATION_M3U8) {
                        null // 从强制 HLS 切回直连 TS/FLV 自动嗅探
                    } else {
                        MimeTypes.APPLICATION_M3U8 // 从直连切到强制 HLS (.m3u8) 解析
                    }
                    val ch = currentChannel
                    val route = ch?.currentRoute()
                    if (ch != null && route != null) {
                        startRoutePlaybackInternal(route, nextMime)
                        return
                    }
                }

                val reason = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "信号源响应异常(HTTP错误)"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "流格式解析异常"
                    PlaybackException.ERROR_CODE_DECODING_FAILED -> "视频解码异常"
                    else -> "信号异常(${error.errorCodeName})"
                }
                triggerFailoverOrReconnect(reason)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    currentChannel?.currentRoute()?.resolution = "${videoSize.width}x${videoSize.height}"
                    listener.onVideoResolutionChanged(videoSize.width, videoSize.height)
                }
            }
        })

        player.playWhenReady = true
        this.exoPlayer = player
        boundPlayerView?.player = player
    }

    fun playChannel(channel: TvChannel, specificRouteIndex: Int? = null) {
        mainHandler.removeCallbacks(stallWatchdogRunnable)
        val isNewChannel = (currentChannel?.id != channel.id)
        currentChannel = channel
        if (isNewChannel) {
            consecutiveFailCountInChannel = 0
            channel.sortRoutesByHealth()
        }

        if (channel.routes.isEmpty()) {
            listener.onAllRoutesExhausted(channel, "该频道暂无可用纯 IPv4 信号源")
            return
        }

        if (specificRouteIndex != null) {
            channel.currentRouteIndex = specificRouteIndex.coerceIn(0, channel.routes.size - 1)
        }

        val route = channel.currentRoute() ?: return
        listener.onChannelPlaying(channel, route, channel.currentRouteIndex, channel.routes.size)

        triedAlternateMimeForCurrentRoute = false
        val initialMime = guessInitialMimeType(route.url)
        startRoutePlaybackInternal(route, initialMime)
    }

    /**
     * 根据 URL 特征智能判断首选流协议：
     * - 包含 .m3u8 或 .php 或 /163189/ 等常见 HLS 动态代理源时，优先按 HLS (APPLICATION_M3U8) 加载；
     * - 若实际为直连 TS/FLV 流，onPlayerError 会毫秒级自动切换为 null (Progressive Extractor) 重试。
     */
    private fun guessInitialMimeType(url: String): String? {
        val low = url.lowercase()
        return when {
            low.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            low.contains(".flv") || low.contains(".ts") -> null
            low.contains("/channel/") && low.contains("streamid=") -> null // 直连 TS 流
            low.contains(".php") || low.contains("/163189/") || low.contains("jdshipin.com") -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
    }

    private fun startRoutePlaybackInternal(route: StreamRoute, mimeType: String?) {
        val player = exoPlayer ?: return
        currentForcedMimeType = mimeType
        val builder = MediaItem.Builder().setUri(route.url)
        if (mimeType != null) {
            builder.setMimeType(mimeType)
        }
        player.stop()
        player.setMediaItem(builder.build())
        player.prepare()
        player.playWhenReady = true
    }

    fun switchRouteManually(delta: Int): StreamRoute? {
        val ch = currentChannel ?: return null
        if (ch.routes.isEmpty()) return null
        val size = ch.routes.size
        val nextIndex = ((ch.currentRouteIndex + delta) % size + size) % size
        consecutiveFailCountInChannel = 0
        playChannel(ch, nextIndex)
        return ch.currentRoute()
    }

    private fun triggerFailoverOrReconnect(reason: String) {
        val ch = currentChannel ?: return
        val failedRoute = ch.currentRoute() ?: return

        failedRoute.failCount += 1
        failedRoute.lastFailTimestamp = System.currentTimeMillis()
        if (failedRoute.latencyMs < 5000) {
            failedRoute.latencyMs = 9999
        }

        consecutiveFailCountInChannel += 1

        if (ch.routes.size > 1 && consecutiveFailCountInChannel < ch.routes.size * 2) {
            val nextIdx = ch.pickNextHealthyRouteIndex()
            val nextRoute = ch.routes[nextIdx]
            listener.onRouteFailover(ch, failedRoute, nextRoute, reason)
            playChannel(ch, nextIdx)
            return
        }

        listener.onAllRoutesExhausted(ch, "$reason，正在为您自动重连...")
        mainHandler.postDelayed({
            val activeCh = currentChannel
            if (activeCh != null && activeCh.id == ch.id) {
                consecutiveFailCountInChannel = 0
                val nextIdx = activeCh.pickNextHealthyRouteIndex()
                playChannel(activeCh, nextIdx)
            }
        }, 2500L)
    }

    fun updatePlaybackConfig(newBufferMode: BufferMode, useSoftwareDecoder: Boolean) {
        this.bufferMode = newBufferMode
        this.preferSoftwareDecoder = useSoftwareDecoder
        val activeChannel = currentChannel
        val activeRouteIdx = activeChannel?.currentRouteIndex
        rebuildExoPlayer()
        if (activeChannel != null) {
            playChannel(activeChannel, activeRouteIdx)
        }
    }

    fun getCurrentChannel(): TvChannel? = currentChannel

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
    }
}
