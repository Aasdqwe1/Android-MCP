package com.mcp.compaction

import com.mcp.llm.ChatMessage
import com.mcp.llm.ToolCall
import com.mcp.llm.ToolCallFunction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CompactionTransaction 单元测试：覆盖对齐 deepseek-harness 的事务语义——
 * 压缩锁、异步摘要期间的稳定性复检、失败必释放锁、replace 语义取最新尾部。
 *
 * 最核心的一条是 [transactionKeepsMessagesAppendedDuringSummarization]：
 * 摘要是异步的，期间新到的消息必须存活——这正是「选范围 → 摘要 → 替换」朴素实现会丢消息的地方。
 */
class CompactionTransactionTest {

    private val compactor = ContextCompactor()

    private fun userMsg(text: String) = ChatMessage(role = "user", content = text)
    private fun assistantMsg(text: String) = ChatMessage(role = "assistant", content = text)
    private fun toolCallMsg(id: String, name: String = "run_bash", args: String = "{}") =
        ChatMessage(role = "assistant", toolCalls = listOf(ToolCall(id, "function", ToolCallFunction(name, args))))
    private fun toolMsg(id: String, content: String) =
        ChatMessage(role = "tool", content = content, toolCallId = id)

    /** 5 轮合法工具会话（20 条）。 */
    private fun fiveRounds(): MutableList<ChatMessage> =
        (0 until 5).flatMap { r ->
            listOf(
                userMsg("用户问题 $r"),
                toolCallMsg("c$r", "run_bash", """{"script":"echo $r"}"""),
                toolMsg("c$r", "工具结果 $r"),
                assistantMsg("回答 $r")
            )
        }.toMutableList()

    // ── 核心：异步摘要期间新增的消息必须存活 ──────────────────────────

    @Test
    fun transactionKeepsMessagesAppendedDuringSummarization() = runBlocking {
        val msgs = fiveRounds()
        val tx = CompactionTransaction(compactor)
        var applied: List<ChatMessage>? = null

        val outcome = tx.run(
            snapshot = { msgs.toList() },
            apply = { applied = it },
            summarize = { _, _ ->
                // 模拟摘要调用期间用户又发了一条消息（真实场景：秒级 LLM 调用）
                msgs.add(userMsg("摘要期间新发的消息"))
                "## 核心请求与目标\n- 完成对齐测试"
            },
            retainTokens = 50
        )

        assertNotNull(outcome)
        val result = applied!!
        assertTrue("摘要期间新增的消息必须保留，不能被旧切片覆盖",
            result.any { it.content == "摘要期间新发的消息" })
        assertTrue(result.any { it.content!!.contains("<compacted-summary>") })
        // 替换后整体仍是合法的 OpenAI 序列（无孤立 tool 消息）
        assertTrue(compactor.toolPairingBalanced(result, result.size))
    }

    // ── 稳定性：范围被改写则放弃，且锁必须释放 ────────────────────────

    @Test
    fun transactionAbortsAndUnlocksWhenSpanRewritten() = runBlocking {
        val msgs = fiveRounds()
        val tx = CompactionTransaction(compactor)
        var applied: List<ChatMessage>? = null

        var threw = false
        try {
            tx.run(
                snapshot = { msgs.toList() },
                apply = { applied = it },
                summarize = { _, _ ->
                    msgs[0] = userMsg("历史被改写了") // 落在选中范围内 → 必须放弃
                    "摘要"
                },
                retainTokens = 50
            )
        } catch (e: SurfaceChangedException) {
            threw = true
        }

        assertTrue("范围被改写必须抛 SurfaceChangedException", threw)
        assertNull("放弃时绝不能提交任何结果", applied)
        assertFalse("失败后锁必须释放（否则后续压缩永久 busy）", tx.isBusy)

        // 锁已释放 → 后续压缩仍可正常进行
        assertNotNull(
            tx.run(snapshot = { msgs.toList() }, apply = {}, summarize = { _, _ -> "摘要" }, retainTokens = 50)
        )
    }

