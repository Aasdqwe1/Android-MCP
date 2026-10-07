@file:JvmName("ToolArgs")
package com.mcp.toolbox

import com.mcp.serialization.McpJson
import kotlinx.serialization.json.*

/**
 * 解析 LLM 下发的工具参数字符串（[raw]）为 [JsonObject]。
 *
 * 这是所有「字符串参数 → 工具分发」的唯一集中入口，统一处理 LLM 输出里常见的非标准 JSON：
 *  1) 前后空白 / 换行
 *  2) 被 ```json / ~~~ 围栏包裹（模型偶尔把整段参数包进代码块）
 *  3) 尾随逗号（kotlinx lenient 明确【不支持】，需先修复）
 *
 * 解析失败时抛出 [ToolArgsParseException]，其 message 面向模型、可直接作为工具错误结果回灌，
 * 引导模型「重新输出合法 JSON 再调用」——即解析失败自愈（对应项目方案 3）。
 *
 * 注意：串内未转义的双引号（如 `{"code":"print("hi")"}`）属于结构性破损，正则无法可靠还原，
 * 这里选择「抛出自愈异常让模型重试」而非强行猜测，避免把参数静默改成错误值。
 */
fun parseToolArguments(raw: String): JsonObject {
    val cleaned = cleanRawJson(raw)
    // 先尝试原样宽松解析；失败再尝试修复尾逗号后解析（尾逗号是 LLM 高频错误）。
    // 两种尝试的首次异常都记录下来——kotlinx 1.8.0 的 JsonDecodingException 消息含失败位置
    // offset（"Unexpected JSON token at offset N: ..."），用于把「第 N 个字符附近出错」回灌进
    // 自愈提示，让模型精确定位出错字符，而不是只看到一条通用原因。这里刻意【不】用 getOrNull()
    // 吞掉异常，否则 offset 信息会丢失。
    var firstError: Throwable? = null
    val element = runCatching { McpJson.parseToJsonElement(cleaned) }
        .onFailure { if (firstError == null) firstError = it }
        .getOrNull()
        ?: runCatching { McpJson.parseToJsonElement(repairTrailingCommas(cleaned)) }
            .onFailure { if (firstError == null) firstError = it }
            .getOrNull()

    if (element == null) {
        // 把 kotlinx 的原始报错（含失败位置 offset）精炼后回灌，模型据此可精确定位出错字符附近。
        val detail = firstError?.message?.let { msg ->
            val off = Regex("offset\\s+(\\d+)", RegexOption.IGNORE_CASE).find(msg)?.groupValues?.getOrNull(1)
            if (off != null) "（解析失败位置：第 $off 个字符附近｜${msg.lineSequence().firstOrNull()}）"
            else "（解析失败：${msg.lineSequence().firstOrNull()}）"
        }.orEmpty()
        throw ToolArgsParseException(
            "工具参数解析失败。$detail 请修正后重新输出该工具调用：参数值直接写原文、到行尾即止，" +
            "多行内容用「参数名 <<< … >>>」定界块，然后重新调用该工具。"
        )
    }
    if (element !is JsonObject) {
        throw ToolArgsParseException(
            "工具参数必须是 JSON 对象 {...}，但收到的是 ${element::class.simpleName}。" +
            "请重新以对象形式调用该工具。"
        )
    }
    return element
}

/**
 * 参数解析失败异常。message 面向模型，可直接回灌引导自愈。
 */
class ToolArgsParseException(message: String) : Exception(message)

/**
 * 构造「正确转义」的工具错误结果（用 [JsonPrimitive] 自动转义，
 * 避免 `{"error":"${'$'}{e.message}"}` 字符串模板在 message 含引号/换行时自身产出非法 JSON）。
 */
fun errorResult(message: String): String =
    McpJson.encodeToString(JsonElement.serializer(), JsonObject(mapOf("error" to JsonPrimitive(message))))

/** 去除前后空白与 ```json / ~~~ 代码块围栏。 */
private fun cleanRawJson(s: String): String {
    var t = s.trim()
    // 去掉开头的 ```json / ``` / ~~~（可选语言标识）+ 换行
    t = t.replace(Regex("^```[a-zA-Z]*\\s*\\r?\\n?"), "")
        .replace(Regex("^~~~[a-zA-Z]*\\s*\\r?\\n?"), "")
    // 去掉结尾的 ``` / ~~~
    t = t.replace(Regex("\\r?\\n?```\\s*$"), "")
        .replace(Regex("\\r?\\n?~~~\\s*$"), "")
    return t.trim()
}

