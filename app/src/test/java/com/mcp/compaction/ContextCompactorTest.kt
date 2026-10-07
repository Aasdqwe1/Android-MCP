package com.mcp.compaction

import com.mcp.llm.ChatMessage
import com.mcp.llm.ToolCall
import com.mcp.llm.ToolCallFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContextCompactor 单元测试：覆盖 deepseek-harness 移植的四项核心机制——
 * tool-pairing 平衡切点、模型感知阈值、token 预算保留、收敛/收缩验证，以及溢出分类。
 */
class ContextCompactorTest {

    private val compactor = ContextCompactor()

    // ── 消息构造辅助 ──────────────────────────────────────────────

    private fun userMsg(text: String) = ChatMessage(role = "user", content = text)

    private fun assistantMsg(text: String) = ChatMessage(role = "assistant", content = text)

    private fun toolCallMsg(id: String, name: String = "run_bash", args: String = "{}") = ChatMessage(
        role = "assistant",
        toolCalls = listOf(ToolCall(id, "function", ToolCallFunction(name, args)))
    )

    private fun toolMsg(id: String, content: String) = ChatMessage(
        role = "tool",
        content = content,
        toolCallId = id
    )

    /** 构造一轮「用户 → 工具调用 → 工具结果 → 回答」的合法工具会话。 */
    private fun toolRound(round: Int): List<ChatMessage> = listOf(
        userMsg("用户问题 $round"),
        toolCallMsg("c$round", "run_bash", """{"script":"echo $round"}"""),
        toolMsg("c$round", "工具结果 $round"),
        assistantMsg("回答 $round")
    )

    // ── estimateTokens ─────────────────────────────────────────────

    @Test
    fun estimateTokens_accountsForContentAndOverhead() {
        val one = compactor.estimateTokens(listOf(userMsg("a".repeat(120))))
        assertEquals(52, one) // 120/3 + 12
        val two = compactor.estimateTokens(listOf(userMsg("a".repeat(120)), assistantMsg("b".repeat(60))))
        assertTrue(two > one)
        // 工具调用参数计入
        val withTool = compactor.estimateTokens(
            listOf(ChatMessage(role = "assistant", toolCalls = listOf(ToolCall("1", "function", ToolCallFunction("read_file", "path=/x")))))
        )
        assertTrue(withTool > 12)
    }

    // ── toolPairingBalanced ─────────────────────────────────────────

    @Test
    fun balancedCut_neverSplitsToolPair() {
        val msgs = listOf(
            userMsg("u1"),
            toolCallMsg("c1", "read_file", "{}"),
            toolMsg("c1", "result")
        )
        // 切在 assistant(tool_calls) 之后 = 切断调用/结果对 → 不平衡
        assertFalse(compactor.toolPairingBalanced(msgs, 2))
        // 切在调用之前 / 完整列表末尾 = 平衡
        assertTrue(compactor.toolPairingBalanced(msgs, 1))
        assertTrue(compactor.toolPairingBalanced(msgs, 3))
        assertTrue(compactor.toolPairingBalanced(msgs, 0))
    }

    @Test
    fun balancedCut_handlesParallelCalls() {
        // 一次调用两个工具：assistant(tool_calls=2) + 2 条 tool 结果
        val msgs = listOf(
            userMsg("u"),
            ChatMessage(
                role = "assistant",
                toolCalls = listOf(
                    ToolCall("a", "function", ToolCallFunction("read_file", "{}")),
                    ToolCall("b", "function", ToolCallFunction("glob", "{}"))
                )
            ),
            toolMsg("a", "r1"),
            toolMsg("b", "r2")
        )
        // 只有两条 tool 都闭合后切点才平衡
        assertFalse(compactor.toolPairingBalanced(msgs, 3))
        assertTrue(compactor.toolPairingBalanced(msgs, 4))
    }

    @Test
    fun balancedCut_rejectsOrphanToolMessage() {
        // 孤立 tool 消息（无前驱调用）→ 非法序列，任何切点都不平衡
        val msgs = listOf(userMsg("u"), toolMsg("x", "orphan"))
        assertFalse(compactor.toolPairingBalanced(msgs, 2))
    }

    // ── selectRetainedTail ──────────────────────────────────────────

    @Test
    fun retainTail_declinesWhenTooShort() {
        val msgs = (0 until 5).map { userMsg("m$it") }
        assertNull(compactor.selectRetainedTail(msgs, 50))
    }

