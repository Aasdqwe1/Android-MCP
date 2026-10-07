package com.mcp.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全量转录里 tool_calls 的渲染契约。
 *
 * 背景（真机实测）：`WebAutomationPrompt.build` 只渲染 `content`，而助手调用工具那一轮
 * 的 content 往往是空的 —— 于是整行被 `if (text.isBlank()) return@forEach` 跳过，
 * 模型只看到悬空的 `[工具] 结果`，也拿不到行式协议的在场示例，自己发明出
 * 裸 JSON 参数块，解析器认不出 → 调用被静默丢弃 → 下一轮继续重试。**别改回只渲染 content。**
 */
class WebAutomationPromptToolCallTest {

    /** arguments 里刻意带一个 JSON 转义的换行，用于覆盖 <<< >>> 定界块分支。 */
    private fun assistantWithCall() = ChatMessage(
        role = "assistant",
        content = "",
        toolCalls = listOf(
            ToolCall(
                id = "test-tools-1",
                type = "function",
                function = ToolCallFunction(
                    name = "run_code",
                    arguments = "{\"code\":\"line1\\nline2\",\"description\":\"probe\"}"
                )
            )
        )
    )

    private val user = ChatMessage(role = "user", content = "请测试工具")

    @Test
    fun `开启后助手轮的tool_calls以行式协议进转录而不是被整行跳过`() {
        val out = WebAutomationPrompt.build(listOf(user, assistantWithCall()), renderToolCalls = true)
        assertTrue("必须出现助手段: $out", out.contains("[助手]"))
        assertTrue("必须出现 tool_call 行: $out", out.contains("tool_call: run_code"))
        assertTrue("必须带调用 id: $out", out.contains("id: test-tools-1"))
        assertTrue("单行参数按「名: 值」渲染: $out", out.contains("description: probe"))
        assertTrue("多行参数用定界块: $out", out.contains("code <<<") && out.contains(">>>"))
    }

    @Test
    fun `默认不开时保持原字节行为`() {
        assertEquals("[用户] 请测试工具", WebAutomationPrompt.build(listOf(user, assistantWithCall())))
    }

    @Test
    fun `工具结果的call_id在开启时可见`() {
        val tool = ChatMessage(role = "tool", content = "ok", toolCallId = "test-tools-1")
        val on = WebAutomationPrompt.build(listOf(user, assistantWithCall(), tool), renderToolCalls = true)
        val off = WebAutomationPrompt.build(listOf(user, assistantWithCall(), tool))
        assertTrue(on.contains("[工具](调用 id=test-tools-1)"))
        assertTrue(off.contains("[工具] ok"))
        assertTrue(!off.contains("调用 id="))
    }
}