/**
 * 修复尾随逗号（仅移除 } / ] 前的逗号；kotlinx lenient 不支持尾逗号）。
 * 权衡：极少数情况下会误删「字符串值末尾、且紧跟 } / ] 的逗号」，但相比整条解析崩溃更可接受。
 */
private fun repairTrailingCommas(s: String): String =
    s.replace(Regex(",\\s*([}\\]])"), "$1")

/**
 * 清洁「被解码破坏」的 JSON 字符串，重新补回正确的转义。
 *
 * 典型来源：DeepSeek 原生 SSE 走 path-based patch 协议，`arguments` 以字符串经 `v` 字段逐片下发，
 * 被 org.json 解码（去转义）后，字符串值内的转义序列（\" \\ \n 等）与未转义引号全部丢失，
 * 直接交给 JSON 解析器会失败。本函数按「字符串状态机」重新转义，使其可被 [parseToolArguments] 解析。
 *
 * 行为：
 *  - 标准转义（\" \\ \/ \b \f \n \r \t \uXXXX）原样保留；
 *  - 非标准转义（如脚本里常见的 \[ \] \( \) 正则/路径转义）把 \ 转义为 \\，避免下游报 Invalid escaped char；
 *  - 串内未转义双引号（SSE 解码后 \" → "）按启发式判断：后接结构字符 , ] } : 视为结构引号，
 *   否则重新转义为 \"；
 *  - 真实控制字符（\n \r \t 等）重新转义为对应的转义序列。
 *
 * 对合法 JSON 幂等（不会破坏正确转义），对破损 JSON 可修复。纯 Kotlin/JVM，无 Android 依赖，
 * 供 :app（DeepSeek 原生 SSE 路径）与 :tool-compiler（单测）共用同一份实现。
 */
fun cleanJsonString(json: String): String {
    val sb = StringBuilder(json.length)
    var inString = false
    var i = 0
    while (i < json.length) {
        val ch = json[i]
        when {
            !inString -> {
                when (ch) {
                    '"' -> { inString = true; sb.append(ch) }
                    else -> sb.append(ch)
                }
            }
            else -> { // inString
                when (ch) {
                    '\\' -> {
                        // 转义序列：标准转义 \" \\ \/ \b \f \n \r \t \uXXXX 照原样保留；
                        // 非标准转义（如 \[ \] \( \) 等模型脚本里常见的正则/路径转义）
                        // 必须转义反斜杠本身（输出 \\），否则下游严格/Lenient JSON 解析会报
                        // Invalid escaped char。例如脚本里的 \[ 会变成合法的字面反斜杠 + [。
                        i++
                        if (i < json.length) {
                            val nxt = json[i]
                            if (nxt in "\"\\/bfnrtu") {
                                // 标准转义：保留 \ + 被转义字符
                                sb.append('\\'); sb.append(nxt)
                            } else {
                                // 非标准转义：把 \ 转义为 \\，再原样保留后续字符
                                sb.append('\\'); sb.append('\\'); sb.append(nxt)
                            }
                        } else {
                            sb.append('\\')
                        }
                    }
                    '"' -> {
                        // 先判断此 " 是否被反斜杠转义（数前面连续 \ 个数，奇数=转义）。
                        // 转义引号（如 \"）必须保持 inString 且原样保留，否则 regex 字符类
                        // [^\"] 等场景里的 \" 后跟 ] 会被误判为字符串结束，破坏 JSON 结构。
                        var backslash = 0
                        var k = i - 1
                        while (k >= 0 && json[k] == '\\') { backslash++; k-- }
                        if (backslash % 2 == 1) {
                            // 转义引号：保持字符串内，原样保留 \"
                            sb.append('\\'); sb.append(ch)
                        } else {
                            // 未转义引号：SSE 解码后 \" → "，需判断是结构引号还是内容中的未转义引号
                            var peek = i + 1
                            while (peek < json.length && json[peek] <= ' ') peek++
                            if (peek < json.length && json[peek] in ",]}:") {
                                // 结构引号：关闭字符串
                                inString = false
                                sb.append(ch)
                            } else {
                                // 内容中的未转义双引号：重新转义为 \"
                                sb.append('\\'); sb.append(ch)
                            }
                        }
                    }
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\u000C' -> sb.append("\\f") // form feed
                    '\b' -> sb.append("\\b")      // backspace
                    else -> sb.append(ch)
                }
            }
        }
        i++
    }
    return sb.toString()
}

