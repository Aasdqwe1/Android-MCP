package com.mcp

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Markdown 内容查看器（底部弹窗 + WebView 渲染）。
 *
 * 为什么不用 AlertDialog.setMessage：那是纯 TextView，Markdown 的标题/列表/
 * 表格/代码块会原样裸奔，且系统弹窗与应用的 Material3 风格割裂。
 *
 * 渲染方案：复用 assets 里已有的 marked.min.js（解析）+ purify.min.js（防 XSS）。
 * 这两个库本来就在给聊天页的 WebView 用，不必额外引 Markwon 依赖。
 *
 * 用法：
 *   MarkdownViewerDialog.newInstance(title, version, subtitle, content)
 *       .show(parentFragmentManager, "MD_VIEWER")
 */
class MarkdownViewerDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_VERSION = "version"
        private const val ARG_SUBTITLE = "subtitle"
        private const val ARG_CONTENT = "content"
        private const val ARG_REQUIRE_SCROLL = "require_scroll_to_bottom"
        private const val MAX_CONTENT = 200_000

        /**
         * @param requireScrollToBottom true 时进入「强制阅读」模式：关闭按钮先隐藏，
         *   正文滚动到底才出现，期间禁止下拉 / 点外部 / 返回键关闭（免责声明用）。
         */
        fun newInstance(
            title: String,
            version: String = "",
            subtitle: String = "",
            content: String,
            requireScrollToBottom: Boolean = false,
        ): MarkdownViewerDialog = MarkdownViewerDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_VERSION, version)
                putString(ARG_SUBTITLE, subtitle)
                putString(ARG_CONTENT, content.take(MAX_CONTENT))
                putBoolean(ARG_REQUIRE_SCROLL, requireScrollToBottom)
            }
        }
    }

    /** WebView 构造失败时已降级为纯文本（见 [onCreateView]）。 */
    private var webViewUnavailable = false

    /** BottomSheet 是否已初始化过（防止 onStart 重复触发时重置用户拖动后的状态）。 */
    private var sheetInitialized = false

    /** 是否要求阅读到正文底部才允许关闭（免责声明用）。 */
    private var requireScrollToBottom = false

    /** 正文是否已滚动到底（仅 [requireScrollToBottom] 为 true 时有意义）。 */
    private var reachedBottom = false

    /** 关闭按钮与内容 WebView 的引用（滚动到底检测需要）。 */
    private var closeButton: MaterialButton? = null
    private var contentWebView: WebView? = null

    /**
     * 弹窗关闭回调（仅当「强制阅读」满足、用户真正关闭时触发）。
     *
     * 普通查看模式：一旦关闭就触发。
     * 强制阅读模式：只有读到底后才可能被关闭，因此也只在读到底后触发。
     */
    var onClosed: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = try {
        inflater.inflate(R.layout.dialog_skill_viewer, container, false)
    } catch (t: Throwable) {
        // 冷启动时系统会把这个弹窗一并「恢复」，而 WebView 提供者在启动的头几帧尚未就绪，
        // 此时构造 WebView 会抛
        //   IllegalStateException: AwContents must be created if we are not posting!
        // 外层包成 InflateException: Error inflating class android.webkit.WebView 直接 FATAL。
        // 查看器只是展示内容，不该因为一次环境时序把整个 App 打挂：降级成纯文本视图，
        // 至少让用户看到原始 Markdown，而不是闪退。
        LogStore.e("MD", "WebView 构造失败，降级为纯文本展示: " + t.message)
        webViewUnavailable = true
        buildPlainTextView()
    }

    /** 兜底视图：可滚动的纯文本，内容即原始 Markdown 源码。 */
    private fun buildPlainTextView(): View {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(ctx).apply {
            text = arguments?.getString(ARG_CONTENT).orEmpty()
            textSize = 12f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        return android.widget.ScrollView(ctx).apply { addView(tv) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 已经降级为纯文本视图：没有 tvViewerTitle / wvViewerContent 等控件可接线
        if (webViewUnavailable) return
        val args = requireArguments()
        val title = args.getString(ARG_TITLE) ?: ""
        val version = args.getString(ARG_VERSION) ?: ""
        val subtitle = args.getString(ARG_SUBTITLE) ?: ""
        val content = args.getString(ARG_CONTENT) ?: ""
        requireScrollToBottom = args.getBoolean(ARG_REQUIRE_SCROLL, false)

        view.findViewById<TextView>(R.id.tvViewerTitle).text = title
        val tvVersion = view.findViewById<TextView>(R.id.tvViewerVersion)
        if (version.isBlank()) tvVersion.visibility = View.GONE else tvVersion.text = "v" + version
        val tvFiles = view.findViewById<TextView>(R.id.tvViewerFiles)
        if (subtitle.isBlank()) tvFiles.visibility = View.GONE else tvFiles.text = subtitle

        val progress = view.findViewById<LinearProgressIndicator>(R.id.progressViewer)
        val webView = view.findViewById<WebView>(R.id.wvViewerContent)

        // 调试开关：可用 chrome://inspect 连上查看 console（仅 debug 构建）
        if (0 != (requireContext().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 渲染前先展示加载态；onPageFinished 时隐藏。
        progress.visibility = View.VISIBLE
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // targetSdk >= 30 时 allowFileAccess 默认为 false，不开则 file:// 加载失败。
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = false
        }
        // 背景必须是不透明实色。
        //
        // 曾经这里设的是 0x00000000（全透明），配合布局里 WebView 的
        // layout_height="match_parent"：BottomSheet 停在 peekHeight 高度时，
        // WebView 会被测量成「父容器高度」，两者不一致，WebView 实际只拿到
        // 很矮的一条，而它自身和它没覆盖到的区域都透出下层 Fragment 的技能卡片，
        // 表现为「弹窗是透明的 / 文字和下面的列表叠在一起」。
        // 用主题的 colorSurface 铺底，无论 sheet 拖到多高都不会露出下层。
        webView.setBackgroundColor(resolveSurfaceColor())
        webView.isVerticalScrollBarEnabled = true
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg?.let { LogStore.w("MD", "console[" + it.messageLevel() + "] " + it.message() + " @" + it.lineNumber()) }
                return true
            }
        }
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                LogStore.e("MD", "加载失败: " + error?.errorCode + " " + error?.description + " url=" + request?.url)
                progress.visibility = View.GONE
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                // 页面就绪后再注入内容：
                // 走 assets 静态外壳 + evaluateJavascript，而不是把整份 HTML（含两个
                // 压缩后的 JS 库，约 60KB）写到 cacheDir 再 loadUrl(file://)。
                // 后者失败的原因是 WebView 渲染进程跑在独立沙箱 UID 下，
                // 读不到 App 私有目录（cache 权限 drwxrws--x，组外无读权）——
                // 而 assets 走 file:///android_asset/ 是 WebView 的特殊通道，可正常读取。
                progress.visibility = View.GONE
                LogStore.d("MD", "onPageFinished url=" + url + " contentLen=" + content.length)
                injectWhenReady(v, content, 0)
                if (requireScrollToBottom) {
                    // 把页面滚动事件接到原生：__onViewerScroll -> AndroidScroll.onScroll
                    v?.evaluateJavascript(
                        "window.__onViewerScroll=function(){try{AndroidScroll.onScroll();}catch(e){}};",
                        null,
                    )
                    // 内容不足以滚动时不会触发 scroll，这里补一次检测。
                    v?.postDelayed({ checkScrolledToBottom() }, 700)
                }
            }
        }
        webView.loadUrl("file:///android_asset/md_viewer.html")

        contentWebView = webView
        val btnClose = view.findViewById<MaterialButton>(R.id.btnViewerClose)
        closeButton = btnClose
        btnClose.setOnClickListener { dismiss() }
        if (requireScrollToBottom) {
            // 强制阅读模式：先隐藏关闭按钮，禁止一切「绕过阅读」的关闭途径。
            btnClose.visibility = View.GONE
            dialog?.setCancelable(false)
            dialog?.setCanceledOnTouchOutside(false)
            // 注意：WebView 是原生滚动容器，页面滚动**不会**触发
            // View.setOnScrollChangeListener。因此改由页面侧（md_viewer.html）
            // 监听 scroll 并回调 window.__onViewerScroll。
            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onScroll() {
                    view.post { checkScrolledToBottom() }
                }
            }, "AndroidScroll")
        }

        // 手势隔离：内容区的滑动只给 WebView，不要冒泡给 BottomSheet。
        //
        // 默认行为下，WebView 滚到顶/底后继续滑动，触摸事件会冒泡到 BottomSheet
        // 触发拖拽 —— 表现为「看着看着弹窗自己收起了」。
        //
        // 修法：WebView 上任何触摸都请求父容器不要拦截（requestDisallowInterceptTouchEvent(true)），
        // 这样内容区无论怎么滑都不会触发 BottomSheet 折叠。
        // 想收起弹窗时，拖「标题栏」（那里不设拦截，BottomSheet 正常接管）。
        webView.isNestedScrollingEnabled = true
        webView.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        // 标题栏：显式允许父容器拦截，保证仍可拖动/下拉收起
        view.findViewById<View>(R.id.viewerHeader).setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(false)
            false
        }
    }

    /**
     * 在 onStart 里配置 BottomSheet 高度。
     *
     * 不能放 onViewCreated：那时 view.parent 尚未 attach 到 BottomSheet 容器，
     * BottomSheetBehavior.from(parent) 拿不到正确的 Behavior，设置无效。
     */
    override fun onStart() {
        super.onStart()
        // 只初始化一次：onStart 会在「切后台回来 / 锁屏恢复」时重复触发，
        // 每次都设 state 会把用户拖动调整后的高度重置回半屏。
        if (sheetInitialized) return

        // ── 窗口铺满 ──
        //
        // coordinator / container 在 Material 的 design_bottom_sheet_dialog.xml
        // 里都是 match_parent，但前提是 decorView 有确定高度。实测 MIUI 上
        // 这个对话框窗口起初没有铺满，导致下层容器被压扁、Behavior 定位失准。
        // 显式要求 match_parent，让整条 match_parent 链有东西可撑。
        // （实测后 coordinator 稳定为满屏 3200px）
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        // view.parent 是 ViewParent，BottomSheetBehavior.from 需要 View，须显式转换。
        (view?.parent as? ViewGroup)?.let { sheet ->
            sheetInitialized = true
            // 裁剪子视图：不裁剪的话内容可能画出弹窗边界、盖住下面的列表。
            sheet.clipChildren = true
            sheet.clipToPadding = true
            // 注意：这里**不要**用 setBackgroundColor 给 sheet / coordinator 铺色。
            //  - coordinator 是全屏容器，铺色会在半屏 COLLAPSED 下盖住本该透出的下层内容；
            //  - sheet 的圆角依赖 Material 设置的 MaterialShapeDrawable 背景，
            //    setBackgroundColor 会把它替换成纯色 → 圆角消失。
            // 底部露白的正解是「布局稳定后重算内容高度」（见下方）。

            // sheet = design_bottom_sheet（FrameLayout），它的父级才是 coordinator。
            // 高度基准要用 coordinator：sheet 自身的高度是「内容高度」，
            // 而我们需要的正是 CoordinatorLayout 这一层给出的可用空间。
            val coordinator = sheet.parent as? ViewGroup
            val available = (coordinator?.height ?: 0).takeIf { it > 0 }
                ?: resources.displayMetrics.heightPixels
            val barHeight = statusBarHeight()
            // 留出顶部安全区，但不允许 expandedOffset 吃掉太多高度。
            val topOffset = barHeight.coerceAtMost((available * 0.2f).toInt())

            BottomSheetBehavior.from(sheet).apply {
                // ── 高度模型（确定性）──
                //
                // fitToContents=false 时：
                //   COLLAPSED 可视高度 == calculatePeekHeight()（≈ peekHeight）
                //   HALF 可视高度      == (available - expandedOffset) * halfExpandedRatio
                //   EXPANDED 可视高度  == available - expandedOffset
                // 默认停在 COLLAPSED ≈ 屏高 60%；上拖到 HALF / EXPANDED，下拖收起。
                isFitToContents = false
                expandedOffset = topOffset
                peekHeight = ((available - topOffset) * 0.6f).toInt()
                    .coerceAtLeast((available * 0.3f).toInt())
                // 半展开位置：偏高一些，方便长文档阅读
                halfExpandedRatio = 0.8f
                // skipCollapsed=false：允许停在 COLLAPSED（否则会跳过它直奔 HALF，
                // 而 HALF 依赖容器测量值，onStart 时可能还没测量完，高度会乱）
                skipCollapsed = false
                // 强制阅读模式下：禁止下拉隐藏、禁止拖动，直到读到底。
                // 否则用户把 sheet 拖下去就绕过了阅读，也拿不到 onClosed 回调。
                isHideable = !requireScrollToBottom
                isDraggable = !requireScrollToBottom
                state = if (requireScrollToBottom) BottomSheetBehavior.STATE_EXPANDED
                        else BottomSheetBehavior.STATE_COLLAPSED
            }

            // ── 关键修复：给内容区一个确定高度 ──
            //
            // design_bottom_sheet 的 layout_height 是 wrap_content，
            // 于是「sheet 高度 = 内容高度」；而内容里正文用的是
            // FrameLayout(0dp, weight=1)，在 wrap_content 的父容器下
            // 只能分到退化的残余空间。实测：sheet 只有 630px，
            // WebView 只剩 225px（≈ 两行）—— 就是「正文只有一两行」的真凶。
            //
            // 显式把内容高度设为「拖到顶时的最大高度」：
            //   COLLAPSED → Behavior 用 peekHeight 决定露出多少（其余裁在下方）
            //   EXPANDED  → 正好铺满到 expandedOffset，不浪费一点空间
            // WebView 随之拿到 (内容高度 - 头部区) 的完整高度，可以正常滚动。
            val contentHeight = (available - topOffset).coerceAtLeast(1)
            view?.layoutParams = (view?.layoutParams)?.apply { height = contentHeight }
                ?: android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    contentHeight,
                )

            // ── 底部露白修正（全屏 EXPANDED 才暴露）──
            //
            // onStart 时 coordinator 往往还没完成测量，上面取的 available 会偏小
            // （曾经 fallback 到 displayMetrics.heightPixels，比实际窗口矮一个导航栏），
            // 于是 contentHeight 偏小、sheet 底部够不到屏幕底，露出下方的透明区。
            // skill 查看器默认 COLLAPSED（只露半屏）看不出问题，免责声明全屏 EXPANDED 就露馅。
            //
            // 等布局稳定后再用真实的 coordinator 高度重算一次；高度没变则不重复写。
            view?.post {
                val real = (coordinator?.height ?: 0).takeIf { it > 0 } ?: return@post
                val realContent = (real - topOffset).coerceAtLeast(1)
                if (view?.layoutParams?.height != realContent) {
                    view?.layoutParams = (view?.layoutParams)?.apply { height = realContent }
                        ?: android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            realContent,
                        )
                }
            }

            // ── 诊断日志 ──
            //
            // 排查「弹窗被压扁 / 正文只有一两行」时看这条就能定位到哪一层高度不对。
            // 必须等布局稳定后再取：show() 有入场动画，onStart 时量到的是中间态
            // （曾经因此把 sheet 误判成只有 360px 高的瞬时值）。
            view?.postDelayed({
                val wv = view?.findViewById<WebView>(R.id.wvViewerContent)
                val loc = IntArray(2)
                wv?.getLocationOnScreen(loc)
                LogStore.d(
                    "MD",
                    "尺寸诊断: window=" + (dialog?.window?.decorView?.height ?: -1) +
                        " coordinator=" + (coordinator?.height ?: -1) +
                        " sheet=" + sheet.height +
                        " sheetTop=" + sheet.top +
                        " peek=" + BottomSheetBehavior.from(sheet).peekHeight +
                        " content=" + (view?.height ?: -1) +
                        " wv=" + (wv?.width ?: -1) + "x" + (wv?.height ?: -1) +
                        " wvScreenY=" + loc[1],
                )
            }, 600)
        }
    }

    /**
     * 等 marked / DOMPurify 就绪后再注入 Markdown。
     *
     * onPageFinished 只保证 DOM 解析完成，<script src> 引的外部库是异步执行的，
     * 此刻 window.marked 可能还是 undefined；历史上这里直接裸调用 evaluateJavascript，
     * 结果抛 ReferenceError、正文区一片空白。
     *
     * 最多轮询 40 次 x 50ms = 2s，超时就退回原文展示，保证「至少有内容」。
     *
     * @param attempt 已尝试次数
     */
    private fun injectWhenReady(webView: WebView?, content: String, attempt: Int) {
        val wv = webView ?: return
        val quoted = org.json.JSONObject.quote(content)
        // 探测库是否就绪；就绪则直接渲染并返回 "OK"，否则返回 "WAIT"。
        // 注意：renderMarkdown 接收的是「纯文本」，不要用 JSON.parse ——
        // 那会把 "# 标题" 这类 Markdown 源码当 JSON 解析并抛错。
        val probe = "javascript:(function(){" +
            "if(typeof marked===\"undefined\"||typeof DOMPurify===\"undefined\"){return \"WAIT\";}" +
            "window.renderMarkdown(" + quoted + ");return \"OK\";})()"
        wv.evaluateJavascript(probe) { r ->
            if (r != null && r.contains("OK")) {
                LogStore.d("MD", "inject=OK attempt=" + attempt)
                // 渲染完成后再判一次：内容不足一屏（无滚动事件）时也能开放关闭。
                if (requireScrollToBottom) wv.postDelayed({ checkScrolledToBottom() }, 400)
            } else if (attempt < 40) {
                wv.postDelayed({ injectWhenReady(wv, content, attempt + 1) }, 50)
            } else {
                // 库始终没就绪：退化成纯文本，总比留白强。
                LogStore.w("MD", "marked/DOMPurify 2s 内未就绪，降级纯文本展示")
                val fallback = "javascript:(function(){var d=document.getElementById(\"content\");" +
                    "if(!d)return;d.textContent=" + quoted + ";" +
                    "d.style.whiteSpace=\"pre-wrap\";})()"
                wv.evaluateJavascript(fallback, null)
            }
        }
    }

    /**
     * 检测正文是否已滚动到底；到底则显示关闭按钮。
     *
     * 判定留 24px 容差：不同字号/缩放下的滚动像素取整会让 scrollY+height 与
     * contentHeight 差几个像素，卡得太死会出现「明明滑到底了按钮还不出来」。
     */
    private fun checkScrolledToBottom() {
        if (!requireScrollToBottom || reachedBottom) return
        val wv = contentWebView ?: return
        // 容差：滚动像素取整会差几个 px，卡太死会「明明到底了按钮不出来」。
        val tol = (24 * resources.displayMetrics.density).toInt()
        // 注意：这里必须拼出**合法**的 JS 表达式，并返回 "true"/"false" 字符串。
        // 判定同时覆盖「内容不足一屏」（scrollHeight <= clientHeight）——此时无需滚动即算读完。
        val js = "(function(){" +
            "var d=document.documentElement;" +
            "var body=document.body;" +
            "var y=window.scrollY||d.scrollTop||(body?body.scrollTop:0)||0;" +
            "var h=window.innerHeight||d.clientHeight||0;" +
            "var c=Math.max(d.scrollHeight, body?body.scrollHeight:0, h);" +
            "var atBottom=(y+h)>=(c-" + tol + ");" +
            "var noScroll=(c-h)<=" + tol + ";" +
            "return String(atBottom||noScroll);" +
            "})()"
        wv.evaluateJavascript(js) { result ->
            // result 形如 "true" / "false"（JSON 字符串，可能带引号）
            val atBottom = result != null && result.contains("true")
            if (atBottom) markReachedBottom()
        }
    }

    /** 标记已读到底：显示关闭按钮，解除关闭限制。 */
    private fun markReachedBottom() {
        if (reachedBottom) return
        reachedBottom = true
        closeButton?.visibility = View.VISIBLE
        dialog?.setCancelable(true)
        dialog?.setCanceledOnTouchOutside(true)
        // 恢复拖动/隐藏：读完后用户可正常下拉收起。
        (view?.parent as? ViewGroup)?.let { sheet ->
            runCatching {
                BottomSheetBehavior.from(sheet).apply {
                    isHideable = true
                    isDraggable = true
                }
            }
        }
        LogStore.d("MD", "免责声明已阅读到底，关闭按钮开放")
    }

    /**
     * 从当前主题解析 colorSurface（弹窗/内容区底色）。
     *
     * 不写死颜色：深色主题下 colorSurface 是 #1c1b1f 一类的深灰，浅色主题下接近白，
     * 都要跟界面其余部分一致。取不到时兜底成深色，避免又变成透明。
     */
    private fun resolveSurfaceColor(): Int {
        val tv = android.util.TypedValue()
        val attr = com.google.android.material.R.attr.colorSurface
        val ok = requireContext().theme.resolveAttribute(attr, tv, true)
        return if (ok) {
            if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(requireContext(), tv.resourceId)
            else tv.data
        } else {
            0xFF1C1B1F.toInt()
        }
    }

    /** 状态栏高度（用于 BottomSheet 全屏时留出顶部安全区）。 */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // 只有「读到底」（或普通模式）才可能走到关闭，这里统一收尾。
        runCatching { onClosed?.invoke() }
    }

    override fun onDestroyView() {
        // WebView 必须显式销毁，否则持有 Activity 引用泄漏
        view?.findViewById<WebView>(R.id.wvViewerContent)?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        super.onDestroyView()
    }
}
