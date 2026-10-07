package com.mcp

import android.content.Context
import com.mcp.browser.WebBrowser
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.ToolDsl
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 浏览器工具集（App 内嵌 WebView 版）。
 *
 * 替代原 Chrome CDP 方案：Chrome 的 devtools abstract socket 被 SELinux 隔离、
 * adb forward/reverse 依赖电脑，对手机上的 agent 不可用。改为 App 自带 WebView
 * 进程内加载页面，无需 Chrome / USB / 电脑 / root。
 *
 * 按需初始化：首次调用 browser_navigate / browser_evaluate / browser_list_tabs
 * 时才创建 WebView 会话（browser_status 可查询状态），不调用零开销。
 *
 * 注意：控制的是 App 内浏览器（新会话），不是系统 Chrome 已打开的页面；
 * 页面内容对用户不可见（后台加载），agent 通过执行 JS 读写页面。
 */
fun browserTools(context: Context): List<ToolDef> {
    val appContext = context.applicationContext
    return listOf(
        browserListTabsTool(appContext),
        browserNavigateTool(appContext),
        browserActTool(appContext),
        browserEvaluateTool(appContext),
        browserConsoleTool(),
        browserNetworkTool(),
        browserStatusTool(),
        browserSnapshotTool(appContext),
        browserExtractTool(appContext),
        browserWaitTool(appContext),
        browserStateTool(appContext),
        browserClickTool(appContext),
        browserTypeTool(appContext),
        browserScrollTool(appContext),
        browserBackTool(appContext),
        browserForwardTool(appContext),
        browserReloadTool(appContext),
        browserScreenshotTool(appContext),
        browserCookiesTool(appContext),
        browserStorageTool(appContext),
        browserHighlightTool(appContext),
        browserDevtoolsTool(appContext),
        browserTabsTool(appContext),
        browserPressKeyTool(appContext),
        browserHoverTool(appContext),
        browserSelectOptionTool(appContext),
        browserFileUploadTool(appContext),
        browserHandleDialogTool(),
        browserDownloadTool(appContext)
    )
}

/** 会话列表的统一呈现（browser_list_tabs 与 browser_tabs action=list 共用）。 */
private suspend fun formatTabs(): String {
    val tabs = WebBrowser.listTabs()
    if (tabs.isEmpty()) return "还没有打开任何页面。请先用 browser_navigate 打开一个网页。"
    return buildString {
        append("内嵌浏览器会话（共 ${tabs.size} 个；编号即 tab 参数）：\n")
        tabs.forEach { t ->
            append("[${t.index}] ${t.title} — ${t.url}\n")
        }
    }
}

/**
 * 列出 App 内嵌浏览器的会话（页面）。
 */
private fun browserListTabsTool(appContext: Context): ToolDef = tool("browser_list_tabs") {
    description = "列出 App 内嵌浏览器的会话（App 自己打开的页面，非系统 Chrome）。" +
        "每个会话是一个 WebView 页面；首次调用会自动创建一个空会话。" +
        "返回：[编号] 标题 — 网址，其中编号就是所有 browser_* 工具 tab 参数的取值（不是会话内部 id）。" +
        "开关/新建/关闭会话请用 browser_tabs。"
    handler { _ ->
        withContext(Dispatchers.IO) {
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            formatTabs()
        }
    }
}

/**
 * 让内嵌浏览器导航到指定网址，并等待页面加载完成。
 *
 * 参数：
 * - url: 要打开的网址（必填）
 * - tab (可选): 会话序号（见 browser_list_tabs），越界或没有会话时自动新建
 * - tab_url (可选): 按网址包含的文本匹配会话（与 tab 二选一，优先 tab）
 *
 * 返回：导航后的页面标题与最终网址（可能发生重定向）。
 */