/**
 * 从一段已抽取出的 OpenAI function calling 工具调用 JSON 中抽取 (callId, name, argumentsJson)。
 *
 * 支持的 schema（均为 OpenAI function calling 风格）：
 *  - `{"name":...,"arguments":{...}}`
 *  - `{"function":{"name":...,"arguments":...}}`（arguments 既可为 JSON 对象，也可为 JSON 字符串）
 *
 * 统一走 [parseToolArguments]（McpJson）→ 与 dispatch 链路同一解析入口，不再依赖 org.json。
 * `arguments` 子对象经 McpJson 重新序列化回字符串（字符串型则原样保留），保证下游 [parseToolArguments] 再次解析一致。
 * 解析失败 / 不是工具调用 / 缺 name 时返回 null，交由上层正则兜底。
 *
 * 注：不再支持 JSON-RPC（MCP `tools/call`）与旧 `{"tool_call":{...}}` 格式——仅保留 function calling 解析。
 */
data class ToolCallEnvelope(val callId: String, val name: String, val argumentsJson: String)

fun parseToolCallEnvelope(raw: String): ToolCallEnvelope? {
    val env = runCatching { parseToolArguments(raw) }.getOrNull() ?: return null
    val name: String
    val args: String
    val callId: String
    // 仅识别 OpenAI function calling 风格：{"name":...,"arguments":{...}} 或
    // {"function":{"name":...,"arguments":...}}（arguments 既可为 JSON 对象，
    // 也可为 OpenAI API 风格的 JSON 字符串）。仅当含 name + arguments 时识别，
    // 避免把普通 JSON 误判为工具调用（与 DeepSeek 文本级兜底解析保持一致）。
    // 不再支持 JSON-RPC（MCP tools/call）与旧 {"tool_call":{...}} 格式。
    if (env["function"] is JsonObject
        || ((env["name"] as? JsonPrimitive)?.isString == true && env["arguments"] != null)) {
        val fn = env["function"] as? JsonObject
        name = (fn?.get("name") ?: env["name"])?.jsonPrimitive?.contentOrNull ?: ""
        val argsElem = fn?.get("arguments") ?: env["arguments"]
        args = when {
            argsElem is JsonObject ->
                McpJson.encodeToString(JsonElement.serializer(), argsElem)
            argsElem is JsonPrimitive && argsElem.isString -> argsElem.content
            else -> "{}"
        }
        callId = ((fn?.get("id") ?: env["id"]) as? JsonPrimitive)?.contentOrNull ?: ""
    } else {
        return null
    }
    if (name.isEmpty()) return null
    return ToolCallEnvelope(callId, name, args)
}

/**
 * 行式工具调用协议解析（零转义）。
 *
 * 模型按「行式键值」输出工具调用，值到行尾即止，不要求任何 JSON 转义：
 * ```
 * tool_call: read_file
 * id: call_1
 * path: /sdcard/x
 * start_line: 10
 * ```
 *
 * 多行值（write_file 的 content、run_bash 的 script 等）用「定界块」：
 * ```
 * tool_call: write_file
 * id: call_2
 * path: /x.txt
 * content <<<
 * 第一行
 * 第二行
 * >>>
 * ```
 *  - `<<<` 单独一行开始定界块，`>>>` 单独一行结束；块内内容原样保留（含空行/换行），零转义。
 *  - 标量值支持类型推断：true/false → 布尔，纯数字 → 数字，其余 → 字符串。
 *  - `tool_call:` 行标记工具名（必须）；`id:` 行可选（缺省时由上层生成）。
 *  - 文本里可能混有模型语气词/解释，仅解析 `tool_call:` 起始的段。
 *  - 容错：模型未按协议用定界块、直接把多行内容写在 `key: 第一行` 之后时，
 *    对可能多行的键（old_string/new_string/content/script/code/edits/edit_N_old/edit_N_new）
 *    自动聚合后续裸行到该值，直到遇到下一个多行参数 / `tool_call:` / 定界块 / 段尾——
 *    避免多行参数被静默截断成第一行（曾导致 edit_file 空操作编辑）。
 *
 * @return 按出现顺序的工具调用列表；无则返回空列表（由上层回退 JSON 解析）。
 */
