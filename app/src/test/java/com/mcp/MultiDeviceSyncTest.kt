package com.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 多端（手机 WebView ⇄ 桌面浏览器）会话态同步的回归测试。
 *
 * 背景：手机桥（Tabs 创建）与桌面桥（WebApiServer 创建）**同进程共存**，各自 new 一个
 * ChatBridge 曾让同一个 sid 有两份 SessionState（history 镜像 / 锚点 / isBusy / ask_user），
 * 磁盘日志却只有一份，表现为「信息流与运行状态两端不同步」，且桌面停不掉手机发起的流。
 *
 * 两条修复腿都必须保持，改一侧忘另一侧会立刻红：
 *  1) 会话态与锁进程级共享（[SessionStateStore] 是唯一持有者）；
 *  2) 流事件进程级扇出（[ChatBridge.StreamListener]），SSE 与对端 WebView 都能实时收到。
 */
class MultiDeviceSyncTest {

    private fun src(rel: String): String {
        val candidates = listOf(File(rel), File("../app/" + rel))
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码 " + rel + "（尝试过: " + candidates.joinToString { it.path } + "）")
        return f.readText().replace("\r\n", "\n")
    }

    private val bridge: String by lazy { src("src/main/java/com/mcp/ChatBridge.kt") }
    private val web: String by lazy { src("src/main/java/com/mcp/WebApiServer.kt") }
    private val store: String by lazy { src("src/main/java/com/mcp/SessionStateStore.kt") }

    // ── 1) 进程级会话态 ────────────────────────────────────────────────────

    @Test
    fun `会话态与两把锁必须进程级共享`() {
        assertTrue("SessionStateStore 未持有 states", store.contains("val states = ConcurrentHashMap<String, ChatBridge.SessionState>()"))
        assertTrue("SessionStateStore 未持有 historyLock", store.contains("val historyLock = Any()"))
        assertTrue("SessionStateStore 未持有 saveMutex", store.contains("val saveMutex = Mutex()"))
        assertTrue(
            "ChatBridge.sessionStates 必须委托给进程级仓库（否则同一个会话有两份 history）",
            bridge.contains("private val sessionStates: ConcurrentHashMap<String, SessionState> get() = SessionStateStore.states")
        )
        assertTrue(
            "historyLock 必须共享（两端各持一把锁会让 addToHistory 的「读 id + 追加」交错）",
            bridge.contains("private val historyLock = SessionStateStore.historyLock")
        )
        assertTrue(
            "saveMutex 必须共享（rebuildMessages 是整文件重写，跨实例并发会丢消息）",
            bridge.contains("private val saveMutex = SessionStateStore.saveMutex")
        )
        // 不能退回 per-instance 实例化
        assertTrue("sessionStates 不得再 new 一份", !bridge.contains("private val sessionStates = ConcurrentHashMap"))
        assertTrue("historyLock 不得再 new 一份", !bridge.contains("private val historyLock = Any()"))
    }

    @Test
    fun `同一会话态在进程内只有一份`() {
        SessionStateStore.clear()
        try {
            val st = ChatBridge.SessionState()
            SessionStateStore.states["sid-x"] = st
            // 两端（手机桥 / 桌面桥）读到的是**同一个对象**，而不是各自 new 的一份镜像
            assertTrue(
                "进程级仓库必须按 sid 返回同一个 SessionState",
                SessionStateStore.states["sid-x"] === st
            )
            SessionStateStore.clear()
            assertTrue("clear() 后仓库应为空", SessionStateStore.states.isEmpty())
        } finally {
            SessionStateStore.clear()
        }
    }

    // ── 2) 流事件进程级扇出 ────────────────────────────────────────────────

    @Test
    fun `流事件扇出到所有订阅者且互相隔离`() {
        val seen = CopyOnWriteArrayList<String>()
        val first = ChatBridge.StreamListener { origin, sid, event, _ -> seen.add("first:" + origin + ":" + sid + ":" + event) }
        // 订阅者抛错不得影响其它订阅者与事件发布方
        val boom = ChatBridge.StreamListener { _, _, _, _ -> throw IllegalStateException("boom") }
        val second = ChatBridge.StreamListener { _, _, event, _ -> seen.add("second:" + event) }
        ChatBridge.addStreamListener(first)
        ChatBridge.addStreamListener(boom)
        ChatBridge.addStreamListener(second)
        try {
            ChatBridge.publishStreamEvent("mobile-bridge", "sid-1", "content", "{\"delta\":\"a\"}")
            assertEquals(
                listOf("first:mobile-bridge:sid-1:content", "second:content"),
                seen.toList()
            )
            ChatBridge.removeStreamListener(first)
            seen.clear()
            ChatBridge.publishStreamEvent("desktop-bridge", "sid-1", "done", "{}")
            assertEquals("移除后不应再收到", listOf("second:done"), seen.toList())
        } finally {
            ChatBridge.removeStreamListener(first)
            ChatBridge.removeStreamListener(boom)
            ChatBridge.removeStreamListener(second)
        }
    }

