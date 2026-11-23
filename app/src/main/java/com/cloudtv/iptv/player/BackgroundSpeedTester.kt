package com.cloudtv.iptv.player

import com.cloudtv.iptv.model.StreamRoute
import com.cloudtv.iptv.model.TvChannel
import com.cloudtv.iptv.network.Ipv4OnlyDns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 后台静默纯 IPv4 信号源并发测速器 (BackgroundSpeedTester)
 */
class BackgroundSpeedTester(private val scope: CoroutineScope) {

    private val probeClient = Ipv4OnlyDns.createHttpClientBuilder(
        connectTimeoutMs = 5000L,
        readTimeoutMs = 5000L
    ).build()

    private val concurrencySemaphore = Semaphore(8)
    private var activeFullTestJob: Job? = null

    fun probeChannelRoutesAsync(channel: TvChannel, onUpdated: () -> Unit) {
        if (channel.routes.size <= 1) return
        scope.launch(Dispatchers.IO) {
            channel.routes.map { route ->
                async {
                    concurrencySemaphore.withPermit {
                        probeSingleRoute(route)
                    }
                }
            }.awaitAll()
            withContext(Dispatchers.Main) {
                onUpdated()
            }
        }
    }

    fun startFullPlaylistSpeedTest(
        channels: List<TvChannel>,
        onProgress: (tested: Int, total: Int, available: Int) -> Unit,
        onComplete: (availableRoutes: Int, totalRoutes: Int) -> Unit
    ) {
        activeFullTestJob?.cancel()
        activeFullTestJob = scope.launch(Dispatchers.IO) {
            val allRoutes = channels.flatMap { it.routes }
            val total = allRoutes.size
            var tested = 0
            var available = 0

            allRoutes.map { route ->
                async {
                    concurrencySemaphore.withPermit {
                        val ok = probeSingleRoute(route)
                        synchronized(this@BackgroundSpeedTester) {
                            tested++
                            if (ok) available++
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(tested, total, available)
                        }
                    }
                }
            }.awaitAll()

            channels.forEach { it.sortRoutesByHealth() }

            withContext(Dispatchers.Main) {
                onComplete(available, total)
            }
        }
    }

    private fun probeSingleRoute(route: StreamRoute): Boolean {
        if (!Ipv4OnlyDns.isPureIpv4Url(route.url)) {
            route.latencyMs = 9999
            route.speedKbps = 0f
            return false
        }

        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(route.url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Android TV) ExoPlayerLib/2.19.1")
                .build()

            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    route.latencyMs = 9999
                    route.speedKbps = 0f
                    return false
                }
                val stream = resp.body?.byteStream() ?: return false
                val buf = ByteArray(16 * 1024)
                val firstRead = stream.read(buf)
                val tFirst = System.currentTimeMillis()
                if (firstRead <= 0) {
                    route.latencyMs = 9999
                    return false
                }
                val latency = (tFirst - t0).toInt().coerceAtLeast(1)

                var totalBytes = firstRead
                val maxReadBytes = 48 * 1024
                while (totalBytes < maxReadBytes) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    totalBytes += n
                }
                val elapsedSec = ((System.currentTimeMillis() - t0).coerceAtLeast(25)) / 1000f
                route.latencyMs = latency
                route.speedKbps = (totalBytes / 1024f) / elapsedSec
                true
            }
        } catch (e: Exception) {
            route.latencyMs = 9999
            route.speedKbps = 0f
            false
        }
    }
}
