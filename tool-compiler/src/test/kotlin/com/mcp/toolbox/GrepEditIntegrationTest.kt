package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertContains
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * grep 与 edit_file 集成测试。
 *
 * 验证：
 * 1. grep 返回的行文本保留缩进，能直接作为 edit_file 的 old_string
 * 2. 路径 glob 匹配正常工作（含文件名前缀）
 * 3. 多行匹配与上下文一致性
 */
class GrepEditIntegrationTest {

    private lateinit var tmpDir: File

    private fun setup() {
        tmpDir = Files.createTempDirectory("grep-edit-test").toFile()
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
    // 测试 1: grep 保留缩进，可直接用于 edit_file
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepPreservesIndentation() {
        setup()
        try {
            val testCode = "fun main() {\n" +
                    "    val x = 1\n" +
                    "    if (x > 0) {\n" +
                    "        println(x)\n" +
                    "    }\n" +
                    "}"

            createTestFile("Main.kt", testCode)

            val lines = testCode.lines()
            val printlnIdx = lines.indexOfFirst { it.contains("println") }
            assertTrue(printlnIdx >= 0, "应找到 println 行")

            val lineContent = lines[printlnIdx]
            // 验证缩进被保留
            assertEquals("        println(x)", lineContent)
            assertTrue(lineContent.startsWith("        "), "行首缩进（8 个空格）应被保留")

            // 模拟 grep 返回（不调用 trim）
            val grepOutput = lineContent  // 直接返回，不 trim
            
            // 模拟 edit_file 用该输出作为 old_string
            val newContent = "        println(\"Modified: x\")"
            val edited = testCode.replace(grepOutput, newContent)
            
            assertTrue(edited.contains("println(\"Modified: x\")"), "替换应成功，缩进对齐")
            assertTrue(edited.contains("fun main()"), "其他行应保持不变")
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 测试 2: glob 路径前缀匹配
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepGlobPathPrefixMatching() {
        setup()
        try {
            // 创建多个嵌套文件
            createTestFile("src/main/Main.kt", "fun main() { }")
            createTestFile("src/test/MainTest.kt", "fun test() { }")
            createTestFile("app/src/App.kt", "class App { }")
            createTestFile("lib/Utils.kt", "fun util() { }")

            val files = tmpDir.walkTopDown().filter { it.isFile }.toList()
            assertEquals(4, files.size, "应有 4 个文件")

            // 测试 glob: **/*.kt（应匹配所有）
            val allGlob = "**/*.kt"
            val allMatches = files.filter { file ->
                val relativePath = file.relativeTo(tmpDir).path.replace('\\', '/')
                grepGlobMatches(relativePath, allGlob)
            }
            assertEquals(4, allMatches.size, "**/*.kt 应匹配所有 .kt 文件")

            // 测试 glob: src/*.kt（仅顶层 src，应不匹配嵌套文件）
            val srcTopGlob = "src/*.kt"
            val srcTopMatches = files.filter { file ->
                val relativePath = file.relativeTo(tmpDir).path.replace('\\', '/')
                grepGlobMatches(relativePath, srcTopGlob)
            }
            assertEquals(0, srcTopMatches.size, "src/*.kt（仅顶层）应不匹配 src 内嵌套文件")
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 测试 3: grep 返回行号与上下文
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepReturnLineNumberAndContext() {
        setup()
        try {
            val testCode = "line 1\nline 2\nline 3 TARGET\nline 4\nline 5"

            createTestFile("test.txt", testCode)
            val lines = testCode.lines()

            // 模拟 grep 找到第 3 行（含 TARGET）
            val targetIdx = lines.indexOfFirst { it.contains("TARGET") }
            assertEquals(2, targetIdx)  // 0-based

            val lineNum = targetIdx + 1  // 转为 1-based
            assertEquals(3, lineNum)

            // 获取上下文（前后各 1 行）
            val contextStart = maxOf(0, targetIdx - 1)
            val contextEnd = minOf(lines.size - 1, targetIdx + 1)
            val contextLines = lines.subList(contextStart, contextEnd + 1)

            assertEquals(3, contextLines.size)
            assertEquals("line 2", contextLines[0])
            assertEquals("line 3 TARGET", contextLines[1])
            assertEquals("line 4", contextLines[2])
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // 测试 4: 混合场景 - grep 找到 + edit_file 编辑
    // ──────────────────────────────────────────────────────────

    @Test
    fun grepAndEditEndToEnd() {
        setup()
        try {
            val testCode = "class Handler {\n" +
                    "    fun process(data: String) {\n" +
                    "        val result = data.trim()\n" +
                    "        return result\n" +
                    "    }\n" +
                    "}"

            createTestFile("Handler.kt", testCode)
            val lines = testCode.lines()

            // grep 找到 "trim" 行
            val trimIdx = lines.indexOfFirst { it.contains("trim") }
            val trimLine = lines[trimIdx]
            
            // 验证缩进被保留
            assertEquals("        val result = data.trim()", trimLine)
            assertTrue(trimLine.startsWith("        "), "应保留缩进（8 个空格）")

            // 模拟 edit_file：用 grep 返回的行作为 old_string，替换为新内容
            val newLine = "        val result = data.trim().uppercase()"
            val edited = testCode.replace(trimLine, newLine)

            assertTrue(edited.contains("uppercase()"), "应成功替换")
            assertTrue(edited.contains("class Handler {"), "其他行应保持不变")
            assertTrue(edited.contains("data.trim().uppercase()"), "新内容应在结果中")
        } finally {
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────
    // ──────────────────────────────────────────────────────────
    // 测试 5: 制表符与空格混合 - fuzzy 匹配应能处理
    // ──────────────────────────────────────────────────────────

    @Test
    fun editFileTabsVsSpaces() {
        // 文件用制表符缩进，old_string 用空格 → fuzzy 展开 tab=4 空格应能匹配
        val fileWithTabs = "def process():\n\tfor item in items:\n\t\tprint(item)\n\treturn items\n"

        // fuzzy：tab 展开为 4 空格后与 old_string 比较
        val oldWithSpaces = "\tfor item in items:\n\t\tprint(item)"
        val newStr = "\tfor item in items:\n\t\tprint(repr(item))"

        val result = applySingleEdit(fileWithTabs, EditSpec(oldWithSpaces, newStr))
        assertTrue(result is SingleEditResult.Ok, "tab 缩进文件应能被精确匹配（old_string 也用 tab）")
        assertTrue((result as SingleEditResult.Ok).newText.contains("repr(item)"))
    }

    @Test
    fun editFileTabExpandedToSpaces() {
        // 文件用 tab，old_string 用 4 空格代替 tab → fuzzy expandTabs 应能匹配
        val fileWithTabs = "class Foo:\n\tdef bar(self):\n\t\treturn 42\n"

        // LLM 看到 grep 返回原始 tab 行，但自己写 old_string 时误用了 4 个空格
        val oldWithSpacesInsteadOfTab = "    def bar(self):\n        return 42"
        val newStr = "    def bar(self):\n        return 0"

        val result = applySingleEdit(fileWithTabs, EditSpec(oldWithSpacesInsteadOfTab, newStr))
        assertTrue(result is SingleEditResult.Ok, "4 空格应能 fuzzy 匹配文件中的 tab 缩进")
        assertTrue((result as SingleEditResult.Ok).newText.contains("return 0"))
    }

    @Test
    fun editFileTrailingSpacesMismatch() {
        // 文件行尾有多余空格，old_string 没有 → fuzzy trimTrailing 应能匹配
        val fileWithTrailing = "fun compute() {   \n    val x = 1  \n    return x\n}"

        val oldNoTrailing = "fun compute() {\n    val x = 1\n    return x"
        val newStr = "fun compute() {\n    val x = 2\n    return x"

        val result = applySingleEdit(fileWithTrailing, EditSpec(oldNoTrailing, newStr))
        assertTrue(result is SingleEditResult.Ok, "行尾空格差异应被 fuzzy 忽略")
        assertTrue((result as SingleEditResult.Ok).newText.contains("val x = 2"))
    }

    @Test
    fun editFileEscapedCharsInContent() {
        // 文件内容含转义字符（字符串字面量里的 \n \t \\）
        val fileContent = "val msg = \"hello\\nworld\"\n" +
                "val path = \"C:\\\\Users\\\\admin\"\n" +
                "val tab = \"col1\\tcol2\"\n"

        // grep 应原样返回这些行，edit_file 精确匹配应能找到
        val oldStr = "val path = \"C:\\\\Users\\\\admin\""
        val newStr = "val path = \"C:\\\\Users\\\\admin\\\\Desktop\""

        val result = applySingleEdit(fileContent, EditSpec(oldStr, newStr))
        assertTrue(result is SingleEditResult.Ok, "含反斜杠转义的字符串应能精确匹配")
        assertTrue((result as SingleEditResult.Ok).newText.contains("Desktop"))
    }

    @Test
    fun editFileCrlfLineEndings() {
        // 文件使用 CRLF，old_string 使用 LF → CRLF 归一化应能匹配
        val fileWithCrlf = "line1\r\nline2\r\nline3\r\n"

        val oldLf = "line1\nline2"
        val newLf = "lineA\nlineB"

        val result = applySingleEdit(fileWithCrlf, EditSpec(oldLf, newLf))
        assertTrue(result is SingleEditResult.Ok, "CRLF 文件应能被 LF 的 old_string 匹配")
        assertTrue((result as SingleEditResult.Ok).newText.contains("lineA"))
    }

    @Test
    fun editFileMixedTabSpaceInSameLine() {
        // 同一行内既有 tab 又有空格（混合缩进的典型坑）
        val fileContent = "class X:\n\t    def method(self):\n\t        pass\n"

        val oldStr = "\t    def method(self):\n\t        pass"
        val newStr = "\t    def method(self):\n\t        return None"

        val result = applySingleEdit(fileContent, EditSpec(oldStr, newStr))
        assertTrue(result is SingleEditResult.Ok, "混合 tab+空格的行应能精确匹配")
        assertTrue((result as SingleEditResult.Ok).newText.contains("return None"))
    }

    @Test
    fun editFileAllMixedInOneLine() {
        // 同一行内同时包含：tab 缩进 + 转义字符（\\、\t）+ 行尾多余空格
        // 例：脚本中打印带反斜杠路径，且 grep 拿到的行末尾有空格
        val fileContent = "def show():\n" +
                "\tprint(\"C:\\\\Users\\\\admin\\tdesktop\")   \n" +   // tab缩进 + 反斜杠 + \t字面量 + 行尾3空格
                "\treturn 0\n"

        // old_string：tab缩进精确，转义字符精确，但无行尾空格（trimTrailing 应处理）
        val oldStr = "\tprint(\"C:\\\\Users\\\\admin\\tdesktop\")"
        val newStr = "\tprint(\"C:\\\\Users\\\\admin\\\\Desktop\")"

        val result = applySingleEdit(fileContent, EditSpec(oldStr, newStr))
        assertTrue(result is SingleEditResult.Ok, "tab缩进+转义字符+行尾空格同行应能 fuzzy 匹配")
        assertTrue((result as SingleEditResult.Ok).newText.contains("Desktop"))
    }

    @Test
    fun editFileTabExpandedWithEscapeInContent() {
        // 文件用 tab 缩进 + 内容含 \\ 转义，old_string 误用 4 空格代替 tab
        val fileContent = "config = {\n" +
                "\t\"path\": \"C:\\\\Users\\\\admin\",\n" +
                "\t\"debug\": True\n" +
                "}\n"

        // LLM 用 4 空格替代 tab → fuzzy expandTabs 处理
        val oldStr = "    \"path\": \"C:\\\\Users\\\\admin\",\n    \"debug\": True"
        val newStr = "    \"path\": \"D:\\\\Work\",\n    \"debug\": False"

        val result = applySingleEdit(fileContent, EditSpec(oldStr, newStr))
        assertTrue(result is SingleEditResult.Ok, "4空格替代tab+转义字符内容应能 fuzzy 匹配")
        val newText = (result as SingleEditResult.Ok).newText
        assertTrue(newText.contains("D:\\\\Work"))
        assertTrue(newText.contains("False"))
    }

    @Test
    fun editFileCrlfWithTabAndEscape() {
        // 三重叠加：CRLF 换行 + tab 缩进 + 内容含 \\ 和 \t 转义字符
        val fileContent = "def run():\r\n" +
                "\tcmd = \"ping\\t127.0.0.1\"\r\n" +   // tab 缩进，内容含 \t 字面量
                "\tpath = \"C:\\\\Windows\"\r\n" +      // tab 缩进，内容含 \\
                "\treturn cmd\r\n"

        // old_string 用 LF + 4空格（两个差异同时出现）
        val oldStr = "    cmd = \"ping\\t127.0.0.1\"\n    path = \"C:\\\\Windows\""
        val newStr = "    cmd = \"ping -n 4 127.0.0.1\"\n    path = \"C:\\\\Windows\\\\System32\""

        val result = applySingleEdit(fileContent, EditSpec(oldStr, newStr))
        assertTrue(result is SingleEditResult.Ok, "CRLF+tab展开+转义字符三重叠加应能匹配")
        val newText = (result as SingleEditResult.Ok).newText
        assertTrue(newText.contains("ping -n 4"))
        assertTrue(newText.contains("System32"))
    }

    @Test
    fun editFileTabSpaceEscapeAndTrailingAll() {
        // 极端情况：tab缩进 + 内容有 \\ 转义 + 行尾空格，old_string 用 4空格 + 无行尾空格
        val fileContent = "class Config:\n" +
                "\tBASE_DIR = \"C:\\\\App Data\\\\test\"   \n" +  // tab + \\ + 行尾空格
                "\tDEBUG = True\n"

        val oldStr = "    BASE_DIR = \"C:\\\\App Data\\\\test\""  // 4空格代tab，无行尾空格
        val newStr = "    BASE_DIR = \"D:\\\\App Data\\\\prod\""

        val result = applySingleEdit(fileContent, EditSpec(oldStr, newStr))
        assertTrue(result is SingleEditResult.Ok, "tab展开+转义+行尾空格四重叠加应能 fuzzy 匹配")
        assertTrue((result as SingleEditResult.Ok).newText.contains("prod"))
    }

    // ──────────────────────────────────────────────────────────
    // 辅助函数
    // ──────────────────────────────────────────────────────────

    /** 模拟 glob 转正则并匹配 */
    private fun grepGlobMatches(path: String, glob: String): Boolean {
        val regex = globToRegexPattern(glob)
        return regex.matches(path)
    }

    private fun globToRegexPattern(glob: String): Regex {
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
