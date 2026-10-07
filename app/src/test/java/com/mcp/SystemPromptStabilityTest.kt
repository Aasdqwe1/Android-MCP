package com.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * system_prompt.txt 稳定性测试（仿官方 system_prompt_test.go）：
 * 提示词是 DeepSeek 前缀缓存的一部分，必须字节稳定（无动态内容）、长度可控。
 */
class SystemPromptStabilityTest {

    private fun promptText(): String {
        // gradle 本地测试工作目录 = 模块目录（app/）
        val candidates = listOf(
            File("src/main/assets/system_prompt.txt"),
            File("app/src/main/assets/system_prompt.txt")
        )
        val f = candidates.firstOrNull { it.exists() }
        assertTrue("system_prompt.txt 应存在（候选：${candidates.map { it.path }}，cwd=${File(".").absolutePath}）", f != null)
        return f!!.readText(Charsets.UTF_8)
    }

    // 长度上限：压缩后应显著小于原始长文本（前缀缓存 token 预算）
    @Test
    fun lengthBounded() {
        val t = promptText()
        assertTrue("提示词过长（${t.length} 字符），会挤压前缀缓存预算", t.length <= 8000)
    }

    // 无动态内容：禁止时间戳、会话 id、随机数（保证字节稳定）
    @Test
    fun noDynamicContent() {
        val t = promptText()
        val dynamicPatterns = listOf(
            Regex("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}"),   // 时间戳
            Regex("call_\\d+"),                                // 调用 id
            Regex("session[_ ]?id"),                            // 会话 id
            Regex("[0-9a-f]{40}")                               // 长哈希
        )
        for (p in dynamicPatterns) {
            assertTrue("提示词不应包含动态内容（匹配 $p）", !p.containsMatchIn(t))
        }
    }

    // 关键不变量：必须包含核心段落标题
    @Test
    fun coreSectionsPresent() {
        val t = promptText()
        for (section in listOf("工作流", "核心规则", "文件修改工作流", "多 Agent 协作")) {
            assertTrue("提示词应包含「$section」段", t.contains(section))
        }
    }

    // 演示约定必须与协议指南一致：行首 // 表示「不执行」，正式调用不加前缀。
    // 历史上这里写的是 `#`（旧协议残留），与 XML/行式/PTC 三套指南的 `//` 冲突，
    // 模型照抄 `#` 前缀会把真调用当成演示而不执行。
    @Test
    fun demoPrefixMatchesProtocolGuides() {
        val t = promptText()
        assertTrue("应声明 // 前缀不执行", t.contains("行首加 // 前缀的行不会被系统执行"))
        assertTrue("不应残留 # 前缀说明", !t.contains("行首加 # 前缀"))
    }

    // 换行风格保持一致，避免混用 CRLF 与 LF
    @Test
    fun lineEndingsAreConsistent() {
        val t = promptText()
        val withoutCrlf = t.replace("\r\n", "")
        assertTrue("不应混入孤立 CR", !withoutCrlf.contains("\r"))
        assertTrue("提示词应包含换行", t.contains("\n"))
    }
}
