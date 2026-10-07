package com.mcp.core.prompt

import com.mcp.toolbox.ParamSpec
import com.mcp.toolbox.ParamType
import com.mcp.toolbox.ToolDef
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTC SDK 段生成、呈现折叠与 dispatch 门控的单元测试。
 * PTC 状态已按请求派生（[PtcRequestContext]），不再依赖进程级全局对象。
 */
class PtcSdkTest {

    private fun tool(name: String, desc: String, vararg params: ParamSpec): ToolDef =
        ToolDef(name, desc, params.associateBy { it.name }) { "ok" }

    private val readFile = tool(
        "read_file", "读取文件",
        ParamSpec("path", ParamType.STRING, "文件路径", required = true),
        ParamSpec("start_line", ParamType.INTEGER, "起始行", required = false, default = JsonPrimitive(0))
    )
    private val runCode = tool("run_code", "执行程序", ParamSpec("code", ParamType.STRING, "代码", required = true))
    private val web = tool("web_search", "搜索")
    private val all = listOf(readFile, runCode, web)

    @Test
    fun `SDK 段包含可见工具签名且排除 run_code`() {
        val sdk = renderToolsSdkPy(all)
        assertTrue("应含 read_file 签名", sdk.contains("read_file(path: str, start_line: int = 0) -> str"))
        assertTrue("应含 web_search 无参签名", sdk.contains("web_search() -> str"))
        assertFalse("run_code 不应出现在 SDK 段", sdk.contains("run_code("))
    }

    @Test
    fun `JS SDK 为同步风格、排除 run_code 且提示不要 await`() {
        val sdk = renderToolsSdkJs(all)
        assertTrue("应含 JS 对象参数签名", sdk.contains("tools.read_file({ path: string, start_line?: int }) -> string"))
        assertTrue("应含无参工具签名", sdk.contains("tools.web_search({  }) -> string"))
        assertFalse("run_code 不应出现在程序内 SDK", sdk.contains("run_code({"))
        assertTrue("必须明确禁止 await（Rhino 无 async）", sdk.contains("不要写 await"))
        assertTrue("必须声明失败抛 ToolCallError", sdk.contains("ToolCallError"))
    }

    @Test
    fun `renderToolsSdk 按语言分派`() {
        val js = renderToolsSdk(all, PtcSdkLanguage.JAVASCRIPT)
        val py = renderToolsSdk(all, PtcSdkLanguage.PYTHON)
        assertTrue("JS 渲染器应标注同步", js.contains("JavaScript，同步"))
        assertTrue("PY 渲染器应标注 Python", py.contains("Python"))
        assertNotEquals("两种语言渲染结果不应相同", js, py)
    }

    @Test
    fun `ptcRequestContext 仅在 ptc 为真时生成`() {
        assertNull("非 PTC 不应有上下文", ptcRequestContext(all, ptc = false))
        val ctx = ptcRequestContext(all, ptc = true)
        assertNotNull("PTC 应生成上下文", ctx)
        assertTrue("上下文应含 JS SDK 段", ctx!!.sdkSection.contains("JavaScript，同步"))
        assertEquals("上下文规则应复用 PTC_ONLY_RULE", PTC_ONLY_RULE, ctx.rule)
    }

    @Test
    fun `PTC 协议段顺序固定：硬约束 → 指南 → SDK`() {
        // 段序是契约：指南里有「程序内工具 SDK 段见下方」的指路，SDK 必须紧随其后。
        // 曾经在两者之间插 run_code 的行式工具清单，既隔开指路与目标，
        // 又把 run_code 的参数表重复了第三次。
        val ctx = ptcRequestContext(all, ptc = true)!!
        val secs = ptcProtocolSections(ctx)
        assertEquals("三段，顺序固定", 3, secs.size)
        assertTrue("第一段是硬约束", secs[0].contains("调用约束"))
        assertTrue("第二段是协议指南", secs[1].startsWith("## 工具调用协议"))
        assertEquals("第三段就是 SDK 段本身", ctx.sdkSection, secs[2])
        assertTrue("指南应把 SDK 段指向紧邻的下一块", PTC_PROTOCOL_GUIDE.contains("紧接着的「程序内工具 SDK」段"))
        assertTrue("指南里不应再有与 SDK 段同名的空壳标题",
            PTC_PROTOCOL_GUIDE.lines().none { it.trim() == "### 程序内工具 SDK" })
        secs.forEach { s ->
            assertFalse("协议段不应再夹工具清单块", s.contains("[工具清单]"))
        }
    }

    @Test
    fun `两个会话的 PTC 状态互不影响`() {
        // 会话 A：PTC；会话 B：普通模式。两者各自派生上下文，不再共享全局开关。
        val a = ptcRequestContext(all, ptc = true)
        val b = ptcRequestContext(all, ptc = false)
        assertNotNull(a)
        assertNull(b)
        assertEquals("A 折叠后只剩 run_code", listOf("run_code"), modelFacingTools(all, true).map { it.name })
        assertEquals("B 不折叠", 3, modelFacingTools(all, false).size)
        assertTrue("A 只放行 run_code", modelDirectToolAllowed(PTC_ENTRY_TOOL, true))
        assertFalse("A 拒绝其它工具", modelDirectToolAllowed("read_file", true))
        assertTrue("B 全部放行", modelDirectToolAllowed("read_file", false))
    }
}
