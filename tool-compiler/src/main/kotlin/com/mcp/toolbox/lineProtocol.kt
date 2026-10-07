package com.mcp.toolbox

/**
 * 行式协议（DeepSeek 逆向）的「调用段剥离」与「围栏屏蔽」。
 *
 * 背景（用户报告的两个问题）：
 *  1. 模型输出的 `tool_call:` 段是**识别用的原始文本**，不应出现在气泡里；
 *  2. 提示词/解释里的**示例**调用会被当真执行，必须能区分。
 *
 * 区分规则（与系统提示词的协议一致）：
 *  - `//` 前缀行 = 示例/说明，不执行、也不从气泡剥离（模型给用户看的解释）；
 *  - 代码围栏（``` / ~~~）内的内容**一律**视为示例/演示：不执行，也不从气泡剥离。
 *    对齐 universal-web-api 的做法（解析前把围栏整段置空）——语义标记（示例/例如…）
 *    依赖模型措辞，漏标就会把演示当调用执行；围栏是模型「贴代码」的强信号，更稳。
 *  - 历史上曾保留「整条消息被一层围栏完整包裹时照常解析」的兼容例外；现已移除——
 *    因为该例外会让「被围栏包着的调用声明」在 UI 上原样显示并执行，用户视觉上无法区分
 *    示例和真调用。现统一：只要落在围栏内就是示例，无论围栏包裹多少内容。
 *  - 其余 `tool_call:` 段 = 真调用：执行，并从展示文本中剥离。
 */

/** 围栏行（``` 或 ~~~，可带语言标记；独占一行）。 */
private val FENCE_LINE = Regex("^\\s*(`{3,}|~{3,})\\s*[a-zA-Z0-9_+-]*\\s*$")

/**
 * 围栏块覆盖的行号区间（闭区间，**含首尾围栏行**）。
 * 未闭合的围栏延伸到文本末尾（模型输出被截断时按示例处理，宁可少执行不误执行）。
 */
internal fun fenceRanges(lines: List<String>): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var i = 0
    while (i < lines.size) {
        if (!FENCE_LINE.matches(lines[i])) { i++; continue }
        var j = i + 1
        while (j < lines.size && !FENCE_LINE.matches(lines[j])) j++
        val end = if (j < lines.size) j else lines.size - 1
        ranges.add(i..end)
        i = end + 1
    }
    return ranges
}

/** 行号是否落在任一围栏块内。 */
internal fun isFencedLine(index: Int, ranges: List<IntRange>): Boolean =
    ranges.any { index in it }

/** 行式调用起始行（可带 ``` 前缀）。 */
private val CALL_LINE_RE = Regex("(?m)^`{0,3}(?:[a-zA-Z]+\\s+)?tool_call\\s*:")

/** 参数行：`key: value` 或 `key <<<`。 */
private val PARAM_LINE_RE = Regex("^\\S+\\s*:\\s*.*$")
private val BLOCK_START_RE = Regex("^\\S+\\s*<<<\\s*$")

/**
 * 从展示文本中剥离**真调用**的行式段。
 * 围栏内的段落原样保留（那是模型给用户看的示例/演示），其余段落走 [stripCallsInText]。
 */
fun stripLineProtocolCalls(text: String): String {
    if (text.isEmpty()) return text
    val lines = text.lines()
    val ranges = fenceRanges(lines)
    if (ranges.isEmpty()) return stripCallsInText(text)
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < lines.size) {
        val fenced = isFencedLine(i, ranges)
        var j = i
        while (j < lines.size && isFencedLine(j, ranges) == fenced) j++
        val segment = lines.subList(i, j).joinToString("\n")
        sb.append(if (fenced) segment else stripCallsInText(segment))
        if (j < lines.size) sb.append('\n')
        i = j
    }
    return sb.toString()
}

/**
 * 在**无围栏**的文本片段里剥离真调用段。
 * 段尾规则（修复「最后一个调用会吞掉其后正文」的缺陷）：
 *  1. 调用块在围栏内 → 吃到闭合围栏行尾，围栏外的正文保留；
 *  2. 否则吃到「调用段」结束（参数行/定界块/空行// 注释），保留其后正文；
 *  3. 多个连续调用块各自剥离。
 * 不剥离 `//tool_call:`（示例行，模型给用户看的说明）。
 */
private fun stripCallsInText(text: String): String {
    var result = text
    while (true) {
        val tcM = CALL_LINE_RE.find(result) ?: break
        val tcPos = tcM.range.first
        val lineStart = result.lastIndexOf('\n', tcPos) + 1
        val fenceStart = result.lastIndexOf("```", tcPos)
        val cutStart = if (fenceStart >= 0 && result.substring(fenceStart, tcPos).indexOf('\n') < 0)
            fenceStart else lineStart
        val nextM = CALL_LINE_RE.find(result.substring(tcPos + 1))
        val nextPos = if (nextM != null) tcPos + 1 + nextM.range.first else -1
        val closeFence = result.indexOf("```", tcPos)
        val cutEnd = when {
            // 闭合围栏在本段内（且不越过下一个调用）→ 吃到围栏行尾
            closeFence >= 0 && (nextPos < 0 || closeFence < nextPos) ->
                result.indexOf('\n', closeFence).let { if (it >= 0) it + 1 else result.length }
            // 有下一个调用 → 只吃到本调用段结束（保留两段之间的正文）
            nextPos >= 0 -> minOf(endOfCallSegment(result, tcPos), nextPos)
            // 最后一段 → 吃到调用段结束，保留其后正文
            else -> endOfCallSegment(result, tcPos)
        }
        // 只裁掉调用段前后各自残留的整行空白，不整段 trim()——
        // 反复 trim() 会削掉正文首尾的缩进/空行（与 xmlProtocol.stripXmlInText 同规则）。
        result = trimBlankLines(result.substring(0, cutStart) + result.substring(cutEnd))
    }
    return result
}

/**
 * 从调用起始行之后开始扫描「调用段」：参数行 / `key <<<` 定界块 / 空行 / // 注释；
 * 遇到正文行或下一个 tool_call 行停止，返回该位置。
 */
private fun endOfCallSegment(text: String, callStart: Int): Int {
    val firstNl = text.indexOf('\n', callStart)
    if (firstNl < 0) return text.length
    var i = firstNl + 1
    var inBlock = false
    while (i < text.length) {
        val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
        val t = text.substring(i, lineEnd).trim()
        if (inBlock) {
            if (t == ">>>") inBlock = false
            i = if (lineEnd < text.length) lineEnd + 1 else text.length
            continue
        }
        if (CALL_LINE_RE.containsMatchIn(t)) break
        if (BLOCK_START_RE.matches(t)) { inBlock = true; i = if (lineEnd < text.length) lineEnd + 1 else text.length; continue }
        if (PARAM_LINE_RE.matches(t) || t.isEmpty() || t.startsWith("//")) {
            i = if (lineEnd < text.length) lineEnd + 1 else text.length
            continue
        }
        break
    }
    return i
}