    @Test
    fun retainTail_declinesOpenToolCallAtTail() {
        val msgs = (0 until 12).flatMap { if (it == 11) listOf(toolCallMsg("open", "x", "{}")) else listOf(userMsg("m$it")) }
        assertNull(compactor.selectRetainedTail(msgs, 50))
    }

    @Test
    fun retainTail_floorAppliesWhenBudgetIsPlentiful() {
        // 短消息：预算宽裕时条数地板兜底，至少保留 minRetain 条
        val msgs = (0 until 20).map { userMsg("m$it") }
        val sel = compactor.selectRetainedTail(msgs, 1_000_000)
            ?: throw AssertionError("预算宽裕时应能选出可压缩范围")
        assertEquals(8, sel.retained.size) // 条数地板
        assertEquals(12, sel.shadowed.size)
        // 切点必须平衡（全用户消息 → 平凡平衡）
        assertTrue(compactor.toolPairingBalanced(msgs, sel.shadowed.size))
    }

    @Test
    fun retainTail_tokenBudgetWinsOverFloor_forLongMessages() {
        // 回归：日志中的「降级截断 55129→53083 几乎无效」根因——8 条长思考消息 ≈ 数万 tokens，
        // 旧实现条数地板（至少 8 条）压倒 token 预算，压缩/降级只切掉极少数。
        // 新语义：单条消息很长时，token 预算优先于条数地板。
        val longMsg = "x".repeat(20_000) // ≈ 6.6k tokens/条
        val msgs = (0 until 12).map { userMsg(longMsg) }
        val retainTokens = 4_000 // 预算只能覆盖不到 1 条长消息
        val sel = compactor.selectRetainedTail(msgs, retainTokens)!!
        // 预算优先：保留条数应远小于 8 条地板（这里最多 1 条），且切点仍平衡
        assertTrue(sel.retained.size <= 2)
        assertTrue(sel.shadowed.size >= 10)
        assertTrue(compactor.toolPairingBalanced(msgs, sel.shadowed.size))
    }

    @Test
    fun retainTail_cutIsAlwaysBalanced_acrossToolRounds() {
        val msgs = (0 until 3).flatMap { toolRound(it) } // 12 条
        val sel = compactor.selectRetainedTail(msgs, 50)
            ?: throw AssertionError("工具会话应能选出可压缩范围")
        // 关键不变量：切点平衡，保留尾部不以 tool 消息开头（无孤立 tool）
        assertTrue(compactor.toolPairingBalanced(msgs, sel.shadowed.size))
        assertNotEquals("tool", sel.retained.first().role)
        // 保留尾部自身也必须平衡
        assertTrue(compactor.toolPairingBalanced(sel.retained, sel.retained.size))
    }

    @Test
    fun retainTail_respectsTokenBudget() {
        // 长消息在前、短消息在后：token 预算应能越过部分旧消息
        val msgs = (0 until 12).map {
            if (it < 4) userMsg("x".repeat(2000)) else userMsg("m$it")
        }
        // 预算取 4 条短消息的 token 左右，确保 keepFrom 由预算决定（> 条数地板）
        val retainTokens = compactor.estimateTokens((4 until 12).map { msgs[it] })
        val sel = compactor.selectRetainedTail(msgs, retainTokens)!!
        assertTrue(sel.shadowed.size >= 4)
        assertTrue(compactor.toolPairingBalanced(msgs, sel.shadowed.size))
    }

    @Test
    fun retainTail_nullWhenConversationTooShort() {
        // 12 条仍触发地板保留（预算极大时保留最近 8 条），而真正太短的对话返回 null
        val short = (0 until 5).map { userMsg("m$it") }
        assertNull(compactor.selectRetainedTail(short, 50))
        // 预算极大时不再返回 null：地板保证至少保留 minRetain 条并压缩其余
        val msgs = (0 until 12).map { userMsg("m$it") }
        val sel = compactor.selectRetainedTail(msgs, 1_000_000)!!
        assertEquals(8, sel.retained.size)
    }

    // ── pruneToolResults ────────────────────────────────────────────

