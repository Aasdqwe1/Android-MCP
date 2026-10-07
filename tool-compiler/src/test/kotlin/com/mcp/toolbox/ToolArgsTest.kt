package com.mcp.toolbox

import com.mcp.serialization.McpJson
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * 工具参数解析健壮性（对应工具调用转义错误治理方案 1/2/3/4）。
 */
class ToolArgsTest {

    // 2) 剥离 ```json 围栏后仍可解析
    @Test
    fun stripsJsonFence() {
        val raw = "```json\n{\"a\": 1, \"b\": \"x\"}\n```"
        val obj = parseToolArguments(raw)
        assertEquals(1, obj["a"]?.jsonPrimitive?.int)
        assertEquals("x", obj["b"]?.jsonPrimitive?.content)
    }

    // 2) 剥离 ~~~ 围栏（模型偶发用波浪线围栏）
    @Test
    fun stripsTildeFence() {
        val raw = "~~~json\n{\"ok\": true}\n~~~"
        val obj = parseToolArguments(raw)
        assertEquals(true, obj["ok"]?.jsonPrimitive?.boolean)
    }

    // 2) 尾随逗号修复（kotlinx lenient 不支持尾逗号，需先修）
    @Test
    fun repairsTrailingComma() {
        val obj = parseToolArguments("{\"a\": 1,}")
        assertEquals(1, obj["a"]?.jsonPrimitive?.int)
        val nested = parseToolArguments("{\"arr\": [1, 2,],}")
        val arr = nested["arr"]?.jsonArray
        assertEquals(2, arr?.size)
    }

    // 2) 正常合法 JSON 原样解析
    @Test
    fun parsesValidJson() {
        val obj = parseToolArguments("""{"name":"world","n":3}""")
        assertEquals("world", obj["name"]?.jsonPrimitive?.content)
        assertEquals(3, obj["n"]?.jsonPrimitive?.int)
    }

    // 3) 串内未转义双引号属于结构性破损，应抛出自愈异常（而非静默改成错值）
    @Test
    fun brokenEscape_throwsSelfHealException() {
        // Kotlin 字符串值为 {"code":"print("hi")"} —— 内层引号未做 JSON 转义
        assertFailsWith<ToolArgsParseException> {
            parseToolArguments("""{"code":"print("hi")"}""")
        }
    }

    // 3) 破损 JSON 抛出的自愈异常必须回灌 kotlinx 的失败位置（"第 N 个字符附近"），
    // 让模型能精确定位出错字符，而不是只看到通用原因。
    @Test
    fun brokenEscape_surfacesErrorPosition() {
        val ex = assertFailsWith<ToolArgsParseException> {
            parseToolArguments("""{"code":"print("hi")"}""")
        }
        val msg = ex.message ?: ""
        // 必须包含中文位置前缀 "第" 与具体数字位置（由 kotlinx offset 提炼）
        assertContains(msg, "第")
        assertTrue(msg.any { it.isDigit() }, "自愈提示应包含出错字符位置数字，实际: $msg")
    }

    // 3) 缺失逗号等结构性破损同样应回灌位置信息（位置指向出错字符附近，而非 EOF 才算）
    @Test
    fun missingComma_surfacesErrorPosition() {
        val ex = assertFailsWith<ToolArgsParseException> {
            parseToolArguments("""{"a": 1 "b": 2}""")
        }
        val msg = ex.message ?: ""
        assertContains(msg, "第")
        assertTrue(msg.any { it.isDigit() }, "自愈提示应包含出错字符位置数字，实际: $msg")
    }

    // 3) 非对象（如数组/标量）根节点也应抛出自愈异常
    @Test
    fun nonObjectRoot_throwsSelfHealException() {
        assertFailsWith<ToolArgsParseException> {
            parseToolArguments("[1, 2, 3]")
        }
    }

