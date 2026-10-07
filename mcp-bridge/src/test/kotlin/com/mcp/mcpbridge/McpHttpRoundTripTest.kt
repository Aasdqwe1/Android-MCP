package com.mcp.mcpbridge

import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.tool
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP 端到端联调：JDK 内建 HttpServer 承载 /mcp，真实 HTTP 往返。
 *
 * 覆盖：McpServer -> McpEndpoint -> HTTP -> McpHttpSender -> McpClient -> ToolDef 全链路，
 * 是「本地工具发射」+「远程工具接收」闭环的最小可运行验证（不依赖 Android）。
 */
class McpHttpRoundTripTest {

    private var server: HttpServer? = null

    private fun startServer(endpoint: McpEndpoint, token: String = ""): Int {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/mcp") { exchange: HttpExchange ->
            try {
                if (exchange.requestMethod != "POST") {
                    respond(exchange, 405, "{}")
                    return@createContext
                }
                if (token.isNotEmpty()) {
                    val auth = exchange.requestHeaders.getFirst("Authorization") ?: ""
                    if (auth != "Bearer " + token) { respond(exchange, 401, "{\"error\":\"unauthorized\"}"); return@createContext }
                }
                val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                val out = runBlocking { endpoint.handleJson(body) }
                if (out == null) respond(exchange, 202, "") else respond(exchange, 200, out)
            } catch (e: Exception) {
                respond(exchange, 500, "{\"error\":\"" + (e.message ?: "err") + "\"}")
            }
        }
        s.executor = null
        s.start()
        server = s
        return s.address.port
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @AfterTest
    fun tearDown() { server?.stop(0) }

    private fun demoEndpoint(): McpEndpoint {
        val echo = tool("echo") {
            description = "回显"
            string("text") { description = "文本" }
            handler { args -> "ECHO:" + (args["text"]?.toString() ?: "") }
        }
        val toolbox = Toolbox().apply { register(echo) }
        return McpEndpoint(McpServer(toolbox))
    }

    @Test
    fun fullRoundTripOverHttp() = runBlocking {
        val port = startServer(demoEndpoint())
        val sender = McpHttpSender("http://127.0.0.1:" + port + "/mcp")
        val client = McpClient("local", sender.asSender())

        // 1. 握手
        val init = client.initialize()
        assertNotNull(init["protocolVersion"])

        // 2. 拉工具
        val tools = client.listTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)

        // 3. 调用
        val out = client.callTool("echo", buildJsonObject { put("text", "hi") })
        assertTrue(out.contains("ECHO:"), "实际: " + out)

        // 4. 包装为本地 ToolDef
        val defs = client.wrapAsToolDefs(tools)
        assertEquals("remote_local_echo", defs[0].name)
    }

    @Test
    fun bearerTokenIsEnforced() = runBlocking {
        val port = startServer(demoEndpoint(), token = "s3cret")
        val bad = McpHttpSender("http://127.0.0.1:" + port + "/mcp", bearerToken = "")
        var failed = false
        try { McpClient("x", bad.asSender()).listTools() } catch (e: Exception) { failed = true }
        assertTrue(failed, "无 token 应被拒绝")

        val good = McpHttpSender("http://127.0.0.1:" + port + "/mcp", bearerToken = "s3cret")
        val tools = McpClient("x", good.asSender()).listTools()
        assertEquals(1, tools.size)
    }
}
