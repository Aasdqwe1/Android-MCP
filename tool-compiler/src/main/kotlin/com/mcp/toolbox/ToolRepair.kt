package com.mcp.toolbox

/**
 * 「聚焦修复」（focused repair）：工具调用被拒绝时回给模型的**最小**纠错信息。
 *
 * 对齐 universal-web-api 的 focused_repair 策略（`tool_calling_validation_retry.py`）：
 * 不重发整份工具 schema / 整段会话，只回「哪个字段错了 + 该字段的正确写法 + 一条修正指令」，
 * 让模型下一轮只改这一处。原实现只回一句
 * `invalid arguments for edit_file: $.old_string: 缺少必填参数 "old_string"`——
 * 模型不知道参数类型、是否多行、怎么补，往往要连续试错两三轮才修好。
 *
 * 这里只做**渲染**：调用方（[Toolbox.dispatch] / [Toolbox.dispatchOrThrow]）负责把文本
 * 作为工具结果回传。PTC 程序内调用拿到的是同一条文本（包在 `ToolCallError.message` 里）。
 */
object ToolRepair {

    /** 校验器错误串里的「缺少必填参数 "x"」。 */
    private val MISSING_RE = Regex("缺少必填参数 \"([^\"]+)\"")
    /** 校验器错误串里的「不允许多余参数 "x"」。 */
    private val EXTRA_RE = Regex("不允许多余参数 \"([^\"]+)\"")
    /** 校验器错误串的 JSON 路径前缀，如 `$.options.maxResults: …`。 */
    private val PATH_RE = Regex("^\\$\\.([A-Za-z0-9_]+)")
    /** 可用工具清单的展示上限（避免几十个工具名把纠错信息本身撑成上下文负担）。 */
    private const val MAX_LISTED_TOOLS = 24

    /**
     * 参数校验失败（[JsonSchemaSubset.validate] 返回非空）时的聚焦修复文本。
     *
     * @param problems 校验器的全部错误串（原样回显，保证「缺少必填参数」等关键字可被日志/测试检索）。
     */
    fun invalidArguments(toolName: String, tool: ToolDef, problems: List<String>): String {
        val fields = problems.mapNotNull { offendingField(it) }.distinct()
        return buildString {
            append("[工具调用修复] invalid arguments for ").append(toolName).append('\n')
            append("错误：\n")
            problems.forEach { append("- ").append(it).append('\n') }
            if (fields.isNotEmpty()) {
                append("相关参数：\n")
                fields.forEach { field ->
                    append("- ").append(field)
                    val spec = tool.parameters[field.substringBefore('.')]
                    if (spec == null) {
                        append("（该工具没有这个参数，请只使用签名里列出的参数名）")
                    } else {
                        append(" (").append(spec.type.jsonName)
                        append(if (spec.required) "，必填" else "，可选")
                        if (spec.multiLine) {
                            append("，多行值：必须用 ").append(spec.name).append(" <<< … >>> 定界块包裹")
                        }
                        append(')')
                        if (spec.description.isNotBlank()) {
                            append(" — ").append(spec.description.replace('\n', ' ').trim().take(120))
                        }
                        if (spec.enumValues.isNotEmpty()) {
                            append("；可选值：").append(spec.enumValues.joinToString("/"))
                        }
                    }
                    append('\n')
                }
            }
            append("正确签名：").append(signature(tool)).append('\n')
            append("请只修正上述问题后重新调用 ").append(toolName)
                .append("：本轮不要输出其它内容，不要改用别的工具。")
        }
    }

    /** 工具名不在清单中：给出最接近的候选与可用清单，避免模型反复编造名字。 */
    fun unknownTool(name: String, available: Collection<String>): String {
        val candidates = available.sorted()
        val suggestions = candidates
            .map { it to distance(it.lowercase(), name.lowercase()) }
            .filter { (it, d) -> d <= 2 || it.contains(name, ignoreCase = true) || name.contains(it, ignoreCase = true) }
            .sortedBy { it.second }
            .map { it.first }
            .take(5)
        return buildString {
            append("[工具调用修复] unknown tool: ").append(name).append('\n')
            if (suggestions.isNotEmpty()) {
                append("最接近的候选：").append(suggestions.joinToString(", ")).append('\n')
            }
            append("当前可用工具（共 ").append(candidates.size).append(" 个）：")
            append(candidates.take(MAX_LISTED_TOOLS).joinToString(", "))
            if (candidates.size > MAX_LISTED_TOOLS) append(" …")
            append('\n')
            append("请改用清单中的准确工具名重新调用；不要编造工具名，也不要调用清单之外的工具。")
        }
    }

    /** 参数文本无法解析（[ToolArgsParseException]）：回显原因 + 正确签名 + 格式要求。 */
    fun parseError(toolName: String, reason: String, tool: ToolDef? = null): String = buildString {
        append("[工具调用修复] 工具 ").append(toolName).append(" 的参数无法解析\n")
        append("原因：").append(reason.replace('\n', ' ').trim()).append('\n')
        if (tool != null) append("正确签名：").append(signature(tool)).append('\n')
        append("请按上述格式重新调用：多行参数必须用「参数名 <<< 单独一行 … >>> 单独一行」的定界块包裹，")
        append("单行参数写成「参数名: 值」（值到行尾为止，不加引号）。")
    }

    /**
     * 单行签名：`edit_file(path: string, old_string: string!, new_string: string!, replace_all?: boolean)`。
     * 必填参数后加 `!`，多行参数标注定界块写法——一眼能看出该补什么。
     */
    fun signature(tool: ToolDef): String = buildString {
        append(tool.name).append('(')
        var first = true
        for ((k, p) in tool.parameters) {
            if (!first) append(", ")
            first = false
            append(p.name.ifBlank { k }).append(": ").append(p.type.jsonName)
            if (p.required) append('!')
            if (p.multiLine) append("（<<< … >>>）")
        }
        append(')')
    }

    /** 从校验器错误串里取出出错的字段名（支持嵌套路径，返回顶层字段名）。 */
    private fun offendingField(problem: String): String? {
        MISSING_RE.find(problem)?.let { return it.groupValues[1] }
        EXTRA_RE.find(problem)?.let { return it.groupValues[1] }
        PATH_RE.find(problem)?.let { return it.groupValues[1] }
        return null
    }

    /** 小规模 Levenshtein（工具名最长几十字符，直接两行滚动数组）。 */
    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}
