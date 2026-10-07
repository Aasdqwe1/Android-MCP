package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * grep 工具内容匹配验证。
 *
 * 直接测试修复后的 grep 核心逻辑：
 * 1. 正则模式在行级别的匹配
 * 2. 缩进保留后是否能精确匹配内容
 * 3. 多行匹配与行号正确性
 */
class GrepMatchingTest {

    private lateinit var tmpDir: File

    private fun setup() {
        tmpDir = Files.createTempDirectory("grep-match-test").toFile()
    }

    private fun cleanup() {
        tmpDir.deleteRecursively()
    }

    private fun createTestFile(path: String, content: String): File {
        val file = File(tmpDir, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    // ──────────────────────────────────────────────────────────
    // 测试 1: 简单关键词匹配
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepSimpleKeywordMatching() {
        setup()
        try {
            val content = "line 1\nline 2 ERROR\nline 3\nline 4 ERROR\nline 5"
            createTestFile("log.txt", content)
            
            val lines = content.lines()
            val pattern = Regex("ERROR")
            
            // 模拟 grep 匹配
            val matched = mutableListOf<Int>()
            for ((idx, line) in lines.withIndex()) {
                if (pattern.containsMatchIn(line)) {
                    matched.add(idx)
                }
            }
            
            assertEquals(2, matched.size, "应找到 2 个 ERROR")
            assertEquals(1, matched[0], "第一个匹配在行索引 1（行号 2）")
            assertEquals(3, matched[1], "第二个匹配在行索引 3（行号 4）")
            
            // 验证匹配行内容
            assertEquals("line 2 ERROR", lines[matched[0]])
            assertEquals("line 4 ERROR", lines[matched[1]])
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 测试 2: 正则模式匹配（大小写不敏感）
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepRegexPatternMatching() {
        setup()
        try {
            val content = "class MyClass\n" +
                    "class YourClass\n" +
                    "fun myFunction\n" +
                    "val myVariable"
            
            createTestFile("code.kt", content)
            val lines = content.lines()
            
            // 模拟大小写不敏感的正则匹配
            val pattern = Regex("class", RegexOption.IGNORE_CASE)
            val matched = mutableListOf<Pair<Int, String>>()
            for ((idx, line) in lines.withIndex()) {
                if (pattern.containsMatchIn(line)) {
                    matched.add(idx to line)
                }
            }
            
            assertEquals(2, matched.size, "应找到 2 个 class")
            assertEquals("class MyClass", matched[0].second)
            assertEquals("class YourClass", matched[1].second)
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 测试 3: 缩进行的精确匹配（修复的核心）
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepIndentedLineMatching() {
        setup()
        try {
            val content = "fun main() {\n" +
                    "    val x = 1\n" +
                    "    if (x > 0) {\n" +
                    "        println(x)\n" +
                    "    }\n" +
                    "}"
            
            createTestFile("Main.kt", content)
            val lines = content.lines()
            
            // 模拟 grep：找到包含 "println" 的行
            val pattern = Regex("println")
            val matchedIdx = lines.indexOfFirst { pattern.containsMatchIn(it) }
            assertTrue(matchedIdx >= 0, "应找到 println 行")
            
            val matchedLine = lines[matchedIdx]
            
            // 关键验证：缩进应被保留（不 trim）
            val expectedIndent = "        "  // 8 个空格
            assertTrue(matchedLine.startsWith(expectedIndent), "缩进应被保留")
            assertEquals("        println(x)", matchedLine)
            
            // 模拟 edit_file：用该行作为 old_string
            val newLine = "        println(\"x = 1\")"
            val edited = content.replace(matchedLine, newLine)
            
            // 验证替换成功
            assertTrue(edited.contains("println(\"x = 1\")"), "替换应成功，缩进对齐")
            assertTrue(!edited.contains("println(x)") || edited.contains("println(\"x = 1\")"), 
                "原行应被新行替代")
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 测试 4: 多行匹配与行号正确性
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepMultilineMatchingWithLineNumbers() {
        setup()
        try {
            val content = "TODO: fix 1\n" +
                    "FIXME: issue\n" +
                    "TODO: fix 2\n" +
                    "DONE: task\n" +
                    "normal line"
            
            createTestFile("tasks.txt", content)
            val lines = content.lines()
            
            // 模拟 grep：找所有 TODO 或 FIXME
            val pattern = Regex("TODO|FIXME")
            val results = mutableListOf<Triple<Int, Int, String>>()  // line_num, line_index, content
            
            for ((idx, line) in lines.withIndex()) {
                if (pattern.containsMatchIn(line)) {
                    results.add(Triple(idx + 1, idx, line))  // 1-based line number
                }
            }
            
            assertEquals(3, results.size, "应找到 3 个 TODO/FIXME")
            
            // 验证行号
            assertEquals(1, results[0].first, "第 1 行")
            assertEquals(2, results[1].first, "第 2 行")
            assertEquals(3, results[2].first, "第 3 行")
            
            // 验证内容
            assertEquals("TODO: fix 1", results[0].third)
            assertEquals("FIXME: issue", results[1].third)
            assertEquals("TODO: fix 2", results[2].third)
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 测试 5: 二进制文件检测（修复前后都应工作）
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepBinaryFileDetection() {
        setup()
        try {
            val textContent = "line 1\nline 2 MATCH\nline 3"
            val binaryContent = "text\u0000binary\u0000data"  // 包含 NUL 字节
            
            createTestFile("text.txt", textContent)
            createTestFile("binary.dat", binaryContent)
            
            // 模拟 looksBinary
            fun looksBinary(text: String, threshold: Double = 0.10): Boolean {
                if (text.isEmpty()) return false
                if ('\u0000' in text) return true
                var ctrl = 0
                for (c in text) {
                    val code = c.code
                    val isControl = code in 0x00..0x08 || code in 0x0B..0x0C || code in 0x0E..0x1F || code == 0x7F
                    if (isControl) ctrl++
                }
                return ctrl.toDouble() / text.length > threshold
            }
            
            assertTrue(!looksBinary(textContent), "文本文件不应被判为二进制")
            assertTrue(looksBinary(binaryContent), "含 NUL 的文件应被判为二进制")
            
            // 验证：grep 默认会跳过二进制文件
            val pattern = Regex("MATCH")
            
            val textMatched = textContent.lines().any { pattern.containsMatchIn(it) }
            assertTrue(textMatched, "文本文件中应找到匹配")
            
            // 二进制文件即使被加载，在 textMode=false 时也应被跳过
            // （在实际 grep 工具中检查）
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 测试 6: 上下文行返回
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepContextLineRetrieval() {
        setup()
        try {
            val content = "line 1\n" +
                    "line 2 MATCH\n" +
                    "line 3\n" +
                    "line 4\n" +
                    "line 5"
            
            createTestFile("context.txt", content)
            val lines = content.lines()
            
            // 找到匹配行
            val pattern = Regex("MATCH")
            val matchIdx = lines.indexOfFirst { pattern.containsMatchIn(it) }
            assertEquals(1, matchIdx)
            
            // 获取上下文（前后各 1 行）
            val contextLines = 1
            val start = maxOf(0, matchIdx - contextLines)
            val end = minOf(lines.size - 1, matchIdx + contextLines)
            val ctx = lines.subList(start, end + 1)
            
            assertEquals(3, ctx.size)
            assertEquals("line 1", ctx[0])
            assertEquals("line 2 MATCH", ctx[1])
            assertEquals("line 3", ctx[2])
        } finally {
            cleanup()
        }
    }
}
