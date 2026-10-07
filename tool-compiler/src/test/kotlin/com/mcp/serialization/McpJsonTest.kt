package com.mcp.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * McpJson（kotlinx.serialization）健壮性验证。
 *
 * 目标：证明迁移到 kotlinx.serialization 后能解决 org.json 的痛点——
 *   - 严格解析对未转义/非标准结构敏感、易崩溃
 *   - 无类型安全、无编译期字段检查
 * 这里覆盖：宽松解析、忽略未知字段、非法值回落默认、往返序列化。
 */
class McpJsonTest {

    // ── 与 :app 实际 JSON 形状对齐的线模型（仅用于单元测试，:app 端用其自有 @Serializable 领域类）──
    @Serializable
    private data class WireMessage(
        val isUser: Boolean = false,
        val content: String = "",
        val thinking: String = "",
        val toolCall: WireToolCall? = null
    )

    @Serializable
    private data class WireToolCall(
        val id: String = "",
        val name: String = "",
        val arguments: String = "",
        val result: String = ""
    )

    @Serializable
    private enum class WireStatus { PENDING, DONE, WEIRD }

    @Serializable
    private data class WireTodo(
        val id: String,
        val title: String,
        val status: WireStatus = WireStatus.PENDING,
        val notes: String? = null
    )

    @Serializable
    private data class Simple(val a: String = "", val b: Int = 0)

    @Serializable
    private data class Num(val v: Double)

    // 1) ignoreUnknownKeys：后端新增字段不崩（向前兼容）
    @Test
    fun ignoreUnknownKeys_keepsForwardCompat() {
        val json = """{"a":"x","b":2,"extra_field":"should-be-ignored"}"""
        val r = McpJson.decodeFromString<Simple>(json)
        assertEquals("x", r.a)
        assertEquals(2, r.b)
    }

    // 2) coerceInputValues：非法枚举值回落默认，而非抛异常
    @Test
    fun coerceInputValues_fallsBackToDefaultOnBadEnum() {
        val json = """{"id":"t1","title":"task","status":"NOT_A_REAL_STATUS"}"""
        val r = McpJson.decodeFromString<WireTodo>(json)
        assertEquals(WireStatus.PENDING, r.status) // 非法枚举 -> 默认值
        assertEquals("t1", r.id)
    }

    // 3) isLenient：未加引号的 key 可被正确解析（LLM 输出偶发裸 key）。
    //    经验证（kotlinx-serialization 1.8.0）lenient 正确支持：未加引号 key、前导零、浮点数前导 +/-；
    //    但注意单引号字符串会【保留引号本身】进取值（即 'hello' -> "'hello'"），属底层怪异行为，不宜依赖；
    //    也不支持尾随逗号、// 与 /* */ 注释、整数前导 +、NaN/Infinity。
    @Test
    fun isLenient_allowsUnquotedKeys() {
        val json = """{ a: "hello" }""" // 裸 key
        val r = McpJson.decodeFromString<Simple>(json)
        assertEquals("hello", r.a) // 裸 key -> 正常解析（值不带引号）
        assertEquals(0, r.b)       // 缺失字段 -> 默认值
    }

    // 4) isLenient：前导零与浮点数前导 +/- 可解析（007 -> 7；+1.5 -> 1.5）
    @Test
    fun isLenient_allowsLeadingZerosAndNonStrictFloats() {
        val r1 = McpJson.decodeFromString<Simple>("""{ "b": 007 }""")
        assertEquals(7, r1.b) // 前导零 -> 正常解析
        val r2 = McpJson.decodeFromString<Num>("""{v: +1.5}""")
        assertEquals(1.5, r2.v) // 浮点前导 +/- 可解析（整数前导 + 不被支持）
    }

    // 5) 往返序列化：encode -> decode 一致（工具调用结果可稳定持久化）
    @Test
    fun roundTrip_toolCallPreservesData() {
        val msg = WireMessage(
            isUser = false,
            content = "思考结果",
            thinking = "推理过程",
            toolCall = WireToolCall(id = "call_1", name = "write_file", arguments = """{"path":"/x"}""", result = "ok")
        )
        val str = McpJson.encodeToString(msg)
        val back = McpJson.decodeFromString<WireMessage>(str)
        assertEquals(msg, back)
    }

    // 6) 与 org.json 行为对齐：null 字段回落（模拟 LocalStore 读旧文件时 toolCall 缺失）
    @Test
    fun missingOptionalField_fallsBackToNull() {
        val json = """[{"isUser":true,"content":"hi"},{"isUser":false,"content":"reply"}]"""
        val list = McpJson.decodeFromString<List<WireMessage>>(json)
        assertEquals(2, list.size)
        assertEquals(null, list[1].toolCall) // 缺失的可空字段 -> null
    }
}
