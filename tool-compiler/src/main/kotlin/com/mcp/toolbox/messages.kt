@file:JvmName("ToolMessagesCore")
package com.mcp.toolbox

import com.mcp.serialization.McpJson
import kotlinx.serialization.json.*

/**
 * OpenAI function calling 「第 4 步：把工具结果回传给模型」的消息构造与解析——全局单一真源。
 *
 * 协议回顾（与 [ToolCompiler.toOpenAi] 产出的 `tools` 定义配套）：
 *
 * 1. 客户端发送 `messages` + `tools`（工具定义，见 [ToolCompiler.toOpenAi]）
 * 2. 模型返回 assistant 消息：`content: null` + `tool_calls: [{id, type:"function", function:{name, arguments}}]`
 * 3. 客户端按 `name`/`arguments` 本地执行函数，拿到原始返回值
 * 4. **客户端把结果作为 `role:"tool"` 消息追加进 messages** ← 本文件负责的环节
 * 5. 模型据此生成最终自然语言回复（或再次发起 `tool_calls`，进入多轮）
 *
 * 第 4 步的标准形状：
 * ```json
 * {"role":"tool","tool_call_id":"call_1","content":"<函数返回的原文>"}
 * ```
 * - `tool_call_id` 必须与第 2 步 `tool_calls[i].id` 一一对应，模型靠它把结果关联回具体调用
 * - `content` 是函数返回的原文（JSON 文本或纯文本），**原样透传**，不加任何信封/前缀/标注
 * - 并行调用时，有几个 `tool_calls` 就回几条 tool 消息，且**顺序与 `tool_calls` 一致**
 */
object ToolMessages {

    /** function calling 中工具结果消息固定的 role。 */
    const val ROLE = "tool"

    /**
     * 构造单条工具结果消息（第 4 步的标准形状）。
     *
     * @param callId  对应 `tool_calls[i].id`
     * @param content 函数返回原文，原样作为 `content` 透传
     */
    fun result(callId: String, content: String): JsonObject = buildJsonObject {
        put("role", ROLE)
        put("tool_call_id", callId)
        put("content", content)
    }

    /** [result] 的字符串形式：`{"role":"tool","tool_call_id":...,"content":...}`。 */
    fun resultJson(callId: String, content: String): String =
        McpJson.encodeToString(JsonObject.serializer(), result(callId, content))

    // ───────────────────── 行式工具结果（零转义，DeepSeek 后端）─────────────────────

    /**
     * 行式协议的单条工具结果：`tool_result: <callId> <<<` + 原文 + `>>>`。
     * 原文零转义原样保留（含引号/反斜杠/换行），与行式调用协议配套。
     */
    fun lineResult(callId: String, content: String): String {
        // 行首 >>> 转义为 \>>> 防止提前终止定界块（反转义在 parseLineResults）
        val body = content.trimEnd('\n').replace(Regex("(?m)^>>>"), "\\\\>>>")
        return if (body.isEmpty()) "tool_result: $callId <<<\n>>>"
        else "tool_result: $callId <<<\n$body\n>>>"
    }

    /**
     * 行式协议的多条工具结果：按调用顺序逐条 `tool_result: <id> <<< ... >>>`。
     * DeepSeek 逆向后端续聊 prompt 专用（替代 [batchJson] 的 JSON 形状，零转义）。
     */
    fun lineResults(results: List<Pair<String, String>>): String =
        results.joinToString("\n") { (id, wrapped) -> lineResult(id, extractContent(wrapped)) }