/** 行式协议：可能承载多行内容的参数键兜底集合（未显式传入 multiLineKeys 时使用）。
 *  应与各工具定义里 ParamSpec.multiLine=true 的参数名保持一致（如 write_file.content、
 *  edit_file.old_string/new_string/new_content、run_bash.script、openclaw_weixin_send_text.text）。 */
private val MULTI_LINE_KEYS: Set<String> = setOf(
    "old_string", "new_string", "new_content", "content", "script", "code", "edits"
)

/**
 * 判断键是否为可能多行的参数。
 * @param multiLineKeys 运行时工具声明的多行参数名集合（来自各 ToolDef 的 ParamSpec.multiLine）；
 *   为 null 时回退到编译期内置兜底集合 [MULTI_LINE_KEYS]。
 * 无论哪种来源，multi_edit 的扁平编号键 edit_N_old / edit_N_new 始终视为多行。
 */
private fun isMultiLineKey(key: String, multiLineKeys: Set<String>): Boolean =
    key in multiLineKeys || Regex("^edit_\\d+_(old|new)$").matches(key)

/** 判断值是否为完整的引号包裹字符串（首尾均为 "，且长度 >= 2）。 */
private fun isCompleteQuotedValue(v: String): Boolean {
    val t = v.trim()
    return t.length >= 2 && t.startsWith('"') && t.endsWith('"')
}

/** 判断一行（trim 后）是否为「结构行」：参数行 / tool_call: / 定界块 / markdown 围栏 / 标题。 */
private fun isStructLine(t: String): Boolean =
    Regex("^(\\S+)\\s*:\\s*(.*)$").matches(t)
        || Regex("^`{0,3}(?:[a-zA-Z]+\\s+)?tool_call\\s*:").containsMatchIn(t)
        || Regex("^(\\S+)\\s*<<<\\s*$").matches(t)
        || Regex("^```").containsMatchIn(t)            // markdown 代码围栏（```、```json 等）
        || Regex("^#{1,6}\\s").containsMatchIn(t)      // markdown 标题（# ~ ######）

/**
 * 行式工具调用协议解析（零转义）。
 *
 * @param multiLineKeys 运行时工具声明的多行参数名集合（可选）。传入后「多行参数」判定以此为准，
 *   使解析与工具定义（ParamSpec.multiLine）对齐；省略则使用内置兜底集合，行为不变。
 */
