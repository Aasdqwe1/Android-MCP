package com.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mcp.browser.WebBrowser
import com.mcp.deepseek.AuthPrefs
import com.mcp.floatwin.BrowserFloatWindow
import com.mcp.floatwin.OverlayPermission
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREF_TAB_MODE = "tab_bar_mode"

        /** 进程身份：进程内恒定，跨进程重启变化。日志用它区分「进程重启」与「仅 Activity/页面重建」。 */
        val PROCESS_PID = android.os.Process.myPid()

        /** 内嵌浏览器 Tab（底部导航第 6 项，index=5；无 Fragment，直接显示隐藏的 browser_container）。 */
        const val BROWSER_TAB = 5

        /** 起始页资源地址与底色（底色与 browser_home.html 的 body 背景一致，避免加载瞬间白闪）。 */
        private const val HOME_PAGE_URL = "file:///android_asset/browser_home.html"
        private val HOME_PAGE_BG = 0xFF121018.toInt()

        // 外观主题偏好键（值：system / light / dark）
        const val PREF_THEME = "pref_theme"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    private lateinit var bottomNav: BottomNavigationView

    /** 内嵌浏览器 WebView 容器（可见 Tab 时显示，其余隐藏）。入口在会话窗口标题栏右侧按钮。 */
    private lateinit var browserContainer: ViewGroup

    /** 起始页容器（HTML 起始页的宿主，仅主页/起始页可见）。 */
    private lateinit var homeOverlay: FrameLayout

    /** 起始页 WebView：懒创建，见 [ensureHomeWeb]。 */
    private var homeWeb: WebView? = null

    private var currentTab = -1
    // 五个 Tab Fragment 长驻（add 一次，show/hide 切换）
    private lateinit var fragments: List<Fragment>

    // SAF 目录选择器
    private val safDirPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        SafManager.handlePickerResult(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("MCP_LIFE", "MainActivity.onCreate pid=$PROCESS_PID savedInstanceState=${savedInstanceState != null} tab=$currentTab", Throwable("MCP_LIFE"))
        // 先按保存的主题偏好应用 DayNight，再 inflate 视图，避免闪一下默认主题
        applyAppTheme()
        EdgeToEdge.apply(this)

        // 应用「信任所有证书」开关（若用户在设置中开启，则全局跳过 HTTPS 校验）
        CertBypass.apply(this)

        // WebView 进程级预热：Android 的 WebView 首次 new 时要加载 Chromium native
        // 库并初始化数据目录，耗时明显。若某个弹窗（如 Markdown 查看器）是进程里
        // 第一个创建 WebView 的地方，onPageFinished / evaluateJavascript 的时序会不稳，
        // 表现为「必须先打开聊天窗口（那里也建 WebView）才正常」。
        // 但**必须放在主线程**：WebView 的构造只能在 UI 线程调用。在后台线程 new 会让
        // WebViewChromium 的工厂停在「posted」状态，之后进程内任何 WebView 构造都会在
        // addJavascriptInterface 处抛
        //   IllegalStateException: AwContents must be created if we are not posting!
        // 外层包成 InflateException: Error inflating class android.webkit.WebView 直接 FATAL。
        // 表现为「Markdown 查看器弹窗一打开就崩」。预热本身仍然保留，只是改为在首帧之后
        // 由主线程执行（见下方 setContentView 之后的 post）。

        // 初始化 SAF 管理器
        SafManager.init(applicationContext)
        SandboxMounts.init(applicationContext)
        SafManager.onRequestPicker = { safDirPicker.launch(null) }

        // 后台清扫一次私有目录里「只增不减」的过程产物（浏览器截图/下载、录音、
        // 附件、run_code 失败目录）。放 IO 线程、runCatching 兜底，绝不影响启动。
        AppScope.launch {
            runCatching { StorageJanitor.sweep(applicationContext) }
        }

        setContentView(R.layout.activity_main)

        // WebView 预热：必须主线程 + 首帧之后（理由见上方 onCreate 开头的注释）。
        // 注意别在 Activity 创建过程中同步 new WebView——冷启动时系统还会恢复
        // Markdown 查看器这类 DialogFragment，两边同时构造 WebView 更容易撞上工厂未就绪。
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.webkit.WebView(applicationContext).destroy() }
        }

        // === 内嵌浏览器 WebView 挂载到页面区（browser_content；主页覆盖层/FAB/菜单悬浮其上）===
        browserContainer = findViewById(R.id.browser_container)
        // 普通浮窗显示时 NORMAL 会话的 WebView 由浮窗持有，不能抢回全屏区；
        // 否则用 reattachContainer 把浏览器区设为容器——它会连已有会话一起搬回来。
        // 注意：isShowing 现仅指**普通浮窗**；自动化浮窗持有的是 AUTOMATION 会话，
        // 与 browser_content 无关，所以这里无需考虑它。
        if (!BrowserFloatWindow.isNormalShowing) {
            WebBrowser.reattachContainer(findViewById(R.id.browser_content))
        }
        // Activity 重建（旋转 / 主题切换 / 低内存回收）后重新绑定宿主与「回全屏」回调。
        // 两个浮窗实例的回调都必须换成新 Activity 的 lambda，否则从悬浮球展开时会去操作已销毁的 Activity。
        BrowserFloatWindow.onActivityCreated(
            this,
            { restoreBrowserFromFloat() },
            { switchToTab(BROWSER_TAB) }
        )
        homeOverlay = findViewById<FrameLayout>(R.id.home_overlay)
        // 开发者工具（Eruda 抓包）由 AI 通过 browser_devtools 工具开关，默认关闭
        setupBrowserHome()
        // 页面状态变化 → 切换主页态/浏览态（地址栏仅主页可见）。
        // 必须按用途过滤：自动化会话的导航此前会把浏览器 Tab 顶成起始页/浏览页（还会顺带新建 homeWeb）。
        WebBrowser.onPageChanged = { url, purpose ->
            if (purpose == com.mcp.browser.SessionPurpose.NORMAL) {
                runOnUiThread { updateBrowserHomeState(url) }
            }
        }
        setupBrowserFab()

        // === 视图 ===
        bottomNav = findViewById(R.id.bottom_nav)
        val contentContainer = findViewById<FrameLayout>(R.id.content_container)

        // === 经典底栏选中 ===
        bottomNav.setOnItemSelectedListener { item ->
            val index = when (item.itemId) {
                R.id.nav_session  -> 0
                R.id.nav_todo     -> 1
                R.id.nav_tools    -> 2
                R.id.nav_log      -> 3
                R.id.nav_settings -> 4
                else -> return@setOnItemSelectedListener false
            }
            switchToTab(index)
            true
        }

        // === 系统窗口边距（含软键盘 IME） ===
        // 底栏为全宽、贴合屏幕底部（无侧边/底部留缝）；底部预留空间直接取底栏「真实测量高度(px)」，
        // 不再用硬编码的 96dp 估值，避免底部被顶出过大空白（之前大到能停航母）。
        val rootView = findViewById<FrameLayout>(R.id.root)

        // 最近一次派发的 insets。底栏高度是「测量后才有」的值，而 insets 只在变化时派发：
        // 必须缓存 insets，才能在底栏测量完成后用同一份数据重算，而不必等下一次（可能永远不来的）派发。
        var lastInsets: WindowInsetsCompat? = null

        // 依据当前窗口 insets 与底栏真实高度，计算内容区底部预留
        fun applyContentPadding(insets: WindowInsetsCompat) {
            lastInsets = insets
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            // 软键盘（IME）高度：边到边模式下框架不再自动 adjustResize/adjustPan，需手动处理。
            // 仅 API 30+（已 setDecorFitsSystemWindows(false)）需要手动处理；旧版本交给框架 adjustResize。
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ime.bottom else 0
            // 底栏实际占据的高度（含其内部为系统导航栏预留的内边距，由 BottomNavigationView 自行处理）
            val barHeight = bottomNav.height
            // 键盘弹出时：内容区底部预留 = IME 高度，使输入框/消息上移避开键盘；
            // 键盘收起时：预留 = 底栏高度，使内容不被底栏遮挡。
            // 注意：不再取 max(..., imeBottom) 后叠加——下方已「消费 IME」让 root 不再被系统缩小，
            // 若这里再叠加 imeBottom 会造成内容被过度上移、露出大块空白。
            val bottom = if (imeBottom > 0) imeBottom else barHeight
            contentContainer.setPadding(0, systemBars.top, 0, bottom)
            // 浏览器容器：顶部让出状态栏安全区（页面/工具栏都不被状态栏遮挡），
            // 安全区本身透明（容器无背景，透出主题背景色，深色模式不白屏）；底部避开底栏
            browserContainer.setPadding(0, systemBars.top, 0, bottom)
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            applyContentPadding(insets)
            // 消费软键盘（IME）的 inset：不要让系统因 IME 自动缩小 root，
            // 否则 BottomNavigationView（layout_gravity=bottom）会被顶到键盘上方。
            // 改为由上面手动给内容区加 padding 来实现「内容上移、底栏留在原位（被键盘覆盖）」。
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            insets.inset(0, 0, 0, ime.bottom)
        }

        // 底部预留依赖 bottomNav.height，而首帧派发时它还是 0 —— 顶部用的是 systemBars.top
        // （纯 inset 值，首帧就准确），底部却依赖测量结果，这种不对称正是「顶部正常、内容贴住底栏」的由来。
        //
        // 原先只在 post 里兜底一次，且取的是 getRootWindowInsets()：视图尚未 attach 时它返回 null，
        // 兜底就静默失效，而 insets 不变也不会再派发，底部预留便永久停在 0。
        // 改为监听底栏高度变化、用缓存的 insets 重算，测量一到就修正。
        bottomNav.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                lastInsets?.let { applyContentPadding(it) }
            }
        }
        bottomNav.post { lastInsets?.let { applyContentPadding(it) } }
        // 兜底触发一次派发，保证 lastInsets 至少被填充一次
        ViewCompat.requestApplyInsets(rootView)

        // === 初始化四个 Tab Fragment（确保各页面状态常驻，切 Tab 不重建）===
        // 取/建：首次新建并 add 到 nav_host；重建时从 FragmentManager 取回系统已恢复的实例（按 tag），避免旋转后重复 add。
        fragments = listOf(
            supportFragmentManager.findFragmentByTag("TAB0") ?: SessionFragment(),
            supportFragmentManager.findFragmentByTag("TAB1") ?: TodoFragment(),
            supportFragmentManager.findFragmentByTag("TAB2") ?: ToolsFragment(),
            supportFragmentManager.findFragmentByTag("TAB3") ?: LogFragment(),
            supportFragmentManager.findFragmentByTag("TAB4") ?: SettingsFragment()
        )
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().apply {
                fragments.forEachIndexed { i, f -> add(R.id.nav_host, f, "TAB$i") }
            }.commit()
        }
        // 恢复当前 Tab（旋转等场景），首启默认停在会话（0）
        currentTab = savedInstanceState?.getInt("currentTab", 0) ?: 0
        applyTabVisibility(currentTab)
    }

    // ═══════════════════════════════════════════
    //  公共方法
    // ═══════════════════════════════════════════

    /** 按保存的主题偏好应用 DayNight 模式（启动与重建时调用）。 */
    private fun applyAppTheme() {
        val theme = getSharedPreferences(PREF_TAB_MODE, Context.MODE_PRIVATE)
            .getString(PREF_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        AppCompatDelegate.setDefaultNightMode(mapNightMode(theme))
    }

    private fun mapNightMode(theme: String): Int = when (theme) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** 设置外观主题并持久化；会触发 Activity 重建以全局生效（会话状态由 SessionFragment 恢复）。 */
    fun setAppTheme(theme: String) {
        getSharedPreferences(PREF_TAB_MODE, Context.MODE_PRIVATE)
            .edit().putString(PREF_THEME, theme).apply()
        AppCompatDelegate.setDefaultNightMode(mapNightMode(theme))
    }

    // ═══════════════════════════════════════════
    //  内部逻辑
    // ═══════════════════════════════════════════

    /** 点击/底栏选中时：切换页面。仅 show/hide，不重建 Fragment；浏览器视图直接切换容器可见性。 */
    fun switchToTab(index: Int) {
        if (index == currentTab) return
        if (index != BROWSER_TAB && (index < 0 || index >= fragments.size)) return

        val prev = currentTab
        currentTab = index

        if (index == BROWSER_TAB) {
            // 全屏浏览器与浮窗互斥：进全屏前先收掉浮窗（WebView 容器交还 browser_content）
            BrowserFloatWindow.close()
            // 浏览器视图：隐藏所有 Fragment，显示 WebView 容器
            supportFragmentManager.beginTransaction().apply {
                fragments.forEach { hide(it) }
            }.commit()
            browserContainer.visibility = View.VISIBLE
        // 懒初始化 WebView（无工具调用时容器为空）+ 地址栏/主页态同步当前页
            lifecycleScope.launch {
                WebBrowser.ensureStarted(applicationContext)
                // 起始页是否显示由当前页 URL 决定（原生地址栏已换成 assets/browser_home.html）
                updateBrowserHomeState(WebBrowser.listTabs().firstOrNull()?.url.orEmpty())
            }
        } else {
            // show/hide：Fragment 视图常驻，切走再切回不丢失状态（输入框、列表、正在流的 SSE 等）。
            // prev 可能是 BROWSER_TAB（从浏览器视图切回）：所有 Fragment 已隐藏，只 show 目标即可，避免 fragments[5] 越界
            supportFragmentManager.beginTransaction().apply {
                show(fragments[index])
                if (prev != BROWSER_TAB) hide(fragments[prev])
                if (prev == BROWSER_TAB) browserContainer.visibility = View.GONE
            }.commit()
        }

        if (index < bottomNav.menu.size()) {
            bottomNav.menu.getItem(index).isChecked = true
        }
    }

    /**
     * 起始页（主页）：原生地址栏整体换成 assets/browser_home.html（深色卡片式起始页），
     * 页面唯一的原生交互是 [HomeBridge]：输入内容交回原生解析，网址直接打开、其余当搜索词。
     */
    private fun setupBrowserHome() {
        // 起始页的显示/隐藏由 onPageChanged → updateBrowserHomeState 统一控制，这里无需额外接线。
    }

    /**
     * 确保起始页 WebView 已创建并挂进 home_overlay（幂等）。
     *
     * 懒创建 + 代码里 new WebView 的原因：冷启动时系统可能同时在恢复 Markdown 查看器这类
     * DialogFragment，两边同时构造 WebView 容易撞上「WebView 工厂未就绪」（见 onCreate 注释），
     * 所以不在 XML 里写 WebView，只在真正要显示起始页时才建。
     */
    private fun ensureHomeWeb(): WebView {
        homeWeb?.let { return it }
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.setBackgroundColor(HOME_PAGE_BG) // 与起始页底色一致，避免加载完成前白闪
        web.addJavascriptInterface(HomeBridge(), "McpHome")
        web.loadUrl(HOME_PAGE_URL)
        homeOverlay.addView(
            web,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        homeWeb = web
        return web
    }

    /** 起始页（browser_home.html）调用原生的唯一入口；JS 侧用 window.McpHome.go(...) 调用。 */
    inner class HomeBridge {
        /**
         * 提交输入内容：
         * - 带协议（http/https）→ 原样打开；
         * - 形如域名（含点、无空格）→ 补 https；
         * - 其余 → 当搜索词（Bing；换搜索引擎只改这一行）。
         */
        @JavascriptInterface
        fun go(input: String) {
            // 解析规则与 WebBrowser 会话里的起始页共用一份实现（com.mcp.browser.resolveHomeInput）
            val target = com.mcp.browser.resolveHomeInput(input) ?: return
            runOnUiThread {
                lifecycleScope.launch {
                    WebBrowser.ensureStarted(applicationContext)?.let { err ->
                        Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    WebBrowser.navigate(target, null, null)
                }
            }
        }
    }

    /** 浏览器可拖动 FAB + 展开菜单（后退/前进/刷新/窗口切换/开发者工具/回到起始页）。 */
    private fun setupBrowserFab() {
        val fab = findViewById<FloatingActionButton>(R.id.browser_fab)
        val menu = findViewById<View>(R.id.browser_menu)
        val container = findViewById<FrameLayout>(R.id.browser_content)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // ── 可拖动 FAB：拖动移动位置；原地点击展开/收起菜单 ──
        var downX = 0f
        var downY = 0f
        var origX = 0f
        var origY = 0f
        fab.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    origX = fab.translationX
                    origY = fab.translationY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    // 边界限制（初始位置在容器右下角，translation 为相对偏移）
                    val m = (16 * resources.displayMetrics.density).toInt()
                    val maxX = (container.width - fab.width - m).coerceAtLeast(0)
                    val maxY = (container.height - fab.height - m).coerceAtLeast(0)
                    fab.translationX = (origX + dx).coerceIn(-maxX.toFloat(), 0f)
                    fab.translationY = (origY + dy).coerceIn(-maxY.toFloat(), 0f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dist = kotlin.math.hypot(event.rawX - downX, event.rawY - downY)
                    // 菜单固定定位在右下角（不跟随 FAB 拖动位置）
                    if (dist < touchSlop) {
                        menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }

        // 统一执行浏览器操作（suspend），并收起菜单
        fun runBrowser(block: suspend () -> Unit) {
            lifecycleScope.launch {
                WebBrowser.ensureStarted(applicationContext)?.let { return@launch }
                block()
            }
            menu.visibility = View.GONE
        }

        menu.findViewById<MaterialButton>(R.id.menu_back).setOnClickListener {
            runBrowser { WebBrowser.history("back", null, null) }
        }
        menu.findViewById<MaterialButton>(R.id.menu_forward).setOnClickListener {
            runBrowser { WebBrowser.history("forward", null, null) }
        }
        menu.findViewById<MaterialButton>(R.id.menu_reload).setOnClickListener {
            runBrowser { WebBrowser.reload(null, null) }
        }
        menu.findViewById<MaterialButton>(R.id.menu_home).setOnClickListener {
            runBrowser { WebBrowser.navigate("about:blank", null, null) }
        }

        // 自动化窗口：把 Web 自动化会话弹成专属浮窗（独立会话，不影响当前浏览）
        menu.findViewById<MaterialButton>(R.id.menu_automation).setOnClickListener {
            menu.visibility = View.GONE
            popOutAutomationWindow()
        }

        // 窗口切换：列出所有会话供选择
        menu.findViewById<MaterialButton>(R.id.menu_windows).setOnClickListener {
            lifecycleScope.launch {
                WebBrowser.ensureStarted(applicationContext)?.let { return@launch }
                val tabs = WebBrowser.listTabs()
                menu.visibility = View.GONE
                if (tabs.isEmpty()) {
                    Toast.makeText(this@MainActivity, "当前没有窗口", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val names = tabs.map { "[${it.index}] ${it.title.ifEmpty { it.url }}" }
                MaterialDialogs.choose(
                    context = this@MainActivity,
                    title = "窗口切换",
                    items = names,
                ) { which -> WebBrowser.switchSession(which) }
            }
        }

        // 开发者工具：打开/收起 Eruda 调试面板。
        // 用户与 AI 都能开关抓包注入（AI 走 browser_devtools）；用户点这个入口 = 明确要抓包，
        // 所以会开启注入并展开面板，长按则关闭注入。标签实时反映当前状态，避免"点了没反应"。
        val devBtn = menu.findViewById<MaterialButton>(R.id.menu_devtools)
        var panelVisible = false
        fun updateDevLabel() {
            devBtn.text = when {
                panelVisible -> "收起开发者工具"
                WebBrowser.devToolsEnabled -> "开发者工具"
                else -> "开发者工具（开启抓包）"
            }
        }
        devBtn.setOnClickListener {
            menu.visibility = View.GONE
            if (!WebBrowser.devToolsEnabled) {
                WebBrowser.setDevToolsEnabled(true)
                panelVisible = true
                updateDevLabel()
                WebBrowser.showDevToolsPanel(true)
                Toast.makeText(
                    this@MainActivity,
                    "已开启抓包并展开开发者工具面板（长按此按钮可关闭抓包）",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            panelVisible = !panelVisible
            updateDevLabel()
            WebBrowser.showDevToolsPanel(panelVisible)
            Toast.makeText(
                this@MainActivity,
                if (panelVisible) "已打开开发者工具面板" else "已收起开发者工具面板",
                Toast.LENGTH_SHORT
            ).show()
        }
        devBtn.setOnLongClickListener {
            menu.visibility = View.GONE
            WebBrowser.setDevToolsEnabled(false)
            panelVisible = false
            updateDevLabel()
            Toast.makeText(
                this@MainActivity,
                "已关闭抓包注入（刷新页面后恢复干净加载）",
                Toast.LENGTH_LONG
            ).show()
            true
        }
        updateDevLabel()
    }

    /** 切换主页态/浏览态：起始页（about:blank / 空 URL）显示 HTML 起始页，浏览页隐藏（页面全屏）。 */
    private fun updateBrowserHomeState(url: String) {
        val isHome = url.isBlank() || url.startsWith("about:")
        if (isHome) ensureHomeWeb()
        homeOverlay.visibility = if (isHome) View.VISIBLE else View.GONE
    }

    /** 统一设置各 Tab 可见性（activity 创建 / 重建后调用），并同步底栏选中态。 */
    private fun applyTabVisibility(active: Int) {
        currentTab = active
        if (active == BROWSER_TAB) {
            supportFragmentManager.beginTransaction().apply {
                fragments.forEach { hide(it) }
            }.commit()
            browserContainer.visibility = View.VISIBLE
        } else {
            if (active < 0 || active >= fragments.size) return
            browserContainer.visibility = View.GONE
            supportFragmentManager.beginTransaction().apply {
                fragments.forEachIndexed { i, f ->
                    if (i == active) show(f) else hide(f)
                }
            }.commit()
        }

        // 浏览器视图时无底栏选中项（浏览器不在底栏 5 项中），其余 Tab 同步高亮
        if (active < bottomNav.menu.size()) {
            bottomNav.menu.getItem(active).isChecked = true
        }
    }

    // ───────────────────────── 浏览器浮窗（长按会话窗口的浏览器入口弹出） ─────────────────────────

    /**
     * 长按会话窗口标题栏的浏览器入口：把内嵌浏览器弹成可拖拽浮窗。
     * 有悬浮窗权限 → 系统级浮窗（可盖在其他 App 之上）；无权限 → 自动降级为应用内小窗。
     */
    fun popOutBrowser() {
        if (BrowserFloatWindow.isShowing) {
            restoreBrowserFromFloat()
            return
        }
        lifecycleScope.launch {
            WebBrowser.ensureStarted(applicationContext)?.let { err ->
                Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                return@launch
            }
            val tip = BrowserFloatWindow.show(this@MainActivity) { restoreBrowserFromFloat() }
            when {
                tip == null -> Toast.makeText(
                    this@MainActivity,
                    getString(R.string.float_browser_open_tip),
                    Toast.LENGTH_SHORT
                ).show()

                // 没有悬浮窗权限：已降级为应用内小窗，用对话框给一条明确的开启路径
                !OverlayPermission.granted(this@MainActivity) -> MaterialDialogs.confirm(
                    context = this@MainActivity,
                    title = getString(R.string.float_browser_perm_title),
                    message = tip,
                    confirmText = getString(R.string.float_browser_perm_go),
                    cancelText = getString(R.string.float_browser_perm_later),
                    onConfirm = { OverlayPermission.request(this@MainActivity) }
                )

                // 有权限但打开失败等其它提示
                else -> Toast.makeText(this@MainActivity, tip, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 弹出 Web 自动化专属浮窗：显示模型正在操作的页面。
     *
     * 与普通浮窗的关键区别：搬运的是 AUTOMATION 会话（独立 WebView），
     * 因此不会打断/覆盖用户当前在浏览器 Tab 看的页面；反之亦然。
     * 主要用于观察自动化进度、或在该页面里手动登录站点。
     */
    /**
     * 确保自动化浮窗已显示（**不 toggle**）。
     *
     * 会话页点「网页会话」时用：那里只关心"把站点切到那条会话"，不能因为浮窗已显示就把它收掉。
     */
    fun showAutomationWindow() {
        if (!BrowserFloatWindow.isAutomationShowing) popOutAutomationWindow()
    }

    fun popOutAutomationWindow() {
        // 已显示时再点 = 收起（与普通浮窗的 toggle 语义一致），
        // 这样「关掉后想再打开」不会卡死在「已在显示」的提示上。
        if (BrowserFloatWindow.isAutomationShowing) {
            BrowserFloatWindow.closeAutomation()
            return
        }
        lifecycleScope.launch {
            val waCfg = AuthPrefs(this@MainActivity).getWebAutomationConfig()
            // UA 模式放在最前面：它是**全局**的，先应用就能让随后新建的会话直接用正确 UA，
            // 不必"先建再 reload"。旧实现是异步销毁旧会话等重建，且排在导航之后，
            // 结果下一次导航仍用旧 UA（用户看到的就是"开关没影响 UA"）。
            WebBrowser.setDesktopUaMode(waCfg.desktopMode)
            // 顺序很重要：**先弹浮窗**（showAutomation 内部会 attachAutomationContainer），
            // 再建自动化会话。反过来会在没有容器时创建 WebView——部分 ROM 上 loadUrl 静默无回调，
            // 表现就是"自动化打不开/一直加载中"。
            val floatTip = BrowserFloatWindow.showAutomation(this@MainActivity) {
                // 自动化浮窗的「回全屏」：切到浏览器 Tab 看自动化页面
                switchToTab(BROWSER_TAB)
            }
            WebBrowser.ensureAutomationStarted(applicationContext)?.let { err ->
                Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
                return@launch
            }
            // 再导航到配置的站点：自动化会话刚创建时是空白页，
            // 用户需要看到站点界面才能登录（这是该后端能工作的前提）。
            // 只在**不在该站点上**时才导航（按 scheme+host 判断，不比路径）——
            // 会话页 URL 随选中会话变化，按前缀比会把用户从选定的会话里踢回新会话页。
            val siteUrl = waCfg.siteUrl
            if (siteUrl.isNotBlank()) {
                if (!WebBrowser.automationOnSite(siteUrl)) {
                    WebBrowser.navigateAutomation(siteUrl)
                }
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "未配置站点地址，请到「设置 → LLM 后端 → Web 自动化」填写",
                    Toast.LENGTH_LONG,
                ).show()
            }
            if (floatTip != null) Toast.makeText(this@MainActivity, floatTip, Toast.LENGTH_LONG).show()
        }
    }

    /** 浮窗「回全屏」：收掉浮窗（WebView 交还 browser_content）并切到浏览器 Tab。 */
    fun restoreBrowserFromFloat() {
        BrowserFloatWindow.close()
        switchToTab(BROWSER_TAB)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        android.util.Log.i("MCP_LIFE", "MainActivity.onSaveInstanceState pid=$PROCESS_PID tab=$currentTab", Throwable("MCP_LIFE"))
        // 保存当前 Tab，重建（如旋转）后由 onCreate 恢复，配合 add+show/hide 保留各页面状态
        outState.putInt("currentTab", currentTab)
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.w("MCP_LIFE", "MainActivity.onDestroy pid=$PROCESS_PID isFinishing=$isFinishing isChangingConfigurations=$isChangingConfigurations", Throwable("MCP_LIFE"))
        // 应用内浮窗随 Activity 一起消失；系统级浮窗继续存活（这正是它存在的意义）
        BrowserFloatWindow.onActivityDestroyed(this, isFinishing)
        WebBrowser.onPageChanged = null
        // 起始页 WebView 是 Activity 自己 new 的，不销毁就随每次重建泄漏（它还持有 McpHome 桥）
        homeWeb?.let { w ->
            runCatching { homeOverlay.removeView(w) }
            runCatching { w.destroy() }
        }
        homeWeb = null
        // 真正退出（非配置变更）时释放进程级浏览器资源：旋转/主题切换等重建必须保留会话。
        // 但有浮窗存活时**不能**销毁——系统级浮窗不随 Activity 销毁，WebView 被 destroy 后浮窗会永久空白，
        // 且 container/appContext 被清空后浮窗的兜底导航会抛「未初始化」。
        if (isFinishing && !BrowserFloatWindow.isNormalShowing && !BrowserFloatWindow.isAutomationShowing) {
            WebBrowser.destroy()
        }
    }

}
