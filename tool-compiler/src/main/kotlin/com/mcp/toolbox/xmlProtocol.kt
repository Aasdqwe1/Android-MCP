package com.mcp.toolbox

/**
 * XML 工具调用协议的解析与剥离（与 [parseToolCallLineProtocol] / [stripLineProtocolCalls] 对等）。
 *
 * 为什么需要第二套：极简模式（preset `minimal`）的提示词改用 XML 引导，
 * 但解析层要**两套都认**——行式协议的其他模式继续照常工作，互不影响。
 *
 * 协议形态（模型输出，标签名里的 `｜` 是全角竖线 U+FF5C，两侧各一）：
 * ```
 * <｜｜DSML｜｜ invoke name="read_file">
 * <｜｜DSML｜｜ parameter name="id" string="true">call_1</｜｜DSML｜｜ parameter>
 * <｜｜DSML｜｜ parameter name="path" string="true">/sdcard/x.txt</｜｜DSML｜｜ parameter>
 * </｜｜DSML｜｜ invoke>
 * ```
 *
 * 语义约定（刻意与行式协议对齐，降低两套并存的认知成本）：
 *  - 一个 `<... invoke name="X">` 段 = 一次工具调用；`name` 即工具名；
 *  - 段内每个 `<... parameter name="K" ...>值</... parameter>` = 一个参数；
 *  - `name="id"` 的参数视作 callId（与行式 `id:` 同义），不进入 arguments；
 *  - 值的类型：`string="true"` 声明为字符串，按原样取；否则走 [inferScalarXml]
 *    （布尔/数字原样，其余按 JSON 字符串），与行式 [inferScalar] 口径一致；
 *  - 多行值原样保留（XML 内不需要定界块——标签本身就是边界），
 *    仅做「行尾统一 LF」与「去掉标签外的首尾空行」；
 *  - 围栏（``` / ~~~）内一律视为示例：不解析、不剥离（与行式同规则）；
 *  - `//` 前缀行视为示例（与行式同规则）。
 *
 * 容错：模型常把闭合标签写坏（`<｜｜DSML｜｜ parameter>` 缺 `string` 属性、
 * 或闭合写成 `<｜｜DSML｜｜ parameter`）。解析器按「尽力而为」处理：
 * 属性缺失不影响取值，闭合标签宽容匹配（见 XML_PARAM_CLOSE_RE）。
 *
 * @return 按出现顺序的工具调用列表；无则返回空列表（由上层回退其他解析路径）。
 */

/**
 * XML 协议的标签名前缀/后缀（`<｜｜DSML｜｜` 与 `｜｜>`）。
 *
 * 注意全角竖线 U+FF5C（｜）而非 ASCII 竖线（|）——模型输出用的是前者，
 * 但为兼容手写/转码差异，正则里两种都接受。
 */
private const val BAR = "[|｜]"

/** 开标签前缀：`<` + 两个竖线 + `DSML` + 两个竖线。 */
private val XML_OPEN_PREFIX = Regex("<$BAR{2}\\s*DSML\\s*$BAR{2}")

/** `invoke` 起始标签：`<｜｜DSML｜｜ invoke name="工具名">`。 */
private val XML_INVOKE_RE = Regex(
    "<$BAR{2}\\s*DSML\\s*$BAR{2}\\s*invoke\\b([^>]*)>",
    RegexOption.IGNORE_CASE,
)

