package com.mcp.data

import com.mcp.core.llm.BackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStoreTest {
    @Test
    fun backendNamespacesStaySeparated() {
        assertEquals("oa", LocalStore.backendTag(BackendType.OPENAI))
    }

    /**
     * 会话轮转状态的磁盘格式锁定：字段名/默认值一旦漂移，旧版本写下的 rotation 文件
     * 就会被静默读成「未轮转过」（服务端会话 id 丢失 + id 偏移归零 → 锚点算错）。
     */
    @Test
    fun sessionRotationRoundTripsThroughJson() {
        val rotation = LocalStore.SessionRotation(serverSessionId = "srv-abc", idOffset = 42L)
        val json = com.mcp.serialization.McpJson.encodeToString(rotation)
        assertTrue(json, json.contains("server_session_id"))
        assertTrue(json, json.contains("id_offset"))
        assertEquals(rotation, com.mcp.serialization.McpJson.decodeFromString<LocalStore.SessionRotation>(json))
    }

    @Test
    fun sessionRotationDefaultsToNotRotated() {
        // 缺字段（旧文件/损坏文件）→ 默认「未轮转过」，不能瞎推进偏移
        val decoded = com.mcp.serialization.McpJson.decodeFromString<LocalStore.SessionRotation>("{}")
        assertEquals("", decoded.serverSessionId)
        assertEquals(0L, decoded.idOffset)
    }
}
