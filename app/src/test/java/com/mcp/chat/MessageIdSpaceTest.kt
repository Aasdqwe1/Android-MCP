package com.mcp.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 消息 id 命名空间测试桩：证明「压缩轮转后服务端消息 id 重新从 1 开始」不会与
 * 本地已有气泡 id 撞车，且本地 id 能无损换算回服务端 id。
 */
class MessageIdSpaceTest {

    @Test
    fun offsetZeroIsIdentity() {
        assertEquals("7", MessageIdSpace.toLocal("7", 0))
        assertEquals("7", MessageIdSpace.toRaw("7", 0))
        // OpenAI 后端的本地合成 id / 空串在偏移为 0 时必须原样透传
        assertEquals("", MessageIdSpace.toLocal("", 0))
        assertEquals("abc", MessageIdSpace.toLocal("abc", 0))
        assertNull(MessageIdSpace.toLocal(null, 0))
        assertNull(MessageIdSpace.toRaw(null, 0))
    }

    @Test
    fun rotationKeepsNewGenerationIdsDisjoint() {
        // 上一代：服务端 id 1..40（本地 id 也是 1..40）
        val offset = MessageIdSpace.advance(0L, "40")
        assertEquals(40L, offset)
        // 新服务端会话从 1 开始编号 → 本地 41、42，与旧气泡不重叠
        assertEquals("41", MessageIdSpace.toLocal("1", offset))
        assertEquals("42", MessageIdSpace.toLocal("2", offset))
        // 反算：本地 42 → 服务端 2（发给 DeepSeek 的 parent_message_id）
        assertEquals("2", MessageIdSpace.toRaw("42", offset))
        assertEquals("1", MessageIdSpace.toRaw("41", offset))
    }

    @Test
    fun secondRotationAccumulates() {
        val first = MessageIdSpace.advance(0L, "40")     // 旧会话最后一条服务端 id = 40
        val second = MessageIdSpace.advance(first, "6")  // 新会话又聊到服务端 id 6
        assertEquals(46L, second)
        assertEquals("47", MessageIdSpace.toLocal("1", second))
        assertEquals("1", MessageIdSpace.toRaw("47", second))
    }

    @Test
    fun advanceIgnoresUnknownLastId() {
        // 没有收到过 message_id（新会话首条就轮转）→ 偏移保持不变，不能瞎推进
        assertEquals(0L, MessageIdSpace.advance(0L, null))
        assertEquals(5L, MessageIdSpace.advance(5L, null))
        assertEquals(5L, MessageIdSpace.advance(5L, ""))
        assertEquals(5L, MessageIdSpace.advance(5L, "not-a-number"))
        assertEquals(5L, MessageIdSpace.advance(5L, "0"))
        assertEquals(5L, MessageIdSpace.advance(5L, "-3"))
    }

    @Test
    fun invalidLocalIdIsRejectedWhenOffsetActive() {
        // 偏移生效时，非数字 / 落在偏移内的本地 id 都不能当服务端 id 发出去
        assertNull(MessageIdSpace.toRaw("abc", 40))
        assertNull(MessageIdSpace.toRaw("40", 40))
        assertNull(MessageIdSpace.toRaw("1", 40))
        assertEquals("1", MessageIdSpace.toRaw("41", 40))
    }

    @Test
    fun roundTripForEveryLocalId() {
        val offset = MessageIdSpace.advance(MessageIdSpace.advance(0L, "12"), "34")
        for (raw in 1L..34L) {
            val local = MessageIdSpace.toLocal(raw.toString(), offset)
            assertEquals(raw.toString(), MessageIdSpace.toRaw(local, offset))
        }
    }
}
