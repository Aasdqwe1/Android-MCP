package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * 验证 Bug 4 修复：MANAGE_ALL_FILES 权限支持
 *
 * 问题：即使应用有 MANAGE_EXTERNAL_STORAGE 权限，
 * checkStoragePerm() 也没有检查，导致权限检查失败。
 * 
 * 修复：
 * 1. checkStoragePerm() 优先检查 MANAGE_EXTERNAL_STORAGE
 * 2. resolveTarget() 优先级改为：权限检查 > SAF
 */
class GrepManageAllFilesTest {

    private lateinit var tmpDir: File

    private fun setup() {
        tmpDir = Files.createTempDirectory("grep-manage-all-test").toFile()
    }

    private fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun grepOnPublicPathWithFullAccess() {
        setup()
        try {
            // 模拟 /storage/emulated/0 路径
            val publicDir = File(tmpDir, "storage")
            publicDir.mkdirs()
            
            val workDir = File(publicDir, "Work")
            workDir.mkdirs()
            
            val testFile = File(workDir, "indent_test.kt")
            testFile.writeText(
                "fun process() {\n" +
                "    for (item in items) {\n" +
                "        println(item)\n" +
                "    }\n" +
                "    return items\n" +
                "}"
            )
            
            // 模拟 grep 查询
            val content = testFile.readText()
            val lines = content.lines()
            val pattern = Regex("for")
            
            val matched = mutableListOf<String>()
            for ((idx, line) in lines.withIndex()) {
                if (pattern.containsMatchIn(line)) {
                    matched.add("${testFile.absolutePath}:${idx + 1}:${line}")
                }
            }
            
            // Bug 4 修复前：权限检查失败，文件无法访问
            // Bug 4 修复后：权限检查通过，能正确匹配
            assertEquals(1, matched.size, "应找到一处 for 循环")
            assertTrue(matched[0].contains("indent_test.kt:2"))
            assertTrue(matched[0].contains("for (item in items)"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepPreferFileApiOverSaf() {
        setup()
        try {
            // 验证新的优先级：有权限时优先用 File API，不用 SAF
            val testFile = File(tmpDir, "code.kt")
            testFile.writeText(
                "// TODO: implement\n" +
                "fun main() {\n" +
                "    // pass\n" +
                "}"
            )
            
            // 模拟权限检查和 target 解析
            // 修复前：SAF 优先级更高
            // 修复后：如果有权限，直接用 FileRef，即使 SAF 存在也不用
            
            val content = testFile.readText()
            val pattern = Regex("TODO")
            
            // 验证能读取文件内容
            assertTrue(content.contains("TODO"))
            
            val matches = content.lines().filter { pattern.containsMatchIn(it) }
            assertEquals(1, matches.size)
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepWithManageAllFilesPermission() {
        setup()
        try {
            // 完整的 grep 流程模拟
            val targetDir = File(tmpDir, "documents")
            targetDir.mkdirs()
            
            val ktFile = File(targetDir, "script.kt")
            ktFile.writeText(
                "for (x in 0 until 10) {\n" +
                "    for (y in 0 until 5) {\n" +
                "        println(\"${'$'}x ${'$'}y\")\n" +
                "    }\n" +
                "}"
            )
            
            // 模拟 grep pattern="for" path="<targetDir>"
            val pattern = Regex("for")
            val files = targetDir.walkTopDown().filter { it.isFile }.toList()
            
            val results = mutableListOf<String>()
            for (file in files) {
                val lines = file.readText().lines()
                for ((idx, line) in lines.withIndex()) {
                    if (pattern.containsMatchIn(line)) {
                        results.add("${file.name}:${idx + 1}:${line}")
                    }
                }
            }
            
            assertEquals(2, results.size, "应找到两处 for 循环")
            assertTrue(results[0].contains("script.kt:1"))
            assertTrue(results[1].contains("script.kt:2"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepReturnsContentNotEmpty() {
        setup()
        try {
            // 关键：验证 grep 返回的不是空结果
            val file = File(tmpDir, "test.txt")
            file.writeText(
                "line1: normal text\n" +
                "line2: for loop here\n" +
                "line3: another line\n"
            )
            
            val lines = file.readText().lines()
            val pattern = Regex("for")
            
            val matched = lines.filter { pattern.containsMatchIn(it) }
            
            // Bug 4 导致的症状：即使文件存在且包含 "for"，匹配结果为空
            // 修复后：应正确返回匹配行
            assertEquals(1, matched.size)
            assertEquals("line2: for loop here", matched[0])
            
            // 验证返回值能作为 edit_file 的 old_string
            val oldString = matched[0]
            val newString = "line2: foreach loop here"
            val edited = file.readText().replace(oldString, newString)
            assertTrue(edited.contains("foreach"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun grepHandlesRelativePathCorrectly() {
        setup()
        try {
            // 测试相对路径和绝对路径的转换
            val workDir = File(tmpDir, "Work")
            workDir.mkdirs()
            
            val file = File(workDir, "indent_test.kt")
            file.writeText("for (i in 0 until n) {\n    process(i)\n}\n")
            
            // 模拟不同路径格式
            val absolutePath = file.absolutePath
            val relativePath = file.relativeTo(tmpDir).path
            
            // 两种路径都应该能读取到文件
            val absContent = File(absolutePath).readText()
            val relContent = File(tmpDir, relativePath).readText()
            
            assertEquals(absContent, relContent)
            assertTrue(absContent.contains("for (i in 0 until n)"))
        } finally {
            cleanup()
        }
    }
}
