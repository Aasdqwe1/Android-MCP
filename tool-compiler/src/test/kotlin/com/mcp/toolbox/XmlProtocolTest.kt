package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * XML（DSML）工具调用协议的解析与剥离测试。
 *
 * 与行式协议的 [LineProtocolStripTest] 对等：新增一套协议必须有配套单测，
 * 锁定「解析正确」「围栏内不执行」「剥离干净」三条核心语义。
 */
class XmlProtocolTest {

    private fun call(name: String, id: String, body: String): String = buildString {
        append("<｜｜DSML｜｜ invoke name=\"").append(name).append("\">\n")
        append("<｜｜DSML｜｜ parameter name=\"id\" string=\"true\">").append(id).append("</｜｜DSML｜｜ parameter>\n")
        append(body)
        append("</｜｜DSML｜｜ invoke>")
    }

    private fun param(k: String, v: String, isString: Boolean = true): String =
        "<｜｜DSML｜｜ parameter name=\"$k\"" + (if (isString) " string=\"true\"" else "") +
            ">$v</｜｜DSML｜｜ parameter>\n"

    // ── 解析 ──

    @Test
    fun `解析单个 XML 调用`() {
        val text = call("read_file", "call_1", param("path", "/sdcard/x.txt"))
        val calls = parseToolCallXmlProtocol(text)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("call_1", calls[0].callId)
        assertTrue(calls[0].argumentsJson.contains("/sdcard/x.txt"))
    }

