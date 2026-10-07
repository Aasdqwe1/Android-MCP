package com.mcp.wechat

import android.content.Context
import android.util.Base64
import android.util.Log
import com.mcp.BashTaskManager
import com.mcp.LogStore
import com.mcp.executeBash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * OpenClaw 微信渠道对接（双路径）。
 *
 * 基于：
 *  - 官方文档 https://docs.openclaw.ai/zh-CN/channels/wechat
 *  - 腾讯开源仓库 https://github.com/Tencent/openclaw-weixin
 *
 * 两种工作模式：
 *
 *  【路径 A：官方插件（Debian PRoot + Node.js + OpenClaw CLI）】
 *    在 Debian PRoot 里装 Node.js、装 openclaw CLI、通过
 *    `openclaw plugins install @tencent-weixin/openclaw-weixin` 装官方插件，
 *    跑 Gateway。走 100% 官方推荐、由腾讯微信团队维护的栈。
 *
 *  【路径 B：原生 iLink HTTP JSON（本文件直连）】
 *    直接在 Android App 原生 Kotlin 层实现腾讯 iLink 协议：
 *    二维码登录凭证保存 → getUpdates 长轮询收消息 → sendMessage 回复
 *    → AES-128-ECB 加密媒体 + CDN 上传/下载。
 *    依赖少、省电、无 Node.js 进程常驻。
 *
 * 两条路径账号（token / uin / sync_buf）互通，可随时切换。
 * 凭证统一存在 filesDir/openclaw-weixin/accounts.json。
 *
 *  iLink 后端 BASE_URL 来自官方仓库 accounts.ts: DEFAULT_BASE_URL
 */
object OpenClawWeChat {

    private const val TAG = "Weixin"
    const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
    const val CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"
    private const val AUTH_TYPE = "ilink_bot_token"

    /**
     * iLink 协议固定 header（对齐官方 api.ts buildCommonHeaders）。
     * iLink-App-Id 来自 package.json 顶层 ilink_appid 字段（值为 "bot"）。
     * iLink-App-ClientVersion = (major<<16)|(minor<<8)|patch，2.4.6 -> 132102。
     */
    private const val ILINK_APP_ID = "bot"
    private const val ILINK_APP_CLIENT_VERSION = 132102  // 2.4.6
    private const val CHANNEL_VERSION = "2.4.6"
    private const val DEFAULT_BOT_TYPE = "3"  // iLink bot_type=3 表示微信渠道

    /** UA 风格的 bot_agent；仅用于可观测性（日志归因、监控聚合）。 */
    var botAgent: String = "AgentToolbox/1.0 (platform=android;env=app)"

    // ═════════════════════════════════════════════════════════════════
    //  持久化：账号、token、get_updates_buf
    // ═════════════════════════════════════════════════════════════════

