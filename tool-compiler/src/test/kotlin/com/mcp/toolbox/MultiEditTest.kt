package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 顺序原子多编辑（multi_edit 核心逻辑）测试。
 *
 * 语义对齐 Reasonix multi_edit：每个编辑基于前一个结果、全部成功才产出最终文本，
 * 任一失败整体失败（不写盘）。
 */
class MultiEditTest {

    private fun ok(r: EditBatchResult): EditBatchResult.Success = r as EditBatchResult.Success
    private fun fail(r: EditBatchResult): EditBatchResult.Failure = r as EditBatchResult.Failure

    // 顺序应用多个编辑，最终文本为各编辑叠加结果
    @Test
    fun appliesEditsSequentially() {
        val text = "a\nb\nc\nd"
        val r = applySequentialEdits(
            text,
            listOf(
                EditSpec("b", "B"),
                EditSpec("c", "C"),
                EditSpec("a\nB", "A\nB")
            )
        )
        val s = ok(r)
        assertEquals("A\nB\nC\nd", s.newText)
        assertEquals(3, s.outcomes.size)
        assertEquals(2, s.outcomes[0].editLine)   // "b" 在原始第 2 行
        assertEquals(3, s.outcomes[1].editLine)   // "c" 在原始第 3 行
    }

    // 后一个编辑基于前一个编辑的结果（第二个编辑命中第一个编辑产生的文本）
    @Test
    fun laterEditSeesPreviousResult() {
        val r = applySequentialEdits(
            "x = 1\ny = 2",
            listOf(
                EditSpec("x = 1", "x = 10"),
                EditSpec("x = 10", "x = 100")   // 命中上一步产物
            )
        )
        assertEquals("x = 100\ny = 2", ok(r).newText)
    }

    // 任一编辑失败 → 整体 Failure，带失败步骤号（1-based）
    @Test
    fun failureAbortsBatchWithStepNumber() {
        val r = applySequentialEdits(
            "a\nb",
            listOf(
                EditSpec("a", "A"),          // 成功
                EditSpec("zzz", "Z"),        // 未找到 → 失败
                EditSpec("b", "B")           // 不应再执行
            )
        )
        val f = fail(r)
        assertEquals(2, f.step)
        assertTrue(f.reason.contains("找到匹配"), f.reason)
    }

    // 失败时不产出部分结果（整体原子性）
    @Test
    fun batchIsAtomicOnFailure() {
        val r = applySequentialEdits(
            "keep\ndrop",
            listOf(
                EditSpec("drop", "changed"),
                EditSpec("missing", "x")
            )
        )
        val f = fail(r)
        assertEquals(2, f.step)
        // 调用方拿到 Failure 后应丢弃整个 newText，不落盘
    }

    // CRLF 文件 + LF old_string：归一化匹配成功；未编辑区保持 \r\n 风格，
    // 替换区按 new_string 原文（与 edit_file 语义一致）
    @Test
    fun crlfNormalizationMatchesButReplacementUsesNewStringAsIs() {
        val text = "a\r\nb\r\nc"
        val r = applySequentialEdits(text, listOf(EditSpec("a\nb", "A\nB")))
        assertEquals("A\nB\r\nc", ok(r).newText)
    }

    // 多匹配且未指定 occurrence → 失败并提示
    @Test
    fun duplicateOldStringWithoutOccurrenceFails() {
        val r = applySequentialEdits("x\nx\nx", listOf(EditSpec("x", "y")))
        val f = fail(r)
        assertEquals(1, f.step)
        assertTrue(f.reason.contains("出现 3 次"), f.reason)
        assertTrue(f.reason.contains("occurrence"), f.reason)
    }

    // occurrence 指定第几次匹配（1-based）
    @Test
    fun occurrencePicksNthMatch() {
        val r = applySequentialEdits("x\nx\nx", listOf(EditSpec("x", "y", occurrence = 2)))
        assertEquals("x\ny\nx", ok(r).newText)
    }

    // occurrence 越界 → 失败
    @Test
    fun occurrenceOutOfRangeFails() {
        val r = applySequentialEdits("x", listOf(EditSpec("x", "y", occurrence = 2)))
        val f = fail(r)
        assertTrue(f.reason.contains("超出范围"), f.reason)
    }

    // 空 old_string 拒绝
    @Test
    fun emptyOldStringFails() {
        val r = applySequentialEdits("abc", listOf(EditSpec("", "x")))
        val f = fail(r)
        assertTrue(f.reason.contains("不能为空"), f.reason)
    }

    // 未找到匹配时给出最接近的行诊断（帮模型定位缩进/空白差异）
    @Test
    fun missingOldStringReportsClosestLine() {
        val r = applySingleEdit("aaa\nbbb\nccc", EditSpec("bbb\nxxx", "B"))
        val f = r as SingleEditResult.Error
        assertTrue(f.reason.contains("最接近"), f.reason)
        assertTrue(f.reason.contains("第 2 行"), f.reason)
    }

    // ── 模糊匹配容错（对齐 DeepSeek-Reasonix 官方 fuzzyEditRanges）──

    // 行尾空白差异：old_string 末行带换行、文件行尾多空格也能命中，并标记 fuzzy
    @Test
    fun fuzzyMatchesTrailingWhitespace() {
        val r = applySingleEdit("def f():\n    return 1   \n", EditSpec("def f():\n    return 1\n", "def f():\n    return 2\n"))
        val s = r as SingleEditResult.Ok
        assertTrue(s.outcome.fuzzy)
        assertEquals("def f():\n    return 2\n", s.newText)
    }

