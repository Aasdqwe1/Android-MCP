package com.mcp.mcpbridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 基于 HttpURLConnection 的 MCP HTTP 发送器。
 *
 * 纯 JDK 实现（Android / JVM 通用），零额外依赖。
 * 一次调用 = 一个 HTTP POST，请求体是 JSON-RPC 文本，返回响应体文本。
 *
 * 对应 MCP「Streamable HTTP」传输：每条 JSON-RPC 消息一个 POST，
 * 服务端同步返回 JSON 响应（通知则返回 202 空体）。
 *
 * @param endpoint 完整 URL，如 http://127.0.0.1:8080/mcp
 * @param bearerToken 可选的 Bearer token（服务端配了才需带）
 * @param timeoutMs 连接 + 读取超时（毫秒）
 * @param trustSelfSigned 是否信任自签名证书（仅对该服务器生效）
 */
class McpHttpSender(
    endpoint: String,
    private val bearerToken: String = "",
    private val timeoutMs: Int = 60_000,
    private val trustSelfSigned: Boolean = false,
) {

    /**
     * 规范化后的端点 URL。
     *
     * 为什么要做这一步：用户（和模型）填地址时常省略 scheme，写成
     * `192.168.1.10:3000/mcp` 或 `mcp.example.com/mcp`。`java.net.URL` 对这类
     * 输入会把它当成 `protocol:192.168.1.10` 解析，直接抛 MalformedURLException，
     * 表现为「连不上」却看不出原因。这里统一补齐：
     *  - 已带 http:// 或 https:// → 原样使用；
     *  - 其余情况（无 scheme / 只有 //）→ 补 https://。
     *
     * 为什么默认 https 而不是 http：远程 MCP 服务器基本都要求 https，
     * 而明文 http 仅用于本机 / 局域网调试——那类地址用户通常会显式写全。
     */
    private val url: URL = URL(normalizeEndpoint(endpoint))

    /** 发送一段 JSON-RPC 文本，返回响应文本；null 表示服务端无响应体（通知）。 */
    fun send(text: String): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (bearerToken.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer " + bearerToken)
            }
            // 信任自签名证书：仅当用户对该服务器显式勾选时启用。
            //
            // 为什么需要：自签名证书的根不在系统信任库里，Java 默认的证书链校验
            // 会抛 CertPathValidatorException: Trust anchor for certification path not found，
            // 表现为「连不上」。自托管 / 内网 / 本机自签服务器只能靠这个开关放行。
            //
            // 为什么按服务器而非全局：全局放行等于对任何站点都放弃校验（易被中间人），
            // 按服务器则只对用户明确信任的那一台放宽。
            if (trustSelfSigned && this is HttpsURLConnection) {
                sslSocketFactory = insecureSslContext().socketFactory
                hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
        }
        try {
            val bodyBytes = text.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bodyBytes.size)
            val out: OutputStream = conn.outputStream
            out.write(bodyBytes)
            out.flush()
            out.close()

            val code = conn.responseCode
            // 202（通知）/ 204：无响应体。
            if (code == 202 || code == 204) return null
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) return null
            val text2 = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) {
                throw McpClientException("HTTP " + code + ": " + text2.take(500))
            }
            return text2.ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }

    /** 转成 McpClient 需要的 suspend sender。 */
    fun asSender(): suspend (String) -> String? = { text -> send(text) }

    companion object {
        /**
         * 构造「信任所有证书」的 SSLContext（仅供 [trustSelfSigned] 使用）。
         *
         * 用一个空实现的 X509TrustManager 替换默认校验器——它不做任何链验证、
         * 也不校验证书有效期与主机名。安全性由「用户显式勾选」这一前置动作兜底。
         */
        private fun insecureSslContext(): SSLContext {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }
        }

        /** 端点 URL 规范化：补 scheme、去首尾空白。见 [url] 注释。 */
        fun normalizeEndpoint(raw: String): String {
            val t = raw.trim()
            if (t.isEmpty()) throw McpClientException("MCP 端点地址为空")
            val lower = t.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) return t
            // 形如 //host/path：补 https:
            if (t.startsWith("//")) return "https:" + t
            return "https://" + t
        }
    }
}