    @Test
    fun prune_truncatesOversizedToolResultHeadTail() {
        // 阈值已对齐 dsh DEFAULTS：8192 触发 / 头 4096 / 尾 1024
        val long = "x".repeat(20_000)
        val msgs = mutableListOf(userMsg("u"), toolMsg("c1", long), assistantMsg("a"))
        compactor.pruneToolResults(msgs)
        val pruned = msgs[1].content!!
        assertTrue(pruned.length < 20_000)
        assertTrue(pruned.startsWith("x".repeat(4_096)))  // 头
        assertTrue(pruned.endsWith("x".repeat(1_024)))    // 尾
        assertTrue(pruned.contains("已截断"))
    }

    @Test
    fun prune_doesNotTouchResultsUnderThreshold() {
        // 8192 阈值下，5000 字符的结果应完整保留（旧阈值 3000 会误剪）
        val msgs = mutableListOf(userMsg("u"), toolMsg("c1", "y".repeat(5_000)), assistantMsg("a"))
        compactor.pruneToolResults(msgs)
        assertEquals(5_000, msgs[1].content!!.length)
    }

    @Test
    fun prune_neverSplitsSurrogatePairs() {
        // 😀 是增补平面字符，UTF-16 占 2 个 code unit。
        // 若按 String.length 截取，可能在代理对中间下刀，产出非法字符串。
        val emoji = "😀"
        val long = emoji.repeat(9_000) // 9000 个 code point > 8192 阈值
        val msgs = mutableListOf(toolMsg("c1", long))
        compactor.pruneToolResults(msgs)
        val pruned = msgs[0].content!!
        // 按 code point 计数：头 4096 + 尾 1024
        assertTrue(pruned.startsWith(emoji.repeat(4_096)))
        assertTrue(pruned.endsWith(emoji.repeat(1_024)))
        // 关键：结果必须是合法字符串（无孤立代理项）
        var i = 0
        while (i < pruned.length) {
            val cp = pruned.codePointAt(i)
            assertTrue("出现孤立代理项（代理对被切断）", !Character.isSurrogate(pruned[i]) || Character.isHighSurrogate(pruned[i]))
            i += Character.charCount(cp)
        }
    }

    @Test
    fun codePointLength_countsSupplementaryPlaneAsOne() {
        assertEquals(1, compactor.codePointLength("😀"))
        assertEquals(2, compactor.codePointLength("a😀"))
        // 与 UTF-16 length 的差别正是修复点
        assertNotEquals("😀".length, compactor.codePointLength("😀"))
    }

    @Test
    fun prune_leavesShortAndNonToolMessagesUntouched() {
        val msgs = mutableListOf(userMsg("u"), toolMsg("c1", "short"), assistantMsg("a"))
        compactor.pruneToolResults(msgs)
        assertEquals("short", msgs[1].content)
        assertEquals("u", msgs[0].content)
    }

    // ── buildCheckpoint ─────────────────────────────────────────────

    @Test
    fun checkpoint_replacesNotAppends() {
        val sys = ChatMessage(role = "system", content = "sys")
        val retained = listOf(userMsg("新消息"))
        val out = compactor.buildCheckpoint(sys, "摘要内容", retained)
        assertEquals(4, out.size)
        assertEquals("system", out[0].role)
        assertEquals("user", out[1].role)
        assertTrue(out[1].content!!.contains("<compacted-summary>"))
        assertTrue(out[1].content!!.contains("摘要内容"))
        assertEquals("assistant", out[2].role)
        assertEquals("新消息", out[3].content)
    }

    @Test
    fun checkpointTokens_growsWithSummaryLength() {
        val small = compactor.checkpointTokens("短")
        val large = compactor.checkpointTokens("长".repeat(500))
        assertTrue(large > small)
    }

    // ── renderForSummary ────────────────────────────────────────────

    @Test
    fun render_includesAllRolesAndBoundsArgs() {
        val msgs = listOf(
            userMsg("问题"),
            toolCallMsg("c1", "run_bash", "x".repeat(5000)),
            toolMsg("c1", "结果"),
            assistantMsg("回答")
        )
        val text = compactor.renderForSummary(msgs)
        assertTrue(text.contains("[用户]"))
        assertTrue(text.contains("问题"))
        assertTrue(text.contains("[助手-工具调用]"))
        assertTrue(text.contains("run_bash"))
        assertTrue(text.contains("[工具结果]"))
        assertTrue(text.contains("[助手]"))
        // 超长参数被截断（500 字符上限），不会把 5000 字符全量塞进摘要输入
        assertFalse(text.contains("x".repeat(4000)))
    }

