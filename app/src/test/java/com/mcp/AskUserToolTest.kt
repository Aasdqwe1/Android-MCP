package com.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ask_user 工具契约与 handler 的回归测试。
 *
 * 背景（修的两个 bug）：
 *  1) `questions` 曾声明为 array，且作者的 `required = false` 写在 array 的**嵌套 block** 里，
 *     只作用于「数组元素的属性」，没作用到 questions 参数本身 → questions 意外变成必填，
 *     「与 question 二选一」的单问题回退路径被校验层直接拒绝（缺少必填参数 "questions"）。
 *  2) 行式协议（DeepSeek 逆向）下没有数组参数的原生表达，模型输出的 questions 会被解析成
 *     JSON 字符串，array 类型声明会以「期望类型 array, 实际 string」拒绝，工具永远进不到 handler。
 *
 * 修法：questions 改为 json 类型（不输出 type 约束）+ required = false，
 * handler 内兼容「字符串编码的 JSON 数组 / 真数组 / 单对象」三种形态。
 * 本测试锁定这两点，防止回退。
 */
class AskUserToolTest {

    private val tool = askUserTool()

    private fun args(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    // ── schema：questions 必须可选，且不得带 type:array 约束 ──────────────

    @Test
    fun `questions 是可选参数`() {
        val spec = tool.parameters["questions"]!!
        assertFalse("questions 必须可选（与 question 二选一），否则单问题路径调不通", spec.required)
    }

    @Test
    fun `questions 不得在 schema required 中`() {
        val required = tool.inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertFalse("questions 不应出现在 required 里，实际=$required", "questions" in required)
    }

    @Test
    fun `questions 不输出 type array 约束`() {
        // json 类型：不写 type，校验层才不会再报「期望类型 array, 实际 string」
        val qSchema = tool.inputSchema["properties"]!!.jsonObject["questions"]!!.jsonObject
        assertFalse("questions 不应带 type 约束，实际 schema=$qSchema", qSchema.containsKey("type"))
    }

    // ── handler：三种传入形态都要能产出 questions 数组 ──────────────────────

    private fun questionsOf(result: String) =
        Json.parseToJsonElement(result).jsonObject["questions"]!!.jsonArray

    @Test
    fun `字符串编码的 JSON 数组也能解析`() = runBlocking {
        // 这正是行式协议下模型实际会传进来的形态（数组被序列化成字符串）
        val a = args(
            """{"questions":"[{\"question\":\"选哪个库\",\"type\":\"choice\",\"options\":\"A|说明,B\"},{\"question\":\"可多选\",\"type\":\"choice\",\"options\":\"登录,搜索\",\"multi_select\":true}]"}"""
        )
        val qs = questionsOf(tool.handler(a))
        assertEquals(2, qs.size)
        assertEquals("选哪个库", qs[0].jsonObject["question"]!!.jsonPrimitive.content)
        assertEquals("choice", qs[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("A|说明,B", qs[0].jsonObject["options"]!!.jsonPrimitive.content)
        assertEquals("true", qs[1].jsonObject["multi_select"]!!.jsonPrimitive.content)
    }

    @Test
    fun `真 JSON 数组形态也能解析`() = runBlocking {
        val a = args("""{"questions":[{"question":"Q1"},{"question":"Q2"}]}""")
        val qs = questionsOf(tool.handler(a))
        assertEquals(2, qs.size)
    }

    @Test
    fun `单对象 questions 也能解析`() = runBlocking {
        val a = args("""{"questions":{"question":"只有一个问题"}}""")
        val qs = questionsOf(tool.handler(a))
        assertEquals(1, qs.size)
        assertEquals("只有一个问题", qs[0].jsonObject["question"]!!.jsonPrimitive.content)
    }

    @Test
    fun `无 questions 时回退到单问题字段`() = runBlocking {
        val a = args("""{"question":"你的名字？","type":"text"}""")
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertEquals("你的名字？", obj["question"]!!.jsonPrimitive.content)
        assertEquals("text", obj["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `非法 questions 字符串降级为空而不抛异常`() = runBlocking {
        val a = args("""{"questions":"这不是 JSON"}""")
        // 不崩、且回退到默认问题
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertTrue(obj.containsKey("question"))
        assertEquals("ask_user", obj["action"]!!.jsonPrimitive.content)
    }

    // ── options：字符串与数组两种形态都必须放行 ──────────────────────────
    // 曾有第 3 个 bug：描述里写着「也接受字符串数组形式（自动合并）」，handler 也确实合并了，
    // 但参数声明为 string，校验层先于 handler 拒绝数组 —— 文档与实现自相矛盾。

    @Test
    fun `options 不输出 type 约束`() {
        // 带 type:string 就会把数组挡在 handler 之外（见上）
        val oSchema = tool.inputSchema["properties"]!!.jsonObject["options"]!!.jsonObject
        assertFalse("options 不应带 type 约束，实际 schema=$oSchema", oSchema.containsKey("type"))
    }

    @Test
    fun `单问题 options 传字符串数组会合并为逗号串`() = runBlocking {
        val a = args("""{"question":"选哪个","type":"choice","options":["A|说明","B","C"]}""")
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertEquals("A|说明,B,C", obj["options"]!!.jsonPrimitive.content)
    }

    @Test
    fun `questions 内层 options 传数组也会合并`() = runBlocking {
        val a = args("""{"questions":[{"question":"Q","type":"choice","options":["X","Y"]}]}""")
        val qs = questionsOf(tool.handler(a))
        assertEquals("X,Y", qs[0].jsonObject["options"]!!.jsonPrimitive.content)
    }

    @Test
    fun `options 传逗号字符串保持原样`() = runBlocking {
        val a = args("""{"question":"选哪个","type":"choice","options":"A|说明,B"}""")
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertEquals("A|说明,B", obj["options"]!!.jsonPrimitive.content)
    }

    // ── type 归一化 ────────────────────────────────────────────────────────
    // 前端只实现 text / confirm / choice 三条渲染分支，非法 type 会静默落进
    // 「只渲染一个文本框」的兜底分支：模型给了 options，用户却看不到任何选项。
    // 归一化必须在后端做——前端无法区分「模型写错了」和「故意要自由输入」。

    @Test
    fun `multiple 归一化为 choice 且开启多选`() = runBlocking {
        val a = args("""{"question":"要装哪些","type":"multiple","options":"jadx,apktool,dex2jar"}""")
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertEquals("choice", obj["type"]!!.jsonPrimitive.content)
        assertEquals("true", obj["multi_select"]!!.jsonPrimitive.content)
    }

    @Test
    fun `single 归一化为单选 choice 且不带 multi_select`() = runBlocking {
        val a = args("""{"question":"选哪个","type":"single","options":"A,B"}""")
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertEquals("choice", obj["type"]!!.jsonPrimitive.content)
        assertFalse("单选不应带 multi_select", obj.containsKey("multi_select"))
    }

    @Test
    fun `有 options 却把 type 写成 text 时纠正为 choice`() = runBlocking {
        // 选项给了却按 text 渲染 = 选项被丢掉，用户只能盲打，这里必须纠正
        val a = args("""{"question":"选哪个","type":"text","options":"A,B"}""")
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertEquals("choice", obj["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `无法识别的 type 且无 options 时降级为 text 而非崩掉`() = runBlocking {
        val a = args("""{"question":"随便说说","type":"whatever"}""")
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertEquals("text", obj["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `yes_no 归一化为 confirm`() = runBlocking {
        val a = args("""{"question":"继续吗","type":"yes_no"}""")
        val obj = Json.parseToJsonElement(tool.handler(a)).jsonObject
        assertEquals("confirm", obj["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `questions 内层的非法 type 同样归一化`() = runBlocking {
        val a = args(
            """{"questions":[{"question":"Q1","type":"multiple","options":"X,Y"},{"question":"Q2","type":"radio","options":"P,Q"}]}"""
        )
        val qs = questionsOf(tool.handler(a))
        assertEquals("choice", qs[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", qs[0].jsonObject["multi_select"]!!.jsonPrimitive.content)
        assertEquals("choice", qs[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("radio 是单选", qs[1].jsonObject.containsKey("multi_select"))
    }
}