/** `invoke` 结束标签：`</｜｜DSML｜｜ invoke>`（宽容：允许属性/空白残留）。 */
private val XML_INVOKE_CLOSE_RE = Regex(
    "</?$BAR{2}\\s*DSML\\s*$BAR{2}\\s*invoke\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)

/** `parameter` 起始标签：`<｜｜DSML｜｜ parameter name="K" string="true">`。 */
private val XML_PARAM_RE = Regex(
    "<$BAR{2}\\s*DSML\\s*$BAR{2}\\s*parameter\\b([^>]*)>",
    RegexOption.IGNORE_CASE,
)

/**
 * `parameter` 结束标签（宽容）。
 *
 * 实测模型写法多变：`</｜｜DSML｜｜ parameter>`、`</｜｜DSML｜｜parameter>`、
 * 甚至把 `>` 吃掉写成 `</｜｜DSML｜｜ parameter`。这里统一用「斜线可选 +
 * 前缀 + parameter + 可选 `>`」匹配，避免因闭合写法差异整段解析失败。
 */
private val XML_PARAM_CLOSE_RE = Regex(
    "</?$BAR{2}\\s*DSML\\s*$BAR{2}\\s*parameter\\b[^>]*>?",
    RegexOption.IGNORE_CASE,
)

/** 任意 DSML 标签（用于剥离时定位整段）。 */
private val XML_ANY_TAG_RE = Regex(
    "</?$BAR{2}\\s*DSML\\s*$BAR{2}\\s*(invoke|parameter)\\b[^>]*>?",
    RegexOption.IGNORE_CASE,
)

/** 从属性串里取 `name="值"`（单双引号都接受）。 */
private val ATTR_NAME_RE = Regex("""\bname\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

/** 从属性串里取 `string="true|false"`。 */
private val ATTR_STRING_RE = Regex("""\bstring\s*=\s*["']?\s*(true|false)\s*["']?""", RegexOption.IGNORE_CASE)

/**
 * 解析 XML 协议的工具调用。
 *
 * 只解析**围栏外、非 `//` 前缀**的内容（与行式协议同规则：围栏内是示例）。
 */
fun parseToolCallXmlProtocol(text: String): List<ToolCallEnvelope> {
    if (text.isEmpty()) return emptyList()
    val lines = text.lines()
    val fenced = fenceRanges(lines)
    // 围栏内不解析：把围栏内的行整体替换成空行（保留行号，便于下面按原文定位）
    val effective = buildString {
        lines.forEachIndexed { idx, line ->
            if (isFencedLine(idx, fenced) || line.trimStart().startsWith("//")) append('\n')
            else append(line).append('\n')
        }
    }

    val result = mutableListOf<ToolCallEnvelope>()
    var searchFrom = 0
    while (true) {
        val invoke = XML_INVOKE_RE.find(effective, searchFrom) ?: break
        val attrs = invoke.groupValues[1]
        val name = ATTR_NAME_RE.find(attrs)?.groupValues?.get(1)?.trim().orEmpty()
        // 段体：invoke 开标签之后，到 invoke 闭合标签（或文本末尾）之前
        val bodyStart = invoke.range.last + 1
        val close = XML_INVOKE_CLOSE_RE.find(effective, bodyStart)
        val bodyEnd = close?.range?.first ?: effective.length
        val body = effective.substring(bodyStart, bodyEnd)
        searchFrom = if (close != null) close.range.last + 1 else effective.length

        if (name.isEmpty()) continue   // 无工具名的 invoke 段忽略（多半是残片）

        val args = LinkedHashMap<String, String>()
        // 本段内声明了 string="true" 的参数名：强制按字符串输出（局部，线程安全）
        val stringFlags = HashSet<String>()
        var callId = ""
        val seen = HashSet<String>()
        var p = 0
        while (true) {
            val open = XML_PARAM_RE.find(body, p) ?: break
            val pAttrs = open.groupValues[1]
            val key = ATTR_NAME_RE.find(pAttrs)?.groupValues?.get(1)?.trim().orEmpty()
            val isString = ATTR_STRING_RE.find(pAttrs)
                ?.groupValues?.get(1)?.equals("true", ignoreCase = true) == true
            val valStart = open.range.last + 1
            val valClose = XML_PARAM_CLOSE_RE.find(body, valStart)
            val rawValue = body.substring(valStart, valClose?.range?.first ?: body.length)
            p = if (valClose != null) valClose.range.last + 1 else body.length
            if (key.isEmpty()) continue

            // 值处理：统一 LF；只剥「紧邻标签的首尾换行/空白」——标签本身即边界，
            // 不做整段 trim()，否则多行值（如 content）的内部首尾空行会被静默吃掉。
            // 单行值（无内部换行）额外去掉首尾空白，与行式协议口径一致；
            // 多行值保留内部结构（含尾部空行）。不做 XML 实体反转义（协议约定原文直出）。
            val value = rawValue
                .replace("\r\n", "\n").replace('\r', '\n')
                .let { stripOneBoundaryNewline(it) }
                .let { if (it.contains('\n')) it else it.trim() }
            if (key == "id" && callId.isEmpty()) { callId = value; continue }
            if (!seen.add(key)) {
                throw ToolArgsParseException(
                    "参数 '$key' 在同一个 invoke 里重复出现。每个参数只写一次，请删除多余的那段后重新调用。"
                )
            }
            args[key] = value
            if (isString) stringFlags.add(key)
        }

        if (args.isEmpty() && callId.isEmpty()) continue
        val argsJson = buildArgsJson(args, stringFlags)
        result.add(ToolCallEnvelope(callId, name, argsJson))
    }
    return result
}



/**
 * 构造 argumentsJson。
 *
 * @param stringFlags 声明了 `string="true"` 的参数名——这些**强制按字符串**输出，
 *   不做标量推断（模型显式声明的类型优先）。
 */
private fun buildArgsJson(args: LinkedHashMap<String, String>, stringFlags: Set<String>): String {
    val sb = StringBuilder("{")
    var first = true
    for ((k, v) in args) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(escapeJsonKeyXml(k)).append("\":")
        sb.append(if (k in stringFlags) jsonString(v) else inferScalarXml(v))
    }
    sb.append('}')
    return sb.toString()
}

/** XML 协议：键做最小 JSON 转义。 */
private fun escapeJsonKeyXml(k: String): String =
    k.replace("\\", "\\\\").replace("\"", "\\\"")

/** XML 协议：把任意文本编成 JSON 字符串字面量。 */
private fun jsonString(v: String): String {
    val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
    return "\"$escaped\""
}

/**
 * XML 协议：标量类型推断（与行式 [inferScalar] 口径一致）。
 *
 * 布尔/整数/小数原样输出；引号包裹的值按 JSON 字面量；其余按字符串自动转义。
 */
private fun inferScalarXml(v: String): String {
    val t = v.trim()
    if (t == "true") return "true"
    if (t == "false") return "false"
    if (t.isNotEmpty() && Regex("^-?\\d+$").matches(t)) return t
    if (t.isNotEmpty() && Regex("^-?\\d+\\.\\d+$").matches(t)) return t
    if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) return t
    // 数组/对象字面量：模型按工具清单提示写成 JSON 原文，直接原样透传（不包成字符串）
    if (t.length >= 2 && ((t.startsWith('[') && t.endsWith(']')) || (t.startsWith('{') && t.endsWith('}')))) {
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(t)
        }.fold({ t }, { jsonString(v) })
    }
    return jsonString(v)
}

