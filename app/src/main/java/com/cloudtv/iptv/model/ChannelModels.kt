package com.cloudtv.iptv.model

/**
 * 单条信号源线路（每个电视频道可绑定多条 IPv4 备用信号源线路，用于卡顿/失效时无缝自动切源）
 */
data class StreamRoute(
    val url: String,
    val label: String = "主线路",
    val sourceOrigin: String = "内置IPv4源",
    @Volatile var latencyMs: Int = -1,          // 测速首包延迟 (ms)，-1 表示未测速，9999 表示超时失效
    @Volatile var speedKbps: Float = 0f,        // 实测下载速率 (KB/s)
    @Volatile var failCount: Int = 0,           // 连续播放失败或卡顿次数（用于故障降权）
    @Volatile var lastFailTimestamp: Long = 0L, // 最近一次故障时间戳（冷却期内跳过该故障源）
    @Volatile var resolution: String = ""       // 分辨率标签，如 1920x1080
) {
    /**
     * 判断该线路是否处于近期故障冷却期（3分钟内连续失败过的源排到最后）
     */
    fun isInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean {
        return failCount > 0 && (nowMs - lastFailTimestamp) < 180_000L
    }

    /**
     * 综合健康度评分（越小越优先播放）
     */
    fun healthScore(nowMs: Long = System.currentTimeMillis()): Int {
        var score = 0
        if (isInCooldown(nowMs)) {
            score += 50_000 * failCount
        }
        score += when {
            latencyMs in 1..4999 -> latencyMs
            latencyMs >= 5000 -> 30_000
            else -> 1_500 // 未测速默认中等优先级
        }
        // 下载速率越高，得分越优
        score -= (speedKbps.coerceAtMost(3000f) * 0.3f).toInt()
        return score
    }

    fun statusBadgeText(): String {
        return when {
            failCount > 0 && latencyMs >= 5000 -> "不可用"
            latencyMs in 1..4999 && speedKbps > 0 -> "${latencyMs}ms · ${speedKbps.toInt()}KB/s"
            latencyMs in 1..4999 -> "${latencyMs}ms"
            else -> "纯IPv4就绪"
        }
    }
}

/**
 * 单个电视频道模型（包含频道号、标准频道名、所属分类、以及多条备用 IPv4 信号源）
 */
data class TvChannel(
    val id: String,
    val channelNumber: Int,
    val name: String,
    val groupName: String,
    val routes: MutableList<StreamRoute> = mutableListOf(),
    @Volatile var currentRouteIndex: Int = 0,
    @Volatile var isFavorite: Boolean = false
) {
    fun currentRoute(): StreamRoute? {
        if (routes.isEmpty()) return null
        val safeIdx = currentRouteIndex.coerceIn(0, routes.size - 1)
        currentRouteIndex = safeIdx
        return routes[safeIdx]
    }

    /**
     * 根据实时测速与故障记录重排线路列表，并优先选择健康度最佳的线路
     */
    @Synchronized
    fun sortRoutesByHealth() {
        val currentUrl = currentRoute()?.url
        val now = System.currentTimeMillis()
        routes.sortBy { it.healthScore(now) }
        if (currentUrl != null) {
            val newIdx = routes.indexOfFirst { it.url == currentUrl }
            if (newIdx >= 0) currentRouteIndex = newIdx
        }
    }

    /**
     * 选择下一条最佳备用信号源索引（优先跳过处于故障冷却期的线路）
     */
    @Synchronized
    fun pickNextHealthyRouteIndex(): Int {
        if (routes.size <= 1) return 0
        val now = System.currentTimeMillis()
        for (offset in 1 until routes.size) {
            val candidateIdx = (currentRouteIndex + offset) % routes.size
            if (!routes[candidateIdx].isInCooldown(now)) {
                currentRouteIndex = candidateIdx
                return candidateIdx
            }
        }
        // 如果所有线路都在冷却期，按顺序切到下一条重试
        currentRouteIndex = (currentRouteIndex + 1) % routes.size
        return currentRouteIndex
    }
}

/**
 * 频道分组（央视频道 / 卫视频道 / 地方与综合 / 我的收藏）
 */
data class ChannelGroup(
    val name: String,
    val channels: List<TvChannel>
)

/**
 * 三级抗抖动缓冲策略模式
 */
enum class BufferMode(
    val displayName: String,
    val description: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
    val stallTimeoutMs: Long
) {
    FAST_ZAPPING(
        displayName = "极速秒开模式",
        description = "换台 0.8 秒起播，适合带宽充裕的优质源",
        minBufferMs = 4_000,
        maxBufferMs = 18_000,
        playbackBufferMs = 800,
        rebufferMs = 1_800,
        stallTimeoutMs = 5_500L
    ),
    BALANCED_ANTI_STUTTER(
        displayName = "均衡防卡顿模式 (推荐)",
        description = "1.5 秒起播 + 30 秒环形抗抖动缓冲，兼顾换台速度与稳定性",
        minBufferMs = 10_000,
        maxBufferMs = 32_000,
        playbackBufferMs = 1_500,
        rebufferMs = 3_000,
        stallTimeoutMs = 6_500L
    ),
    WEAK_NETWORK_LARGE_BUFFER(
        displayName = "弱网超大缓冲模式",
        description = "2.8 秒起播 + 50 秒超大缓冲池，彻底消除晚高峰网络波动卡顿",
        minBufferMs = 18_000,
        maxBufferMs = 50_000,
        playbackBufferMs = 2_800,
        rebufferMs = 5_000,
        stallTimeoutMs = 8_500L
    );

    fun next(): BufferMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }
}