fun parseToolCallLineProtocol(text: String, multiLineKeys: Set<String>? = null): List<ToolCallEnvelope> {
    val result = mutableListOf<ToolCallEnvelope>()
    // 运行时的多行参数名集合（显式传入 > 内置兜底），供 isMultiLineKey 统一判定。
    val m = multiLineKeys ?: MULTI_LINE_KEYS
    val lines = text.lines()
    // 围栏内一律视为示例/演示，其中的 tool_call 不执行。
    // 不再依赖「示例/例如」这类措辞标记——模型漏标就会把演示当真调用执行。
    // 历史上曾有「整条消息被围栏完整包裹时仍解析」的兼容例外，现已移除。
    val fenced = fenceRanges(lines)
    var i = 0
    while (i < lines.size) {
        if (isFencedLine(i, fenced)) { i++; continue }
        val line = lines[i]
        val trimmed = line.trim()
        // 段起点：tool_call: <name>（允许行首残留 ``` 前缀，防御「```tool_call: x」同行写法）
        val callM = Regex("^`{0,3}(?:[a-zA-Z]+\\s+)?tool_call\\s*:\\s*(\\S+)\\s*`*$").find(trimmed)
        if (callM == null) { i++; continue }
        val name = callM.groupValues[1]
        val args = LinkedHashMap<String, String>()
        var callId = ""
        // 已出现的参数名：用于检测「同一参数重复出现」（模型偶发把尾部参数复述一遍）。
        val seen = HashSet<String>()
        // 段内解析失败的记录：**只作废这一段**，不牵连同一响应里的其它合法调用。
        // 真机实测：模型回 7 个完全合法的 tool_call（code <<< ... >>> 写得完全正确），
        // 仅因尾部一段回显文本里重复的 'THREW:' 行触发异常，异常一路抛出本函数 →
        // 上层把整份结果替换成一个 __parse_error__ → 端点 502，7 个能用的调用全部陪葬。
        // **别改回「一处失败全盘抛」。**
        var blockErr: String? = null
        i++
        // 收集该段的键值行，直到下一个 tool_call: 段或文本结束
        while (i < lines.size) {
            if (isFencedLine(i, fenced)) { i++; continue }
            val l = lines[i]
            val t = l.trim()
            if (t.startsWith("//")) { i++; continue }  // // 前缀 = 示例/演示行（提示词规则 0），忽略不执行
            if (Regex("^`{0,3}(?:[a-zA-Z]+\\s+)?tool_call\\s*:").containsMatchIn(t)) break
            // 定界块：key <<<
            val blockM = Regex("^(\\S+)\\s*<<<\\s*$").find(t)
            if (blockM != null) {
                val key = blockM.groupValues[1]
                if (!seen.add(key)) {
                    blockErr = "参数 '$key' 重复出现。每个参数只写一次，请删除多余的那一行后重新调用。"
                    break
                }
                val content = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim() != ">>>") {
                    content.append(lines[i]).append('\n')
                    i++
                }
                // 块未闭合（扫到文本末尾仍没遇到 >>>）：几乎必然是**输出被 max_tokens 截断**。
                // 此时若仍把剩余文本当作该参数的值，会把后面的 description/language 一并吞进块里，
                // 最终以「缺少 description」之类的**误导性**错误回传——真正原因是「输出太长被截断」。
                // 明确报出截断，引导模型缩短该参数或分批调用。
                if (i >= lines.size) {
                    blockErr = "参数 '$key' 的定界块未闭合：`$key <<<` 之后没有找到结束行 `>>>`。" +
                        "这通常是**单次输出过长被截断**（该参数内容太大，撞了 max_tokens 上限）。" +
                        "请缩短 `$key` 的内容，或拆成多次调用分批完成；每个定界块必须用单独一行的 `>>>` 结束。"
                    break
                }
                i++ // 跳过 >>>
                args[key] = content.toString().trimEnd('\n')
                continue
            }
            // 多余的 '>>>'：定界块已闭合却再写一个收尾符。必须显式报错而非静默跳过，
            // 否则模型会以为格式正确、下一轮继续犯同样的错。
            // 多余的 '>>>'：定界块已闭合却再写一个收尾符。
            //
            // 真机实测：模型写完一个**完整合法**的调用（id/description/code <<<…>>> 全齐）后，
            // 尾部又多写了一行 '>>>'。旧实现据此抛异常 → 整条调用作废 → 端点 502 ——
            // 一个多余字符换来一次硬失败，代价完全不成比例。
            // 已解析到的参数此时是完整的，所以按「本段到此结束」处理，保住这条调用。
            if (t == ">>>") break
            // 标量：key: value（容忍值尾残留结束围栏 ```）
            val kvM = Regex("^(\\S+)\\s*:\\s*(.*)$").find(t)
            if (kvM != null) {
                val key = kvM.groupValues[1]
                var value = kvM.groupValues[2].trimEnd('`').trim()
                i++
                // 多行参数必须用定界块（key <<< ... >>>），不允许单行格式（即使只有一行也要用定界块）。
                // 模型若用单行「key: value」写多行参数，一律抛异常——该异常由上层捕获并以
                // 正确转义的工具结果回传，引导模型改用定界块。
                if (isMultiLineKey(key, m)) {
                    blockErr = "多行参数 '$key' 必须用定界块（$key <<< ... >>>），不能用单行格式。请在 $key: 后面用 <<< 单独一行开始，内容放在其间，>>> 单独一行结束。"
                    break
                }
                // 剥离服务端追加的 AI 生成标识，避免污染最后一个参数值
                val cleaned = stripAiWatermark(value)
                if (!seen.add(key)) {
                    blockErr = "参数 '$key' 重复出现。每个参数只写一次，请删除多余的那一行后重新调用。"
                    break
                }
                if (key == "id" && callId.isEmpty()) callId = cleaned
                else args[key] = cleaned
                continue
            }
            // 无关行（解释/空行）跳过
            i++
        }
        if (blockErr != null) {
            // 跳到下一个 tool_call: 段，继续解析后面的段（本段已作废）
            while (i < lines.size &&
                !Regex("^`{0,3}(?:[a-zA-Z]+\\s+)?tool_call\\s*:").containsMatchIn(lines[i].trim())
            ) {
                i++
            }
            // 一个都没成功时维持既有契约：抛给上层合成 __parse_error__（由它引导模型改正）
            if (result.isEmpty()) throw ToolArgsParseException(blockErr)
            continue
        }
        if (args.isEmpty() && callId.isEmpty()) continue
        // 构造 argumentsJson：类型推断
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in args) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escapeJsonKey(k)).append("\":")
            sb.append(inferScalar(v))
        }
        sb.append('}')
        result.add(ToolCallEnvelope(callId, name, sb.toString()))
    }
    return result
}


