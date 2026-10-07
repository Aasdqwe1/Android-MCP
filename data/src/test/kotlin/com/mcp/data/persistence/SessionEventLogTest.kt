package com.mcp.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Test

/** SessionEventLog.messagesOf：模型历史只取 message 事件，PTC 审计事件不进历史。 */
class SessionEventLogTest {

    @Test
    fun `messagesOf 过滤掉 ptc 审计事件`() {
        val events = listOf(
            SessionLogEvent(type = "message", seq = 0, content = "hi"),
            SessionLogEvent(type = "ptc/call", seq = 1, content = "{}", id = "c1"),
            SessionLogEvent(type = "ptc/result", seq = 2, content = "ok", id = "c1"),
            SessionLogEvent(type = "message", seq = 3, content = "bye")
        )
        val history = SessionEventLog.messagesOf(events)
        assertEquals(2, history.size)
        assertEquals("hi", history[0].content)
        assertEquals("bye", history[1].content)
    }
}
