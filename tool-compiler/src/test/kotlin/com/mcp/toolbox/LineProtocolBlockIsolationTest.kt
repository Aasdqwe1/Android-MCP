package com.mcp.toolbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行式协议解析的**段级隔离**契约。
 *
 * 真机事故：模型回了 7 个完全合法的 tool_call（`code <<< … >>>` 写得完全正确），
 * 只有尾部一段回显文本里出现了两行 `THREW: …`，被当成重复参数触发异常；
 * 异常一路抛出 `parseToolCallLineProtocol` → 上层把**整份结果**替换成一个
 * `__parse_error__` → 端点丢弃 7 个能用的调用并回 502。
 *
 * 契约：一次解析失败**只作废出错的那一段**；全部段都失败时才抛（维持上层
 * 「合成 __parse_error__ 引导模型改正」的既有路径）。**别改回「一处失败全盘抛」。**
 */
class LineProtocolBlockIsolationTest {

    /** 一个参数写两遍的坏段（重复参数）。 */
    private fun bad(id: String) = """
        |tool_call: run_code
        |id: $id
        |description: first
        |description: second
    """.trimMargin()

    private val good = { id: String, n: String -> """
        |tool_call: run_code
        |id: $id
        |description: probe $n
        |code <<<
        |return $n;
        |>>>
    """.trimMargin() }

    @Test
    fun `中间一段坏了不影响前后两段`() {
        val bad = """
            |tool_call: run_code
            |id: call_bad
            |description: first
            |description: second
        """.trimMargin()
        val text = good("call_a", "1") + "\n" + bad + "\n" + good("call_c", "3")
        val calls = parseToolCallLineProtocol(text)
        assertEquals("坏段只作废自己，前后两段都要留下", 2, calls.size)
        assertEquals(listOf("call_a", "call_c"), calls.map { it.callId })
    }

    @Test
    fun `尾部回显文本导致的重复参数不再葬送已有调用`() {
        // 复现真机形态：合法调用之后跟着一段回显，里面有重复的 THREW: 行
        val echo = """
            |[工具](调用 id=call_a) 1) create plain file
            |  operation = create
            |  THREW: ToolCallError: missing required property "code"
            |[工具](调用 id=call_a) 2) again
            |  THREW: ToolCallError: missing required property "code"
        """.trimMargin()
        // 真机形态：多个合法块 + 尾部回显。回显落在**最后一个块**里，只作废那一个块。
        val calls = parseToolCallLineProtocol(
            good("call_a", "1") + "\n" + bad("call_b") + "\n" + echo
        )
        assertEquals("前面的合法调用必须保住（真机是 7 个里保住 6 个，不是 0 个）", 1, calls.size)
        assertEquals("call_a", calls[0].callId)
        assertEquals("run_code", calls[0].name)
    }

    @Test
    fun `只有一个段且它坏了仍然抛异常维持既有契约`() {
        val bad = """
            |tool_call: run_code
            |id: only
            |description: first
            |description: second
        """.trimMargin()
        var threw = false
        try { parseToolCallLineProtocol(bad) } catch (e: ToolArgsParseException) { threw = true }
        assertTrue("全盘皆错时上层要靠这个异常合成 __parse_error__", threw)
    }
}
