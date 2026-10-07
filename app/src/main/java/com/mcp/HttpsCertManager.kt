package com.mcp

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Web 服务的 HTTPS 证书管理。
 *
 * 设计取舍：
 *  - **自签名**而非申请 CA 证书：本服务只在内网/本机使用，没有公网域名，
 *    无法走 Let's Encrypt 的 HTTP-01/DNS-01 验证。自签名是唯一可行的自动方案。
 *  - **运行时生成**而非预置：预置意味着所有安装共用同一私钥，一旦泄露影响所有
 *    用户；运行时生成每个设备一份，且无需把私钥写进 APK（可被反编译提取）。
 *  - **BouncyCastle**：JDK 没有公开的自签名证书生成 API，Android 内置的 BC 又是
 *    阉割版（不含 X509v3CertificateBuilder）。bcpkix 提供了完整的证书构造能力。
 *
 * 客户端信任：自签名证书默认不被信任，浏览器会警告、MCP 客户端会拒连。
 * 用户需要手动信任，或选择「继续访问」。这是自签名的固有代价，界面上有明确提示。
 */
object HttpsCertManager {

    private const val TAG = "HTTPS"
    private const val KS_FILE = "web_https_keystore.p12"
    private const val ALIAS = "mcp-web-server"
    private const val KEYSTORE_PASSWORD = "mcp-local-https"
    private const val VALIDITY_DAYS = 3650L   // 10 年：本地服务，避免频繁过期

    // 注意：这里**不注册** BouncyCastleProvider。
    //
    // Android 系统内置了一个同名的阉割版 BC（provider 名也是 "BC"），
    // Security.addProvider 会因重名被忽略，结果拿到的是系统那个残缺实现。
    // 它没有 SHA256withRSA 等常用签名算法，一旦用 setProvider("BC") 指定它，
    // 就会抛 NoSuchAlgorithmException: no such algorithm: SHA256WITHRSA for provider BC。
    //
    // 本类只用 bcpkix 的**证书结构构造能力**（ASN.1 编码，纯逻辑不依赖 provider），
    // 签名一律交给 Android 默认 provider（Conscrypt）完成——它完整支持 SHA256withRSA。

    /** 证书库文件路径（app 私有目录，其他应用不可读）。 */
    fun keystoreFile(ctx: Context): File = File(ctx.filesDir, KS_FILE)

    /** 证书是否已存在。 */
    fun exists(ctx: Context): Boolean = keystoreFile(ctx).let { it.exists() && it.length() > 0L }

    /**
     * 载入证书库；不存在则生成一份自签名证书。
     *
     * @return 可直接交给 [SSLContext] 的 KeyStore
     */
    @Throws(Exception::class)
    fun loadOrCreate(ctx: Context): KeyStore {
        val f = keystoreFile(ctx)
        if (f.exists() && f.length() > 0L) {
            return runCatching { load(f) }.getOrElse { e ->
                // 损坏/口令不符：重建而不是让服务起不来。旧文件先备份便于排查。
                Log.w(TAG, "证书库读取失败，将重新生成: ${e.message}")
                runCatching { f.renameTo(File(f.parentFile, f.name + ".bad")) }
                generate(ctx)
            }
        }
        return generate(ctx)
    }

    /** 强制重新生成自签名证书（换设备名/证书损坏时用）。 */
    @Throws(Exception::class)
    fun regenerate(ctx: Context): KeyStore {
        val f = keystoreFile(ctx)
        if (f.exists()) runCatching { f.delete() }
        return generate(ctx)
    }

    /**
     * 由证书库构建服务端 SSLSocketFactory。
     *
     * 用 TLS（即 TLSv1.2+）而非 SSLv3：后者已被证明不安全，现代客户端会拒绝。
     */
    @Throws(Exception::class)
    fun sslSocketFactory(ctx: Context): SSLServerSocketFactory {
        val ks = loadOrCreate(ctx)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray())
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(kmf.keyManagers, null, null)
        return sslCtx.serverSocketFactory
    }

    /**
     * 导出当前证书的 PEM 文本（供外部 MCP 客户端下载并导入信任）。
     *
     * 为什么需要：自签名证书的根不在任何系统信任库里，外部客户端（Claude Desktop、
     * Cursor 等）连接时会抛 Trust anchor for certification path not found。
     * 把证书导给用户手动导入，是自签名场景下唯一的标准做法。
     *
     * @return PEM 文本；证书不存在时返回 null
     */
    fun certPem(ctx: Context): String? {
        if (!exists(ctx)) return null
        return runCatching {
            val ks = load(keystoreFile(ctx))
            val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: return null
            val b64 = android.util.Base64.encodeToString(cert.encoded, android.util.Base64.NO_WRAP)
            buildString {
                append("-----BEGIN CERTIFICATE-----\n")
                // 每 64 字符一行，符合 PEM 规范（有些客户端解析器要求）
                b64.chunked(64).forEach { append(it).append("\n") }
                append("-----END CERTIFICATE-----\n")
            }
        }.getOrNull()
    }

    /** 当前证书的摘要信息（设置页展示用）；未生成时返回 null。 */
    fun describe(ctx: Context): String? {
        if (!exists(ctx)) return null
        return runCatching {
            val ks = load(keystoreFile(ctx))
            val cert = ks.getCertificate(ALIAS) as? X509Certificate ?: return null
            val cn = cert.subjectX500Principal.name
            val exp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cert.notAfter)
            "自签名 · $cn · 有效期至 $exp"
        }.getOrNull()
    }

    // ─────────────────────── 内部实现 ───────────────────────

    @Throws(Exception::class)
    private fun load(f: File): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(f).use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }
        return ks
    }

    @Throws(Exception::class)
    private fun generate(ctx: Context): KeyStore {
        // 1. RSA 2048：兼顾安全与生成速度（4096 在手机上要数秒，体验差）
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        // 2. 主题：用设备型号做 CN，便于在证书列表里辨认是哪台设备
        val model = android.os.Build.MODEL?.replace(",", "") ?: "Android"
        val subject = X500Name("CN=$model MCP Web, O=AgentToolbox, C=CN")
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)          // 回拨 1 天，避开设备时钟偏差
        val notAfter = Date(now + VALIDITY_DAYS * 24L * 3600 * 1000)
        val serial = BigInteger.valueOf(now)

        // 3. 构造 v3 证书：CA=false + SAN（现代客户端不再只看 CN，必须带 SAN）
        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(
                arrayOf(
                    GeneralName(GeneralName.dNSName, "localhost"),
                    GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                    GeneralName(GeneralName.iPAddress, "0.0.0.0"),
                ) + localIpGeneralNames()
            )
        )

        // 不 setProvider：交给默认 provider 签名（Android 上是 Conscrypt）。
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        // 4. 装进 PKCS12 并落盘
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, keyPair.private, KEYSTORE_PASSWORD.toCharArray(), arrayOf(cert))
        FileOutputStream(keystoreFile(ctx)).use { ks.store(it, KEYSTORE_PASSWORD.toCharArray()) }
        Log.i(TAG, "自签名证书已生成: ${cert.subjectX500Principal.name}")
        return ks
    }

    /** 把本机各网卡 IP 加进 SAN，便于局域网用 IP 直连。 */
    private fun localIpGeneralNames(): Array<GeneralName> {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<InetAddress>()
                .filter { !it.isLoopbackAddress && it.hostAddress?.contains(":") != true }
                .map { GeneralName(GeneralName.iPAddress, it.hostAddress) }
                .distinctBy { it.name.toString() }
                .toTypedArray()
        }.getOrDefault(emptyArray())
    }
}
