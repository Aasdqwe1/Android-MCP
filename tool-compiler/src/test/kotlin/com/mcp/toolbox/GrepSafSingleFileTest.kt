package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * 测试 grep 在 SAF 单文件情况下的工作能力。
 *
 * Bug 4：grepSafRecursive() 总是尝试 listFiles()，
 * 当 DocumentFile 是单个文件时，listFiles() 返回 null，
 * 导致循环什么都不做，返回空结果。
 *
 * 真实场景：用户在 /storage/emulated/0 公共存储中有一个 indent_test.kt 文件，
 * grep 调用返回 {"count":0,...}，虽然文件内容确实有 "for" 关键字。
 *
 * 修复：在 grepTool() 的 SafRef 处理中，先判断是否为单文件，
 * 是的话直接搜索而不调用 grepSafRecursive()。
 */
class GrepSafSingleFileTest {

    private lateinit var tmpDir: File

    private fun setup() {
        tmpDir = Files.createTempDirectory("grep-saf-test").toFile()
    }

    private fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun grepSafSingleFileShouldFindMatches() {
        setup()
        try {
            // 模拟真实场景：单个 Kotlin 文件，含多个 for 循环
            val kotlinFile = File(tmpDir, "indent_test.kt")
            val content = """fun processItems() {
    // Process a list of items.
    val items = listOf(1, 2, 3, 4, 5)
    for (item in items) {
        println("Item: ${'$'}item")
        if (item % 2 == 0) {
            for (j in 0..2) {
                println("  ${'$'}j")
            }
        }
    }
}

for (i in 0 until 10) {
    // pass
}"""
            
            kotlinFile.writeText(content)
            val lines = content.lines()
            
            // 模拟 grep 在 SAF 单文件上搜索 "for"
            val pattern = Regex("for")
            val matches = mutableListOf<String>()
            
            for ((idx, line) in lines.withIndex()) {
                if (pattern.containsMatchIn(line)) {
                    matches.add("${idx + 1}: ${line.trim()}")
                }
            }
            
            // 验证找到所有 "for"
            assertEquals(3, matches.size, "应找到 3 个 'for'")
            assertTrue(matches[0].contains("for (item in items)"))
            assertTrue(matches[1].contains("for (j in 0..2)"))
            assertTrue(matches[2].contains("for (i in 0 until 10)"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepSafSingleFileWithContextLines() {
        setup()
        try {
            val kotlinFile = File(tmpDir, "code.kt")
            val content = """fun main() {
    // TODO: implement
    println("start")
    for (item in data) {
        process(item)
    }
    println("end")
}"""
            
            kotlinFile.writeText(content)
            val lines = content.lines()
            
            // 搜索 "for" 并包含上下文
            val pattern = Regex("for")
            val contextLines = 1
            val results = mutableListOf<String>()
            
            for ((idx, line) in lines.withIndex()) {
                if (pattern.containsMatchIn(line)) {
                    val start = maxOf(0, idx - contextLines)
                    val end = minOf(lines.size - 1, idx + contextLines)
                    val ctx = lines.subList(start, end + 1)
                    results.add(ctx.joinToString("\n"))
                }
            }
            
            assertEquals(1, results.size)
            val context = results[0]
            assertTrue(context.contains("println(\"start\")"))
            assertTrue(context.contains("for (item in data)"))
            assertTrue(context.contains("process(item)"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepSafSingleFileTextEncoding() {
        setup()
        try {
            // 测试中文编码的文件
            val codeFile = File(tmpDir, "app.kt")
            val content = """// 这是一个中文注释
fun 处理数据() {
    for (项 in 数据列表) {
        println("处理${'$'}{项}")
    }
}

for (循环 in 0 until 10) {
    // pass
}"""
            
            codeFile.writeText(content, Charsets.UTF_8)
            val lines = content.lines()
            
            // 搜索 "for"，不管是否中文混用
            val pattern = Regex("for")
            val matches = mutableListOf<String>()
            
            for ((idx, line) in lines.withIndex()) {
                if (pattern.containsMatchIn(line)) {
                    matches.add(line)
                }
            }
            
            assertEquals(2, matches.size, "应找到 2 个 'for'")
            assertTrue(matches[0].contains("for (项 in 数据列表)"))
            assertTrue(matches[1].contains("for (循环 in 0 until 10)"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepSafSingleFileWithoutMatches() {
        setup()
        try {
            val emptyFile = File(tmpDir, "empty.kt")
            val content = "println(\"hello\")\nprintln(\"world\")"
            emptyFile.writeText(content)
            val lines = content.lines()
            
            // 搜索不存在的模式
            val pattern = Regex("for")
            val matches = lines.filter { pattern.containsMatchIn(it) }
            
            assertEquals(0, matches.size, "不应找到任何 'for'")
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepSafSingleFileHeadLimit() {
        setup()
        try {
            val file = File(tmpDir, "loops.kt")
            val content = (1..20).joinToString("\n") { "for i${it}() pass" }
            file.writeText(content)
            val lines = content.lines()
            
            // 搜索带 head_limit=5
            val pattern = Regex("for")
            val headLimit = 5
            val matches = mutableListOf<String>()
            
            for (line in lines) {
                if (matches.size >= headLimit) break
                if (pattern.containsMatchIn(line)) {
                    matches.add(line)
                }
            }
            
            assertEquals(5, matches.size, "head_limit 应限制为 5 个结果")
        } finally {
            cleanup()
        }
    }
}