    // read_file 的 LINE[HASH] 前缀差异：old_string 带前缀也能命中（剥离前缀后匹配）
    @Test
    fun fuzzyStripsReadFilePrefix() {
        val content = "import os\nimport sys\n"
        val oldWithPrefix = "2[abc123]: import sys\n"
        val r = applySingleEdit(content, EditSpec(oldWithPrefix, "import pathlib\n"))
        val s = r as SingleEditResult.Ok
        assertTrue(s.outcome.fuzzy)
        assertEquals("import os\nimport pathlib\n", s.newText)
    }

    // tab 与空格差异：expandTabs 归一化后命中；替换区按 new_string 原文（不保留原 tab）
    @Test
    fun fuzzyExpandsTabs() {
        val r = applySingleEdit("if x:\n\tprint(1)\n", EditSpec("if x:\n    print(1)", "if x:\n    print(2)"))
        val s = r as SingleEditResult.Ok
        assertTrue(s.outcome.fuzzy)
        assertEquals("if x:\n    print(2)\n", s.newText)
    }

    // 模糊多命中：不唯一时报错，不静默改
    @Test
    fun fuzzyMultipleHitsFails() {
        val r = applySingleEdit("aaa\nbbb  \nbbb  \n", EditSpec("bbb\n", "X"))
        val f = r as SingleEditResult.Error
        assertTrue(f.reason.contains("模糊匹配到 2 处"), f.reason)
    }

    // ── inferScalar 剥引号后的回退：文件行本身带引号（如字符串字面量）──

    // ── inferScalar 剥引号后导致多处匹配的回退：加引号后唯一命中 ──

    // 真实场景：文件含 "if j == 0" 行（字符串表达式）和 if j == 0: 行（if 语句含子串）。
    // inferScalar 把 edit_1_old: "if j == 0" 剥成 if j == 0，两处都含此子串 → 多处匹配。
    // 回退：加回外层引号 → "if j == 0" 只在字符串表达式行精确命中 → fuzzy 替换成功。
    @Test
    fun fuzzyFallbackAddsQuotesWhenMultipleHitsAfterStrip() {
        val fileContent = "    if j == 0:\n        pass\n\"if j == 0\"\n    return x\n"
        // old_string 经 inferScalar 剥引号后匹配两处；加引号回退唯一定位到字符串表达式行整体替换
        val result = applySingleEdit(fileContent, EditSpec("if j == 0", "if j == 0:"))
        assertTrue(result is SingleEditResult.Ok, "加引号回退应能唯一匹配，实际: $result")
        val newText = (result as SingleEditResult.Ok).newText
        // 整体替换："if j == 0" → if j == 0:（去掉外层引号，变成合法 if 语句）
        assertTrue(newText.contains("if j == 0:"), "替换后应有合法 if 语句，实际: $newText")
        assertTrue(!newText.contains("\"if j == 0\""), "原字符串表达式行应被移除")
        assertTrue(result.outcome.fuzzy)
    }

    // 加引号后仍多处命中 → 应报错，不静默改
    @Test
    fun fuzzyFallbackQuotedMultipleHitsFails() {
        val fileContent = "\"foo\"\n\"foo\"\n"
        val result = applySingleEdit(fileContent, EditSpec("foo", "bar"))
        assertTrue(result is SingleEditResult.Error, "加引号后仍多处应报错")
    }

    // 单次匹配但在引号内部：文件有 "if False:"，old_string=if False:（剥引号后）
    // 匹配到 "if False:" 内部的 if False:。回退：加引号 → "if False:" 精确匹配 → 替换整个引号内容。
    @Test
    fun fuzzyFallbackSingleMatchInsideQuotes() {
        val fileContent = "    # 第一层嵌套\n\"    if False:\"\n    print(\"hello\")\n"
        // old_string 经 inferScalar 剥引号后匹配到 "    if False:" 内部的子串
        val result = applySingleEdit(fileContent, EditSpec("    if False:", "    if True:"))
        assertTrue(result is SingleEditResult.Ok, "单次匹配在引号内部应触发引号回退，实际: $result")
        val newText = (result as SingleEditResult.Ok).newText
        // 整体替换："    if False:" → "    if True:"（引号被去掉，new_string 原样写入）
        assertTrue(newText.contains("    if True:"), "替换后应有 if True:，实际: $newText")
        assertTrue(!newText.contains("\"    if False:\""), "原引号包裹行应被移除")
        assertTrue(result.outcome.fuzzy)
    }

    // 模糊命中时 new_string 按文件换行风格适配（CRLF 文件展开 LF；未匹配的末行保留）
    @Test
    fun fuzzyAdaptsNewLineEndingsToCrlf() {
        val r = applySingleEdit("a\r\nb   \r\nc", EditSpec("b\n", "B\nC\n"))
        val s = r as SingleEditResult.Ok
        assertEquals("a\r\nB\r\nC\r\nc", s.newText)
    }

    // 空 edits 列表 → 失败
    @Test
    fun emptyEditsFails() {
        val f = fail(applySequentialEdits("abc", emptyList()))
        assertEquals(0, f.step)
        assertTrue(f.reason.contains("为空"), f.reason)
    }

    // editLine：编辑位置行号基于应用前文本
    @Test
    fun editLineIsOneBasedOnPreEditText() {
        val r = applySequentialEdits(
            "line1\nline2\nline3",
            listOf(EditSpec("line2", "LINE2"))
        )
        assertEquals(2, ok(r).outcomes[0].editLine)
    }
}