    // ── 模型感知阈值 ────────────────────────────────────────────────

    @Test
    fun contextWindow_resolvesKnownModelsAndFallsBack() {
        assertEquals(128_000, ContextCompactor.contextWindowFor("gpt-4o"))
        assertEquals(128_000, ContextCompactor.contextWindowFor("gpt-4o-mini"))
        assertEquals(8_192, ContextCompactor.contextWindowFor("gpt-4"))
        assertEquals(16_384, ContextCompactor.contextWindowFor("gpt-3.5-turbo"))
        assertEquals(65_536, ContextCompactor.contextWindowFor("deepseek-chat"))
        assertEquals(200_000, ContextCompactor.contextWindowFor("claude-sonnet-4-20250514"))
        assertEquals(32_768, ContextCompactor.contextWindowFor("llama3.1:8b"))
        // nemotron：3-ultra 系列为 1M 窗口，其它 nemotron 回退 128k
        assertEquals(1_048_576, ContextCompactor.contextWindowFor("nemotron-3-ultra-free"))
        assertEquals(131_072, ContextCompactor.contextWindowFor("nemotron-70b-instruct"))
        // 未知模型回退 128k 默认窗口
        assertEquals(131_072, ContextCompactor.contextWindowFor(null))
        assertEquals(131_072, ContextCompactor.contextWindowFor("some-unknown-model"))
    }

    @Test
    fun threshold_isRatioTimesWindow() {
        assertEquals(102_400, compactor.thresholdTokens("gpt-4o"))          // 128000 × 0.8
        assertEquals(26_214, compactor.thresholdTokens("llama3.1:8b"))      // 32768 × 0.8
        assertEquals(20_480, compactor.retainTokensFor("gpt-4o"))           // 128000 × 0.16
    }

    @Test
    fun threshold_usesUserOverrideWhenProvided() {
        // 设置页「上下文窗口」手填值 >0 时覆盖模型表/默认推断
        assertEquals(51_200, compactor.thresholdTokens("gpt-4o", 64_000))            // 覆盖 64k × 0.8
        assertEquals(26_214, compactor.thresholdTokens("some-unknown-model", 32_768)) // 未知模型 + 覆盖 32k × 0.8
        assertEquals(20_480, compactor.retainTokensFor("gpt-4o", 128_000))           // 覆盖 128k × 0.16
        // 0（自动）与负数等同未覆盖
        assertEquals(102_400, compactor.thresholdTokens("gpt-4o", 0))
        assertEquals(102_400, compactor.thresholdTokens("gpt-4o", -1))
    }

    @Test
    fun threshold_capsByMaxInputOverride() {
        // 只填窗口：窗口×0.8
        assertEquals(102_400, compactor.thresholdTokens("gpt-4o", 0, 0))
        // 窗口 + 最大输入：取较小者（输入部分给输出/思考留预算）
        assertEquals(32_000, compactor.thresholdTokens("gpt-4o", 0, 32_000))       // min(102400, 32000)
        assertEquals(51_200, compactor.thresholdTokens("gpt-4o", 64_000, 51_200))  // min(51200, 51200)
        // 最大输入比窗口比例还大：不影响
        assertEquals(102_400, compactor.thresholdTokens("gpt-4o", 0, 200_000))
        // 未知模型 + 窗口覆盖 + 最大输入
        assertEquals(24_000, compactor.thresholdTokens("unknown", 32_768, 24_000)) // min(26214, 24000)
    }

    // ── 溢出分类 ─────────────────────────────────────────────────────

    @Test
    fun overflowClassification_matchesKnownProviderMessages() {
        assertTrue(ContextCompactor.isContextWindowExceededError("This model maximum context length is 128000 tokens"))
        assertTrue(ContextCompactor.isContextWindowExceededError("context_length_exceeded maximum context length"))
        assertTrue(ContextCompactor.isContextWindowExceededError("input is too long for this model"))
        assertTrue(ContextCompactor.isContextWindowExceededError("input exceeds the model context window limit"))
        assertTrue(ContextCompactor.isContextWindowExceededError("request too large for model context"))
        assertTrue(ContextCompactor.isContextWindowExceededError("context window exceeded"))
        // SenseNova 网关对超大请求的通用拒收文案（无 context/length 字样）
        assertTrue(ContextCompactor.isContextWindowExceededError("HTTP 400: inference request is invalid"))
        assertTrue(ContextCompactor.isContextWindowExceededError("The request is invalid"))
    }

