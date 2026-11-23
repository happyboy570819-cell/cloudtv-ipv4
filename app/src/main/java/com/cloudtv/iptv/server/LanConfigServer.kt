package com.cloudtv.iptv.server

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 局域网手机扫码/网页配源与远程遥控服务 (LanConfigServer)
 * ====================================================================================
 * 解决痛点：在电视盒子上用遥控器一个字母一个字母敲入长串 M3U 链接极其痛苦！
 * 本服务在电视盒子本地启动 HTTP 8989 端口，手机或电脑在同一 WiFi 下打开浏览器访问
 * `http://盒子IP:8989`，即可直接：
 * 1. 推送远程 M3U / TXT 订阅地址（如电脑端 `iptv_aggregator.py --serve` 的局域网地址）；
 * 2. 直接粘贴/上传自定义的 M3U 或 TXT 直播源文本（自动执行纯 IPv4 过滤并合并多线路）；
 * 3. 手机网页直接充当虚拟遥控器：一键换台、一键切换备用信号源、一键触发全频道 IPv4 测速！
 */
class LanConfigServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val port: Int = 8989,
    private val callback: Callback
) {

    interface Callback {
        fun onRemoteUrlSubmitted(url: String, mergeWithBuiltin: Boolean): String
        fun onCustomPlaylistSubmitted(content: String, mergeWithBuiltin: Boolean): String
        fun onRemoteControlAction(action: String): String
        fun getCurrentStatusSummary(): String
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    fun start() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                while (isActive && !socket.isClosed) {
                    val client = socket.accept()
                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverJob?.cancel()
    }

    fun getServerAddress(): String {
        val ip = getLocalIpv4Address()
        return "http://$ip:$port"
    }

    private fun getLocalIpv4Address(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("169.254.")) return ip
                    }
                }
            }
        } catch (_: Exception) {
        }
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wm?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            } else "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            while (true) {
                val headerLine = reader.readLine()
                if (headerLine.isNullOrEmpty()) break
                if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val bodyChars = CharArray(contentLength.coerceIn(0, 2 * 1024 * 1024))
            var readTotal = 0
            while (readTotal < bodyChars.size) {
                val r = reader.read(bodyChars, readTotal, bodyChars.size - readTotal)
                if (r <= 0) break
                readTotal += r
            }
            val body = String(bodyChars, 0, readTotal)
            val params = parseFormParams(body)

            val output = sock.getOutputStream()
            when {
                method == "POST" && path.startsWith("/api/sub") -> {
                    val url = params["url"].orEmpty()
                    val merge = params["merge"] != "false"
                    val msg = callback.onRemoteUrlSubmitted(url, merge)
                    sendJsonResponse(output, msg)
                }
                method == "POST" && path.startsWith("/api/content") -> {
                    val content = params["content"].orEmpty()
                    val merge = params["merge"] != "false"
                    val msg = callback.onCustomPlaylistSubmitted(content, merge)
                    sendJsonResponse(output, msg)
                }
                method == "POST" && path.startsWith("/api/action") -> {
                    val act = params["action"].orEmpty()
                    val msg = callback.onRemoteControlAction(act)
                    sendJsonResponse(output, msg)
                }
                else -> {
                    sendHtmlResponse(output, buildWebPortalHtml(callback.getCurrentStatusSummary()))
                }
            }
        }
    }

    private fun parseFormParams(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val result = HashMap<String, String>()
        for (pair in body.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result[k] = v
            }
        }
        return result
    }

    private fun sendJsonResponse(out: OutputStream, message: String) {
        val safeMsg = message.replace("\"", "\\\"").replace("\n", " ")
        val json = """{"message":"$safeMsg"}"""
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendHtmlResponse(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun buildWebPortalHtml(statusSummary: String): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>云视 TV (纯IPv4防卡顿版) - 局域网配源与遥控中心</title>
            <style>
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 16px; }
                .card { background: #1e293b; border-radius: 12px; padding: 18px; margin-bottom: 16px; box-shadow: 0 4px 12px rgba(0,0,0,0.35); border: 1px solid #334155; }
                h2 { margin-top: 0; color: #38bdf8; font-size: 20px; }
                h3 { margin-top: 0; color: #e2e8f0; font-size: 16px; }
                .status { background: #0284c7; color: #fff; padding: 10px 14px; border-radius: 8px; font-size: 14px; margin-bottom: 16px; }
                input[type="text"], textarea { width: 100%; box-sizing: border-box; background: #0f172a; border: 1px solid #475569; color: #f8fafc; border-radius: 8px; padding: 10px; font-size: 14px; margin-bottom: 10px; }
                textarea { height: 130px; font-family: monospace; }
                button { background: #0ea5e9; color: white; border: none; padding: 11px 16px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; margin-right: 6px; margin-bottom: 8px; }
                button.secondary { background: #334155; }
                button.success { background: #10b981; }
                .grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
                #toast { position: fixed; bottom: 20px; left: 50%; transform: translateX(-50%); background: #10b981; color: #fff; padding: 10px 20px; border-radius: 20px; display: none; font-size: 14px; }
            </style>
        </head>
        <body>
            <h2>📺 云视 TV (纯 IPv4 专版) 局域网管理中心</h2>
            <div class="status" id="statusBox">当前状态：$statusSummary</div>

            <div class="card">
                <h3>🎮 手机快捷遥控器（遇到卡顿一键切源）</h3>
                <div class="grid">
                    <button onclick="sendAction('prev_channel')">⬆️ 上一频道</button>
                    <button onclick="sendAction('next_channel')">⬇️ 下一频道</button>
                    <button class="success" onclick="sendAction('next_route')">⚡ 切换备用信号源</button>
                    <button class="secondary" onclick="sendAction('speed_test')">🚀 全频道 IPv4 测速优选</button>
                    <button class="secondary" onclick="sendAction('toggle_buffer')">🛡️ 切换防卡顿缓冲模式</button>
                    <button class="secondary" onclick="sendAction('reset_builtin')">🔄 恢复内置精选 IPv4 源</button>
                </div>
            </div>

            <div class="card">
                <h3>🔗 方式一：推送远程或局域网 M3U / TXT 订阅链接</h3>
                <p style="font-size:12px;color:#94a3b8;margin-top:0;">支持粘贴电脑端聚合测速器开启的 http://电脑IP:8123/ipv4_live.m3u 或任意公开源，APP 会自动剔除 IPv6 源并聚合多线路。</p>
                <input type="text" id="subUrl" placeholder="输入 http://.../ipv4_live.m3u 或 .txt 链接" />
                <button onclick="submitUrl(true)">➕ 合并到现有多线路</button>
                <button class="secondary" onclick="submitUrl(false)">🔃 仅使用该订阅源</button>
            </div>

            <div class="card">
                <h3>📋 方式二：直接粘贴 M3U 或 TXT 直播源文本</h3>
                <textarea id="rawContent" placeholder="支持标准 #EXTM3U 格式，或：&#10;央视频道,#genre#&#10;CCTV-1 综合,http://xxx/1.m3u8"></textarea>
                <button onclick="submitContent(true)">➕ 过滤 IPv6 并合并导入</button>
                <button class="secondary" onclick="submitContent(false)">🔃 覆盖导入</button>
            </div>

            <div id="toast"></div>

            <script>
                function showToast(msg) {
                    const t = document.getElementById('toast');
                    t.innerText = msg;
                    t.style.display = 'block';
                    setTimeout(() => { t.style.display = 'none'; }, 3500);
                }
                async function postForm(url, data) {
                    const body = Object.keys(data).map(k => encodeURIComponent(k) + '=' + encodeURIComponent(data[k])).join('&');
                    const res = await fetch(url, { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body });
                    const json = await res.json();
                    showToast(json.message || '操作已发送至电视盒子');
                }
                function sendAction(action) { postForm('/api/action', { action }); }
                function submitUrl(merge) {
                    const url = document.getElementById('subUrl').value.trim();
                    if (!url) return showToast('请输入有效的订阅链接');
                    postForm('/api/sub', { url, merge });
                }
                function submitContent(merge) {
                    const content = document.getElementById('rawContent').value.trim();
                    if (!content) return showToast('请粘贴直播源内容');
                    postForm('/api/content', { content, merge });
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}
