package com.mcp.floatwin

import android.app.Activity
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mcp.R
import com.mcp.browser.WebBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 浏览器浮窗门面：管理**两个相互独立**的浮窗实例。
 *
 * 为什么要两个实例：普通浏览浮窗搬运的是用户正在看的 NORMAL 会话，
 * Web 自动化浮窗搬运的是模型正在操作的 AUTOMATION 会话。两者要求同时存在、
 * 互不干扰——共用一套单例状态会互相覆盖位置、标题、WebView 容器。
 *
 * | 实例 | 会话用途 | 位置记忆 key | 标题 |
 * |---|---|---|---|
 * | normal | NORMAL | browser_float | 跟随页面标题 |
 * | automation | AUTOMATION | browser_float_auto | 固定「Web 自动化」 |
 *
 * 分层（每层可单独复用）：
 * 1. [FloatHost]            —— 窗口从哪来：系统级 overlay / 应用内，自动降级；
 * 2. [FloatDragController]  —— 拖拽、边缘吸附、回弹、位置记忆；
 * 3. [FloatResizeController]—— 右下角手柄缩放；
 * 4. [FloatWindowInstance]  —— 单个浮窗的卡片 UI 与 WebView 容器互转。
 */
object BrowserFloatWindow {

    private const val TAG = "BrowserFloat"

    /** 起始页地址（与 MainActivity.HOME_PAGE_URL 一致；普通浮窗空白时兜底加载）。 */
    const val HOME_PAGE = "file:///android_asset/browser_home.html"

    /** 位置/尺寸记忆用的 SharedPreferences 文件名（与两个控制器保持一致）。 */
    const val PREFS_NAME = "float_window"

    /** 普通浏览浮窗（用户正在看的页面）。 */
    private val normal = FloatWindowInstance(
        automation = false,
        prefKey = "browser_float",
        ballKey = "browser_float_ball",
    )

    /** Web 自动化浮窗（模型正在操作的页面）。 */
    private val automation = FloatWindowInstance(
        automation = true,
        prefKey = "browser_float_auto",
        ballKey = "browser_float_auto_ball",
    )

    /** 普通浮窗是否显示（兼容旧语义）。 */
    val isShowing: Boolean get() = normal.isShowing

    val isNormalShowing: Boolean get() = normal.isShowing

    val isAutomationShowing: Boolean get() = automation.isShowing

    // （原 isAnyShowing 已删除：0 调用者；UI 用 isNormalShowing / isAutomationShowing 区分用途。）

    /** 弹出普通浏览器浮窗。 */
    fun show(activity: Activity, onExpand: () -> Unit): String? =
        normal.show(activity, onExpand)

    /**
     * 弹出 Web 自动化专属浮窗：搬运 AUTOMATION 会话，与普通浮窗并存互不干扰。
     *
     * 主要用于观察自动化进度、或在该页面里手动登录站点（浮窗可输入）。
     */
    fun showAutomation(activity: Activity, onExpand: () -> Unit): String? =
        automation.show(activity, onExpand)

    /** 关闭普通浮窗（兼容旧调用）。 */
    fun close() = normal.close()

    fun closeNormal() = normal.close()

    fun closeAutomation() = automation.close()

    // （原 closeAll 已删除：0 调用者，两个实例各自在 onActivityDestroyed 里收尾。）

    /**
     * Activity 重建后重新绑定两个实例。
     *
     * [onExpand] 用于普通浮窗（收浮窗 + 回全屏），[onExpandAutomation] 用于自动化浮窗（只切 Tab）。
     * 两者都必须随 Activity 重建刷新——旧实现给 automation 传 null「保留原回调」，
     * 结果它一直强引用上一个已销毁的 Activity。
     */
    fun onActivityCreated(activity: Activity, onExpand: () -> Unit, onExpandAutomation: (() -> Unit)? = null) {
        normal.onActivityCreated(activity, onExpand)
        automation.onActivityCreated(activity, onExpandAutomation ?: onExpand)
    }

