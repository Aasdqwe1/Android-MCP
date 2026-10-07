package com.mcp.core.chat

import com.mcp.serialization.McpJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    @Test
    fun serializesStorageFieldNames() {
        val session = ChatSession(
            id = "s1",
            title = "Demo",
            pinned = true,
            updatedAt = 12.5,
            currentMessageId = "m2"
        )

        val json = McpJson.encodeToString(session)
        assertTrue(json.contains("\"updated_at\""))
        assertTrue(json.contains("\"current_message_id\""))
        assertEquals(session, McpJson.decodeFromString<ChatSession>(json))
    }
}
