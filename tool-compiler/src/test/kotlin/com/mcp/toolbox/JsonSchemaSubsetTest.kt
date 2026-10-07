package com.mcp.toolbox

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JsonSchemaSubset 测试桩：证明 dsh 子集（type/oneOf/properties/required/
 * additionalProperties/items/enum/const）校验可用，且**违规全量汇总**。
 */
class JsonSchemaSubsetTest {

    private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun value(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test
    fun `valid arguments pass`() {
        val s = schema("""{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""")
        assertTrue(JsonSchemaSubset.validate(s, value("""{"path":"/a.txt"}""")).isEmpty())
    }

    @Test
    fun `type mismatch is reported with path`() {
        val s = schema("""{"type":"object","properties":{"count":{"type":"integer"}}}""")
        val errors = JsonSchemaSubset.validate(s, value("""{"count":"3"}"""))
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("$.count"), errors[0])
        assertTrue(errors[0].contains("integer") && errors[0].contains("string"), errors[0])
    }

    @Test
    fun `missing required and unexpected property are aggregated`() {
        val s = schema(
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}"""
        )
        val errors = JsonSchemaSubset.validate(s, value("""{"extra":1}"""))
        assertEquals(2, errors.size, errors.toString())
        assertTrue(errors.any { it.contains("缺少必填参数") && it.contains("path") }, errors.toString())
        assertTrue(errors.any { it.contains("不允许多余参数") && it.contains("extra") }, errors.toString())
    }

    @Test
    fun `nested object and array items are validated`() {
        val s = schema(
            """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"n":{"type":"integer"}},"required":["n"]}}}}"""
        )
        val errors = JsonSchemaSubset.validate(s, value("""{"items":[{"n":1},{"n":"x"},{}]}"""))
        assertEquals(2, errors.size, errors.toString())
        assertTrue(errors.any { it.startsWith("$.items[1].n") }, errors.toString())
        assertTrue(errors.any { it.startsWith("$.items[2]") && it.contains("缺少必填参数") }, errors.toString())
    }

    @Test
    fun `enum and const are enforced`() {
        val s = schema("""{"type":"object","properties":{"mode":{"enum":["a","b"]},"v":{"const":7}}}""")
        val errors = JsonSchemaSubset.validate(s, value("""{"mode":"c","v":8}"""))
        assertEquals(2, errors.size, errors.toString())
        assertTrue(errors.any { it.contains("不在枚举") }, errors.toString())
        assertTrue(errors.any { it.contains("期望常量") }, errors.toString())
    }

    @Test
    fun `oneOf requires exactly one branch`() {
        val s = schema("""{"oneOf":[{"type":"string"},{"type":"integer"}]}""")
        assertTrue(JsonSchemaSubset.validate(s, value(""""s"""")).isEmpty())
        val none = JsonSchemaSubset.validate(s, value("""true"""))
        assertEquals(1, none.size, none.toString())
        assertTrue(none[0].contains("不匹配 oneOf"), none.toString())
    }

    @Test
    fun `schema without type accepts any json value`() {
        // 对应 ParamType.JSON：空 schema 必须放行 object/array/number/bool/null
        val s = schema("""{"description":"任意 JSON 值"}""")
        listOf("""{"a":1}""", """[1,2]""", """3""", """true""", """null""").forEach {
            assertTrue(JsonSchemaSubset.validate(s, value(it)).isEmpty(), "should accept " + it)
        }
    }

    @Test
    fun `integer rejects fractional number`() {
        val s = schema("""{"type":"integer"}""")
        assertTrue(JsonSchemaSubset.validate(s, value("""3""")).isEmpty())
        assertEquals(1, JsonSchemaSubset.validate(s, value("""3.5""")).size)
    }

    // 回归：JSON Schema 规范规定 integer 是 number 的子类型。
    // 曾因 typeOf 把整数判成 "integer"、与声明的 "number" 字符串精确比较而误拒，
    // 导致所有 number 参数（speed/lines/timeout_sec）传整数都用不了。
    @Test
    fun `number accepts integer`() {
        val s = schema("""{"type":"number"}""")
        assertTrue(JsonSchemaSubset.validate(s, value("""30""")).isEmpty(), "整数应通过 number 校验")
        assertTrue(JsonSchemaSubset.validate(s, value("""3.5""")).isEmpty(), "小数应通过 number 校验")
    }

    // 反向不能放宽：number 声明不应接受字符串/布尔
    @Test
    fun `number still rejects non-numeric`() {
        val s = schema("""{"type":"number"}""")
        assertEquals(1, JsonSchemaSubset.validate(s, value(""" "30" """)).size)
        assertEquals(1, JsonSchemaSubset.validate(s, value("""true""")).size)
    }
}
