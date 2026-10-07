package com.mcp.core.chat

import kotlinx.serialization.Serializable

/** 一条可持久化的聊天消息，供 WebView 会话、工具结果和本地缓存共享。 */
@Serializable
data class ChatMessage(
    val isUser: Boolean,
    var content: String = "",
    var thinking: String = "",
    var toolCall: ToolCallData? = null,
    val id: String = "",
    val parentId: String = ""
)

/** 一次工具调用及其结果，作为聊天消息的一部分持久化。 */
@Serializable
data class ToolCallData(
    val id: String = "",
    val name: String = "",
    val arguments: String = "",
    val result: String = ""
)
