package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ToolCallGuard 行为契约：畸形信封自愈 + 工具 id 重复分级门禁。
 * 对应此前 agent 侧 Python 守卫的等价 Kotlin 实现（已集成进 :app 的 CompatChatRunner）。
 */
class ToolCallGuardTest {

    // ── 畸形信封自愈 ──────────────────────────────────────────────

    @Test
    fun repairsOrphanFenceAndDuplicateMeta() {
        // 孤儿 >>> + 重复元数据（用户最初贴出的畸形调用）
        val raw = """
            tool_call: run_code
            id: call_34
            code <<<
            var out = {};
            return out;
            >>>
            description: 运行 parity 单元测试
            language: javascript
            timeout_seconds: 290
            >>>
            description: 运行 parity 单元测试
            language: javascript
        """.trimIndent()
        val fixed = ToolCallGuard.repairEnvelope(raw)
        // 只应剩一个闭合 >>>
        assertEquals(1, fixed.lines().count { it.trim() == ">>>" })
        // 元数据只出现一次
        assertEquals(1, fixed.lines().count { it.trim().startsWith("description:") })
        assertEquals(1, fixed.lines().count { it.trim().startsWith("language:") })
        assertTrue(fixed.contains("code <<<"))
        // 重复元数据被归一；id 保留
        assertTrue(fixed.contains("id: call_34"))
    }

    @Test
    fun keepsValidEnvelopeStable() {
        val raw = """
            tool_call: run_code
            id: call_1
            code <<<
            print("ok")
            >>>
            description: ok
            language: python
            timeout_seconds: 30
        """.trimIndent()
        val fixed = ToolCallGuard.repairEnvelope(raw)
        assertEquals(1, fixed.lines().count { it.trim() == ">>>" })
        assertTrue(fixed.contains("id: call_1"))
    }

    // ── id 分级门禁 ───────────────────────────────────────────────

    @Test
    fun blankIdHealsWithWarning() {
        val seen = LinkedHashSet<String>()
        val v = ToolCallGuard.guardId("", seen)
        assertTrue(v.allow)
        assertEquals("call_1", v.fixedId)
        assertTrue(v.warning?.contains("id 缺失") == true)
        assertEquals(setOf("call_1"), seen)
    }

    @Test
    fun uniqueIdPassesThrough() {
        val seen = LinkedHashSet<String>()
        val v = ToolCallGuard.guardId("call_34", seen)
        assertTrue(v.allow)
        assertEquals(null, v.fixedId)
        assertEquals(null, v.error)
        assertEquals(setOf("call_34"), seen)
    }

    @Test
    fun duplicateIdRejectedWithError() {
        val seen = LinkedHashSet<String>()
        ToolCallGuard.guardId("call_34", seen) // 首次合法
        val v = ToolCallGuard.guardId("call_34", seen) // 重复
        assertFalse(v.allow)
        assertTrue(v.error?.startsWith("Error:") == true)
        assertTrue(v.error?.contains("id 重复（call_34）") == true)
    }

    @Test
    fun batchGuardSequence() {
        // 两条调用共用 call_34：第一条通过，第二条因重复被拒
        val verdicts = ToolCallGuard.guardIds(listOf("call_34", "call_34"))
        assertTrue(verdicts[0].allow)
        assertFalse(verdicts[1].allow)
        assertTrue(verdicts[1].error?.contains("call_34") == true)
    }
}
