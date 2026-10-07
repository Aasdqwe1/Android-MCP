package com.mcp.toolbox

import com.mcp.serialization.McpJson
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 重复失败防护测试（对齐官方 repeat_failure_guard.go）。 */
class RepeatFailureGuardTest {

    private fun args(json: String): JsonObject = McpJson.parseToJsonElement(json) as JsonObject

    // 同类锚点失败 2 次后第 3 次阻止
    @Test
    fun blocksAfterTwoFailures() {
        val g = RepeatFailureGuard()
        val a = args("""{"path":"/a/b.txt","old_string":"xxx","new_string":"yyy"}""")
        assertNull(g.shouldBlock("edit_file", a))
        g.recordFailure("edit_file", a, """{"error":"未在文件中找到匹配的 old_string。"}""", "/a/b.txt")
        assertNull(g.shouldBlock("edit_file", a))
        g.recordFailure("edit_file", a, """{"error":"未在文件中找到匹配的 old_string。"}""", "/a/b.txt")
        val block = g.shouldBlock("edit_file", a)
        assertNotNull(block)
        assertTrue(block.contains("重建 old_string"), block)
    }

    // 不同锚点（不同 old_string）互不影响
    @Test
    fun differentAnchorNotBlocked() {
        val g = RepeatFailureGuard()
        val a1 = args("""{"path":"/a/b.txt","old_string":"aaa"}""")
        val a2 = args("""{"path":"/a/b.txt","old_string":"bbb"}""")
        g.recordFailure("edit_file", a1, """{"error":"未在文件中找到匹配的 old_string。"}""", "/a/b.txt")
        g.recordFailure("edit_file", a1, """{"error":"未在文件中找到匹配的 old_string。"}""", "/a/b.txt")
        assertNotNull(g.shouldBlock("edit_file", a1))
        assertNull(g.shouldBlock("edit_file", a2))
    }

    // 错误分类：锚点类 vs 其它
    @Test
    fun errorClassification() {
        assertEquals("old_string_not_found", RepeatFailureGuard.errorClass("edit_file", "未在文件中找到匹配的 old_string。请确保…"))
        assertEquals("old_string_not_found", RepeatFailureGuard.errorClass("edit_file", "…最接近的匹配在第 3 行附近…"))
        assertEquals("old_string_not_unique", RepeatFailureGuard.errorClass("edit_file", "old_string 在文件中出现 3 次（…）"))
        assertEquals("old_string_not_unique", RepeatFailureGuard.errorClass("edit_file", "…模糊匹配到 2 处…"))
        assertEquals("other", RepeatFailureGuard.errorClass("run_bash", "command not found"))
    }

    // 非锚点类失败：写工具成功后按路径清除
    @Test
    fun nonAnchorClearedByMutation() {
        val g = RepeatFailureGuard()
        val a = args("""{"path":"/a/b.txt","content":"x"}""")
        g.recordFailure("write_file", a, """{"error":"文件不存在"}""", "/a/b.txt")
        g.recordFailure("write_file", a, """{"error":"文件不存在"}""", "/a/b.txt")
        assertNotNull(g.shouldBlock("write_file", a))
        g.clearAfterMutation("write_file", a, "/a/b.txt")
        assertNull(g.shouldBlock("write_file", a))
    }

    // 锚点类失败不因其它写入而清除（edit_file 成功后保留）
    @Test
    fun anchorFailureSurvivesOtherMutation() {
        val g = RepeatFailureGuard()
        val a = args("""{"path":"/a/b.txt","old_string":"zzz"}""")
        g.recordFailure("edit_file", a, """{"error":"未在文件中找到匹配的 old_string。"}""", "/a/b.txt")
        g.recordFailure("edit_file", a, """{"error":"未在文件中找到匹配的 old_string。"}""", "/a/b.txt")
        g.clearAfterMutation("write_file", args("""{"path":"/a/b.txt"}"""), "/a/b.txt")
        assertNotNull(g.shouldBlock("edit_file", a))
    }

    // multi_edit：edits 的 old_string 参与签名
    @Test
    fun multiEditSignatureIncludesEdits() {
        val g = RepeatFailureGuard()
        val a1 = args("""{"path":"/a/b.txt","edits":"[{\"old_string\":\"aaa\"}]"}""")
        val a2 = args("""{"path":"/a/b.txt","edits":"[{\"old_string\":\"bbb\"}]"}""")
        g.recordFailure("multi_edit", a1, """{"error":"第 1 个编辑失败"}""", "/a/b.txt")
        g.recordFailure("multi_edit", a1, """{"error":"第 1 个编辑失败"}""", "/a/b.txt")
        assertNotNull(g.shouldBlock("multi_edit", a1))
        assertNull(g.shouldBlock("multi_edit", a2))
    }

    // multi_edit：edits 为原生 JSON 数组时也能正确提取 old_string 参与签名
    @Test
    fun multiEditSignatureWithJsonArray() {
        val g = RepeatFailureGuard()
        val a1 = args("""{"path":"/a/b.txt","edits":[{"old_string":"aaa","new_string":"bbb"}]}""")
        val a2 = args("""{"path":"/a/b.txt","edits":[{"old_string":"bbb","new_string":"ccc"}]}""")
        g.recordFailure("multi_edit", a1, """{"error":"第 1 个编辑失败"}""", "/a/b.txt")
        g.recordFailure("multi_edit", a1, """{"error":"第 1 个编辑失败"}""", "/a/b.txt")
        assertNotNull(g.shouldBlock("multi_edit", a1))
        assertNull(g.shouldBlock("multi_edit", a2))
    }

    // multi_edit：行式编号键 edit_N_old 也能正确提取 old_string 参与签名
    @Test
    fun multiEditSignatureWithLineProtocolKeys() {
        val g = RepeatFailureGuard()
        val a1 = args("""{"path":"/a/b.txt","edit_1_old":"aaa","edit_1_new":"bbb"}""")
        val a2 = args("""{"path":"/a/b.txt","edit_1_old":"ccc","edit_1_new":"ddd"}""")
        g.recordFailure("multi_edit", a1, """{"error":"第 1 个编辑失败"}""", "/a/b.txt")
        g.recordFailure("multi_edit", a1, """{"error":"第 1 个编辑失败"}""", "/a/b.txt")
        assertNotNull(g.shouldBlock("multi_edit", a1))
        assertNull(g.shouldBlock("multi_edit", a2))
    }

    // 路径规范化：不同写法同路径共享签名（对齐 filepath.Clean：相对路径不补前导斜杠）
    @Test
    fun pathNormalization() {
        assertEquals("a/b.txt", RepeatFailureGuard.normalizePath("a//b.txt"))
        assertEquals("a/b.txt", RepeatFailureGuard.normalizePath("./a/b.txt"))
        assertEquals("/a/b.txt", RepeatFailureGuard.normalizePath("\\a\\b.txt"))
    }
}
