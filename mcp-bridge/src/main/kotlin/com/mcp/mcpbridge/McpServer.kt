package com.mcp.mcpbridge

import com.mcp.mcpbridge.jsonrpc.JsonRpcError
import com.mcp.mcpbridge.jsonrpc.JsonRpcErrorCode
import com.mcp.mcpbridge.jsonrpc.JsonRpcRequest
import com.mcp.mcpbridge.jsonrpc.JsonRpcResponse
import com.mcp.toolbox.Toolbox
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * MCP 服务器核心：把一个 [Toolbox] 暴露成符合 MCP 规范的工具服务。
 *
 * 传输无关——只负责「JSON-RPC 请求 -> JSON-RPC 响应」这一步，
 * 具体走 HTTP / stdio / SSE 由外层的传输实现决定（见 Transport 接口）。
 *
 * 实现的方法：
 *  - initialize / server/discover — 能力协商（新规范下可选，保留以兼容旧客户端）
 *  - ping                        — 健康检查
 *  - tools/list                  — 列出工具（复用 ToolCompiler.toMcpToolsList）
 *  - tools/call                  — 调用工具（复用 Toolbox.dispatchOrThrow）
 *
 * 通知（无 id）一律返回 null，不产生响应。
 *
 * @param toolbox  工具注册表（本地工具的唯一事实来源）
 * @param filter   工具可见性过滤；返回 false 的工具不会出现在 tools/list，也无法被调用
 */
class McpServer(
    private val toolbox: Toolbox,
    private val serverName: String = "agent-toolbox",
    private val serverVersion: String = "1.0.1",
    private val filter: (String) -> Boolean = { true },
) {

    /** 处理一条 JSON-RPC 请求；返回 null 表示这是通知，无需响应。 */
    suspend fun handle(request: JsonRpcRequest): JsonRpcResponse? {
        return when (request.method) {
            McpProtocol.METHOD_INITIALIZE -> ok(request.id, initializeResult(request.params))
            McpProtocol.METHOD_DISCOVER -> ok(request.id, discoverResult())
            McpProtocol.METHOD_PING -> ok(request.id, JsonObject(emptyMap()))
            McpProtocol.METHOD_TOOLS_LIST -> ok(request.id, toolsListResult(request.params))
            McpProtocol.METHOD_TOOLS_CALL -> callTool(request)
            else -> {
                if (request.id == null) {
                    // 未知通知：按 JSON-RPC 2.0 忽略，不报错。
                    null
                } else {
                    error(request.id, JsonRpcErrorCode.METHOD_NOT_FOUND, "未支持的方法: " + request.method)
                }
            }
        }
    }

    // ───────────────────────── 方法实现 ─────────────────────────

    /** 能力协商响应。客户端可传 params.protocolVersion 协商版本，不传用最新。 */
    private fun initializeResult(params: JsonElement?): JsonObject {
        val requested = (params as? JsonObject)
            ?.get("protocolVersion")
            ?.jsonPrimitive?.content
        val negotiated = if (requested != null && requested in McpProtocol.SUPPORTED_VERSIONS) {
            requested
        } else {
            McpProtocol.PROTOCOL_VERSION
        }
        return buildJsonObject {
            put("protocolVersion", negotiated)
            putJsonObject("capabilities") {
                // 只声明 tools 能力；listChanged=false 表示不会主动推送变更通知。
                putJsonObject("tools") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", serverName)
                put("version", serverVersion)
            }
        }
    }

    /** 新规范的可选发现端点：不握手也能拿到服务器能力与工具清单。 */
    private fun discoverResult(): JsonObject = buildJsonObject {
        put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
        putJsonObject("capabilities") {
            putJsonObject("tools") { put("listChanged", false) }
        }
        putJsonObject("serverInfo") {
            put("name", serverName)
            put("version", serverVersion)
        }
    }

    /** tools/list：复用 ToolCompiler.toMcpToolsList，产出 {name, description, inputSchema}。 */
    private fun toolsListResult(params: JsonElement?): JsonObject {
        val visible = toolbox.all().filter { filter(it.name) }
        val toolsArr = com.mcp.toolbox.ToolCompiler.toMcp(visible)
        return buildJsonObject {
            put("tools", toolsArr)
            // nextCursor 省略 = 无更多页（工具数量少，不做分页）。
        }
    }

    /** tools/call：解析 {name, arguments}，经 Toolbox.dispatchOrThrow 执行。 */
    private suspend fun callTool(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params as? JsonObject
            ?: return error(request.id, JsonRpcErrorCode.INVALID_PARAMS, "tools/call 缺少 params")
        val toolName = params["name"]?.jsonPrimitive?.content
            ?: return error(request.id, JsonRpcErrorCode.INVALID_PARAMS, "tools/call 缺少 name")

        if (!filter(toolName)) {
            return ok(request.id, callToolText("工具未启用或不存在: " + toolName, isError = true))
        }

        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            val text = toolbox.dispatchOrThrow(toolName, arguments)
            ok(request.id, callToolText(text, isError = false))
        } catch (e: Exception) {
            // 工具级失败不算协议错误：按 MCP 规范回 isError=true 的内容块，
            // 让模型看到失败原因并可自行修正，而不是整条 JSON-RPC 连接报错。
            ok(request.id, callToolText(e.message ?: "工具执行失败", isError = true))
        }
    }

    /** 构造 MCP CallToolResult：{ content: [{type:text, text}], isError }。 */
    private fun callToolText(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        put("isError", isError)
    }

    // ───────────────────────── 响应封装 ─────────────────────────

    private fun ok(id: JsonElement?, result: JsonObject): JsonRpcResponse =
        JsonRpcResponse(id = id, result = result)

    private fun error(id: JsonElement?, code: Int, message: String): JsonRpcResponse =
        JsonRpcResponse(id = id, error = JsonRpcError(code, message))
}
