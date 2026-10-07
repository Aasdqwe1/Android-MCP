package com.mcp.mcpbridge

import com.mcp.mcpbridge.jsonrpc.JsonRpcRequest
import com.mcp.mcpbridge.jsonrpc.JsonRpcResponse
import com.mcp.toolbox.ToolDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 远程 MCP 服务器暴露的一个工具。 */
data class RemoteTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** MCP 客户端调用失败。 */
class McpClientException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * MCP 客户端：连接一个远程 MCP 服务器，把它的工具「接收」进本地 Toolbox。
 *
 * 传输无关——只依赖一个 sender 函数（JSON 文本进、JSON 文本出）。
 * stdio / HTTP 的具体实现由上层注入（对齐 CapabilitySeam 的 Provider 模式）。
 *
 * 用法：
 *   val client = McpClient("github", httpSend)
 *   client.initialize()
 *   val remote = client.listTools()
 *   client.wrapAsToolDefs(remote).forEach { toolbox.register(it) }
 */
class McpClient(
    val serverId: String,
    private val sender: suspend (String) -> String?,
    private val protocolVersion: String = McpProtocol.PROTOCOL_VERSION,
) {
    private var nextId = 1

    /** 远程工具名 -> 本地工具名：加 remote_<server>_ 前缀，非字母数字下划线转 _，防冲突。 */
    fun localToolName(remoteName: String): String {
        val raw = "remote_" + serverId + "_" + remoteName
        return raw.replace(Regex("[^A-Za-z0-9_]"), "_")
    }

    /** 握手：发送 initialize，返回服务器的能力声明。 */
    suspend fun initialize(): JsonObject {
        val params = buildJsonObject { put("protocolVersion", protocolVersion) }
        return request("initialize", params)
    }

    /** 拉取远程工具清单，自动翻页（nextCursor）。 */
    suspend fun listTools(): List<RemoteTool> {
        val all = mutableListOf<RemoteTool>()
        var cursor: String? = null
        do {
            val c = cursor
            val params: JsonObject? = if (c == null) null else buildJsonObject { put("cursor", c) }
            val result = request("tools/list", params)
            val arr = result["tools"] as? JsonArray ?: JsonArray(emptyList())
            for (element in arr) {
                val obj = element as? JsonObject ?: continue
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                all.add(
                    RemoteTool(
                        name = name,
                        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        inputSchema = obj["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
                    )
                )
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return all
    }

    /** 调用远程工具，返回文本结果；远程标记 isError 时统一包成 error JSON。 */
    suspend fun callTool(remoteName: String, arguments: JsonObject): String {
        val params = buildJsonObject {
            put("name", remoteName)
            put("arguments", arguments)
        }
        val result = request("tools/call", params)
        val text = extractText(result)
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        return if (isError) buildJsonObject { put("error", text) }.toString() else text
    }

    /** 把远程工具包装成本地 ToolDef，可直接 toolbox.register。 */
    fun wrapAsToolDefs(remoteTools: List<RemoteTool>): List<ToolDef> = remoteTools.map { rt ->
        ToolDef(
            name = localToolName(rt.name),
            description = "[远程 " + serverId + "] " + rt.description,
            parameters = JsonSchemaToParams.convert(rt.inputSchema),
            handler = { args -> callTool(rt.name, args) },
        )
    }

    // ───────── 内部 ─────────

    private fun extractText(result: JsonObject): String {
        val content = result["content"] as? JsonArray ?: return ""
        val parts = mutableListOf<String>()
        for (block in content) {
            val obj = block as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull
            if (type == "text") {
                parts.add(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
            } else {
                parts.add(block.toString())
            }
        }
        return parts.joinToString("\n")
    }

    private suspend fun request(method: String, params: JsonElement?): JsonObject {
        val id = nextId
        nextId += 1
        val req = JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)
        val payload = McpWireJson.encodeToString(req)
        val respText = sender(payload) ?: throw McpClientException("远程无响应: " + method)
        val resp = try {
            McpWireJson.decodeFromString<JsonRpcResponse>(respText)
        } catch (e: Exception) {
            throw McpClientException("远程响应解析失败: " + (e.message ?: "unknown"), e)
        }
        val err = resp.error
        if (err != null) {
            throw McpClientException("远程错误 [" + err.code + "] " + err.message)
        }
        return resp.result as? JsonObject ?: JsonObject(emptyMap())
    }
}
