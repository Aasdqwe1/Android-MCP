package com.mcp.core.prompt

import com.mcp.toolbox.ParamSpec
import com.mcp.toolbox.ParamType
import com.mcp.toolbox.ToolDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SDK ↔ 工具箱契约测试（golden 思路）：提示词里的签名必须与当前工具集逐条对应，
 * 防止「模型看到的 tools.xxx 签名」与「实际可调用工具」漂移——PTC 最容易静默失效的地方。
 */
class PtcSdkContractTest {

    private fun tool(name: String, desc: String, vararg params: ParamSpec): ToolDef =
        ToolDef(name, desc, params.associateBy { it.name }) { "ok" }

    private val tools = listOf(
        tool(
            "read_file", "读取文件",
            ParamSpec("path", ParamType.STRING, "路径", required = true),
            ParamSpec("start_line", ParamType.INTEGER, "起始行", required = false)
        ),
        tool(
            "write_file", "写文件",
            ParamSpec("path", ParamType.STRING, "路径", required = true),
            ParamSpec("content", ParamType.STRING, "内容", required = true)
        ),
        tool("run_code", "入口", ParamSpec("code", ParamType.STRING, "代码", required = true)),
        tool("web_search", "搜索")
    )

    private fun sdkLine(sdk: String, toolName: String): String =
        sdk.lines().firstOrNull { it.contains(toolName + "(") }
            ?: error("SDK 缺少工具签名: " + toolName)

    @Test
    fun `JS SDK 与工具集逐条对应`() {
        val sdk = renderToolsSdkJs(tools)
        val visible = tools.filter { it.name != PTC_ENTRY_TOOL }
        visible.forEach { t ->
            val line = sdkLine(sdk, t.name)
            assertTrue("签名应为 JS 对象参数风格: " + line, line.contains("tools." + t.name + "({ "))
            t.parameters.values.forEach { p ->
                val marker = if (p.required) p.name + ": " else p.name + "?: "
                assertTrue("缺少参数 " + t.name + "." + p.name + ": " + line, line.contains(marker))
            }
        }
        assertFalse("run_code 不应出现在程序内 SDK", sdk.contains("tools.run_code"))
        assertEquals("每个可见工具一行", visible.size, sdk.lines().count { it.startsWith("- `tools.") })
        assertFalse("JS SDK 不应引导 await（Rhino 无 async）", sdk.contains("await tools"))
    }

    @Test
    fun `PY SDK 与工具集逐条对应`() {
        val sdk = renderToolsSdkPy(tools)
        val visible = tools.filter { it.name != PTC_ENTRY_TOOL }
        visible.forEach { t ->
            val line = sdkLine(sdk, t.name)
            assertTrue("应标注返回 str: " + line, line.contains("-> str"))
            t.parameters.values.forEach { p ->
                assertTrue("缺少参数 " + t.name + "." + p.name + ": " + line, line.contains(p.name + ": "))
            }
        }
        assertFalse("run_code 不应出现在程序内 SDK", sdk.contains("run_code("))
    }

    @Test
    fun `两种语言的 SDK 渲染不同且都排除入口工具`() {
        val js = renderToolsSdkJs(tools)
        val py = renderToolsSdkPy(tools)
        assertNotEquals(js, py)
        assertTrue(js.contains("JavaScript，同步"))
        assertTrue(py.contains("Python"))
    }

    @Test
    fun `SDK 子章节标题为 H3`() {
        // SDK 段作为「工具调用协议」下的子章节注入，必须是 H3；
        // 用 H2 会与协议指南平级，H1 更会把后续章节全部吞进子节。
        val js = renderToolsSdkJs(tools)
        val py = renderToolsSdkPy(tools)
        assertTrue("JS SDK 标题应为 H3: " + js.lines().first(), js.startsWith("### 程序内工具 SDK"))
        assertTrue("PY SDK 标题应为 H3: " + py.lines().first(), py.startsWith("### 程序内工具 SDK"))
    }

    @Test
    fun `SDK 段自带 run_code 调用约定以免与工具描述重复`() {
        val js = renderToolsSdkJs(tools)
        assertTrue("应给出子调用并发/超时上限", js.contains("200") && js.contains("280"))
        assertTrue("应说明日志与返回值用法", js.contains("console.log") && js.contains("return"))
    }
}
