package com.mcp.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mcp.LogStore
import com.mcp.serialization.McpJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * App 内嵌 WebView 浏览器（替代原 Chrome CDP 直连方案）。
 *
 * 为什么换：Chrome 的 localabstract:chrome_devtools_remote 在 AOSP SELinux 下
 * 不允许普通 App connect（只有 adbd 被放行），adb forward/reverse 又依赖电脑，
 * 对「手机上的 agent 自主调工具」不可行（详见 git 历史 67b1296 前的讨论）。
 * 改为 App 自带 WebView：进程内直接加载页面、evaluateJavascript 执行 JS、
 * onConsoleMessage 捕获 console、shouldInterceptRequest 记录网络。无 SELinux
 * 阻挡、无需 USB/电脑/root。代价：控制的是 App 内浏览器，不是系统 Chrome 已开页面。
 *
 * 按需初始化：首次调用工具时才创建首个 WebView 会话（不调用零开销）；
 * 支持多会话（对应工具里的 tab 参数），tab 越界或尚无会话时自动新建。
 *
 * WebView attach 到 MainActivity 的隐藏容器（browser_container，invisible）：
 * 部分设备/ROM 上 detached WebView（不挂到 ViewGroup）的 loadUrl 会静默无回调，
 * 导致导航永远等不到 onPageFinished；attach 后加载/渲染/截图均正常，且不可见
 * 不占用聊天界面。
 *
 * 线程模型：WebView 只能在主线程创建/操作（Android 限制），本类所有 WebView
 * 操作都切到 [Dispatchers.Main]；console/network 事件缓冲线程安全，任意线程可读。
 */
/** 会话用途：普通浏览 / Web 自动化。两者视图容器与操作入口完全隔离。 */
enum class SessionPurpose { NORMAL, AUTOMATION }

object WebBrowser {
    private const val TAG = "WebBrowser"
    private const val MAX_EVENT_BUFFER = 200
    private const val MAX_SESSIONS = 5

    /** flat 快照默认最多输出多少条（超出部分给省略提示 + max 参数指引），避免大页面吃满上下文。 */
    private const val SNAPSHOT_DEFAULT_MAX = 300

    /** 整页截图最多拼接多少屏（防止超长页面把内存打爆）。 */
    private const val MAX_FULLPAGE_SLICES = 20

    /** 页面 JS 弹窗等待 agent 答复的上限；超时按默认（取消）处理，避免页面永久卡死。 */
    private const val DIALOG_TIMEOUT_MS = 15_000L

    /** 无返回值语句的统一提示（实现与单测见 [BrowserInternals.NO_RETURN_HINT]）。 */
    private const val NO_RETURN_HINT = BrowserInternals.NO_RETURN_HINT

    /** 页面导航等待上限（onPageFinished 超时）。 */
    private const val NAV_TIMEOUT_MS = 30_000L

    /** 历史导航等待上限。SPA 的同文档历史不会触发 onPageFinished，靠 URL 变化提前收工。 */
    private const val HISTORY_TIMEOUT_MS = 8_000L

    /** evaluateJavascript 等待上限。 */
    private const val EVAL_TIMEOUT_MS = 20_000L

    /** 现代 Chrome Android UA，避免部分站点对默认 WebView UA 不兼容。 */
    private const val UA_OVERRIDE = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * 桌面 Chrome UA。
     *
     * 用途：部分站点（如智谱清言）会按 UA 把移动端重定向到功能受限的 mini 版；
     * 换成桌面 UA 即可拿到完整版界面。由 Web 自动化配置的 desktopMode 开关控制。
     */
    private const val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /**
     * 会话信息，供 browser_list_tabs 返回给 LLM。
     *
     * [index] 是该会话在**同用途池**里的下标，也是所有工具 `tab` 参数与 UI「窗口切换」统一使用的编号；
     * [id] 只是内部稳定标识（从 1 起、会因淘汰跳跃），**不要**当 tab 传。
     */
    data class TabInfo(
        val index: Int,
        val id: Int,
        val title: String,
        val url: String
    )

    /** 单个 WebView 会话。view 字段仅主线程访问；标题/URL 为跨线程快照。 */
    private class Session(
        val id: Int,
        val view: WebView,
        /** 会话用途：普通浏览 / Web 自动化。两者视图容器与操作入口完全隔离。 */
        val purpose: SessionPurpose = SessionPurpose.NORMAL,
    ) {
        @Volatile var title: String = ""
        @Volatile var url: String = ""
        @Volatile var loadError: String? = null

        /** 最近一次被工具/UI 选中使用的时刻（单调时钟），供会话淘汰按「最久未用」挑选牺牲者。 */
        @Volatile var lastUsedAt: Long = android.os.SystemClock.elapsedRealtime()

        /**
         * 该页面是否被站点 CSP 沙箱禁了脚本（如 raw.githubusercontent.com 的
         * `CSP: sandbox` 响应头）。判定依据：evaluateJavascript 回调 "null" 且
         * 探针也失败。置位后 extract 走 HTTP 直连兜底，evaluate 给出明确指引。
         */
        @Volatile var scriptBlocked: Boolean = false

        /** 是否正处于「整文档加载」中（onPageStarted→onPageFinished）。同文档导航（SPA）不会置位。 */
        @Volatile var docLoading: Boolean = false

        /** 等待中的一次导航（onPageFinished 时完成）。同一时刻至多一个。 */
        @Volatile var pendingLoad: CompletableDeferred<String>? = null

        fun beginLoad(url: String): CompletableDeferred<String> {
            val d = CompletableDeferred<String>()
            pendingLoad = d
            return d
        }

        fun notifyPageFinished(finishedUrl: String) {
            pendingLoad?.let {
                pendingLoad = null
                it.complete(finishedUrl)
            }
        }
    }

    @Volatile private var started = false

    /** 创建 WebView 用的 appContext（ensureStarted 时保存；仅主线程读写）。 */
    @Volatile private var appContext: Context? = null

    /** 只用于 status 的会话数快照（sessions 本体仅主线程读写）。 */
    @Volatile private var sessionCount = 0

    /** WebView attach 宿主（MainActivity 隐藏容器；主线程读写）。 */
    @Volatile private var container: ViewGroup? = null

    /**
     * Web 自动化会话的独立容器。
     *
     * 与 [container] 分离的意义：普通会话挂在浏览器 Tab 的隐藏容器里，
     * 自动化会话挂在自己的浮窗容器里——两边视图互不干扰，
     * 用户看浏览器时不会看到自动化在操作，自动化也不会抢走用户正在看的页面。
     */
    @Volatile private var automationContainer: ViewGroup? = null

    /**
     * **全局 UA 模式**（桌面 / 移动）。
     *
     * 作用于**所有**会话：内嵌浏览器 Tab 与自动化会话共用同一套 UA。
     *
     * 旧实现只在"新建自动化会话"时读这个开关，并靠"销毁旧会话、下次重建"生效：
     * 于是开关改了当前页面还是旧 UA，紧接着的导航也常常抢在重建之前用旧 UA 发出去，
     * 用户看到的现象就是「开关没影响 UA」。
     */
    @Volatile private var desktopUaMode = false

    /**
     * 切换全局 UA 模式并**立即生效**：直接改活会话的 UA，并重载已加载的 http 页面。
     *
     * WebView 允许运行时改 `settings.userAgentString`（对后续请求生效），所以不再销毁重建。
     * @return 面向界面/日志的结果描述
     */
    suspend fun setDesktopUaMode(desktop: Boolean): String = withContext(Dispatchers.Main) {
        val changed = desktopUaMode != desktop
        desktopUaMode = desktop
        val ua = if (desktop) UA_DESKTOP else UA_OVERRIDE
        var applied = 0
        var reloaded = 0
        sessions.forEach { s ->
            runCatching {
                s.view.settings.userAgentString = ua
                applied++
                if (changed && s.url.startsWith("http")) {
                    s.view.reload()
                    reloaded++
                }
            }
        }
        LogStore.i(TAG, "全局 UA 模式：desktop=$desktop，生效 $applied 个会话，重载 $reloaded 个")
        if (changed) {
            "UA 模式已切换为${if (desktop) "桌面" else "移动"} UA：$applied 个会话已生效，$reloaded 个已重新加载。"
        } else {
            "UA 模式未变（当前${if (desktop) "桌面" else "移动"} UA），已确保 $applied 个会话生效。"
        }
    }

    /** 最近活动会话的 URL（cookies 等非主线程读取用；主线程更新）。 */
    @Volatile private var lastActiveUrl: String? = null

    /**
     * 页面状态变化监听（主线程回调）：参数为当前主框架 URL 与会话用途。
     * 供 MainActivity 切换「主页态（居中地址栏）/浏览态（页面全屏）」。
     * **订阅方必须自己按 purpose 过滤**，否则自动化会话的导航会把浏览器 Tab 的界面状态带跑。
     */
    @Volatile var onPageChanged: ((String, SessionPurpose) -> Unit)? = null

    /**
     * 页面状态变化的「附加」监听（主线程回调）：浏览器浮窗标题等外部 UI 用。
     * 与 [onPageChanged] 并行触发、互不覆盖——MainActivity 的主页态/浏览态切换不能被顶掉。
     */
    @Volatile var onPageChangedExtra: ((String, SessionPurpose) -> Unit)? = null

    /** 开发者工具（Eruda 抓包/调试面板）是否启用。默认关闭——不注入、不劫持，
     * 页面干净加载；用户点浏览器 Tab 的「开发者工具」开关或 agent 调 browser_devtools 开启后，
     * 当前页面立即注入、后续页面自动提前注入。符合「开始抓包才抓包」。 */
    @Volatile var devToolsEnabled: Boolean = false
        private set

    /** 开启/关闭开发者工具。开启时对已有会话的当前页面立即注入。任意线程可调。 */
    fun setDevToolsEnabled(enabled: Boolean) {
        if (devToolsEnabled == enabled) return
        devToolsEnabled = enabled
        LogStore.i(TAG, "开发者工具（Eruda 抓包/调试）${if (enabled) "已开启" else "已关闭"}")
        if (enabled) {
            mainHandler.post {
                sessions.forEach { s -> runCatching { injectEruda(s.view) } }
            }
        }
    }

    /**
     * 显示/隐藏 Eruda 调试面板（FAB 菜单「开发者工具」用）。
     * 与 [setDevToolsEnabled] 的区别：这里是打开/收起面板 UI；注入开关仍由 AI
     * 的 browser_devtools 控制。若尚未注入，先注入（当前页立即生效、后续页自动），
     * 然后轮询等待 Eruda 就绪再 show/hide。
     */
    fun showDevToolsPanel(show: Boolean) {
        // 明确要看面板 = 需要注入：这里顺带打开开关（用户菜单与 AI 的 browser_devtools 都走这条路）。
        // 想关掉抓包用菜单长按或 browser_devtools(on=false)。
        if (!devToolsEnabled) setDevToolsEnabled(true)
        mainHandler.post {
            sessions.forEach { s -> showErudaOn(s.view, show, 0) }
        }
    }

    /** 轮询等待 Eruda 就绪后执行 show/hide（注入是异步的，页面需先加载 eruda）。 */
    private fun showErudaOn(view: WebView, show: Boolean, attempt: Int) {
        if (attempt > 20) { // 约 6 秒仍未就绪则放弃
            LogStore.w(TAG, "Eruda 未就绪，无法${if (show) "展开" else "收起"}面板")
            return
        }
        runCatching {
            view.evaluateJavascript(
                "if(window.eruda){window.eruda.${if (show) "show()" else "hide()"};'ok'}else{'no'}",
                ValueCallback { value ->
                    if (value?.contains("no") == true) {
                        mainHandler.postDelayed({ showErudaOn(view, show, attempt + 1) }, 300)
                    }
                }
            )
        }
    }

    // （原 attachContainer 已删除：0 调用者，容器统一由 reattachContainer / attachAutomationContainer 设置。）

    /**
     * 绑定 Web 自动化会话的容器（自动化浮窗的宿主 View）。
     *
     * 与 [attachContainer] 并行存在：普通会话与自动化会话各挂各的，
     * 这样用户看浏览器 Tab 时不会看到自动化在操作页面。
     */
    fun attachAutomationContainer(container: ViewGroup) {
        this.automationContainer = container
        // 已有自动化会话迁移到新容器
        mainHandler.post {
            sessions.filter { it.purpose == SessionPurpose.AUTOMATION }.forEach { s ->
                (s.view.parent as? ViewGroup)?.removeView(s.view)
                container.addView(s.view, 0)
                forceRedraw(s.view)
            }
        }
    }

    // ── Web 自动化专用入口（只操作 AUTOMATION 会话）────────────

    /** 确保自动化会话已创建（幂等）。容器判定见 [ensureStartedFor]（只看自动化自己的容器）。 */
    suspend fun ensureAutomationStarted(context: Context): String? =
        ensureStartedFor(context, SessionPurpose.AUTOMATION)

