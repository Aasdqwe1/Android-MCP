package com.mcp.core.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PTC 专用协议指南的回归测试：把「行式协议」与「程序内 SDK」彻底拆开。
 *
 * 历史 bug：PTC 分支曾注入通用的 DEEPSEEK_PROTOCOL_GUIDE，其示例是
 * `tool_call: read_file / write_file` 等直调——在 PTC 下被 PTC_ONLY_RULE 禁止，
 * 造成「示例教 A、约束禁 A」的自相矛盾，模型照抄后调用失败。
 */
class PtcProtocolGuideTest {

    private val guide = PTC_PROTOCOL_GUIDE
    private val rule = PTC_ONLY_RULE

    @Test
    fun `只出现 run_code 作为直接入口`() {
        val calls = guide.lines().map { it.trim() }.filter { it.startsWith("tool_call:") }
        assertTrue("应含 tool_call 示例", calls.isNotEmpty())
        calls.forEach { line ->
            assertTrue("PTC 下行式调用只能是 run_code，实际: " + line, line == "tool_call: run_code")
        }
    }

    @Test
    fun `不得出现其它工具名的直调示例`() {
        listOf("tool_call: read_file", "tool_call: write_file", "tool_call: run_bash", "tool_call: edit_file")
            .forEach { forbidden ->
                assertFalse("PTC 指南不应出现直调示例: " + forbidden, guide.contains(forbidden))
            }
    }

    @Test
    fun `明确唯一直接入口与程序内 SDK`() {
        // 「只能直调 run_code」这一条只在 rule 里权威陈述一次；指南只讲格式，
        // 避免同一约束在 system message 里出现第三遍（预设 override 只做指路）。
        assertTrue("规则应声明唯一直接入口", rule.contains("只能有一个工具名") && rule.contains("run_code"))
        assertFalse("指南不应复述「唯一入口」规则", guide.contains("唯一能直接输出的行式调用"))
        assertTrue("应说明其余工具走程序内 SDK", guide.contains("程序内工具 SDK"))
        assertFalse("不应保留与 SDK 段同名的空壳标题", guide.lines().any { it.trim() == "### 程序内工具 SDK" })
        assertTrue("应说明程序内禁止再调 run_code", guide.contains("禁止"))
    }

    @Test
    fun `调用约束不与协议指南逐字重复`() {
        // 历史问题：PTC_ONLY_RULE 与 PTC_PROTOCOL_GUIDE 各写一遍「两层协议/严格区分不得混用」，
        // 模型连续读到两遍同样的话。规则压成硬约束短句，展开只保留在指南一处。
        assertFalse("规则不应再重复「严格区分、不得混用」整句", rule.contains("严格区分、不得混用"))
        assertFalse("规则不应再重复「本会话…工具协议」开场", rule.contains("本会话采用两层工具协议"))
        assertTrue("规则应点名 read_file 等禁直调", rule.contains("read_file"))
        assertTrue("规则应给出 run_code 这一唯一入口", rule.contains("run_code"))
    }

    @Test
    fun `定界块规则必须带尾部重复反例`() {
        // 历史 bug：调用尾部多写一个 >>>、甚至重抄 description/language，
        // 导致整条调用被判为非法。抽象条文（「不要再多写一个」）不足以阻止——
        // 必须在指南里给出**错误示范**本身，模型才会避开这个形态。
        assertTrue("应点明最常见的失败形态", guide.contains("最常见的失败形态"))
        assertTrue("应说明这是把结束写了两遍", guide.contains("把「结束」写了两遍"))
        assertTrue("应给出错误示范小节", guide.contains("错误示范"))
        assertTrue("应给出正确示范小节", guide.contains("正确示范"))
        assertTrue("应强调一条调用里 >>> 仅有一次", guide.contains("有且仅有一次"))
    }