    // 2/3) errorResult 正确转义，错误模板自身不会产出非法 JSON
    @Test
    fun errorResult_isValidEscapedJson() {
        val msg = "参数含 \"引号\" 与\n换行与 \\ 反斜杠"
        val json = errorResult(msg)
        // 回环解析必须成功且内容一致
        val back = McpJson.parseToJsonElement(json).jsonObject["error"]?.jsonPrimitive?.content
        assertEquals(msg, back)
    }

    // 1) strict 模式：toOpenAi 输出 strict:true 且 schema 全必填 + additionalProperties:false（含嵌套）
    @Test
    fun toOpenAi_strictMode_normalizesSchema() {
        val def = ToolDef(
            name = "demo",
            description = "d",
            parameters = mapOf(
                "a" to ParamSpec("a", ParamType.STRING, required = true),
                "b" to ParamSpec("b", ParamType.INTEGER, required = false), // 可选 → strict 下应升级为必填
                "obj" to ParamSpec(
                    "obj", ParamType.OBJECT, required = true,
                    properties = mapOf("x" to ParamSpec("x", ParamType.STRING, required = false))
                )
            ),
            handler = { "ok" }
        )
        val arr = ToolCompiler.toOpenAi(listOf(def), strict = true)
        val fn = (arr as JsonArray)[0].jsonObject["function"]!!.jsonObject
        assertEquals(true, fn["strict"]?.jsonPrimitive?.boolean)

        val params = fn["parameters"]!!.jsonObject
        assertEquals(false, params["additionalProperties"]?.jsonPrimitive?.boolean)
        val req = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertContains(req, "a")
        assertContains(req, "b")   // 可选参数在 strict 下被强制必填
        assertContains(req, "obj")

        // 嵌套对象同样规范化
        val objProps = params["properties"]!!.jsonObject["obj"]!!.jsonObject
        assertEquals(false, objProps["additionalProperties"]?.jsonPrimitive?.boolean)
        val objReq = objProps["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertContains(objReq, "x")
    }

    // 1) 默认（非 strict）不附加 strict 字段
    @Test
    fun toOpenAi_default_noStrictFlag() {
        val def = ToolDef("demo", "d", handler = { "ok" })
        val arr = ToolCompiler.toOpenAi(listOf(def))
        val fn = (arr as JsonArray)[0].jsonObject["function"]!!.jsonObject
        assertEquals(null, fn["strict"])
    }

    // ── OpenAI function calling 信封解析（与 DeepSeek 文本路径共用同一实现）──


    // OpenAI function calling 风格：顶层 {"name":...,"arguments":{...}}
    @Test
    fun envelope_openAiFormat_nameArguments() {
        val env = parseToolCallEnvelope(
            """{"name":"run_bash","arguments":{"cmd":"ls -la"}}"""
        )
        assertEquals("run_bash", env?.name)
        assertEquals("ls -la", env?.argumentsJson?.let { parseToolArguments(it) }?.get("cmd")?.jsonPrimitive?.content)
    }

    // OpenAI function calling 风格：{"function":{"name":...,"arguments":{...}}} 包裹
    @Test
    fun envelope_openAiFormat_functionWrapper() {
        val env = parseToolCallEnvelope(
            """{"function":{"name":"read_file","arguments":{"path":"/tmp/a.kt"}}}"""
        )
        assertEquals("read_file", env?.name)
        assertEquals("/tmp/a.kt", env?.argumentsJson?.let { parseToolArguments(it) }?.get("path")?.jsonPrimitive?.content)
    }

    // OpenAI API 风格：arguments 为 JSON 字符串（非对象），需正确还原为对象
    @Test
    fun envelope_openAiFormat_argsAsString() {
        val env = parseToolCallEnvelope(
            """{"name":"echo","arguments":"{\"text\":\"hi\"}"}"""
        )
        assertEquals("echo", env?.name)
        assertEquals("hi", env?.argumentsJson?.let { parseToolArguments(it) }?.get("text")?.jsonPrimitive?.content)
    }

    // ── 行式协议（零转义）──

    // 标量键值：值到行尾，无需转义
    @Test
    fun lineProtocol_basicScalars() {
        val calls = parseToolCallLineProtocol(
            """
            tool_call: read_file
            id: call_1
            path: /sdcard/x.txt
            start_line: 10
            end_line: 20
            expand_blocks: true
            """.trimIndent()
        )
        assertEquals(1, calls.size)
        val c = calls[0]
        assertEquals("read_file", c.name)
        assertEquals("call_1", c.callId)
        val args = parseToolArguments(c.argumentsJson)
        assertEquals("/sdcard/x.txt", args["path"]?.jsonPrimitive?.content)
        assertEquals(10, args["start_line"]?.jsonPrimitive?.int)
        assertEquals(20, args["end_line"]?.jsonPrimitive?.int)
        assertEquals(true, args["expand_blocks"]?.jsonPrimitive?.boolean)
    }

    // 值内含引号/反斜杠/冒号：零转义，原样保留
    @Test
    fun lineProtocol_quotesAndColonNoEscape() {
        val calls = parseToolCallLineProtocol(
            """
            tool_call: grep
            id: call_2
            pattern: "a"b\c:d
            path: /x
            """.trimIndent()
        )
        assertEquals(1, calls.size)
        val args = parseToolArguments(calls[0].argumentsJson)
        assertEquals(""""a"b\c:d""", args["pattern"]?.jsonPrimitive?.content)
    }

    // 多行定界块：<<< ... >>> 原样保留
    @Test
    fun lineProtocol_multiLineBlock() {
        val calls = parseToolCallLineProtocol(
            """
            tool_call: write_file
            id: call_3
            path: /x/note.txt
            content <<<
            line1 "quoted"
            line2 \ backslash
            line3

            >>> 
            """.trimIndent()
        )
        assertEquals(1, calls.size)
        val args = parseToolArguments(calls[0].argumentsJson)
        assertEquals("line1 \"quoted\"\nline2 \\ backslash\nline3", args["content"]?.jsonPrimitive?.content)
    }

    // 多个工具段：连续输出并行调用
    @Test
    fun lineProtocol_multipleCalls() {
        val calls = parseToolCallLineProtocol(
            """
            tool_call: read_file
            id: call_1
            path: /a.txt

            tool_call: read_file
            id: call_2
            path: /b.txt
            """.trimIndent()
        )
        assertEquals(2, calls.size)
        assertEquals("call_1", calls[0].callId)
        assertEquals("/a.txt", parseToolArguments(calls[0].argumentsJson)["path"]?.jsonPrimitive?.content)
        assertEquals("call_2", calls[1].callId)
        assertEquals("/b.txt", parseToolArguments(calls[1].argumentsJson)["path"]?.jsonPrimitive?.content)
    }

    // 模型语气词/解释混入：只认 tool_call: 段
    @Test
    fun lineProtocol_ignoresProse() {
        val calls = parseToolCallLineProtocol(
            """
            好的，我来读取这个文件。
            tool_call: read_file
            id: call_9
            path: /x/y.txt
            以上是工具调用。
            """.trimIndent()
        )
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        assertEquals("/x/y.txt", parseToolArguments(calls[0].argumentsJson)["path"]?.jsonPrimitive?.content)
    }

    // 代码块围栏与 tool_call 同行（```tool_call: x）也能解析
    @Test
    fun lineProtocol_fenceSameLineAsToolCall() {
        val calls = parseToolCallLineProtocol(
            """
            ```tool_call: calculator
            id: call_1
            expression: 1+1
            ```
            """.trimIndent()
        )
        assertEquals(1, calls.size)
        val c = calls[0]
        assertEquals("calculator", c.name)
        assertEquals("call_1", c.callId)
        val args = parseToolArguments(c.argumentsJson)
        assertEquals("1+1", args["expression"]?.jsonPrimitive?.content)
    }

    // 整段被 ```json 围栏包裹也能解析
    @Test
    fun lineProtocol_wholeBlockFenced_notExecuted() {
        // 整条消息被围栏包裹时——围栏内一律视为示例，不再执行（历史上曾作为兼容例外，现已移除）
        val calls = parseToolCallLineProtocol(
            """
            ```json
            tool_call: read_file
            id: call_5
            path: /a/b.txt
            ```
            """.trimIndent()
        )
        assertEquals(0, calls.size, "围栏内一律视为示例，不再执行")
    }

    // // 前缀示例行不被执行（提示词规则 0：演示/示例用 // 标记，系统忽略）
    @Test
    fun lineProtocol_hashPrefixedExamplesIgnored() {
        val calls = parseToolCallLineProtocol(
            """
            我来演示一下调用格式：
            //tool_call: read_file
            //path: /x.kt
            //return_format: raw
            tool_call: read_file
            id: call_1
            path: /real.kt
            """.trimIndent()
        )
        assertEquals(1, calls.size)
        assertEquals("read_file", calls[0].name)
        val args = parseToolArguments(calls[0].argumentsJson)
        assertEquals("/real.kt", args["path"]?.jsonPrimitive?.content)
        // 演示段（// 行）既不被当作工具调用，也不混入真实调用的参数
        assertEquals(null, args["//path"])
    }

    // ── 多行参数强制定界块：模型未用定界块、把多行内容直接写在「key: 第一行」之后时报错 ──

    // 裸多行 old_string/new_string（含空行与 kv 样式内容行）未用定界块 → 抛 ToolArgsParseException
    @Test
    fun lineProtocol_multilineRequiresDelimiterBlock() {
        assertFailsWith<ToolArgsParseException> {
            parseToolCallLineProtocol(
                """
                tool_call: edit_file
                id: call_23
                path: /x.txt
                old_string: 第一行内容
                第二行
                拿到结果后：还有后续
                new_string: 第一行内容
                第二行
                第三行
                """.trimIndent()
            )
        }
    }

    // 多行参数未用定界块（即使只写单行）→ 抛异常；标量参数不受影响
    @Test
    fun lineProtocol_scalarNotSwallowedByTrailingParams() {
        val calls = parseToolCallLineProtocol(
            """
            tool_call: edit_file
            path: /x.txt
            old_string <<<
            abc
            >>>
            new_string <<<
            def
            >>>
            occurrence: 2
            """.trimIndent()
        )
        assertEquals(1, calls.size)
        val args = parseToolArguments(calls[0].argumentsJson)
        assertEquals("abc", args["old_string"]?.jsonPrimitive?.content)
        assertEquals("def", args["new_string"]?.jsonPrimitive?.content)
        assertEquals(2, args["occurrence"]?.jsonPrimitive?.int)
    }

    // 多行参数未用定界块（后跟裸内容行）→ 抛异常，即使后面还有 tool_call 段
    @Test
    fun lineProtocol_multilineRequiresDelimiterBeforeNextToolCall() {
        assertFailsWith<ToolArgsParseException> {
            parseToolCallLineProtocol(
                """
                tool_call: edit_file
                path: /x.txt
                old_string: a
                b
                tool_call: calculator
                expression: 1+1
                """.trimIndent()
            )
        }
    }

    // 多行参数单行格式（引号包裹的 JSON 字面量）→ 现在也要求定界块，抛异常
    @Test
    fun lineProtocol_multilineQuotedValueRequiresDelimiter() {
        assertFailsWith<ToolArgsParseException> {
            parseToolCallLineProtocol(
                """
                tool_call: edit_file
                path: /x.kt
                edit_1_old: " if (j == 0)"
                edit_1_new: if (j == 0) { }
                """.trimIndent()
            )
        }
    }

    // 多行参数单行格式（含 \n 转义的 JSON 字面量）→ 现在也要求定界块，抛异常
    @Test
    fun lineProtocol_multilineEscapedNewlinesRequiresDelimiter() {
        assertFailsWith<ToolArgsParseException> {
            parseToolCallLineProtocol(
                """
                tool_call: edit_file
                path: /x.kt
                old_string: "fun main() {\n    println(\"hello\")\n}"
                new_string: "fun main() {\n    println(\"world\")\n}"
                """.trimIndent()
            )
        }
    }

    @Test
    fun lineProtocol_unquotedValueStillWorks() {
        // 不带引号的值不受影响
        val calls = parseToolCallLineProtocol(
            """
            tool_call: grep
            pattern: for (i in 0 until
            path: /storage/emulated/0/Work/test.kt
            """.trimIndent()
        )
        assertEquals(1, calls.size)
        val args = parseToolArguments(calls[0].argumentsJson)
        assertEquals("for (i in 0 until", args["pattern"]?.jsonPrimitive?.content)
        assertEquals("/storage/emulated/0/Work/test.kt", args["path"]?.jsonPrimitive?.content)
    }

    // ── 多行聚合修复：quoted 值后跟 markdown 内容不应被吞入 ──

    // 多行参数（old_string/new_string）即使只写单行值也必须用定界块 → 抛异常
    @Test
    fun lineProtocol_multilineQuotedValueRequiresDelimiter2() {
        assertFailsWith<ToolArgsParseException> {
            parseToolCallLineProtocol(
                """
                tool_call: edit_file
                id: call_57
                path: /x.kt
                old_string: "            this.name = \"test\""
                new_string: "            this.name = \"modified\""
                ```

                ### 测试 10：匹配单行代码（含引号但不用转义）

                ```
                tool_call: edit_file
                id: call_58
                path: /x.kt
                old_string: "    println(\"hello\")"
                new_string: "    println(\"world\")"
                """.trimIndent()
            )
        }
    }

    // 不带引号的多行 old_string 后跟裸内容行：未用定界块 → 抛异常（即使后面有 markdown 围栏）
    @Test
    fun lineProtocol_multilineNoDelimiterThrowsBeforeFence() {
        assertFailsWith<ToolArgsParseException> {
            parseToolCallLineProtocol(
                """
                tool_call: edit_file
                path: /x.kt
                old_string: fun foo() {
                    println("hello")
                }
                new_string: fun foo() {
                    println("world")
                }
                ```
                some trailing text
                """.trimIndent()
            )
        }
    }

    // 多行参数单行值（即使后跟结构行）也要用定界块 → 抛异常
    @Test
    fun lineProtocol_multilineSingleLineValueRequiresDelimiter() {
        assertFailsWith<ToolArgsParseException> {
            parseToolCallLineProtocol(
                """
                tool_call: edit_file
                path: /x.kt
                old_string: old line
                new_string: new line
                ### Section Header
                some text
                """.trimIndent()
            )
        }
    }

    // toLineProtocolTools：单行参数带「单行值」提示，多行参数带定界块提示，且都用实际参数名
    @Test
    fun toLineProtocolTools_marksMultiLineParams() {
        val def = ToolDef(
            name = "write_file",
            description = "写入文件",
            parameters = mapOf(
                "path" to ParamSpec("path", ParamType.STRING, "文件路径"),
                "content" to ParamSpec("content", ParamType.STRING, "要写入的内容", multiLine = true)
            ),
            handler = { "ok" }
        )
        val listing = ToolCompiler.toProtocolTools(listOf(def))
        // 多行参数：实际参数名的定界块提示
        assertTrue(
            listing.contains("content(必填) 要写入的内容（多行值，必须用 `content <<< … >>>` 定界块包裹）"),
            "multiLine 参数应带实际参数名的定界块提示，实际: $listing"
        )
        // 单行参数：带「单行值」提示，且不含定界块
        assertTrue(
            listing.contains("path(必填) 文件路径（单行值：冒号后写到行尾，无需引号、不转义）"),
            "单行参数应带「单行值」提示，实际: $listing"
        )
        assertFalse(
            "path(必填) 文件路径（单行值：冒号后写到行尾，无需引号、不转义）".contains("定界块"),
            "单行参数不应出现定界块提示"
        )
    }
}