    @Test
    fun `解析多个并行调用`() {
        val text = call("read_file", "call_1", param("path", "/a.txt")) + "\n" +
            call("write_file", "call_2", param("path", "/b.txt") + param("content", "hi"))
        val calls = parseToolCallXmlProtocol(text)
        assertEquals(2, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("write_file", calls[1].name)
        assertEquals("call_2", calls[1].callId)
    }

    @Test
    fun `多行值原样保留`() {
        val body = param("content", "第一行\n第二行\n第三行")
        val calls = parseToolCallXmlProtocol(call("write_file", "c1", body))
        val json = calls[0].argumentsJson
        assertTrue(json.contains("第一行"), "应含第一行: $json")
        assertTrue(json.contains("第三行"), "应含第三行: $json")
    }

    @Test
    fun `string=true 强制按字符串输出数字`() {
        val calls = parseToolCallXmlProtocol(call("t", "c1", param("v", "123", isString = true)))
        // 字符串声明 → JSON 里是 "123"（带引号），不是数字 123
        assertTrue(calls[0].argumentsJson.contains("\"v\":\"123\""), calls[0].argumentsJson)
    }

    @Test
    fun `未声明 string 时数字按标量推断`() {
        val calls = parseToolCallXmlProtocol(call("t", "c1", param("v", "123", isString = false)))
        assertTrue(calls[0].argumentsJson.contains("\"v\":123"), calls[0].argumentsJson)
    }

    @Test
    fun `闭合标签写坏也能解析`() {
        // 模型常见写法：闭合缺了 >
        val text = "<｜｜DSML｜｜ invoke name=\"read_file\">\n" +
            "<｜｜DSML｜｜ parameter name=\"id\" string=\"true\">c1</｜｜DSML｜｜ parameter>\n" +
            "<｜｜DSML｜｜ parameter name=\"path\" string=\"true\">/x.txt</｜｜DSML｜｜ parameter\n" +
            "</｜｜DSML｜｜ invoke>"
        val calls = parseToolCallXmlProtocol(text)
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
    }

    @Test
    fun `ASCII 竖线也接受`() {
        val text = "<||DSML|| invoke name=\"read_file\">\n" +
            "<||DSML|| parameter name=\"id\" string=\"true\">c1</||DSML|| parameter>\n" +
            "<||DSML|| parameter name=\"path\" string=\"true\">/x.txt</||DSML|| parameter>\n" +
            "</||DSML|| invoke>"
        val calls = parseToolCallXmlProtocol(text)
        assertEquals(1, calls.size)
    }

    @Test
    fun `无 XML 调用时返回空列表`() {
        assertTrue(parseToolCallXmlProtocol("普通回复，没有工具调用").isEmpty())
    }

    // ── 围栏 / 示例屏蔽 ──

    @Test
    fun `围栏内的 XML 调用不解析`() {
        val text = "```\n" + call("read_file", "c1", param("path", "/x.txt")) + "\n```"
        assertTrue(parseToolCallXmlProtocol(text).isEmpty(), "围栏内一律视为示例，不执行")
    }

    @Test
    fun `双斜杠前缀行不解析`() {
        val text = "// <｜｜DSML｜｜ invoke name=\"read_file\">\n" +
            "// <｜｜DSML｜｜ parameter name=\"path\">/x.txt</｜｜DSML｜｜ parameter>\n" +
            "// </｜｜DSML｜｜ invoke>"
        assertTrue(parseToolCallXmlProtocol(text).isEmpty(), "// 前缀是示例，不执行")
    }

    // ── 剥离 ──

    @Test
    fun `剥离调用段保留正文`() {
        val text = "先读一下文件。\n" + call("read_file", "c1", param("path", "/x.txt")) + "\n然后总结。"
        val out = stripXmlProtocolCalls(text)
        assertFalse(out.contains("DSML"), "不应再含 DSML 标签: $out")
        assertTrue(out.contains("先读一下文件"), "正文应保留")
        assertTrue(out.contains("然后总结"), "正文应保留")
    }

    @Test
    fun `围栏内的调用原样保留不剥离`() {
        val text = "示例：\n```\n" + call("read_file", "c1", param("path", "/x.txt")) + "\n```"
        val out = stripXmlProtocolCalls(text)
        assertTrue(out.contains("DSML"), "围栏内示例应保留给用户看")
    }

    @Test
    fun `无 XML 时原样返回`() {
        val text = "普通文本，无调用。"
        assertEquals(text, stripXmlProtocolCalls(text))
    }

    // ── 工具结果（XML 协议对称）──

    @Test
    fun `XML 工具结果可往返`() {
        val raw = "line1 \"quoted\"\nline2 \\ backslash\nline3"
        val s = ToolMessages.xmlResult("call_1", raw)
        val parsed = ToolMessages.parseXmlResults(s)
        assertEquals(1, parsed?.size)
        assertEquals("call_1", parsed!![0].first)
        assertEquals(raw, parsed[0].second)
    }

    @Test
    fun `XML 多条结果顺序与调用一致`() {
        val s = ToolMessages.xmlResults(listOf("call_1" to "aaa", "call_2" to "bbb"))
        val parsed = ToolMessages.parseXmlResults(s)
        assertEquals(2, parsed?.size)
        assertEquals("call_1" to "aaa", parsed!![0])
        assertEquals("call_2" to "bbb", parsed[1])
    }

    @Test
    fun `parseResults 兼容 XML 结果形状`() {
        val s = ToolMessages.xmlResult("call_9", "hi")
        assertEquals(listOf("call_9" to "hi"), ToolMessages.parseResults(s))
    }

    @Test
    fun `extractContent 取 XML 结果原文`() {
        val s = ToolMessages.xmlResult("call_7", "payload")
        assertEquals("payload", ToolMessages.extractContent(s))
    }

    @Test
    fun `行首尖括号在 XML 结果里无需转义`() {
        val raw = ">>>not-a-close-tag\nnext"
        val s = ToolMessages.xmlResult("call_1", raw)
        assertTrue(s.contains(">>>not-a-close-tag"), "XML 结果不应转义行首: " + s)
        assertEquals(raw, ToolMessages.parseXmlResults(s)!![0].second)
    }

    @Test
    fun `containsXmlProtocolCalls 判定`() {
        assertTrue(containsXmlProtocolCalls(call("t", "c1", "")))
        assertFalse(containsXmlProtocolCalls("普通文本"))
    }

    // ── 边界：多行值尾部空行 / 正文缩进 / 数组参数 / 提前截断 ──

    @Test
    fun `多行值保留内部与尾部空行`() {
        // P3：值只剥紧邻标签的首尾换行，不整段 trim()——末尾空行必须保留，
        // 否则写文件时「内容以空行结尾」会静默丢失。
        val body = param("content", "第一行\n第二行\n\n")
        val calls = parseToolCallXmlProtocol(call("write_file", "c1", body))
        val json = calls[0].argumentsJson
        // 标签边界只剥「紧邻的一个换行」（排版），内容自身的尾部空行保留：
        // 输入两个尾换行 → 剥边界一个 → 值仍以一个尾换行结尾。
        assertTrue(json.contains("第一行\\n第二行\\n"), "内容尾部换行应保留: $json")
    }

    @Test
    fun `剥离保留正文行首缩进`() {
        // P4：剥离调用段后不整段 trim()，否则正文列表项/代码块的缩进会被削掉。
        val text = "说明：\n\n  - 项目 A\n  - 项目 B\n\n" +
            call("read_file", "c1", param("path", "/x.txt")) + "\n\n结尾。"
        val out = stripXmlProtocolCalls(text)
        assertFalse(out.contains("DSML"), "不应再含 DSML: $out")
        assertTrue(out.contains("  - 项目 A"), "正文缩进应保留: [$out]")
        assertTrue(out.contains("  - 项目 B"), "正文缩进应保留: [$out]")
    }

    @Test
    fun `数组参数按 JSON 原文解析`() {
        // P5：数组/对象参数写成 JSON 原文，string=false 走标量推断时按 JSON 字面量直出。
        val body = param("items", "[1, 2, 3]", isString = false)
        val calls = parseToolCallXmlProtocol(call("batch", "c1", body))
        assertTrue(calls[0].argumentsJson.contains("\"items\":[1, 2, 3]"), calls[0].argumentsJson)
    }

    @Test
    fun `程序体内出现结束标签字面量会提前截断`() {
        // P6：标签即边界，code 参数内若出现 parameter 结束标签字面量，解析会在该处截断
        // （提示词已明确警告「需要输出时用字符串拼接构造」）。此测试锁定这一已知行为，
        // 避免回归时被误当成「正常解析」。
        val body = "<｜｜DSML｜｜ parameter name=\"code\" string=\"true\">print(1)</｜｜DSML｜｜ parameter>"
        val calls = parseToolCallXmlProtocol(call("run_code", "c1", body))
        assertEquals(1, calls.size)
        // 值应在首个结束标签处终止，不含其后的内容
        assertFalse(calls[0].argumentsJson.contains("</｜｜DSML"), "值不应吞入结束标签本身")
        assertTrue(calls[0].argumentsJson.contains("print(1)"), calls[0].argumentsJson)
    }
}