    @Serializable
    data class WeixinAccount(
        val accountId: String,
        val token: String,
        val uin: Long,
        val botAgentOverride: String? = null,
        val getUpdatesBuf: String? = null,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    @Serializable
    private data class AccountIndex(val accounts: List<WeixinAccount> = emptyList())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun stateDir(context: Context): File =
        File(context.filesDir, "openclaw-weixin").apply { mkdirs() }

    private fun indexPath(context: Context): File =
        File(stateDir(context), "accounts.json")

    fun listAccounts(context: Context): List<WeixinAccount> {
        val p = indexPath(context)
        if (!p.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<AccountIndex>(p.readText()).accounts
        }.getOrElse {
            LogStore.w(TAG, "读取 accounts.json 失败: ${it.message}")
            emptyList()
        }
    }

    fun saveAccount(context: Context, acc: WeixinAccount) {
        val list = listAccounts(context).filter { it.accountId != acc.accountId } + acc
        indexPath(context).writeText(json.encodeToString(AccountIndex(list)))
    }

    fun removeAccount(context: Context, accountId: String) {
        val list = listAccounts(context).filter { it.accountId != accountId }
        indexPath(context).writeText(json.encodeToString(AccountIndex(list)))
    }

    /** 清空所有已登录账号（文件直接删除）。 */
    fun clearAccounts(context: Context) {
        indexPath(context).delete()
        LogStore.i(TAG, "已清空全部微信账号记录")
    }

    fun defaultAccount(context: Context): WeixinAccount? = listAccounts(context).firstOrNull()

    // ═════════════════════════════════════════════════════════════════
    //  iLink 协议类型（镜像官方 types.ts）
    // ═════════════════════════════════════════════════════════════════

    @Serializable
    data class CDNMedia(
        val encrypt_query_param: String? = null,
        val aes_key: String? = null,
        val encrypt_type: Int? = null,
        val full_url: String? = null
    )

    @Serializable
    data class MessageItem(
        val type: Int? = null,
        val create_time_ms: Long? = null,
        val update_time_ms: Long? = null,
        val is_completed: Boolean? = null,
        val msg_id: String? = null,
        val text_item: TextItem? = null,
        val image_item: ImageItem? = null,
        val voice_item: VoiceItem? = null,
        val file_item: FileItem? = null,
        val video_item: VideoItem? = null
    ) {
        companion object {
            const val TYPE_TEXT = 1
            const val TYPE_IMAGE = 2
            const val TYPE_VOICE = 3
            const val TYPE_FILE = 4
            const val TYPE_VIDEO = 5
        }
    }

    @Serializable
    data class TextItem(val text: String? = null)
    @Serializable
    data class ImageItem(
        val media: CDNMedia? = null,
        val thumb_media: CDNMedia? = null,
        val aeskey: String? = null
    )
    @Serializable
    data class VoiceItem(
        val media: CDNMedia? = null,
        val encode_type: Int? = null,
        val sample_rate: Int? = null,
        val playtime: Int? = null,
        val text: String? = null
    )
    @Serializable
    data class FileItem(
        val media: CDNMedia? = null,
        val file_name: String? = null,
        val md5: String? = null,
        val len: String? = null
    )
    @Serializable
    data class VideoItem(
        val media: CDNMedia? = null,
        val thumb_media: CDNMedia? = null,
        val video_size: Int? = null,
        val play_length: Int? = null
    )

    @Serializable
    data class WeixinMessage(
        val seq: Long? = null,
        val message_id: Long? = null,
        val from_user_id: String? = null,
        val to_user_id: String? = null,
        val create_time_ms: Long? = null,
        val session_id: String? = null,
        val group_id: String? = null,
        val message_type: Int? = null,     // 1=USER, 2=BOT
        val message_state: Int? = null,    // 0=NEW,1=GENERATING,2=FINISH
        val item_list: List<MessageItem>? = null,
        val context_token: String? = null,
        val run_id: String? = null
    ) {
        companion object {
            const val MSG_TYPE_USER = 1
            const val MSG_TYPE_BOT = 2
            const val STATE_NEW = 0
            const val STATE_GENERATING = 1
            const val STATE_FINISH = 2
        }

        fun plainText(): String? = item_list
            ?.mapNotNull { it.text_item?.text }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
    }

    @Serializable
    data class GetUpdatesResp(
        val ret: Int? = null,
        val errcode: Int? = null,
        val errmsg: String? = null,
        val msgs: List<WeixinMessage>? = null,
        val get_updates_buf: String? = null,
        val longpolling_timeout_ms: Int? = null
    ) {
        // 服务端正常情况下 ret=0；但有些响应体不返回 ret 字段（视作无错误），
        // 所以 ret 为 null 时也算 OK，仅在 ret 显式非 0 或 errcode 非 0 时判失败。
        val isOk: Boolean get() = (ret == null || ret == 0) && (errcode == null || errcode == 0)
    }

    @Serializable
    data class SendMessageResp(
        val ret: Int? = null,
        val errmsg: String? = null
    ) {
        val isOk: Boolean get() = ret == null || ret == 0
    }

    @Serializable
    data class GetConfigResp(
        val ret: Int? = null,
        val errmsg: String? = null,
        val typing_ticket: String? = null
    )

    @Serializable
    data class SendTypingResp(
        val ret: Int? = null,
        val errmsg: String? = null
    ) {
        val isOk: Boolean get() = ret == null || ret == 0
    }

    // ═════════════════════════════════════════════════════════════════
    //  基础 HTTP 客户端（POST JSON 到 iLink CGI）
    // ═════════════════════════════════════════════════════════════════

    private fun randomUin32(): Long = SecureRandom().nextLong().and(0xFFFFFFFFL)

    private fun uinToHeader(uin: Long): String =
        Base64.encodeToString(uin.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

    /**
     * 通用 iLink CGI 调用。path 如 "getupdates" / "sendmessage" / "get_bot_qrcode"
     * （内部自动拼接为 https://ilinkai.weixin.qq.com/ilink/bot/<path>）。
     *
     * - POST：总是带 AuthorizationType + X-WECHAT-UIN；account 非空时附 Bearer token
     * - GET：只带 iLink-App-Id / iLink-App-ClientVersion 等通用头（对齐 api.ts apiGetFetch）
     *
     * @param query 用于 GET 时的 query string（如 "qrcode=xxx"），POST 时忽略
     */
    private suspend fun callIlink(
        context: Context,
        account: WeixinAccount?,
        path: String,
        body: JsonObject,
        timeoutMs: Int = 35_000,
        method: String = "POST",
        query: String? = null
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val fullUrl = if (query != null) {
            "$DEFAULT_BASE_URL/ilink/bot/$path?$query"
        } else {
            "$DEFAULT_BASE_URL/ilink/bot/$path"
        }
        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = timeoutMs
            // 通用 header（GET/POST 都带）
            setRequestProperty("iLink-App-Id", ILINK_APP_ID)
            setRequestProperty("iLink-App-ClientVersion", ILINK_APP_CLIENT_VERSION.toString())
        }
        if (method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("AuthorizationType", AUTH_TYPE)
            conn.setRequestProperty("X-WECHAT-UIN", uinToHeader(account?.uin ?: randomUin32()))
            if (account != null) {
                conn.setRequestProperty("Authorization", "Bearer ${account.token}")
            }
        }
        LogStore.d(TAG, "-> $method $path ${if (method == "POST") json.encodeToString(body).take(180) else "(GET q=${query?.take(60)})"}")
        runCatching {
            if (method == "POST") {
                val bodyStr = json.encodeToString(body)
                conn.outputStream.use { it.write(bodyStr.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            LogStore.d(TAG, "<- $path code=$code len=${text.length}")
            code to text
        }.getOrElse {
            LogStore.w(TAG, "callIlink $path 异常: ${it.message}")
            0 to """{"error":"${it.message?.replace("\"", "'")}"}"""
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  二维码登录
    //
    //  官方插件通过 `openclaw channels login --channel openclaw-weixin` 调
    //  get_bot_qrcode -> 轮询 get_qrcode_status -> 拿到 bot_token。
    //  对应源码：src/auth/login-qr.ts
    //  这里在原生 Kotlin 里复刻流程，避免依赖 Node.js。
    // ═════════════════════════════════════════════════════════════════

    /** GET /ilink/bot/get_bot_qrcode?bot_type=3 响应体 */
    @Serializable
    data class QrCodeResponse(
        val qrcode: String? = null,             // 后续轮询用的 qrcode 标识
        val qrcode_img_content: String? = null  // 可扫码的 URL（qrcode:// 或 https://）
    )

    /** GET /ilink/bot/get_qrcode_status?qrcode=<qrcode> 响应体（status 是字符串枚举） */
    @Serializable
    data class QrLoginStatus(
        val status: String? = null,   // wait / scaned / confirmed / expired / scaned_but_redirect / need_verifycode / verify_code_blocked / binded_redirect
        val bot_token: String? = null,
        val ilink_bot_id: String? = null,
        val ilink_user_id: String? = null,
        val baseurl: String? = null,         // 服务端可能返回新 baseurl，后续轮询切到它
        val redirect_host: String? = null    // scaned_but_redirect 时的新 host
    )

    /**
     * 开启二维码登录。返回 (qrcodeUrl, qrcode)：
     *  - qrcodeUrl：扫码用 URL（CLI 里渲染成二维码图片）
     *  - qrcode：后续 loginQrPoll 用的标识符
     *
     * 对应官方 login-qr.ts fetchQRCode()。
     */
    suspend fun loginQrStart(context: Context): Pair<String, String> {
        // body 仅含 local_token_list（已绑定的 bot token 列表，避免重复登录同号）
        val req = buildJsonObject {
            put("local_token_list", kotlinx.serialization.json.buildJsonArray {})
        }
        val (_, resp) = callIlink(
            context, null,
            path = "get_bot_qrcode",
            body = req,
            timeoutMs = 30_000,
            method = "POST",
            query = "bot_type=${java.net.URLEncoder.encode(DEFAULT_BOT_TYPE, "UTF-8")}"
        )
        val parsed = runCatching { json.decodeFromString<QrCodeResponse>(resp) }.getOrNull()
            ?: throw IllegalStateException("二维码接口响应解析失败: ${resp.take(200)}")
        if (parsed.qrcode.isNullOrEmpty() || parsed.qrcode_img_content.isNullOrEmpty()) {
            throw IllegalStateException("二维码启动失败: resp=${resp.take(200)}")
        }
        return parsed.qrcode_img_content to parsed.qrcode
    }

    /**
     * 轮询二维码状态。间隔 2s 调用一次即可。
     * GET /ilink/bot/get_qrcode_status?qrcode=<qrcode>
     */
    suspend fun loginQrPoll(context: Context, qrcode: String, baseUrl: String? = null): QrLoginStatus {
        val q = "qrcode=${java.net.URLEncoder.encode(qrcode, "UTF-8")}"
        // baseUrl 仅用于 scaned_but_redirect 时切换；当前实现仍走 DEFAULT_BASE_URL
        val (_, resp) = callIlink(
            context, null,
            path = "get_qrcode_status",
            body = buildJsonObject {},  // GET 时 body 不写出
            timeoutMs = 35_000,
            method = "GET",
            query = q
        )
        return runCatching { json.decodeFromString<QrLoginStatus>(resp) }.getOrElse {
            QrLoginStatus(status = "wait")
        }
    }

    /** 登录结果（含官方插件路径需要的原始字段）。 */
    data class LoginOutcome(
        val account: WeixinAccount?,
        val rawBotId: String?,
        val baseUrl: String?,
        val userId: String?,
        val alreadyBound: Boolean = false
    )

    /** 轮询直到确认或过期（最多 180 秒）。成功自动 saveAccount 并返回。 */
    suspend fun loginQrAwait(
        context: Context,
        qrcode: String,
        maxSeconds: Int = 180
    ): WeixinAccount? = loginQrAwaitDetailed(context, qrcode, maxSeconds).account

    /**
     * 同 [loginQrAwait]，但额外返回官方插件路径所需的原始字段
     * （ilink_bot_id / baseurl / ilink_user_id）与 binded_redirect 标记。
     */
    suspend fun loginQrAwaitDetailed(
        context: Context,
        qrcode: String,
        maxSeconds: Int = 180
    ): LoginOutcome {
        val deadline = System.currentTimeMillis() + maxSeconds * 1000L
        var currentHost: String? = null  // 用于 scaned_but_redirect 切换
        while (System.currentTimeMillis() < deadline) {
            val s = loginQrPoll(context, qrcode, currentHost)
            when (s.status) {
                "confirmed" -> {
                    val token = s.bot_token ?: return LoginOutcome(null, null, null, null)
                    val id = s.ilink_bot_id ?: "wx-${token.take(8)}"
                    val acc = WeixinAccount(
                        accountId = id,
                        token = token,
                        uin = randomUin32(),
                        botAgentOverride = null
                    )
                    saveAccount(context, acc)
                    LogStore.i(TAG, "微信登录成功: account=$id userId=${s.ilink_user_id}")
                    return LoginOutcome(acc, id, s.baseurl, s.ilink_user_id)
                }
                "binded_redirect" -> {
                    // 该微信号已绑定到当前 OpenClaw 实例，无需重新发凭证
                    LogStore.i(TAG, "微信已绑定（binded_redirect），无需重新登录")
                    return LoginOutcome(null, null, null, null, alreadyBound = true)
                }
                "expired" -> return LoginOutcome(null, null, null, null)
                "scaned_but_redirect" -> {
                    // 切换到 redirect_host 继续轮询
                    currentHost = s.redirect_host
                    LogStore.i(TAG, "二维码扫描后服务端重定向到 $currentHost")
                }
                "need_verifycode", "verify_code_blocked" -> {
                    LogStore.w(TAG, "服务端要求验证码（$s），原生直连暂不支持，请改用 Debian PRoot 官方插件路径")
                    return LoginOutcome(null, null, null, null)
                }
                // wait / scaned 继续轮询
            }
            delay(2000)
        }
        return LoginOutcome(null, null, null, null)
    }

    // ═════════════════════════════════════════════════════════════════
    //  官方插件路径：登录成功后把凭证写进插件的账号目录
    //
    //  官方插件（accounts.ts 的 saveWeixinAccount / registerWeixinAccountId）把凭证
    //  存到 ~/.openclaw/openclaw-weixin/accounts/{normalizedId}.json，并把 normalizedId
    //  追加进 ~/.openclaw/openclaw-weixin/accounts.json 索引；openclaw gateway 启动时
    //  从这两个文件恢复账号。原生直连的 accounts.json（filesDir）与它互不相通。
    //  这里在 Kotlin 侧确定 normalizedId，交给 guest 内 node 原样落盘，规避引号转义。
    // ═════════════════════════════════════════════════════════════════

    /**
     * 将登录凭证写入官方插件账号目录。成功返回 normalizedId，失败返回 null。
     * @param rawAccountId 服务端返回的 ilink_bot_id（如 "hex@im.bot"）
     */
    fun savePluginAccount(
        context: Context,
        rawAccountId: String,
        token: String,
        baseUrl: String?,
        userId: String?
    ): String? {
        // 对齐 openclaw/plugin-sdk/account-id 的 normalizeAccountId：
        // "@im.bot" → "-im-bot"、".im.wechat" 同理（@ 与 . 都替换成 -）。
        val normalizedId = rawAccountId.replace("@", "-").replace(".", "-")
        val accountJson = buildJsonObject {
            put("token", token)
            put("savedAt", isoNow())
            baseUrl?.takeIf { it.isNotBlank() }?.let { put("baseUrl", it) }
            userId?.takeIf { it.isNotBlank() }?.let { put("userId", it) }
        }.toString()

        val idB64 = Base64.encodeToString(normalizedId.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(accountJson.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)

        val script = buildString {
            appendLine("export HOME=\"${'$'}{HOME:-/root}\"")
            appendLine("export WX_ACC_ID='$idB64'")
            appendLine("export WX_ACC_DATA='$dataB64'")
            appendLine("node <<'NODE_EOF'")
            appendLine("const fs = require('fs');")
            appendLine("const path = require('path');")
            appendLine("const home = process.env.HOME || '/root';")
            appendLine("const dir = path.join(home, '.openclaw', 'openclaw-weixin');")
            appendLine("const id = Buffer.from(process.env.WX_ACC_ID || '', 'base64').toString('utf8');")
            appendLine("const data = Buffer.from(process.env.WX_ACC_DATA || '', 'base64').toString('utf8');")
            appendLine("fs.mkdirSync(path.join(dir, 'accounts'), { recursive: true });")
            appendLine("fs.writeFileSync(path.join(dir, 'accounts', id + '.json'), data, { mode: 0o600 });")
            appendLine("const idxPath = path.join(dir, 'accounts.json');")
            appendLine("let arr = [];")
            appendLine("try { arr = JSON.parse(fs.readFileSync(idxPath, 'utf8')); } catch (e) {}")
            appendLine("if (!Array.isArray(arr)) arr = [];")
            appendLine("if (arr.indexOf(id) < 0) { arr.push(id); fs.writeFileSync(idxPath, JSON.stringify(arr, null, 2)); }")
            appendLine("console.log('saved account ' + id);")
            appendLine("NODE_EOF")
        }

        val out = runCatching { executeBash(context, script) }.getOrNull()
        if (out == null || !out.contains("saved account")) {
            LogStore.w(TAG, "保存官方插件账号失败: ${out.orEmpty().take(200)}")
            return null
        }
        LogStore.i(TAG, "官方插件账号已写入 ~/.openclaw/openclaw-weixin/accounts/$normalizedId.json")
        return normalizedId
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    /**
     * 检测官方插件（Debian PRoot 路径）的登录状态，返回：
     *  - "logged_in"：openclaw CLI + openclaw-weixin 插件已装，且 accounts.json 有账号
     *  - "not_logged_in"：已装但未登录（accounts.json 为空或不存在）
     *  - "not_installed"：openclaw CLI 或插件未安装
     *  - "unknown"：检测失败
     *
     * 用途：设置页「启动 Gateway」在检测到「已安装但未登录」时，自动弹出扫码登录二维码，
     * 不再像以前那样「已经安装了就不打印二维码和链接」。
     */
    fun pluginLoginState(context: Context): String {
        val script = buildString {
            appendLine("export HOME=\"${'$'}{HOME:-/root}\"")
            appendLine("if ! command -v openclaw >/dev/null 2>&1; then echo not_installed; exit 0; fi")
            appendLine("if ! openclaw plugins list 2>/dev/null | grep -q 'openclaw-weixin'; then echo not_installed; exit 0; fi")
            appendLine("node <<'NODE_EOF'")
            appendLine("const fs = require('fs');")
            appendLine("const path = require('path');")
            appendLine("const home = process.env.HOME || '/root';")
            appendLine("const p = path.join(home, '.openclaw', 'openclaw-weixin', 'accounts.json');")
            appendLine("try { const a = JSON.parse(fs.readFileSync(p, 'utf8')); console.log(Array.isArray(a) && a.length > 0 ? 'logged_in' : 'not_logged_in'); } catch (e) { console.log('not_logged_in'); }")
            appendLine("NODE_EOF")
        }
        val out = runCatching { executeBash(context, script) }.getOrNull() ?: return "unknown"
        return when {
            out.contains("not_installed") -> "not_installed"
            out.contains("logged_in") -> "logged_in"
            out.contains("not_logged_in") -> "not_logged_in"
            else -> "unknown"
        }
    }

    // ═════════════════════════════════════════════════════════════════
    //  收/发消息核心 API
    // ═════════════════════════════════════════════════════════════════

    /**
     * 长轮询拉取新消息。传入 account，内部持久化 get_updates_buf。
     * 无消息时也会正常返回空 msgs（服务端按 longpolling_timeout_ms 断连）。
     */
    suspend fun getUpdates(
        context: Context,
        account: WeixinAccount
    ): GetUpdatesResp {
        val req = buildJsonObject {
            put("base_info", buildBaseInfo(account))
            put("get_updates_buf", account.getUpdatesBuf ?: "")
        }
        val timeout = 38_000 // 比服务端默认 35s 略宽
        val (_, body) = callIlink(context, account, "getupdates", req, timeout)
        val resp = runCatching { json.decodeFromString<GetUpdatesResp>(body) }.getOrElse {
            GetUpdatesResp(ret = -1, errmsg = "解析失败: ${it.message}")
        }
        if (resp.get_updates_buf != null && resp.get_updates_buf != account.getUpdatesBuf) {
            saveAccount(context, account.copy(getUpdatesBuf = resp.get_updates_buf))
        }
        return resp
    }

    /**
     * 发送一条纯文本回复。必须带上入站消息里的 context_token + to_user_id（方向反转）。
     *
     * 对齐官方 src/messaging/send.ts buildTextMessageReq：
     *  - from_user_id 必须为空字符串（不是 bot 账号 id）
     *  - client_id 每次发送必须唯一，用作幂等键，防止服务端去重导致消息被静默丢弃
     */
    suspend fun sendText(
        context: Context,
        account: WeixinAccount,
        toUserId: String,
        text: String,
        contextToken: String?
    ): SendMessageResp {
        val msg = buildJsonObject {
            put("from_user_id", "")  // 官方约定：bot 发送时此字段固定为空串
            put("to_user_id", toUserId)
            put("client_id", "openclaw-weixin-${java.util.UUID.randomUUID()}")
            put("message_type", WeixinMessage.MSG_TYPE_BOT)    // 2 = BOT 发出
            put("message_state", WeixinMessage.STATE_FINISH)   // 2 = 完成态（流式除外）
            contextToken?.let { put("context_token", it) }
            putJsonArray("item_list") {
                add(buildJsonObject {
                    put("type", MessageItem.TYPE_TEXT)
                    putJsonObject("text_item") { put("text", text) }
                })
            }
        }
        val req = buildJsonObject {
            put("base_info", buildBaseInfo(account))
            put("msg", msg)
        }
        val (_, body) = callIlink(context, account, "sendmessage", req, 15_000)
        LogStore.d(TAG, "sendmessage 原始响应: $body")
        val resp = runCatching { json.decodeFromString<SendMessageResp>(body) }.getOrElse {
            SendMessageResp(ret = -1, errmsg = "解析失败: ${it.message}")
        }
        if (!resp.isOk) {
            LogStore.w(TAG, "sendmessage 业务失败 ret=${resp.ret} errmsg=${resp.errmsg}")
        }
        return resp
    }

    suspend fun getTypingTicket(
        context: Context,
        account: WeixinAccount,
        ilinkUserId: String,
        contextToken: String?
    ): String? {
        val req = buildJsonObject {
            put("base_info", buildBaseInfo(account))
            put("ilink_user_id", ilinkUserId)
            contextToken?.let { put("context_token", it) }
        }
        val (_, body) = callIlink(context, account, "getconfig", req, 10_000)
        return runCatching { json.decodeFromString<GetConfigResp>(body) }
            .getOrNull()
            ?.takeIf { it.ret == null || it.ret == 0 }
            ?.typing_ticket
    }

    suspend fun sendTyping(
        context: Context,
        account: WeixinAccount,
        ilinkUserId: String,
        typingTicket: String,
        typing: Boolean = true
    ): SendTypingResp {
        val req = buildJsonObject {
            put("base_info", buildBaseInfo(account))
            put("ilink_user_id", ilinkUserId)
            put("typing_ticket", typingTicket)
            put("status", if (typing) 1 else 2)
        }
        val (_, body) = callIlink(context, account, "sendtyping", req, 8_000)
        val resp = runCatching { json.decodeFromString<SendTypingResp>(body) }
            .getOrElse { SendTypingResp(ret = -1, errmsg = "解析失败: ${it.message}") }
        if (!resp.isOk) {
            LogStore.w(TAG, "sendtyping 业务失败 status=${if (typing) 1 else 2} ret=${resp.ret} errmsg=${resp.errmsg}")
        }
        return resp
    }

    // ═════════════════════════════════════════════════════════════════
    //  媒体：AES-128-ECB 加解密 + CDN 上传参数
    // ═════════════════════════════════════════════════════════════════

    private const val AES_ECB = "AES/ECB/PKCS5Padding"

    fun aesKeyRandom(): ByteArray {
        val buf = ByteArray(16)
        SecureRandom().nextBytes(buf)
        return buf
    }

    fun aesEcbEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_ECB)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(plain)
    }

    fun aesEcbDecrypt(key: ByteArray, cipher: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_ECB)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(cipher)
    }

    fun md5Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("MD5").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /**
     * 取上传预签名 URL。media_type: 1=图片 2=视频 3=文件 4=语音。
     * 返回一对：(主图 upload_param, 缩略图 upload_param?) 以及完整上传 URL
     * upload_full_url 优先存在时直接用，不用再拼 CDN_BASE_URL。
     */
    @Serializable
    data class GetUploadUrlResp(
        val ret: Int? = null,
        val errmsg: String? = null,
        val upload_param: String? = null,
        val thumb_upload_param: String? = null,
        val upload_full_url: String? = null
    )

    suspend fun getUploadUrl(
        context: Context,
        account: WeixinAccount,
        fileKey: String,
        mediaType: Int,
        toUserId: String,
        plainBytes: ByteArray,
        thumbBytes: ByteArray? = null,
        aesKey: ByteArray = aesKeyRandom()
    ): GetUploadUrlResp {
        val cipher = aesEcbEncrypt(aesKey, plainBytes)
        val req = buildJsonObject {
            put("base_info", buildBaseInfo(account))
            put("filekey", fileKey)
            put("media_type", mediaType)
            put("to_user_id", toUserId)
            put("rawsize", plainBytes.size)
            put("rawfilemd5", md5Hex(plainBytes))
            put("filesize", cipher.size)
            put("aeskey", Base64.encodeToString(aesKey, Base64.NO_WRAP))
            if (thumbBytes != null) {
                val tc = aesEcbEncrypt(aesKey, thumbBytes)
                put("thumb_rawsize", thumbBytes.size)
                put("thumb_rawfilemd5", md5Hex(thumbBytes))
                put("thumb_filesize", tc.size)
            }
        }
        val (_, body) = callIlink(context, account, "getuploadurl", req, 15_000)
        return runCatching { json.decodeFromString<GetUploadUrlResp>(body) }.getOrElse {
            GetUploadUrlResp(ret = -1, errmsg = "解析失败: ${it.message}")
        }
    }

    /** 按 upload_param 的要求，AES 加密后 PUT 到 CDN。官方插件使用上传加密参数里带的 full_url。 */
    suspend fun uploadToCdn(
        context: Context,
        uploadFullUrl: String,
        plainBytes: ByteArray,
        aesKey: ByteArray
    ): Boolean = withContext(Dispatchers.IO) {
        val cipher = aesEcbEncrypt(aesKey, plainBytes)
        runCatching {
            val conn = URL(uploadFullUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.outputStream.use { it.write(cipher) }
            val code = conn.responseCode
            LogStore.d(TAG, "uploadToCdn code=$code size=${cipher.size}")
            code in 200..299
        }.getOrDefault(false)
    }

    // ═════════════════════════════════════════════════════════════════
    //  路径 A（Debian PRoot + OpenClaw 官方插件）包装层
    //
    //  文档步骤：
    //   1. npx -y @tencent-weixin/openclaw-weixin-cli install
    //   2. openclaw config set plugins.entries.openclaw-weixin.enabled true
    //   3. openclaw channels login --channel openclaw-weixin
    //   4. openclaw gateway restart
    // ═════════════════════════════════════════════════════════════════

    /** Debian PRoot 下安装/升级 nodejs + openclaw CLI + openclaw-weixin 插件。 */
    fun prootInstallScript(forceLegacy: Boolean = false): String = buildString {
        appendLine("#!/usr/bin/bash")
        appendLine("set -euo pipefail")
        appendLine("export HOME=\"${'$'}{HOME:-/root}\"")
        appendLine("export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${'$'}PATH\"")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine("")
        appendLine("# ══ 幂等短路：已装好则秒返回，不重跑 apt/npm ══")
        appendLine("# openclaw CLI、node、openclaw-weixin 插件三样都就绪时直接跳过安装，")
        appendLine("# 避免「已经装好了」时还无谓地 apt update + npm install（耗时且易卡住）。")
        appendLine("if command -v openclaw >/dev/null 2>&1 && command -v node >/dev/null 2>&1; then")
        appendLine("  if openclaw plugins list 2>/dev/null | grep -q 'openclaw-weixin'; then")
        appendLine("    echo '[skip] openclaw + node + openclaw-weixin 插件已安装，快速短路（不重跑 apt/npm）。'")
        appendLine("    echo '安装完成。下一步：openclaw channels login --channel openclaw-weixin'")
        appendLine("    exit 0")
        appendLine("  fi")
        appendLine("fi")
        appendLine("")
        appendLine("# ── PRoot 加固 ──")
        appendLine("# dbus 等包 postinst 调 groupadd/useradd 时，shadow-utils 用 link()+st_nlink==2 做文件锁；")
        appendLine("# PRoot 把 link() 退化成立即拷贝，st_nlink 恒为 1，于是报 “lock file already used (nlink: 1)”。")
        appendLine("# 对策：提前把目标组/用户写进 /etc/group、/etc/passwd，让 postinst 走 getent「已存在」分支，跳过 groupadd/useradd。")
        appendLine("# 1) 清理历史失败残留的 PID 锁临时文件与 .lock 文件")
        appendLine("rm -f /etc/group.lock /etc/passwd.lock /etc/shadow.lock /etc/gshadow.lock")
        appendLine("rm -f /etc/group.[0-9]* /etc/passwd.[0-9]* /etc/shadow.[0-9]* /etc/gshadow.[0-9]*")
        appendLine("# 2) 幂等预置 messagebus 组/用户（UID/GID 对齐 Debian trixie 默认 101）")
        appendLine("grep -q '^messagebus:' /etc/group  || echo 'messagebus:x:101:' >> /etc/group")
        appendLine("grep -q '^messagebus:' /etc/passwd || echo 'messagebus:x:101:101::/nonexistent:/usr/sbin/nologin' >> /etc/passwd")
        appendLine("getent group messagebus >/dev/null && getent passwd messagebus >/dev/null || \\")
        appendLine("  echo 'WARN: messagebus 组/用户预置未生效，dbus 配置可能再次报锁错误' >&2")
        appendLine("# 3) 先修好残留的半配置包，后续 apt 才不会被旧错误卡住")
        appendLine("dpkg --configure -a || true")
        appendLine("")
        appendLine("# 1) 强制升级 Node.js 到 22.x（覆盖已有旧版本）")
        appendLine("echo '[1/4] 升级 nodejs 到 22.x...'")
        appendLine("apt-get update -y")
        appendLine("apt-get install -y --no-install-recommends ca-certificates curl")
        appendLine("curl -fsSL https://deb.nodesource.com/setup_22.x | bash -")
        appendLine("apt-get install -y --no-install-recommends nodejs")
        appendLine("node --version; npm --version")
        appendLine("# 编译 tree-sitter-bash 等原生依赖所需的工具链")
        appendLine("apt-get install -y --no-install-recommends build-essential python3")
        appendLine("")
        appendLine("# 2) 装 openclaw CLI（全局）")
        appendLine("if ! command -v openclaw >/dev/null 2>&1; then")
        appendLine("  echo '[2/4] 安装 OpenClaw CLI...'")
        appendLine("  SHARP_IGNORE_GLOBAL_LIBVIPS=1 npm install -g openclaw@latest --legacy-peer-deps --no-audit --no-fund")
        appendLine("fi")
        appendLine("openclaw --version 2>&1 || true")
        appendLine("")
        appendLine("# 3) 安装 openclaw-weixin 渠道插件")
        appendLine("echo '[3/4] 安装 openclaw-weixin 插件...'")
        if (forceLegacy) {
            appendLine("openclaw plugins install @tencent-weixin/openclaw-weixin@legacy --force || true")
        } else {
            appendLine("npx -y @tencent-weixin/openclaw-weixin-cli install || \\")
            appendLine("  (openclaw plugins install \"@tencent-weixin/openclaw-weixin\" --force")
            appendLine("   openclaw config set plugins.entries.openclaw-weixin.enabled true)")
        }
        appendLine("openclaw plugins list 2>&1 || true")
        appendLine("")
        appendLine("echo '[4/4] 多账号会话隔离（已存在配置不会覆盖）...'")
        appendLine("openclaw config get session.dmScope >/dev/null 2>&1 || \\")
        appendLine("  openclaw config set session.dmScope per-account-channel-peer")
        appendLine("echo '安装完成。下一步：openclaw channels login --channel openclaw-weixin'")
    }

    fun prootStatusCmd(): String =
        "openclaw plugins list; echo '---'; openclaw channels status --probe; echo '---'; openclaw --version 2>&1"

    fun prootLoginCmd(): String =
        "openclaw channels login --channel openclaw-weixin"

    fun prootPairingListCmd(): String =
        "openclaw pairing list openclaw-weixin"

    fun prootPairingApproveCmd(code: String): String =
        "openclaw pairing approve openclaw-weixin $code"

    // ────────────────────────────────────────────────────────────────
    //  Gateway 生命周期（常驻 PRoot 进程前台承载）
    //
    //  原实现用 `nohup openclaw gateway start & echo $!`，但在 executeBash 里
    //  bash 脚本一结束 PRoot 就退出，`--kill-on-exit` 会把 nohup 出去的子进程
    //  连带杀掉，导致 Gateway 实际上根本没活下来（微信自然收不到消息）。
    //  改为：gateway 作为「前台命令」在常驻 PRoot 进程里运行，进程不退则 Gateway 不退；
    //  任务由 BashTaskManager 管理（启动 / 停止 / 查状态 / 看日志）。
    // ────────────────────────────────────────────────────────────────

    /** 当前 Gateway 后台任务 id（进程内）。 */
    @Volatile
    private var gatewayTaskId: String? = null

    /**
     * 前台常驻运行 Gateway 的脚本（交给 startDetached 承载）。
     * 关键点：`openclaw gateway`（等价 `openclaw gateway run`）是阻塞的前台形式，
     * 不是 `openclaw gateway start`（那是交给 launchd/systemd 的服务化管理，PRoot 里用不了）。
     * 输出用 tee 同时落到 guest 侧 ~/.openclaw/gateway.log，供 openclaw_weixin_logs 读取。
     */
    fun prootGatewayForegroundCmd(): String = buildString {
        appendLine("#!/usr/bin/bash")
        appendLine("set -uo pipefail")
        appendLine("mkdir -p \"${'$'}HOME/.openclaw\"")
        // Gateway 长期常驻、每轮消息都打日志，原 `tee` 是纯追加：日志只增不减。
        // 启动前轮转一次：超过 8MB 就把旧内容截断保留最后 2000 行，避免 gateway.log 无限膨胀。
        appendLine("LOG=\"${'$'}HOME/.openclaw/gateway.log\"")
        appendLine("if [ -f \"${'$'}LOG\" ]; then")
        appendLine("  SZ=\$(wc -c < \"${'$'}LOG\" 2>/dev/null || echo 0)")
        appendLine("  if [ \"\${SZ:-0}\" -gt 8388608 ]; then")
        appendLine("    tail -n 2000 \"${'$'}LOG\" > \"${'$'}LOG.tmp\" 2>/dev/null && mv \"${'$'}LOG.tmp\" \"${'$'}LOG\" || true")
        appendLine("  fi")
        appendLine("fi")
        appendLine("# 清掉可能残留的旧 gateway，避免 18789 端口被占")
        appendLine("pkill -f 'openclaw gateway' 2>/dev/null || true")
        appendLine("sleep 1")
        appendLine("# 前台常驻：PRoot 进程活着，Gateway 就活着")
        appendLine("openclaw gateway --verbose 2>&1 | tee \"${'$'}HOME/.openclaw/gateway.log\"")
    }

    /** 启动 Gateway：先落盘必需配置，再以常驻 PRoot 进程前台运行。返回后台任务 id。 */
    fun startGateway(context: Context): String {
        stopGateway(context)
        // 前台 gateway 拒在 gateway.mode 未配置 / 插件未启用时启动，先落盘配置
        runCatching {
            executeBash(
                context,
                "openclaw config set plugins.entries.openclaw-weixin.enabled true\n" +
                    "openclaw config set gateway.mode local\n" +
                    "openclaw config set session.dmScope per-account-channel-peer"
            )
        }.onFailure { LogStore.w(TAG, "Gateway 预置配置失败: ${it.message}") }
        val task = BashTaskManager.startTask(context, prootGatewayForegroundCmd())
        gatewayTaskId = task.taskId
        LogStore.i(TAG, "Gateway 已后台启动 taskId=${task.taskId}")
        return task.taskId
    }

    /** 停止当前 Gateway（终止承载它的常驻 PRoot 进程）。 */
    fun stopGateway(context: Context): Boolean {
        val id = gatewayTaskId ?: return false
        gatewayTaskId = null
        val killed = BashTaskManager.killTask(id, context)
        LogStore.i(TAG, "Gateway 停止 taskId=$id killed=$killed")
        return killed
    }

    /** 重启 Gateway（配置变更后用）：停旧 → 重新落盘配置 → 起新。 */
    fun restartGateway(context: Context): String {
        stopGateway(context)
        return startGateway(context)
    }

    /** 临时禁用插件并停止 Gateway。 */
    fun disableGateway(context: Context): Boolean {
        runCatching {
            executeBash(context, "openclaw config set plugins.entries.openclaw-weixin.enabled false")
        }.onFailure { LogStore.w(TAG, "禁用插件失败: ${it.message}") }
        return stopGateway(context)
    }

    fun gatewayRunning(): Boolean =
        gatewayTaskId?.let { id ->
            BashTaskManager.getTask(id)?.status == BashTaskManager.TaskStatus.RUNNING
        } == true

    fun gatewayLog(lines: Int = 120): String? =
        gatewayTaskId?.let { BashTaskManager.getLogs(it, lines) }

    // ═════════════════════════════════════════════════════════════════
    //  诊断 / 整体状态快照（用于 openclaw_weixin_status 工具返回）
    // ═════════════════════════════════════════════════════════════════

    fun statusSnapshot(context: Context): JsonObject {
        val accs = listAccounts(context)
        val nativeAcc = defaultAccount(context)
        return buildJsonObject {
            put("mode_native", JsonObject(mapOf(
                "accounts_count" to JsonPrimitive(accs.size),
                "default_account" to JsonPrimitive(nativeAcc?.accountId ?: "(无)"),
                "has_token" to JsonPrimitive(!nativeAcc?.token.isNullOrBlank()),
                "base_url" to JsonPrimitive(DEFAULT_BASE_URL)
            )))
            // 路径 A 的状态不在内存里，由工具执行 `openclaw plugins list` 获得
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  内部帮助
    // ────────────────────────────────────────────────────────────────

    /** 构造 base_info（对齐官方 api.ts buildBaseInfo）。 */
    private fun buildBaseInfo(account: WeixinAccount? = null): JsonObject {
        val ua = account?.botAgentOverride?.takeIf { it.isNotBlank() } ?: botAgent
        // 按 UA 规范清洗：ASCII、<=256 bytes
        val cleaned = ua.filter { it.code in 0x20..0x7e }.take(256)
        return buildJsonObject {
            put("channel_version", CHANNEL_VERSION)
            put("bot_agent", cleaned)
        }
    }

    /** 小帮助：JSON dsl 缺失 putJsonArray 时退化为直接写 JsonArray 字符串。 */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArray(
        key: String,
        block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit
    ) {
        val b = kotlinx.serialization.json.buildJsonArray(block)
        put(key, b)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
        key: String,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ) {
        put(key, buildJsonObject(block))
    }

    // ═════════════════════════════════════════════════════════════════
    //  WeixinMessageLoop：后台长轮询消息 → 处理 → 回复
    // ═════════════════════════════════════════════════════════════════

    /**
     * 单例轮询器：对每个已保存账号跑一个协程，循环调 getUpdates。
     * 收到 message_type=USER 的消息，交给 onIncomingText 处理；
     * onIncomingText 返回回复文本后，通过 sendText 回推到微信。
     *
     * 使用方式：
     *   val loop = WeixinMessageLoop.singleton(context)
     *   loop.onIncomingText = { account, fromUserId, ctxToken, rawText ->
     *       // 返回回复字符串；如果返回 null 就不回复（上层自行处理）
     *       "收到: $rawText"
     *   }
     *   loop.startAll()
     *   loop.stopAll()
     */
    class WeixinMessageLoop private constructor(private val appContext: Context) {

        companion object {
            @Volatile private var instance: WeixinMessageLoop? = null
            private const val TYPING_KEEPALIVE_MS = 5_000L
            private const val TYPING_TICKET_TIMEOUT_MS = 10_000L
            private const val TYPING_TICKET_CACHE_TTL_MS = 24 * 60 * 60 * 1000L

            fun singleton(context: Context): WeixinMessageLoop {
                if (instance == null) synchronized(this) {
                    if (instance == null) instance = WeixinMessageLoop(context.applicationContext)
                }
                return instance!!
            }
        }

        /** 返回值：回复文本；返回 null 代表不自动回复（上层自行处理）。 */
        var onIncomingText: suspend (
            account: WeixinAccount,
            fromUserId: String,
            contextToken: String?,
            rawText: String,
            message: WeixinMessage
        ) -> String? = { _, _, _, rawText, _ ->
            // 默认实现：简单回显前缀，避免空 bridge
            "收到: $rawText"
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("WeixinLoop"))
        private val jobs = mutableMapOf<String, Job>()   // key=account.accountId
        private val runningAccountIds = mutableSetOf<String>()
        private val typingTicketCache = mutableMapOf<String, TypingTicketCacheEntry>()

        private data class TypingTicketCacheEntry(
            val ticket: String,
            val expiresAtMs: Long
        )

        private data class TypingState(
            val ticket: String,
            val keepAliveJob: Job
        )

        @Synchronized fun isRunning(accountId: String): Boolean = runningAccountIds.contains(accountId)

        @Synchronized fun runningCount(): Int = runningAccountIds.size

        @Synchronized fun statusMap(): Map<String, Boolean> = runningAccountIds.associateWith { true }

        /** 加载所有已保存账号，并为未启动者启动轮询。 */
        @Synchronized fun startAll() {
            val accounts = try { listAccounts(appContext) } catch (t: Throwable) { emptyList() }
            accounts.forEach { a ->
                if (jobs[a.accountId]?.isActive == true) return@forEach
                runningAccountIds.add(a.accountId)
                val job = scope.launch { runOne(a) }
                jobs[a.accountId] = job
                LogStore.i(TAG, "启动微信轮询 account=${a.accountId}")
            }
        }

        /** 启动单个账号（登录成功后立即调这个，无需重启 App）。 */
        @Synchronized fun start(account: WeixinAccount) {
            if (jobs[account.accountId]?.isActive == true) return
            runningAccountIds.add(account.accountId)
            jobs[account.accountId] = scope.launch { runOne(account) }
            LogStore.i(TAG, "启动微信轮询 account=${account.accountId}")
        }

        @Synchronized fun stop(accountId: String) {
            jobs.remove(accountId)?.cancel()
            runningAccountIds.remove(accountId)
            LogStore.i(TAG, "停止微信轮询 account=$accountId")
        }

        @Synchronized fun stopAll() {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            runningAccountIds.clear()
            LogStore.i(TAG, "停止全部微信轮询")
        }

        private suspend fun cachedTypingTicket(
            account: WeixinAccount,
            userId: String,
            contextToken: String?
        ): String? {
            val key = "${account.accountId}\u0000$userId"
            val now = System.currentTimeMillis()
            synchronized(typingTicketCache) {
                typingTicketCache[key]
                    ?.takeIf { it.expiresAtMs > now }
                    ?.ticket
            }?.let { return it }

            val ticket = withTimeoutOrNull(TYPING_TICKET_TIMEOUT_MS) {
                runCatching {
                    getTypingTicket(appContext, account, userId, contextToken)
                }.getOrNull()
            }?.takeIf { it.isNotBlank() }

            if (ticket != null) {
                synchronized(typingTicketCache) {
                    typingTicketCache[key] = TypingTicketCacheEntry(
                        ticket = ticket,
                        expiresAtMs = now + TYPING_TICKET_CACHE_TTL_MS
                    )
                }
            }
            return ticket
        }

        private suspend fun beginTyping(
            account: WeixinAccount,
            userId: String,
            contextToken: String?
        ): TypingState? {
            val ticket = cachedTypingTicket(account, userId, contextToken)
                ?: return null
            val started = runCatching {
                sendTyping(appContext, account, userId, ticket, typing = true)
            }.getOrElse {
                LogStore.w(TAG, "[${account.accountId}] sendtyping start 异常: ${it.message}")
                return null
            }
            if (!started.isOk) return null

            val keepAliveJob = scope.launch {
                while (isActive) {
                    delay(TYPING_KEEPALIVE_MS)
                    runCatching {
                        sendTyping(appContext, account, userId, ticket, typing = true)
                    }.onFailure {
                        LogStore.d(TAG, "[${account.accountId}] sendtyping keepalive 异常: ${it.message}")
                    }
                }
            }
            return TypingState(ticket, keepAliveJob)
        }

        private suspend fun endTyping(
            account: WeixinAccount,
            userId: String,
            state: TypingState?
        ) {
            if (state == null) return
            state.keepAliveJob.cancel()
            runCatching {
                sendTyping(appContext, account, userId, state.ticket, typing = false)
            }.onFailure {
                LogStore.d(TAG, "[${account.accountId}] sendtyping stop 异常: ${it.message}")
            }
        }

        /** 每个账号独立的轮询循环：getUpdates（35s long-poll）→ 逐条处理。 */
        private suspend fun runOne(initialAccount: WeixinAccount) {
            var account = initialAccount  // 可变：每次拿到新 get_updates_buf 后更新本地副本
            var retryBackoff = 1000L
            while (true) {
                coroutineContext.ensureActive()
                val resp = try {
                    getUpdates(appContext, account)
                } catch (ce: CancellationException) {
                    return
                } catch (t: Throwable) {
                    LogStore.w(TAG, "[${account.accountId}] getUpdates 异常: ${t.message}")
                    delay(retryBackoff.coerceAtMost(15_000L))
                    retryBackoff = (retryBackoff * 2).coerceAtMost(15_000L)
                    continue
                }
                retryBackoff = 1000L
                // -14 = session 超时（token 失效），需要用户重新扫码
                if (resp.errcode == -14) {
                    LogStore.e(TAG, "[${account.accountId}] 微信 session 超时 errcode=-14，请重新扫码登录")
                    return  // 结束轮询协程，等下一轮 startAll
                }
                if (!resp.isOk) {
                    LogStore.w(TAG, "[${account.accountId}] getUpdates ret=${resp.ret} errcode=${resp.errcode} err=${resp.errmsg}")
                    delay(1000)
                    continue
                }
                // 关键：用本次响应返回的新 buf 更新本地 account，
                // 否则下一轮还会带旧 buf 请求，服务端会反复返回同一条已消费的消息。
                if (!resp.get_updates_buf.isNullOrEmpty()) {
                    account = account.copy(getUpdatesBuf = resp.get_updates_buf)
                }
                val msgs = resp.msgs.orEmpty()
                msgs.forEach { msg ->
                    try { handleMessage(account, msg) } catch (t: Throwable) {
                        LogStore.w(TAG, "[${account.accountId}] handleMessage 失败: ${t.message}")
                    }
                }
                // 服务端建议的下一次 long-poll 超时
                val waitMs = (resp.longpolling_timeout_ms ?: 0).coerceAtLeast(0)
                if (waitMs > 0 && msgs.isEmpty()) {
                    // 空响应且服务端给了超时，通常意味着 "请求被 hold 住到超时"
                    // 这里立刻再发起下一次 long-poll 即可，无需额外 sleep
                } else if (msgs.isEmpty()) {
                    delay(300)
                }
            }
        }

        private suspend fun handleMessage(account: WeixinAccount, msg: WeixinMessage) {
            if (msg.message_type == WeixinMessage.MSG_TYPE_BOT) return   // 自己发出的，忽略
            val fromUserId = msg.from_user_id ?: return
            val text = msg.plainText()
            if (text != null) {
                LogStore.i(TAG, "[${account.accountId}] ← ${msg.from_user_id}: $text")
                val typingState = beginTyping(account, fromUserId, msg.context_token)
                try {
                    val reply = onIncomingText(account, fromUserId, msg.context_token, text, msg)
                    if (!reply.isNullOrBlank()) {
                        val s = sendText(appContext, account, fromUserId, reply, msg.context_token)
                        if (s.isOk) {
                            LogStore.i(TAG, "[${account.accountId}] → ${msg.from_user_id}: ${reply.take(60)}")
                        } else {
                            LogStore.w(TAG, "[${account.accountId}] sendText 失败 ret=${s.ret} err=${s.errmsg}")
                        }
                    }
                } finally {
                    endTyping(account, fromUserId, typingState)
                }
                return
            }
            // 非文本消息（图片/语音/文件/视频/工具调用）先在日志里记录类型，不自动回复
            val types = msg.item_list?.map { it.type }?.joinToString(",")
            LogStore.i(TAG, "[${account.accountId}] ← ${msg.from_user_id} non-text item_types=[$types]")
        }
    }
}
