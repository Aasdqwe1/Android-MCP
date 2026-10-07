package com.mcp.toolbox

/**
 * 行式协议「文本级解析器」——从模型/站点的整段回复里提取 tool_call。
 *
 * 原在 `com.mcp.deepseek.DeepSeekApi`（DeepSeek 逆向专用），但 **Web 自动化后端也复用它**
 * 解析站点回复里的 tool_call 行（见 WebAutomationClient）。DeepSeek 后端移除后，
 * 本解析器提取到 tool-compiler，作为「文本工具调用协议」的通用实现。
 *
 * 值到行尾即止，无 JSON 转义负担；多行值用 `<<< >>>` 定界块。
 */
fun parseToolCallFromText(text: String): List<Triple<String, String, String>> {
    // 行式协议解析可能抛 ToolArgsParseException（如多行参数未用定界块）。
    // 捕获后转成 ("", "__parse_error__", argumentsJson) 的特殊调用，由上层将其作为
    // 错误结果回传模型——而不是抛出异常把整条响应打挂。
    return try {
        // 两套协议并存：XML 优先（极简模式的提示词引导模型用 XML），
        // 未命中则回退行式协议（其他模式照旧）。两套都认，不强制其一。
        val xml = parseToolCallXmlProtocol(text)
        val calls = if (xml.isNotEmpty()) xml else parseToolCallLineProtocol(repairLineEnvelopes(text))
        calls.map { Triple(it.callId, it.name, it.argumentsJson) }
    } catch (e: ToolArgsParseException) {
        val msg = e.message ?: "工具参数解析失败"
        // 复用 errorResult 做正确转义，避免 message 含引号/换行时产出非法 JSON
        listOf(Triple("", "__parse_error__", errorResult(msg)))
    }
}

/**
 * 行式信封预处理：把文本按 `tool_call:` 起点切成「信封段 / 其它段」，
 * 对每个信封段跑 [ToolCallGuard.repairEnvelope] 规范化后再重组。
 *
 * 为什么需要：模型常吐出畸形信封——孤儿 `>>>`、元数据错位/重复、id 写到 code 之后等。
 * 现有 [parseToolCallLineProtocol] 对部分畸形已有逐行容错，但**整段重写式**的规范化
 * （元数据归位、统一 code 块形态）能让后续解析更稳。两者互补：本函数做第一道粗修，
 * 逐行解析器做第二道精解析。
 *
 * 安全阀：repairEnvelope 只认 [ToolCallGuard] 的 KNOWN_META 白名单（run_code 的
 * 元数据），对**任意参数名**的通用工具（如 pattern/path/command）会丢失参数。因此：
 *  - 只对**含 code <<< 定界块**的信封应用 repair（那是 run_code 形态，白名单覆盖）；
 *  - 其余信封原样保留，交给逐行解析器按通用规则处理。
 */
private fun repairLineEnvelopes(text: String): String {
    if (text.isEmpty() || !text.contains("tool_call:")) return text
    val lines = text.lines()
    val headRe = Regex("^\\s*`{0,3}(?:[a-zA-Z]+\\s+)?tool_call\\s*:")
    val starts = lines.indices.filter { headRe.containsMatchIn(lines[it]) }
    if (starts.isEmpty()) return text
    val sb = StringBuilder(text.length)
    var cursor = 0
    for ((k, start) in starts.withIndex()) {
        // 该信封段的结束 = 下一个 tool_call 起点（或文末）
        val endExclusive = if (k + 1 < starts.size) starts[k + 1] else lines.size
        // 起点之前的内容原样保留
        for (j in cursor until start) sb.append(lines[j]).append('\n')
        val seg = lines.subList(start, endExclusive).joinToString("\n")
        // 仅对 run_code 形态（含 code <<< 定界块）应用整段重写，避免丢失通用工具参数
        val hasCodeBlock = seg.lines().any { Regex("^\\s*code\\s*<<<\\s*$").matches(it) }
        val repaired = if (hasCodeBlock) {
            runCatching { ToolCallGuard.repairEnvelope(seg) }
                .getOrElse { seg }
        } else seg
        sb.append(repaired)
        if (!repaired.endsWith("\n")) sb.append('\n')
        cursor = endExclusive
    }
    for (j in cursor until lines.size) sb.append(lines[j]).append('\n')
    return sb.toString()
}