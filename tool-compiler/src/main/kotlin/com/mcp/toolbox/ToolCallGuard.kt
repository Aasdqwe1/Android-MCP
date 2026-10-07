package com.mcp.toolbox

/**
 * 工具调用信封守卫：把「畸形调用自愈 + 工具 id 重复分级门禁」做成可复用的纯逻辑，
 * 供 :app 的 CompatChatRunner / AgentOrchestrator 在处理 [com.mcp.llm.MessageEvent.ToolCall]
 * 时调用，取代原先散落各处的内联去重。
 *
 * 分级策略（固定，无外部开关）：
 *  - warning 级（可自愈、不致命）：信封畸形（孤儿 >>>、重复/错位元数据）、id 缺失
 *      -> 自愈后放行，可附一段给 LLM 的修正提示。
 *  - error 级（致命）：工具 id 重复、信封不可自愈（无 code 块）
 *      -> 拒绝放行，返回 Error 文本交由调用方回给 LLM。
 *
 * 文本约定：返回给 LLM 的内容零 emoji，沿用 [ToolRepair] 的聚焦修复风格。
 */
object ToolCallGuard {

    /** 允许出现的元数据键（用于区分「合法元数据」与「代码正文」）。 */
    private val KNOWN_META = setOf("description", "language", "timeout_seconds", "name", "model", "stream")

    private val CALL_HEAD = Regex("^\\s*tool_call\\s*:\\s*(.*)$")
    private val CODE_OPEN = Regex("^\\s*code\\s*<<<\\s*$")
    private val ID_LINE = Regex("^id\\s*:\\s*(\\S.*)$")
    private val META_LINE = Regex("^([A-Za-z_][\\w-]*)\\s*:\\s*(.*)$")
    private const val CLOSE = ">>>"

    /**
     * 一条调用门禁的结论。
     *
     * @param allow    是否允许放行（error 级问题时为 false）。
     * @param fixedId  id 缺失时补位的唯一 id；无需补位为 null。
     * @param warning  给 LLM 的修正提示（warning 级）；无则为 null。
     * @param error    给 LLM 的错误文本（error 级，拒绝时回传）；无则为 null。
     */
    data class IdVerdict(
        val allow: Boolean,
        val fixedId: String? = null,
        val warning: String? = null,
        val error: String? = null,
    )

    /** 该文本是否像一条 tool_call 信封（整段判定用）。 */
    fun looksLikeToolCall(text: String): Boolean = text.lines().any { CALL_HEAD.matches(it) }

    /**
     * 自愈一条 tool_call 信封：孤儿 `>>>` 删除、重复/错位元数据归一、缺 code 块标记。
     * 返回规范化后的文本。无 code 块等不可自愈情形走 best-effort 重建。
     */
    fun repairEnvelope(raw: String): String {
        val lines = raw.lines()
        var type: String? = null
        var id: String? = null
        var openerIdx = -1
        var firstClose = -1
        for (ln in lines) {
            CALL_HEAD.matchEntire(ln)?.let { type = it.groupValues[1].trim().ifBlank { null } }
            if (openerIdx < 0 && CODE_OPEN.matches(ln)) openerIdx = lines.indexOf(ln)
            if (firstClose < 0 && ln.trim() == CLOSE) firstClose = lines.indexOf(ln)
            // id 可能在 code 之前或之后，整段扫描一次（top-level 即可）
            ID_LINE.matchEntire(ln.trim())?.let { id = it.groupValues[1].trim().ifBlank { null } }
        }
        val code = if (openerIdx >= 0 && firstClose > openerIdx)
            lines.subList(openerIdx + 1, firstClose).joinToString("\n") else ""

        // 元数据：top-level（非缩进）的 key:value，去重；code 之前出现的视为错位，统一挪到 >>> 之后。
        val meta = LinkedHashMap<String, String>()
        fun collect(from: Int, to: Int) {
            for (j in from until to) {
                val ln = lines[j]
                if (ln.trim() == CLOSE || ln.startsWith(" ")) continue
                val m = META_LINE.matchEntire(ln.trim()) ?: continue
                val k = m.groupValues[1]
                val v = m.groupValues[2].trim()
                if (k in KNOWN_META) meta.putIfAbsent(k, v)
            }
        }
        if (openerIdx >= 0 && firstClose > openerIdx) collect(openerIdx + 1, firstClose)
        if (firstClose >= 0) collect(firstClose + 1, lines.size)

        return buildString {
            appendLine("tool_call: ${type ?: "run_code"}")
            if (id != null) appendLine("id: $id")
            appendLine("code <<<")
            appendLine(code)
            appendLine(CLOSE)
            for ((k, v) in meta) appendLine("$k: $v")
        }.trimEnd() + "\n"
    }

    /** 信封是否含 code 块（无则为不可自愈，error 级）。 */
    fun hasCodeBlock(envelope: String): Boolean =
        envelope.lines().any { CODE_OPEN.matches(it) }

    /**
     * 对单个 id 做分级门禁。
     *
     * @param rawId  模型给出的 id（可能为空）。
     * @param seen   本轮已用 id 集合（可变，命中合法 id 会写入），保证批量内唯一。
     */
    fun guardId(rawId: String?, seen: MutableSet<String>): IdVerdict {
        val id = rawId?.trim().takeIf { !it.isNullOrBlank() }
        return when {
            id == null -> {
                val fixed = nextCallId(seen)
                seen.add(fixed)
                IdVerdict(
                    allow = true,
                    fixedId = fixed,
                    warning = "工具调用 id 缺失，已自动补为 $fixed；" +
                        "下次请为每个 tool_call 显式给定唯一且非空的 id（例如 id: call_1）。",
                )
            }
            seen.contains(id) -> IdVerdict(
                allow = false,
                error = "Error: 工具调用被拒绝执行。原因：id 重复（$id）。" +
                    "每个 tool_call 必须拥有唯一且非空的 id，否则框架无法把工具结果对应回发起的调用。" +
                    "请重新发送，使用互不相同的 id（例如 id: call_1、id: call_2）。",
            )
            else -> {
                seen.add(id)
                IdVerdict(allow = true)
            }
        }
    }

    /** 批量 id 门禁：逐条判定，返回与输入等长的结论列表。 */
    fun guardIds(ids: List<String?>): List<IdVerdict> {
        val seen = LinkedHashSet<String>()
        return ids.map { guardId(it, seen) }
    }

    private fun nextCallId(seen: Set<String>): String {
        var n = 1
        while ("call_$n" in seen) n++
        return "call_$n"
    }
}
