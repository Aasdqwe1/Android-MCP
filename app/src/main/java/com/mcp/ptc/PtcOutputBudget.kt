package com.mcp.ptc

/**
 * run_code 输出字节预算（对齐 dsh 的 output-limit 语义）：日志按行累计，超出预算时
 * 截断并追加显式标记 [output truncated]，避免一段循环 print 把模型上下文冲爆
 * （整改前 logs 全量回传、无任何上限）。
 */
object PtcOutputBudget {

    /** 默认日志预算（字符数，按 UTF-16 长度近似字节）。 */
    const val DEFAULT_MAX_LOG_CHARS = 64_000

    /** 返回值预算（字符数）：run_code 的 return 值同样需要上限，否则大对象会冲爆上下文。 */
    const val DEFAULT_MAX_RESULT_CHARS = 128_000

    /** 截断标记行：必须显式可见，禁止静默截断。 */
    const val TRUNCATED_MARK = "[output truncated]"

    /**
     * 按行累计裁剪日志：保留前缀直到超出 [maxChars]，丢弃剩余行并追加标记行。
     * @return 裁剪后的行列表；未超预算时内容不变。
     */
    fun capLogs(lines: List<String>, maxChars: Int = DEFAULT_MAX_LOG_CHARS): List<String> {
        if (maxChars <= 0) return listOf(TRUNCATED_MARK)
        val out = ArrayList<String>(lines.size)
        var used = 0
        var dropped = 0
        for (line in lines) {
            val cost = line.length + 1
            if (used + cost > maxChars) {
                dropped++
                continue
            }
            out.add(line)
            used += cost
        }
        if (dropped > 0) out.add(TRUNCATED_MARK + " (" + dropped + " 行被省略)")
        return out
    }

    /** 单段文本裁剪（保留前缀 + 标记）。 */
    fun capText(text: String, maxChars: Int = DEFAULT_MAX_LOG_CHARS): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "\n" + TRUNCATED_MARK

    /**
     * 返回值（result）预算：超出时保留前缀并标记，并告知调用方已截断
     * （结果里加 `result_truncated: true`，避免模型把截断串当成完整结果）。
     */
    fun capResult(text: String, maxChars: Int = DEFAULT_MAX_RESULT_CHARS): Pair<String, Boolean> =
        if (text.length <= maxChars) text to false
        else (text.take(maxChars) + "\n" + TRUNCATED_MARK) to true
}
