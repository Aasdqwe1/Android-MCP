package com.mcp.toolbox.ptc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PtcEventModelTest {

    @Test
    fun `request 编解码往返`() {
        val r = PtcCallRequest("c1", "read_file", """{"path":"/x.txt"}""")
        val line = PtcIpc.encodeRequest(r)
        val back = PtcIpc.decodeRequest(line)
        assertNotNull(back)
        assertEquals("c1", back!!.callId)
        assertEquals("read_file", back.name)
        assertEquals("""{"path":"/x.txt"}""", back.arguments)
    }

    @Test
    fun `result 编解码往返（成功带 value）`() {
        val r = PtcCallResult("c1", value = "line1\nline2")
        val back = PtcIpc.decodeResult(PtcIpc.encodeResult(r))
        assertNotNull(back)
        assertEquals("line1\nline2", back!!.value)
        assertEquals(null, back.error)
    }

    @Test
    fun `result 编解码往返（失败带 error）`() {
        val r = PtcCallResult("c1", error = "boom")
        val back = PtcIpc.decodeResult(PtcIpc.encodeResult(r))
        assertNotNull(back)
        assertEquals("boom", back!!.error)
        assertEquals(null, back.value)
    }

    @Test
    fun `损坏行解码为 null`() {
        assertEquals(null, PtcIpc.decodeRequest("not a json"))
        assertEquals(null, PtcIpc.decodeResult("}{"))
    }

    @Test
    fun `PtcEventKind 类型取值`() {
        assertEquals("ptc/call", PtcEventKind.PTC_CALL.type)
        assertEquals("ptc/result", PtcEventKind.PTC_RESULT.type)
    }
}
