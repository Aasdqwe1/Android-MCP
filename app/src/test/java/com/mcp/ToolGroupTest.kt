package com.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具分组的回归测试。
 *
 * 分组是纯名字→组 id 的映射，所以这里不构造任何 ToolDef，直接喂名字断言。
 * 关注两件事：
 *  1. **不丢工具**——每个名字都必须落到某个组（最差也是 other），且只落一次；
 *  2. **顺序敏感的前缀规则**真的生效（run_bash_persistent 不该掉进 other）。
 */
class ToolGroupTest {

    /** 取某工具名所属组。找不到该名字时抛错，避免断言静默通过。 */
    private fun groupOf(name: String): String {
        val hit = ToolGroups.groupNames(listOf(name)).firstOrNull() ?: error("未分组: " + name)
        return hit.first
    }

    // ── 前缀规则 ──────────────────────────────────────────────────────

    @Test
    fun `前缀规则按预期命中`() {
        assertEquals("browser", groupOf("browser_navigate"))
        assertEquals("browser", groupOf("browser_screenshot"))
        assertEquals("wechat", groupOf("openclaw_weixin_send_text"))
        assertEquals("skill", groupOf("skill_install"))
        assertEquals("mcp", groupOf("mcp_add_server"))
        assertEquals("async", groupOf("async_task_submit"))
        assertEquals("saf", groupOf("saf_open_directory"))
        assertEquals("build", groupOf("gradle_build"))
        assertEquals("agent", groupOf("agent_workflow"))
        assertEquals("terminal", groupOf("bash_task_kill"))
        assertEquals("terminal", groupOf("persistent_bash_reset"))
    }

    /**
     * 顺序敏感：run_bash_persistent 同时匹配 run_bash 前缀，必须先被 run_bash 规则捕获；
     * 而 persistent_bash_status 走的是另一条前缀。两者都该进终端组，不能落进 other。
     */
    @Test
    fun `run_bash 与 persistent_bash 都归终端组`() {
        assertEquals("terminal", groupOf("run_bash"))
        assertEquals("terminal", groupOf("run_bash_persistent"))
        assertEquals("terminal", groupOf("run_bash_bg"))
        assertEquals("terminal", groupOf("persistent_bash_status"))
        assertEquals("terminal", groupOf("proot_status"))
    }

    // ── 精确名集合 ────────────────────────────────────────────────────

    @Test
    fun `精确名集合按预期命中`() {
        assertEquals("basic", groupOf("ask_user"))
        assertEquals("basic", groupOf("web_search"))
        assertEquals("file", groupOf("multi_edit"))
        assertEquals("search", groupOf("search_and_read"))
        assertEquals("multimodal", groupOf("describe_image"))
        assertEquals("todo", groupOf("import_todos"))
        assertEquals("build", groupOf("lint_check"))
        assertEquals("dependency", groupOf("suggest_updates"))
        assertEquals("logdiff", groupOf("merge_files"))
        assertEquals("root", groupOf("run_root"))
        assertEquals("batch", groupOf("batch_execute"))
        assertEquals("ptc", groupOf("run_code"))
    }

    /** agent_ 前缀覆盖不到的 Agent 工具必须被精确名兜住。 */
    @Test
    fun `delegate 与 list_agents 归 Agent 组`() {
        assertEquals("agent", groupOf("delegate_to_agent"))
        assertEquals("agent", groupOf("list_agents"))
    }

    // ── 兜底与完整性 ──────────────────────────────────────────────────

    @Test
    fun `未识别的名字落到 other 而不是消失`() {
        assertEquals(ToolGroups.OTHER_ID, groupOf("some_future_tool"))
        assertEquals(ToolGroups.OTHER_ID, groupOf(""))
    }

    /**
     * 全量不丢失：把一份「覆盖所有前缀 + 若干精确名」的名字清单丢进去，
     * 断言分组结果的工具总数与输入一致（每个名字恰好出现一次）。
     *
     * 这条是防「规则表写错导致某类工具被 continue 掉」的核心保障——
     * 分组是 UI 的唯一入口，丢一个工具就等于用户在页面上找不到它。
     */
    @Test
    fun `分组不丢工具且不重复`() {
        val names = listOf(
            "calculator", "ask_user", "run_bash", "run_bash_persistent", "persistent_bash_status",
            "bash_task_status", "run_root", "async_task_submit", "list_todos", "read_file",
            "search_files", "read_image", "saf_list_roots", "agent_status", "delegate_to_agent",
            "gradle_build", "lint_check", "suggest_updates", "tail_log", "browser_navigate",
            "openclaw_weixin_logs", "skill_list", "batch_execute", "run_code", "mcp_refresh",
            "some_future_tool",
        )
        val grouped = ToolGroups.groupNames(names).flatMap { it.second }
        assertEquals("分组后总数必须与输入一致（不丢）", names.size, grouped.size)
        assertEquals("不得有工具被分到多个组", names.toSet(), grouped.toSet())
    }

    /** 每组 id 必须在 META 里登记过，否则 group() 会把它整组丢掉。 */
    @Test
    fun `所有前缀规则产出的组 id 都在 META 中`() {
        val known = ToolGroups.allGroupIds().toSet()
        val probes = listOf(
            "browser_x", "openclaw_x", "skill_x", "mcp_x", "async_task_x", "saf_x",
            "gradle_x", "bash_task_x", "persistent_bash_x", "run_bash_x", "agent_x", "unknown_x",
        )
        for (n in probes) {
            assertTrue(
                "组 id 未在 META 登记: " + groupOf(n),
                groupOf(n) in known
            )
        }
    }

    @Test
    fun `includeEmpty 为 false 时不返回空组`() {
        // 只喂一个 basic 工具，其余组都应为空、不出现在结果里
        val groups = ToolGroups.groupNames(listOf("calculator"))
        assertEquals(1, groups.size)
        assertEquals("basic", groups[0].first)
        assertFalse(groups.any { it.second.isEmpty() })
    }

    @Test
    fun `displayName 能取到中文名`() {
        assertEquals("浏览器自动化", ToolGroups.displayName("browser"))
        assertEquals("其它", ToolGroups.displayName(ToolGroups.OTHER_ID))
        // 未知 id 原样返回，不崩
        assertEquals("nope", ToolGroups.displayName("nope"))
    }
}
