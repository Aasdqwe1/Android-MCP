package com.mcp.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预设准入语义测试。
 *
 * 「allowedTools 非空即限制」是极简模式能否生效的关键约定：
 * 一旦这里反了，极简模式就会变成全量模式（或反过来把完整模式锁死）。
 */
class PresetTest {

    @Test
    fun `空白名单不做限制，deny 仍然生效`() {
        val full = Preset(id = "full", name = "完整模式", denyTools = listOf("run_root"))
        assertFalse(full.isRestricted)
        assertTrue(full.allows("run_bash"))
        assertTrue(full.allows("write_file"))
        assertFalse(full.allows("run_root"))
    }

    @Test
    fun `白名单非空时只放行名单内工具`() {
        val minimal = Preset(
            id = "minimal",
            name = "极简模式",
            allowedTools = listOf("run_bash_persistent", "read_file", "edit_file")
        )
        assertTrue(minimal.isRestricted)
        assertTrue(minimal.allows("run_bash_persistent"))
        assertTrue(minimal.allows("edit_file"))
        assertFalse("浏览器工具不应出现在极简模式", minimal.allows("browser_open"))
    }

    @Test
    fun `系统提示词覆盖可为空，空表示沿用默认`() {
        assertEquals(null, Preset(id = "x", name = "x").systemPromptOverride)
        val withPrompt = Preset(id = "y", name = "y", systemPromptOverride = "你是极简编码 Agent")
        assertEquals("你是极简编码 Agent", withPrompt.systemPromptOverride)
    }

    @Test
    fun `新增 ptc-complete-compaction 标志有合理默认值`() {
        // 默认非 PTC、非 complete、开启压缩（与全量模式一致）
        val full = Preset(id = "full", name = "完整模式")
        assertFalse(full.ptc)
        assertFalse(full.complete)
        assertTrue(full.compaction)

        // 极简模式：complete + 关压缩
        val minimal = Preset(
            id = "minimal", name = "极简模式",
            allowedTools = listOf("run_bash_persistent", "read_file", "write_file", "edit_file", "undo_edit"),
            complete = true, compaction = false
        )
        assertTrue(minimal.complete)
        assertFalse(minimal.compaction)

        // PTC 模式：ptc 开启
        val ptc = Preset(
            id = "ptc", name = "PTC 编程模式",
            allowedTools = listOf("run_code", "read_file"),
            ptc = true, complete = true
        )
        assertTrue(ptc.ptc)
        assertTrue(ptc.allows("run_code"))
    }

    @Test
    fun `协议风格是预设字段而非按 id 硬编码`() {
        // 旧实现按 `id == "minimal"` 判定 XML：换个 id 或新增 XML 预设就会静默失效。
        assertEquals("默认行式", "line", Preset(id = "x", name = "x").protocolStyle)
        assertFalse(Preset(id = "x", name = "x").xmlProtocol)
        val custom = Preset(id = "anything", name = "自定义", protocolStyle = "xml")
        assertTrue("与 id 无关，看字段", custom.xmlProtocol)
        assertTrue("大小写不敏感", Preset(id = "y", name = "y", protocolStyle = "XML").xmlProtocol)
    }

    @Test
    fun `PTC 预设声明行式协议风格`() {
        // PTC 模式的注入链（协议风格与预设正交，声明只决定「跟随预设」时的取值）：
        //   ptc.json 的 protocolStyle=line
        //   → PresetRuntime.resolveProtocolStyle 走「跟随预设」返回 LINE
        //   → ChatBridge.currentPtcStyle(sid) 返回 LINE
        //   → ptcRequestContext 注入 PTC_ONLY_RULE + PTC_PROTOCOL_GUIDE。
        // 用户仍可在设置页显式切 XML，那时走另一套注入（见 resolveProtocolStyle 注释）。
        val f = listOf(
            java.io.File("src/main/assets/presets/ptc.json"),
            java.io.File("../app/src/main/assets/presets/ptc.json")
        ).firstOrNull { it.exists() } ?: error("找不到 ptc.json")
        val text = f.readText()
        assertTrue("ptc.json 应为 PTC 预设", text.contains("\"ptc\": true"))
        assertTrue(
            "ptc.json 应声明 protocolStyle=line（PTC 默认行式，用户可在设置页切 XML）",
            Regex("\"protocolStyle\"\\s*:\\s*\"line\"").containsMatchIn(text)
        )
    }

    @Test
    fun `compaction 是预设字段且默认开启`() {
        // 该字段此前只有定义与注释、全仓库无读取点；现在由
        // PresetRuntime.isCompactionEnabled 消费（ChatBridge 的常规压缩入口据此放行）。
        assertTrue("默认开启压缩", Preset(id = "full", name = "完整模式").compaction)
        assertFalse(Preset(id = "minimal", name = "极简模式", compaction = false).compaction)
    }
}