    /** Activity 销毁回调（两个实例共用同一判定逻辑）。 */
    fun onActivityDestroyed(activity: Activity, isFinishing: Boolean) {
        normal.onActivityDestroyed(activity, isFinishing)
        automation.onActivityDestroyed(activity, isFinishing)
    }
}

/**
 * 单个浮窗实例：卡片 UI（把手 / 标题 / 回全屏 / 缩小 / 关闭）+ 与 [WebBrowser] 的容器互转。
 *
 * 关键约束：一个 View 只能有一个父容器。所以「全屏 ⇄ 浮窗」的搬运必须走
 * WebBrowser 的 reattach/attach 方法（先摘后挂），只改引用会出现「浮窗空白、全屏容器还挂着 WebView」。
 *
 * @param automation true 表示这是 Web 自动化浮窗（搬 AUTOMATION 会话、标题固定、关闭时不交还会话）
 * @param prefKey    卡片位置/尺寸的记忆 key（两个实例各记各的）
 * @param ballKey    悬浮球位置的记忆 key
 */
private class FloatWindowInstance(
    private val automation: Boolean,
    private val prefKey: String,
    private val ballKey: String,
) {

    private val tag: String get() = if (automation) "FloatAuto" else "FloatNormal"

    private var card: View? = null
    private var controller: FloatDragController? = null
    private var resizer: FloatResizeController? = null
    private var ball: View? = null
    private var ballController: FloatDragController? = null
    private var titleView: TextView? = null
    private var ownerRef: WeakReference<Activity>? = null
    private var onExpandRequest: (() -> Unit)? = null
    /** 是否处于最大化（仅自动化浮窗用）。 */
    private var maximized = false
    /** 最大化前的尺寸与位置，用于还原。 */
    private var preMaxSize: Pair<Int, Int>? = null
    private var preMaxPos: Pair<Int, Int>? = null

    /** 浮窗（卡片或悬浮球）是否正在显示。 */
    val isShowing: Boolean get() = card != null || ball != null

    /**
     * 弹出浮窗（须在主线程）。
     *
     * @return null 表示一切正常；非 null 为需要提示用户的文案（注意：可能仍显示成功——
     *         例如没有悬浮窗权限时自动降级为应用内浮窗，此时返回引导文案）。
     */
    fun show(activity: Activity, onExpand: () -> Unit): String? {
        if (isShowing) return null
        val systemLevel = OverlayPermission.granted(activity)
        val host: FloatHost = if (systemLevel) OverlayWindowHost(activity) else InAppHost(activity)
        // 尺寸：优先用上次缩放记忆的尺寸（缩成球再展开、关闭再打开都该保持），
        // 无记忆时才用默认比例。此前 sizeOf() 每次都重算默认值，
        // 导致用户调好的尺寸一缩球就丢。
        val size = restoredSize(activity, host)
        val view = LayoutInflater.from(activity).inflate(R.layout.float_browser_card, null)
        val hostView = view.findViewById<FloatTouchFrameLayout>(R.id.floatWebHost)
        val handle = view.findViewById<View>(R.id.floatTitleBar)
        val title = view.findViewById<TextView>(R.id.floatTitle)

        val ctl = FloatDragController(host, view, handle, prefKey)
        try {
            ctl.attach(size.first, size.second)
        } catch (e: Exception) {
            Log.w(tag, "浮窗挂载失败: " + e.message, e)
            runCatching { host.detach() }
            return "浮窗打开失败：" + (e.message ?: e.javaClass.simpleName)
        }

        val fallbackTitle = activity.getString(
            if (automation) R.string.float_automation_title else R.string.float_browser_title
        )
        view.findViewById<MaterialButton>(R.id.floatBtnExpand).setOnClickListener {
            if (automation) {
                // 自动化浮窗：最大化 / 还原。
                // 注意**不能**走 switchToTab(BROWSER_TAB) —— 浏览器 Tab 显示的是
                // NORMAL 会话，与自动化会话是两个独立 WebView，跳过去看到的是
                // 用户自己的页面，不是模型正在操作的页面。
                toggleMaximize(activity, ctl, host)
            } else {
                // 普通浮窗：回全屏（WebView 交还 browser_content 并切到浏览器 Tab）
                val expand = onExpandRequest
                if (expand != null) expand() else close()
            }
        }
        view.findViewById<MaterialButton>(R.id.floatBtnClose).setOnClickListener { close() }
        // overlay 焦点策略：手指落进浮窗内部 → 窗口可聚焦（网页能输入）；
        // 点到浮窗之外（ACTION_OUTSIDE）→ 让出焦点，把输入法还给宿主 App
        hostView.onTouchDown = { host.setContentFocused(true) }

        // 右下角缩放手柄：拖动改变浮窗尺寸（与整体拖拽分离，互不干扰）
        val resizeHandle = view.findViewById<View>(R.id.floatResizeHandle)
        val rs = FloatResizeController(
            host = host,
            root = view,
            handle = resizeHandle,
            prefsKey = prefKey,
            getPos = { Pair(ctl.posX, ctl.posY) },
            onSizeChanged = { w, h -> ctl.syncSize(w, h) },
        )
        rs.attach()
        resizer = rs

        // 缩小为悬浮球
        view.findViewById<MaterialButton>(R.id.floatBtnMinimize).setOnClickListener { minimize() }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) host.setContentFocused(false)
            false
        }

        card = view
        controller = ctl
        titleView = title
        ownerRef = WeakReference(activity)
        onExpandRequest = onExpand

        if (automation) {
            // 自动化浮窗标题固定（页面标题由驱动操作频繁变化，跟随会让标题乱跳）
            title.text = fallbackTitle
            WebBrowser.attachAutomationContainer(hostView)
        } else {
            title.text = WebBrowser.currentTitle().ifEmpty { fallbackTitle }
            // 普通浮窗：标题跟着导航走（onPageChangedExtra 是「附加」监听，不顶掉主页态回调）
            WebBrowser.onPageChangedExtra = { _, purpose ->
                // 只跟普通会话的标题：自动化会话的导航不该改这个浮窗的标题
                if (purpose == com.mcp.browser.SessionPurpose.NORMAL) {
                    val t = WebBrowser.currentTitle()
                    titleView?.text = if (t.isBlank()) fallbackTitle else t
                }
            }
            WebBrowser.reattachContainer(hostView)
            // ── 起始页兜底 ──
            // 起始页（browser_home.html）是全屏页 home_overlay 里独立的一层，
            // 不在 WebBrowser 的容器链里，所以 reattachContainer 搬不动它——
            // 若此时 WebView 还是空白/起始态，浮窗里就只剩一片空白。
            // 这里直接把起始页加载进 WebBrowser 自己的 WebView，浮窗即有内容。
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                val url = runCatching { WebBrowser.currentUrl() }.getOrDefault("")
                if (url != BrowserFloatWindow.HOME_PAGE && (url.isBlank() || url.startsWith("about:"))) {
                    // 必须先 ensureStarted：浏览器被释放过（appContext=null）时直接 navigate 会抛「未初始化」，
                    // 表现出来就是"浮窗一片空白"。
                    val err = WebBrowser.ensureStarted(activity.applicationContext)
                    if (err == null) WebBrowser.navigate(BrowserFloatWindow.HOME_PAGE, null, null)
                    else Log.w(tag, "浮窗起始页兜底失败：$err")
                }
            }
        }

        Log.i(tag, "浮窗已显示（" + (if (systemLevel) "系统级" else "应用内降级") + "）")
        return if (systemLevel) null else OverlayPermission.guideText(activity)
    }

    /**
     * 缩小为悬浮球：把卡片窗口收掉，改挂一个 56dp 的小球。
     *
     * 关键：WebView 必须先交还容器（否则随卡片一起被摘掉会崩），
     * 但**不关闭** WebBrowser 会话——这样从小球展开回来时页面还在。
     */
    private fun minimize() {
        val activity = ownerRef?.get() ?: return
        val ctl = controller ?: return
        val oldHost = ctl.currentHost
        // 1) WebView 交还/摘出容器（页面状态保留在 WebBrowser 里）
        if (automation) {
            // 自动化会话不能被留在即将被移除的卡片里（会变成孤儿，之后重建会话时又挂到死容器）
            WebBrowser.detachAutomationContainer()
        } else {
            val target = activity.findViewById<FrameLayout>(R.id.browser_content)
            if (target != null) WebBrowser.reattachContainer(target)
        }
        // 2) 拆掉卡片窗口
        resizer?.detach()
        resizer = null
        ctl.detach()
        card = null
        controller = null
        titleView = null
        // 3) 换挂悬浮球（沿用同一宿主类型，保持系统级/应用内一致）
        val host: FloatHost = if (oldHost.isSystemLevel) OverlayWindowHost(activity) else InAppHost(activity)
        val ballView = LayoutInflater.from(activity).inflate(R.layout.float_browser_ball, null)
        val bctl = FloatDragController(host, ballView, ballView, ballKey)
        val size = dp(activity, 56f)
        // 注意：必须用 controller.onClick 而非 ballView.setOnClickListener ——
        // 控制器在 ACTION_DOWN 就消费了事件，View 的 click 监听永远收不到。
        bctl.onClick = { expandFromBall() }
        bctl.attach(size, size)
        ball = ballView
        ballController = bctl
        Log.i(tag, "已缩小为悬浮球")
    }

    /** 从悬浮球展开回浮窗。 */
    private fun expandFromBall() {
        val activity = ownerRef?.get() ?: return
        val bctl = ballController ?: return
        val expandCb = onExpandRequest
        bctl.detach()
        ball = null
        ballController = null
        // 复用 show() 的卡片构建流程；此时 card 已置空，isShowing 为 false 可正常进入
        val tip = show(activity) { expandCb?.invoke() ?: close() }
        Log.i(tag, "已从悬浮球展开（tip=" + tip + "）")
    }

    /**
     * 关闭浮窗：普通浮窗先把 WebView 交还全屏浏览器区域，再拆掉窗口。
     * 顺序不能反——先拆窗口的话，WebView 会连着卡片一起被摘下来，全屏浏览器就空了。
     */
    fun close() {
        // 球形态下直接收球（没有 WebView 要交还）
        ballController?.let {
            it.detach()
            ball = null
            ballController = null
            Log.i(tag, "悬浮球已关闭")
        }
        val ctl = controller ?: return
        if (automation) {
            // 自动化浮窗：WebView 必须先从承载它的浮窗容器摘下。
            // 否则 ctl.detach() 移除 overlay 容器时，WebView 会跟着变成孤儿，
            // 下次 showAutomation 重新挂载时状态异常（表现为无法唤醒/空白）。
            // 会话本身保留（驱动仍可继续用），只是暂时无容器。
            WebBrowser.detachAutomationContainer()
            Log.i(tag, "自动化浮窗已关闭（会话保留给驱动继续使用）")
        } else {
            WebBrowser.onPageChangedExtra = null
            val target = ownerRef?.get()?.findViewById<FrameLayout>(R.id.browser_content)
            // Activity 已 finishing 时不要再把 WebView 交还给它——那会让静态引用捏住一个将死 Activity 的视图树
            if (target != null && (ownerRef?.get()?.isFinishing != true)) WebBrowser.reattachContainer(target)
        }
        ctl.detach()
        resizer?.detach()
        resizer = null
        card = null
        controller = null
        titleView = null
        ownerRef = null
        onExpandRequest = null
        Log.i(tag, "浮窗已关闭")
    }

    /**
     * Activity 重建后的重新绑定。
     *
     * 系统级浮窗挂在 WindowManager 上，本身不受影响，只需刷新宿主引用与「回全屏」回调；
     * 应用内浮窗必须重新挂到新窗口上。
     *
     * @param onExpand 为 null 表示保留原回调（自动化浮窗用）
     */
    fun onActivityCreated(activity: Activity, onExpand: (() -> Unit)?) {
        ownerRef = WeakReference(activity)
        if (onExpand != null) onExpandRequest = onExpand
        // 悬浮球形态：controller 已被 minimize() 置空，必须单独处理 ballController，
        // 否则球留在一个已销毁 Activity 的 content 里，isShowing 仍为 true 还会让
        // MainActivity 跳过 WebView 重挂 → 浏览器 Tab 空白。
        if (ball != null) {
            ballController?.let { bc -> if (!bc.currentHost.isSystemLevel) bc.reattach(InAppHost(activity)) }
            return
        }
        val ctl = controller ?: return
        if (ctl.currentHost.isSystemLevel) return
        ctl.reattach(InAppHost(activity))
    }

    /**
     * Activity 销毁回调。
     * - 应用内浮窗：父视图没了，必然跟着关闭；
     * - 系统级浮窗：继续存活（「浮在其他 App 之上」正是它的意义），只是暂时没有
     *   「回全屏」的目标，按钮退化为关闭，等 App 再次启动时会重新绑定。
     */
    fun onActivityDestroyed(activity: Activity, isFinishing: Boolean) {
        if (!isFinishing) return // 配置变化：新 Activity 的 onCreate 会重新挂载
        // 球形态：没有 controller，直接收球（否则静态引用会一直捏着旧 Activity 的视图）
        ballController?.let { bc ->
            if (!bc.currentHost.isSystemLevel) {
                bc.detach()
                ball = null
                ballController = null
            } else if (ownerRef?.get() === activity) {
                ownerRef = null
            }
            return
        }
        val ctl = controller ?: return
        if (!ctl.currentHost.isSystemLevel) {
            close()
            return
        }
        if (ownerRef?.get() === activity) {
            ownerRef = null
            onExpandRequest = null
        }
    }

    /**
     * 自动化浮窗最大化 / 还原。
     *
     * 最大化 = 撑满宿主可用区域（留出状态栏，避免被盖住）。
     * 再点还原回最大化前的尺寸与位置。
     */
    private fun toggleMaximize(activity: Activity, ctl: FloatDragController, host: FloatHost) {
        if (maximized) {
            preMaxSize?.let { ctl.syncSize(it.first, it.second) }
            preMaxPos?.let { ctl.moveTo(it.first, it.second) }
            maximized = false
            preMaxSize = null
            preMaxPos = null
            Log.i(tag, "已还原浮窗尺寸")
            return
        }
        preMaxSize = ctl.currentSize()
        preMaxPos = Pair(ctl.posX, ctl.posY)
        val barH = runCatching {
            val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
        val w = host.hostWidth()
        val h = (host.hostHeight() - barH).coerceAtLeast(dp(activity, 160f))
        ctl.syncSize(w, h)
        ctl.moveTo(0, barH)
        maximized = true
        Log.i(tag, "浮窗已最大化 ${w}x$h")
    }

    /**
     * 取本次显示应使用的尺寸：优先上次缩放记忆，否则默认比例。
     *
     * 记忆值要 clamp 到当前宿主可用区域——换设备/旋转屏幕后旧尺寸可能超出。
     */
    private fun restoredSize(activity: Activity, host: FloatHost): Pair<Int, Int> {
        val prefs = activity.getSharedPreferences(BrowserFloatWindow.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val w = prefs.getInt(prefKey + "_w", 0)
        val h = prefs.getInt(prefKey + "_h", 0)
        if (w <= 0 || h <= 0) return sizeOf(host)
        val maxW = host.hostWidth()
        val maxH = host.hostHeight()
        if (maxW <= 0 || maxH <= 0) return sizeOf(host)
        // 分屏/小窗时宿主可能比最小尺寸还窄：coerceIn(min, max) 在 min > max 时会抛
        // IllegalArgumentException（旧实现在 try/catch 之外，直接崩）。这里把区间拉正。
        val minW = dp(activity, 200f)
        val minH = dp(activity, 160f)
        val loW = min(minW, maxW)
        val loH = min(minH, maxH)
        return Pair(w.coerceIn(loW, maxW), h.coerceIn(loH, maxH))
    }

    /** 浮窗默认尺寸：宽 86% 屏宽、高 50% 屏高（跟随宿主可用区域）。 */
    private fun sizeOf(host: FloatHost): Pair<Int, Int> {
        val w = min((host.hostWidth() * 0.86f).roundToInt(), host.hostWidth())
        val h = min((host.hostHeight() * 0.50f).roundToInt(), host.hostHeight())
        return Pair(w, h)
    }

    private fun dp(context: android.content.Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
