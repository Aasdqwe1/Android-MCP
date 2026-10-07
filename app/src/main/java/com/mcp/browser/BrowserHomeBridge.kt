package com.mcp.browser

import android.webkit.JavascriptInterface

/**
 * 起始页（assets/browser_home.html）输入框的解析规则。
 *
 * 主界面（MainActivity.HomeBridge）与 WebBrowser 会话共用同一份实现，
 * 否则浮窗/自动化会话里的起始页会和主界面行为不一致。
 */
internal fun resolveHomeInput(raw: String): String? {
    val input = raw.trim()
    if (input.isEmpty()) return null
    return when {
        input.startsWith("http://") || input.startsWith("https://") -> input
        input.contains('.') && !input.contains(' ') -> "https://$input"
        else -> "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
    }
}

/**
 * 注入到 WebView 的 `McpHome` 桥：让起始页的输入框/「前往」按钮有反应。
 *
 * 背景：起始页 HTML 是 `if (window.McpHome && window.McpHome.go) …`，
 * 此前只有 MainActivity 自己那个 homeWeb 注入了桥，而浏览器浮窗把同一份 HTML
 * 加载进 WebBrowser 的会话 → 浮窗里的起始页输入框点了完全没反应（静默 no-op）。
 *
 * 安全约束：addJavascriptInterface 会把桥暴露给该 WebView 加载的**任何**页面，
 * 所以 [currentUrl] 会先校验当前仍是起始页，否则直接忽略。
 */
class BrowserHomeBridge(
    private val currentUrl: () -> String,
    private val navigate: (String) -> Unit,
) {
    @JavascriptInterface
    fun go(input: String) {
        val cur = runCatching { currentUrl() }.getOrDefault("")
        if (!cur.contains("browser_home.html")) return
        resolveHomeInput(input)?.let { runCatching { navigate(it) } }
    }
}
