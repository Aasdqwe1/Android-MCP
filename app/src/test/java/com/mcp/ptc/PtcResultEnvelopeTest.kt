package com.mcp.ptc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A4 测试桩：result.json 信封解析 + 两种 runtime 生成的 runner/超时。 */
class PtcResultEnvelopeTest {

    @Test
    fun `成功信封取 value`() {
        val p = PtcResultEnvelope.parse("""{"__ptc_ok":true,"value":{"a":1}}""")
        assertTrue(p.ok)
        assertNull(p.error)
        assertEquals("""{"a":1}""", p.valueJson)
    }

    @Test
    fun `成功信封无 value 时为 null`() {
        val p = PtcResultEnvelope.parse("""{"__ptc_ok":true,"value":null}""")
        assertTrue(p.ok)
        assertNull(p.valueJson)
    }

    @Test
    fun `失败信封取 error`() {
        val p = PtcResultEnvelope.parse("""{"__ptc_ok":false,"error":"boom"}""")
        assertFalse(p.ok)
        assertEquals("boom", p.error)
    }

    @Test
    fun `旧格式裸值按成功处理`() {
        val p = PtcResultEnvelope.parse("""{"answer":42}""")
        assertTrue(p.ok)
        assertTrue(p.valueJson!!.contains("42"))
    }

    @Test
    fun `缺失或非法 result 视为失败`() {
        assertFalse(PtcResultEnvelope.parse(null).ok)
        assertFalse(PtcResultEnvelope.parse("not-json").ok)
    }

    @Test
    fun `python 与 node 程序包含信封与统一超时`() {
        val py = PythonRuntime.buildProgram("return 1", "/req.jsonl", "/res", 123)
        assertTrue(py.contains("__ptc_ok"))
        assertTrue("deadline 应使用传入秒数: " + py, py.contains("time.time() + 123"))
        val js = NodeRuntime.buildProgram("return 1", "/req.jsonl", "/res", 123)
        assertTrue(js.contains("__ptc_ok"))
        assertTrue("deadline 应使用传入秒数: " + js, js.contains("123000"))
    }
}