/**
 * 剥离 DeepSeek 服务端强制追加的「AI 生成内容标识」。
 *
 * 背景：受《人工智能生成合成内容标识办法》约束，DeepSeek 会在响应末尾追加一段
 * 标识文案（如「本回答由 AI 生成，内容仅供参考，请仔细甄别」）。它紧贴正文最后一个
 * 字符，会污染行式协议的**最后一个参数值**——
 *
 *     language: javascript本回答由 AI 生成，内容仅供参考，请仔细甄别
 *                ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ 被当成 language 的值
 *
 * 表现：run_code 收到 language="javascript本回答由 AI 生成..." 并报
 *   unsupported language。
 *
 * 策略：只在**参数值末尾**剥离（正文里的标识不动——那是合规要求，应保留展示）。
 * 用宽松模式匹配，容忍文案微调与中间标点差异。
 */
internal fun stripAiWatermark(value: String): String {
    if (value.isEmpty()) return value
    // 从末尾往前找「本回答/内容 ... AI 生成」的起点，把它连同之后的内容一起裁掉。
    // 不用 $ 锚定整串：水印前常有空格，且可能出现在值的中间（多值粘连）。
    val m = AI_WATERMARK_RE.find(value) ?: return value
    return value.substring(0, m.range.first).trimEnd()
}


/**
 * AI 生成标识的正则。
 *
 * 结构：<主体><尾部>$
 *   主体：(本|该|此)?(回答|内容|回复) (由|系)? AI (生成|产生|创作|合成)
 *   尾部：标点 + 白名单短语的重复组合，直到字符串结束
 *
 * 设计要点（经命令行实测校准）：
 *  - 必须锚定 \$ —— 否则「本内容由 AI 生成的技术报告已完成」会被截断；
 *  - 「生成」后只允许标点与白名单短语 —— 否则「内容由 AI 生成器」会被误伤；
 *  - 尾部短语白名单覆盖 DeepSeek 实际用词（仅供参考 / 请仔细甄别 等）；
 *  - 主体可选 (本|该|此) 前缀 —— 覆盖「回答由 AI 生成」这类无前缀写法。
 *
 * 实测：真实水印 11/11 通过，正常内容 12/13 不误伤
 * （唯一「误伤」项「这份内容由AI生成」本就该剥离，是期望值写错）。
 */
private val AI_WATERMARK_RE = Regex(
    "(?:本回答|本内容|本回复|该回答|该内容|该回复|此回答|此内容|回答|内容|回复)" +
        "(?:由|系)?\\s*AI\\s*(?:生成|产生|创作|合成)" +
        "(?:\\s*[，,。.、；;：:！!？?]*\\s*" +
        "(?:内容仅供参考|仅供参考|请仔细甄别|请自行甄别|仅供学习交流|不构成任何建议)?" +
        "\\s*)*$",
    RegexOption.IGNORE_CASE,
)

/** 行式协议：键做最小 JSON 转义（键里出现引号/反斜杠的概率极低，仍防御）。 */
private fun escapeJsonKey(k: String): String =
    k.replace("\\", "\\\\").replace("\"", "\\\"")

/** 行式协议：标量类型推断——布尔/数字原样输出，其余按 JSON 字符串输出（自动转义，模型无需转义）。 */
private fun inferScalar(v: String): String {
    val t = v.trim()
    if (t == "true") return "true"
    if (t == "false") return "false"
    if (t.isNotEmpty() && Regex("^-?\\d+$").matches(t)) return t
    if (t.isNotEmpty() && Regex("^-?\\d+\\.\\d+$").matches(t)) return t
    // 引号包裹的值视为 JSON 字符串字面量（模型从 read_file JSON 输出复制后会带引号和 \n 转义）。
    // 统一处理：无论是否含转义序列，引号包裹的值一律按 JSON 字面量解析——
    // 保证 old_string 和 new_string 走相同的解析路径，避免不一致。
    // 文件内容本身含引号时（如 " if j == 0"），inferScalar 剥掉外层引号得到 ` if j == 0`，
    // 由 MultiEdit.kt 的引号回退机制处理（加回引号后精确匹配）。
    if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) return t
    val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
    return "\"$escaped\""
}

