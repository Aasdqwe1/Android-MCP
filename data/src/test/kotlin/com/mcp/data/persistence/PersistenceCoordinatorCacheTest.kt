package com.mcp.data.persistence

import com.mcp.core.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B8 测试桩：同一会话重复 load 命中缓存；写后失效并重新读取。 */
class PersistenceCoordinatorCacheTest {

    private class FakeBackend : PersistenceBackend {
        val files = HashMap<String, String>()
        var readCount = 0
        override fun readRaw(sid: String): String? { readCount++; return files[sid] }
        override fun write(sid: String, text: String) { files[sid] = text }
        override fun appendLine(sid: String, line: String) { files[sid] = (files[sid] ?: "") + line + "\n" }
        override fun delete(sid: String) { files.remove(sid) }
        override fun lastModified(sid: String): Double = if (files.containsKey(sid)) 1.0 else 0.0
        override fun length(sid: String): Long = (files[sid]?.length ?: 0).toLong()
    }

    @Test
    fun `重复 load 命中缓存且写后失效`() {
        val backend = FakeBackend()
        val coord = PersistenceCoordinator(backend, "oa")
        coord.append("s1", ChatMessage(isUser = true, content = "hi"))
        val first = coord.load("s1")
        assertEquals(1, first.size)
        val readsAfterFirst = backend.readCount
        val second = coord.load("s1")
        assertEquals("缓存命中应返回同一内容", first, second)
        assertEquals("缓存命中不应再读盘", readsAfterFirst, backend.readCount)
        coord.append("s1", ChatMessage(isUser = false, content = "yo"))
        val third = coord.load("s1")
        assertEquals("写后应重新读取", 2, third.size)
        assertTrue("写后读缓存必须失效", backend.readCount > readsAfterFirst)
    }
}
