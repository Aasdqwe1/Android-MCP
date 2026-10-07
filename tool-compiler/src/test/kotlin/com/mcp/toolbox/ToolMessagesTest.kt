package com.mcp.toolbox

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OpenAI function calling 第 4 步（工具结果回传）的形状约束测试。
 * 以「查天气 + 推荐活动」为例，覆盖单条 / 并行多条 / 顺序 / 转义 / 历史信封兼容。
 */
class ToolMessagesTest {

    @Test
    fun `单条结果就是标准 tool 消息`() {
        val json = ToolMessages.resultJson("call_1", """{"temp":22,"condition":"晴"}""")
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals("tool", o["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", o["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"temp":22,"condition":"晴"}""", o["content"]!!.jsonPrimitive.content)
        // content 必须是字符串（原文透传），而不是被解析成嵌套对象
        assertTrue(o["content"]!!.jsonPrimitive.isString)
        // 只有协议规定的三个字段，不夹带任何信封
        assertEquals(setOf("role", "tool_call_id", "content"), o.keys)
    }

    @Test
    fun `batchJson 单条产出对象而非数组`() {
        val s = ToolMessages.batchJson(listOf("call_2" to "你好\n世界\n"))
        val e = Json.parseToJsonElement(s)
        assertTrue(e is JsonObject, "单条应为 JSON 对象")
        assertEquals("你好\n世界\n", e.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("""{"role":"tool","tool_call_id":"call_2","content":"你好\n世界\n"}""", s)
    }

    @Test
    fun `并行多条产出数组且顺序与 tool_calls 一致`() {
        val s = ToolMessages.batchJson(
            listOf(
                "call_1" to """{"temp":22}""",
                "call_2" to """{"aqi":35}""",
                "call_3" to "第三个"
            )
        )
        val arr = Json.parseToJsonElement(s).jsonArray
        assertEquals(3, arr.size)
        assertEquals(
            listOf("call_1", "call_2", "call_3"),
            arr.map { it.jsonObject["tool_call_id"]!!.jsonPrimitive.content }
        )
        arr.forEach { assertEquals("tool", it.jsonObject["role"]!!.jsonPrimitive.content) }
    }

    @Test
    fun `已是 tool 消息的输入不会被二次包装`() {
        val once = ToolMessages.resultJson("call_1", "晴，22℃")
        val twice = ToolMessages.batchJson(listOf("call_1" to once))
        assertEquals("晴，22℃", Json.parseToJsonElement(twice).jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `兼容历史 JSON-RPC 成功信封`() {
        val legacy = """{"jsonrpc":"2.0","id":"call_1","result":{"content":[{"type":"text","text":"晴，22℃"}]}}"""
        assertEquals("晴，22℃", ToolMessages.extractContent(legacy))
    }

    @Test
    fun `兼容历史 JSON-RPC 错误信封`() {
        val legacy = """{"jsonrpc":"2.0","id":"call_1","error":{"code":-32000,"message":"城市不存在"}}"""
        assertEquals("城市不存在", ToolMessages.extractContent(legacy))
    }

    @Test
    fun `纯文本与空串原样返回`() {
        assertEquals("just text", ToolMessages.extractContent("just text"))
        assertEquals("", ToolMessages.extractContent(""))
        assertEquals("", ToolMessages.extractContent("   "))
    }

    @Test
    fun `工具执行失败的错误 JSON 作为 content 原样透传`() {
        // Toolbox.dispatch 失败时返回 {"error":"..."}，应原样进 content，由模型决定重试/换方案
        val err = """{"error":"unknown tool: get_weather"}"""
        val s = ToolMessages.batchJson(listOf("call_1" to err))
        assertEquals(err, Json.parseToJsonElement(s).jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `空列表返回空串`() {
        assertEquals("", ToolMessages.batchJson(emptyList()))
    }

    @Test
    fun `parseResults 是 batchJson 的逆运算`() {
        val origin = listOf("call_1" to """{"temp":22}""", "call_2" to "你好\n世界\n")
        val back = ToolMessages.parseResults(ToolMessages.batchJson(origin))
        assertEquals(origin, back)

        val single = listOf("call_9" to "晴，22℃")
        assertEquals(single, ToolMessages.parseResults(ToolMessages.batchJson(single)))
    }

    @Test
    fun `parseResults 拒绝普通文本与普通 JSON`() {
        // 用户手打的普通消息不能被误判为工具结果，否则气泡会被吞掉
        assertEquals(null, ToolMessages.parseResults("帮我查下北京天气"))
        assertEquals(null, ToolMessages.parseResults(""))
        assertEquals(null, ToolMessages.parseResults("   "))
        assertEquals(null, ToolMessages.parseResults("""{"role":"user","content":"hi"}"""))
        assertEquals(null, ToolMessages.parseResults("""{"foo":"bar"}"""))
        assertEquals(null, ToolMessages.parseResults("""[]"""))
        assertEquals(null, ToolMessages.parseResults("not json at all {"))
        // 缺 tool_call_id → 无法归属，判否
        assertEquals(null, ToolMessages.parseResults("""{"role":"tool","content":"x"}"""))
        assertEquals(null, ToolMessages.parseResults("""{"role":"tool","tool_call_id":"","content":"x"}"""))
    }

    @Test
    fun `parseResults 对混入非 tool 元素的数组整体判否`() {
        val mixed = """[{"role":"tool","tool_call_id":"call_1","content":"ok"},{"role":"user","content":"hi"}]"""
        assertEquals(null, ToolMessages.parseResults(mixed))
    }

    @Test
    fun `parseResults 容忍前后空白`() {
        val r = ToolMessages.parseResults("\n  {\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"ok\"}  \n")
        assertEquals(listOf("call_1" to "ok"), r)
    }

    @Test
    fun `非字符串 content 不会被吞掉`() {
        // content 为对象 → 序列化回 JSON 文本
        assertEquals(
            """{"temp":22}""",
            ToolMessages.extractContent("""{"role":"tool","tool_call_id":"call_1","content":{"temp":22}}""")
        )
        // content 为数字 → 取字面量
        assertEquals("22", ToolMessages.extractContent("""{"role":"tool","tool_call_id":"call_1","content":22}"""))
        // content 为 null → 空串（而不是字符串 "null"）
        assertEquals("", ToolMessages.extractContent("""{"role":"tool","tool_call_id":"call_1","content":null}"""))
    }

    @Test
    fun `content 中的引号与换行被正确转义`() {
        val raw = "他说：\"下雨了\"\n第二行\t制表"
        val s = ToolMessages.resultJson("call_1", raw)
        // 产物必须是合法 JSON，且解析回来与原文完全一致
        assertEquals(raw, Json.parseToJsonElement(s).jsonObject["content"]!!.jsonPrimitive.content)
    }

    // ── 行式工具结果（零转义，DeepSeek）──

    @Test
    fun `行式单条结果保留原文含引号反斜杠换行`() {
        val raw = "line1 \"quoted\"\nline2 \\ backslash\nline3"
        val s = ToolMessages.lineResult("call_1", raw)
        val parsed = ToolMessages.parseLineResults(s)
        assertEquals(1, parsed?.size)
        assertEquals("call_1", parsed!![0].first)
        assertEquals(raw, parsed[0].second)
    }

    @Test
    fun `行式多条结果顺序与调用一致`() {
        val s = ToolMessages.lineResults(listOf("call_1" to "aaa", "call_2" to "bbb"))
        val parsed = ToolMessages.parseLineResults(s)
        assertEquals(2, parsed?.size)
        assertEquals("call_1" to "aaa", parsed!![0])
        assertEquals("call_2" to "bbb", parsed[1])
    }

    @Test
    fun `parseResults兼容行式与JSON两种形状`() {
        val line = ToolMessages.lineResult("call_9", "hi")
        assertEquals(listOf("call_9" to "hi"), ToolMessages.parseResults(line))
        val json = """{"role":"tool","tool_call_id":"call_8","content":"yo"}"""
        assertEquals(listOf("call_8" to "yo"), ToolMessages.parseResults(json))
    }

    @Test
    fun `extractContent行式结果取块内原文`() {
        val s = ToolMessages.lineResult("call_1", "{\"ok\":true}")
        assertEquals("{\"ok\":true}", ToolMessages.extractContent(s))
    }
}