    @Test
    fun summaryMaxTokens_takesConfiguredValueVerbatim() {
        // 设置值原样使用，不做任何保底抬升
        assertEquals(65_536, ContextCompactor.summaryMaxTokens(65_536))
        assertEquals(1_024, ContextCompactor.summaryMaxTokens(1_024))
        // 仅当配置为 0（未设置 / 表示不限制）时回退
        assertEquals(ContextCompactor.DEFAULT_SUMMARY_MAX_TOKENS, ContextCompactor.summaryMaxTokens(0))
    }

    @Test
    fun overflowClassification_doesNotMatchUnrelatedErrors() {
        assertFalse(ContextCompactor.isContextWindowExceededError("context window size must be positive"))
        assertFalse(ContextCompactor.isContextWindowExceededError("invalid input: temperature exceeds maximum allowed value"))
        assertFalse(ContextCompactor.isContextWindowExceededError("HTTP 401: unauthorized"))
        assertFalse(ContextCompactor.isContextWindowExceededError(""))
    }

    // ── 端到端：一次完整压缩流程的切点不变量 ─────────────────────────

    @Test
    fun fullSelectionKeepsBalancedInvariant() {
        // 5 轮工具会话（20 条非 system 消息）
        val msgs = (0 until 5).flatMap { toolRound(it) }
        val sel = compactor.selectRetainedTail(msgs, compactor.retainTokensFor("gpt-4o")) ?: return
        // 无论保留多少，切点与保留尾部都必须平衡
        assertTrue(compactor.toolPairingBalanced(msgs, sel.shadowed.size))
        assertTrue(compactor.toolPairingBalanced(sel.retained, sel.retained.size))
        assertEquals(msgs.size, sel.shadowed.size + sel.retained.size)
        // 替换后整体（含检查点）不引入孤立 tool
        val replacement = compactor.buildCheckpoint(
            ChatMessage(role = "system", content = "sys"),
            "## 核心请求与目标\n- 完成测试",
            sel.retained
        )
        assertTrue(compactor.toolPairingBalanced(replacement, replacement.size))
    }

    // ══ 对齐 deepseek-harness 补充的结构性机制 ══════════════════════