    /** 自动化会话导航（不影响普通会话）。 */
    suspend fun navigateAutomation(url: String): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(null, null, SessionPurpose.AUTOMATION)
        s.loadError = null
        s.url = url
        val d = s.beginLoad(url)
        runCatching { s.view.loadUrl(url) }.onFailure {
            s.loadError = it.message
            return@withContext "导航失败：${it.message}"
        }
        val finished = withTimeoutOrNull(NAV_TIMEOUT_MS) { d.await() }
        if (finished != null && s.loadError == null) "已导航到 ${s.url}" else "导航未完成：${s.url}"
    }

    /**
     * 把自动化会话从当前容器摘下来（不销毁会话）。
     *
     * 用途：自动化浮窗关闭时，承载它的 overlay 容器会被移除。
     * 若 WebView 还挂在那里，会随父容器一起变成「孤儿」——
     * 下次重新挂载时状态异常（表现为浮窗空白/无法唤醒）。
     * 这里主动摘到无容器状态，WebView 本身仍存活可继续工作。
     */
    fun detachAutomationContainer() {
        mainHandler.post {
            sessions.filter { it.purpose == SessionPurpose.AUTOMATION }.forEach { s ->
                (s.view.parent as? ViewGroup)?.removeView(s.view)
            }
            automationContainer = null
            LogStore.d(TAG, "自动化会话已从容器摘下（会话保留）")
        }
    }

    /** 自动化会话当前 URL。 */
    suspend fun currentAutomationUrl(): String = withContext(Dispatchers.Main) {
        sessions.firstOrNull { it.purpose == SessionPurpose.AUTOMATION }?.url.orEmpty()
    }

    /**
     * 自动化页面是否已在该站点上（按 **scheme+host** 判断，**不比路径**）。
     *
     * 站点选中某个会话后 URL 会变成会话地址（/chat/<id> 等），若按「siteUrl 前缀」比较，
     * 发消息/弹浮窗时会把用户从选定的会话里导航回 siteUrl（新会话页）——
     * 表现就是「选了会话还新建会话/刷新网页」。只有不在该站点（空白页/别的站）才需要导航。
     */
    fun automationOnSite(siteUrl: String): Boolean {
        if (siteUrl.isBlank()) return false
        val cur = sessions.firstOrNull { it.purpose == SessionPurpose.AUTOMATION }?.url.orEmpty()
        if (cur.isBlank() || cur == "about:blank") return false
        return runCatching {
            val a = java.net.URI(cur)
            val b = java.net.URI(siteUrl)
            a.scheme == b.scheme && (a.host ?: "").equals(b.host ?: "", ignoreCase = true)
        }.getOrDefault(cur.startsWith(siteUrl.trimEnd('/')))
    }

    /**
     * 自动化 WebView 最近一次「页面加载完成」的时间戳（ms；0 = 从未完成）。
     * onPageFinished 与 SPA 同文档导航（doUpdateVisitedHistory）都会刷新——
     * 会话页初次打开要「等页面彻底加载好再刷新一次」就等这个信号。
     */
    @Volatile
    var automationPageFinishedAt: Long = 0L
        private set

    /**
     * 等待自动化页面彻底加载完成（suspend）。
     *
     * 已经加载完成过 → 立即返回；否则等下一次 onPageFinished / SPA 导航完成，
     * 超时兜底返回（调用方照常去读，读不到会给出明确原因）。
     */
    suspend fun awaitAutomationPageLoaded(timeoutMs: Long = 15_000) {
        if (automationPageFinishedAt > 0L) return
        withTimeoutOrNull(timeoutMs) {
            while (automationPageFinishedAt <= 0L) delay(200)
        }
    }

    /**
     * 网页会话列表里的一项（Web 自动化的会话列表以**网页**为准，本地只做记录）。
     *
     * [siteKey] 是站点的稳定标识（data-id / href 里的 cid 等）；站点没有稳定 id 时为空串，
     * 由上层回退用 [title] 做绑定键。**不要用 [index] 做绑定**——侧边栏会重排，下标会漂移。
     */
    data class WebSessionItem(val index: Int, val title: String, val active: Boolean, val siteKey: String = "")

    /**
     * 读取网页会话列表的结果。
     *
     * [error] 非 null 表示读取失败（页面未就绪 / 选择器失配 / 未登录），UI 可据此给出可执行提示；
     * [items] 为空且 error 为 null 表示读到了但没有条目。
     */
    data class WebSessionList(val items: List<WebSessionItem>, val error: String? = null)

    /**
     * 读取自动化会话（网页）里的会话列表——源头是站点自己的侧边栏，本地 JSON 只作记录。
     *
     * @param containerSelector 设置页「会话列表（选择器）」填的容器选择器
     */
    suspend fun readAutomationSessions(containerSelector: String): WebSessionList = withContext(Dispatchers.Main) {
        val s = sessions.firstOrNull { it.purpose == SessionPurpose.AUTOMATION }
            ?: return@withContext WebSessionList(emptyList(), "还没有自动化会话：请先在浏览器页弹出「自动化窗口」并登录站点。")
        if (s.url.isEmpty() || s.url == "about:blank") {
            return@withContext WebSessionList(emptyList(), "自动化窗口还是空白页：请先在浮窗里打开站点并登录。")
        }
        if (containerSelector.isBlank()) {
            return@withContext WebSessionList(emptyList(), "未配置「会话列表（选择器）」，无法读取网页会话。")
        }
        val raw = evalJs(s, buildJs(SESSION_LIST_JS, jsonStringLiteral(containerSelector)))
            ?: return@withContext WebSessionList(emptyList(), "读取网页会话超时（页面可能正在加载）。")
        val obj = unwrapEvalResult(raw) as? JsonObject
            ?: return@withContext WebSessionList(emptyList(), "网页返回的内容无法解析：${raw.take(120)}")
        obj["error"]?.jsonPrimitive?.contentOrNull?.let { return@withContext WebSessionList(emptyList(), it) }
        val arr = obj["items"] as? JsonArray ?: return@withContext WebSessionList(emptyList(), "网页没有返回会话数组。")
        val items = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val idx = o["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapNotNull null
            WebSessionItem(
                index = idx,
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                active = o["active"]?.jsonPrimitive?.contentOrNull == "true",
                siteKey = o["siteKey"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
        WebSessionList(items)
    }

    /**
     * 点击网页会话列表里的第 [index] 条，把站点切到那条会话。
     *
     * [title] 是 app 侧列表快照里该条的标题：站点侧边栏会随时重排（当前会话上浮、自动化新增会话），
     * 点击瞬间 index 可能已指向别的条目——标题能对上才点，对不上报错让用户刷新，
     * 避免出现「点了 A 却进了 B」。传空串则退化为纯 index 点击（程序化兜底场景）。
     */
    suspend fun switchAutomationSession(containerSelector: String, siteKey: String = "", index: Int = -1, title: String = ""): String = withContext(Dispatchers.Main) {
        val s = sessions.firstOrNull { it.purpose == SessionPurpose.AUTOMATION }
            ?: return@withContext "还没有自动化会话：请先弹出「自动化窗口」。"
        if (containerSelector.isBlank()) return@withContext "未配置「会话列表（选择器）」，无法切换网页会话。"
        // 优先按稳定 siteKey 定位；无 key 时才回退 index+title。
        // 稳定 key 命中不了 → JS 直接报错不点（宁可不切，也不切进错误会话）。
        val raw = evalJs(s, buildJs(SESSION_CLICK_JS, jsonStringLiteral(containerSelector), index.toString(), jsonStringLiteral(title), jsonStringLiteral(siteKey)))
            ?: return@withContext "切换网页会话超时。"
        val obj = unwrapEvalResult(raw) as? JsonObject
            ?: return@withContext "切换结果无法解析：${raw.take(120)}"
        obj["error"]?.jsonPrimitive?.contentOrNull?.let { return@withContext "切换失败：$it" }
        "已切换到网页会话「${obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()}」"
    }

    /**
     * 自动化会话的三个动作入口：与 browser_* 工具**共用同一套 JS**
     * （[CLICK_JS] / [TYPE_JS] / [PRESS_KEY_JS]），避免驱动自己再写一份解析逻辑后各自漂移。
     */
    suspend fun clickAutomation(selector: String): String? =
        evalRawAutomation(buildJs(CLICK_JS, jsonStringLiteral(selector)))

    suspend fun typeAutomation(selector: String, text: String): String? =
        evalRawAutomation(buildJs(TYPE_JS, jsonStringLiteral(selector), jsonStringLiteral(text)))

    suspend fun pressEnterAutomation(): String? =
        evalRawAutomation(buildJs(PRESS_KEY_JS, jsonStringLiteral("Enter"), keyCodeOf("Enter").toString()))

    /** 在自动化会话上执行 JS 并返回原始结果。 */
    suspend fun evalRawAutomation(js: String): String? = withContext(Dispatchers.Main) {
        val s = sessions.firstOrNull { it.purpose == SessionPurpose.AUTOMATION } ?: return@withContext null
        if (s.url.isEmpty()) return@withContext null
        try {
            withTimeoutOrNull(EVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    s.view.evaluateJavascript(js) { result -> cont.resume(result ?: "null") }
                }
            }
        } catch (e: Exception) {
            LogStore.w(TAG, "evalRawAutomation 失败: ${e.message}")
            null
        }
    }

    /**
     * 把已有会话的 WebView 整体迁移到新容器（浏览器「全屏 ⇄ 浮窗」互转用）。
     *
     * 为什么必须单独有这个方法：一个 View 只能有一个父容器，[attachContainer] 只改
     * 「以后新建会话挂哪里」，不会搬动已有 WebView——直接改引用会出现
     * 「新容器空白、旧容器还挂着 WebView」的错位。这里在主线程先摘后挂，
     * 并保持各会话原有的可见性（同一时刻仍只有当前会话可见）。
     *
     * **只搬普通浏览会话**：自动化会话有自己的容器（[attachAutomationContainer]），
     * 若一起搬走会出现「自动化浮窗空白、用户小窗里出现模型正在操作的页面」。
     */
    fun reattachContainer(newContainer: ViewGroup) {
        mainHandler.post {
            if (container === newContainer) return@post
            container = newContainer
            var moved = 0
            sessions.filter { it.purpose == SessionPurpose.NORMAL }.forEach { s ->
                val v = s.view
                (v.parent as? ViewGroup)?.removeView(v)
                newContainer.addView(v, 0) // index 0：WebView 在底层，卡片/工具栏浮层在上
                forceRedraw(v)             // 重新挂载后强制出帧，避免"黑屏直到触摸"
                moved++
            }
            LogStore.d(TAG, "WebView 容器已迁移（普通会话 $moved 个，自动化会话不参与）")
        }
    }

    private val sessionIdGen = AtomicInteger(0)
    private val sessions = ArrayList<Session>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val consoleLock = Any()
    private val networkLock = Any()
    private val consoleMessages = ArrayDeque<String>()
    private val networkEvents = ArrayDeque<String>()

    private val downloadLock = Any()
    private val downloadResults = ArrayDeque<String>()

    /** 下载/上传等后台动作的作用域（与工具调用生命周期解耦）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 待答复的页面 JS 弹窗（alert/confirm/prompt）。 */
    private class PendingDialog(
        val type: String,
        val message: String,
        val result: android.webkit.JsResult,
        val defaultValue: String?
    )

    @Volatile private var pendingDialog: PendingDialog? = null

    /** 下一次文件选择器要用的本地文件（由 [uploadFile] 设置，[onShowFileChooser] 消费）。 */
    @Volatile private var pendingUploadPaths: List<String>? = null

    /** 最近一次页面弹窗的描述（没有则返回 null）。 */
    fun pendingDialogInfo(): String? = pendingDialog?.let { "类型=${it.type}｜内容=${it.message}" }

    /**
     * 答复待处理的页面 JS 弹窗。
     *
     * 为什么必须有这个：本 WebView 用 application context 构造，弹不出系统对话框，
     * 旧实现里 `confirm()` 恒返回 false——"确认删除/确认提交"类页面点了没反应。
     * @return true=确实有待处理弹窗并已答复
     */
    fun answerDialog(accept: Boolean, promptText: String?): Boolean {
        val d = pendingDialog ?: return false
        pendingDialog = null
        mainHandler.post {
            runCatching {
                if (d.type == "prompt") (d.result as? android.webkit.JsPromptResult)?.confirm(promptText ?: d.defaultValue ?: "")
                else if (accept) d.result.confirm() else d.result.cancel()
            }
        }
        LogStore.i(TAG, "页面弹窗已答复：${d.type} accept=$accept")
        return true
    }

    /** 挂起一个页面弹窗等待答复；同一时刻只留一个（新的来先把旧的取消，避免页面永久卡死）。 */
    private fun offerDialog(type: String, message: String, result: android.webkit.JsResult, defaultValue: String?) {
        pendingDialog?.let { old -> runCatching { old.result.cancel() } }
        pendingDialog = PendingDialog(type, message, result, defaultValue)
        LogStore.w(TAG, "页面弹窗待处理（$type）：$message")
        mainHandler.postDelayed({
            val cur = pendingDialog
            if (cur != null && cur.result === result) {
                pendingDialog = null
                runCatching { cur.result.cancel() }
                LogStore.w(TAG, "弹窗超时未答复，已按默认取消处理：$message")
            }
        }, DIALOG_TIMEOUT_MS)
    }

    /** 最近 N 条下载结果（新→旧）。 */
    fun downloadsSnapshot(limit: Int): List<String> = synchronized(downloadLock) {
        downloadResults.take(limit)
    }

    private fun recordDownload(msg: String) {
        synchronized(downloadLock) {
            downloadResults.addFirst(msg)
            while (downloadResults.size > MAX_EVENT_BUFFER) downloadResults.removeLast()
        }
    }

    /** 直接下载一个 URL（页面链接点不动、或拿到直链时用）。 */
    suspend fun downloadUrl(url: String): String =
        performDownload(url, UA_OVERRIDE, null, null, -1L)

    /**
     * 落盘下载：与 WebView 共享 cookie，存到 filesDir/browser/downloads。
     * 结果同时写入下载缓冲（browser_download action=list 可查），失败也如实记录。
     */
    private suspend fun performDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ): String = withContext(Dispatchers.IO) {
        val result = runCatching {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                return@runCatching "下载失败：不支持的地址 $url"
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", userAgent ?: UA_OVERRIDE)
                instanceFollowRedirects = true
                runCatching { CookieManager.getInstance().getCookie(url) }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return@runCatching "下载失败：HTTP $code $url"
                val dir = File(appContext?.filesDir ?: return@runCatching "下载失败：浏览器未初始化", "browser/downloads")
                dir.mkdirs()
                val file = File(dir, guessFileName(url, contentDisposition))
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                "下载完成：${file.absolutePath}（${file.length()} 字节，${mimeType ?: conn.contentType ?: "?"}）"
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrElse { "下载失败：$url → ${it.message ?: it.javaClass.simpleName}" }
        recordDownload(result)
        result
    }

    /** 从 Content-Disposition 或 URL 末段猜一个安全文件名（实现见 [BrowserInternals.guessFileName]）。 */
    private fun guessFileName(url: String, contentDisposition: String?): String =
        BrowserInternals.guessFileName(url, contentDisposition)

    // ── 初始化（懒加载）──────────────────────────────────────

    /**
     * 确保已创建普通浏览会话。幂等：已就绪时直接返回 null（零开销）。
     *
     * @return null=就绪；非 null=友好错误信息（如系统 WebView 不可用）。
     */
    suspend fun ensureStarted(context: Context): String? = ensureStartedFor(context, SessionPurpose.NORMAL)

    /**
     * 确保指定用途的浏览器已就绪（幂等）。
     *
     * **按用途判断容器**：自动化会话有自己的浮窗容器，不该被"普通浏览器容器还没就绪"卡住——
     * 旧实现里 ensureAutomationStarted 先走 ensureStarted，于是从没打开过浏览器页时，
     * 自动化永远起不来（报的还是一句跟自动化无关的"容器未就绪"）。
     */
    private suspend fun ensureStartedFor(context: Context, purpose: SessionPurpose): String? =
        withContext(Dispatchers.Main) {
            try {
                val appCtx = context.applicationContext
                appContext = appCtx // 先存：没有容器也要能初始化自动化会话
                // 接受 cookie
                runCatching { CookieManager.getInstance().setAcceptCookie(true) }
                if (sessions.any { it.purpose == purpose }) {
                    started = true
                    return@withContext null
                }
                val box = containerOf(purpose)
                if (box == null) {
                    // 没有容器就别建会话：detached WebView 的 loadUrl 在部分 ROM 上静默无回调，
                    // 表现出来是「导航一直超时」这种极难排查的故障，不如直接说清楚。
                    return@withContext if (purpose == SessionPurpose.AUTOMATION) {
                        "自动化浮窗容器未就绪：请先在「浏览器」页用菜单弹出「自动化窗口」，再使用 Web 自动化后端。"
                    } else {
                        "浏览器视图容器未就绪（MainActivity 未创建或已销毁）。" +
                            "请先在 App 里打开「浏览器」页（或把 App 切到前台）再调用 browser_* 工具。"
                    }
                }
                val view = WebView(appCtx)
                configure(view)
                box.addView(view, 0) // index 0：WebView 在底层，工具栏/浮层在上
                val s = Session(sessionIdGen.incrementAndGet(), view, purpose)
                sessions.add(s)
                // 新建的自动化 WebView 是空白页：清掉旧「加载完成」时间戳，
                // 避免等加载完成的调用方被旧信号立即放行（页面其实还是空白）
                if (purpose == SessionPurpose.AUTOMATION) automationPageFinishedAt = 0L
                attachHomeBridge(view, s)
                started = true
                sessionCount = sessions.size
                val ver = runCatching {
                    if (Build.VERSION.SDK_INT >= 26) {
                        WebView.getCurrentWebViewPackage()?.versionName ?: "?"
                    } else "?"
                }.getOrDefault("?")
                LogStore.i(TAG, "内嵌浏览器已启动（${if (purpose == SessionPurpose.AUTOMATION) "自动化" else "普通"}会话，系统 WebView $ver）")
                null
            } catch (e: Exception) {
                LogStore.e(TAG, "WebView 初始化失败: ${e.stackTraceToString()}")
                "内嵌浏览器初始化失败：${e.message ?: e.javaClass.simpleName}。请确认系统 WebView 可用。"
            }
        }

    /**
     * 诊断状态（不触发初始化；与 proot_status 对齐）。
     *
     * 会切到主线程读会话（sessions 只能在主线程访问），并顺带报出：
     * 各用途会话数、当前会话 URL/标题、待处理弹窗、下载条数、等待答复的对话框等真正排障需要的信息。
     */
    suspend fun status(): String = withContext(Dispatchers.Main) {
        val ver = runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                WebView.getCurrentWebViewPackage()?.versionName ?: "未知"
            } else "API<26（无法查询）"
        }.getOrDefault("未知")
        val normal = sessions.filter { it.purpose == SessionPurpose.NORMAL }
        val automation = sessions.filter { it.purpose == SessionPurpose.AUTOMATION }
        val dl = synchronized(downloadLock) { downloadResults.size }
        buildString {
            append("内嵌浏览器状态：\n")
            append("- 就绪: $started\n")
            append("- 普通会话: ${normal.size} 个（编号 0…${(normal.size - 1).coerceAtLeast(0)}）\n")
            append("- 自动化会话: ${automation.size} 个\n")
            normal.firstOrNull()?.let { append("- 当前页: ${it.title.ifEmpty { "<无标题>" }} — ${it.url}\n") }
            append("- 待处理弹窗: ${pendingDialogInfo() ?: "无"}\n")
            append("- 待上传文件: ${pendingUploadPaths?.size ?: 0} 个\n")
            append("- 下载记录: $dl 条（browser_download action=list 查看）\n")
            append("- 系统 WebView: $ver\n")
            append("- 开发者工具: ${if (devToolsEnabled) "开启（Eruda 抓包/调试中）" else "关闭（默认，不注入不抓包）"}\n")
            append("- 说明: 无需 Chrome/USB/电脑/root；首次调用 browser_* 工具时自动初始化（按需，不用不占资源）；" +
                "开发者工具由 AI 用 browser_devtools 开关；CSP 沙箱页会自动转 HTTP 直连提取")
        }
    }

    // ── 会话操作（供工具调用）────────────────────────────────

    /**
     * 列出会话（默认只列普通浏览会话，与工具 `tab` 参数的取值口径一致）。
     *
     * [index] 是**同用途池**内的下标：工具传 `tab=N`、UI 选第 N 项，指向的都是同一个会话。
     */
    suspend fun listTabs(purpose: SessionPurpose = SessionPurpose.NORMAL): List<TabInfo> =
        withContext(Dispatchers.Main) {
            sessions.filter { it.purpose == purpose }
                .mapIndexed { i, s ->
                    TabInfo(i, s.id, s.title.ifEmpty { "<无标题>" }, s.url)
                }
        }

    /** 当前会话的页面标题（主线程调用；无会话/无标题返回空串）。默认只看普通浏览会话。 */
    fun currentTitle(purpose: SessionPurpose = SessionPurpose.NORMAL): String =
        sessions.firstOrNull { it.purpose == purpose }?.title.orEmpty()

    /**
     * 切换 UI 显示的会话：把对应 WebView 置为可见，**同用途**其余会话隐藏。
     * 供浏览器「窗口切换」菜单使用；[index] 是同用途池下标。任意线程可调。
     */
    fun switchSession(index: Int, purpose: SessionPurpose = SessionPurpose.NORMAL) {
        mainHandler.post {
            val pool = sessions.filter { it.purpose == purpose }
            if (index < 0 || index >= pool.size) return@post
            pool.forEachIndexed { i, s ->
                s.view.visibility = if (i == index) View.VISIBLE else View.GONE
            }
            pool.getOrNull(index)?.lastUsedAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    /**
     * 导航到 [url]，等待页面加载完成（onPageFinished，超时 [NAV_TIMEOUT_MS]）。
     * [tab] / [urlFilter] 选择目标会话，无匹配或越界时自动新建会话。
     * @return 面向 LLM 的导航结果描述。
     */
    suspend fun navigate(url: String, tab: Int?, urlFilter: String?): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        s.loadError = null
        s.url = url // loadUrl 前立即记录目标，即使回调延迟也不误判「无页面」
        val d = s.beginLoad(url)
        runCatching { s.view.loadUrl(url) }
            .onFailure {
                s.loadError = it.message
                return@withContext "导航失败：${it.message}"
            }
        val finishedUrl = withTimeoutOrNull(NAV_TIMEOUT_MS) { d.await() }
        val title = s.title.ifEmpty { "<无标题>" }
        val base = when {
            finishedUrl != null && s.loadError == null ->
                "已导航到 ${s.url}\n页面标题：$title"
            s.loadError != null ->
                "页面加载失败：${s.loadError}\n目标：${s.url}"
            else ->
                "页面加载超时（${NAV_TIMEOUT_MS / 1000} 秒未完成）：${s.url}\n可能原因：网络慢、页面 JS 持续加载，或站点拒绝访问。可稍后用 browser_status / browser_snapshot 查看实际状态。"
        }
        appendPageState(s, base)
    }

    /**
     * 在当前会话页面执行 JS 表达式并返回结果。
     * @return 面向 LLM 的结果描述；JS 抛出异常时返回异常文本。
     */
    suspend fun evaluate(
        js: String,
        tab: Int?,
        urlFilter: String?,
        start: Int = 0,
        maxLen: Int = 5000
    ): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        if (s.url.isEmpty()) return@withContext "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        scriptBlockedMsg(s, "执行 JS")?.let { return@withContext it }
        val raw = try {
            withTimeoutOrNull(EVAL_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    s.view.evaluateJavascript(wrapEval(js, start, maxLen)) { result -> cont.resume(result ?: "null") }
                }
            }
        } catch (e: Exception) {
            return@withContext "JS 执行异常：${e.message ?: e.javaClass.simpleName}"
        } ?: return@withContext "JS 执行超时（${EVAL_TIMEOUT_MS / 1000} 秒）"
        if (raw.trim() == "null") {
            // 多语句被塞进 return (...) 会在**解析期**失败，try/catch 接不到、回调只给 null。
            // 这里做一次同上下文编译探针，把误导性的「结果：null」换成能自愈的提示。
            val probe = evalJs(s, syntaxProbeJs(js))
            val inner = (unwrapEvalResult(probe) as? JsonPrimitive)?.contentOrNull ?: probe?.trim('"').orEmpty()
            if (inner.startsWith("syntax:")) {
                return@withContext "JS 语法错误：${inner.removePrefix("syntax:")}\n" +
                    "提示：本工具只接受**单个表达式**；多语句请包成 IIFE：(function(){ …; return 结果; })()。" +
                    "例如 (function(){ document.querySelector('#x').click(); return document.title; })()"
            }
        }
        formatJsResult(raw)
    }

    // （原 evalRaw 已删除：0 调用者。自动化驱动走 evalRawAutomation，模型侧走 evaluate。）

    /** 当前会话的页面 URL（驱动用于判断是否已在目标站点）。 */
    suspend fun currentUrl(): String = withContext(Dispatchers.Main) {
        sessions.firstOrNull()?.url.orEmpty()
    }

    /**
     * 给用户 JS 包一层 try/catch（不使用 eval，规避 CSP unsafe-eval 限制）：
     * - 用户 JS 抛异常 → 返回 {__error:...}，工具明确提示「JS 执行异常」而非误导性 null/undefined
     * - 表达式（document.title、对象/字符串字面量、IIFE）→ 直接作为表达式求值，字符串化后按
     *   [start]/[maxLen] 分段返回 {total, content}，长内容可分段读取完整
     * - 无返回值语句（.click()/.submit() 等）→ 返回 undefined，工具提示「无返回值」
     *
     * 为什么不用 eval：GitHub 等站点 CSP 设 `script-src github.githubassets.com`，
     * 禁止 `unsafe-eval`，运行时 eval("...") 会被拒绝。改把用户 JS 作为表达式直接嵌入
     * 脚本（evaluateJavascript 是 WebView 特权注入，不触发 CSP），多语句请用 IIFE：
     * (function(){ ...; return ...; })()
     */
    private fun wrapEval(js: String, start: Int, maxLen: Int): String {
        val s = start.coerceAtLeast(0)
        val m = maxLen.coerceIn(100, 20000)
        return "(function(){try{" +
            "var __r=(function(){return ($js)})(); " +
            // undefined 必须用对象标记回传：直接 return 'undefined' 会被 WebView 再编码一层引号，
            // Kotlin 侧永远匹配不上「无返回值」分支（历史上这条提示一次都没出现过）。
            "if(__r===undefined){return JSON.stringify({__undefined:true})}" +
            "var __s=(typeof __r==='string')?__r:JSON.stringify(__r);" +
            "if(__s===undefined){return JSON.stringify({__undefined:true})}" +
            "return JSON.stringify({total:__s.length,content:__s.substr($s,$m)})" +
            "}catch(__e){return JSON.stringify({__error:(__e&&__e.message)||String(__e)})}})()"
    }

    // ── 完整开发者工具（DOM 快照 / 交互 / 历史 / 截图 / 存储）──

    /**
     * DOM 快照：收集页面上所有可交互元素并编号（存 window.__browserSnapshot，
     * 供 click/type/highlight 用 #编号 引用），返回编号 + 标签 + 文本列表。
     */
    suspend fun snapshot(tab: Int?, urlFilter: String?, max: Int? = null): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        if (s.url.isEmpty()) return@withContext "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        scriptBlockedMsg(s, "DOM 快照")?.let { return@withContext it }
        val raw = evalJs(s, SNAPSHOT_JS) ?: return@withContext "DOM 快照执行超时"
        val arr = unwrapEvalResult(raw) as? JsonArray
            ?: return@withContext "DOM 快照解析失败（返回非数组），前 200 字符：${raw.take(200)}"
        if (arr.isEmpty()) return@withContext "页面没有可交互元素（可能还在加载，稍后重试）。"
        val limit = (max ?: SNAPSHOT_DEFAULT_MAX).coerceIn(1, 1000)
        buildString {
            append("可交互元素（共 ${arr.size} 个，显示前 ${minOf(limit, arr.size)} 个；编号 #N 即 browser_click / browser_type / browser_highlight 的 ref）：\n")
            arr.take(limit).forEachIndexed { i, el ->
                val o = el.jsonObject
                val tag = o["tag"]?.jsonPrimitive?.contentOrNull ?: "?"
                val text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val href = o["href"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val label = when {
                    text.isNotEmpty() -> text
                    href.isNotEmpty() -> href
                    type.isNotEmpty() -> type
                    else -> "<无文本>"
                }
                append("#$i <$tag> $label\n")
            }
            if (arr.size > limit) {
                append("…还有 ${arr.size - limit} 个元素未显示（默认最多 $SNAPSHOT_DEFAULT_MAX 条，可用 max 参数提高，上限 1000）\n")
            }
        }
    }


    // ── browser-use 缝合：新能力 ─────────────────────────────

    /** 层级 DOM 树快照（browser-use 风格）：缩进层级 + xpath + ARIA 角色。
     *  [max] 限制输出条数（默认 200）。编号与 [snapshot] 完全一致，可互操作。 */
    suspend fun snapshotTree(tab: Int?, urlFilter: String?, max: Int?): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        if (s.url.isEmpty()) return@withContext "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        scriptBlockedMsg(s, "DOM 树快照")?.let { return@withContext it }
        val raw = evalJs(s, SNAPSHOT_TREE_JS) ?: return@withContext "DOM 树快照执行超时"
        val arr = unwrapEvalResult(raw) as? JsonArray
            ?: return@withContext "DOM 树快照解析失败（返回非数组），前 200 字符：${raw.take(200)}"
        if (arr.isEmpty()) return@withContext "页面没有可交互元素（可能还在加载，稍后重试）。"
        val limit = (max ?: 200).coerceIn(1, 500)
        buildString {
            append("交互元素树（共 ${arr.size} 个，显示前 ${minOf(limit, arr.size)} 个；编号与 browser_snapshot 一致）：\n")
            arr.take(limit).forEach { el ->
                val o = el.jsonObject
                val depth = o["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val idx = o["index"]?.jsonPrimitive?.contentOrNull ?: "?"
                val tag = o["tag"]?.jsonPrimitive?.contentOrNull ?: "?"
                val text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val href = o["href"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val role = o["role"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val xpath = o["xpath"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val label = when {
                    text.isNotEmpty() -> text
                    href.isNotEmpty() -> href
                    type.isNotEmpty() -> type
                    else -> "<无文本>"
                }
                append("  ".repeat(depth.coerceAtMost(8)))
                append("#$idx <$tag> $label")
                if (role.isNotEmpty()) append(" [role=$role]")
                if (xpath.isNotEmpty()) append("  → $xpath")
                append("\n")
            }
            if (arr.size > limit) append("…共 ${arr.size} 个，可用 max 参数查看更多\n")
        }
    }

    /** 提取页面正文为 Markdown（browser-use 的 markdown extraction）。
     *  [selector] 可选：限定提取区域（默认 article/main/body）。返回分段读取提示。 */
    suspend fun extractMarkdown(
        tab: Int?,
        urlFilter: String?,
        selector: String?,
        maxLen: Int = 8000,
        start: Int = 0
    ): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        if (s.url.isEmpty()) return@withContext "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        val m = maxLen.coerceIn(200, 200_000)
        val st0 = start.coerceAtLeast(0)
        // CSP 沙箱页（脚本被站点禁用）直接走 HTTP 直连，不再白等一次注定失败的 JS
        if (s.scriptBlocked) return@withContext httpFallbackExtract(s.url, selector, m, st0)
        val js = buildJs(MARKDOWN_JS, jsonStringLiteral(selector ?: ""), st0.toString(), m.toString())
        val raw = evalJs(s, js) ?: return@withContext "正文提取超时或页面未响应（JS 无返回）"
        val parsed = unwrapEvalResult(raw)
        if (parsed == null || parsed !is JsonObject) {
            // evaluateJavascript 对被 CSP 禁脚本的页面静默回调 "null"：标记并转直连，
            // 而不是把「站点策略」包装成工具报错让调用方排查。
            if (raw.trim() == "null") {
                s.scriptBlocked = true
                LogStore.w(TAG, "页面脚本被 CSP 禁用（sandbox），extract 转 HTTP 直连: ${s.url}")
                return@withContext httpFallbackExtract(s.url, selector, m, start)
            }
            return@withContext buildString {
                append("正文提取失败：脚本返回无法解析为 JSON 对象")
                append("；返回前 200 字符：").append(raw.take(200).replace("\n", "\\n"))
            }
        }
        val obj = parsed
        obj["error"]?.jsonPrimitive?.contentOrNull?.let { return@withContext "提取失败：$it" }
        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val total = obj["total"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: content.length
        val from = obj["from"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: st0
        val more = if (total > from + content.length)
            "\n（共 $total 字符，本次显示第 $from–${from + content.length}；继续读取请用 browser_extract 的 start=${from + content.length} 参数）" else ""
        when {
            content.isNotBlank() -> "提取到的 Markdown（${s.url}，第 $from 字符起）：\n$content$more"
            total > 0 && from >= total -> "已到正文末尾：全文共 $total 字符，start=$from 已超出范围。"
            else -> "该区域没有可提取的正文文本（selector 可能不匹配当前页面，可先 browser_snapshot 看结构）。"
        }
    }

    /**
     * CSP 沙箱页的 HTTP 直连提取：绕过 WebView 直接抓原文。
     * 对 text/plain（raw.githubusercontent.com 等）就是全文；对 HTML 剥标签取文本。
     * 返回格式与 [extractMarkdown] 一致，附兜底说明供调用方知情。
     */
    private suspend fun httpFallbackExtract(url: String, selector: String?, maxLen: Int, start: Int): String =
        withContext(Dispatchers.IO) {
        val resp = httpFetchText(url)
        val text = resp.text
        if (text.isNullOrEmpty()) {
            return@withContext buildString {
                append("该页面脚本被站点 CSP 禁用，HTTP 直连兜底也没拿到内容")
                if (resp.code > 0) append("（HTTP ${resp.code}）")
                resp.error?.let { append("（$it）") }
                append("。若页面需要登录，请确认浏览器会话里已登录（兜底请求会带上同一份 cookie）；")
                append("也可以用 http_request 工具自带请求头/Cookie 直接取。")
            }
        }
        val body = if (!selector.isNullOrBlank())
            "(注意：直连模式不支持 selector=$selector，已返回整页文本)\n"
        else ""
        val st = start.coerceIn(0, text.length)
        val content = text.substring(st).take(maxLen)
        val more = if (text.length > st + content.length)
            "\n（共 ${text.length} 字符，本次显示到 ${st + content.length}；继续读取请用 browser_extract 的 start=${st + content.length} 参数）"
        else ""
            return@withContext "提取到的 Markdown（$url，CSP 沙箱页已自动转 HTTP 直连）：\n$body${content}$more"
    }

    /** HTTP 抓取的返回：状态码 + 正文（可能为空）+ 失败原因。 */
    private class HttpFetch(val code: Int, val text: String?, val error: String?)

    /**
     * HTTP GET 抓取 URL 文本（UA 伪装浏览器、10s 超时）。
     *
     * 与 WebView 共用同一份 Cookie（CookieManager）：否则「登录后才可见」的页面在 CSP 直连兜底时
     * 永远只能拿到登录页。失败也把状态码带回来，便于给出可执行的下一步而不是一句「失败了」。
     * 仅 IO 线程调用。
     */
    private fun httpFetchText(url: String): HttpFetch = runCatching {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return HttpFetch(0, null, "仅支持 http/https")
        }
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", UA_OVERRIDE)
            instanceFollowRedirects = true
            runCatching { CookieManager.getInstance().getCookie(url) }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Cookie", it) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(charset(parseContentType(conn.contentType).second ?: "UTF-8"))
            ?.use { it.readText() }
        runCatching { conn.disconnect() }
        HttpFetch(code, text, null)
    }.getOrElse { HttpFetch(0, null, it.message ?: it.javaClass.simpleName) }

    /** 等待元素出现/消失/含指定文本（browser-use 的 wait_for）。轮询至超时。 */
    suspend fun waitFor(
        tab: Int?,
        urlFilter: String?,
        selector: String,
        mode: String,
        text: String?,
        timeoutSeconds: Int
    ): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        if (s.url.isEmpty()) return@withContext "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        if (s.scriptBlocked) {
            return@withContext "该页面脚本被站点 CSP 禁用（sandbox），browser_wait 无法工作。" +
                "此类页面请改用 browser_extract（自动 HTTP 直连）或 http_request。"
        }
        val js = buildJs(WAIT_JS, jsonStringLiteral(selector), jsonStringLiteral(mode), jsonStringLiteral(text ?: ""))
        val timeout = timeoutSeconds.coerceIn(1, 120)
        val deadline = System.currentTimeMillis() + timeout * 1000L
        var last = "尚无检查结果"
        while (System.currentTimeMillis() < deadline) {
            val raw = evalJs(s, js) ?: return@withContext "等待检查超时"
            val obj = unwrapEvalResult(raw) as? JsonObject
            val ok = obj?.get("ok")?.jsonPrimitive?.contentOrNull == "true"
            last = buildString {
                append("selector=$selector mode=$mode")
                obj?.get("found")?.jsonPrimitive?.contentOrNull?.let { append(" found=$it") }
                obj?.get("visible")?.jsonPrimitive?.contentOrNull?.let { append(" visible=$it") }
                obj?.get("count")?.jsonPrimitive?.contentOrNull?.let { append(" count=$it") }
            }
            if (ok) return@withContext "等待成功：$last"
            delay(500)
        }
        "等待超时（${timeout} 秒）：$last"
    }

    /** 标注截图（browser-use 的 screenshot highlighting）：先注入 #编号 角标再截图。
     *  截图里的编号与 browser_snapshot 一一对应，适合配合视觉模型使用。 */
    suspend fun screenshotAnnotated(
        context: Context,
        tab: Int?,
        urlFilter: String?,
        fullPage: Boolean = false
    ): String {
        val s = withContext(Dispatchers.Main) { pickOrCreateLocked(tab, urlFilter) }
        if (s.url.isEmpty()) return "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        withContext(Dispatchers.Main) { evalJs(s, SNAPSHOT_JS) } // 确保编号数组最新
        val ann = withContext(Dispatchers.Main) { evalJs(s, ANNOTATE_JS) }
        val count = (unwrapEvalResult(ann) as? JsonObject)?.get("count")
            ?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        delay(300) // 等角标渲染完成
        val shot = screenshot(context, tab, urlFilter, fullPage)
        withContext(Dispatchers.Main) { evalJs(s, CLEAR_ANNOTATE_JS) }
        return when {
            count <= 0 -> "$shot\n（未叠加编号：页面无可交互元素或全部失效，请先 browser_snapshot）"
            fullPage -> "$shot\n（已在首屏叠加 $count 个编号角标；整页长图只有第一屏带角标，因为角标是 fixed 定位）"
            else -> "$shot\n（截图中已叠加 $count 个元素编号角标，与 browser_snapshot 的 #编号 一一对应）"
        }
    }

    /** 页面状态摘要（browser-use 的 browser state summary）：URL/标题/加载状态/
     *  可交互元素数/视口/滚动/正文长度/meta 描述。轻量，不列出元素。 */
    suspend fun pageState(tab: Int?, urlFilter: String?): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        if (s.url.isEmpty()) return@withContext "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        val raw = evalJs(s, PAGE_STATE_JS) ?: return@withContext "页面状态查询超时"
        val o = unwrapEvalResult(raw) as? JsonObject
            ?: return@withContext "页面状态解析失败（返回非对象），前 200 字符：${raw.take(200)}"
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        buildString {
            append("页面状态：\n")
            append("- URL: ${str("url")}\n")
            append("- 标题: ${str("title").ifEmpty { "<无标题>" }}\n")
            append("- 加载状态: ${str("readyState")}\n")
            append("- 可交互元素: ${str("interactive")} 个\n")
            append("- 视口: ${str("viewportW")}x${str("viewportH")}，滚动 y=${str("scrollY")}/${str("scrollH")}\n")
            append("- 正文长度: ${str("bodyTextLen")} 字符\n")
            val meta = str("metaDescription")
            if (meta.isNotEmpty()) append("- meta 描述: $meta\n")
            s.loadError?.let { append("- 加载错误: $it\n") }
        }
    }
    /** 点击元素。ref 支持 #编号（来自 snapshot）或 CSS 选择器（如 button.submit）。 */
    suspend fun click(tab: Int?, urlFilter: String?, ref: String): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        scriptBlockedMsg(s, "点击")?.let { return@withContext it }
        val r = evalJs(s, buildJs(CLICK_JS, jsonStringLiteral(ref)))
            ?: return@withContext "点击执行超时"
        appendPageState(s, formatActionResult(r))
    }

    /** 输入文本到 input/textarea/select。ref 同 click。 */
    suspend fun type(tab: Int?, urlFilter: String?, ref: String, text: String): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        scriptBlockedMsg(s, "输入")?.let { return@withContext it }
        val r = evalJs(s, buildJs(TYPE_JS, jsonStringLiteral(ref), jsonStringLiteral(text)))
            ?: return@withContext "输入执行超时"
        appendPageState(s, formatActionResult(r))
    }

    /** 滚动页面。direction: top/bottom/up/down；amount 为 up/down 时的像素数。 */
    suspend fun scroll(tab: Int?, urlFilter: String?, direction: String, amount: Int?): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        scriptBlockedMsg(s, "滚动")?.let { return@withContext it }
        val r = evalJs(s, buildJs(SCROLL_JS, jsonStringLiteral(direction), (amount ?: 600).toString()))
            ?: return@withContext "滚动执行超时"
        appendPageState(s, formatActionResult(r))
    }

    /**
     * 历史导航：back / forward。
     *
     * 不再无条件等 onPageFinished（SPA 的同文档历史永远不会触发它，旧实现会白等 30 秒并报失败）：
     * 三条路谁先到算谁——① onPageFinished / doUpdateVisitedHistory 完成等待；
     * ② 会话 URL 变化；③ 页面 location.href 变化（兜底轮询）。都没有才算失败。
     */
    suspend fun history(action: String, tab: Int?, urlFilter: String?): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        val verb = if (action == "back") "后退" else "前进"
        val can = if (action == "back") s.view.canGoBack() else s.view.canGoForward()
        if (!can) return@withContext "没有可${verb}的页面（${s.url}）。"
        val before = s.url
        val d = s.beginLoad(s.url)
        if (action == "back") s.view.goBack() else s.view.goForward()
        var finished: String? = null
        var moved = false
        val deadline = System.currentTimeMillis() + HISTORY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            finished = withTimeoutOrNull(250) { d.await() }
            if (finished != null) break
            val now = (unwrapEvalResult(evalJs(s, "location.href")) as? JsonPrimitive)?.contentOrNull
            if (s.url != before || (now != null && now != before)) {
                if (now != null) s.url = now
                moved = true
                delay(200) // 给页面一点时间更新标题/渲染
                break
            }
        }
        s.title = runCatching { s.view.title ?: "" }.getOrDefault("")
        val title = s.title.ifEmpty { "<无标题>" }
        when {
            finished != null && s.loadError == null -> "已${verb}到 ${s.url}\n页面标题：$title"
            s.loadError != null -> "历史导航后页面报错：${s.loadError}（当前 ${s.url}）"
            moved -> "已${verb}到 ${s.url}（同文档导航，未触发页面加载事件）\n页面标题：$title"
            else -> "历史导航未生效（仍在 ${s.url}）：可能已到历史尽头，请用 browser_navigate 直接打开目标地址。"
        }
    }

    /** 重新加载当前页。 */
    suspend fun reload(tab: Int?, urlFilter: String?): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        if (s.url.isEmpty()) return@withContext "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        val d = s.beginLoad(s.url)
        s.view.reload()
        val finished = withTimeoutOrNull(NAV_TIMEOUT_MS) { d.await() }
        val title = s.title.ifEmpty { "<无标题>" }
        if (finished != null && s.loadError == null) "已重新加载 ${s.url}\n页面标题：$title"
        else "重新加载未收到完成事件，当前：${s.url}"
    }

    /**
     * 截图当前 WebView 到 filesDir 的 browser 目录（PNG 文件），返回文件路径。
     *
     * 两个保修点：
     * 1) **取帧前先把目标会话置为可见**——GONE 或被别的会话盖住时 draw() 只会拿到空白/陈旧内容；
     * 2) **空白检测**——截完抽样统计，若整图近似纯色就明确提示「可能没截到内容」，而不是谎报成功。
     * [fullPage] 为 true 时按屏滚动拼接整页（上限 [MAX_FULLPAGE_SLICES] 屏），结束后恢复原滚动位置。
     */
    suspend fun screenshot(
        context: Context,
        tab: Int?,
        urlFilter: String?,
        fullPage: Boolean = false
    ): String {
        val s = withContext(Dispatchers.Main) { pickOrCreateLocked(tab, urlFilter) }
        if (s.url.isEmpty()) return "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        delay(400) // 等渲染完成（挂起不阻塞主线程）
        return withContext(Dispatchers.Main) {
            val w = s.view.width
            val h = s.view.height
            if (w <= 0 || h <= 0) return@withContext "截图失败：WebView 尚无尺寸（未完成布局）。"
            val saved = makeVisibleForCapture(s)
            var shots: List<Bitmap> = emptyList()
            var bmp: Bitmap? = null
            try {
                shots = if (fullPage) captureFullPage(s, w, h) else listOf(captureOnce(s.view, w, h))
                bmp = if (shots.size == 1) shots[0] else stitchBitmaps(shots, w)
                val dir = File(context.filesDir, "browser")
                dir.mkdirs()
                val file = File(dir, "screenshot_${System.currentTimeMillis()}.png")
                val out = bmp
                runCatching {
                    file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
                }.onFailure { return@withContext "截图保存失败：${it.message}" }
                val blank = looksBlank(out)
                buildString {
                    append("截图已保存：${file.absolutePath}（${w}x${out.height}${if (fullPage) "，整页拼接" else ""}）。可用 read_file 读取该文件查看图片（PNG）。")
                    if (blank) {
                        append("\n注意：这张图近似纯色，很可能没截到内容（页面还没渲染完 / 页面本身为空 / WebView 不可见）。")
                        append("建议先用 browser_wait 等目标元素出现，再重试截图。")
                    }
                }
            } finally {
                restoreVisibility(saved)
                shots.forEach { runCatching { if (it !== bmp) it.recycle() } }
                runCatching { bmp?.recycle() }
            }
        }
    }

    /** 截图期间把目标会话置为可见（同用途其余会话隐藏），返回原始可见性以便恢复。仅主线程调用。 */
    private fun makeVisibleForCapture(s: Session): List<Pair<WebView, Int>> {
        val pool = sessions.filter { it.purpose == s.purpose }
        val saved = pool.map { it.view to it.view.visibility }
        pool.forEach { it.view.visibility = if (it === s) View.VISIBLE else View.GONE }
        return saved
    }

    /** 恢复 [makeVisibleForCapture] 保存的可见性。 */
    private fun restoreVisibility(saved: List<Pair<WebView, Int>>) {
        saved.forEach { (v, vis) -> runCatching { v.visibility = vis } }
    }

    /** 单次取帧（调用方保证视图可见且已布局）。 */
    private fun captureOnce(view: WebView, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** 近似空白判定：抽样统计，单一颜色占比 > 99% 视为「什么都没截到」。 */
    private fun looksBlank(bmp: Bitmap): Boolean = runCatching {
        val step = maxOf(1, minOf(bmp.width, bmp.height) / 24)
        val first = bmp.getPixel(0, 0)
        var same = 0
        var total = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getPixel(x, y) == first) same++
                total++
                x += step
            }
            y += step
        }
        total > 0 && same.toDouble() / total > 0.99
    }.getOrDefault(false)

    /** 整页截图：按屏滚动逐屏取帧（上限 [MAX_FULLPAGE_SLICES]），结束后恢复原滚动位置。 */
    private suspend fun captureFullPage(s: Session, w: Int, h: Int): List<Bitmap> {
        val o = unwrapEvalResult(evalJs(s, PAGE_STATE_JS)) as? JsonObject
        fun num(k: String, def: Int) = o?.get(k)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: def
        val scrollH = num("scrollH", h)
        val startY = num("scrollY", 0)
        val slices = (((scrollH + h - 1) / h)).coerceIn(1, MAX_FULLPAGE_SLICES)
        val out = ArrayList<Bitmap>(slices)
        for (i in 0 until slices) {
            evalJs(s, "window.scrollTo(0, ${i * h}); 'ok'")
            delay(250)
            out.add(captureOnce(s.view, w, h))
        }
        evalJs(s, "window.scrollTo(0, $startY); 'ok'")
        delay(150)
        return out
    }

    /** 纵向拼接多屏截图。 */
    private fun stitchBitmaps(shots: List<Bitmap>, w: Int): Bitmap {
        val total = shots.sumOf { it.height }
        val out = Bitmap.createBitmap(w, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var y = 0f
        shots.forEach { b ->
            canvas.drawBitmap(b, 0f, y, null)
            y += b.height
        }
        return out
    }

    /** 读取当前页 cookie。url 为空用当前会话 URL。 */
    fun cookies(url: String?): String {
        val u = url ?: lastActiveUrl
            ?: return "当前没有页面可读取 cookie。"
        val c = runCatching { CookieManager.getInstance().getCookie(u) }.getOrNull().orEmpty()
        return if (c.isBlank()) "（该页面暂无 cookie）" else c.split(";").joinToString("\n") { it.trim() }
    }

    /** 读取 localStorage（可指定 key）。 */
    suspend fun storage(tab: Int?, urlFilter: String?, key: String?): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        if (s.url.isEmpty()) return@withContext "当前会话还没有页面，请先用 browser_navigate 打开一个网页。"
        scriptBlockedMsg(s, "读取 localStorage")?.let { return@withContext it }
        val r = evalJs(s, buildJs(STORAGE_JS, if (key != null) jsonStringLiteral(key) else "null"))
            ?: return@withContext "读取 localStorage 超时"
        formatActionResult(r)
    }

    /** 按键：向当前焦点元素派发 keydown/keypress/keyup（Enter、Escape、Tab、方向键等）。 */
    suspend fun pressKey(tab: Int?, urlFilter: String?, key: String): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        scriptBlockedMsg(s, "按键")?.let { return@withContext it }
        val r = evalJs(s, buildJs(PRESS_KEY_JS, jsonStringLiteral(key), keyCodeOf(key).toString()))
            ?: return@withContext "按键执行超时"
        appendPageState(s, formatActionResult(r))
    }

    /** 悬停：派发 mouseover/mouseenter/mousemove（下拉菜单、tooltip 类交互需要）。 */
    suspend fun hover(tab: Int?, urlFilter: String?, ref: String): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        scriptBlockedMsg(s, "悬停")?.let { return@withContext it }
        val r = evalJs(s, buildJs(HOVER_JS, jsonStringLiteral(ref)))
            ?: return@withContext "悬停执行超时"
        appendPageState(s, formatActionResult(r))
    }

    /** 下拉选择：按 value 或可见文本选中 option（并派发 input/change）。 */
    suspend fun selectOption(tab: Int?, urlFilter: String?, ref: String, value: String): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        scriptBlockedMsg(s, "下拉选择")?.let { return@withContext it }
        val r = evalJs(s, buildJs(SELECT_JS, jsonStringLiteral(ref), jsonStringLiteral(value)))
            ?: return@withContext "下拉选择执行超时"
        appendPageState(s, formatActionResult(r))
    }

    /**
     * 文件上传：指定本地文件后点击 ref 触发 `<input type=file>`。
     *
     * WebView 不能从 JS 设置 FileList，唯一通道是 [onShowFileChooser]——
     * 这里先把路径放进 [pendingUploadPaths]，点一下目标元素让页面发起选择请求，
     * 再由 onShowFileChooser 把文件交给页面。
     */
    suspend fun uploadFile(tab: Int?, urlFilter: String?, ref: String, path: String): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        val f = File(path)
        if (!f.isFile) return@withContext "上传失败：找不到文件 $path"
        pendingUploadPaths = listOf(f.absolutePath)
        val r = evalJs(s, buildJs(CLICK_JS, jsonStringLiteral(ref)))
        if (r == null) {
            pendingUploadPaths = null
            return@withContext "上传失败：点击 ${ref} 超时"
        }
        delay(400) // 等 onShowFileChooser 被页面触发
        val consumed = pendingUploadPaths == null
        if (!consumed) pendingUploadPaths = null
        val base = if (consumed) {
            "已把 ${f.name}（${f.length()} 字节）交给页面的文件选择器（ref=$ref）。"
        } else {
            "点击了 $ref，但页面没有触发文件选择器：ref 可能不是 <input type=file>，或该输入框被隐藏（可先 browser_snapshot 确认）。"
        }
        appendPageState(s, base)
    }

    /** 新开一个普通会话（可选直接导航）。返回面向 LLM 的描述。 */
    suspend fun newTab(url: String?): String = withContext(Dispatchers.Main) {
        val s = createSessionLocked(SessionPurpose.NORMAL)
        s.lastUsedAt = android.os.SystemClock.elapsedRealtime()
        val idx = sessions.filter { it.purpose == SessionPurpose.NORMAL }.indexOf(s)
        if (url.isNullOrBlank()) {
            "已新建会话（编号 $idx），当前共 ${sessions.count { it.purpose == SessionPurpose.NORMAL }} 个。"
        } else {
            navigate(url, idx, null)
        }
    }

    /** 高亮元素（视觉辅助，配合 click/type 使用）。 */
    suspend fun highlight(tab: Int?, urlFilter: String?, ref: String): String = withContext(Dispatchers.Main) {
        val s = pickOrCreateLocked(tab, urlFilter)
        scriptBlockedMsg(s, "高亮")?.let { return@withContext it }
        val r = evalJs(s, buildJs(HIGHLIGHT_JS, jsonStringLiteral(ref)))
            ?: return@withContext "高亮执行超时"
        formatActionResult(r)
    }

    /** 最近 N 条 console 消息（新→旧）。 */
    fun consoleSnapshot(limit: Int): List<String> = synchronized(consoleLock) {
        consoleMessages.take(limit)
    }

    /** 最近 N 条网络事件（新→旧）。 */
    fun networkSnapshot(limit: Int): List<String> = synchronized(networkLock) {
        networkEvents.take(limit)
    }

    /**
     * 释放全部会话**并清空所有静态引用**（App 退出 / 用户显式关闭浏览器时调用）。
     *
     * 必须逐个 removeView 再 destroy（销毁后仍挂在视图树上违反 WebView 使用约定），
     * 也必须把 container/appContext/回调清掉——否则复位后 [ensureStarted] 会把新 WebView
     * 挂进上一个 Activity 的旧容器，表现为「新的浏览器页空白」。
     */
    fun destroy() {
        mainHandler.post {
            sessions.forEach { s ->
                (s.view.parent as? ViewGroup)?.removeView(s.view)
                runCatching { s.view.clearCache(true) }
                runCatching { s.view.destroy() }
            }
            sessions.clear()
            started = false
            sessionCount = 0
            appContext = null
            container = null
            automationContainer = null
            lastActiveUrl = null
            pendingDialog = null
            pendingUploadPaths = null
            onPageChanged = null
            onPageChangedExtra = null
            synchronized(consoleLock) { consoleMessages.clear() }
            synchronized(networkLock) { networkEvents.clear() }
            synchronized(downloadLock) { downloadResults.clear() }
            LogStore.i(TAG, "内嵌浏览器已完全释放（会话/容器/回调均已复位）")
        }
    }

    /**
     * 关闭指定编号的会话并释放 WebView。
     *
     * [index] 是**同用途池**的下标（与 browser_list_tabs / tab 参数同一口径）。
     * 顺序必须是「先摘出父容器，再 destroy」——反过来会把已销毁的 WebView 留在视图树上。
     */
    suspend fun closeTab(index: Int, purpose: SessionPurpose = SessionPurpose.NORMAL): String =
        withContext(Dispatchers.Main) {
            val pool = sessions.filter { it.purpose == purpose }
            val s = pool.getOrNull(index)
                ?: return@withContext "关闭失败：没有编号为 $index 的会话（当前 ${pool.size} 个）。"
            val url = s.url
            (s.view.parent as? ViewGroup)?.removeView(s.view)
            runCatching { s.view.clearCache(true) }
            runCatching { s.view.destroy() }
            sessions.remove(s)
            sessionCount = sessions.size
            LogStore.d(TAG, "关闭会话 index=$index（id=${s.id}），剩余 ${sessions.size} 个")
            "已关闭会话 $index（${url.ifEmpty { "<空页面>" }}）。剩余 ${pool.size - 1} 个，编号会重新排，请用 browser_list_tabs 确认。"
        }

    // ── 内部实现 ─────────────────────────────────────────────

    private fun configure(view: WebView) {
        // WebView 基础底色跟随深色模式：about:blank / 空白页在深色主题下不再白屏
        val dark = appContext?.resources?.configuration?.uiMode?.and(
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        ) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        view.setBackgroundColor(if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt())
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 允许 file:// 加载本地文件（默认 false，否则 file:// URL 会被拒绝，落到 chrome-error 错误页）。
            // 仅允许访问本地文件本身，不开 allowUniversalAccessFromFileURLs（避免 XSS 跨源读 filesDir 含 token 的文件）。
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // 全局 UA 模式：所有新会话都按当前模式建（旧会话由 setDesktopUaMode 就地更新）
            userAgentString = if (desktopUaMode) UA_DESKTOP else UA_OVERRIDE
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                val s = sessionOf(view) ?: return
                s.url = url // 页面一开始加载就记录，evaluate 不再误报「没有页面」
                s.docLoading = true
                LogStore.d(TAG, "onPageStarted: $url")
                onPageChanged?.invoke(url, s.purpose)
                onPageChangedExtra?.invoke(url, s.purpose)
            }

            override fun onPageFinished(view: WebView, url: String) {
                val s = sessionOf(view) ?: return
                s.url = url
                s.title = runCatching { view.title ?: "" }.getOrDefault("")
                // chrome-error:// 是 WebView 内部错误页（file:// 被拒、网络失败等）。
                // 该页加载完成也会触发 onPageFinished，若不跳过会抹掉 onReceivedError 设置的错误标志，
                // 导致 navigate() 把「加载到错误页」误判为「加载成功」返回「已导航到」。
                if (!url.startsWith("chrome-error://")) s.loadError = null
                s.docLoading = false
                s.notifyPageFinished(url)
                if (s.purpose == SessionPurpose.AUTOMATION) automationPageFinishedAt = System.currentTimeMillis()
                LogStore.d(TAG, "onPageFinished: $url (title=${s.title})")
                // 开发者工具开启时注入（提前注入失败时的兜底）
                if (devToolsEnabled) injectEruda(view)
                // 脚本可用性探针：CSP 沙箱页（如 raw.githubusercontent.com）会禁一切 JS，
                // evaluateJavascript 静默回调 "null"。此处提前探测并标记，
                // 后续 extract 直连兜底 / wait·evaluate 给出明确指引，不用每次撞墙。
                if (url.startsWith("http")) {
                    view.evaluateJavascript("1") { r ->
                        val ok = r != null && r != "null"
                        s.scriptBlocked = !ok
                        if (!ok) LogStore.w(TAG, "页面脚本被 CSP 禁用(sandbox): $url")
                    }
                }
                onPageChanged?.invoke(url, s.purpose)
                onPageChangedExtra?.invoke(url, s.purpose)
            }

            /**
             * SPA（pushState/replaceState/hash、同文档历史）**不会**触发 onPageFinished：
             * 这里补上 URL/标题同步与主页态回调；若当前不是整文档加载，还把等待中的导航标记完成，
             * 让 browser_back/forward 立刻返回而不是白等超时。
             */
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                val s = sessionOf(view) ?: return
                s.url = url
                if (s.docLoading) {
                    LogStore.d(TAG, "doUpdateVisitedHistory（文档加载中）: $url")
                    return
                }
                s.title = runCatching { view.title ?: "" }.getOrDefault("")
                LogStore.d(TAG, "doUpdateVisitedHistory（同文档导航）: $url")
                s.notifyPageFinished(url)
                if (s.purpose == SessionPurpose.AUTOMATION) automationPageFinishedAt = System.currentTimeMillis()
                onPageChanged?.invoke(url, s.purpose)
                onPageChangedExtra?.invoke(url, s.purpose)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                val code = runCatching { errorResponse.statusCode }.getOrDefault(-1)
                recordNetwork(sessionTagFor(view), "[响应] $code ${request.method} ${request.url}")
                if (request.isForMainFrame) {
                    LogStore.w(TAG, "onReceivedHttpError: ${request.url} -> $code")
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                val s = sessionOf(view) ?: return
                val code = runCatching { error.errorCode }.getOrDefault(-1)
                val desc = runCatching { error.description?.toString() ?: "" }.getOrDefault("")
                if (request.isForMainFrame) {
                    s.loadError = "错误码 $code: $desc"
                    LogStore.w(TAG, "页面加载失败: ${request.url} $code $desc")
                }
                recordNetwork(sessionTagFor(view), "[加载失败] $code ${request.url} ${desc}")
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // 放行所有证书错误（公网 / 内网自签一律信任）：本浏览器定位为 agent 的信息抓取器，
                // 遇到 10.x 内网自签服务时若 cancel 会直接白屏，改为 proceed 并留日志可追溯。
                val code = runCatching { error.primaryError }.getOrDefault(-1)
                val url = runCatching { error.url ?: "" }.getOrDefault("")
                LogStore.w(TAG, "SSL 错误（已放行）: $url code=$code")
                if (sessionOf(view) != null) {
                    recordNetwork(sessionTagFor(view), "[SSL放行] $code $url")
                }
                handler.proceed()
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                LogStore.e(TAG, "WebView 渲染进程退出: ${detail.didCrash()}")
                return false // 返回 false 让系统销毁 WebView，下次工具调用重建
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                recordNetwork(sessionTagFor(view), "[请求] ${request.method} ${request.url}")
                val urlStr = request.url.toString()
                // Eruda 调试面板资源：从 assets 提供（页面注入的 <script src="eruda.min.js">）
                if (urlStr.contains("eruda.min.js")) {
                    runCatching {
                        val stream = appContext?.assets?.open("eruda.min.js")
                        if (stream != null) {
                            return WebResourceResponse("application/javascript", "UTF-8", stream)
                        }
                    }
                }
                // 提前注入：仅当开发者工具开启时，主框架 GET 的 text/html 响应重写，
                // 把 eruda 脚本插进 <head>，使 XHR/fetch 从页面脚本执行前开始被劫持（Network 全量抓包）。
                if (devToolsEnabled && request.isForMainFrame && request.method.equals("GET", ignoreCase = true)) {
                    injectErudaIntoHtml(request)?.let { return it }
                }
                // 主框架无条件剥 CSP：本浏览器是 agent 的信息抓取器而非日常浏览，
                // 站点 `CSP: sandbox`（raw.githubusercontent.com 等）会连 evaluateJavascript
                // 一起禁掉，表现为所有 JS 静默返回 null。剥除后 agent 能力完整可用；
                // 若 App 自行重发请求失败（403/风控），返回 null 由 WebView 原样加载，
                // 此时由 extractMarkdown 的 HTTP 直连兜底接手。
                if (request.isForMainFrame && request.method.equals("GET", ignoreCase = true)) {
                    stripCspOnAnyRequest(request)?.let { return it }
                }
                return null
            }
        }
        view.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val level = runCatching { message.messageLevel().name }.getOrDefault("LOG")
                val where = runCatching { message.sourceId() }.getOrNull()
                recordConsole(sessionTagForUrl(where), "[console:$level] ${message.message()}（${where ?: "?"}）")
                return true
            }

            /**
             * 页面 JS 弹窗：本 WebView 用 application context 构造、弹不出系统对话框，
             * 因此这里全部接管、挂起等 browser_handle_dialog 答复（超时按默认取消），
             * 不再让 `confirm()` 恒返回 false。
             */
            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                offerDialog("alert", message, result, null)
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
                offerDialog("confirm", message, result, null)
                return true
            }

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String?,
                result: android.webkit.JsPromptResult
            ): Boolean {
                offerDialog("prompt", message, result, defaultValue)
                return true
            }

            /**
             * 文件上传：`<input type=file>` 被点击时触发。
             * agent 先用 browser_file_upload 指定本地文件，这里把 URI 交给页面；
             * 没有指定就取消（本进程没有 Activity，弹不出系统选择器）。
             */
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                val paths = pendingUploadPaths
                pendingUploadPaths = null
                if (paths.isNullOrEmpty()) {
                    runCatching { filePathCallback.onReceiveValue(null) }
                    LogStore.w(TAG, "页面请求选择文件，但未指定文件（先用 browser_file_upload 指定本地路径）")
                    return true
                }
                val uris = paths.mapNotNull { p -> runCatching { Uri.fromFile(File(p)) }.getOrNull() }.toTypedArray()
                runCatching { filePathCallback.onReceiveValue(uris) }
                LogStore.i(TAG, "已向页面提供 ${uris.size} 个上传文件")
                return true
            }

            /** target=_blank / window.open：WebView 不支持多窗口，记一笔（链接会在本窗口内打开或静默失败）。 */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                LogStore.w(TAG, "页面尝试打开新窗口（WebView 不支持），已忽略；可用 browser_tabs action=new 另开会话")
                return false
            }

            /** 摄像头/麦克风/剪贴板等权限请求：默认拒绝并留痕，避免静默挂起。 */
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                LogStore.w(TAG, "页面请求权限 ${request.resources.joinToString()}，已拒绝（浏览器会话没有 UI 授权通道）")
                runCatching { request.deny() }
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                // 进度日志只打关键点，避免刷屏
                if (newProgress == 25 || newProgress == 50 || newProgress == 75 || newProgress == 100) {
                    LogStore.d(TAG, "加载进度 $newProgress%")
                }
            }
        }

        // 下载：WebView 自己没有下载实现，不接管的话点下载链接毫无反应。
        // 这里用共享 cookie 的 HTTP 抓取落地到 filesDir/browser/downloads，结果用 browser_download 查。
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val tag = sessionTagFor(view)
            recordNetwork(tag, "[下载] 页面触发下载 $mimeType $url")
            LogStore.i(TAG, "页面触发下载：$url（$mimeType）")
            scope.launch {
                performDownload(url, userAgent, contentDisposition, mimeType, contentLength = -1L)
            }
        }
    }

    /**
     * 选择会话：优先 tab 下标（越界/无则新建）→ 其次 url 包含匹配 → 默认第一个（无则新建）。
     *
     * [purpose] 决定只在**同用途**的会话里挑：自动化不会选中用户正在看的浏览器页面，
     * 反之亦然——这是「视图和操作互不影响」的关键。仅主线程调用。
     */
    private fun pickOrCreateLocked(
        tab: Int?,
        urlFilter: String?,
        purpose: SessionPurpose = SessionPurpose.NORMAL,
    ): Session {
        val pool = sessions.filter { it.purpose == purpose }
        val s = if (tab != null) {
            pool.getOrNull(tab) ?: createSessionLocked(purpose)
        } else {
            urlFilter?.let { f ->
                pool.firstOrNull { it.url.contains(f, ignoreCase = true) } ?: pool.firstOrNull()
            } ?: pool.firstOrNull() ?: createSessionLocked(purpose)
        }
        s.lastUsedAt = android.os.SystemClock.elapsedRealtime()
        if (purpose == SessionPurpose.NORMAL) lastActiveUrl = s.url
        return s
    }

    /** 新建会话。仅主线程调用；正常流程下 ensureStarted 已保存 appContext。 */
    private fun createSessionLocked(purpose: SessionPurpose = SessionPurpose.NORMAL): Session {
        val ctx = appContext ?: throw IllegalStateException("WebBrowser 未初始化，请先调用 ensureStarted")
        // 池满时淘汰最久未活跃的**同用途**会话（销毁 WebView 释放内存）。
        // 只淘汰同用途：自动化会话跑到一半被回收会直接炸掉正在进行的对话。
        while (sessions.count { it.purpose == purpose } >= MAX_SESSIONS) {
            // 淘汰同用途里「最久未用」的会话——不是最早创建的：早创建但一直在用的页面不该被杀。
            val victim = sessions.filter { it.purpose == purpose }
                .minWithOrNull(compareBy({ it.lastUsedAt }, { it.id })) ?: break
            LogStore.w(TAG, "会话数达上限 $MAX_SESSIONS，淘汰最久未用会话 id=${victim.id}（${victim.url}）")
            (victim.view.parent as? ViewGroup)?.removeView(victim.view)
            runCatching { victim.view.clearCache(true) }
            runCatching { victim.view.destroy() }
            sessions.remove(victim)
        }
        val view = WebView(ctx)
        // UA 由 configure() 按全局模式统一设置，不再只对自动化会话特殊处理
        configure(view)
        // 按用途挂到各自的容器：普通 → 浏览器 Tab；自动化 → 自动化浮窗
        containerOf(purpose)?.let { it.addView(view, 0) } // index 0：WebView 在底层，工具栏浮层在上
        val s = Session(sessionIdGen.incrementAndGet(), view, purpose)
        sessions.add(s)
        attachHomeBridge(view, s)
        sessionCount = sessions.size
        // 新会话显示，**同用途**旧会话隐藏（两种用途各自同时只显示一个窗口）
        sessions.filter { it.purpose == purpose }.forEach {
            it.view.visibility = if (it === s) View.VISIBLE else View.GONE
        }
        return s
    }

    /** 取某用途对应的容器。 */
    private fun containerOf(purpose: SessionPurpose): ViewGroup? =
        if (purpose == SessionPurpose.AUTOMATION) automationContainer else container

    private fun sessionOf(view: WebView): Session? =
        sessions.firstOrNull { it.view === view }

    /**
     * 重新挂载后强制重绘。
     *
     * 现象（用户报障）：浮窗"缩小成球再打开"是黑屏（其实是 WebView 背景色），**摸一下才出内容**。
     * 原因：View 被摘下来又挂到新父容器时，硬件加速的 WebView 合成层不会自动重建，
     * 视图树自己认为"没有待绘制内容"，于是没有新帧——直到一次触摸触发输入→重绘。
     * 这里在挂载后主动 requestLayout + invalidate（并补一次下一帧），并 WebView.onResume 恢复渲染。
     */
    private fun forceRedraw(v: WebView) {
        // 只处理当前可见的会话：同用途下其余会话是 GONE，强行置 VISIBLE 会让它们叠在一起显示。
        if (v.visibility != View.VISIBLE) return
        runCatching {
            v.onResume()
            v.resumeTimers()
            v.requestLayout()
            v.invalidate()
            v.post {
                runCatching {
                    v.requestLayout()
                    v.invalidate()
                }
                // 再来一次：球展开成卡片时，窗口可能还没跑完第一帧，
                // 单次 post 有概率落在"空白帧"之前，补一次延迟重绘更稳。
                v.postDelayed({
                    runCatching {
                        v.requestLayout()
                        v.invalidate()
                    }
                }, 150)
            }
        }
    }

    /** 给会话挂上起始页桥（仅对 browser_home.html 生效，见 [BrowserHomeBridge] 的 URL 守卫）。 */
    private fun attachHomeBridge(view: WebView, s: Session) {
        runCatching {
            view.addJavascriptInterface(
                BrowserHomeBridge(
                    currentUrl = { s.url },
                    navigate = { url -> mainHandler.post { runCatching { view.loadUrl(url) } } }
                ),
                "McpHome"
            )
        }.onFailure { LogStore.w(TAG, "起始页桥注入失败：${it.message}") }
    }

    /**
     * 注入 Eruda 开发者工具面板（完整 console/DOM/network/存储/性能 调试界面）。
     * 幂等：页面已注入（window.eruda 存在）则跳过；script 资源经 shouldInterceptRequest
     * 从 assets 提供。此方法为 onPageFinished 兜底（提前注入失败的页面再试一次）。
     */
    private fun injectEruda(view: WebView) {
        val js = "(function(){" +
            "if(window.eruda)return;" +
            "var s=document.createElement('script');" +
            "s.src='eruda.min.js';" +
            "s.onload=function(){window.eruda.init({defaults:{transparency:0.85,displaySize:50}})};" +
            "(document.head||document.documentElement).appendChild(s);" +
            "})()"
        runCatching { view.evaluateJavascript(js, null) }
            .onFailure { LogStore.w(TAG, "Eruda 注入失败: ${it.message}") }
    }

    // ── Eruda 提前注入（HTML 响应重写）───────────────────────

    /** 注入到 HTML <head> 的脚本：同步加载 eruda.min.js（拦截器提供）并 init。 */
    private val ERUDA_HEAD_SCRIPT = "<script src=\"eruda.min.js\"></script>" +
        "<script>window.eruda&&window.eruda.init({defaults:{transparency:0.85,displaySize:50}});</script>"

    /**
     * 重新请求主框架 HTML，把 eruda 脚本插进 <head> 后返回重写响应。
     * 失败（网络/非 HTML/超大）时返回 null，WebView 走默认加载（仅失去注入，不影响页面）。
     * 在 shouldInterceptRequest 的工作线程执行（同步阻塞）。
     */
    private fun injectErudaIntoHtml(request: WebResourceRequest): WebResourceResponse? {
        val urlStr = request.url.toString()
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) return null
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                // 带上原请求头（UA、Accept 等），避免被反爬/拿到不一致页面
                request.requestHeaders.forEach { (k, v) -> copyRequestHeader(this, k, v) }
                CookieManager.getInstance().getCookie(urlStr)?.takeIf { it.isNotEmpty() }?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            try {
                val code = conn.responseCode
                val type = conn.contentType.orEmpty()
                if (code !in 200..299 || !type.contains("text/html", ignoreCase = true)) {
                    null
                } else {
                    val body = conn.inputStream.use { it.readBytes() }
                    if (body.size > 8 * 1024 * 1024) {
                        null // 超大 HTML 跳过（避免内存峰值）
                    } else {
                        // 移除 CSP（响应头 + HTML 内 meta 标签），否则严格 CSP 站点会拦截注入的 Eruda 脚本；
                        // body 被改写过，必须丢掉 Content-Length（否则页面会被截断）
                        val headers = sanitizeHeaders(conn.headerFields, urlStr, dropLength = true)
                        var html = stripMetaCsp(body)
                        html = injectErudaScript(html)
                        WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            code,
                            runCatching { conn.responseMessage }.getOrNull() ?: "",
                            headers,
                            html.inputStream()
                        )
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * 主框架 GET 由 App 代发一次、剥掉 CSP 后再交给 WebView。
     *
     * 调用点见 [configure] 里的 shouldInterceptRequest：**主框架 GET 无条件走这里**
     * （不只是 devToolsEnabled —— CSP: sandbox 的页面会连 evaluateJavascript 一起禁掉，
     * 只有子资源/非 GET 才交给 WebView 原生加载，避免扰动 body 流）。
     * 对非 http(s) scheme（data: / file: / chrome-extension）直接跳过；
     * 失败/异常/无 body 时返回 null，交给 WebView 默认路径（不把网络抖动放大成失败）。
     */
    private fun stripCspOnAnyRequest(request: WebResourceRequest): WebResourceResponse? = runCatching {
        val urlStr = request.url.toString()
        if (!urlStr.startsWith("http://", ignoreCase = true) &&
            !urlStr.startsWith("https://", ignoreCase = true)) return@runCatching null
        val method = request.method ?: "GET"
        if (!method.equals("GET", ignoreCase = true) && !method.equals("HEAD", ignoreCase = true)) {
            return@runCatching null
        }
        val url = java.net.URL(urlStr)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = false // 重定向也交给下一轮 shouldInterceptRequest 统一剥 CSP
            request.requestHeaders.forEach { (k, v) -> copyRequestHeader(this, k, v) }
            CookieManager.getInstance().getCookie(urlStr)?.takeIf { it.isNotEmpty() }?.let {
                setRequestProperty("Cookie", it)
            }
        }
        try {
            val code = conn.responseCode
            // 3xx 重定向：让 WebView 自己走下一轮，下一轮再次到这里会统一剥新 URL 的 CSP，
            // 不在这里代发（避免改变 Referer/Cookie 语义）。
            if (code in 300..399) return@runCatching null
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            // 没有 body（204/304/HEAD、或 4xx 无 errorStream）：别构造空流响应，交回 WebView 自己处理
            if (stream == null) return@runCatching null
            val (mimeType, encoding) = parseContentType(conn.contentType)
            val headers = sanitizeHeaders(conn.headerFields, urlStr)
            WebResourceResponse(mimeType ?: "application/octet-stream", encoding ?: "UTF-8",
                code, runCatching { conn.responseMessage }.getOrNull() ?: "", headers, stream)
        } finally {
            // 不要 disconnect：让 WebResourceResponse 持有流，读取完后由 GC 回收连接。
            // conn.disconnect() 在读取期间提前关会截断正文。
        }
    }.getOrNull()

    /** 从 Content-Type 头解析 (mimeType, encoding)（实现见 [BrowserInternals.parseContentType]）。 */
    private fun parseContentType(raw: String?): Pair<String?, String?> = BrowserInternals.parseContentType(raw)

    /**
     * 归一化响应头（拦截器返回给 WebView 用）：
     * 1) 删掉 CSP / CSP-Report-Only——本浏览器是 agent 的信息抓取器，站点 `CSP: sandbox` 会把
     *    evaluateJavascript 一起禁掉（表现是所有 JS 静默返回 null）；
     * 2) **逐条把 Set-Cookie 写进 CookieManager 再删掉这个头**——WebView 不会保存拦截响应里的 cookie，
     *    而 `Map<String,String>` 也表达不了多条 Set-Cookie（旧实现用 firstOrNull() 把其余 cookie 直接丢了）；
     * 3) [dropLength] 为 true 时必须删掉 Content-Length/Transfer-Encoding——body 已被改写，
     *    长度不再匹配，留着会把页面截断（Eruda 注入路径就是这个坑）。
     */
    private fun sanitizeHeaders(
        headerFields: Map<String, List<String>>,
        url: String,
        dropLength: Boolean = false
    ): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        headerFields.forEach { (k, v) ->
            if (k == null) return@forEach
            when {
                k.equals("Content-Security-Policy", ignoreCase = true) ||
                    k.equals("Content-Security-Policy-Report-Only", ignoreCase = true) -> Unit
                k.equals("Set-Cookie", ignoreCase = true) -> {
                    v.forEach { raw -> runCatching { CookieManager.getInstance().setCookie(url, raw) } }
                }
                dropLength && (k.equals("Content-Length", ignoreCase = true) ||
                    k.equals("Transfer-Encoding", ignoreCase = true)) -> Unit
                else -> out[k] = v.firstOrNull() ?: ""
            }
        }
        return out
    }

    /**
     * 复制 WebView 的请求头到代发请求。
     *
     * **必须丢弃 Accept-Encoding**：手动设置它会关掉 HttpURLConnection 的透明解压，
     * 于是拿到的是 gzip/br 原始字节，而响应头里又写着 Content-Encoding，页面直接变乱码。
     * Host/Content-Length/Connection/Transfer-Encoding 同理交给连接层自己管。
     */
    private fun copyRequestHeader(conn: HttpURLConnection, key: String, value: String) {
        when {
            key.equals("Accept-Encoding", ignoreCase = true) -> Unit
            key.equals("Host", ignoreCase = true) -> Unit
            key.equals("Content-Length", ignoreCase = true) -> Unit
            key.equals("Connection", ignoreCase = true) -> Unit
            key.equals("Transfer-Encoding", ignoreCase = true) -> Unit
            else -> runCatching { conn.setRequestProperty(key, value) }
        }
    }

    /** 字节级删除 HTML 里的 <meta http-equiv="Content-Security-Policy"> 标签（大小写不敏感）。 */
    private fun stripMetaCsp(body: ByteArray): ByteArray {
        var pos = 0
        val out = java.io.ByteArrayOutputStream(body.size)
        while (pos < body.size) {
            val metaStart = indexOfIgnoreCase(body, "<meta".toByteArray(), pos)
            if (metaStart == null) {
                out.write(body, pos, body.size - pos)
                break
            }
            out.write(body, pos, metaStart - pos)
            var gt = metaStart
            while (gt < body.size && body[gt].toInt() != '>'.code) gt++
            if (gt >= body.size) {
                out.write(body, metaStart, body.size - metaStart)
                break
            }
            val segment = body.copyOfRange(metaStart, gt + 1)
            if (indexOfIgnoreCase(segment, "content-security-policy".toByteArray()) != null) {
                pos = gt + 1 // 删除该 meta 标签
            } else {
                out.write(body, metaStart, gt + 1 - metaStart)
                pos = gt + 1
            }
        }
        return out.toByteArray()
    }

    /** 字节级把 eruda 脚本插到 <head>（或 <html>）之后；均无则插最前。避免页面编码问题。 */
    private fun injectErudaScript(body: ByteArray): ByteArray {
        val script = ERUDA_HEAD_SCRIPT.toByteArray(Charsets.UTF_8)
        val insertAt = tagInsertPosition(body, "<head".toByteArray(Charsets.US_ASCII))
            ?: tagInsertPosition(body, "<html".toByteArray(Charsets.US_ASCII))
            ?: 0
        return body.copyOfRange(0, insertAt) + script + body.copyOfRange(insertAt, body.size)
    }

    /** 找标签起始，返回该标签闭合 > 之后的位置（找不到返回 null）。忽略大小写。 */
    private fun tagInsertPosition(body: ByteArray, tag: ByteArray): Int? {
        val idx = indexOfIgnoreCase(body, tag) ?: return null
        var gt = idx
        while (gt < body.size && body[gt].toInt() != '>'.code) gt++
        if (gt >= body.size) return null
        return gt + 1
    }

    /** 忽略 ASCII 大小写的字节序列查找（实现见 [BrowserInternals.indexOfIgnoreCase]）。 */
    private fun indexOfIgnoreCase(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int? =
        BrowserInternals.indexOfIgnoreCase(haystack, needle, from)

    private fun toLowerAscii(b: Byte): Byte = BrowserInternals.toLowerAscii(b)

    /** 事件归因前缀（按 WebView 反查会话 id）。多会话时 console/network 必须能分辨来源。 */
    private fun sessionTagFor(view: WebView?): String {
        if (view == null) return ""
        val s = sessions.firstOrNull { it.view === view } ?: return ""
        return "[会话#${s.id}] "
    }

    /** console 回调拿不到 WebView，只能按 sourceId 的 host 匹配会话（同一站点的脚本都在同一 host 下）。 */
    private fun sessionTagForUrl(sourceUrl: String?): String {
        val host = runCatching { java.net.URI(sourceUrl ?: "") .host }.getOrNull().orEmpty()
        if (host.isEmpty()) return ""
        val hit = sessions.firstOrNull { it.url.contains("//$host") } ?: return ""
        return "[会话#${hit.id}] "
    }

    private fun recordConsole(tag: String, msg: String) {
        synchronized(consoleLock) {
            consoleMessages.addFirst(tag + msg)
            while (consoleMessages.size > MAX_EVENT_BUFFER) consoleMessages.removeLast()
        }
    }

    private fun recordNetwork(tag: String, msg: String) {
        synchronized(networkLock) {
            networkEvents.addFirst(tag + msg)
            while (networkEvents.size > MAX_EVENT_BUFFER) networkEvents.removeLast()
        }
    }

    /** evaluateJavascript 返回值是 JSON 编码的字符串，这里解码成人类可读结果（实现见 [BrowserInternals.formatJsResult]）。 */
    private fun formatJsResult(raw: String): String = BrowserInternals.formatJsResult(raw)

    // ── JS 注入脚本 ──────────────────────────────────────────

    /** DOM 快照：收集可交互元素到 window.__browserSnapshot 并返回编号列表。
     *  递归穿透 shadow DOM 与同域 iframe（GitHub / Primer 等大量用 shadow root 封装控件）。 */
    private val SNAPSHOT_JS = """(function() {
  if (!window.__browserSnapshot) window.__browserSnapshot = [];
  var arr = window.__browserSnapshot; arr.length = 0;
  var out = [], seen = new Set();
  var SEL = 'a,button,input,select,textarea,summary,label,[role="button"],[role="link"],[role="textbox"],[role="combobox"],[role="menuitem"],[role="checkbox"],[role="radio"],[role="switch"],[contenteditable="true"],[onclick],[tabindex]';
  function walk(root) {
    if (!root) return;
    try {
      var q = root.querySelectorAll(SEL);
      q.forEach(function(el) {
        if (seen.has(el)) return; seen.add(el);
        var r; try { r = el.getBoundingClientRect(); } catch(e) { r = {width:0,height:0}; }
        if (r.width < 4 || r.height < 4) return;
        arr.push(el);
        var text = '';
        try {
          var _tp = el.type ? String(el.type).toLowerCase() : '';
          var _nm = el.name ? String(el.name).toLowerCase() : '';
          var _ac = (el.getAttribute('autocomplete')||'').toLowerCase();
          var _sensitive = _tp === 'password' || /password/.test(_ac) || /token|secret|passwd|credential/.test(_nm);
          text = _sensitive ? (el.value ? '<sensitive:filled>' : '<sensitive:empty>')
            : (el.innerText || el.value || el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.getAttribute('name') || el.getAttribute('id') || '').replace(/\s+/g,' ').trim().slice(0,100);
        } catch(e) {}
        out.push({
          tag: el.tagName ? el.tagName.toLowerCase() : '?',
          text: text,
          href: (el.getAttribute && el.getAttribute('href')) || '',
          type: el.type || ''
        });
        // 立刻钻入 open shadowRoot：必须与 tree 模式**同一顺序**，
        // 否则两种模式给出的 #编号会指向不同元素（同一次快照的编号必须互操作）。
        try { if (el.shadowRoot) walk(el.shadowRoot); } catch(e) {}
      });
      // 递归同域 iframe（跨域拿不到 contentDocument，静默忽略）
      if (root === document) {
        try {
          var frames = document.querySelectorAll('iframe, frame');
          frames.forEach(function(f) {
            try { var doc = f.contentDocument; if (doc) walk(doc); } catch(e) {}
          });
        } catch(e) {}
      }
    } catch(e) {}
  }
  walk(document);
  return JSON.stringify(out);
})()"""

    /** 按 ref 找元素。优先级：
     *  1. `#N`：快照编号（window.__browserSnapshot[N]）
     *  2. 纯文本不含任何选择器字符 / 空格 优先：递归 shadow+iframe 按「可见文本 / innerText / aria-label / placeholder / name / id / value」模糊匹配
     *  3. 其他：先当 CSS 选择器试（querySelector，穿透 shadow+iframe），失败再 fallback 到文本匹配
     *  找不到/失效时返回错误 JSON。 */
    private const val RESOLVE_PREFIX = """(function() {
  var ref = @@0@@;
  var el = null;
  var SEL_A = 'a,button,input,select,textarea,summary,label,[role="button"],[role="link"],[role="textbox"],[role="combobox"],[role="menuitem"],[role="checkbox"],[role="radio"],[role="switch"],[contenteditable="true"],[onclick],[tabindex]';
  function flatten(root, out) {
    out = out || [];
    if (!root) return out;
    try {
      var q = root.querySelectorAll(SEL_A);
      q.forEach(function(n) { out.push(n); });
      q.forEach(function(n) { try { if (n.shadowRoot) flatten(n.shadowRoot, out); } catch(e) {} });
      if (root === document) {
        try {
          var fs = document.querySelectorAll('iframe, frame');
          fs.forEach(function(f) { try { var d = f.contentDocument; if (d) flatten(d, out); } catch(e) {} });
        } catch(e) {}
      }
    } catch(e) {}
    return out;
  }
  function textOf(n) {
    try {
      return ((n.innerText || n.textContent || n.value || n.getAttribute && (n.getAttribute('aria-label') || n.getAttribute('placeholder') || n.getAttribute('name') || n.getAttribute('id') || n.getAttribute('title')) || '').replace(/\s+/g,' ').trim().toLowerCase());
    } catch(e) { return ''; }
  }
  function queryAll(css) {
    var all = flatten(document);
    var hit = [];
    // 先在每个宿主根上原生 querySelector（支持 :scope / nth-child 等），再加上 shadow/iframe 兜底
    try { var n = document.querySelector(css); if (n) hit.push(n); } catch(e) {}
    if (hit.length) return hit;
    // 兜底：对扁平列表用 matches（注意跨 shadow 时原生 matches 只看同一根，所以不强求）
    all.forEach(function(n) {
      try { if (n.matches && n.matches(css)) hit.push(n); } catch(e) {}
    });
    return hit;
  }
  function findByText(txt) {
    var t = txt.replace(/\s+/g,' ').trim().toLowerCase();
    if (!t) return null;
    var all = flatten(document);
    var exact = [], contains = [];
    all.forEach(function(n) {
      var s = textOf(n);
      if (!s) return;
      if (s === t) exact.push(n);
      else if (s.indexOf(t) !== -1 || t.indexOf(s) !== -1) contains.push(n);
    });
    var cand = exact.concat(contains);
    if (!cand.length) return null;
    // 取可点击/可视面积最大的那个作为主命中
    cand.sort(function(a,b) {
      function area(n){ try{var r=n.getBoundingClientRect(); return r.width*r.height;}catch(e){return 0;} }
      return area(b) - area(a);
    });
    return cand[0];
  }
  var looksLikeCss = /[.#\[\]>:*,>+~()=|^$\s]/.test(ref);
  var __idx = /^#\s*(\d+)$/.exec(ref) || /^\[\s*(\d+)\s*\]$/.exec(ref);
  if (__idx) {
    var i = parseInt(__idx[1],10); el = (window.__browserSnapshot||[])[i]||null;
  } else if (looksLikeCss) {
    try {
      var hs = queryAll(ref);
      if (hs && hs.length) el = hs[0];
      else el = findByText(ref);
    } catch(e) { el = findByText(ref); }
  } else {
    el = findByText(ref);
    if (!el) { try { el = document.querySelector(ref); } catch(e) {} }
  }
  if (!el) return JSON.stringify({error:'找不到目标元素: '+ref + '（可用 browser_snapshot 查看编号列表，或用 browser_evaluate 执行 JS 直接操作）'});
  if (el.isConnected === false) return JSON.stringify({error:'元素已失效（页面已刷新/导航），请先重新 browser_snapshot'});
"""

    private val CLICK_JS = RESOLVE_PREFIX + """
  el.scrollIntoView({block:'center', inline:'center'});
  el.click();
  return JSON.stringify({ok:true, tag: el.tagName.toLowerCase(), text:(el.innerText||el.value||'').trim().slice(0,50)});
})()"""

    private val TYPE_JS = RESOLVE_PREFIX + """
  var text = @@1@@;
  el.focus();
  if (el instanceof HTMLSelectElement) {
    el.value = text; el.dispatchEvent(new Event('change',{bubbles:true}));
  } else if (el instanceof HTMLTextAreaElement || el instanceof HTMLInputElement) {
    var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var setter = Object.getOwnPropertyDescriptor(proto,'value').set;
    setter.call(el, text);
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
  } else {
    el.textContent = text; el.dispatchEvent(new Event('input',{bubbles:true}));
  }
  return JSON.stringify({ok:true, tag: el.tagName.toLowerCase()});
})()"""

    /** 与 [RESOLVE_PREFIX] 同构，但目标是「当前焦点元素」（没有焦点则 body），供按键使用。 */
    private val RESOLVE_FOCUS_PREFIX = """(function() {
  var el = document.activeElement || document.body;
  if (!el || !el.isConnected) el = document.body;
  if (!el) return JSON.stringify({error:'页面没有可接收按键的元素'});
"""

    /**
     * 读取网页会话列表。
     *
     * 收集规则：容器选择器先取**直接子节点**（为空时退到常见可点元素）；每个候选若内部还有更像条目的
     * 元素（a[href] / role=option|tab|treeitem / data-testid 含 conv|session|chat|history），就用那个。
     * 标题优先 aria-label/title，其次**第一行**文本——条目里常混着悬浮菜单文字（重命名/删除…），
     * 整段取会把菜单一起带出来；命中 ≥2 个菜单词的候选按菜单剔除。
     * 选中态看自身及前 12 个后代的 aria-current/aria-selected 或 class 里的 active/selected/current/checked。
     */
    private val SESSION_LIST_JS = """(function() {
  var sel = @@0@@;
  var root = null;
  try { root = document.querySelector(sel); } catch(e) { return JSON.stringify({error:'选择器语法错误: ' + sel}); }
  // 容器找不到时下探：站点改版后外层容器类名常变，但「会话条目列表」多在含 history/session/conv 的容器里。
  // 逐级尝试常见候选，命中即用；仍找不到才报错（附原选择器便于校正）。
  if (!root) {
    var fallbacks = ['nav[class*=history] ul','[class*=history-list]','[class*=history] [class*=list]','[class*=session-list]','[class*=conv-list]','[class*=conversation] ul','aside ul','nav ul'];
    for (var fi=0; fi<fallbacks.length; fi++) {
      try { var fr = document.querySelector(fallbacks[fi]); if (fr && fr.children && fr.children.length) { root = fr; break; } } catch(e2) {}
    }
  }
  if (!root) return JSON.stringify({error:'页面上找不到该选择器对应的元素: ' + sel + '（可在浏览器页用 browser_snapshot 确认）'});
  function rawText(n) { try { return (n.innerText || n.textContent || ''); } catch(e) { return ''; } }
  function flat(t) { return (t || '').replace(/\s+/g, ' ').trim(); }
  function titleOf(n) {
    var al = '';
    try { al = (n.getAttribute && (n.getAttribute('aria-label') || n.getAttribute('title'))) || ''; } catch(e) {}
    var t = flat(al);
    if (!t) {
      var parts = rawText(n).split('\n').map(flat).filter(function(x){ return x; });
      t = parts.length ? parts[0] : flat(rawText(n));
    }
    return t.slice(0, 60);
  }
  var MENU_WORDS = /(重命名|删除|置顶|取消置顶|分享|导出|批量|归档|复制链接|移动|标记)/;
  function looksLikeMenu(t) {
    var hits = t.match(new RegExp(MENU_WORDS.source, 'g'));
    return hits ? hits.length >= 2 : false;
  }
  function pickItem(node) {
    try {
      var inner = node.querySelector('a[href],[role="option"],[role="tab"],[role="treeitem"],[data-testid*="conv"],[data-testid*="session"],[data-testid*="chat"],[data-testid*="history"]');
      if (inner) return inner;
    } catch(e) {}
    return node;
  }
  function isActive(n) {
    try {
      var stack = [n];
      var kids = n.querySelectorAll('*');
      for (var z = 0; z < kids.length && z < 12; z++) stack.push(kids[z]);
      // 选中类常标在条目的父级 li/容器上（自己这层只有标题文本）：向上最多看 4 级，不越过容器本身
      var anc = n.parentElement;
      for (var a = 0; a < 4 && anc && anc !== root; a++) { stack.push(anc); anc = anc.parentElement; }
      for (var s = 0; s < stack.length; s++) {
        var el = stack[s];
        var cur = el.getAttribute && el.getAttribute('aria-current');
        var sel2 = el.getAttribute && el.getAttribute('aria-selected');
        if (cur === 'true' || cur === 'page' || sel2 === 'true') return true;
        var cls = (el.className && typeof el.className === 'string') ? el.className : '';
        if (/(^|[\s_-])(active|selected|current|checked)([\s_-]|$)/i.test(cls)) return true;
      }
      return false;
    } catch(e) { return false; }
  }
  var roots = [];
  var kids = root.children || [];
  for (var i = 0; i < kids.length; i++) roots.push(kids[i]);
  if (!roots.length) {
    var q = root.querySelectorAll('a,button,li,[role="option"],[role="tab"],[role="treeitem"]');
    for (var j = 0; j < q.length; j++) roots.push(q[j]);
  }
  // 会话条目的**稳定标识**：优先 data-* 属性，其次 href 里的 cid/id/conversation 段。
  // 侧边栏会按活跃度重排（当前会话上浮），下标会漂移——绑定必须靠这个 key，不能靠 index。
  // 站点没有稳定 id 时返回空串，由上层回退用标题做键。
  function keyOf(n) {
    var attrs = ['data-id','data-conv-id','data-conversation-id','data-session-id','data-chat-id','data-key'];
    var stack = [n];
    try { var kids = n.querySelectorAll('*'); for (var z=0; z<kids.length && z<8; z++) stack.push(kids[z]); } catch(e) {}
    try { var anc = n.parentElement; for (var a=0; a<3 && anc; a++) { stack.push(anc); anc = anc.parentElement; } } catch(e) {}
    for (var i=0;i<stack.length;i++) {
      var el = stack[i];
      for (var j=0;j<attrs.length;j++) {
        var v = el.getAttribute && el.getAttribute(attrs[j]);
        if (v && String(v).trim()) return String(v).trim();
      }
      var href = (el.getAttribute && el.getAttribute('href')) || '';
      if (href) {
        var m = href.match(/[?&](?:cid|conversation_id|conv_id|chat_id|id)=([^&#]+)/);
        if (m) return decodeURIComponent(m[1]);
        var m2 = href.match(/\/(?:chat|c|conversation|session)\/([A-Za-z0-9_-]{6,})/);
        if (m2) return m2[1];
      }
    }
    return '';
  }
  var out = [];
  for (var k = 0; k < roots.length; k++) {
    var node = pickItem(roots[k]);
    var t = titleOf(node);
    if (!t || looksLikeMenu(t)) continue;
    out.push({ index: out.length, title: t, active: isActive(node), siteKey: keyOf(node) });
  }
  return JSON.stringify({ ok: true, items: out, count: out.length });
})()"""

    /**
     * 点击网页会话列表里的第 N 条（与 [SESSION_LIST_JS] 用同一套收集与过滤规则，保证 index 指向同一个元素）。
     *
     * @@2@@ 为 app 侧快照里该条的标题：非空时先按 index 定位、标题对不上再按标题找，
     * 都对不上则报错不点击（宁可不切，也不能切进错误的会话）。
     * 点击派发完整指针/鼠标序列（pointerdown → mousedown → pointerup → mouseup → click）：
     * 部分站点条目只响应 mousedown/pointerdown，单发 el.click() 切不过去。
     */
    private val SESSION_CLICK_JS = """(function() {
  var sel = @@0@@, want = @@1@@, wantTitle = @@2@@, wantKey = @@3@@;
  var root = null;
  try { root = document.querySelector(sel); } catch(e) { return JSON.stringify({error:'选择器语法错误: ' + sel}); }
  // 与 SESSION_LIST_JS 同款容器下探（读取与点击必须指向同一个根，否则 index 对不上）
  if (!root) {
    var fallbacks = ['nav[class*=history] ul','[class*=history-list]','[class*=history] [class*=list]','[class*=session-list]','[class*=conv-list]','[class*=conversation] ul','aside ul','nav ul'];
    for (var fi=0; fi<fallbacks.length; fi++) {
      try { var fr = document.querySelector(fallbacks[fi]); if (fr && fr.children && fr.children.length) { root = fr; break; } } catch(e2) {}
    }
  }
  if (!root) return JSON.stringify({error:'页面上找不到该选择器对应的元素: ' + sel});
  function rawText(n) { try { return (n.innerText || n.textContent || ''); } catch(e) { return ''; } }
  function flat(t) { return (t || '').replace(/\s+/g, ' ').trim(); }
  function titleOf(n) {
    var al = '';
    try { al = (n.getAttribute && (n.getAttribute('aria-label') || n.getAttribute('title'))) || ''; } catch(e) {}
    var t = flat(al);
    if (!t) {
      var parts = rawText(n).split('\n').map(flat).filter(function(x){ return x; });
      t = parts.length ? parts[0] : flat(rawText(n));
    }
    return t.slice(0, 60);
  }
  var MENU_WORDS = /(重命名|删除|置顶|取消置顶|分享|导出|批量|归档|复制链接|移动|标记)/;
  function looksLikeMenu(t) {
    var hits = t.match(new RegExp(MENU_WORDS.source, 'g'));
    return hits ? hits.length >= 2 : false;
  }
  function pickItem(node) {
    try {
      var inner = node.querySelector('a[href],[role="option"],[role="tab"],[role="treeitem"],[data-testid*="conv"],[data-testid*="session"],[data-testid*="chat"],[data-testid*="history"]');
      if (inner) return inner;
    } catch(e) {}
    return node;
  }
  var roots = [];
  var kids = root.children || [];
  for (var i = 0; i < kids.length; i++) roots.push(kids[i]);
  if (!roots.length) {
    var q = root.querySelectorAll('a,button,li,[role="option"],[role="tab"],[role="treeitem"]');
    for (var j = 0; j < q.length; j++) roots.push(q[j]);
  }
  function keyOf(n) {
    var attrs = ['data-id','data-conv-id','data-conversation-id','data-session-id','data-chat-id','data-key'];
    var stack = [n];
    try { var kids = n.querySelectorAll('*'); for (var z=0; z<kids.length && z<8; z++) stack.push(kids[z]); } catch(e) {}
    try { var anc = n.parentElement; for (var a=0; a<3 && anc; a++) { stack.push(anc); anc = anc.parentElement; } } catch(e) {}
    for (var i=0;i<stack.length;i++) {
      var el = stack[i];
      for (var j=0;j<attrs.length;j++) {
        var v = el.getAttribute && el.getAttribute(attrs[j]);
        if (v && String(v).trim()) return String(v).trim();
      }
      var href = (el.getAttribute && el.getAttribute('href')) || '';
      if (href) {
        var m = href.match(/[?&](?:cid|conversation_id|conv_id|chat_id|id)=([^&#]+)/);
        if (m) return decodeURIComponent(m[1]);
        var m2 = href.match(/\/(?:chat|c|conversation|session)\/([A-Za-z0-9_-]{6,})/);
        if (m2) return m2[1];
      }
    }
    return '';
  }
  var items = [];
  for (var k = 0; k < roots.length; k++) {
    var node = pickItem(roots[k]);
    var t = titleOf(node);
    if (!t || looksLikeMenu(t)) continue;
    items.push({ el: node, title: t, key: keyOf(node) });
  }
  // 定位优先级：稳定 siteKey（站点 data-id/href）> 标题 > (index + 标题校验)。
  // siteKey 由 app 侧给出：有稳定 id 时是 data-id/cid，没有时上层已回退填成标题
  // （见 Tabs.mergeWebSessions 的 siteKey.ifEmpty { item.title }）。所以匹配要两级：
  //  1) 先按站点稳定 key 精确匹配（仅对 key 非空的条目）；
  //  2) 匹配不到 → 把 wantKey 当标题再匹配一次（无稳定 id 的站点走这条）。
  // 若把「一级没命中」直接当失败返回，无 id 站点会**永远切不了会话**。
  if (wantKey) {
    var byKey = -1;
    for (var q = 0; q < items.length; q++) { if (items[q].key && items[q].key === wantKey) { byKey = q; break; } }
    if (byKey < 0) {
      for (var qt = 0; qt < items.length; qt++) { if (items[qt].title === wantKey) { byKey = qt; break; } }
    }
    if (byKey < 0) return JSON.stringify({error:'找不到该会话（key/标题=' + wantKey + '）：站点可能已删除它或已改名，请刷新会话页后重试'});
    want = byKey;
  } else {
    if (want < 0 || want >= items.length) {
      if (wantTitle) {
        for (var t = 0; t < items.length; t++) { if (items[t].title === wantTitle) { want = t; break; } }
      }
      if (want < 0 || want >= items.length) return JSON.stringify({error:'会话序号越界: ' + want + '（共 ' + items.length + ' 条，列表可能已变化，请刷新会话页）'});
    }
    if (wantTitle && items[want].title !== wantTitle) {
      var byTitle = -1;
      for (var t2 = 0; t2 < items.length; t2++) { if (items[t2].title === wantTitle) { byTitle = t2; break; } }
      if (byTitle >= 0) want = byTitle;
      // 站点会自动改名会话（首条消息后命名等），本地标题快照可能已过期：
      // 无稳定 key 时标题对不上仍按位置点（拒绝会让站点停在「新对话」，语义相反），靠刷新列表自愈。
    }
  }
  var el = items[want].el;
  try { el.scrollIntoView({block:'center'}); } catch(e) {}
  try {
    var r = el.getBoundingClientRect();
    var cx = Math.round((r.left + r.right) / 2), cy = Math.round((r.top + r.bottom) / 2);
    function fireMouse(type) {
      el.dispatchEvent(new MouseEvent(type, {bubbles:true, cancelable:true, view:window, button:0, buttons:1, clientX:cx, clientY:cy}));
    }
    function firePointer(type) {
      if (!window.PointerEvent) return;
      el.dispatchEvent(new PointerEvent(type, {bubbles:true, cancelable:true, view:window, pointerId:1, pointerType:'mouse', isPrimary:true, button:0, buttons:1, clientX:cx, clientY:cy}));
    }
    firePointer('pointerdown');
    fireMouse('mousedown');
    firePointer('pointerup');
    fireMouse('mouseup');
    el.click();
  } catch(e) { return JSON.stringify({error:'点击失败: ' + (e && e.message)}); }
  return JSON.stringify({ok:true, title: items[want].title});
})()"""

    /** 按键：向当前焦点元素（或 body）派发完整的键盘事件序列。 */
    private val PRESS_KEY_JS = RESOLVE_FOCUS_PREFIX + """
  var key = @@0@@, code = @@1@@;
  var opts = {key: key, code: key, keyCode: code, which: code, bubbles: true, cancelable: true};
  ['keydown','keypress','keyup'].forEach(function(t){ el.dispatchEvent(new KeyboardEvent(t, opts)); });
  return JSON.stringify({ok:true, tag: el.tagName.toLowerCase(), key: key});
})()"""

    /** 悬停：派发鼠标移入事件（很多菜单/tooltip 只认 mouseover）。 */
    private val HOVER_JS = RESOLVE_PREFIX + """
  var r = el.getBoundingClientRect();
  var opts = {bubbles:true, cancelable:true, clientX: r.left + r.width/2, clientY: r.top + r.height/2};
  ['mouseover','mouseenter','mousemove'].forEach(function(t){ el.dispatchEvent(new MouseEvent(t, opts)); });
  return JSON.stringify({ok:true, tag: el.tagName.toLowerCase()});
})()"""

    /** 下拉选择：按 value 命中，其次按可见文本命中。 */
    private val SELECT_JS = RESOLVE_PREFIX + """
  var want = @@1@@;
  if (el.tagName !== 'SELECT') return JSON.stringify({error:'目标不是 <select>（实际 <' + el.tagName.toLowerCase() + '>），请用 browser_click'});
  var hit = null;
  for (var i=0;i<el.options.length;i++){ if (el.options[i].value === want) { hit = el.options[i]; break; } }
  if (!hit) { for (var j=0;j<el.options.length;j++){ if ((el.options[j].text||'').trim() === want) { hit = el.options[j]; break; } } }
  if (!hit) {
    var opts = [];
    for (var k=0;k<el.options.length;k++) opts.push(el.options[k].value + '|' + (el.options[k].text||'').trim());
    return JSON.stringify({error:'没有匹配的选项: ' + want + '；可选值：' + opts.slice(0,20).join('，')});
  }
  el.value = hit.value;
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
  return JSON.stringify({ok:true, tag:'select', text:hit.value});
})()"""

    private const val SCROLL_JS = """(function() {
  var dir = @@0@@, amount = @@1@@;
  var doc = document.documentElement || document.body;
  if (dir === 'top') { window.scrollTo(0,0); }
  else if (dir === 'bottom') { window.scrollTo(0, Math.max(doc.scrollHeight, document.body?document.body.scrollHeight:0)); }
  else if (dir === 'up') { window.scrollBy(0, -(amount||600)); }
  else { window.scrollBy(0, (amount||600)); }
  return JSON.stringify({ok:true, y: Math.round(window.scrollY||doc.scrollTop||0)});
})()"""

    private val HIGHLIGHT_JS = RESOLVE_PREFIX + """
  el.scrollIntoView({block:'center'});
  el.style.outline = '3px solid #ff3b30';
  el.style.outlineOffset = '2px';
  return JSON.stringify({ok:true, tag: el.tagName.toLowerCase()});
})()"""

    private const val STORAGE_JS = """(function() {
  var key = @@0@@;
  var out = {};
  try {
    if (key) { out[key] = localStorage.getItem(key); }
    else { for (var i=0;i<localStorage.length;i++){ var k=localStorage.key(i); out[k]=localStorage.getItem(k); } }
  } catch(e) { out._error = String(e); }
  return JSON.stringify(out);
})()"""

    /** 把 @N@ 占位符替换为参数。 */

    // ── browser-use 缝合：DOM 树 / Markdown 提取 / 等待 / 标注 / 页面状态 ──

    /** 层级 DOM 树快照：在扁平编号基础上增加缩进层级、xpath 与 ARIA 角色。
     *  编号顺序与 [SNAPSHOT_JS] 完全一致（depth-first，穿透 shadow/iframe），
     *  保证 #编号 引用与 browser_snapshot 互操作。 */
    private val SNAPSHOT_TREE_JS = """(function() {
  if (!window.__browserSnapshot) window.__browserSnapshot = [];
  var arr = window.__browserSnapshot; arr.length = 0;
  var out = [], seen = new Set();
  var SEL = 'a,button,input,select,textarea,summary,label,[role="button"],[role="link"],[role="textbox"],[role="combobox"],[role="menuitem"],[role="checkbox"],[role="radio"],[role="switch"],[contenteditable="true"],[onclick],[tabindex]';
  function xpathOf(el) {
    var parts = [];
    var node = el;
    while (node && node.nodeType === 1) {
      if (node === document.documentElement) break;
      var host = node.host;
      if (host) { parts.unshift('::shadow'); node = host; continue; }
      var parent = node.parentNode;
      if (!parent) break;
      var tag = node.tagName.toLowerCase();
      var id = node.getAttribute && node.getAttribute('id');
      if (id && !/^\d/.test(id)) { parts.unshift('//' + tag + '[@id="' + id + '"]'); break; }
      var idx = 1; var sib = node.previousElementSibling;
      while (sib) { if (sib.tagName === node.tagName) idx++; sib = sib.previousElementSibling; }
      parts.unshift(tag + '[' + idx + ']');
      node = parent;
    }
    return '/' + parts.join('/');
  }
  function walk(root, depth) {
    if (!root) return;
    try {
      var q = root.querySelectorAll(SEL);
      var list = []; q.forEach(function(el) { list.push(el); });
      list.forEach(function(el) {
        if (seen.has(el)) return; seen.add(el);
        var r; try { r = el.getBoundingClientRect(); } catch(e) { r = {width:0,height:0}; }
        if (r.width < 4 || r.height < 4) return;
        arr.push(el);
        var text = '';
        try {
          var _tp = el.type ? String(el.type).toLowerCase() : '';
          var _nm = el.name ? String(el.name).toLowerCase() : '';
          var _ac = (el.getAttribute('autocomplete')||'').toLowerCase();
          var _sensitive = _tp === 'password' || /password/.test(_ac) || /token|secret|passwd|credential/.test(_nm);
          text = _sensitive ? (el.value ? '<sensitive:filled>' : '<sensitive:empty>')
            : (el.innerText || el.value || el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.getAttribute('name') || el.getAttribute('id') || '').replace(/\s+/g,' ').trim().slice(0,120);
        } catch(e) {}
        var role = (el.getAttribute && el.getAttribute('role')) || '';
        var xp = '';
        try { xp = xpathOf(el); } catch(e) {}
        out.push({
          index: arr.length - 1,
          depth: depth,
          tag: el.tagName ? el.tagName.toLowerCase() : '?',
          text: text,
          href: (el.getAttribute && el.getAttribute('href')) || '',
          type: el.type || '',
          role: role,
          xpath: xp
        });
        try { if (el.shadowRoot) walk(el.shadowRoot, depth + 1); } catch(e) {}
      });
      if (root === document) {
        try {
          var fs = document.querySelectorAll('iframe, frame');
          fs.forEach(function(f) {
            try { var doc = f.contentDocument; if (doc) walk(doc, depth + 1); } catch(e) {}
          });
        } catch(e) {}
      }
    } catch(e) {}
  }
  walk(document, 0);
  // depth 重新计算为「在收集集合内的 DOM 祖先个数」，才是模型预期的"层级树"；
  // 之前用的 shadow/iframe 嵌套层数会让普通元素全部是 0。
  var __set = new Set(arr);
  for (var __i = 0; __i < out.length; __i++) {
    var __d = 0, __p = arr[__i] ? arr[__i].parentNode : null;
    while (__p) { if (__set.has(__p)) __d++; __p = __p.parentNode; }
    out[__i].depth = __d;
  }
  return JSON.stringify(out);
})()"""

    /** Markdown 正文提取（browser-use 的 markdown extraction）：把 article/main/指定节点
     *  的可见内容转成 Markdown（标题 #、链接 [t](href)、列表 -、段落空行分隔）。 */
    private val MARKDOWN_JS = """(function() {
  var sel = @@0@@, startPos = @@1@@, maxLen = @@2@@;
  var root = null;
  if (sel) { try { root = document.querySelector(sel); } catch(e) {} }
  if (!root || !root.isConnected) {
    root = document.querySelector('article') || document.querySelector('main') || document.body;
  }
  // text/plain 等无结构页面兜底：body 可能不存在或不可连接，退到文档根节点
  if (!root || !root.isConnected) root = document.documentElement;
  if (!root) return JSON.stringify({error:'无内容节点'});
  var out = [];
  function esc(t){ return (t||'').replace(/\s+/g,' ').trim(); }
  function textOf(n){ try { return (n.innerText||n.textContent||'').replace(/\s+/g,' ').trim(); } catch(e){ return ''; } }
  function walk(node) {
    if (!node) return;
    if (node.nodeType === 3) { var t = esc(node.nodeValue); if (t) out.push(t); return; }
    if (node.nodeType !== 1) return;
    var tag = node.tagName.toLowerCase();
    if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'svg' ||
        tag === 'nav' || tag === 'footer' || tag === 'header' || tag === 'aside' ||
        tag === 'form' || tag === 'button' || tag === 'select' || tag === 'textarea') return;
    var children = node.childNodes;
    if (tag === 'a') {
      var href = node.getAttribute && node.getAttribute('href');
      var t = esc(textOf(node));
      if (t && href && !/^(javascript|#)/.test(href)) { out.push('[' + t + '](' + href + ')'); return; }
    }
    if (/^h[1-6]$/.test(tag)) {
      var n = parseInt(tag[1],10); var t = esc(textOf(node));
      if (t) { out.push(''); out.push('#'.repeat(n) + ' ' + t); out.push(''); }
      return;
    }
    if (tag === 'li') { var t = esc(textOf(node)); if (t) { out.push('- ' + t); return; } }
    if (tag === 'img') {
      var alt = node.getAttribute && (node.getAttribute('alt')||'');
      var src = node.getAttribute && (node.getAttribute('src')||'');
      var w = node.naturalWidth || 0, h = node.naturalHeight || 0;
      if (src) {
        out.push('![图片' + (alt ? (' ' + alt) : '') + '](' + src + ')' + ((w && h) ? (' ' + w + 'x' + h) : ''));
      } else if (alt) {
        out.push('[图片: ' + alt + ']');
      }
      return;
    }
    if (/^(p|div|section|table|ul|ol|pre|blockquote)$/.test(tag)) out.push('');
    for (var i=0;i<children.length;i++) walk(children[i]);
    if (/^(p|div|section|table|ul|ol|pre|blockquote)$/.test(tag)) out.push('');
  }
  walk(root);
  // 兜底：结构化遍历一无所获时（text/plain、纯文本 pre、innerText 不可用），
  // 直接取整页 textContent——textContent 不依赖渲染，比 innerText 可靠。
  if (out.length === 0) {
    var t = '';
    try { t = (root.textContent || '').replace(/[\t ]+/g,' ').trim(); } catch(e) {}
    if (t) out.push(t);
  }
  var md = out.join('\n').replace(/\n{3,}/g,'\n\n').trim();
  // 直接返回请求的窗口（而不是永远从 0 截）：长文才能真的分页读完，不再有 30000 字符天花板。
  var from = Math.max(0, Math.min(startPos || 0, md.length));
  return JSON.stringify({total: md.length, from: from, content: md.substr(from, maxLen)});
})()"""

    /** 等待条件检查：selector 是否存在/可见/含指定文本。返回布尔供轮询。 */
    private val WAIT_JS = """(function() {
  var sel = @@0@@, mode = @@1@@, text = @@2@@;
  var el = null, count = -1;
  try { el = document.querySelector(sel); count = document.querySelectorAll(sel).length; } catch(e) {}
  var found = !!el;
  var visible = false;
  if (el) {
    try {
      var r = el.getBoundingClientRect();
      var cs = getComputedStyle(el);
      visible = r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none';
    } catch(e) {}
  }
  var textOk = true;
  if (text && el) { try { textOk = (el.innerText||el.textContent||'').indexOf(text) !== -1; } catch(e) { textOk = false; } }
  var ok = false;
  if (mode === 'visible') ok = found && visible;
  else if (mode === 'hidden') ok = !found || !visible;
  else if (mode === 'text') ok = found && visible && textOk;
  else ok = found;
  return JSON.stringify({ok: ok, found: found, visible: visible, textOk: textOk, count: count});
})()"""

    /** 把交互元素编号叠加到页面（browser-use 的 screenshot highlighting）：
     *  给 window.__browserSnapshot 每个仍连接的可见元素加固定定位角标 #N。 */
    private val ANNOTATE_JS = """(function() {
  var arr = window.__browserSnapshot || [];
  var layer = document.getElementById('__browseruse_annotate_layer');
  if (layer) layer.remove();
  layer = document.createElement('div');
  layer.id = '__browseruse_annotate_layer';
  layer.style.cssText = 'position:fixed;top:0;left:0;width:0;height:0;z-index:2147483646;pointer-events:none;';
  document.documentElement.appendChild(layer);
  var n = 0;
  arr.forEach(function(el, i) {
    if (!el || !el.isConnected) return;
    var r; try { r = el.getBoundingClientRect(); } catch(e) { return; }
    if (r.width < 2 || r.height < 2) return;
    var badge = document.createElement('div');
    badge.style.cssText = 'position:fixed;left:' + r.left + 'px;top:' + r.top + 'px;' +
      'z-index:2147483647;background:#ff3b30;color:#fff;font:bold 12px/1.4 sans-serif;' +
      'padding:1px 5px;border-radius:3px;pointer-events:none;white-space:nowrap;';
    badge.textContent = '#' + i;
    layer.appendChild(badge);
    n++;
  });
  return JSON.stringify({ok:true, count:n});
})()"""

    /** 移除标注层。 */
    private val CLEAR_ANNOTATE_JS = """(function() {
  var layer = document.getElementById('__browseruse_annotate_layer');
  if (layer) layer.remove();
  return 'ok';
})()"""

    /** 页面状态摘要（browser-use 的 browser state summary）。 */
    private val PAGE_STATE_JS = """(function() {
  var interactive = 0;
  var SEL = 'a,button,input,select,textarea,summary,label,[role="button"],[role="link"],[role="textbox"],[role="combobox"],[role="menuitem"],[role="checkbox"],[role="radio"],[role="switch"],[contenteditable="true"],[onclick],[tabindex]';
  try { interactive = document.querySelectorAll(SEL).length; } catch(e) {}
  var meta = '';
  try { var m = document.querySelector('meta[name="description"]'); meta = m ? (m.content||'') : ''; } catch(e) {}
  var bodyLen = 0;
  try { bodyLen = (document.body ? (document.body.innerText||'').length : 0); } catch(e) {}
  return JSON.stringify({
    url: location.href,
    title: document.title || '',
    readyState: document.readyState,
    interactive: interactive,
    viewportW: window.innerWidth,
    viewportH: window.innerHeight,
    scrollY: Math.round(window.scrollY || 0),
    scrollH: document.documentElement ? document.documentElement.scrollHeight : 0,
    bodyTextLen: bodyLen,
    metaDescription: meta.slice(0, 300)
  });
})()"""


    private fun buildJs(template: String, vararg args: String): String {
        var js = template
        args.forEachIndexed { i, arg -> js = js.replace("@@$i@@", arg) }
        return js
    }

    /** 常见按键的 keyCode（实现与单测见 [BrowserInternals.keyCodeOf]）。 */
    private fun keyCodeOf(key: String): Int = BrowserInternals.keyCodeOf(key)

    /** 转义成 JS 字符串字面量（供注入脚本使用）。 */
    private fun jsonStringLiteral(s: String): String =
        McpJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), JsonPrimitive(s))

    /** 语法定位探针：在与 [wrapEval] 相同的解析上下文里编译用户表达式，返回 syntax: 前缀的错误信息。 */
    private fun syntaxProbeJs(js: String): String =
        "(function(){try{ new Function('return (' + " + jsonStringLiteral(js) + " + ')'); return 'ok'; }" +
            "catch(__e){ return 'syntax:' + ((__e&&__e.message)||String(__e)); }})()"

    /**
     * 页面脚本被 CSP 禁用时的统一提示；返回 null 表示可以继续执行。
     *
     * 带一次「自愈复检」：探针偶发失败（页面刚加载、回调竞争）时不应把整页永久判死。
     */
    private suspend fun scriptBlockedMsg(s: Session, op: String): String? {
        if (!s.scriptBlocked) return null
        val probe = evalJs(s, "1")
        if (probe != null && probe != "null") {
            s.scriptBlocked = false
            return null
        }
        return "$op 无法执行：该页面脚本被站点 CSP（sandbox）禁用，evaluateJavascript 会静默返回 null。" +
            "请改用 browser_extract（自动 HTTP 直连）或 http_request 获取内容；" +
            "若是普通页面，先 browser_reload 重新加载再试。"
    }

    /** 执行 JS 并等回调（超时返回 null）。仅主线程调用。 */
    private suspend fun evalJs(s: Session, js: String): String? = withTimeoutOrNull(EVAL_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            s.view.evaluateJavascript(js) { result -> cont.resume(result) }
        }
    }

    /**
     * 解析 evalJs 回调结果，自动剥离 WebView 的双层 JSON 编码。
     *
     * evaluateJavascript 的回调值是「脚本返回值的 JSON 编码」；本项目所有注入脚本
     * 以 `JSON.stringify(...)` 返回字符串，因此回调结果是双重编码：
     * ```
     * 脚本对象 {ok:true} --stringify--> "{\"ok\":true}" --WebView编码--> "\"{\\\"ok\\\":true}\""
     * ```
     * 直接 parseToJsonElement 会得到 JsonPrimitive（字符串壳）而非 JsonObject，
     * 后续 .jsonObject 抛异常——这正是 browser_extract 曾把成功结果当失败抛出的根因。
     * 本函数检测到字符串壳且内容以 { [ 开头时再解一层；单层编码天然兼容。
     */
    private fun unwrapEvalResult(raw: String?): JsonElement? = BrowserInternals.unwrapEvalResult(raw)

    /** 状态驱动回路：动作后追加页面状态摘要（URL/标题/可交互数/前 20 个元素），
     *  免去 LLM 显式再调 browser_snapshot；返回空串表示当前无页面可描述。 */
    private suspend fun describePageState(s: Session): String {
        if (s.url.isEmpty()) return ""
        val metaRaw = evalJs(s, PAGE_STATE_JS) ?: return ""
        val o = unwrapEvalResult(metaRaw) as? JsonObject ?: return ""
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        // 刷新编号数组并取前 20 个可交互元素（同时保证后续 #编号 引用有效）。
        // 这里顺便用同一份数组作为「可交互元素」计数——与 browser_snapshot 完全同口径，
        // 不再用 PAGE_STATE_JS 里只数主文档的 interactive（两个数字对不上会让模型以为元素丢了）。
        val all = evalJs(s, SNAPSHOT_JS)?.let { raw -> unwrapEvalResult(raw) as? JsonArray }
        val total = all?.size ?: 0
        val top = all?.take(20)?.mapIndexedNotNull { i, el ->
            val e = el.jsonObject
            val tag = e["tag"]?.jsonPrimitive?.contentOrNull ?: "?"
            val text = e["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val href = e["href"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val label = when {
                text.isNotEmpty() -> text
                href.isNotEmpty() -> href
                else -> "<无文本>"
            }
            "#$i <$tag> $label"
        }.orEmpty()
        return buildString {
            append("页面状态：URL=${str("url")} 标题=${str("title").ifEmpty { "<无标题>" }}")
            append(" 加载=${str("readyState")} 可交互元素=${total}个（含 shadow/iframe，与 browser_snapshot 同口径）")
            append(" 视口=${str("viewportW")}x${str("viewportH")} 滚动y=${str("scrollY")} 正文=${str("bodyTextLen")}字符")
            val meta = str("metaDescription")
            if (meta.isNotEmpty()) append(" 描述=$meta")
            if (top.isNotEmpty()) {
                append("\n可交互元素（共 $total 个，显示前 ${top.size} 个；编号即 browser_click/type 的 ref）：\n")
                append(top.joinToString("\n"))
            }
        }
    }

    /** 动作结果 + 页面状态组合：状态非空则追加，否则原样返回。 */
    private suspend fun appendPageState(s: Session, base: String): String {
        val state = describePageState(s)
        return if (state.isNotEmpty()) "$base\n\n$state" else base
    }

    /** 解析 {ok/error/...} 形式的动作结果，输出面向 LLM 的描述。 */
    private fun formatActionResult(raw: String?): String {
        val r = raw?.trim().orEmpty()
        val obj = runCatching { McpJson.parseToJsonElement(r).jsonObject }.getOrNull()
        if (obj == null) return "执行结果：$r"
        obj["error"]?.jsonPrimitive?.contentOrNull?.let { return "执行失败：$it" }
        if (obj["ok"]?.jsonPrimitive?.contentOrNull == "true" || obj["ok"] != null) {
            val tag = obj["tag"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val y = obj["y"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return buildString {
                append("执行成功")
                if (tag.isNotEmpty()) append("（<$tag>）")
                if (text.isNotEmpty()) append("：$text")
                if (y.isNotEmpty()) append(" 当前滚动位置 y=$y")
            }
        }
        return "执行结果：$r"
    }
}
