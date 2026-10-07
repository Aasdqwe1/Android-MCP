package com.mcp.mcpbridge.jsonrpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON-RPC 2.0 协议信封（MCP 底层消息格式）。
 *
 * MCP 所有消息都是 JSON-RPC 2.0：请求 / 响应 / 通知三类。
 * 2026-07-28 版规范取消强制握手、移除会话 ID，每条请求自包含，
 * 因此信封只需承载 id / method / params 三要素。
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null,
)

/** JSON-RPC 2.0 错误对象。 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/** JSON-RPC 2.0 响应对象（result 与 error 互斥）。 */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

/** JSON-RPC 2.0 标准错误码。 */
object JsonRpcErrorCode {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}