    @Test
    fun `id 必填且超时上限明确`() {
        assertFalse("id 不应再写「推荐必填」", guide.contains("推荐必填"))
        assertTrue("id 必须标为必填并说明唯一性", guide.contains("`id`：必填"))
        assertTrue("timeout_seconds 应给出默认值与上限", guide.contains("默认 300") && guide.contains("上限 3600"))
    }

    @Test
    fun `language 合法值全部列出`() {
        listOf("javascript", "python", "node").forEach { lang ->
            assertTrue("应列出 language 取值 " + lang, guide.contains("`" + lang + "`"))
        }
    }

    @Test
    fun `章节层级不断裂`() {
        // 指南作为系统提示词中的一节注入，不能再出现 H1——否则后续章节都被它吞进子节。
        assertFalse("不应出现 H1 标题", guide.lines().any { it.trim().startsWith("# ") })
        assertTrue("应有 H2 标题", guide.lines().any { it.trim().startsWith("## ") })
    }

    @Test
    fun `结果与错误说明仍在`() {
        assertTrue("应含 tool_result 回传", guide.contains("tool_result:"))
        assertTrue("应含 ToolCallError", guide.contains("ToolCallError"))
    }

    // ── XML（DSML）版：PTC 走 XML 时的对称契约 ──

    private val xmlGuide = PTC_PROTOCOL_GUIDE_XML
    private val xmlRule = PTC_ONLY_RULE_XML

    @Test
    fun `XML 规则声明唯一入口为 run_code`() {
        assertTrue("XML 规则应声明唯一 invoke 入口", xmlRule.contains("只能有一个工具名") && xmlRule.contains("run_code"))
        assertTrue("XML 规则应点名 read_file 等禁直调", xmlRule.contains("read_file"))
        assertTrue("XML 规则措辞应对齐 XML 语法", xmlRule.contains("invoke"))
    }

    @Test
    fun `XML 指南给出完整 DSML 标签示例`() {
        // 历史缺陷：XML 指南只说「用 DSML invoke 段」却没给完整标签字面量，
        // 模型会写出漏掉 `<｜｜DSML｜｜` 前缀的退化形态（实测已发生）。
        assertTrue("应给出完整 invoke 开标签", xmlGuide.contains("<｜｜DSML｜｜ invoke name=\"run_code\">"))
        assertTrue("应给出 invoke 闭合标签", xmlGuide.contains("</｜｜DSML｜｜ invoke>"))
        assertTrue("应给出 parameter 开标签", xmlGuide.contains("<｜｜DSML｜｜ parameter name=\"code\""))
        assertTrue("应给出 parameter 闭合标签", xmlGuide.contains("</｜｜DSML｜｜ parameter>"))
        assertTrue("应强调竖线为全角", xmlGuide.contains("全角"))
    }

    @Test
    fun `XML 指南只出现 run_code 作为直接入口`() {
        // invoke name= 只允许 run_code
        val invokeNames = Regex("<｜｜DSML｜｜ invoke name=\"([^\"]+)\"").findAll(xmlGuide)
            .map { it.groupValues[1] }.toList()
        assertTrue("应含 invoke 示例", invokeNames.isNotEmpty())
        invokeNames.forEach { n -> assertTrue("XML 下只能直调 run_code，实际: " + n, n == "run_code") }
    }

    @Test
    fun `XML 指南说明其余工具走程序内 SDK`() {
        assertTrue("应说明其余工具走程序内 SDK", xmlGuide.contains("程序内工具 SDK"))
        assertTrue("应说明程序内禁止再调 run_code", xmlGuide.contains("禁止再调用 run_code"))
        assertTrue("应含 result 段回传说明", xmlGuide.contains("result 段"))
    }

    @Test
    fun `XML 指南章节层级不断裂`() {
        assertFalse("不应出现 H1 标题", xmlGuide.lines().any { it.trim().startsWith("# ") })
        assertTrue("应有 H2 标题", xmlGuide.lines().any { it.trim().startsWith("## ") })
    }
}
