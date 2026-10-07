package com.mcp.apifwd

import com.mcp.llm.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 外部上下文库的单元测试（纯 JVM，不依赖 Android Context）。
 *
 * 覆盖设计文档 §7.2 的前缀比对四种情形、§7.11 的 packedThrough 对齐，
 * 以及 store 的落盘往返与 TTL / LRU 回收。
 */
class ExternalConversationTest {

    private val t0 = 1_700_000_000_000L

    private fun turns(vararg msgs: Pair<String, String>): List<ClientTurn> =
        normalizeClientMessages(msgs.map { ChatMessage(role = it.first, content = it.second) })

    private fun record(
        packedThrough: Int = 0,
        vararg existing: Pair<String, String>,
    ): ConversationRecord {
        val r = newConversationRecord("k_test", "ext-1", "DEEPSEEK_REVERSE", t0)
        return r.copy(
            packedThrough = packedThrough,
            turns = existing.map { turnRecordOf(it.first, it.second) },
            lastUsedAt = t0,
        )
    }

    private fun withTempStore(block: (ExternalConversationStore, File) -> Unit) {
        val dir = Files.createTempDirectory("extstore").toFile()
        try {
            block(ExternalConversationStore(dir), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ───────────────────────── 指纹归一化 ─────────────────────────

    @Test
    fun `换行与行尾空白差异不破坏比对`() {
        val a = turnFingerprint("user", "第一行\n第二行")
        val b = turnFingerprint("user", "第一行  \r\n第二行\t\n")
        assertEquals("CRLF / 行尾空白 / 尾随换行应归一为同一指纹", a, b)
    }

    @Test
    fun `大小写与角色差异必须区分`() {
        // 刻意不做大小写折叠：把语义不同的消息混为一谈，比"多跑一次重放"危险得多
        assertTrue(turnFingerprint("user", "Hello") != turnFingerprint("user", "hello"))
        assertTrue(turnFingerprint("user", "a") != turnFingerprint("assistant", "a"))
    }

    @Test
    fun `保守归一不压缩内部空白`() {
        assertTrue(
            "内部多空格不应被压缩",
            turnFingerprint("user", "a  b") != turnFingerprint("user", "a b")
        )
    }

    // ───────────────────────── 情形 A / B ─────────────────────────

    @Test
    fun `前缀匹配且恰好新增一条 user 时走增量`() {
        val rec = record(0, "user" to "问题1", "assistant" to "回答1")
        val client = turns("user" to "问题1", "assistant" to "回答1", "user" to "问题2")
        val m = matchConversation(rec, client)
        assertTrue("期望 Incremental，实际 $m", m is ConvMatch.Incremental)
        assertEquals(2, (m as ConvMatch.Incremental).newUserIndex)
    }

    @Test
    fun `客户端重发完全相同的请求是幂等而不是新对话`() {
        val rec = record(0, "user" to "问题1", "assistant" to "回答1")
        assertEquals(ConvMatch.Idempotent, matchConversation(rec, turns("user" to "问题1", "assistant" to "回答1")))
    }

    @Test
    fun `追加两条以上交给重放而不是增量`() {
        val rec = record(0, "user" to "问题1", "assistant" to "回答1")
        val client = turns(
            "user" to "问题1",
            "assistant" to "回答1",
            "user" to "问题2",
            "assistant" to "客户端自己生成的回答",
            "user" to "问题3",
        )
        assertTrue("期望 Fork（交给重放），实际 ${matchConversation(rec, client)}", matchConversation(rec, client) is ConvMatch.Fork)
    }

    // ───────────────────────── 情形 C（分叉） ─────────────────────────

    @Test
    fun `客户端回滚删掉最后一条时判为分叉`() {
        val rec = record(
            0,
            "user" to "问题1", "assistant" to "回答1",
            "user" to "问题2", "assistant" to "回答2",
        )
        // 客户端把「问题2 / 回答2」删掉了，只留前两条
        assertEquals(ConvMatch.Fork(2), matchConversation(rec, turns("user" to "问题1", "assistant" to "回答1")))
    }

    @Test
    fun `客户端编辑中间一条时判为分叉且定位到该条`() {
        val rec = record(
            0,
            "user" to "问题1", "assistant" to "回答1",
            "user" to "问题2", "assistant" to "回答2",
        )
        val client = turns(
            "user" to "问题1",
            "assistant" to "回答1",
            "user" to "问题2（改过）",
            "assistant" to "回答2",
            "user" to "问题3",
        )
        assertEquals("分叉点应是第 2 条（被编辑的那条）", ConvMatch.Fork(2), matchConversation(rec, client))
    }

    // ───────────────────────── 情形 D（无匹配） ─────────────────────────

    @Test
    fun `客户端截断到打包点之前时整条记录作废`() {
        val rec = record(4, "assistant" to "回答")
        assertEquals(ConvMatch.NoMatch, matchConversation(rec, turns("user" to "a", "assistant" to "b")))
    }

    // ───────────────────────── §7.11 packedThrough ─────────────────────────

    @Test
    fun `打包重放后的第一次续聊走增量而不是被误判成分叉`() {
        var rec = newConversationRecord("k_test", "ext-1", "DEEPSEEK_REVERSE", t0)
        // 客户端首轮发来 4 条（system + 一轮历史），触发打包重放；
        // 重放把 4 条吞进一条 prompt，assistant 回复接在 packedThrough 之后
        rec = rec.afterPacked(4, turnRecordOf("assistant", "回答2"), "srv-1", "42", now = t0 + 1)
        assertEquals(5, rec.coveredMessages)

        // 客户端下一轮：原 4 条 + 我们刚产出的回答2 + 新问题
        val next = turns(
            "system" to "你是助手",
            "user" to "问题1",
            "assistant" to "回答1",
            "user" to "问题2",
            "assistant" to "回答2",
            "user" to "问题3",
        )
        val m = matchConversation(rec, next)
        assertTrue(
            "没有 packedThrough 时，前 4 条与 turns 对不上会被误判成分叉；实际 $m",
            m is ConvMatch.Incremental
        )
        assertEquals(5, (m as ConvMatch.Incremental).newUserIndex)
    }

    @Test
    fun `增量推进后记录覆盖到新增的 assistant`() {
        val rec = record(0, "user" to "问题1", "assistant" to "回答1")
        val grown = rec.afterIncremental(
            userTurn = turnRecordOf("user", "问题2"),
            assistantTurn = turnRecordOf("assistant", "回答2"),
            serverSessionId = "srv-1",
            anchorMessageId = "77",
            now = t0 + 10,
        )
        assertEquals(4, grown.coveredMessages)
        assertEquals("srv-1", grown.serverSessionId)
        assertEquals("77", grown.anchorMessageId)
        assertEquals(t0 + 10, grown.lastUsedAt)

        val m = matchConversation(
            grown,
            turns(
                "user" to "问题1", "assistant" to "回答1",
                "user" to "问题2", "assistant" to "回答2",
                "user" to "问题3",
            )
        )
        assertTrue("期望 Incremental，实际 $m", m is ConvMatch.Incremental)
        assertEquals(4, (m as ConvMatch.Incremental).newUserIndex)
    }

    @Test
    fun `分叉推进时截断到分叉点并接上新轮次`() {
        val rec = record(
            0,
            "user" to "问题1", "assistant" to "回答1",
            "user" to "问题2", "assistant" to "回答2",
        )
        val forked = rec.afterFork(
            forkClientIndex = 2,
            userTurn = turnRecordOf("user", "问题2（改过）"),
            assistantTurn = turnRecordOf("assistant", "新回答2"),
            serverSessionId = "srv-1",
            anchorMessageId = "99",
            now = t0 + 5,
        )
        assertEquals("分叉点之前的轮次应保留", 4, forked.turns.size)
        assertEquals(turnFingerprint("user", "问题2（改过）"), forked.turns[2].fingerprint)
        assertEquals("99", forked.anchorMessageId)
    }

    // ───────────────────────── 对话标识 ─────────────────────────

    @Test
    fun `对话标识按优先级派生且文件名安全`() {
        val c = turns("user" to "hi")
        val byHeader = deriveConversationKey("abc", "sid", "bob", c)
        val byBodySid = deriveConversationKey(null, "sid", "bob", c)
        val byUser = deriveConversationKey(null, null, "bob", c)
        val derived = deriveConversationKey(null, null, null, c)
        assertEquals("显式头最优先", "h_", byHeader.take(2))
        assertEquals("其次 body.session_id", "s_", byBodySid.take(2))
        assertEquals("其次 body.user", "u_", byUser.take(2))
        assertEquals("最后按首轮内容派生", "d_", derived.take(2))
        assertEquals(
            "空请求退回兜底",
            DEFAULT_CONVERSATION_KEY,
            deriveConversationKey(null, null, null, emptyList())
        )
        // 客户端每轮都重发同样的开头 → 必须派生同一个 key
        assertEquals(derived, deriveConversationKey(null, null, null, c))
        assertTrue(derived != deriveConversationKey(null, null, null, turns("user" to "hi2")))
        // 不同显式 id 必须落到不同对话
        assertTrue(
            deriveConversationKey("conv-1", null, null, c) != deriveConversationKey("conv-2", null, null, c)
        )
        // 会话 id 绑定：id 必须**可见地**出现在 key 里，而不是被哈希掉 ——
        // 否则出问题时无法一眼看出「这条外部对话绑在哪个会话 id 上」。
        val bound = deriveConversationKey(null, "ds-ABC123", null, c)
        assertTrue("会话 id 应原样出现在 key 中: $bound", bound.contains("abc123"))
        assertEquals("h_", deriveConversationKey("conv-9", null, null, c).take(2))
        assertTrue(
            "X-Conversation-Id 应优先于 session_id",
            deriveConversationKey("conv-9", "ds-other", null, c) != deriveConversationKey(null, "ds-other", null, c)
        )
        // 文件名安全：只含 ASCII 字母数字与下划线（否则落盘会因非法字符失败）
        for (k in listOf(byHeader, byBodySid, byUser, derived)) {
            assertTrue(
                "key 必须可当文件名: $k",
                k.isNotEmpty() && k.all { it == '_' || (it.code < 128 && it.isLetterOrDigit()) }
            )
        }
    }

    // ───────────────────────── store：落盘与回收 ─────────────────────────

    @Test
    fun `记录落盘后可跨实例读回并可继续续聊`() = withTempStore { store, dir ->
        val rec = newConversationRecord("h_abc", "ext-1", "DEEPSEEK_REVERSE", t0)
            .afterIncremental(
                turnRecordOf("user", "问题1"),
                turnRecordOf("assistant", "回答1"),
                "srv-9", "123", t0 + 1,
            )
        store.save(rec)

        // 新实例 = 模拟进程被杀后重启：内存缓存为空，必须从磁盘恢复
        val back = ExternalConversationStore(dir).load("h_abc")
        assertNotNull("进程重启后应从磁盘恢复记录（否则退化成打包重放）", back)
        assertEquals(2, back!!.turns.size)
        assertEquals("srv-9", back.serverSessionId)
        assertEquals("123", back.anchorMessageId)

        // 恢复出来的指纹必须与重新计算的完全一致，否则续聊会被误判成分叉
        val m = matchConversation(back, turns("user" to "问题1", "assistant" to "回答1", "user" to "问题2"))
        assertTrue("恢复的记录应仍可续聊，实际 $m", m is ConvMatch.Incremental)
    }

    @Test
    fun `TTL 超期记录被回收`() = withTempStore { store, _ ->
        store.save(newConversationRecord("h_old", "e1", "B", t0).copy(lastUsedAt = t0))
        store.save(newConversationRecord("h_new", "e2", "B", t0).copy(lastUsedAt = t0 + 1000))
        val evicted = store.evict(now = t0 + ExternalConversationStore.TTL_MS + 1)
        assertEquals(listOf("h_old"), evicted)
        assertNull(store.load("h_old"))
        assertNotNull(store.load("h_new"))
    }

    @Test
    fun `超出容量时按 LRU 淘汰最久未用的`() = withTempStore { store, _ ->
        store.save(newConversationRecord("h_a", "e", "B", t0).copy(lastUsedAt = t0))
        store.save(newConversationRecord("h_b", "e", "B", t0).copy(lastUsedAt = t0 + 10))
        store.save(newConversationRecord("h_c", "e", "B", t0).copy(lastUsedAt = t0 + 20))
        assertEquals(listOf("h_a"), store.evict(now = t0 + 100, maxRecords = 2))
        assertNull(store.load("h_a"))
        assertNotNull(store.load("h_c"))
    }

    @Test
    fun `turns 为空的记录不参与前缀反查`() = withTempStore { store, _ ->
        // 绑定模式的注入台账记录 turns 恒为空，而**空 turns 是任何请求的前缀** ——
        // 若不排除，无关对话会被反查命中并合并写进同一文件（可稳定复现）。
        store.save(
            newConversationRecord("s_bound", "ext-s_bound", "DEEPSEEK_REVERSE", t0).copy(
                serverSessionId = "srv",
                anchorMessageId = "6",
                lastUsedAt = t0 + 100,
            )
        )
        assertNull(
            "空 turns 记录不得被前缀反查命中",
            store.findByMessagePrefix(turns("user" to "任意一条消息")),
        )
    }

    @Test
    fun `无 id 客户端可按前缀反查回同一个对话`() = withTempStore { store, _ ->
        store.save(
            newConversationRecord("d_x", "ext-1", "DEEPSEEK_REVERSE", t0).afterIncremental(
                turnRecordOf("user", "问题1"),
                turnRecordOf("assistant", "回答1"),
                "srv", "5", t0 + 1,
            )
        )
        // 客户端不带任何 id，只发 messages
        val found = store.findByMessagePrefix(turns("user" to "问题1", "assistant" to "回答1", "user" to "问题2"))
        assertNotNull("应反查到唯一匹配的记录", found)
        assertEquals("d_x", found!!.convKey)
        // 完全无关的历史不应误命中
        assertNull(store.findByMessagePrefix(turns("user" to "完全不同的开头")))
    }
}