    // ── 收缩验证：摘要没变小则拒绝提交，且锁必须释放 ────────────────────

    @Test
    fun transactionRejectsNonShrinkingSummaryAndUnlocks() = runBlocking {
        val msgs = fiveRounds()
        val tx = CompactionTransaction(compactor)
        var applied: List<ChatMessage>? = null

        var threw = false
        try {
            tx.run(
                snapshot = { msgs.toList() },
                apply = { applied = it },
                summarize = { _, _ -> "长".repeat(100_000) }, // 摘要比被替换内容还大
                retainTokens = 50
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }

        assertTrue(threw)
        assertNull(applied)
        assertFalse(tx.isBusy)
    }

    // ── 压缩锁：并发必须被拒绝 ────────────────────────────────────────

    @Test
    fun transactionRejectsConcurrentRun() = runBlocking {
        val msgs = fiveRounds()
        val tx = CompactionTransaction(compactor)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            tx.run(
                snapshot = { msgs.toList() },
                apply = {},
                summarize = { _, _ -> entered.complete(Unit); release.await(); "摘要" },
                retainTokens = 50
            )
        }
        entered.await() // 第一个事务已持锁

        var threw = false
        try {
            tx.run(snapshot = { msgs.toList() }, apply = {}, summarize = { _, _ -> "摘要" }, retainTokens = 50)
        } catch (e: CompactionTransaction.BusyException) {
            threw = true
        }

        assertTrue("并发压缩必须被锁拒绝（对齐 dsh ManualCompactionError('busy')）", threw)
        release.complete(Unit)
        first.join()
        assertFalse("第一个事务结束后锁应释放", tx.isBusy)
    }

    // ── 无可压缩范围 / 空白摘要：返回 null，不改状态 ────────────────────

    @Test
    fun transactionReturnsNullWhenNothingToCompact() = runBlocking {
        val msgs = fiveRounds().take(4).toMutableList() // 太短
        val tx = CompactionTransaction(compactor)
        var applied: List<ChatMessage>? = null
        val outcome = tx.run(
            snapshot = { msgs.toList() }, apply = { applied = it },
            summarize = { _, _ -> "摘要" }, retainTokens = 50
        )
        assertNull(outcome)
        assertNull(applied)
        assertFalse(tx.isBusy)
    }

    @Test
    fun transactionReturnsNullOnBlankSummary() = runBlocking {
        val msgs = fiveRounds()
        val tx = CompactionTransaction(compactor)
        var applied: List<ChatMessage>? = null
        val outcome = tx.run(
            snapshot = { msgs.toList() }, apply = { applied = it },
            summarize = { _, _ -> "   " }, retainTokens = 50
        )
        assertNull(outcome)
        assertNull(applied)
        assertFalse(tx.isBusy)
    }

    // ── Outcome 统计与 system 消息保留 ────────────────────────────────

    @Test
    fun transactionPreservesSystemAndReportsOutcome() = runBlocking {
        val msgs = (listOf(ChatMessage(role = "system", content = "你是助手")) + fiveRounds()).toMutableList()
        val tx = CompactionTransaction(compactor)
        var applied: List<ChatMessage>? = null

        val outcome = tx.run(
            snapshot = { msgs.toList() }, apply = { applied = it },
            summarize = { _, _ -> "## 核心请求与目标\n- 摘要" }, retainTokens = 50
        )

        assertNotNull(outcome)
        val result = applied!!
        assertEquals("system 消息必须保留在首位", "system", result.first().role)
        assertEquals("你是助手", result.first().content)
        assertTrue("检查点必须小于被替换内容", outcome!!.summaryTokens < outcome.shadowedTokens)
        // 结果 = system(1) + 检查点(2) + 保留尾部
        assertEquals(3 + (msgs.size - 1 - outcome.shadowedCount), result.size)
    }
}
