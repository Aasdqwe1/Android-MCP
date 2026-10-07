package com.mcp

import android.content.Context
import android.util.Base64
import com.mcp.core.chat.ChatSession
import com.mcp.core.llm.BackendType
import com.mcp.core.llm.backendCredentialsSatisfied
import com.mcp.data.LocalStore
import com.mcp.deepseek.AuthPrefs
import com.mcp.deepseek.DeepSeekException
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面端 Web 客户端的原生 HTTP 服务（内嵌 NanoHTTPD，`fi.iki.elonen` 2.3.1）。
 *
 * 与 ChatBridge 同进程、同后端：静态资源 + REST API + SSE 事件流。
 * 会话列表 / 消息 / 技能 / 聊天操作全部直连 [ChatBridge] 与 [LocalStore]/[DeepSeekApi]，
 * 彻底取代原 local_web_server.py 的「纯静态文件」方案，让 chat_desktop.html 真正可操作会话。
 *
 * 浏览器访问入口：http://<ip>:<端口>/web/（所有路由统一挂 /web 前缀）
 *
 * API 一览（均为前缀剥离后的内部路径；对外需带 /web，如 /web/api/health）：
 *  - GET  /api/health                        -> { ok, hasToken, loggedIn, backend, sessionId }
 *  - GET  /api/sessions                      -> [{ id, title, pinned, updatedAt, msgCount }]
 *  - POST /api/sessions                      -> { id }          （新建）
 *  - POST /api/sessions/{id}                 -> { title? } / { pinned? }（重命名/置顶）
 *  - DELETE /api/sessions/{id}               -> { ok }
 *  - GET  /api/sessions/{id}/messages?offset=&limit= -> { messages, total, offset, limit, hasMore }
 *  - GET  /api/sessions/{id}/stream                  -> { active, busy, stopping, msgId, content, thinking }
 *                                                       （活跃流快照：浏览器刷新后恢复「正在生成中」）
 *  - GET  /api/sessions/{id}/workspace                -> { path, files: [{name, size, mtime}], count }
 *  - POST /api/sessions/{id}/workspace                -> { ok, name, size }  （body: { filename, content_base64 }）
 *  - GET  /api/sessions/{id}/workspace/{filename}     -> 文件流（Content-Disposition: attachment）
 *  - GET  /api/skills                                 -> [技能 JSON]（等价 ChatBridge.listSkills）
 *  - GET  /api/session/current               -> { sessionId }
 *  - POST /api/session/current               -> { sessionId } 设定当前会话
 *  - GET  /api/events                        -> text/event-stream（SSE，转发 emit/emitStream）
 *  - POST /api/chat/send /regenerate /edit /stop /tool_input /attachments /delete_message
 */
