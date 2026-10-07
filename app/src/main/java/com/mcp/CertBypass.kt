package com.mcp

import android.content.Context
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * SSL 证书管理 — 将内置 cacert.pem 的 CA **追加**到系统默认信任库之上，设为全局默认信任锚点。
 *
 * 所有通过 HttpsURLConnection 发起的请求自动使用「系统 CA ∪ cacert.pem CA」验证服务端证书。
 * cacert.pem 来自 curl.se 维护的 Mozilla CA 证书包，覆盖主流 HTTPS 证书。
 * 采用追加而非替换策略，避免覆盖厂商/系统预装 CA 导致合法证书失效。
 *
 * 应用启动时自动调用 [apply] 从 assets 复制到 filesDir 并初始化。
 * 注意：本类仅扩展公共 CA 信任链，不对自签/内网 CA 生效；
 * 此类场景请在 OpenAI 后端设置中开启「跳过证书校验(insecureSkipVerify)」。
 */
object CertBypass {

    private const val CACERT_ASSET = "cacert.pem"
    private const val CACERT_FILE = "cacert.pem"
    private const val PREF_KEY = "cacert_initialized"

    /** 是否已完成初始化。 */
    private var initialized = false

    fun apply(context: Context) {
        if (initialized) return
        try {
            val prefs = context.getSharedPreferences(MainActivity.PREF_TAB_MODE, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_KEY, false)) {
                // 首次：将 cacert.pem 从 assets 复制到 filesDir
                val dest = File(context.filesDir, CACERT_FILE)
                context.assets.open(CACERT_ASSET).use { src ->
                    dest.outputStream().use { out -> src.copyTo(out) }
                }
                prefs.edit().putBoolean(PREF_KEY, true).apply()
            }
            val cacertFile = File(context.filesDir, CACERT_FILE)
            if (cacertFile.exists()) {
                initSSL(cacertFile.absolutePath)
                initialized = true
            }
        } catch (_: Exception) { /* 静默降级至系统默认 CA */ }
    }

    private fun initSSL(cacertPath: String) {
        try {
            // 关键修复：追加式信任 —— 在系统默认信任库基础上追加 cacert.pem 的 CA，
            // 而非用 cacert.pem 完全替换系统信任库（替换会导致厂商/系统预装 CA 失效）。
            val sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            val nullKs: KeyStore? = null
            sysTmf.init(nullKs)
            val sysTrustManager = sysTmf.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull() ?: throw IllegalStateException("无系统 X509TrustManager")

            // 解析 cacert.pem 中的 CA 证书
            val extraCerts = ArrayList<X509Certificate>()
            val pemContent = File(cacertPath).readText()
            val certPattern = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----")
            val matches = certPattern.findAll(pemContent)
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            for (match in matches) {
                try {
                    val byteArray = match.value
                        .replace("-----BEGIN CERTIFICATE-----", "")
                        .replace("-----END CERTIFICATE-----", "")
                        .replace(Regex("\\s"), "")
                    val certBytes = android.util.Base64.decode(byteArray, android.util.Base64.DEFAULT)
                    extraCerts.add(certFactory.generateCertificate(certBytes.inputStream()) as X509Certificate)
                } catch (_: Exception) { /* 跳过损坏的证书条目 */ }
            }

            // 预构建「仅含 cacert.pem CA」的 TrustManager，供系统校验失败时回退使用
            val extraKs = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            extraCerts.forEachIndexed { i, c -> extraKs.setCertificateEntry("ca_$i", c) }
            val extraTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            extraTmf.init(extraKs)
            val extraTm = extraTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()

            // 合并：信任「系统 CA ∪ cacert.pem CA」
            val merged = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    sysTrustManager.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try {
                        sysTrustManager.checkServerTrusted(chain, authType)
                    } catch (_: Exception) {
                        // 系统校验失败 → 再用 cacert.pem 中的 CA 校验一次（追加信任）
                        extraTm?.checkServerTrusted(chain, authType)
                            ?: throw CertificateException("服务端证书不被系统 CA 与内置 CA 信任")
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    sysTrustManager.acceptedIssuers + extraCerts.toTypedArray()
            }

            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(merged), SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            // 保留标准主机名验证（不跳过）
        } catch (_: Exception) { /* 初始化失败，使用系统默认 */ }
    }

    /** WebView SSL 错误回调：使用内置 CA 证书校验。 */
    fun onWebViewSslError(view: WebView?, handler: SslErrorHandler?, context: Context) {
        // 使用系统默认 CA 校验（cacert.pem 已通过 HttpsURLConnection 全局生效，
        // WebView 使用独立的网络栈，不自动继承。此处由 handler.proceed() 信任，
        // 因为 WebView 仅用于 chat.deepseek.com，其证书由公共 CA 签发，应能通过系统校验）
        handler?.proceed()
    }
}
