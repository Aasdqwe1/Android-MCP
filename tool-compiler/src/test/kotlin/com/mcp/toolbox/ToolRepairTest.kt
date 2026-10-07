package com.mcp.toolbox

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 「聚焦修复」测试桩：证明工具调用被拒时回给模型的是**最小**纠错信息——
 * 出错字段（含类型/必填/多行写法）+ 正确签名 + 一条修正指令，
 * 而不是原来那句只含聚合错误串的 `invalid arguments for x: …`。
 */
class ToolRepairTest {

    private fun editTool(): ToolDef = tool("edit_file") {
        description = "编辑文件"
        string("path") { description = "文件路径" }
        string("old_string") { description = "被替换的原文"; multiLine = true }
        string("new_string") { description = "替换后的内容"; multiLine = true }
        boolean("replace_all") { description = "是否全部替换"; required = false }
    }

    @Test
    fun `缺少必填参数时给出字段说明与签名`() {
        val t = editTool()
        val problems = listOf("$: 缺少必填参数 \"old_string\"")
        val text = ToolRepair.invalidArguments("edit_file", t, problems)
        assertTrue(text.contains("invalid arguments for edit_file"), text)
        assertTrue(text.contains("缺少必填参数"), "原始错误串要保留，便于日志检索: $text")
        assertTrue(text.contains("old_string"), text)
        assertTrue(text.contains("必填"), "要标出必填: $text")
        assertTrue(text.contains("<<<"), "多行参数要提示定界块写法: $text")
        assertTrue(text.contains("正确签名：edit_file("), text)
        assertTrue(text.contains("replace_all"), "签名要含可选参数: $text")
        assertTrue(text.contains("请只修正上述问题后重新调用 edit_file"), text)
    }

    @Test
    fun `类型错误时回显期望类型与相关参数`() {
        val t = editTool()
        val text = ToolRepair.invalidArguments("edit_file", t, listOf("$.path: 期望类型 string, 实际 integer"))
        assertTrue(text.contains("$.path: 期望类型 string, 实际 integer"), text)
        assertTrue(text.contains("- path (string，必填)"), text)
    }

    @Test
    fun `未知参数名时提示该工具没有这个参数`() {
        val t = editTool()
        val text = ToolRepair.invalidArguments("edit_file", t, listOf("$: 不允许多余参数 \"content\""))
        assertTrue(text.contains("content"), text)
        assertTrue(text.contains("该工具没有这个参数"), text)
    }

    @Test
    fun `工具名不存在时给出最接近候选`() {
        val text = ToolRepair.unknownTool("read_fil", listOf("read_file", "write_file", "run_bash"))
        assertTrue(text.contains("unknown tool: read_fil"), text)
        assertTrue(text.contains("最接近的候选：read_file"), text)
        assertTrue(text.contains("read_file, run_bash, write_file"), "可用清单应排序并完整列出: $text")
    }

    @Test
    fun `解析失败时给出原因、签名与格式要求`() {
        val text = ToolRepair.parseError(
            "edit_file",
            "多行参数 'old_string' 必须用定界块（old_string <<< ... >>>），不能用单行格式。",
            editTool()
        )
        assertTrue(text.contains("参数无法解析"), text)
        assertTrue(text.contains("必须用定界块"), text)
        assertTrue(text.contains("正确签名：edit_file("), text)
        assertTrue(text.contains("参数名 <<< 单独一行"), text)
    }

    @Test
    fun `签名渲染：必填加感叹号、可选不加、多行标注定界块`() {
        assertEquals(
            "edit_file(path: string!, old_string: string!（<<< … >>>）, new_string: string!（<<< … >>>）, replace_all: boolean)",
            ToolRepair.signature(editTool())
        )
    }

    @Test
    fun `dispatch 失败返回聚焦修复 JSON`() = runBlocking {
        val box = Toolbox().apply { register(editTool()) }
        val out = box.dispatch("edit_file", """{"path":"/a.txt"}""")
        assertTrue(out.contains("\"error\""), out)
        assertTrue(out.contains("正确签名：edit_file("), out)
        assertTrue(out.contains("缺少必填参数"), out)
    }

    @Test
    fun `dispatchOrThrow 失败抛出聚焦修复消息`() = runBlocking {
        val box = Toolbox().apply { register(editTool()) }
        val e = assertFailsWith<ToolCallException> { box.dispatchOrThrow("edit_file", """{"path":"/a.txt"}""") }
        assertEquals("edit_file", e.toolName)
        assertTrue(e.message!!.contains("正确签名：edit_file("), e.message!!)
        val unknown = assertFailsWith<ToolCallException> { box.dispatchOrThrow("nope", "{}") }
        assertTrue(unknown.message!!.contains("unknown tool"), unknown.message!!)
    }
}
