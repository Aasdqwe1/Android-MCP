package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 写后回执渲染测试（对齐官方 post_write_receipt.go）。 */
class PostWriteReceiptTest {

    // 单替换：-旧/+新 span
    @Test
    fun singleReceipt() {
        val out = renderPostWriteReceipts(listOf(ReceiptSpan("aaa", "bbb")))
        assertTrue(out.contains("@@ replacement 1 of 1 (1 occurrence(s)) @@"), out)
        assertTrue(out.contains("- aaa"), out)
        assertTrue(out.contains("+ bbb"), out)
    }

    // fuzzy 标注
    @Test
    fun fuzzyMarked() {
        val out = renderPostWriteReceipts(listOf(ReceiptSpan("a   ", "b", fuzzy = true)))
        assertTrue(out.contains("(1 occurrence(s), fuzzy match)"), out)
    }

    // 多替换只示首尾两条、中间省略
    @Test
    fun manyReceiptsShowFirstAndLast() {
        val out = renderPostWriteReceipts(
            List(5) { ReceiptSpan("old$it", "new$it") }
        )
        assertTrue(out.contains("replacement 1 of 5"), out)
        assertTrue(out.contains("3 intermediate replacement receipt(s) omitted"), out)
        assertTrue(out.contains("replacement 5 of 5"), out)
        // 中间的 2/3/4 不出现
        assertTrue(!out.contains("replacement 2 of 5"), out)
        assertTrue(!out.contains("replacement 3 of 5"), out)
        assertTrue(!out.contains("replacement 4 of 5"), out)
    }

    // 空列表 → 空串
    @Test
    fun emptyReceipts() {
        assertEquals("", renderPostWriteReceipts(emptyList()))
    }

    // UTF-8 多字节字符裁剪安全（不破坏字符）
    @Test
    fun clipsMultiByteSafely() {
        val text = "中文中文中文" + "x".repeat(100)
        val clipped = clipSpan(text, 20)
        // 裁剪后的字节数不超过预算 + 截断标记
        assertTrue(clipped.toByteArray(Charsets.UTF_8).size <= 20 + "…[replacement span truncated]…".length * 3 + 8, clipped)
        // 前 6 个中文字符（18 字节）+ 2 个 x（20 字节）应完整保留
        assertTrue(clipped.startsWith("中文中文中文xx"), clipped)
    }

    // 总预算上限
    @Test
    fun totalBudgetCapped() {
        val big = ReceiptSpan("a".repeat(2000), "b".repeat(2000))
        val out = renderPostWriteReceipts(listOf(big))
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= MAX_POST_WRITE_RECEIPT_BYTES + 64, "size=${out.toByteArray(Charsets.UTF_8).size}")
    }
}
