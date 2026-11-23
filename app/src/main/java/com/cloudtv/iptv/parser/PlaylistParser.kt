package com.cloudtv.iptv.parser

import com.cloudtv.iptv.model.StreamRoute
import com.cloudtv.iptv.model.TvChannel
import com.cloudtv.iptv.network.Ipv4OnlyDns

/**
 * 直播源全格式解析与同名多线路聚合器
 * ========================================================================
 * 支持格式：
 * 1. 标准 M3U / M3U8 格式 (#EXTM3U + #EXTINF:-1 group-title="...",频道名)
 * 2. 国内电视盒子通用 TXT 格式 (分类名,#genre# + 频道名,http://...#http://...)
 *
 * 核心特性：
 * - 自动过滤所有 IPv6 源（如 http://[2409:...]/...），只保留纯 IPv4 源；
 * - 频道名称智能标准化（例如将 "CCTV1综合高清"、"CCTV-1 HD" 统一归并为 "CCTV-1 综合"）；
 * - 将同一频道名下的多条不同 URL 自动合并为该频道的 `routes` 列表（线路1、线路2、线路3...），
 *   为播放器的“卡顿自动切源”和“遥控器左右键秒换源”提供底层数据支撑。
 */
object PlaylistParser {

    private val cctvStandardMap = mapOf(
        "CCTV1" to ("CCTV-1 综合" to "央视频道"),
        "CCTV2" to ("CCTV-2 财经" to "央视频道"),
        "CCTV3" to ("CCTV-3 综艺" to "央视频道"),
        "CCTV4" to ("CCTV-4 中文国际" to "央视频道"),
        "CCTV5" to ("CCTV-5 体育" to "央视频道"),
        "CCTV5+" to ("CCTV-5+ 体育赛事" to "央视频道"),
        "CCTV6" to ("CCTV-6 电影" to "央视频道"),
        "CCTV7" to ("CCTV-7 国防军事" to "央视频道"),
        "CCTV8" to ("CCTV-8 电视剧" to "央视频道"),
        "CCTV9" to ("CCTV-9 纪录" to "央视频道"),
        "CCTV10" to ("CCTV-10 科教" to "央视频道"),
        "CCTV11" to ("CCTV-11 戏曲" to "央视频道"),
        "CCTV12" to ("CCTV-12 社会与法" to "央视频道"),
        "CCTV13" to ("CCTV-13 新闻" to "央视频道"),
        "CCTV14" to ("CCTV-14 少儿" to "央视频道"),
        "CCTV15" to ("CCTV-15 音乐" to "央视频道"),
        "CCTV16" to ("CCTV-16 奥林匹克" to "央视频道"),
        "CCTV17" to ("CCTV-17 农业农村" to "央视频道"),
        "CCTV4K" to ("CCTV-4K 超高清" to "央视频道"),
        "CGTN" to ("CGTN 国际" to "央视频道")
    )

