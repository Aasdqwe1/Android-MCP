package com.mcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桌面端（浏览器）刷新恢复链路的「防漂移」测试。
 *
 * chat_desktop.html 走 WebApiServer 的 REST + SSE，与 WebView 侧（Tabs + ChatBridge +
 * activeStreamResumeJson）是两套接线：页面是浏览器自己的上下文，刷新（F5）后前端状态全丢。
 * 此前刷新后**总是跳回会话列表首个**，且正在生成的流没有气泡承载——事件到达时
 * onChatEvent 里 currentAi 为空直接 return，正在生成的内容被静默丢弃。
 *
 * 四处必须同时成立，改一侧忘另一侧会立刻红：
 *  1) 后端 /api/sessions/{id}/stream 返回活跃流快照（字段 active/busy/stopping/msgId/content/thinking）；
 *  2) 前端把快照作为 onHistory 的第二参数下发（与 WebView 侧 activeStreamResumeJson 同形状）；
 *  3) 前端在「快照已取、气泡未建」的空窗期把 SSE 的 content/thinking 缓冲进 __dsPendingResume；
 *  4) 浏览器端操作（停止 / ask_user 提交 / 删消息）必须带 sessionId。
 */
class WebDesktopResumeTest {

    private fun src(rel: String): String {
        val candidates = listOf(File(rel), File("../app/" + rel))
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到资源 " + rel + "（尝试过: " + candidates.joinToString { it.path } + "）")
        return f.readText().replace("\r\n", "\n")
    }

    private val desktop: String by lazy { src("src/main/assets/chat_desktop.html") }
    private val polyfill: String by lazy { src("src/main/assets/bridge_polyfill.js") }
    private val server: String by lazy { src("src/main/java/com/mcp/WebApiServer.kt") }

    @Test
    fun `后端暴露活跃流快照接口`() {
        assertTrue("sessionRoute 缺少 /stream 分支", server.contains("id.endsWith(\"/stream\")"))
        val fn = server.substringAfter("private fun streamSnapshot", "")
        assertTrue("streamSnapshot 未从 StreamTaskManager 取快照", fn.contains("StreamTaskManager.get(sid)"))
        for (key in listOf("active", "busy", "stopping", "msgId", "content", "thinking")) {
            assertTrue("streamSnapshot 缺少字段 " + key, fn.contains("\"" + key + "\""))
        }
        assertTrue(
            "msgId 必须是本地 id 口径（与 message_id 事件一致）",
            fn.contains("toLocalId(sid, t.messageId)")
        )
    }

    @Test
    fun `前端把活跃流快照随 onHistory 下发`() {
        assertTrue("缺少快照拉取函数", desktop.contains("async function __dsFetchStreamResume(sid)"))
        assertTrue("未请求 /stream 接口", desktop.contains("\"/web/api/sessions/\" + encodeURIComponent(sid) + \"/stream\""))
        assertTrue(
            "未把快照作为 onHistory 第二参数下发（否则刷新后仍复位为发送态）",
            desktop.contains("window.onHistory(payload, __dsResumeArg())")
        )
        val arg = desktop.substringAfter("function __dsResumeArg()").substringBefore("\n}")
        for (key in listOf("content", "thinking", "msgId")) {
            assertTrue("__dsResumeArg 缺少字段 " + key, arg.contains(key))
        }
    }

    @Test
    fun `空窗期 delta 进缓冲不丢字`() {
        assertTrue("缺少待恢复快照状态", desktop.contains("let __dsPendingResume = null;"))
        val at = desktop.indexOf("if (!currentAi && __dsPendingResume")
        assertTrue("未在 currentAi 判空前缓冲 delta", at > 0)
        val guard = desktop.indexOf("if (!currentAi) return;")
        assertTrue("currentAi 判空守卫应存在", guard > at)
        val branch = desktop.substring(at, guard)
        assertTrue("缓冲分支必须覆盖 content", branch.contains("__dsPendingResume.content"))
        assertTrue("缓冲分支必须覆盖 thinking", branch.contains("__dsPendingResume.thinking"))
    }

    @Test
    fun `刷新后回到刷新前所在会话`() {
        assertTrue("未记忆当前会话", desktop.contains("function __dsRememberSid(sid)"))
        assertTrue("switchSession 未写入记忆", desktop.contains("__dsRememberSid(sessionId);"))
        assertTrue("初始化未读回记忆会话", desktop.contains("__dsRememberedSid()"))
        assertTrue(
            "记忆的会话必须校验仍在列表内（可能已被删除）",
            desktop.contains("__dsSessions.some(s => s.id === remembered)")
        )
    }

    @Test
    fun `恢复窗口内读到终态要作废快照并补拉`() {
        val at = desktop.indexOf("if (!currentAi && __dsPendingResume) {")
        assertTrue("终态到达时未作废快照（界面会卡在「继续生成中…」）", at > 0)
        val block = desktop.substring(at, minOf(desktop.length, at + 400))
        assertTrue("未复位待恢复快照", block.contains("__dsPendingResume = null;"))
        assertTrue(
            "done 需标记补拉终态回复（终态正文这一刻才落盘）",
            block.contains("__dsRestoreEndedWhileLoading = (event === \"done\")")
        )
        assertTrue("未在恢复落地后补拉", desktop.contains("if (__dsRestoreEndedWhileLoading) {"))
        // 补拉必须排在请求流水恢复之前：__dsClearView 会清空看板列表
        assertTrue(
            "补拉与流水恢复顺序反了（流水会被清空）",
            desktop.indexOf("if (__dsRestoreEndedWhileLoading) {") <
                desktop.indexOf("const flow = await __dsApi(\"GET\", \"/web/api/token_flow/page?sessionId=\" + encodeURIComponent(sessionId)")
        )
    }

    @Test
    fun `浏览器端停止_提交_删消息都带 sessionId`() {
        // 后端 chatRoute 按请求 body 的 sessionId 显式路由：body 里没有 sid 时
        // XxxFor(null) 会静默忽略（点停止不停、ask_user 提交无反应、删消息不生效）。
        assertTrue(
            "stopGeneration 未带 sessionId",
            polyfill.contains("post(\"/web/api/chat/stop\", { sessionId: currentSid() })")
        )
        assertTrue(
            "submitUserInput 未带 sessionId",
            polyfill.contains("post(\"/web/api/chat/tool_input\", { sessionId: currentSid(),")
        )
        assertTrue(
            "deleteMessage 未带 sessionId",
            polyfill.contains("post(\"/web/api/chat/delete_message\", { sessionId: currentSid(),")
        )
    }
}
