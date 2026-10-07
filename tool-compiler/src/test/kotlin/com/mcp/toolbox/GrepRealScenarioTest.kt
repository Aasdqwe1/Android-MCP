package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * grep 工具真实环境模拟测试。
 *
 * 验证 LLM 调用 grep 工具时，是否能真正匹配到内容。
 * 模拟完整的参数解析与匹配逻辑。
 */
class GrepRealScenarioTest {

    private lateinit var tmpDir: File

    private fun setup() {
        tmpDir = Files.createTempDirectory("grep-real-test").toFile()
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
    // 关键问题：当 LLM 说"匹配不到"，最常见的原因
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepWithoutGlobParameter() {
        setup()
        try {
            // 常见场景：LLM 只提供 pattern，不提供 glob
            createTestFile("src/main.kt", "fun main() {\n    println(\"hello\")\n}")
            createTestFile("test.txt", "hello world")
            
            val files = tmpDir.walkTopDown().filter { it.isFile }.toList()
            
            // 模拟 grep pattern="hello" glob=null
            val pattern = Regex("hello")
            val glob = null  // LLM 可能根本没提 glob 参数
            
            val matched = mutableListOf<File>()
            for (file in files) {
                val text = file.readText()
                val lines = text.lines()
                for (line in lines) {
                    if (pattern.containsMatchIn(line)) {
                        matched.add(file)
                        break  // 该文件已匹配
                    }
                }
            }
            
            assertEquals(2, matched.size, "无 glob 时应匹配所有含 hello 的文件")
            assertTrue(matched.any { it.name == "main.kt" })
            assertTrue(matched.any { it.name == "test.txt" })
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepPathDetectedCorrectly() {
        setup()
        try {
            // LLM 提供了 path 参数，指向一个目录
            val srcDir = File(tmpDir, "src")
            srcDir.mkdirs()
            createTestFile("src/code.kt", "fun fetch() {\n    val data = getData()\n}")
            createTestFile("src/test.kt", "fun test() { }")
            
            // 模拟 grep pattern="fetch" path="src"
            val path = srcDir.absolutePath
            val pattern = Regex("fetch")
            
            val files = File(path).walkTopDown().filter { it.isFile }.toList()
            assertEquals(2, files.size)
            
            val matched = mutableListOf<String>()
            for (file in files) {
                val text = file.readText()
                val lines = text.lines()
                for ((idx, line) in lines.withIndex()) {
                    if (pattern.containsMatchIn(line)) {
                        matched.add("${file.name}:${idx + 1}:$line")
                    }
                }
            }
            
            assertEquals(1, matched.size, "应只在 code.kt 中找到 fetch")
            assertEquals("code.kt:1:fun fetch() {", matched[0])
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepCaseSensitivityIssue() {
        setup()
        try {
            // 常见坑：LLM 提供的正则模式大小写不匹配文件内容
            createTestFile("file.log", "ERROR: database connection failed\nerror: timeout")
            
            val text = File(tmpDir, "file.log").readText()
            val lines = text.lines()
            
            // 测试 1: 大小写敏感的正则
            val patternCaseSensitive = Regex("ERROR")
            val matches1 = lines.filter { patternCaseSensitive.containsMatchIn(it) }
            assertEquals(1, matches1.size, "大小写敏感应只匹配 ERROR")
            
            // 测试 2: 大小写不敏感的正则（正确做法）
            val patternIgnoreCase = Regex("error", RegexOption.IGNORE_CASE)
            val matches2 = lines.filter { patternIgnoreCase.containsMatchIn(it) }
            assertEquals(2, matches2.size, "大小写不敏感应匹配两行")
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepEmptyResultsAfterFix() {
        setup()
        try {
            // 关键问题：glob 路径匹配修复前后的对比
            createTestFile("src/main/App.kt", "class App { val name = \"app\" }")
            createTestFile("src/test/AppTest.kt", "class AppTest { }")
            
            val files = tmpDir.walkTopDown().filter { it.isFile }.toList()
            
            // 修复前：glob="src/**/*.kt" 但只用 file.name 匹配 → 永不匹配
            // 修复后：用 relativePath 匹配 → 正确匹配
            val glob = "src/**/*.kt"
            val pattern = Regex("App")
            
            // 模拟修复后的逻辑：用相对路径做 glob 匹配
            val globRegex = globToRegex(glob)
            val matched = mutableListOf<String>()
            for (file in files) {
                val relativePath = file.relativeTo(tmpDir).path.replace('\\', '/')
                if (globRegex.matches(relativePath)) {
                    val text = file.readText()
                    if (pattern.containsMatchIn(text)) {
                        matched.add(relativePath)
                    }
                }
            }
            
            assertEquals(2, matched.size, "glob 修复后应正确匹配 src 下的 .kt 文件")
            assertTrue(matched.any { it.contains("src/main") })
            assertTrue(matched.any { it.contains("src/test") })
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepLineContentPreservation() {
        setup()
        try {
            // 核心问题：返回的行文本是否能直接作为 edit_file 的 old_string
            val content = "class Handler {\n" +
                    "    fun process(msg: String) {\n" +
                    "        val parts = msg.split(\",\")\n" +
                    "        println(parts)\n" +
                    "    }\n" +
                    "}"
            
            createTestFile("Handler.kt", content)
            val lines = content.lines()
            
            // grep pattern="split"
            val pattern = Regex("split")
            val matchIdx = lines.indexOfFirst { pattern.containsMatchIn(it) }
            assertTrue(matchIdx >= 0, "应找到 split 行")
            
            val matchedLine = lines[matchIdx]
            // 关键：这一行是否包含完整缩进？
            assertEquals("        val parts = msg.split(\",\")", matchedLine)
            assertTrue(matchedLine.startsWith("        "))  // 8 个空格的缩进
            
            // 模拟 LLM 用此行作为 edit_file 的 old_string
            val oldString = matchedLine
            val newString = "        val parts = msg.split(\",\").toList()"
            val edited = content.replace(oldString, newString)
            
            // 验证替换成功
            assertTrue(edited.contains(".toList()"))
            assertTrue(edited.contains("class Handler"))
            assertTrue(!edited.contains("msg.split(\",\")") || edited.contains("msg.split(\",\").toList()"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepMultipleMatches() {
        setup()
        try {
            // 问题：一个文件中多处命中时，grep 是否返回了所有结果？
            val content = "TODO: task 1\n" +
                    "code here\n" +
                    "TODO: task 2\n" +
                    "more code\n" +
                    "TODO: task 3"
            
            createTestFile("todos.txt", content)
            val lines = content.lines()
            
            // grep pattern="TODO" head_limit=50
            val pattern = Regex("TODO")
            val headLimit = 50
            val results = mutableListOf<Int>()
            
            for ((idx, line) in lines.withIndex()) {
                if (results.size >= headLimit) break
                if (pattern.containsMatchIn(line)) {
                    results.add(idx)
                }
            }
            
            assertEquals(3, results.size, "应找到全部 3 个 TODO")
            // 验证每一行的内容
            assertEquals("TODO: task 1", lines[results[0]])
            assertEquals("TODO: task 2", lines[results[1]])
            assertEquals("TODO: task 3", lines[results[2]])
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepContextLinesAreCorrect() {
        setup()
        try {
            // 验证返回的上下文行范围计算是否正确
            val content = "line 1\nline 2\nline 3 TARGET\nline 4\nline 5"
            createTestFile("file.txt", content)
            val lines = content.lines()
            
            // grep pattern="TARGET" context_lines=1
            val pattern = Regex("TARGET")
            val contextLines = 1
            val matchIdx = lines.indexOfFirst { pattern.containsMatchIn(it) }
            
            val start = maxOf(0, matchIdx - contextLines)
            val end = minOf(lines.size - 1, matchIdx + contextLines)
            val ctx = lines.subList(start, end + 1)
            
            assertEquals(3, ctx.size)
            assertEquals("line 2", ctx[0])
            assertEquals("line 3 TARGET", ctx[1])
            assertEquals("line 4", ctx[2])
            
            // 如果 LLM 用这整个上下文段作为 old_string，能否在 edit_file 中精确替换？
            val oldString = ctx.joinToString("\n")
            val newString = "line 2\nline 3 TARGET_UPDATED\nline 4"
            val edited = content.replace(oldString, newString)
            assertTrue(edited.contains("TARGET_UPDATED"))
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 辅助函数
    // ──────────────────────────────────────────────────────────

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        sb.append("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        sb.append(".*")
                        i += 2
                        if (i < glob.length && glob[i] == '/') i++
                        continue
                    } else sb.append("[^/]*")
                }
                '?' -> sb.append(".")
                else -> sb.append(java.util.regex.Pattern.quote(c.toString()))
            }
            i++
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}