class WebApiServer(
    private val context: Context,
    private val auth: AuthPrefs,
    port: Int,
    /** 是否以 HTTPS 启动（影响 makeSecure 的调用，见 [start]）。 */
    val httpsEnabled: Boolean = false,
) : NanoHTTPD(port) {

    /** 独立于 WebView 的桥接实例：聊天操作直连后端，事件经 [onWebEvent] 转发给 SSE 订阅者。 */
    private val bridge = ChatBridge(auth, context.applicationContext)

    /** 当前活跃的 SSE 订阅者（并发安全）。 */
    private val sseClients = CopyOnWriteArrayList<SseClient>()

    @Volatile private var started = false

    /**
     * 流转发：**进程内所有** ChatBridge 实例（手机桥 + 本机桌面桥）的流事件都要进 SSE。
     * 只挂本实例的 onWebEvent 会漏掉手机端发起的流——桌面看不到逐字正文与工具卡片，
     * 「生成中」状态也要等刷新才查得到（多端不同步）。
     */
    private val streamListener = ChatBridge.StreamListener { _, sid, event, data ->
        broadcast(sid, event, data)
    }

    init {
        // 非流事件（tokenConfig / tokenFlow / tts / 附件回传）仍走本实例自己的 onWebEvent；
        // 流事件统一由进程级扇出投递（见 ChatBridge.StreamListener），避免重复投递。
        bridge.onWebEvent = { sid, event, data -> broadcast(sid, event, data) }
        ChatBridge.addStreamListener(streamListener)
    }

    /**
     * SSE 事件流禁用 gzip：NanoHTTPD 对 text 类 MIME（text/html、text/event-stream 等）的响应默认按 `Accept-Encoding: gzip` 走
     * GZIPOutputStream，而 GZIP 会缓冲 Deflater 输出，只有缓冲区满或 finish() 才 flush。
     * SSE 是长连接、永不 finish，叠加 gzip 后每个事件帧都被压在缓冲区里不落盘到 socket，
     * 前端 EventSource 永远收不到逐字 content/thinking/done——表现为「无逐字输出、结束后无气泡」。
     * 固定长度 JSON API 不受影响（整包写完即 finish），故仅对 event-stream 关闭 gzip。
     */
    override fun useGzipWhenAccepted(response: NanoHTTPD.Response): Boolean {
        if (response.getMimeType()?.startsWith("text/event-stream") == true) return false
        return super.useGzipWhenAccepted(response)
    }

    // ───────────────────────── 生命周期（进程内单例） ─────────────────────────

    companion object {
        /**
         * URL 路由前缀（写死，不可配置）：页面 / API / SSE / 字体统一挂在 /web 下。
         * 浏览器访问 http://<ip>:<端口>/web/。
         */
        const val ROUTE_PREFIX = "/web"

        @Volatile private var current: WebApiServer? = null

        /** 当前是否有任一 WebApiServer 实例在监听。 */
        fun isRunning(): Boolean = current?.started == true

        /** 当前实例监听端口；未运行时返回 -1。 */
        fun currentPort(): Int = if (current?.started == true) current?.listeningPort ?: -1 else -1

        /**
         * 当前实例使用的 URL scheme（"https" / "http"）。
         *
         * 供 UI 拼端点地址用：写死 http:// 会导致 HTTPS 模式下展示的地址连不上。
         * 未运行时返回 null，调用方自行决定占位文案。
         */
        fun currentScheme(): String? =
            if (current?.started == true) (if (current?.httpsEnabled == true) "https" else "http") else null

        /** 启动（内部会先停止旧实例）。端口被占用时自动向后递增重试，最多尝试 20 个端口。
         *  返回实际监听端口，全部端口均不可用时抛 [IOException]。 */
        @Throws(IOException::class)
        fun start(appContext: Context, auth: AuthPrefs, port: Int, https: Boolean = false): Int {
            stop()
            var candidate = port.coerceIn(1, 65535)
            var lastError: IOException? = null
            for (attempt in 0 until 20) {
                if (candidate > 65535) break
                val server = WebApiServer(appContext.applicationContext, auth, candidate, https)
                try {
                    if (https) {
                        // makeSecure 必须在 start 之前调用：它替换底层 ServerSocket 工厂，
                        // 之后再设不生效。第二参 null = 用 JVM 默认协议集（TLSv1.2/1.3）。
                        server.makeSecure(HttpsCertManager.sslSocketFactory(appContext), null)
                    }
                    server.start(candidate, true)
                    server.started = true
                    current = server
                    LogStore.i("WEB", "Web 服务器已绑定端口 ${server.listeningPort}（${if (https) "HTTPS" else "HTTP"}）")
                    return server.listeningPort
                } catch (e: IOException) {
                    lastError = e
                    runCatching { server.stop() }
                    val busy = e is java.net.BindException ||
                        e.message?.contains("Address already in use") == true ||
                        e.message?.contains("EADDRINUSE") == true
                    if (!busy) throw e
                    LogStore.w("WEB", "端口 $candidate 被占用（${e.message}），尝试 ${candidate + 1}")
                    candidate++
                    if (candidate <= 65535) Thread.sleep(100)
                }
            }
            throw lastError ?: IOException("无法绑定可用端口")
        }

        /** 停止并释放当前实例（同时关闭所有 SSE 连接）。 */
        fun stop() {
            val s = current
            current = null
            s?.shutdown()
        }

        /**
         * 请求体上限。旧实现按客户端声明的 Content-Length 直接分配 ByteArray，
         * 一条 `Content-Length: 2147483647` 就能让本进程尝试分配 2GB → OOM。
         * 16MB 足以覆盖正常对话请求（含长上下文与 base64 附件），同时把 DoS 面收窄。
         */
        const val MAX_BODY_BYTES: Long = 16L * 1024 * 1024

        val EMPTY = ByteArray(0)
        val HEARTBEAT = ": ping\n\n".toByteArray(Charsets.UTF_8)
    }

    private val sseIdGen = AtomicLong(0)

    private fun shutdown() {
        started = false
        ChatBridge.removeStreamListener(streamListener)
        for (c in sseClients) c.detach()
        sseClients.clear()
        // 销毁本服务独占的 ChatBridge：它在 init 里向 PtcAskUserBridge 注册了 ask_user 处理器，
        // 不销毁就会留下「僵尸处理器」——服务器已停、SSE 订阅者已清空，它却仍在注册表里排队，
        // 下一次程序内 ask_user 可能被它抢先接走并挂起等待，而事件再也送不到任何人，最终整段
        // run_code 卡死到超时。destroy() 会注销处理器、移出实例表并取消其协程作用域。
        runCatching { bridge.destroy() }
        runCatching { stop() }
    }

    // ───────────────────────── 静态资源 ─────────────────────────

    private val staticAssets = mapOf(
        "" to "chat_desktop.html",
        "chat_desktop.html" to "chat_desktop.html",
        "chat.html" to "chat.html",
        "katex.min.css" to "katex.min.css",
        "marked.min.js" to "marked.min.js",
        "highlight.min.js" to "highlight.min.js",
        "purify.min.js" to "purify.min.js",
        "katex.min.js" to "katex.min.js",
        "mermaid.min.js" to "mermaid.min.js",
        "auto-render.min.js" to "auto-render.min.js",
        "bridge_polyfill.js" to "bridge_polyfill.js"
    )

    private fun mimeFor(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        name.endsWith(".woff2") -> "font/woff2"
        name.endsWith(".woff") -> "font/woff"
        else -> "application/octet-stream"
    }

    private fun serveStatic(path: String): NanoHTTPD.Response? {
        val name = staticAssets[path] ?: return null
        return try {
            val bytes = context.assets.open(name).use { it.readBytes() }
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, mimeFor(name),
                java.io.ByteArrayInputStream(bytes), bytes.size.toLong()
            )
        } catch (_: IOException) {
            null
        }
    }

    private fun serveFont(path: String): NanoHTTPD.Response? {
        // katex.min.css 引用 fonts/KaTeX_*.woff2；仅允许白名单字体目录下的 .woff2/.woff
        val name = path.removePrefix("/").removePrefix("fonts/")
        if (!name.matches(Regex("KaTeX_[A-Za-z0-9-]+\\.woff2?"))) return null
        return try {
            val bytes = context.assets.open("fonts/$name").use { it.readBytes() }
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, mimeFor(name),
                java.io.ByteArrayInputStream(bytes), bytes.size.toLong()
            )
        } catch (_: IOException) {
            null
        }
    }

    // ───────────────────────── 路由 ─────────────────────────

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // CORS 预检必须在路由**之前**短路。
        // 浏览器在发带 Authorization 或 Content-Type: application/json 的跨源请求前会先发 OPTIONS；
        // 旧实现没有这个分支，预检落到 route() 后因"方法不匹配"返回 404 ——
        // 预检失败等于请求根本发不出去，而响应头里却声称允许 POST，自相矛盾。
        // 结果是：说明里写的"支持浏览器客户端"，实际一个纯浏览器客户端都连不上。
        //
        // 这里不回显路由是否存在、也不鉴权：预检不携带业务意图，且它的响应必须是无条件成功的。
        if (session.method == NanoHTTPD.Method.OPTIONS) {
            return withCors(
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NO_CONTENT, "text/plain; charset=utf-8", ""
                )
            )
        }
        val response = try {
            route(session.uri, session)
        } catch (e: RequestBodyRejected) {
            LogStore.w("WEBAPI", "拒绝请求体 ${session.uri}: ${e.message}")
            json(e.status, JSONObject().put("error", e.message ?: "请求体不可用"))
        } catch (e: Exception) {
            LogStore.e("WEBAPI", "处理请求异常 ${session.uri}: ${e.message}")
            json(NanoHTTPD.Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "internal error"))
        }
        return withCors(response)
    }

    /**
     * 统一的 CORS 响应头。
     *
     * Allow-Headers 必须列出**实际会被读取**的请求头，漏一个就会让浏览器预检失败：
     *  - Authorization   —— 鉴权（/mcp 与 /v1）
     *  - X-Session-Id    —— 外部请求指定归属会话（openAiCompatRoute 会读）
     *  - X-Conversation-Id —— 外部对话标识（增量通道用）
     * 旧实现只写了 Content-Type，等于把带鉴权的浏览器客户端全部挡在预检上。
     */
    private fun withCors(response: NanoHTTPD.Response): NanoHTTPD.Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader(
            "Access-Control-Allow-Headers",
            "Content-Type, Authorization, X-Session-Id, X-Conversation-Id"
        )
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun route(uri: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val rawPath = uri.substringBefore('?')
        val method = session.method

        // ── 自签名证书下载（HTTPS 模式下供客户端导入信任）──────────────────
        // 自签名证书不在任何系统信任库里，外部客户端连接会抛
        // Trust anchor for certification path not found。把证书导给用户导入是标准解法。
        if (rawPath == "/cert" || rawPath == "/cert.pem") {
            val pem = HttpsCertManager.certPem(context)
            return if (pem == null) {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, "text/plain",
                    "当前未启用 HTTPS 或证书尚未生成"
                )
            } else {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK, "application/x-pem-file", pem
                )
            }
        }

        // ── MCP 双向服务（发射本地工具）────────────────────────────────────
        // /mcp 是标准 MCP 端点，不挂在 /web 前缀下——外部 MCP 客户端
        // （Claude Desktop / Cursor / 自研 client）直连 http(s)://<ip>:<port>/mcp。
        if (rawPath == "/mcp" || rawPath.startsWith("/mcp/")) {
            return mcpRoute(method, session)
        }

        // ── OpenAI 兼容 API（发射本机对话能力）──────────────────────────────
        // 外部客户端（OpenAI SDK / Cherry Studio / NextChat / LangChain…）把本机
        // 当成 OpenAI 兼容 LLM 端点。裸路径 /v1/... 与带前缀 /web/v1/... 都支持，
        // 方便不同客户端（有的要求 base_url 不带额外路径）。
        if (rawPath == "/v1" || rawPath.startsWith("/v1/") ||
            rawPath == "$ROUTE_PREFIX/v1" || rawPath.startsWith("$ROUTE_PREFIX/v1/")
        ) {
            val v1Path = rawPath.removePrefix(ROUTE_PREFIX)
            return openAiCompatRoute(v1Path, method, session)
        }

        // ── 路由前缀（写死 /web）──────────────────────────────────────────
        // 页面 / API / SSE / 字体全部挂在 /web 下，浏览器访问
        // http://<ip>:<端口>/web/ 即可。前缀处理集中在这一处：剥离后下面的
        // 路由判断全部保持原样，不必给每个 endpoint 都加一遍前缀。
        //
        // 裸根（/ 或 /index.html）重定向到 /web/：用户只输 ip:端口 时也能进。
        if (rawPath.isBlank() || rawPath == "/" || rawPath == "/index.html") {
            return redirect("$ROUTE_PREFIX/")
        }
        // /web（无尾斜杠）也必须重定向到 /web/：页面里的静态资源是**相对路径**
        // （katex.min.css / bridge_polyfill.js 等），基准为 /web/ 时解析成
        // /web/katex.min.css；若停在 /web（无斜杠）会解析成 /katex.min.css 而 404。
        if (rawPath == ROUTE_PREFIX) {
            return redirect("$ROUTE_PREFIX/")
        }
        if (!rawPath.startsWith("$ROUTE_PREFIX/")) {
            // 无前缀一律 404（写死前缀，不再兼容旧的根路径访问）
            return json(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "not found: $rawPath"))
        }
        val path = rawPath.removePrefix(ROUTE_PREFIX).trimEnd('/').ifEmpty { "/" }

        if (path == "/api/events" && method == NanoHTTPD.Method.GET) return handleEvents()

        // Token 看板配置（总上下文 / 预留输出 / 预留压缩 / 模型）：浏览器模式拉取。
        // 与 WebView 下 ChatBridge.requestTokenConfig() 语义一致。
        if (path == "/api/token_config" && method == NanoHTTPD.Method.GET) {
            return jsonOk(bridge.requestTokenConfig())
        }

        // 预设（三档模式）列表与切换：浏览器模式下拉初始化 / 切换。
        if (path == "/api/presets" && method == NanoHTTPD.Method.GET) {
            return jsonOk(bridge.listPresets())
        }
        if (path == "/api/preset/current" && method == NanoHTTPD.Method.GET) {
            return jsonOk(JSONObject().put("presetId", bridge.getPreset()))
        }
        if (path == "/api/preset/current" && method == NanoHTTPD.Method.POST) {
            val body = readJson(session)
            return jsonOk(bridge.setPreset(body.optString("presetId", "")))
        }

        // 当前会话工作区路径（供前端显示与快捷注入）。
        if (path == "/api/session/workspace_path" && method == NanoHTTPD.Method.GET) {
            val sid = session.parameters["sessionId"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: bridge.currentSessionId ?: ""
            val path = if (sid.isNotBlank()) bridge.workspacePath(sid) else ""
            return jsonOk(JSONObject().put("workspacePath", path))
        }

        // Token 请求流水（浏览器模式）：按会话拉取分页 / 计数 / 单条详情 / 清空。
        // 与 WebView 下的 ChatBridge.getTokenFlowPage/Counter/Detail/Clear 语义一致。
        if (path == "/api/token_flow" && method == NanoHTTPD.Method.DELETE) {
            val sid = session.parameters["sessionId"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: bridge.currentSessionId ?: ""
            return jsonOk(bridge.clearTokenFlow(sid))
        }
        if (path == "/api/token_flow/count" && method == NanoHTTPD.Method.GET) {
            val sid = session.parameters["sessionId"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: bridge.currentSessionId ?: ""
            return jsonOk(JSONObject().put("total", bridge.getTokenFlowCount(sid)))
        }
        if (path == "/api/token_flow/page" && method == NanoHTTPD.Method.GET) {
            val sid = session.parameters["sessionId"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: bridge.currentSessionId ?: ""
            val offset = session.parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
            val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
            return jsonOk(bridge.getTokenFlowPage(sid, offset, limit))
        }
        if (path == "/api/token_flow/detail" && method == NanoHTTPD.Method.GET) {
            val sid = session.parameters["sessionId"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: bridge.currentSessionId ?: ""
            val lineIndex = session.parameters["lineIndex"]?.firstOrNull()?.toIntOrNull() ?: -1
            return jsonOk(bridge.getTokenFlowDetail(sid, lineIndex))
        }

        // 分支选择状态（◀ 分支 k/N ▶ 箭头）：浏览器模式持久化 / 恢复。
        // 与 WebView 下 ChatBridge.saveBranchState/loadBranchState 语义一致——
        // 桌面页刷新/重开后仍停在用户上次手动切的分支，而不是回退「最新」。
        if (path == "/api/session/branch_state" && method == NanoHTTPD.Method.GET) {
            val sid = session.parameters["sessionId"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: bridge.currentSessionId ?: ""
            return jsonOk(JSONObject().put("state", bridge.loadBranchStateFor(sid)))
        }
        if (path == "/api/session/branch_state" && method == NanoHTTPD.Method.POST) {
            val body = readJson(session)
            val sid = body.optString("sessionId").takeIf { it.isNotBlank() }
                ?: bridge.currentSessionId ?: ""
            bridge.saveBranchStateFor(sid, body.optString("state", ""))
            return jsonOk(JSONObject().put("ok", true))
        }

        when {
            path == "/api/health" && method == NanoHTTPD.Method.GET -> return health()
            path == "/api/sessions" && method == NanoHTTPD.Method.GET -> return listSessions()
            path == "/api/sessions" && method == NanoHTTPD.Method.POST -> return createSession(session)
            path.startsWith("/api/sessions/") -> return sessionRoute(path.removePrefix("/api/sessions/"), method, session)
            path == "/api/skills" && method == NanoHTTPD.Method.GET -> return jsonOk(bridge.listSkills())
            path == "/api/session/current" && method == NanoHTTPD.Method.GET ->
                return jsonOk(JSONObject().put("sessionId", bridge.currentSessionId ?: ""))
            path == "/api/session/current" && method == NanoHTTPD.Method.POST -> {
                val body = readJson(session)
                val sid = body.optString("sessionId").ifBlank { null }
                bridge.currentSessionId = sid
                // 切换会话必须同步该会话**自己的**预设：预设运行时是进程级单例，
                // 不同步就会出现「A 会话开了 PTC，切到 B 会话后工具箱仍被折叠、
                // 而 B 的提示词按自己的 presetId 展开」这种同会话内自相矛盾的状态。
                applyPresetForSession(sid)
                return jsonOk(JSONObject().put("ok", true).put("sessionId", sid ?: ""))
            }
            path.startsWith("/api/chat/") -> return chatRoute(path.removePrefix("/api/chat/"), session)
        }

        val rel = path.removePrefix("/")
        if (staticAssets.containsKey(rel)) serveStatic(rel)?.let { return it }
        if (path.startsWith("/fonts/")) serveFont(path)?.let { return it }
        if (path == "/" || path == "/index.html") serveStatic("")?.let { return it }

        return json(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "not found: $path"))
    }

    // ───────────────────────── MCP 端点 ─────────────────────────

    /**
     * MCP 双向服务 —— Server 方向：把本地工具通过 MCP 协议发射出去。
     *
     * 简化版 Streamable HTTP：
     *  - POST /mcp：请求体是一段 JSON-RPC 2.0 文本，返回 JSON-RPC 响应；
     *    通知（无 id）按规范返回 202 Accepted、无响应体。
     *  - GET  /mcp：返回端点能力说明，便于人工或探测工具确认服务在线。
     *
     * 复用 [McpServerEndpoint]——其 Toolbox 与 LLM 看到的是同一份
     * （ToolPrefs 开关 ∩ 预设白名单），因此 MCP 暴露面自动跟随用户设置。
     */
    private fun mcpRoute(method: NanoHTTPD.Method, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // 访问控制：总开关 -> Origin 校验（防 DNS 重绑定）-> Bearer token（可配）。
        if (!McpPrefs.isEnabled(context)) {
            return json(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "MCP 服务已关闭"))
        }
        checkMcpOrigin(session)?.let { originErr ->
            return json(NanoHTTPD.Response.Status.FORBIDDEN, JSONObject().put("error", originErr))
        }
        // Bearer 校验：三处调用点共用 McpPrefs.checkBearer（见其注释）。
        // 旧实现在这里内联了一份硬编码前缀解析，与 /v1 的两份口径不一致。
        McpPrefs.checkBearer(context, session.headers["authorization"])?.let { reason ->
            return json(NanoHTTPD.Response.Status.UNAUTHORIZED, JSONObject().put("error", reason))
        }
        if (method == NanoHTTPD.Method.GET) {
            return jsonOk(
                JSONObject()
                    .put("protocol", "mcp")
                    .put("transport", "streamable-http")
                    .put("hint", "POST JSON-RPC 2.0 消息到本端点：initialize / tools/list / tools/call / ping")
                    .put("endpoint", "/mcp")
            )
        }
        if (method != NanoHTTPD.Method.POST) {
            return json(
                NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                JSONObject().put("error", "MCP 端点仅支持 POST（JSON-RPC 2.0）")
            )
        }
        val body = readBody(session)
        if (body.isBlank()) {
            return json(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", "空请求体"))
        }
        val out = try {
            McpServerEndpoint.handle(context, auth, body)
        } catch (e: Exception) {
            LogStore.e("MCP", "处理 MCP 请求异常: ${e.message}")
            null
        }
        // 通知（无 id）不产生响应体：按 JSON-RPC 2.0 返回 202 Accepted。
        if (out == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.ACCEPTED, "application/json; charset=utf-8", ""
            )
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", out
        )
    }

    /**
     * OpenAI 兼容 API 路由。
     *
     * 支持：
     *  - POST /v1/chat/completions（stream 决定 SSE 还是整包）
     *  - GET  /v1/models
     *
     * 鉴权复用 [McpPrefs.token]；与 /mcp 一样做 Origin 校验（防 DNS 重绑定）。
     */
    private fun openAiCompatRoute(
        path: String,
        method: NanoHTTPD.Method,
        session: NanoHTTPD.IHTTPSession,
    ): NanoHTTPD.Response {
        if (!ApiForwardPrefs.isEnabled(context)) {
            // 关闭 = 视为路由不存在（404），不暴露「有开关」这一信息。
            // 与 MCP 总开关解耦：MCP 发射工具与 LLM 转发是两件事。
            return json(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "not found: $path"))
        }
        checkMcpOrigin(session)?.let { originErr ->
            return json(NanoHTTPD.Response.Status.FORBIDDEN, JSONObject().put("error", originErr))
        }
        val authHeader = session.headers["authorization"]

        if (path == "/v1/models" && method == NanoHTTPD.Method.GET) {
            // models 也要鉴权：避免未授权探测后端与模型名
            McpPrefs.checkBearer(context, authHeader)?.let { reason ->
                return json(NanoHTTPD.Response.Status.UNAUTHORIZED, JSONObject().put("error", reason))
            }
            return jsonOk(OpenAICompatEndpoint.models(context, auth))
        }

        if (path == "/v1/chat/completions" && method == NanoHTTPD.Method.POST) {
            val body = readBody(session)

            // 先探测 stream：错误响应要与成功响应同形态，否则流式客户端会收到一段裸 JSON 而解析失败。
            val wantsStream = OpenAICompatEndpoint.wantsStream(body)

            // ── ① 鉴权最先 ──
            // 旧实现在 chatCompletions 内部做鉴权，晚于"解析归属会话"这一步，
            // 于是未携带 token 的请求能用 400 / 401 的差异探测本机是否配置了会话、当前是什么后端。
            // 鉴权必须早于任何业务判断。
            McpPrefs.checkBearer(context, authHeader)?.let { reason ->
                return compatError(wantsStream, 401, "invalid_request_error", reason)
            }

            // ── ② 外部对话标识 ──
            // 这里**不再解析用户的 App 会话**：/v1 的上下文由「外部对话自己的隔离记录」承担
            // （设计文档 §7.2），App 会话完全不参与 —— 由此消除 P0-1 的焦点漂移与串味。
            // 标识优先级（X-Conversation-Id > body.session_id > body.user > 首轮内容派生）
            // 在 OpenAICompatEndpoint 里统一处理：那里能同时看到 body 与 messages。
            val conversationIdHeader = session.headers["x-conversation-id"]?.takeIf { it.isNotBlank() }
            // X-Session-Id：客户端按会话 id 绑定的标准入口（次优先于 X-Conversation-Id）。
            val sessionIdHeader = session.headers["x-session-id"]?.takeIf { it.isNotBlank() }
            // 外部调用方审计：谁在什么时候、用什么形态调的。成本不可见是有意的取舍，
            // 但至少要能从 logcat 查出来 —— 否则出问题彻底不可诊断（§7.2）。
            LogStore.i(
                "WEBAPI",
                "/v1/chat/completions 来自 ${session.remoteIpAddress ?: "?"} " +
                    "stream=$wantsStream conv=${conversationIdHeader ?: sessionIdHeader ?: "(derived)"} bytes=${body.length}"
            )

            if (!wantsStream) {
                // 非流式：状态码由结果显式携带，不再靠 out.contains("error") 嗅探。
                // 旧写法有两个独立缺陷：模型正文里出现 error 字样就会把 200 判成 400；
                // 而 401 / 500 又一律被压成 400 —— 客户端无法据状态码决定是否重新认证或退避重试。
                return when (
                    val result = OpenAICompatEndpoint.chatCompletions(
                        context, auth, body, authHeader, conversationIdHeader, sessionIdHeader, emit = {}
                    )
                ) {
                    is OpenAICompatEndpoint.CompatResult.Ok ->
                        json(NanoHTTPD.Response.Status.OK, result.body)
                    is OpenAICompatEndpoint.CompatResult.Err ->
                        json(statusOf(result.httpStatus), compatErrorJson(result))
                }
            }

            // ── ③ 流式 ──
            // PipedStream 边产边发：产帧在 IO 线程，写端关闭后读端 EOF。
            val pipeOut = java.io.PipedOutputStream()
            val pipeIn = java.io.PipedInputStream(pipeOut, 64 * 1024)
            val writer = Thread {
                try {
                    val result = OpenAICompatEndpoint.chatCompletions(
                        context, auth, body, authHeader, conversationIdHeader, sessionIdHeader
                    ) { frame ->
                        runCatching {
                            pipeOut.write(frame.toByteArray(Charsets.UTF_8))
                            pipeOut.flush()
                        }
                    }
                    // 预处理失败（缺 messages / 会话不存在等）：以 SSE 帧形态回错误，
                    // 且**必须补 [DONE]** —— 少了它官方 SDK / LangChain 会一直等流结束。
                    if (result is OpenAICompatEndpoint.CompatResult.Err) {
                        runCatching {
                            pipeOut.write(("data: " + compatErrorJson(result) + "\n\n").toByteArray(Charsets.UTF_8))
                            pipeOut.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                            pipeOut.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { pipeOut.close() }
                }
            }
            writer.isDaemon = true
            writer.start()
            val resp = NanoHTTPD.newChunkedResponse(
                NanoHTTPD.Response.Status.OK, "text/event-stream; charset=utf-8", pipeIn
            )
            resp.addHeader("Cache-Control", "no-cache")
            resp.addHeader("X-Accel-Buffering", "no")
            return resp
        }

        return json(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "not found: $path"))
    }

    // ───────────────────────── /v1 兼容响应工具 ─────────────────────────

    /** 把 [OpenAICompatEndpoint.CompatResult.Err] 渲染为 OpenAI 风格错误体。 */
    private fun compatErrorJson(err: OpenAICompatEndpoint.CompatResult.Err): JSONObject =
        JSONObject().put(
            "error",
            JSONObject()
                .put("message", err.message)
                .put("type", err.type)
                .put("code", err.httpStatus)
        )

    /**
     * 预处理阶段的错误响应。形态按客户端是否请求流式分派（这是既有约定，保持不变）：
     *
     *  - **流式**：SSE 帧 + [DONE]。旧实现漏了 [DONE]，
     *    导致官方 SDK / LangChain 一直等流结束，**挂到超时**才报错。
     *  - **非流式**：JSON + **真实 HTTP 状态码**。
     *    旧实现一律回 400 —— 连鉴权失败都是 400（客户端不会触发重新认证），
     *    上游 5xx 也被压成 400（客户端的退避重试策略全部失效）。
     */
    private fun compatError(stream: Boolean, code: Int, type: String, message: String): NanoHTTPD.Response {
        val err = OpenAICompatEndpoint.CompatResult.Err(code, type, message)
        if (stream) {
            val bytes = ("data: " + compatErrorJson(err) + "\n\ndata: [DONE]\n\n").toByteArray(Charsets.UTF_8)
            val resp = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "text/event-stream; charset=utf-8",
                ByteArrayInputStream(bytes), bytes.size.toLong()
            )
            resp.addHeader("Cache-Control", "no-cache")
            return resp
        }
        return json(statusOf(code), compatErrorJson(err))
    }

    /** HTTP 状态码 → NanoHTTPD 状态。未列出的码统一按 500，不返回客户端读不懂的码。 */
    private fun statusOf(code: Int): NanoHTTPD.Response.IStatus = when (code) {
        200 -> NanoHTTPD.Response.Status.OK
        400 -> NanoHTTPD.Response.Status.BAD_REQUEST
        401 -> NanoHTTPD.Response.Status.UNAUTHORIZED
        403 -> NanoHTTPD.Response.Status.FORBIDDEN
        404 -> NanoHTTPD.Response.Status.NOT_FOUND
        408 -> NanoHTTPD.Response.Status.REQUEST_TIMEOUT
        413 -> NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE
        429 -> NanoHTTPD.Response.Status.TOO_MANY_REQUESTS
        503 -> NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE
        504 -> GatewayTimeout
        else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
    }

    /**
     * 504 Gateway Timeout。
     *
     * NanoHTTPD 2.3.1 的 Status 枚举里没有它 —— 最接近的 REQUEST_TIMEOUT 是 408，语义不同
     * （那是"客户端没及时把请求发完"，这里是"上游太久没回"），故自定义一个 IStatus。
     */
    private object GatewayTimeout : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = 504
        override fun getDescription(): String = "Gateway Timeout"
    }


    /**
     * Origin 头校验（防 DNS 重绑定）。
     *
     * - 非浏览器客户端（curl / MCP SDK / OpenAI SDK）通常不带 Origin → 放行；
     * - 浏览器带 Origin 时，要求与 Host 头**同源**，或为**本机回环**，否则拒绝。
     *
     * ⚠️ 这里必须做**精确主机名比较**，这是本函数存在的全部意义。
     * 旧实现用的是前缀匹配：
     * ```
     * if (originAuthority.startsWith("localhost") || originAuthority.startsWith("127.0.0.1")) return null
     * ```
     * 而 `Origin: http://localhost.evil.com` 的 authority 就是 `localhost.evil.com`，
     * 前缀命中 → 直接放行。攻击者只要用自己的域名（`*.evil.com`）配 DNS 重绑定指向
     * 受害者内网 IP，就能绕过这条**专为防 DNS 重绑定而写**的检查 —— 防线被它自己击穿。
     * `127.0.0.1.evil.com` 同理。
     *
     * ⚠️ 另外，Origin 解析失败时必须 **fail-closed**（拒绝）。
     * 旧实现是 `?: return null`（放行）—— 畸形输入属于攻击面，不是免检通道。
     *
     * @return null 表示放行；非 null 是拒绝原因。
     */
    private fun checkMcpOrigin(session: NanoHTTPD.IHTTPSession): String? {
        // 无 Origin：非浏览器客户端，放行（这是既定策略，CLI 客户端依赖它）
        val origin = session.headers["origin"]?.trim() ?: return null
        if (origin.isEmpty() || origin == "null") return null

        val uri = runCatching { java.net.URI(origin) }.getOrNull()
            ?: return "Origin 无法解析: $origin"
        val originHost = uri.host?.lowercase()?.removeSuffix(".")
            ?: return "Origin 缺少主机名: $origin"

        // 回环地址：精确匹配，绝不做前缀
        if (originHost == "localhost" || originHost == "127.0.0.1" ||
            originHost == "[::1]" || originHost == "::1"
        ) {
            return null
        }

        // 同源判定：主机名一致；端口在双方都显式给出时才要求一致
        val hostHeader = session.headers["host"]
        val sameHost = originHost == hostNameOf(hostHeader)
        if (sameHost) {
            val originPort = originPortOf(uri)
            val hostPort = portOf(hostHeader)
            if (hostPort < 0 || originPort < 0 || hostPort == originPort) return null
        }
        return "Origin 不被允许: $origin"
    }

    /** 从 Host 头取主机名（小写）。支持 `[::1]:8080` 形式的 IPv6 字面量。 */
    private fun hostNameOf(hostHeader: String?): String? {
        val h = hostHeader?.trim()?.lowercase()?.removeSuffix(".")?.ifEmpty { null } ?: return null
        return if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':').ifEmpty { null }
    }

    /** 从 Host 头取显式端口；未写端口返回 -1（未知）。 */
    private fun portOf(hostHeader: String?): Int {
        val h = hostHeader?.trim()?.lowercase() ?: return -1
        val rest = if (h.startsWith("[")) h.substringAfter(']', "") else h.substringAfter(':', "")
        if (rest.isEmpty()) return -1
        return rest.toIntOrNull() ?: -1
    }

    /** Origin URI 的有效端口；scheme 缺省端口按标准补全，无法判定时返回 -1。 */
    private fun originPortOf(uri: java.net.URI): Int {
        if (uri.port > 0) return uri.port
        return when (uri.scheme?.lowercase()) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> -1
        }
    }

    // ───────────────────────── 会话 API ─────────────────────────

    private fun listSessions(): NanoHTTPD.Response {
        val backend = auth.getBackend()
        val tag = LocalStore.backendTag(backend)
        val list: List<ChatSession> = runCatching {
            when (backend) {
                // Web 自动化与 OpenAI 同为无服务端会话态，直接读本地。
                BackendType.OPENAI, BackendType.WEB_AUTOMATION, BackendType.LITERT, BackendType.MNN -> LocalStore.loadSessions(context, tag)
            }
        }.getOrDefault(LocalStore.loadSessions(context, tag))

        val sorted = list.sortedWith(
            compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt }
        )
        val arr = JSONArray()
        for (s in sorted) {
            val count = runCatching { bridge.getMessageCount(s.id) }.getOrDefault(0)
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("pinned", s.pinned)
                put("updatedAt", s.updatedAt)
                put("msgCount", count)
                put("presetId", s.presetId ?: "")
            })
        }
        return jsonOk(arr)
    }

    private fun createSession(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val backend = auth.getBackend()
        val tag = LocalStore.backendTag(backend)
        val body = readJson(session)
        val presetId = body.optString("presetId", "").ifEmpty { null }
        return try {
            // 本地会话（前缀取自 backendTag，wa 为 Web 自动化）
            val id = tag + "-" + java.util.UUID.randomUUID()
            run {
                val s = ChatSession(
                    id = id, title = "", pinned = false, updatedAt = 0.0,
                    model = auth.getOpenAIModel(),
                    contextWindow = auth.getOpenAIContextWindow(),
                    maxInput = auth.getOpenAIMaxInput(),
                    presetId = presetId
                )
                val list = LocalStore.loadSessions(context, tag).toMutableList()
                list.add(0, s)
                LocalStore.saveSessions(context, tag, list)
            }
            bridge.currentSessionId = id
            // 创建时立即应用预设（同时把 presetId 记到会话态，供 ptcActive/提示词派生）
            bridge.applySessionPreset(id, presetId)
            jsonOk(JSONObject().put("id", id))
        } catch (e: Exception) {
            val msg = if (e is DeepSeekException) "HTTP ${e.httpCode}: ${e.message}" else (e.message ?: e.toString())
            json(NanoHTTPD.Response.Status.INTERNAL_ERROR, JSONObject().put("error", "新建会话失败：$msg"))
        }
    }

    /** 把某会话自己的 presetId 同步到进程级预设运行时（切会话时调用）。 */
    private fun applyPresetForSession(sid: String?) {
        if (sid.isNullOrEmpty()) return
        val tag = LocalStore.backendTag(auth.getBackend())
        val presetId = runCatching {
            LocalStore.loadSessions(context, tag).firstOrNull { it.id == sid }?.presetId
        }.getOrNull()
        runCatching { bridge.applySessionPreset(sid, presetId) }
            .onFailure { LogStore.w("PRESET", "会话预设同步失败 sid=$sid: ${it.message}") }
    }

    private fun sessionRoute(id: String, method: NanoHTTPD.Method, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // 消息分页：GET /api/sessions/{id}/messages?offset=&limit=
        if (id.endsWith("/messages") && method == NanoHTTPD.Method.GET) {
            val sid = id.removeSuffix("/messages")
            val offset = session.parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
            val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
            return jsonOk(bridge.getMessagesPage(sid, offset, limit))
        }

        // 活跃流快照：GET /api/sessions/{id}/stream
        // 浏览器刷新（F5）后页面是全新上下文：正在生成的气泡内容/思考/按钮态都没了，
        // 而流还在服务端继续跑。这里把 StreamTaskManager 里的快照交给前端，
        // 由 onHistory 的第二参数确定性恢复（与 WebView 侧 Tabs.activeStreamResumeJson 同形状）。
        if (id.endsWith("/stream") && method == NanoHTTPD.Method.GET) {
            return jsonOk(streamSnapshot(id.removeSuffix("/stream")))
        }

        // 产物托盘：/api/sessions/{id}/workspace[/{filename}]
        //   GET  /workspace        → 列文件
        //   POST /workspace        → 上传（JSON body：{ filename, content_base64 }）
        //   GET  /workspace/<name> → 下载单文件（Content-Disposition: attachment）
        if (id.contains("/workspace")) {
            val parts = id.split("/", limit = 4)   // [sid, "workspace", filename?]
            val sid = parts[0]
            return when {
                parts.size == 2 && method == NanoHTTPD.Method.GET -> listWorkspace(sid)
                parts.size == 2 && method == NanoHTTPD.Method.POST -> uploadWorkspace(sid, session)
                parts.size >= 3 && method == NanoHTTPD.Method.GET -> downloadWorkspace(sid, parts[2])
                else -> json(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "method not allowed"))
            }
        }

        val backend = auth.getBackend()
        val tag = LocalStore.backendTag(backend)

        return when (method) {
            NanoHTTPD.Method.DELETE -> {
                LocalStore.deleteSession(context, tag, id)
                com.mcp.compaction.ToolResultSpill.clearSession(context, id)
                if (bridge.currentSessionId == id) bridge.currentSessionId = null
                // 内存态一并清理：仍在跑的流否则会继续写回已删除的会话日志（幽灵会话）
                bridge.forgetSession(id)
                jsonOk(JSONObject().put("ok", true))
            }
            NanoHTTPD.Method.POST -> {
                val body = readJson(session)
                if (body.has("title")) {
                    LocalStore.renameSession(context, tag, id, body.optString("title", ""))
                }
                if (body.has("pinned")) {
                    val target = body.optBoolean("pinned", false)
                    val cur = LocalStore.loadSessions(context, tag).firstOrNull { it.id == id }
                    if (cur != null && cur.pinned != target) LocalStore.toggleSessionPinned(context, tag, id)
                }
                jsonOk(JSONObject().put("ok", true))
            }
            else -> json(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "method not allowed"))
        }
    }

    /**
     * 某会话的活跃流快照；无活跃流时 active=false。
     *
     * 字段契约（前端 __dsFetchStreamResume 逐字段读取，改一侧必须改另一侧）：
     *  - active   是否仍有流在跑（false 时前端复位为「发送」态）
     *  - busy / stopping   服务端流状态（仅诊断用）
     *  - msgId    当前正在生成的消息 id，**本地 id**口径（与 message_id 事件一致，供前端气泡回填 id）
     *  - content / thinking   已累积的正文 / 思考增量（前端据此重建气泡并自此继续追加 delta）
     */
    private fun streamSnapshot(sid: String): JSONObject {
        val t = StreamTaskManager.get(sid) ?: return JSONObject().put("active", false)
        return JSONObject()
            .put("active", true)
            .put("busy", t.isBusy)
            .put("stopping", t.isStopping)
            .put("msgId", bridge.toLocalId(sid, t.messageId) ?: t.messageId)
            .put("content", t.lastContent)
            .put("thinking", t.lastThinking)
    }

    // ───────────────────────── 聊天转发 ─────────────────────────

    private fun chatRoute(action: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = readJson(session)
        // 会话 id 来自**本请求的 body**，并显式传给 ChatBridge。
        // 不能再「先写全局 currentSessionId，再让 bridge 自己去读」：NanoHTTPD 每请求一个线程，
        // 多会话并发（或桌面端同时开两个标签页）时全局会被相互覆盖，A 的提问/事件会落到 B。
        //
        // 这里也**不再回写** bridge.currentSessionId：桌面页每个聊天请求都回写的话，
        // 外部 /v1 请求（未显式带 session_id 时）会被动跟随桌面页的焦点漂移，造成会话串味。
        // 焦点会话由 /api/session/current 显式切换，聊天请求只按自己携带的 sid 干活。
        val sid = body.optString("sessionId").takeIf { it.isNotBlank() }

        return try {
            when (action) {
                "send" -> bridge.sendMessageFor(
                    sid,
                    body.optString("prompt"),
                    body.optBoolean("thinking", false),
                    body.optBoolean("search", false)
                )
                "regenerate" -> bridge.regenerateFor(
                    sid,
                    body.optString("childMessageId"),
                    body.optBoolean("thinking", false),
                    body.optBoolean("search", false)
                )
                "edit" -> bridge.editMessageFor(
                    sid,
                    body.optString("messageId"),
                    body.optString("prompt"),
                    body.optBoolean("thinking", false),
                    body.optBoolean("search", false)
                )
                "stop" -> bridge.stopGenerationFor(sid)
                "tool_input" -> bridge.submitUserInputFor(
                    sid,
                    body.optString("callId"),
                    body.optString("response")
                )
                "attachments" -> bridge.sendMessageWithAttachmentsFor(
                    sid,
                    body.optString("prompt"),
                    body.optBoolean("thinking", false),
                    body.optBoolean("search", false),
                    body.optString("attachments", "[]")
                )
                "delete_message" -> bridge.deleteMessageFor(sid, body.optString("messageId"))
                else -> return json(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "unknown action $action"))
            }
            jsonOk(JSONObject().put("ok", true))
        } catch (e: Exception) {
            json(NanoHTTPD.Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "chat error"))
        }
    }

    // ───────────────────────── 健康检查 ─────────────────────────

    private fun health(): NanoHTTPD.Response {
        // 这三个字段原先都是「token != null」，等于要求 OpenAI 兼容后端也必须登录 DeepSeek——
        // 与 ChatBridge.checkHealth 曾经的问题一模一样（那处已修，这处漏了）。
        // 后果：网页端连接指示器恒显示「未连接」，并弹「尚未登录，请在会话页完成登录」的误导横幅
        // （chat_desktop.html 的 __setConn 与 bridge_polyfill 的 checkHealth 都吃这三个字段）。
        // 现统一走共享判定；紧邻的 model 字段本来就按后端分支，可见当初只是漏了这一处。
        val authorized = backendCredentialsSatisfied(auth.getBackend(), !auth.getToken().isNullOrEmpty())
        val model = runCatching {
            when (auth.getBackend()) {
                BackendType.OPENAI -> auth.getOpenAIModel()
                BackendType.WEB_AUTOMATION -> auth.getWebAutomationConfig().name
                BackendType.LITERT -> auth.getLiteRtModelPath()
                BackendType.MNN -> auth.getMnnModelLabel()
            }
        }.getOrDefault("")
        return jsonOk(JSONObject().apply {
            put("ok", authorized)
            put("hasToken", authorized)
            put("loggedIn", authorized)
            put("backend", auth.getBackend().name)
            put("model", model)
            put("sessionId", bridge.currentSessionId ?: "")
        })
    }

    // ───────────────────────── SSE 事件流 ─────────────────────────

    private fun broadcast(sid: String?, event: String, dataJson: String) {
        if (sseClients.isEmpty()) return
        val envelope = JSONObject()
            .put("sid", sid ?: JSONObject.NULL)
            .put("event", event)
            .put("data", runCatching { JSONObject(dataJson) }.getOrElse { dataJson })
        val frame = "data: ${envelope}\n\n"
        for (c in sseClients) c.send(frame)
    }

    private fun handleEvents(): NanoHTTPD.Response {
        val client = SseClient()
        sseClients.add(client)
        val response = NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK, "text/event-stream; charset=utf-8", client.stream
        )
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Accel-Buffering", "no")
        client.send("data: " + JSONObject().put("event", "hello").put("sid", JSONObject.NULL).put("data", JSONObject()).toString() + "\n\n")
        return response
    }

    private inner class SseClient {
        val id = sseIdGen.incrementAndGet()
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val closed = AtomicBoolean(false)

        val stream: InputStream = object : InputStream() {
            // 当前帧字节与其读取偏移。**必须**保证一帧被完整读完后才能换下一帧：
            // 早先的实现按字节切分，超时用 HEARTBEAT 覆盖 buf，会把上一帧没发完的
            // UTF-8 残字节直接丢掉（一个汉字 3 字节，切在中途即半个字），前端表现为
            // JSON 解析失败 / 正文缺字——也就是「吞字」。
            private var buf: ByteArray? = HEARTBEAT
            private var pos = 0

            /** 取下一帧：优先队列，20s 空转则发心跳（仅队列真空时才插）。 */
            private fun nextFrame(): ByteArray? {
                if (closed.get()) return null
                while (true) {
                    val frame = queue.poll(20, TimeUnit.SECONDS)
                    if (frame == null) {
                        // 心跳只是一次 poll 的兜底，不覆盖未读完的帧（此处 buf 必已读完）
                        return HEARTBEAT
                    }
                    if (frame.isEmpty()) return null  // EMPTY 是 detach 的结束哨兵
                    if (frame.isNotEmpty()) return frame
                }
            }

            override fun read(): Int {
                while (true) {
                    val b = buf
                    if (b != null && pos < b.size) return b[pos++].toInt() and 0xFF
                    if (closed.get()) return -1
                    val f = nextFrame() ?: return -1
                    buf = f
                    pos = 0
                }
            }

            // NanoHTTPD 会以 16KB 缓冲调用 read(b, off, len) 预读流；若沿用父类默认实现，
            // 它会一直阻塞到把整个 len 填满才返回，导致 SSE 帧迟迟不交给上层写 socket，
            // 前端（EventSource）永远收不到事件。这里改为「只取当前已缓冲的一帧即返回」，
            // 保证每条广播都能即时 flush 到客户端。
            @Synchronized
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                var cur = buf
                if (cur == null || pos >= cur.size) {
                    if (closed.get()) return -1
                    cur = nextFrame() ?: return -1
                    buf = cur
                    pos = 0
                }
                // 只切到本帧边界，绝不跨帧拼接，避免把多字节字符从中间劈开
                val n = minOf(len, cur!!.size - pos)
                System.arraycopy(cur, pos, b, off, n)
                pos += n
                return n
            }

            override fun close() { detach() }
        }

        fun send(frame: String) {
            if (closed.get()) return
            queue.offer(frame.toByteArray(charset))
        }

        fun detach() {
            if (closed.getAndSet(true)) return
            sseClients.remove(this)
            queue.offer(EMPTY)
        }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private val charset: Charset = Charsets.UTF_8

    private fun readJson(session: NanoHTTPD.IHTTPSession): JSONObject {
        val raw = readBody(session)
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    /** 请求体不可用（过大 / 不支持的传输编码）；由 [serve] 映射为对应的 4xx。 */
    private class RequestBodyRejected(
        val status: NanoHTTPD.Response.IStatus,
        message: String,
    ) : Exception(message)

    /**
     * 读取请求体。与旧实现的两点差别都是实打实的坑：
     *
     * 1. **加上限**：旧实现 `ByteArray(len)` 直接按客户端声明的长度分配，无上界 ——
     *    超限现在抛 [RequestBodyRejected]（413），而不是把进程拖进 OOM。
     * 2. **分块传输**：`Transfer-Encoding: chunked` 时没有 Content-Length，
     *    旧实现返回空串，调用方只能报出"缺少 messages 字段"这种牛头不对马嘴的错误。
     *    现在显式回 411 并说明原因，让客户端知道该改用什么。
     *    （NanoHTTPD 的 `parseBody()` 理论上是正解，但其 map 键的契约需实测确认，
     *      在拿到确定结论前不拿主链路去赌。）
     */
    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val te = session.headers["transfer-encoding"]?.lowercase().orEmpty()
        if (te.contains("chunked")) {
            throw RequestBodyRejected(
                NanoHTTPD.Response.Status.LENGTH_REQUIRED,
                "本端点暂不支持 Transfer-Encoding: chunked，请改用 Content-Length 发送请求体",
            )
        }
        val len = session.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (len <= 0L) return ""
        if (len > MAX_BODY_BYTES) {
            throw RequestBodyRejected(
                NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE,
                "请求体 ${len} 字节，超过上限 ${MAX_BODY_BYTES} 字节",
            )
        }
        val size = len.toInt()
        val ins = session.inputStream
        val buf = ByteArray(size)
        var off = 0
        while (off < size) {
            val r = ins.read(buf, off, size - off)
            if (r < 0) break
            off += r
        }
        return String(buf, 0, off, charset)
    }

    private fun json(status: NanoHTTPD.Response.IStatus, body: Any): NanoHTTPD.Response {
        val text = when (body) {
            is String -> body
            else -> body.toString()
        }
        return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", text)
    }

    private fun jsonOk(body: Any): NanoHTTPD.Response = json(NanoHTTPD.Response.Status.OK, body)

    /** 302 重定向（用于把裸根与无尾斜杠的 /web 归一到 /web/）。 */
    private fun redirect(location: String): NanoHTTPD.Response {
        val resp = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.REDIRECT,
            "text/plain; charset=utf-8",
            "redirecting to $location"
        )
        resp.addHeader("Location", location)
        return resp
    }

    // ───────────────────────── 产物托盘（会话 workspace 文件） ─────────────────────────

    /** 当前会话的 workspace 目录（惰性创建）。 */
    private fun workspaceDir(sid: String): File? = runCatching {
        val tag = LocalStore.backendTag(auth.getBackend())
        LocalStore.ensureSessionWorkspace(context, tag, sid)
    }.getOrNull()

    /** 拒绝路径穿越：不允许路径分隔符、..、绝对路径、控制字符、Windows 保留名。 */
    private fun safeFilename(raw: String): String? {
        if (raw.isEmpty() || raw.length > 255) return null
        if (raw.any { it.isISOControl() }) return null
        if (raw.contains('/') || raw.contains('\\') || raw.contains(':')) return null
        val trimmed = raw.trim()
        if (trimmed == "." || trimmed == "..") return null
        val lower = trimmed.lowercase().removeSuffix(".")
        if (lower in setOf("con", "prn", "aux", "nul")) return null
        if (lower.matches(Regex("^(com|lpt)[1-9]$"))) return null
        return trimmed
    }

    /** GET /api/sessions/{sid}/workspace → 列文件（按修改时间倒序）。 */
    private fun listWorkspace(sid: String): NanoHTTPD.Response {
        val dir = workspaceDir(sid)
            ?: return json(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", "workspace not found"))
        val files = JSONArray()
        val list = if (dir.exists() && dir.isDirectory) dir.listFiles()?.filter { it.isFile } else null
        list?.sortedByDescending { it.lastModified() }?.forEach { f ->
            files.put(JSONObject()
                .put("name", f.name)
                .put("size", f.length())
                .put("mtime", f.lastModified()))
        }
        return jsonOk(JSONObject()
            .put("path", dir.absolutePath)
            .put("files", files)
            .put("count", files.length()))
    }

    /** GET /api/sessions/{sid}/workspace/{filename} → 下载单文件。 */
    private fun downloadWorkspace(sid: String, rawName: String): NanoHTTPD.Response {
        val name = safeFilename(rawName)
            ?: return json(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid filename"))
        val dir = workspaceDir(sid)
            ?: return json(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", "workspace not found"))
        val file = File(dir, name)
        if (!file.exists() || !file.isFile) {
            return json(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "file not found"))
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return json(NanoHTTPD.Response.Status.INTERNAL_ERROR, JSONObject().put("error", "read failed"))
        val safeDisp = name.replace("\"", "\\\"")
        val resp = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/octet-stream",
            ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        resp.addHeader("Content-Disposition", "attachment; filename=\"$safeDisp\"")
        return resp
    }

    /**
     * POST /api/sessions/{sid}/workspace → 上传文件。
     * body: { filename, content_base64 }
     */
    private fun uploadWorkspace(sid: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = readJson(session)
        val rawName = body.optString("filename", "")
        val b64 = body.optString("content_base64", "")
        if (b64.isEmpty()) {
            return json(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", "empty content_base64"))
        }
        val name = safeFilename(rawName)
            ?: return json(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid filename"))
        val dir = workspaceDir(sid)
            ?: return json(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", "workspace not found"))
        if (!dir.exists() && !dir.mkdirs()) {
            return json(NanoHTTPD.Response.Status.INTERNAL_ERROR, JSONObject().put("error", "mkdir failed"))
        }
        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrElse {
            return json(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", "invalid base64"))
        }
        val file = File(dir, name)
        try {
            file.writeBytes(bytes)
        } catch (e: Exception) {
            return json(NanoHTTPD.Response.Status.INTERNAL_ERROR, JSONObject().put("error", "write failed: ${e.message}"))
        }
        return jsonOk(JSONObject().put("ok", true).put("name", name).put("size", bytes.size))
    }
}