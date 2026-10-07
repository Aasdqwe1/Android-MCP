package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用户报告的两个问题的测试桩：
 *  1. 识别用的 tool_call 文本必须能从展示文本里剥离（不再显示给用户）；
 *  2. 「示例」调用不得被当成真调用执行——**围栏内一律视为示例**（无条件），
 *     历史上曾有「整条消息被围栏完整包裹时仍执行」的兼容例外，现已移除。
 */
class LineProtocolStripTest {

    private val realCall = """
先读一下文件。
tool_call: read_file
id: call_1
path: /sdcard/a.txt
然后我再总结。
""".trimIndent()

    @Test
    fun `剥离行式调用段但保留正文`() {
        val out = stripLineProtocolCalls(realCall)
        assertTrue(!out.contains("tool_call:"), "不应再含 tool_call:")
        assertTrue(!out.contains("path:"), "不应再含参数行")
        assertTrue(out.contains("先读一下文件。") && out.contains("然后我再总结。"), "正文应保留")
    }

    @Test
    fun `剥离多个调用与定界块`() {
        val text = """
tool_call: write_file
id: call_1
path: /a.txt
content <<<
第一行
第二行
>>>
tool_call: read_file
id: call_2
path: /a.txt
""".trimIndent()
        val out = stripLineProtocolCalls(text)
        assertTrue(out.isBlank(), "整段都是调用，剥离后应为空，实际: " + out)
    }

    @Test
    fun `示例行（双斜杠前缀）保留不剥离`() {
        val text = """
格式示例：
// tool_call: read_file
// path: /x.txt
""".trimIndent()
        val out = stripLineProtocolCalls(text)
        assertTrue(out.contains("// tool_call: read_file"), "//tool_call 示例行应保留给用户看")
    }

    @Test
    fun `工具结果块不剥离`() {
        val text = "tool_result: call_1 <<<\n{\"ok\":true}\n>>>"
        assertEquals(text, stripLineProtocolCalls(text))
    }

    @Test
    fun `围栏内的调用无条件视为示例：不执行也不剥离`() {
        val text = """
先看示例：
```
tool_call: read_file
path: /demo.txt
```
然后继续。
""".trimIndent()
        assertEquals(0, parseToolCallLineProtocol(text).size, "围栏内一律视为示例，不执行")
        val out = stripLineProtocolCalls(text)
        assertTrue(out.contains("tool_call: read_file"), "示例应原样保留给用户看，实际: " + out)
        assertTrue(out.contains("然后继续。"), "围栏外的正文仍应保留")
    }

    @Test
    fun `整条消息被一层围栏包裹不执行也不剥离`() {
        // 历史兼容例外已移除：整段被围栏包裹时按示例处理——不执行、不剥离。
        val text = "```\ntool_call: read_file\npath: /real.txt\n```"
        val calls = parseToolCallLineProtocol(text)
        assertEquals(0, calls.size, "围栏内一律视为示例，不再作为兼容例外执行")
        val out = stripLineProtocolCalls(text)
        assertTrue(out.contains("tool_call:"), "围栏内原文应原样保留给用户看")
    }

    @Test
    fun `围栏与正文混排时只有围栏外部分可执行`() {
        val text = """
```
tool_call: read_file
path: /demo.txt
```
tool_call: read_file
path: /real.txt
""".trimIndent()
        val calls = parseToolCallLineProtocol(text)
        assertEquals(1, calls.size, "只有围栏外那条是真调用")
        assertTrue(calls[0].argumentsJson.contains("/real.txt"), calls[0].argumentsJson)
    }

    @Test
    fun `定界块内的围栏字符原样保留`() {
        val text = """
tool_call: write_file
path: /a.md
content <<<
```js
console.log(1)
```
>>>
""".trimIndent()
        val calls = parseToolCallLineProtocol(text)
        assertEquals(1, calls.size)
        assertTrue(calls[0].argumentsJson.contains("```js"), "定界块内是原文，不应被围栏屏蔽: " + calls[0].argumentsJson)
        assertTrue(calls[0].argumentsJson.contains("console.log(1)"))
    }

    @Test
    fun `波浪线围栏同样屏蔽`() {
        val text = """
示例：
~~~
tool_call: read_file
path: /demo.txt
~~~
""".trimIndent()
        assertEquals(0, parseToolCallLineProtocol(text).size)
    }

    @Test
    fun `无围栏但带双斜杠前缀的裸示例也跳过`() {
        // 「完整示例」段曾是裸块（无围栏）——提示词已统一加 // 前缀，这里验证 // 规则兜底
        val text = """
完整示例：
// tool_call: write_file
// path: /x.txt
// content <<<
// hi
// >>>
""".trimIndent()
        assertEquals(0, parseToolCallLineProtocol(text).size)
    }

    @Test
    fun `fenceRanges 与 isFencedLine`() {
        val lines = listOf("例如：", "```", "tool_call: x", "```")
        val ranges = fenceRanges(lines)
        assertEquals(1, ranges.size)
        assertTrue(isFencedLine(2, ranges), "围栏内行应被标记")
        assertTrue(!isFencedLine(0, ranges), "围栏外的正文不受影响")
    }

    @Test
    fun `未闭合围栏延伸到文本末尾`() {
        val lines = listOf("前言", "```", "tool_call: x", "path: y")
        assertEquals(1..3, fenceRanges(lines)[0])
        assertEquals(0, parseToolCallLineProtocol(lines.joinToString("\n")).size)
    }
}