    /** 断言抛出指定异常；未抛出或类型不符均失败（不依赖 JUnit 版本）。 */
    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("期望抛出 ${T::class.java.simpleName}，但没有抛出")
    }

    /** 5 轮工具会话（20 条）下的一个合法选择，用于稳定性/收缩测试。 */
    private fun selectionOf20(): Pair<List<ChatMessage>, ContextCompactor.RetainedSelection> {
        val msgs = (0 until 5).flatMap { toolRound(it) }
        val sel = compactor.selectRetainedTail(msgs, 50)
            ?: throw AssertionError("5 轮工具会话应能选出可压缩范围")
        return msgs to sel
    }

    // ── 双向 tool-pairing 边界（对齐 dsh Before/After） ───────────────

    @Test
    fun balancedBeforeAndAfterAreOffsetByOne() {
        // [user, toolCall(c1), tool(c1), assistant] → 切点 0 平衡；切点 1（截断在调用后）不平衡
        val msgs = listOf(
            userMsg("问题"),
            toolCallMsg("c1"),
            toolMsg("c1", "结果"),
            assistantMsg("回答")
        )
        assertTrue(compactor.isBalancedBefore(msgs, 0))
        assertFalse(compactor.isBalancedBefore(msgs, 2))   // 调用已发起、结果未回
        assertTrue(compactor.isBalancedBefore(msgs, 3))    // 结果已回
        // After(i) == Before(i+1)：同一条边界的两种说法
        assertTrue(compactor.isBalancedAfter(msgs, 0))
        assertFalse(compactor.isBalancedAfter(msgs, 1))
        assertEquals(
            compactor.isBalancedBefore(msgs, 2),
            compactor.isBalancedAfter(msgs, 1)
        )
    }

    // ── 稳定性校验（对齐 dsh assertSelectedSpanStable） ───────────────

    @Test
    fun spanStable_passesWhenNothingChanged() {
        val (msgs, sel) = selectionOf20()
        compactor.assertSpanStable(msgs, sel) // 不应抛出
    }

    @Test
    fun spanStable_passesWhenTailAppendedDuringSummarization() {
        val (msgs, sel) = selectionOf20()
        // dsh selected-span 语义：范围外新增节点保持可见，不算失效
        val grown = msgs + userMsg("摘要期间新发的消息")
        compactor.assertSpanStable(grown, sel) // 不应抛出
    }

    @Test
    fun spanStable_throwsWhenSpanRewritten() {
        val (msgs, sel) = selectionOf20()
        val rewritten = listOf(userMsg("历史被编辑了")) + msgs.drop(1)
        expectThrows<SurfaceChangedException> { compactor.assertSpanStable(rewritten, sel) }
    }

    @Test
    fun spanStable_throwsWhenListShrunkBelowRange() {
        val (msgs, sel) = selectionOf20()
        val shrunk = msgs.take(1)
        expectThrows<SurfaceChangedException> { compactor.assertSpanStable(shrunk, sel) }
    }

    // ── 收缩验证（对齐 dsh shrink 检查） ─────────────────────────────

    @Test
    fun validateShrink_rejectsSummaryLargerThanShadowed() {
        val shadowedTokens = 100
        val hugeSummary = "长".repeat(10_000)
        expectThrows<IllegalArgumentException> {
            compactor.validateShrink(hugeSummary, shadowedTokens)
        }
    }

    @Test
    fun validateShrink_acceptsSmallerSummary() {
        val shadowedTokens = 10_000
        compactor.validateShrink("## 核心请求与目标\n- 很短的摘要", shadowedTokens) // 不应抛出
    }

    // ── 选择结果携带显式 range ───────────────────────────────────────

    @Test
    fun selectionRangeMatchesShadowedSlice() {
        val (msgs, sel) = selectionOf20()
        assertEquals(0, sel.shadowedRange.first)
        assertEquals(sel.shadowed.size - 1, sel.shadowedRange.last)
        // 按区间回取，必须与原切片一致（事务提交阶段依赖此不变量）
        assertEquals(sel.shadowed, msgs.subList(sel.shadowedRange.first, sel.shadowedRange.last + 1))
        assertEquals(sel.retained, msgs.subList(sel.shadowedRange.last + 1, msgs.size))
    }

    // ── token 计量校准（对齐 dsh token-meter 的双轨） ─────────────────

    @Test
    fun calibrate_firstSampleAdoptedDirectly() {
        val c = ContextCompactor()
        assertFalse(c.isCalibrated)
        // provider 报 300，启发式算 100 → 系数应为 3
        c.calibrate(promptTokens = 300, heuristicTokens = 100)
        assertTrue(c.isCalibrated)
        assertEquals(3.0, c.tokenScaleValue, 1e-6)
    }

    @Test
    fun calibrate_emaConvergesTowardRatioNotJump() {
        val c = ContextCompactor()
        c.calibrate(100, 100)              // 首个样本：系数 = 1.0
        assertEquals(1.0, c.tokenScaleValue, 1e-6)
        c.calibrate(200, 100)              // 比值 2.0，EMA(α=0.3) 只拉动三成
        assertEquals(1.3, c.tokenScaleValue, 1e-6)
        for (i in 0 until 50) c.calibrate(200, 100)
        assertEquals(2.0, c.tokenScaleValue, 1e-3)   // 反复观测后收敛到真实比值
    }

    @Test
    fun calibrate_clampsExtremeRatios() {
        val c = ContextCompactor()
        c.calibrate(promptTokens = 100_000, heuristicTokens = 100)   // 比值 1000
        assertEquals(ContextCompactor.MAX_TOKEN_SCALE, c.tokenScaleValue, 1e-6)
        val c2 = ContextCompactor()
        c2.calibrate(promptTokens = 1, heuristicTokens = 10_000)     // 比值 0.0001
        assertEquals(ContextCompactor.MIN_TOKEN_SCALE, c2.tokenScaleValue, 1e-6)
    }

    @Test
    fun calibrate_ignoresNonPositiveInputs() {
        val c = ContextCompactor()
        c.calibrate(0, 100)
        c.calibrate(100, 0)
        c.calibrate(-5, 100)
        assertFalse(c.isCalibrated)   // 无效样本不得污染系数，也不得计入学到的样本数
        assertEquals(1.0, c.tokenScaleValue, 1e-6)
    }

    @Test
    fun rawEstimateStaysUncalibratedToAvoidFeedbackLoop() {
        val c = ContextCompactor()
        val msgs = listOf(userMsg("a".repeat(300)))
        val raw = c.rawEstimateTokens(msgs)
        c.calibrate(promptTokens = raw * 2, heuristicTokens = raw)   // 系数 → 2.0
        // 未校准口径必须纹丝不动：calibrate 的分母若用已校准值，比值恒为 1，校准会空转
        assertEquals(raw, c.rawEstimateTokens(msgs))
        assertEquals((raw * 2.0).toInt(), c.estimateTokens(msgs))
    }

    @Test
    fun calibrate_scalesToolsEstimateToo() {
        val c = ContextCompactor()
        val tools = listOf(
            com.mcp.toolbox.ToolDef(
                name = "read_file",
                description = "读取文件内容",
                handler = { _: kotlinx.serialization.json.JsonObject -> "{}" }
            )
        )
        val raw = c.rawEstimateToolsTokens(tools)
        assertTrue(raw > 0)
        assertEquals(raw, c.estimateToolsTokens(tools))          // 未校准时两者相等
        c.calibrate(promptTokens = raw * 2, heuristicTokens = raw)
        assertEquals((raw * 2.0).toInt(), c.estimateToolsTokens(tools))
    }

    // ── DeepSeek session rotation 审查修复（2026-02） ─────────────────

    @Test
    fun shouldRotateDeepSeek_usesThisSessionPromptTokensOnly() {
        val c = ContextCompactor()
        // 阈值未配置（0）→ 永不轮转：窗口未知时宁可交给溢出恢复，也不猜窗口提前轮转
        assertFalse(c.shouldRotateDeepSeek(lastPromptTokens = 999_999, threshold = 0))
        // 未达阈值 → 不轮转
        assertFalse(c.shouldRotateDeepSeek(lastPromptTokens = 79_999, threshold = 80_000))
        // 达到/超过阈值 → 轮转（>=，服务端上报等于阈值时上下文已满）
        assertTrue(c.shouldRotateDeepSeek(lastPromptTokens = 80_000, threshold = 80_000))
        assertTrue(c.shouldRotateDeepSeek(lastPromptTokens = 120_000, threshold = 80_000))
        // 尚无上报值（0）→ 不轮转：0 代表「本会话还没有数据」，
        // 不能沿用别的会话的旧数值（那是全局变量版本的真实 bug）
        assertFalse(c.shouldRotateDeepSeek(lastPromptTokens = 0, threshold = 80_000))
    }

    @Test
    fun renderForSummary_canSkipSystemAndKeepsToolCalls() {
        val messages = listOf(
            ChatMessage(role = "system", content = "系统提示词"),
            userMsg("帮我看一下 /a.txt"),
            toolCallMsg("c1", "read_file", """{"path":"/a.txt"}"""),
            toolMsg("c1", "文件内容"),
            assistantMsg("读完了")
        )
        val withSystem = compactor.renderForSummary(messages)
        assertTrue(withSystem, withSystem.contains("[系统]"))
        assertTrue(withSystem, withSystem.contains("[助手-工具调用]"))
        assertTrue(withSystem, withSystem.contains("read_file({\"path\":\"/a.txt\"})"))
        assertTrue(withSystem, withSystem.contains("[工具结果]"))

        // rotation 场景：system 会被同一条消息里的 system + 工具清单覆盖，必须跳过
        val withoutSystem = compactor.renderForSummary(messages, includeSystem = false)
        assertFalse(withoutSystem, withoutSystem.contains("[系统]"))
        // 工具调用绝不能丢：原实现只取 content/reasoning，assistant(tool_calls) 会被整条跳过
        assertTrue(withoutSystem, withoutSystem.contains("[助手-工具调用]"))
        assertTrue(withoutSystem, withoutSystem.contains("read_file"))
    }

    @Test
    fun rotationNeedsSystemReinject_onlyForNonFirstMessageWithTools() {
        // 轮转通常发生在「非首条消息」——新服务端会话必须补注入 system + 工具清单
        assertTrue(compactor.rotationNeedsSystemReinject(isFirstMessage = false, hasTools = true))
        // 首条消息里已经注入过，重复注入会让模型看到两份协议指南
        assertFalse(compactor.rotationNeedsSystemReinject(isFirstMessage = true, hasTools = true))
        // 没有工具就没有工具清单，无需注入
        assertFalse(compactor.rotationNeedsSystemReinject(isFirstMessage = false, hasTools = false))
    }
}
