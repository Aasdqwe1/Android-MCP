package com.mcp.toolbox

import com.mcp.toolbox.examples.calculator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Toolbox 分发契约测试桩：证明两条路径的语义差异——
 *  - 模型直调 [Toolbox.dispatch]：失败返回 {"error":...} 文本（自愈）；
 *  - PTC 程序内 [Toolbox.dispatchOrThrow]：失败抛 [ToolCallException]（toolName 可 catch）。
 * 两条路径共用同一份 schema 子集校验。
 */
class ToolboxStrictTest {

    private fun box(): Toolbox = Toolbox().apply {
        register(
            tool("echo") {
                description = "回显消息"
                string("msg") { description = "要回显的文本" }
                handler { args -> """{"ok":true,"msg":"""" + args["msg"]!!.jsonPrimitive.content + """"}""" }
            }
        )
        register(
            tool("boom") {
                description = "总是失败"
                handler { throw IllegalStateException("kaboom") }
            }
        )
        register(calculator())
    }

    @Test
    fun `model path returns error text for unknown tool and invalid args`() = runBlocking {
        val b = box()
        assertTrue(b.dispatch("nope", "{}").contains("error"))
        val invalid = b.dispatch("echo", "{}")
        assertTrue(invalid.contains("error"), invalid)
        assertTrue(invalid.contains("缺少必填参数"), invalid)
    }

    @Test
    fun `model path still dispatches valid call`() = runBlocking {
        assertEquals("19.0", box().dispatch("calculator", """{"expression":"(2+3)*4-1"}"""))
    }

    @Test
    fun `strict path throws ToolCallException with toolName for unknown tool`() = runBlocking {
        val e = assertFailsWith<ToolCallException> { box().dispatchOrThrow("nope", "{}") }
        assertEquals("nope", e.toolName)
        assertTrue(e.message!!.contains("unknown tool"), e.message!!)
    }

    @Test
    fun `strict path throws on schema violation with aggregated message`() = runBlocking {
        val e = assertFailsWith<ToolCallException> { box().dispatchOrThrow("echo", """{"extra":1}""") }
        assertEquals("echo", e.toolName)
        assertTrue(e.message!!.contains("缺少必填参数"), e.message!!)
    }

    @Test
    fun `strict path wraps handler exception with toolName`() = runBlocking {
        val e = assertFailsWith<ToolCallException> { box().dispatchOrThrow("boom", "{}") }
        assertEquals("boom", e.toolName)
        assertTrue(e.message!!.contains("kaboom"), e.message!!)
    }

    @Test
    fun `strict path throws on malformed arguments json`() = runBlocking {
        val e = assertFailsWith<ToolCallException> { box().dispatchOrThrow("echo", "{not json") }
        assertEquals("echo", e.toolName)
    }

    @Test
    fun `strict path returns value on success`() = runBlocking {
        val r = box().dispatchOrThrow("echo", """{"msg":"hi"}""")
        assertTrue(r.contains(""""msg":"hi""""), r)
    }
}
