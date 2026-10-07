@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mcp.mcpbridge

import com.mcp.mcpbridge.jsonrpc.JsonRpcError
import com.mcp.mcpbridge.jsonrpc.JsonRpcErrorCode
import com.mcp.mcpbridge.jsonrpc.JsonRpcRequest
import com.mcp.mcpbridge.jsonrpc.JsonRpcResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/**
 * MCP 线上格式（wire format）序列化配置。
 *
 * - explicitNulls = false：result / error 只输出非空的那个，符合 JSON-RPC 2.0 的互斥要求。
 * - ignoreUnknownKeys / isLenient：容忍客户端多传字段与非严格 JSON。
 * - encodeDefaults = true：jsonrpc 字段始终输出 "2.0"。
 */
val McpWireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 文本级 MCP 端点：把「JSON 文本」这一传输无关的形态与 [McpServer] 接起来。
 *
 * 传输层（HTTP / stdio / SSE）只需做两件事：
 *  1. 收到一段 JSON 文本 -> 调用 [handleJson]；
 *  2. 把返回值写回（返回 null 表示这是通知，无需响应）。
 *
 * 解析失败、内部异常都在这里收敛成合法的 JSON-RPC 错误响应，不向上抛。
 *
 * @param server 协议核心
 */
class McpEndpoint(private val server: McpServer) {

    /**
     * 处理一段 JSON 文本。
     * @return 响应文本；null 表示这是通知（无 id），调用方不应回写任何内容。
     */
    suspend fun handleJson(text: String): String? {
        val request = try {
            McpWireJson.decodeFromString<JsonRpcRequest>(text)
        } catch (e: Exception) {
            // JSON-RPC 2.0：解析错误时 id 为 null。这里显式写 JsonNull，
            // 保证 "id":null 真的出现在响应里（explicitNulls=false 会省略 Kotlin null）。
            return encode(
                JsonRpcResponse(
                    id = JsonNull,
                    error = JsonRpcError(
                        JsonRpcErrorCode.PARSE_ERROR,
                        "JSON 解析失败: " + (e.message ?: e::class.simpleName ?: "unknown")
                    )
                )
            )
        }

        val response = try {
            server.handle(request)
        } catch (e: Exception) {
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    JsonRpcErrorCode.INTERNAL_ERROR,
                    e.message ?: e::class.simpleName ?: "内部错误"
                )
            )
        }
        return response?.let { encode(it) }
    }

    private fun encode(response: JsonRpcResponse): String =
        McpWireJson.encodeToString(response)
}