private fun browserNavigateTool(appContext: Context): ToolDef = tool("browser_navigate") {
    description = "让 App 内嵌浏览器导航到指定网址，并等待页面加载完成。返回页面标题和最终网址。" +
        "首次调用会自动创建页面；多次调用打开不同 url 会复用同一页面（传 tab 可另开新页）。"
    string("url") {
        description = "要打开的网址，如 https://example.com"
    }
    integer("tab") {
        description = "会话序号（见 browser_list_tabs），不填用第一个会话"
        required = false
    }
    string("tab_url") {
        description = "按网址包含的文本选择会话（与 tab 二选一，优先 tab）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext """{"error":"缺少 url 参数"}"""
            val tab = args["tab"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val tabUrl = args["tab_url"]?.jsonPrimitive?.contentOrNull
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            try {
                WebBrowser.navigate(url, tab, tabUrl)
            } catch (e: Exception) {
                "导航失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}

/**
 * 在当前页面执行 JavaScript。
 *
 * 参数：
 * - js: 要执行的 JS 表达式（必填）。可以读取或修改页面内容。
 * - tab (可选): 会话序号
 * - tab_url (可选): 按网址匹配会话
 *
 * 返回：JS 表达式的结果（字符串化）。
 */
private fun browserEvaluateTool(appContext: Context): ToolDef = tool("browser_evaluate") {
    description = "在 App 内嵌浏览器的当前页面执行 JavaScript 表达式，返回结果。" +
        "重要：本工具只接受**单个表达式**；多语句必须包成 IIFE，如 (function(){ const x=document.querySelector('#a'); x.click(); return x.textContent; })()，" +
        "否则会返回「JS 语法错误」提示。" +
        "可用于读取页面内容（如 document.querySelector('x').textContent）、检查元素、修改页面、触发点击等。" +
        "返回值规则：表达式（document.title、对象/数组/字符串字面量、async 立即执行函数）会返回其值；" +
        "无返回值的语句（如 .click()/.submit()）返回「该语句无返回值，无法确认是否生效」——" +
        "重要：不要据此判断操作成功，务必用表达式读取状态验证（如 return {value: document.querySelector('#x')?.value}）。" +
        "注意：向输入框填值（特别是 React/Vue 等受控组件）直接赋 .value 无效，请用 browser_type 工具" +
        "（内部已用原生 value setter + input 事件，对受控组件生效）" +
        "如需 await 异步结果（fetch / Promise），请用 async 立即执行函数，如 (async () => { const r = await fetch(url); return r.status; })()；" +
        "超长结果（如整个页面 HTML）会被截断到 5000 字符。JS 执行出错会明确返回「JS 执行异常：<原因>」。"
    string("js") {
        description = "要执行的 JS 表达式，如 document.title 或 document.querySelector('h1').textContent"
    }
    integer("tab") {
        description = "会话序号（见 browser_list_tabs），不填用第一个会话"
        required = false
    }
    string("tab_url") {
        description = "按网址包含的文本选择会话（与 tab 二选一，优先 tab）"
        required = false
    }
    integer("start") {
        description = "长字符串结果的起始读取位置（字符），用于分段获取完整内容：先 start=0 读第一段，返回会给出总长度与建议的下一步 start 值；默认 0"
        required = false
    }
    integer("maxLen") {
        description = "本次最多返回的字符数，默认 5000，最大 20000"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val js = args["js"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext """{"error":"缺少 js 参数"}"""
            val tab = args["tab"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val tabUrl = args["tab_url"]?.jsonPrimitive?.contentOrNull
            val start = args["start"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val maxLen = args["maxLen"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 5000
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            try {
                WebBrowser.evaluate(js, tab, tabUrl, start, maxLen)
            } catch (e: Exception) {
                "JS 执行失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}

/**
 * 读取最近的 console 日志（App 内嵌浏览器页面产生）。
 *
 * 参数：
 * - limit (可选): 返回条数，默认 20
 */
private fun browserConsoleTool(): ToolDef = tool("browser_console") {
    description = "读取 App 内嵌浏览器页面最近的 console 日志（报错、警告、log）。用于排查页面 JS 报错。" +
        "多会话时每行带「[会话#N]」前缀标明来源页面。"
    integer("limit") {
        description = "返回条数，默认 20，最大 100"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20).coerceIn(1, 100)
            val messages = WebBrowser.consoleSnapshot(limit)
            if (messages.isEmpty()) {
                "console 暂无消息（浏览器打开页面后产生的新日志才会被记录）。"
            } else {
                buildString {
                    append("最近 ${messages.size} 条 console 消息：\n")
                    messages.forEach { append(it).append("\n") }
                }
            }
        }
    }
}

/**
 * 读取最近的网络请求（App 内嵌浏览器页面产生）。
 *
 * 参数：
 * - limit (可选): 返回条数，默认 30
 */
private fun browserNetworkTool(): ToolDef = tool("browser_network") {
    description = "读取 App 内嵌浏览器页面最近的网络记录：请求行、失败/4xx+ 响应、下载触发。" +
        "注意：WebView 只在 HTTP 错误（≥400）时回调，成功响应不会逐条记录，也拿不到响应体；" +
        "要完整响应请用 browser_devtools 开 Eruda 抓包，或直接对接口地址用 http_request。" +
        "多会话时每行带「[会话#N]」前缀。"
    integer("limit") {
        description = "返回条数，默认 30，最大 100"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 30).coerceIn(1, 100)
            val events = WebBrowser.networkSnapshot(limit)
            if (events.isEmpty()) {
                "网络记录暂无消息（浏览器打开页面后产生的请求才会被记录）。"
            } else {
                buildString {
                    append("最近 ${events.size} 条网络事件：\n")
                    events.forEach { append(it).append("\n") }
                }
            }
        }
    }
}

/**
 * 查询内嵌浏览器状态（诊断用，与 proot_status 对齐）。不触发初始化。
 */
private fun browserStatusTool(): ToolDef = tool("browser_status") {
    description = "查询 App 内嵌浏览器的状态（诊断用）：是否已初始化、会话数、系统 WebView 版本。" +
        "浏览器按需初始化——未使用过 browser_* 工具时状态为未就绪属正常。"
    handler { _ ->
        withContext(Dispatchers.IO) { WebBrowser.status() }
    }
}

/** 公共参数：tab（会话序号）与 tab_url（按网址匹配）。 */
private fun ToolDsl.tabParams() {
    integer("tab") {
        description = "会话序号（见 browser_list_tabs），不填用第一个会话"
        required = false
    }
    string("tab_url") {
        description = "按网址包含的文本选择会话（与 tab 二选一，优先 tab）"
        required = false
    }
}

/** 从参数里解析 tab / tab_url。 */
private fun kotlinx.serialization.json.JsonObject.tabArgs(): Pair<Int?, String?> =
    Pair(
        this["tab"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        this["tab_url"]?.jsonPrimitive?.contentOrNull
    )

/**
 * DOM 快照：列出页面上所有可交互元素（编号 + 标签 + 文本）。
 * LLM 用返回的编号（#0、#1…）作为 browser_click / browser_type / browser_highlight 的 ref。
 * mode=tree 时输出 browser-use 风格的层级树（缩进 + xpath + ARIA 角色），编号保持不变。
 */
private fun browserSnapshotTool(appContext: Context): ToolDef = tool("browser_snapshot") {
    description = "列出当前页面所有可交互元素（链接、按钮、输入框、下拉框等），每个带编号。" +
        "返回的编号（如 #3）可直接用于 browser_click / browser_type / browser_highlight 的 ref 参数。" +
        "页面导航或刷新后编号会失效，需要重新调用本工具。" +
        "mode=tree 输出层级 DOM 树（元素带缩进层级、xpath 定位路径和 ARIA 角色），" +
        "适合复杂页面理解结构；编号与 flat 模式完全一致，仍可直接用于点击/输入。"
    string("mode") {
        description = "输出模式：flat（扁平列表，默认）/ tree（层级树，带 xpath 与角色）"
        enumValues = listOf("flat", "tree")
        default("flat")
    }
    integer("max") {
        description = "最多输出条数：flat 默认 300 / tree 默认 200，最大 1000"
        required = false
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "flat"
            val max = args["max"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try {
                if (mode == "tree") WebBrowser.snapshotTree(tab, tabUrl, max)
                else WebBrowser.snapshot(tab, tabUrl, max)
            } catch (e: Exception) { "DOM 快照失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 点击元素。ref 支持 snapshot 编号（#3）或 CSS 选择器。 */
private fun browserClickTool(appContext: Context): ToolDef = tool("browser_click") {
    description = "点击页面元素。ref 用 browser_snapshot 返回的编号（如 #3），或 CSS 选择器（如 button.submit）。"
    string("ref") {
        description = "元素引用：snapshot 编号（#0、#1…）或 CSS 选择器"
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 ref 参数"}"""
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.click(tab, tabUrl, ref) } catch (e: Exception) { "点击失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 输入文本到 input/textarea/select。 */
private fun browserTypeTool(appContext: Context): ToolDef = tool("browser_type") {
    description = "向输入框输入文本（支持 input / textarea / select）。ref 用 browser_snapshot 编号或 CSS 选择器。" +
        "适用于搜索框、登录表单等。"
    string("ref") {
        description = "元素引用：snapshot 编号（#0、#1…）或 CSS 选择器"
    }
    string("text") {
        description = "要输入的文本"
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 ref 参数"}"""
            val text = args["text"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 text 参数"}"""
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.type(tab, tabUrl, ref, text) } catch (e: Exception) { "输入失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 滚动页面。 */
private fun browserScrollTool(appContext: Context): ToolDef = tool("browser_scroll") {
    description = "滚动当前页面。direction：top（顶部）/ bottom（底部）/ up（向上）/ down（向下，默认）；" +
        "amount 为 up/down 时的滚动像素数（默认 600）。"
    string("direction") {
        description = "滚动方向：top / bottom / up / down"
        enumValues = listOf("top", "bottom", "up", "down")
        default("down")
    }
    integer("amount") {
        description = "up/down 时滚动的像素数，默认 600"
        required = false
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
            val amount = args["amount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.scroll(tab, tabUrl, direction, amount) } catch (e: Exception) { "滚动失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 后退。 */
private fun browserBackTool(appContext: Context): ToolDef = tool("browser_back") {
    description = "浏览器后退到上一个页面（会话历史）。"
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.history("back", tab, tabUrl) } catch (e: Exception) { "后退失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 前进。 */
private fun browserForwardTool(appContext: Context): ToolDef = tool("browser_forward") {
    description = "浏览器前进到下一个页面（会话历史，需先后退过）。"
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.history("forward", tab, tabUrl) } catch (e: Exception) { "前进失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 刷新。 */
private fun browserReloadTool(appContext: Context): ToolDef = tool("browser_reload") {
    description = "重新加载当前页面。"
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.reload(tab, tabUrl) } catch (e: Exception) { "刷新失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 截图。 */
private fun browserScreenshotTool(appContext: Context): ToolDef = tool("browser_screenshot") {
    description = "截取当前页面截图，保存为 PNG 文件并返回文件路径（可用 read_file 读取查看）。" +
        "适合查看页面布局、确认元素位置、排查渲染问题。" +
        "annotate=true 时叠加元素编号角标（browser-use 的 screenshot highlighting）：" +
        "截图上每个可交互元素带 #编号，与 browser_snapshot 一一对应，适合视觉模型看图操作。" +
        "编号角标只出现在截图里，页面本身不受影响。"
    boolean("annotate") {
        description = "true=在截图上叠加元素编号角标（需先 browser_snapshot），默认 false"
        required = false
    }
    boolean("full_page") {
        description = "true=按屏滚动拼接整页长图（默认 false，只截可视区；上限 20 屏）"
        required = false
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val annotate = args["annotate"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            val fullPage = args["full_page"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try {
                if (annotate) WebBrowser.screenshotAnnotated(appContext, tab, tabUrl, fullPage)
                else WebBrowser.screenshot(appContext, tab, tabUrl, fullPage)
            } catch (e: Exception) { "截图失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 读取 cookie。 */
private fun browserCookiesTool(appContext: Context): ToolDef = tool("browser_cookies") {
    description = "读取当前页面的 cookie（登录态等）。url 可指定读取某个网址的 cookie。"
    string("url") {
        description = "要读取 cookie 的网址；不填用当前页面"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val url = args["url"]?.jsonPrimitive?.contentOrNull
            try { WebBrowser.cookies(url) } catch (e: Exception) { "读取 cookie 失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 读取 localStorage。 */
private fun browserStorageTool(appContext: Context): ToolDef = tool("browser_storage") {
    description = "读取页面的 localStorage 键值（前端状态、token 等）。key 可选，不填返回全部键值。"
    string("key") {
        description = "要读取的 localStorage 键名；不填列出全部"
        required = false
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val key = args["key"]?.jsonPrimitive?.contentOrNull
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.storage(tab, tabUrl, key) } catch (e: Exception) { "读取 localStorage 失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 高亮元素。 */
private fun browserHighlightTool(appContext: Context): ToolDef = tool("browser_highlight") {
    description = "在页面上用红色边框高亮指定元素（配合 browser_click / browser_type 使用前确认目标）。" +
        "ref 用 browser_snapshot 编号或 CSS 选择器。"
    string("ref") {
        description = "元素引用：snapshot 编号（#0、#1…）或 CSS 选择器"
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 ref 参数"}"""
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.highlight(tab, tabUrl, ref) } catch (e: Exception) { "高亮失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/**
 * 开发者工具（Eruda 抓包/调试面板）开关——由 AI 驱动，用户不需要手动操作。
 */
private fun browserDevtoolsTool(appContext: Context): ToolDef = tool("browser_devtools") {
    description = "开关内嵌浏览器的开发者工具（Eruda 抓包/调试面板）。默认关闭——不注入、不劫持、页面干净加载。" +
        "需要查看页面网络请求（XHR/fetch 抓包）、console、DOM 时传 on=true 开启（当前页立即注入，后续页面自动提前注入，Network 从页面脚本执行前开始抓）；" +
        "调试完传 on=false 关闭（后续页面不再注入）。开启状态可用 browser_status 查询。"
    boolean("on") {
        description = "true=开启，false=关闭"
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val on = args["on"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            WebBrowser.setDevToolsEnabled(on)
            if (on) {
                "开发者工具已开启：当前页已注入 Eruda，网络/console/DOM 开始抓取；后续页面自动注入。调试完请用 browser_devtools(on=false) 关闭。"
            } else {
                "开发者工具已关闭：后续页面不再注入 Eruda；已注入的页面刷新后恢复干净。"
            }
        }
    }
}

/**
 * 提取页面正文为 Markdown（browser-use 的 markdown extraction）。
 * 自动选择 article/main/body，也可用 selector 限定区域；支持分段读取长文。
 */
private fun browserExtractTool(appContext: Context): ToolDef = tool("browser_extract") {
    description = "把当前页面正文提取为 Markdown（标题、段落、链接、列表、图片说明），" +
        "自动选择文章主体（article/main/body），也可用 selector 限定区域。" +
        "适合阅读长文、抓取文章内容、结构化理解页面信息——比 browser_evaluate 手写 JS 更省事。" +
        "长文会截断并提示继续读取（start 分段）。"
    string("selector") {
        description = "可选：限定提取区域的 CSS 选择器（如 article、#content、.post-body）；不填自动选正文"
        required = false
    }
    integer("start") {
        description = "长文分段读取的起始字符位置，默认 0"
        required = false
    }
    integer("maxLen") {
        description = "本次最多返回字符数，默认 8000，最大 200000（长文用 start 连续分段读，不再有 3 万字符上限）"
        required = false
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val selector = args["selector"]?.jsonPrimitive?.contentOrNull
            val start = args["start"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            val maxLen = args["maxLen"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8000
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.extractMarkdown(tab, tabUrl, selector, maxLen, start) }
            catch (e: Exception) { "正文提取失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/**
 * 等待元素出现/消失/含文本（browser-use 的 wait_for）。解决动态页面时序问题。
 */
private fun browserWaitTool(appContext: Context): ToolDef = tool("browser_wait") {
    description = "等待页面满足指定条件（browser-use 的 wait_for），解决动态页面（SPA 异步加载、" +
        "点击后新元素出现、加载动画消失）的时序问题。轮询检查直到条件满足或超时。" +
        "典型用法：点击按钮后 browser_wait 等结果元素出现，再 snapshot 操作下一步。"
    string("selector") {
        description = "要等待的 CSS 选择器，如 #result、.loading、div[data-status]"
    }
    string("mode") {
        description = "等待条件：visible（元素可见，默认）/ exists（存在即可）/ hidden（消失或隐藏）/ text（可见且包含指定文本）"
        enumValues = listOf("visible", "exists", "hidden", "text")
        default("visible")
    }
    string("text") {
        description = "mode=text 时要求元素包含的文本（可选）"
        required = false
    }
    integer("timeout") {
        description = "最长等待秒数，默认 15，最大 120"
        required = false
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext """{"error":"缺少 selector 参数"}"""
            val mode = args["mode"]?.jsonPrimitive?.contentOrNull ?: "visible"
            val text = args["text"]?.jsonPrimitive?.contentOrNull
            val timeout = args["timeout"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.waitFor(tab, tabUrl, selector, mode, text, timeout) }
            catch (e: Exception) { "等待失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/**
 * 页面状态摘要（browser-use 的 browser state summary）。轻量，不列元素。
 */
private fun browserStateTool(appContext: Context): ToolDef = tool("browser_state") {
    description = "查询当前页面状态摘要：URL、标题、加载状态、可交互元素数、视口与滚动位置、" +
        "正文长度、meta 描述、加载错误。适合开始操作前快速了解页面概况（轻量）；" +
        "要元素列表用 browser_snapshot。"
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.pageState(tab, tabUrl) }
            catch (e: Exception) { "查询页面状态失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 浏览器复合动作（browser-use 风格）：一步完成 navigate/click/type/scroll/extract，
 *  并自动附带页面状态摘要（动作结果已含前 20 个可交互元素编号，无需再调 browser_snapshot）。 */
private fun browserActTool(appContext: Context): ToolDef = tool("browser_act") {
    description = "浏览器复合动作，一步完成常见操作并自动返回新页面状态，省去多轮 navigate→wait→snapshot 往返。" +
        "action 取值：navigate（打开 url）、click（点击 ref）、type（向 ref 输入 text）、scroll（滚动）、extract（提取正文 Markdown）。" +
        "每次动作后已自动附带页面状态（含前 20 个可交互元素编号），无需再调 browser_snapshot。"
    string("action") {
        description = "动作类型"
        enumValues = listOf("navigate", "click", "type", "scroll", "extract")
    }
    string("url") {
        description = "action=navigate 时：要打开的网址"
        required = false
    }
    string("ref") {
        description = "action=click/type 时：元素编号（如 #3）或 CSS 选择器"
        required = false
    }
    string("text") {
        description = "action=type 时：要输入的文本"
        required = false
    }
    string("direction") {
        description = "action=scroll 时：滚动方向 top/bottom/up/down"
        enumValues = listOf("top", "bottom", "up", "down")
        default("down")
    }
    integer("amount") {
        description = "action=scroll 时：up/down 的像素数，默认 600"
        required = false
    }
    string("selector") {
        description = "action=extract 时：限定提取区域的 CSS 选择器"
        required = false
    }
    integer("maxLen") {
        description = "action=extract 时：最多返回字符数，默认 8000"
        required = false
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val action = args["action"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext """{"error":"缺少 action 参数"}"""
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try {
                when (action) {
                    "navigate" -> {
                        val url = args["url"]?.jsonPrimitive?.contentOrNull
                            ?: return@withContext """{"error":"action=navigate 缺少 url 参数"}"""
                        WebBrowser.navigate(url, tab, tabUrl)
                    }
                    "click" -> {
                        val ref = args["ref"]?.jsonPrimitive?.contentOrNull
                            ?: return@withContext """{"error":"action=click 缺少 ref 参数"}"""
                        WebBrowser.click(tab, tabUrl, ref)
                    }
                    "type" -> {
                        val ref = args["ref"]?.jsonPrimitive?.contentOrNull
                            ?: return@withContext """{"error":"action=type 缺少 ref 参数"}"""
                        val text = args["text"]?.jsonPrimitive?.contentOrNull
                            ?: return@withContext """{"error":"action=type 缺少 text 参数"}"""
                        WebBrowser.type(tab, tabUrl, ref, text)
                    }
                    "scroll" -> {
                        val direction = args["direction"]?.jsonPrimitive?.contentOrNull ?: "down"
                        val amount = args["amount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                        WebBrowser.scroll(tab, tabUrl, direction, amount)
                    }
                    "extract" -> {
                        val selector = args["selector"]?.jsonPrimitive?.contentOrNull
                        val maxLen = args["maxLen"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8000
                        val md = WebBrowser.extractMarkdown(tab, tabUrl, selector, maxLen.coerceIn(500, 200_000), 0)
                        // 与 click/type/scroll 保持一致：动作后也带上页面状态，模型不用再补一次 browser_state
                        val state = WebBrowser.pageState(tab, tabUrl)
                        "$md\n\n$state"
                    }
                    else -> """{"error":"不支持的 action: $action（支持 navigate/click/type/scroll/extract）"}"""
                }
            } catch (e: Exception) { "浏览器动作失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/**
 * 会话（标签页）管理：列出 / 新建 / 切换 / 关闭。
 *
 * 存在的意义：此前只有「隐式建会话」（tab 越界就新建），既无法主动关掉用完的页面，
 * 也让模型搞不清编号含义——现在编号口径与 browser_list_tabs 完全一致。
 */
private fun browserTabsTool(appContext: Context): ToolDef = tool("browser_tabs") {
    description = "浏览器会话（标签页）管理。action=list 列出所有会话；new 新建会话（可带 url）；" +
        "select 切换当前显示的会话；close 关闭指定会话并释放内存。tab 参数用 browser_list_tabs 输出的编号。"
    string("action") {
        description = "动作：list / new / select / close"
        enumValues = listOf("list", "new", "select", "close")
        default("list")
    }
    string("url") {
        description = "action=new 时可选：新建后直接打开的网址"
        required = false
    }
    integer("tab") {
        description = "action=select / close 时的会话编号（见 browser_list_tabs）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val tab = args["tab"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            try {
                when (action) {
                    "list" -> formatTabs()
                    "new" -> WebBrowser.newTab(args["url"]?.jsonPrimitive?.contentOrNull)
                    "select" -> {
                        if (tab == null) return@withContext """{"error":"action=select 需要 tab 参数"}"""
                        val tabs = WebBrowser.listTabs()
                        if (tab !in tabs.indices) {
                            "没有编号为 $tab 的会话（当前 ${tabs.size} 个）。"
                        } else {
                            WebBrowser.switchSession(tab)
                            "已切换到会话 $tab：${tabs[tab].title} — ${tabs[tab].url}"
                        }
                    }
                    "close" -> {
                        if (tab == null) return@withContext """{"error":"action=close 需要 tab 参数"}"""
                        WebBrowser.closeTab(tab)
                    }
                    else -> """{"error":"不支持的 action: $action（支持 list/new/select/close）"}"""
                }
            } catch (e: Exception) { "会话操作失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 发送按键：Enter 提交、Escape 关弹层、Tab 换焦点、方向键等。 */
private fun browserPressKeyTool(appContext: Context): ToolDef = tool("browser_press_key") {
    description = "向当前焦点元素发送按键（先 browser_type/browser_click 让目标获得焦点）。" +
        "常用于：输入后按 Enter 提交、Escape 关闭浮层、ArrowDown 在自定义下拉里移动选项。" +
        "注意这是合成事件（isTrusted=false），个别强校验站点可能不响应。"
    string("key") {
        description = "键名：Enter / Escape / Tab / Backspace / Delete / ArrowUp / ArrowDown / ArrowLeft / ArrowRight / PageUp / PageDown / Home / End，或单个字符"
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val key = args["key"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 key 参数"}"""
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.pressKey(tab, tabUrl, key) } catch (e: Exception) { "按键失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 悬停：菜单/tooltip 类交互。 */
private fun browserHoverTool(appContext: Context): ToolDef = tool("browser_hover") {
    description = "把鼠标悬停到元素上（派发 mouseover/mouseenter/mousemove）。" +
        "适合需要先 hover 才展开的下拉菜单、浮层提示、二级导航。ref 用 browser_snapshot 的 #编号或 CSS 选择器。"
    string("ref") {
        description = "元素引用：snapshot 编号（如 #3）或 CSS 选择器"
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 ref 参数"}"""
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.hover(tab, tabUrl, ref) } catch (e: Exception) { "悬停失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 下拉选择：按 value 或可见文本选中 option。 */
private fun browserSelectOptionTool(appContext: Context): ToolDef = tool("browser_select_option") {
    description = "在 <select> 下拉框里选中一项（按 option 的 value 或可见文本匹配），并派发 input/change。" +
        "比 browser_type 更适合原生下拉框；匹配不到时会返回该下拉框的全部可选值。"
    string("ref") {
        description = "元素引用：snapshot 编号（如 #3）或 CSS 选择器"
    }
    string("value") {
        description = "要选中的 option 的 value 或可见文本"
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 ref 参数"}"""
            val value = args["value"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 value 参数"}"""
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.selectOption(tab, tabUrl, ref, value) } catch (e: Exception) { "下拉选择失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/** 文件上传：指定本地文件并触发页面的 <input type=file>。 */
private fun browserFileUploadTool(appContext: Context): ToolDef = tool("browser_file_upload") {
    description = "给页面的文件上传控件指定本地文件（path 为设备上的绝对路径）。" +
        "实现方式：点击 ref 触发 <input type=file>，再把文件交给页面的选择器。ref 必须指向文件输入控件本身或它的触发按钮。"
    string("ref") {
        description = "文件输入控件（或它的触发按钮）：snapshot 编号或 CSS 选择器"
    }
    string("path") {
        description = "要上传的本地文件绝对路径"
    }
    tabParams()
    handler { args ->
        withContext(Dispatchers.IO) {
            val ref = args["ref"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 ref 参数"}"""
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: return@withContext """{"error":"缺少 path 参数"}"""
            WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
            val (tab, tabUrl) = args.tabArgs()
            try { WebBrowser.uploadFile(tab, tabUrl, ref, path) } catch (e: Exception) { "文件上传失败：${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

/**
 * 处理页面 JS 弹窗（alert / confirm / prompt）。
 *
 * 为什么需要：本 WebView 用 application context 构造、弹不出系统对话框，
 * 旧实现下 confirm() 恒返回 false——「确认删除」这类页面点了像没反应。
 * 现在弹窗会被挂起等待本工具答复，超过 15 秒按取消处理（不把页面卡死）。
 */
private fun browserHandleDialogTool(): ToolDef = tool("browser_handle_dialog") {
    description = "查看/处理页面的 JS 弹窗（alert、confirm、prompt）。action=status 看是否有待处理弹窗；" +
        "action=accept 确认（confirm 返回 true，prompt 用 text 参数）；action=dismiss 取消。" +
        "页面点了没反应、疑似被弹窗挡住时先 status 一下。"
    string("action") {
        description = "动作：status / accept / dismiss"
        enumValues = listOf("status", "accept", "dismiss")
        default("status")
    }
    string("text") {
        description = "action=accept 且弹窗是 prompt 时输入的内容"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            when (args["action"]?.jsonPrimitive?.contentOrNull ?: "status") {
                "status" -> WebBrowser.pendingDialogInfo()
                    ?.let { "有页面弹窗待处理：$it。用 action=accept 或 dismiss 答复。" }
                    ?: "当前没有待处理的页面弹窗。"
                "accept" -> {
                    val text = args["text"]?.jsonPrimitive?.contentOrNull
                    if (WebBrowser.answerDialog(true, text)) "已确认弹窗。建议接着 browser_snapshot 看页面变化。" else "当前没有待处理的弹窗。"
                }
                "dismiss" -> if (WebBrowser.answerDialog(false, null)) "已取消弹窗。" else "当前没有待处理的弹窗。"
                else -> """{"error":"不支持的 action（支持 status/accept/dismiss）"}"""
            }
        }
    }
}

/** 下载：查看已落盘的下载，或按 URL 直接下载。 */
private fun browserDownloadTool(appContext: Context): ToolDef = tool("browser_download") {
    description = "文件下载。action=list 查看最近下载结果（含落盘路径）；action=url 直接下载一个地址" +
        "（页面里的下载链接点了没反应时用它）。下载复用浏览器 cookie，保存在 App 私有目录；" +
        "页面自身触发的下载会自动落盘，用 action=list 查结果。"
    string("action") {
        description = "动作：list / url"
        enumValues = listOf("list", "url")
        default("list")
    }
    string("url") {
        description = "action=url 时：要下载的地址"
        required = false
    }
    integer("limit") {
        description = "action=list 时返回条数，默认 10，最大 50"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            when (args["action"]?.jsonPrimitive?.contentOrNull ?: "list") {
                "url" -> {
                    val url = args["url"]?.jsonPrimitive?.contentOrNull
                        ?: return@withContext """{"error":"action=url 需要 url 参数"}"""
                    WebBrowser.ensureStarted(appContext)?.let { return@withContext "浏览器不可用：$it" }
                    WebBrowser.downloadUrl(url)
                }
                "list" -> {
                    val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10).coerceIn(1, 50)
                    val items = WebBrowser.downloadsSnapshot(limit)
                    if (items.isEmpty()) "最近没有下载记录。"
                    else buildString {
                        append("最近 ${items.size} 条下载：\n")
                        items.forEach { append(it).append("\n") }
                    }
                }
                else -> """{"error":"不支持的 action（支持 list/url）"}"""
            }
        }
    }
}