    /**
     * 识别行式工具结果：`tool_result: <callId> <<<` 起始的段。
     * 用于历史回放 / 前端展示时从行式结果中还原 `(callId, content)` 列表；
     * 不是行式结果则返回 null。
     */
    fun parseLineResults(text: String): List<Pair<String, String>>? {
        val lines = text.lines()
        val result = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            val m = Regex("^tool_result\\s*:\\s*(\\S+)\\s*<<<\\s*$").find(t)
            if (m == null) { i++; continue }
            val callId = m.groupValues[1]
            val content = StringBuilder()
            i++
            while (i < lines.size && lines[i].trim() != ">>>") {
                val raw = lines[i]
                // \>>> 是 lineResult 对行首 >>> 的转义，还原为原始字符
                content.append(if (raw.startsWith("\\>>>")) raw.drop(1) else raw).append('\n')
                i++
            }
            if (i < lines.size) i++ // 跳过 >>>
            result.add(callId to content.toString().trimEnd('\n'))
        }
        return result.takeIf { it.isNotEmpty() }
    }

    // ───────────────────── XML 工具结果（零转义，极简模式）─────────────────────

    /**
     * XML 协议的单条工具结果：`<｜｜DSML｜｜ result name="callId">原文</｜｜DSML｜｜ result>`。
     *
     * 与 [lineResult] 对等：原文零转义原样保留。标签即边界，无需定界块，
     * 因此不必像行式那样转义行首 `>>>`。
     */
    fun xmlResult(callId: String, content: String): String {
        val body = content.trimEnd('\n')
        return "<" + XML_BAR2 + "DSML" + XML_BAR2 + " result name=\"" + callId + "\">" +
            body +
            "</" + XML_BAR2 + "DSML" + XML_BAR2 + " result>"
    }

    /** XML 协议的多条工具结果：按调用顺序逐条 result 段。 */
    fun xmlResults(results: List<Pair<String, String>>): String =
        results.joinToString("\n") { (id, wrapped) -> xmlResult(id, extractContent(wrapped)) }

    /**
     * 识别 XML 工具结果段，还原 `(callId, content)` 列表；不是则返回 null。
     */
    fun parseXmlResults(text: String): List<Pair<String, String>>? {
        if (!text.contains("DSML")) return null
        val re = Regex(
            "<" + XML_BAR_RE + "{2}\\s*DSML\\s*" + XML_BAR_RE + "{2}\\s*result\\b([^>]*)>" +
                "([\\s\\S]*?)" +
                "</?" + XML_BAR_RE + "{2}\\s*DSML\\s*" + XML_BAR_RE + "{2}\\s*result\\b[^>]*>?",
            RegexOption.IGNORE_CASE,
        )
        val nameRe = Regex("\\bname\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
        val out = mutableListOf<Pair<String, String>>()
        for (m in re.findAll(text)) {
            val name = nameRe.find(m.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
            if (name.isEmpty()) continue
            out.add(name to m.groupValues[2].trim('\n'))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** XML 标签里的竖线：全角 ｜（U+FF5C）。正则同时接受 ASCII | 以兼容转码差异。 */
    private const val XML_BAR_RE = "[|｜]"
    private const val XML_BAR2 = "｜｜"

    /**
     * 把一批工具结果编成可直接投递的 tool 消息载荷（用于 DeepSeek 逆向后端——
     * 它只接受单个 `prompt` 字符串，无法像 OpenAI 那样逐条追加 messages）。
     *
     * - 单条 → JSON 对象：`{"role":"tool","tool_call_id":"call_1","content":"..."}`
     * - 多条 → JSON 数组：`[{"role":"tool",...},{"role":"tool",...}]`（**保持入参顺序**，
     *   即与模型给出的 `tool_calls` 顺序一致，这是并行调用能正确归属的前提）
     *
     * @param results `callId` → 结果。第二项既可以是函数返回原文，也可以是 [resultJson] /
     *   历史 JSON-RPC 信封——统一经 [extractContent] 归一化为原文，避免二次包装。
     */
    fun batchJson(results: List<Pair<String, String>>): String {
        if (results.isEmpty()) return ""
        val items = results.map { (id, wrapped) -> result(id, extractContent(wrapped)) }
        return if (items.size == 1) {
            McpJson.encodeToString(JsonObject.serializer(), items[0])
        } else {
            McpJson.encodeToString(JsonArray.serializer(), JsonArray(items))
        }
    }

    /**
     * [batchJson] 的逆运算：判断一段文本是否为工具结果回传，是则解析出 `(tool_call_id, content)` 列表。
     *
     * 用于历史回放——DeepSeek 逆向后端把工具结果当作 `prompt` 发出，服务端会将其存成一条
     * **USER** 消息；重新加载会话时必须认出它，才能渲染成工具卡片，而不是让整坨 JSON
     * 冒充「用户说的话」显示在气泡里。
     *
     * 接受 [batchJson] 的两种产物：
     *  - 单条对象：`{"role":"tool","tool_call_id":"call_1","content":"..."}`
     *  - 并行数组：`[{"role":"tool",...},{"role":"tool",...}]`
     *
     * 判定从严：数组要求**每个**元素都是合法 tool 消息，只要混入一个非 tool 元素就整体判否，
     * 避免把用户手打的普通 JSON 误判成工具结果而吞掉其气泡。
     *
     * @return 有序的 `(callId, content)` 列表；不是工具结果回传则返回 null（注意与「空列表」区分）
     */
    fun parseResults(text: String): List<Pair<String, String>>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // 行式工具结果（DeepSeek）：tool_result: <id> <<< ... >>>，直接还原
        parseLineResults(trimmed)?.let { return it }
        // XML 工具结果（极简模式）：<｜｜DSML｜｜ result name="id">原文</...>
        parseXmlResults(trimmed)?.let { return it }
        // 快速否决：不以 { 或 [ 开头的绝不可能是 JSON tool 消息，避免对长正文做无谓的 JSON 解析
        if (trimmed[0] != '{' && trimmed[0] != '[') return null

        val root = runCatching { McpJson.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return when (root) {
            is JsonObject -> root.asToolResult()?.let { listOf(it) }
            is JsonArray -> {
                if (root.isEmpty()) return null
                val items = root.map { (it as? JsonObject)?.asToolResult() ?: return null }
                items
            }
            else -> null
        }
    }

    /** 单个 JSON 对象 → `(tool_call_id, content)`；不是 tool 消息则 null。 */
    private fun JsonObject.asToolResult(): Pair<String, String>? {
        if (str("role") != ROLE) return null
        val id = str("tool_call_id")?.takeIf { it.isNotEmpty() } ?: return null
        return id to (this["content"]?.asText() ?: "")
    }

    /**
     * 从工具结果封装中取回「函数返回原文」，兼容三种形状：
     *  - function calling 的 tool 消息：`{"role":"tool","tool_call_id":...,"content":...}`
     *  - 历史 JSON-RPC 2.0 信封：`{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":...}]}}`
     *    / `{"error":{"message":...}}`
     *  - 其它（含非 JSON 纯文本）：原样返回
     *
     * 幂等：对已经是原文的输入再调用一次仍得到原文，因此 [batchJson] 可安全地重复归一化。
     */
    fun extractContent(wrapped: String): String {
        if (wrapped.isBlank()) return ""
        // 行式工具结果（DeepSeek）：tool_result: <id> <<< ... >>>，直接取块内原文
        parseLineResults(wrapped)?.let { list ->
            if (list.size == 1) return list[0].second
        }
        // XML 工具结果（极简模式）：直接取标签之间原文
        parseXmlResults(wrapped)?.let { list ->
            if (list.size == 1) return list[0].second
        }
        val obj = runCatching { McpJson.parseToJsonElement(wrapped) as? JsonObject }.getOrNull() ?: return wrapped

        // 新形状：function calling 的 tool 消息
        if (obj.str("role") == ROLE) return obj["content"]?.asText() ?: ""

        // 旧形状：JSON-RPC 2.0 成功信封
        (obj["result"] as? JsonObject)?.let { r ->
            val first = (r["content"] as? JsonArray)?.firstOrNull() as? JsonObject
            first?.str("text")?.let { return it }
        }
        // 旧形状：JSON-RPC 2.0 错误信封
        (obj["error"] as? JsonObject)?.str("message")?.let { return it }

        return wrapped
    }

    /** 取字符串字段；JsonNull / 非原语一律视为缺失，避免把 null 读成 "null"。 */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /**
     * 把任意 JSON 值还原成「文本」：
     * 原语取其字面内容（字符串不带引号、数字/布尔取字面量），null 视为空串，
     * 对象/数组则序列化回紧凑 JSON——保证任何形状的 content 都不会在提取时丢失。
     */
    private fun JsonElement.asText(): String = when {
        this is JsonNull -> ""
        this is JsonPrimitive -> content
        else -> McpJson.encodeToString(JsonElement.serializer(), this)
    }
}
