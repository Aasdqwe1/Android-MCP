package com.mcp.toolbox

import com.mcp.serialization.McpJson
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 验证 DeepSeek 原生 tool_call 的 arguments 转义修复。
 *
 * 背景：DeepSeek 原生 SSE 走 path-based patch 协议，arguments 经 org.json 解码（去转义）后
 * 转义丢失，下游 parseToolArguments 必然失败。extractToolCalls() 现已对 String 型 arguments
 * 调用 [cleanJsonString] 重新转义（与文本级兜底路径一致）。
 *
 * 本测试与 app 端共用同一份实现（com.mcp.toolbox.cleanJsonString，定义于 tool-compiler 的
 * args.kt），不再镜像算法，确保「原生路径」与「单测」永远不会漂移。校验「破损 arguments →
 * 合法 JSON → 可被 parseToolArguments 解析」。
 */
class SseArgsCleanTest {

    // 破损：SSE 解码后 arguments 内层引号未转义 → 修复后应为合法 JSON 且值正确
    @Test
    fun nativeArgs_unescapedQuotes_repaired() {
        val broken = """{"code":"print("hi")"}"""   // 解码后的损坏形态（内层引号未转义）
        val cleaned = cleanJsonString(broken)
        val obj = McpJson.parseToJsonElement(cleaned).jsonObject
        assertEquals("""print("hi")""", obj["code"]?.jsonPrimitive?.content)
    }

    // 合法 arguments：cleanJsonString 应幂等（不变），不破坏正确转义
    @Test
    fun nativeArgs_valid_idempotent() {
        val valid = """{"code":"print(\"hi\")","n":3}"""
        assertEquals(valid, cleanJsonString(valid))
    }

    // 控制字符（真实换行）被重新转义为 \n，且解析后还原为真实换行
    @Test
    fun nativeArgs_controlChars_reescaped() {
        val withNewline = "{\"code\":\"line1\nline2\"}"   // 含真实换行
        val cleaned = cleanJsonString(withNewline)
        assertEquals(-1, cleaned.indexOf('\n'))           // 不应再含真实换行
        val obj = McpJson.parseToJsonElement(cleaned).jsonObject
        assertEquals("line1\nline2", obj["code"]?.jsonPrimitive?.content)
    }

    // 端到端：cleanJsonString + parseToolArguments 对破损原生 arguments 成功；
    // 反之若跳过 cleanJsonString，parseToolArguments 会因未转义引号抛异常（证明原生路径必须清洗）
    @Test
    fun nativeArgs_endToEnd_parseSucceeds() {
        val broken = """{"script":"echo "x""}"""
        val ok = parseToolArguments(cleanJsonString(broken))
        assertEquals("echo \"x\"", ok["script"]?.jsonPrimitive?.content)
    }
}
