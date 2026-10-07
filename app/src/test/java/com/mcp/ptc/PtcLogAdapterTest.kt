package com.mcp.ptc

import com.mcp.data.persistence.SessionLogEvent
import com.mcp.toolbox.ptc.PtcDispatchEvent
import com.mcp.toolbox.ptc.PtcEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTC 嵌套事件 → 会话日志事件的映射测试。
 *
 * 关注点：**子调用不进模型历史，但要完整进日志**。因此 type/parentSeq/depth/
 * 参数-结果的摆放位置必须稳定，否则日志回放与 UI 调用树都会错位。
 */
class PtcLogAdapterTest {

    private fun call() = PtcDispatchEvent(
        kind = PtcEventKind.PTC_CALL.type,
        callId = "c_1",
        name = "read_file",
        parentId = "run_42",
        depth = 1,
        arguments = """{"path":"/sdcard/a.txt"}"""
    )

    @Test
    fun `ptc call 事件映射为带参数与父引用的日志事件`() {
        val e: SessionLogEvent = call().toLogEvent(seq = 7, parentSeq = 3)
        assertEquals(PtcEventKind.PTC_CALL.type, e.type)
        assertEquals(7L, e.seq)
        assertEquals(3L, e.parentSeq)
        assertEquals(1, e.depth)
        assertEquals("run_42", e.parentId)
        assertEquals("c_1", e.id)
        assertEquals("read_file", e.toolCall?.name)
        assertEquals("""{"path":"/sdcard/a.txt"}""", e.toolCall?.arguments)
        assertTrue("call 事件不该带结果", e.toolCall?.result.isNullOrEmpty())
    }

    @Test
    fun `ptc result 成功事件把返回值放进 content 与 toolCall result`() {
        val e = PtcDispatchEvent(
            kind = PtcEventKind.PTC_RESULT.type,
            callId = "c_1",
            name = "read_file",
            parentId = "run_42",
            depth = 1,
            result = "hello"
        ).toLogEvent(seq = 8, parentSeq = 3)
        assertEquals("hello", e.content)
        assertEquals("hello", e.toolCall?.result)
        assertEquals("", e.toolCall?.arguments)
    }

    @Test
    fun `ptc result 失败事件把错误写进 content`() {
        val e = PtcDispatchEvent(
            kind = PtcEventKind.PTC_RESULT.type,
            callId = "c_1",
            name = "write_file",
            depth = 1,
            error = "当前预设不允许在程序内调用工具: write_file"
        ).toLogEvent(seq = 9)
        assertTrue(e.content.startsWith("错误: "))
        assertNull("未传 parentSeq 时应为 null（顶层）", e.parentSeq)
    }

    @Test
    fun `非 ptc 类型事件不还原为聊天消息`() {
        val plain = SessionLogEvent(seq = 1, content = "hi")
        assertNull(plain.toChatMessageOrNull())
        val ptc = call().toLogEvent(seq = 2)
        assertTrue(ptc.toChatMessageOrNull() != null)
    }
}