    private val groupTitleRegex = Regex("""group-title="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val latencyAttrRegex = Regex("""route-latency="(\d+)ms"""", RegexOption.IGNORE_CASE)
    private val speedAttrRegex = Regex("""route-speed="([\d.]+)KB/s"""", RegexOption.IGNORE_CASE)
    private val bracketCleanRegex = Regex("""\[.*?\]|\(.*?\)|（.*?）""")
    private val qualityTagRegex = Regex("""(?i)\b(HD|FHD|UHD|4K|1080P|720P|50FPS|IPV4|IPV6|HEVC)\b""")
    private val suffixCleanRegex = Regex("""[-_\s]*(高清|超清|标清|蓝光|直播|备用|线路\d+|源\d+)$""")
    private val cctvPrefixRegex = Regex("""^CCTV(\d+\+?|4K)""")

    data class ParseResult(
        val channels: List<TvChannel>,
        val totalValidIpv4Routes: Int,
        val filteredIpv6Count: Int
    )

    fun parse(rawContent: String, originTag: String = "订阅源", ipv4Only: Boolean = true): ParseResult {
        val channelMap = LinkedHashMap<String, TvChannel>()
        val channelUrlSets = HashMap<String, MutableSet<String>>()

        var currentGroup = "默认频道"
        var pendingName: String? = null
        var pendingLatency = -1
        var pendingSpeed = 0f
        var validRoutesCount = 0
        var filteredIpv6Count = 0

        val lines = rawContent.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }

        fun addCandidateRoute(rawName: String, rawGroup: String, rawUrl: String, latency: Int = -1, speed: Float = 0f) {
            // 去除尾部 $ 线路备注标签
            val cleanUrl = rawUrl.substringBefore("$").trim()
            if (cleanUrl.isEmpty()) return

            if (ipv4Only && !Ipv4OnlyDns.isPureIpv4Url(cleanUrl)) {
                filteredIpv6Count++
                return
            }

            val (stdName, stdGroup) = normalizeNameAndGroup(rawName, rawGroup)
            val urlSet = channelUrlSets.getOrPut(stdName) { HashSet() }
            if (!urlSet.add(cleanUrl)) {
                return // 同一频道下完全相同的 URL 去重
            }

            val channel = channelMap.getOrPut(stdName) {
                TvChannel(
                    id = stdName,
                    channelNumber = channelMap.size + 1,
                    name = stdName,
                    groupName = stdGroup
                )
            }
            val routeNumber = channel.routes.size + 1
            channel.routes.add(
                StreamRoute(
                    url = cleanUrl,
                    label = "IPv4线路$routeNumber",
                    sourceOrigin = originTag,
                    latencyMs = latency,
                    speedKbps = speed
                )
            )
            validRoutesCount++
        }

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> continue

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    groupTitleRegex.find(line)?.groupValues?.getOrNull(1)?.let {
                        if (it.isNotBlank()) currentGroup = it.trim()
                    }
                    pendingLatency = latencyAttrRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
                    pendingSpeed = speedAttrRegex.find(line)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
                    pendingName = line.substringAfter(",", "未知频道").trim()
                }

                line.startsWith("#") -> continue

                line.contains(",#genre#", ignoreCase = true) -> {
                    currentGroup = line.substringBefore(",").trim().ifEmpty { "默认频道" }
                    pendingName = null
                }

                line.contains(",") && !line.startsWith("http://", true) && !line.startsWith("https://", true) -> {
                    val namePart = line.substringBefore(",").trim()
                    val urlPart = line.substringAfter(",").trim()
                    // 支持 TVBox 风格用 # 拼接的多条备用源: 频道名,http://a#http://b
                    for (subUrl in urlPart.split("#")) {
                        if (subUrl.isNotBlank()) {
                            addCandidateRoute(namePart, currentGroup, subUrl.trim())
                        }
                    }
                    pendingName = null
                }

                line.startsWith("http://", true) ||
                line.startsWith("https://", true) ||
                line.startsWith("rtsp://", true) ||
                line.startsWith("rtmp://", true) -> {
                    val chName = pendingName ?: "未知频道"
                    addCandidateRoute(chName, currentGroup, line, pendingLatency, pendingSpeed)
                    pendingName = null
                    pendingLatency = -1
                    pendingSpeed = 0f
                }
            }
        }

        // 重新按标准分类顺序（央视频道 -> 卫视频道 -> 其它）整理频道号
        val sortedList = channelMap.values.sortedWith(
            compareBy<TvChannel> { groupPriority(it.groupName) }
                .thenBy { cctvNumberOrder(it.name) }
        ).mapIndexed { index, tvChannel ->
            tvChannel.copy(channelNumber = index + 1)
        }

        return ParseResult(
            channels = sortedList,
            totalValidIpv4Routes = validRoutesCount,
            filteredIpv6Count = filteredIpv6Count
        )
    }

    private fun normalizeNameAndGroup(rawName: String, rawGroup: String): Pair<String, String> {
        var name = rawName.trim()
        name = bracketCleanRegex.replace(name, "")
        name = qualityTagRegex.replace(name, "")
        name = suffixCleanRegex.replace(name, "").trim()

        val upperCompact = name.uppercase().replace(" ", "").replace("-", "")
        val cctvMatch = cctvPrefixRegex.find(upperCompact)
        if (cctvMatch != null) {
            val key = "CCTV${cctvMatch.groupValues[1]}"
            cctvStandardMap[key]?.let { return it }
            return "CCTV-${cctvMatch.groupValues[1]}" to "央视频道"
        }
        if (upperCompact.contains("CGTN")) {
            return cctvStandardMap["CGTN"]!!
        }
        if (name.contains("央视") || name.contains("中央")) {
            return name to "央视频道"
        }
        if (name.contains("卫视") || rawGroup.contains("卫视")) {
            return name.ifEmpty { rawName } to "卫视频道"
        }
        if (name.contains("少儿") || name.contains("卡通") || name.contains("动漫")) {
            return name.ifEmpty { rawName } to "少儿动漫"
        }
        val finalGroup = when {
            rawGroup.contains("央视") -> "央视频道"
            rawGroup.contains("卫视") -> "卫视频道"
            rawGroup.isNotBlank() && rawGroup != "默认频道" -> rawGroup
            else -> "地方与综合频道"
        }
        return (name.ifEmpty { rawName.trim() }) to finalGroup
    }

    private fun groupPriority(groupName: String): Int = when {
        groupName.contains("央视") -> 1
        groupName.contains("卫视") -> 2
        groupName.contains("少儿") -> 3
        groupName.contains("地方") -> 4
        else -> 5
    }

    private fun cctvNumberOrder(channelName: String): Int {
        val m = Regex("""CCTV-(\d+)""").find(channelName)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 999
    }
}
