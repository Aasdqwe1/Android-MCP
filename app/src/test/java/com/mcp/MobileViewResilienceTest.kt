package com.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 移动端「切走再回来消息没了」的防回归测试。
 *
 * 根因（两条，任一条命中都表现为整窗消息消失/白屏）：
 *  1) `Tabs.onDestroyView` 里 teardown 整个 WebView 池 + destroy ChatBridge。视图销毁
 *     （切页签 / 旋转 / 主题重建）只意味着「暂时不在屏幕上」，池里页面的 DOM、输入框、
 *     正在生成的流都还在；销毁后回来只能冷加载，多会话时尤其明显。
 *  2) 渲染进程被系统回收后 WebView 自我重载：`onPageFinished` 再次回调但没有 pendingLoads
 *     记录，旧逻辑直接 return → DOM 是空的却没人灌历史 → 永远空白。
 *
 * 另：两端 HTML 的「正在生成中」横幅已按需求去掉（连接类横幅保留）。
 */
class MobileViewResilienceTest {

    private fun text(rel: String): String {
        val candidates = listOf(File(rel), File("../app/" + rel))
        val f = candidates.firstOrNull { it.exists() } ?: error("找不到 " + rel)
        return f.readText().replace("\r\n", "\n")
    }

    private val tabs: String by lazy { text("src/main/java/com/mcp/Tabs.kt") }
    private val webview: String by lazy { text("src/main/assets/chat.html") }
    private val desktop: String by lazy { text("src/main/assets/chat_desktop.html") }

    private fun block(from: String, marker: String, len: Int = 2200): String {
        val at = from.indexOf(marker)
        assertTrue("找不到片段: " + marker, at > 0)
        return from.substring(at, minOf(from.length, at + len))
    }

    @Test
    fun `视图销毁不销毁会话 WebView 池与桥`() {
        // 只取 onDestroyView 本体（到下一个 override 为止），否则会把 onDestroy 的释放逻辑算进来
        val v = tabs.substringAfter("override fun onDestroyView() {")
            .substringBefore("override fun onDestroy() {")
        assertTrue("视图销毁时不得销毁 ChatBridge（池里的页面注入的就是它）", !v.contains("chatBridge?.destroy()"))
        assertTrue("视图销毁时不得 teardown 整个池", !v.contains("teardownWebView("))
        assertTrue("视图销毁要摘掉前台的 WebView 引用", v.contains("chatBridge?.frontWebView = null"))
        assertTrue(
            "视图销毁必须摘掉会触碰视图的钩子（否则视图没了还会被回调刷新列表/开窗）",
            v.contains("chatBridge?.onStreamActivityChange = null") &&
                v.contains("chatBridge?.onRecordPermissionRequest = null") &&
                v.contains("chatBridge?.onPickAttachmentsRequest = null") &&
                v.contains("chatBridge?.onForkedSession = null")
        )
        assertTrue(
            "onOffstageStreamEvent 必须保留（只塞 staleSids、不碰视图），否则错过的流事件不标脏",
            v.contains("onOffstageStreamEvent") && !v.contains("chatBridge?.onOffstageStreamEvent = null")
        )
        assertTrue("视图销毁只把池里的页面从容器摘下", v.contains("webContainer.removeAllViews()"))
    }

    @Test
    fun `真正结束时才释放池与桥`() {
        val d = block(tabs, "override fun onDestroy() {", 900)
        assertTrue("onDestroy 未按 finishing/removing 判定", d.contains("activity?.isFinishing == true"))
        assertTrue("onDestroy 未释放池", d.contains("teardownWebView(sid)"))
        assertTrue("onDestroy 未销毁桥", d.contains("chatBridge?.destroy()"))
    }

    @Test
    fun `视图重建复用同一个桥`() {
        val s = block(tabs, "private fun setupWebView() {", 700)
        assertTrue("setupWebView 必须复用已有 ChatBridge，否则老页面调不到原生能力", s.contains("if (chatBridge == null) chatBridge = ChatBridge("))
    }

    @Test
    fun `页面被重载且为空时重灌历史`() {
        val p = block(tabs, "override fun onPageFinished(", 1400)
        assertTrue("onPageFinished 缺少无 pendingLoads 的兜底分支", p.contains("if (pending == null)"))
        assertTrue("兜底分支要调用 rescueBlankPage", p.contains("rescueBlankPage("))
        val r = block(tabs, "private fun rescueBlankPage(", 1500)
        assertTrue("兜底前必须先确认页面确实为空（childElementCount）", r.contains("childElementCount"))
        assertTrue("为空时按 sid 找回会话并重灌历史", r.contains("loadHistoryIntoWeb(session)"))
    }

    @Test
    fun `两端都不再显示正在生成横幅`() {
        for ((name, html) in listOf("chat.html" to webview, "chat_desktop.html" to desktop)) {
            assertTrue("$name 仍在显示「继续生成中…」横幅", !html.contains("showBanner(\"继续生成中…\")"))
            assertTrue("$name 仍在显示「另一端正在生成…」横幅", !html.contains("showBanner(\"另一端正在生成…\")"))
            // 连接类横幅保留（未登录/未建会话/桥不可用）
            assertTrue("$name 误删了连接类横幅", html.contains("showBanner(\"尚未登录"))
        }
    }
}
