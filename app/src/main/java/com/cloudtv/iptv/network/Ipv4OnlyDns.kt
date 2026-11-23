package com.cloudtv.iptv.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 纯 IPv4 强制 DNS 解析器 + 电视盒子全兼容 SSL 客户端构建器 (Ipv4OnlyDns)
 * ====================================================================================
 * 核心作用：
 * 1. 纯 IPv4 路由：直接剔除所有 Inet6Address，只向 ExoPlayer 返回 Inet4Address，防止无 IPv6 环境下黑屏死等。
 * 2. 老旧电视盒子 HTTPS 证书兼容：许多安卓电视盒子系统版本较老（Android 5~9），根证书库过期，
 *    访问 https:// 直播源会报 SSLHandshakeException 导致“信号源不可用”。
 *    通过 createHttpClientBuilder() 自动忽略过期/自签名 SSL 证书校验，确保所有 HTTPS 直播源在盒子上 100% 连通！
 */
object Ipv4OnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.contains(":")) {
            throw UnknownHostException("当前为纯 IPv4 模式，已自动拦截 IPv6 地址: $hostname")
        }

        val allAddresses = Dns.SYSTEM.lookup(hostname)
        val ipv4Addresses = allAddresses.filterIsInstance<Inet4Address>()

        if (ipv4Addresses.isEmpty()) {
            throw UnknownHostException("域名 [$hostname] 不存在可用的 IPv4 (A) 记录，已在纯 IPv4 模式下过滤")
        }
        return ipv4Addresses
    }

    fun isPureIpv4Url(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("rtsp://", ignoreCase = true) &&
            !trimmed.startsWith("rtmp://", ignoreCase = true)
        ) {
            return false
        }
        if (trimmed.contains("[") || trimmed.contains("]")) {
            return false
        }
        return true
    }

    /**
     * 创建专为安卓电视盒子优化的 OkHttpClient.Builder：
     * - 绑定纯 IPv4 DNS
     * - 信任所有 SSL/TLS 证书（解决机顶盒根证书过期导致 HTTPS 直播源连接失败的问题）
     * - 支持跨协议 (HTTP <-> HTTPS) 301/302 重定向
     */
    fun createHttpClientBuilder(
        connectTimeoutMs: Long = 10_000L,
        readTimeoutMs: Long = 15_000L
    ): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .dns(this)
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)

        try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (_: Exception) {
        }

        return builder
    }
}
