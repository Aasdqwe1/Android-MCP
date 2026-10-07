package com.mcp.toolbox

/**
 * 写后回执的单个替换 span（对齐 DeepSeek-Reasonix post_write_receipt.go）。
 */
data class ReceiptSpan(
    /** 被匹配的旧文本（old_string / matched span）。 */
    val matched: String,
    /** 替换后的新文本（new_string / replacement span）。 */
    val replacement: String,
    /** 匹配出现次数（默认 1）。 */
    val occurrences: Int = 1,
    /** 是否为模糊匹配命中。 */
    val fuzzy: Boolean = false
)

/** 回执总预算（字节）。 */
const val MAX_POST_WRITE_RECEIPT_BYTES = 2048
/** 单个 span 预算（字节）。 */
const val MAX_CAPTURED_RECEIPT_SPAN_BYTES = 896

/**
 * 渲染「写后回执」：把实际替换的 `-旧/+新` 片段回显给模型，预算化裁剪，
 * 只包含匹配与替换 span，不包含未变的同行/邻近数据（防上下文膨胀）。
 * 多替换只示首尾两条、中间省略；UTF-8 安全裁剪（不截断多字节字符）。
 */
fun renderPostWriteReceipts(receipts: List<ReceiptSpan>): String {
    if (receipts.isEmpty()) return ""
    val indexes = when {
        receipts.size <= 2 -> receipts.indices.toList()
        else -> listOf(0, receipts.size - 1)
    }
    var fieldBudget = (MAX_POST_WRITE_RECEIPT_BYTES - 256) / (2 * indexes.size)
    if (fieldBudget < 128) fieldBudget = 128
    if (fieldBudget > MAX_CAPTURED_RECEIPT_SPAN_BYTES) fieldBudget = MAX_CAPTURED_RECEIPT_SPAN_BYTES

    val sb = StringBuilder()
    for ((pos, idx) in indexes.withIndex()) {
        if (pos > 0) sb.append('\n')
        if (receipts.size > 2 && pos == 1) {
            sb.append("…").append(receipts.size - 2).append(" intermediate replacement receipt(s) omitted…\n")
        }
        val r = receipts[idx]
        val occ = if (r.occurrences <= 0) 1 else r.occurrences
        val fuzzy = if (r.fuzzy) {
            if (occ > 1) ", fuzzy match, first matched sample shown" else ", fuzzy match"
        } else ""
        sb.append("@@ replacement ").append(idx + 1).append(" of ").append(receipts.size)
            .append(" (").append(occ).append(" occurrence(s)").append(fuzzy).append(") @@\n")
        appendSpan(sb, '-', clipSpan(r.matched, fieldBudget))
        appendSpan(sb, '+', clipSpan(r.replacement, fieldBudget))
    }
    return clipTotal(sb.toString())
}

private fun appendSpan(sb: StringBuilder, prefix: Char, text: String) {
    sb.append(prefix).append(' ').append(text).append('\n')
}

/** 按 UTF-8 字节预算裁剪（在字符边界截断，不破坏多字节字符）。 */
internal fun clipSpan(text: String, budgetBytes: Int): String {
    if (text.isEmpty()) return ""
    val end = utf8ByteBoundary(text, budgetBytes)
    if (end >= text.length) return text
    return text.substring(0, end) + "…[replacement span truncated]…"
}

private fun clipTotal(text: String): String {
    val end = utf8ByteBoundary(text, MAX_POST_WRITE_RECEIPT_BYTES)
    if (end >= text.length) return text
    return text.substring(0, end) + "…[replacement receipt truncated; use read_file for complete current contents]…"
}

/** 返回不超过 budgetBytes 的最大 UTF-8 前缀长度（字符数）。 */
private fun utf8ByteBoundary(text: String, budgetBytes: Int): Int {
    var bytes = 0
    var idx = 0
    while (idx < text.length) {
        val cp = text.codePointAt(idx)
        val ch = Character.charCount(cp)
        val b = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
        if (bytes + b > budgetBytes) break
        bytes += b
        idx += ch
    }
    return idx
}
