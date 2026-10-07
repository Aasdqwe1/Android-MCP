package com.mcp.mcpbridge

import com.mcp.mcpbridge.jsonrpc.JsonRpcErrorCode
import com.mcp.mcpbridge.jsonrpc.JsonRpcRequest
import com.mcp.mcpbridge.jsonrpc.JsonRpcResponse
import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MCP 协议层单元测试 —— 纯 JVM，不依赖 Android，不启动任何传输。
 *
 * 覆盖：initialize 版本协商、tools/list 形状、tools/call 成功与失败、
 * ping、未知方法、通知语义，以及 McpEndpoint 的 JSON 文本往返。
 */
class McpServerTest {

    /** 造一个带 echo 与 fail 两个工具的 Toolbox。 */
    private fun testToolbox(): Toolbox {
        val echo = tool("echo") {
            description = "原样回显"
            string("text") { description = "要回显的文本" }
            handler { args -> args["text"]!!.jsonPrimitive.content }
        }
        val fail = tool("fail") {
            description = "总是失败"
            string("why") { description = "失败原因"; required = false }
            handler { args -> throw RuntimeException("故意失败: " + (args["why"]?.jsonPrimitive?.content ?: "")) }
        }
        return Toolbox().apply { registerAll(echo, fail) }
    }

    private fun req(id: Int, method: String, params: JsonObject? = null) =
        JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)

    private fun assertOk(resp: JsonRpcResponse?): JsonObject {
        assertNotNull(resp, "应有响应")
        assertNull(resp.error, "不应有 JSON-RPC error")
        assertNotNull(resp.result, "应有 result")
        return resp.result!!.jsonObject
    }

    @Test
    fun initializeNegotiatesLatestByDefault() = runBlocking {
        val server = McpServer(testToolbox())
        val result = assertOk(server.handle(req(1, "initialize")))
        assertEquals(McpProtocol.PROTOCOL_VERSION, result["protocolVersion"]!!.jsonPrimitive.content)
        val info = result["serverInfo"]!!.jsonObject
        assertEquals("agent-toolbox", info["name"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
    }

    @Test
    fun initializeHonoursSupportedRequestedVersion() = runBlocking {
        val server = McpServer(testToolbox())
        val params = buildJsonObject { put("protocolVersion", "2025-06-18") }
        val result = assertOk(server.handle(req(1, "initialize", params)))
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun initializeFallsBackWhenVersionUnsupported() = runBlocking {
        val server = McpServer(testToolbox())
        val params = buildJsonObject { put("protocolVersion", "1999-01-01") }
        val result = assertOk(server.handle(req(1, "initialize", params)))
        assertEquals(McpProtocol.PROTOCOL_VERSION, result["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsListHasMcpShape() = runBlocking {
        val server = McpServer(testToolbox())
        val result = assertOk(server.handle(req(2, "tools/list")))
        val tools = result["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        val echo = tools.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "echo" }
        assertTrue(echo["description"]!!.jsonPrimitive.content.isNotEmpty())
        val schema = echo["inputSchema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertTrue(schema["properties"]!!.jsonObject.containsKey("text"))
    }

    @Test
    fun toolsListRespectsFilter() = runBlocking {
        val server = McpServer(testToolbox(), filter = { it != "fail" })
        val result = assertOk(server.handle(req(2, "tools/list")))
        val names = result["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("echo"), names)
    }

    @Test
    fun toolsCallSuccessReturnsTextContent() = runBlocking {
        val server = McpServer(testToolbox())
        val params = buildJsonObject {
            put("name", "echo")
            put("arguments", buildJsonObject { put("text", "你好 MCP") })
        }
        val result = assertOk(server.handle(req(3, "tools/call", params)))
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        val content = result["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("你好 MCP", content[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsCallFailureBecomesIsErrorTrue() = runBlocking {
        val server = McpServer(testToolbox())
        val params = buildJsonObject {
            put("name", "fail")
            put("arguments", buildJsonObject { put("why", "测试用") })
        }
        val result = assertOk(server.handle(req(4, "tools/call", params)))
        // 工具级失败应是 isError=true 的内容块，而不是 JSON-RPC error。
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("故意失败"))
    }

    @Test
    fun toolsCallUnknownToolIsError() = runBlocking {
        val server = McpServer(testToolbox())
        val params = buildJsonObject { put("name", "no_such_tool") }
        val result = assertOk(server.handle(req(5, "tools/call", params)))
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun toolsCallMissingNameIsProtocolError() = runBlocking {
        val server = McpServer(testToolbox())
        val params = buildJsonObject { put("arguments", buildJsonObject { }) }
        val resp = server.handle(req(6, "tools/call", params))
        assertNotNull(resp?.error)
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, resp.error!!.code)
    }

    @Test
    fun pingReturnsEmptyObject() = runBlocking {
        val server = McpServer(testToolbox())
        assertOk(server.handle(req(7, "ping")))
        Unit
    }

    @Test
    fun unknownMethodReturnsMethodNotFound() = runBlocking {
        val server = McpServer(testToolbox())
        val resp = server.handle(req(8, "does/not/exist"))
        assertNotNull(resp?.error)
        assertEquals(JsonRpcErrorCode.METHOD_NOT_FOUND, resp.error!!.code)
    }

    @Test
    fun notificationProducesNoResponse() = runBlocking {
        val server = McpServer(testToolbox())
        val note = JsonRpcRequest(id = null, method = "notifications/initialized")
        assertNull(server.handle(note))
    }

    // ─────────────────── McpEndpoint（JSON 文本往返） ───────────────────

    @Test
    fun endpointRoundTripsToolsList() = runBlocking {
        val endpoint = McpEndpoint(McpServer(testToolbox()))
        val out = endpoint.handleJson("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertNotNull(out)
        assertTrue(out.contains("\"tools\""))
        assertTrue(out.contains("\"echo\""))
    }

    @Test
    fun endpointParseErrorReturnsMinus32700() = runBlocking {
        val endpoint = McpEndpoint(McpServer(testToolbox()))
        val out = endpoint.handleJson("{ this is not json")
        assertNotNull(out)
        assertTrue(out.contains("-32700"), "应含 PARSE_ERROR 码，实际: " + out)
    }

    @Test
    fun endpointNotificationReturnsNull() = runBlocking {
        val endpoint = McpEndpoint(McpServer(testToolbox()))
        val out = endpoint.handleJson("""{"jsonrpc":"2.0","method":"notifications/cancelled"}""")
        assertNull(out)
    }

    @Test
    fun wireJsonOmitsNullResultOnError() = runBlocking {
        val endpoint = McpEndpoint(McpServer(testToolbox()))
        val out = endpoint.handleJson("""{"jsonrpc":"2.0","id":9,"method":"nope"}""")
        assertNotNull(out)
        // explicitNulls=false：不应出现 "result":null
        assertTrue(!out.contains("\"result\":null"), "不应输出 result:null，实际: " + out)
    }
}
