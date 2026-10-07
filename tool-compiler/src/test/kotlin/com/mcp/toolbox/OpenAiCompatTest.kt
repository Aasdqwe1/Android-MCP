package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OpenAI 兼容性回归：OpenAI function calling 的 tools 定义（toOpenAi / toOpenAiTools）
 * 必须【不含】行式协议的 <<< >>> 定界块——定界块是 DeepSeek 行式协议专用，
 * 一旦泄漏进 OpenAI 的 tools / system 上下文就不符合 OpenAI 要求（定界块越界）。
 *
 * 对应 ChatBridge.sendMessage 的修复：OpenAI 首消息不再注入 composeDeepSeek 的行式协议指南，
 * 工具定义走原生 function calling（toOpenAiTools），系统提示词走 composeOpenAiSystemContent，
 * 两者均不含 <<< >>>。本测试在 ToolCompiler 层守住这一不变量的工具定义形态。
 */
class OpenAiCompatTest {

    private fun box(): Toolbox = Toolbox().apply {
        register(
            ToolDef(
                name = "write_file",
                description = "写入文件",
                parameters = mapOf(
                    "path" to ParamSpec("path", ParamType.STRING, "文件路径"),
                    "content" to ParamSpec("content", ParamType.STRING, "要写入的内容", multiLine = true)
                )
            ) { _ -> "{}" }
        )
    }

    @Test
    fun openAiToolsDefinition_hasNoLineDelimiterBlock() {
        val openAi = ToolCompiler.toOpenAiTools(box().all())
        assertFalse(openAi.contains("<<<"), "OpenAI tools 定义不应含行式定界块 <<<，实际: $openAi")
        assertFalse(openAi.contains(">>>"), "OpenAI tools 定义不应含行式定界块 >>>，实际: $openAi")
    }

    @Test
    fun openAiArrayDefinition_hasNoLineDelimiterBlock() {
        val text = ToolCompiler.toOpenAi(box().all()).toString()
        assertFalse(text.contains("<<<"), "OpenAI toOpenAi 数组不应含 <<<，实际: $text")
        assertFalse(text.contains(">>>"), "OpenAI toOpenAi 数组不应含 >>>，实际: $text")
    }

    @Test
    fun deepSeekLineProtocol_usesDelimiterBlock() {
        val line = ToolCompiler.toProtocolTools(box().all())
        assertTrue(line.contains("<<<"), "DeepSeek 行式清单应含 <<< 定界块，实际: $line")
        assertTrue(line.contains(">>>"), "DeepSeek 行式清单应含 >>> 定界块，实际: $line")
    }
}