/**
 * 从展示文本中剥离 XML 协议的工具调用段。
 *
 * 与 [stripLineProtocolCalls] 同规则：围栏内原样保留（示例），围栏外整段剥离。
 */
fun stripXmlProtocolCalls(text: String): String {
    if (text.isEmpty()) return text
    val lines = text.lines()
    val ranges = fenceRanges(lines)
    if (ranges.isEmpty()) return stripXmlInText(text)
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < lines.size) {
        val fenced = isFencedLine(i, ranges)
        var j = i
        while (j < lines.size && isFencedLine(j, ranges) == fenced) j++
        val segment = lines.subList(i, j).joinToString("\n")
        sb.append(if (fenced) segment else stripXmlInText(segment))
        if (j < lines.size) sb.append('\n')
        i = j
    }
    return sb.toString()
}

/**
 * 只剥掉值「紧邻标签边界」的一个换行：开标签后若有换行、闭合标签前若有换行，各剥一个。
 *
 * 标签之间的换行属于排版（模型习惯把值另起一行写），不是内容；但**内容自身的**
 * 尾部空行必须保留（如 content 以空行结尾）。用 trim('\n') 会把两者一起吃掉，
 * 这里只精确剥边界那一个。
 */
private fun stripOneBoundaryNewline(v: String): String {
    var s = v
    if (s.startsWith("\n")) s = s.substring(1)
    if (s.endsWith("\n")) s = s.substring(0, s.length - 1)
    return s
}

/**
 * 只裁掉文本首尾的**整行空白**（空行 / 仅空白字符的行），保留行内缩进与行尾空格。
 *
 * 用于剥离调用段后的正文清理：不能用 String.trim()——那会削掉正文本身的缩进，
 * 例如列表项、代码块的起始空格。
 */
internal fun trimBlankLines(text: String): String {
    val lines = text.lines()
    var start = 0
    while (start < lines.size && lines[start].isBlank()) start++
    var end = lines.size
    while (end > start && lines[end - 1].isBlank()) end--
    return lines.subList(start, end).joinToString("\n")
}

/** 在无围栏片段里剥离 XML 调用段：从 invoke 开标签吃到对应闭合标签。 */
private fun stripXmlInText(text: String): String {
    if (!XML_OPEN_PREFIX.containsMatchIn(text)) return text
    var result = text
    while (true) {
        val invoke = XML_INVOKE_RE.find(result) ?: break
        val lineStart = result.lastIndexOf('\n', invoke.range.first) + 1
        val close = XML_INVOKE_CLOSE_RE.find(result, invoke.range.last + 1)
        val cutEnd = if (close != null) close.range.last + 1 else result.length
        // 只裁掉调用段前后各自残留的整行空白，不整段 trim()——
        // 反复 trim() 会削掉正文首尾的缩进/空行（列表项、代码块等）。
        result = trimBlankLines(result.substring(0, lineStart) + result.substring(cutEnd))
    }
    // 残留的孤立 DSML 标签（如模型写坏的闭合行）也一并清掉，避免泄漏到气泡
    result = XML_ANY_TAG_RE.replace(result, "")
    return trimBlankLines(result)
}

/** 文本里是否含 XML 协议的工具调用（供上层决定走哪套解析器）。 */
fun containsXmlProtocolCalls(text: String): Boolean = XML_OPEN_PREFIX.containsMatchIn(text)