    @Test
    fun `emitStream 走扇出而不再直接推 onWebEvent`() {
        val at = bridge.indexOf("private fun emitStream(sid: String?, event: String, data: JSONObject) {")
        assertTrue("找不到 emitStream", at > 0)
        val body = bridge.substring(at, minOf(bridge.length, at + 1500))
        assertTrue("emitStream 必须发到进程级扇出", body.contains("publishStreamEvent(this, sid, event, data.toString())"))
        assertTrue("emitStream 不得再直接调 onWebEvent（会重复投递两次）", !body.contains("onWebEvent?.invoke"))
        // 本源事件仍推自己的 WebView，后台会话仍标脏
        assertTrue("emitStream 应保留 WebView 推送", body.contains("pushToWebView(event, data)"))
        assertTrue("emitStream 应保留后台会话标脏", body.contains("onOffstageStreamEvent?.invoke(sid)"))
        // 对端事件的处理：跳过本源 + 按前台会话判定
        val foreign = bridge.indexOf("private fun onForeignStreamEvent(")
        assertTrue("缺少对端事件处理", foreign > 0)
        val fbody = bridge.substring(foreign, minOf(bridge.length, foreign + 900))
        assertTrue("对端事件必须先跳过本源（否则本条流会被重复渲染）", fbody.contains("if (origin === this) return"))
        assertTrue("对端事件要更新本端列表的生成中状态", fbody.contains("onStreamActivityChange?.invoke(sid, !terminal)"))
        assertTrue("对端事件落在本端前台会话时要推 WebView", fbody.contains("pushToWebView(event, obj)"))
        // 订阅/反注册必须成对（Fragment 重建时不泄漏监听器）
        assertTrue("init 未注册监听器", bridge.contains("addStreamListener(foreignStreamListener)"))
        assertTrue("destroy 未反注册监听器", bridge.contains("removeStreamListener(foreignStreamListener)"))
    }

    @Test
    fun `桌面 SSE 订阅进程级流事件`() {
        assertTrue("WebApiServer 未订阅流事件", web.contains("ChatBridge.addStreamListener(streamListener)"))
        assertTrue("WebApiServer 停止时未反注册（会向已关闭的服务推事件）", web.contains("ChatBridge.removeStreamListener(streamListener)"))
        assertTrue(
            "SSE 广播必须转发所有实例的流事件",
            web.contains("ChatBridge.StreamListener { _, sid, event, data ->")
        )
    }

    // ── 3) 前端跟随别端的流 ────────────────────────────────────────────────

    @Test
    fun `两端都能中途跟随别端发起的流`() {
        for (name in listOf("src/main/assets/chat.html", "src/main/assets/chat_desktop.html")) {
            val html = src(name)
            assertTrue("$name 缺少「中途加入别端流」的建气泡函数", html.contains("function attachForeignStreamBubble()"))
            assertTrue(
                "$name 未在 currentAi 判空前建气泡（别端流事件会被判空丢掉）",
                html.contains("if (!currentAi && historyLoaded &&")
            )
            assertTrue(
                "$name 建气泡只看历史是否就绪（视图重建期间抢建会与 onHistory 重复建气泡）",
                html.contains("let historyLoaded = false;") &&
                    html.contains("historyLoaded = true;") &&
                    html.contains("historyLoaded = false;   // 视图重建中：历史回来之前不抢建气泡")
            )
            // done 必须用完整正文对齐气泡。语义有两层（都别丢）：
            //  1) 中途加入的别端流：补齐本端错过的前半段；
            //  2) 本端自发的流：站点重渲染导致流式增量缺字时，用 done 全文修正。
            // 实现上 done 分支先判「有 content」，再在内层按 foreignJoinedStream / hadToolCall
            // 决定覆盖范围，因此锚点分两段匹配（不再要求 foreignJoinedStream 出现在条件开头）。
            assertTrue(
                "$name done 分支未处理完整正文（缺字无法修正）",
                html.contains("if (data && typeof data.content === \"string\" && data.content)")
            )
            assertTrue(
                "$name 中途加入的别端流结束后未用 done 的完整正文补齐前半段",
                html.contains("if (foreignJoinedStream || !currentAi.state.hadToolCall)")
            )
            // 事件来源必须已按会话过滤：否则别端别的会话的流会在本端建出幽灵气泡
            assertTrue("$name 建气泡函数未标记 foreignJoinedStream", html.contains("foreignJoinedStream = true;"))
        }
        // 桌面端另有 SSE 侧 sid 过滤（bridge_polyfill）：确认仍在
        val poly = src("src/main/assets/bridge_polyfill.js")
        assertTrue("polyfill 丢失按 sid 过滤", poly.contains("if (envSid && envSid !== currentSid()) return;"))
    }

    // ── 4) 跨端「停止 / ask_user」依赖共享 isBusy ──────────────────────────

    @Test
    fun `停止与提问的守卫读的是共享会话态`() {
        // stopStream 的守卫：state(sid).isBusy —— 会话态共享后才能停掉对端发起的流
        val at = bridge.indexOf("internal fun stopStream(sid: String) {")
        assertTrue("找不到 stopStream", at > 0)
        // 函数体含 Web 自动化「远程先停」分支 + 原取消路径，窗口放宽到 3000 字符
        val body = bridge.substring(at, minOf(bridge.length, at + 3000))
        assertTrue("stopStream 应读共享 state(sid) 的 isBusy", body.contains("val st = state(sid)"))
        assertTrue("stopStream 应取消共享的 streamJob", body.contains("st.streamJob?.cancel()"))
        assertTrue("stopStream 应置共享的 isStopping（对端协程据此定稿前缀）", body.contains("st.isStopping = true"))
    }
}
