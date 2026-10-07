package com.mcp.toolbox

import com.mcp.toolbox.examples.calculator
import com.mcp.toolbox.examples.httpRequest
import com.mcp.toolbox.examples.sampleAnnotatedTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import com.sun.net.httpserver.HttpServer
import com.mcp.toolbox.examples.scopedHttpClient
import okhttp3.OkHttpClient


class ToolboxTest {

    private fun box() = Toolbox().apply {
        registerAll(calculator())
        sampleAnnotatedTools().forEach { register(it) }
    }

    @Test
    fun `calculator evaluates correctly`() = runBlocking {
        val r = box().dispatch("calculator", """{"expression":"(2+3)*4-1"}""")
        assertEquals("19.0", r)
    }

    @Test
    fun `openai definition includes name description and schema`() {
        val defs = ToolCompiler.toOpenAi(box().all())
        assertTrue(defs.size >= 3)
        val calc = defs.first { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "calculator" }
        val fn = calc.jsonObject["function"]!!.jsonObject
        assertEquals("calculator", fn["name"]!!.jsonPrimitive.content)
        assertTrue(fn["description"]!!.jsonPrimitive.content.isNotEmpty())
        val params = fn["parameters"]!!.jsonObject
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertTrue(params["properties"]!!.jsonObject.keys.contains("expression"))
        assertTrue(params["required"]!!.jsonArray.contains(kotlinx.serialization.json.JsonPrimitive("expression")))
    }

    @Test
    fun `inputSchema 与 requiredParams 惰性缓存复用同一实例`() {
        val t = box().get("calculator")!!
        assertSame(t.inputSchema, t.inputSchema)
        assertSame(t.requiredParams, t.requiredParams)
    }

    @Test
    fun `namesJson 缓存且注册注销后失效`() {
        val b = box()
        val j1 = b.namesJson()
        assertSame(j1, b.namesJson())
        b.register(tool("extra") { description = "x" })
        val j2 = b.namesJson()
        assertTrue(j1 !== j2, "注册后应重建缓存")
        assertTrue(j2.contains("extra"), j2)
        b.unregister("extra")
        assertTrue(!b.namesJson().contains("extra"), "注销后缓存应失效")
    }

    @Test
    fun `unknown tool returns error json`() = runBlocking {
        val r = box().dispatch("does_not_exist", "{}")
        assertTrue(r.contains("\"error\""))
    }

    @Test
    fun `annotated tool greets with default`() = runBlocking {
        val r = box().dispatch("greet", """{"name":"World"}""")
        assertEquals("Hello, World!", r)
    }

    @Test
    fun `json param schema omits type to accept any json value`() {
        val t = tool("j") {
            json("payload") { description = "任意 JSON 值" }
        }
        val defs = ToolCompiler.toOpenAi(listOf(t))
        val props = defs.first().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject["properties"]!!.jsonObject
        val payload = props["payload"]!!.jsonObject
        // 关键：json 类型不输出 type 字段，等价于空 schema，模型可传 object/array/string/number/bool
        assertTrue(!payload.containsKey("type"), "json 类型不应输出 type 字段")
        assertTrue(payload["description"]!!.jsonPrimitive.content.contains("任意"))
    }

    /**
     * 回归测试：覆盖用户报告「body 内含未转义嵌套 JSON 导致参数解析失败」的场景。
     * body 现在声明为 json 类型，模型可直接传原始 JSON 对象（无需 \" 转义），
     * 端到端验证该对象能被完整、无二次转义地送达服务端。
     */
    @Test
    fun `http_request body accepts raw json object without escaping`() = runBlocking {
        val received = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/exec") { ex ->
            received.set(ex.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.close()
        }
        server.start()
        val port = server.address.port
        try {
            val args =
                """{"url":"http://127.0.0.1:$port/exec","method":"POST","body":{"cmd":"strings /a.apk | grep parentMessageId"}}"""
            val r = Toolbox().apply { register(httpRequest()) }.dispatch("http_request", args)
            // 原始 JSON 对象应完整送达，内部未被二次转义
            assertEquals("""{"cmd":"strings /a.apk | grep parentMessageId"}""", received.get())
            assertTrue(r.contains("\"status\":200"))
        } finally {
            server.stop(0)
        }
    }

    /**
     * 回归测试：用户报告 timeout_sec 不起作用。
     * 根因：旧代码只设了 per-call 的 call.timeout()，但 OkHttp 的 per-call 超时与 client 级
     * connect/read/write 超时是「独立看门狗、谁先触发谁生效」；client 默认 readTimeout=20s，
     * 导致 timeout_sec>20 时被静默截断——用户想调大的超时根本不生效。
     */
    @Test
    fun `scopedHttpClient makes timeout_sec authoritative`() {
        // 单测层级：派生 client 的 connect/read/write 超时必须等于传入值，
        // 否则大 timeout_sec 仍会被默认 readTimeout 抢先砍断。
        val base = OkHttpClient() // 默认 readTimeout=10000ms
        val scoped = scopedHttpClient(base, 30)
        assertEquals(30_000, scoped.connectTimeoutMillis)
        assertEquals(30_000, scoped.readTimeoutMillis)
        assertEquals(30_000, scoped.writeTimeoutMillis)
        // 同时验证小超时也生效
        val small = scopedHttpClient(base, 2)
        assertEquals(2_000, small.readTimeoutMillis)
    }

    @Test
    fun `http_request small timeout_sec aborts a slow server`() = runBlocking {
        // 服务端故意睡 2s，但把 timeout_sec 设成 1s —— 必须在 ~1s 内以 error 结束，而非等满 2s。
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var sleptSafely = true
        server.createContext("/slow") { ex ->
            try { Thread.sleep(2000) } catch (_: InterruptedException) { sleptSafely = false }
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.close()
        }
        server.start()
        val port = server.address.port
        val t0 = System.currentTimeMillis()
        try {
            val args = """{"url":"http://127.0.0.1:$port/slow","timeout_sec":1}"""
            val r = Toolbox().apply { register(httpRequest()) }.dispatch("http_request", args)
            val elapsed = System.currentTimeMillis() - t0
            assertTrue(r.contains("\"error\""), "超时应返回 error，实际: $r")
            assertTrue(elapsed < 1800, "应在 ~1s 内中止，实际耗时 ${elapsed}ms")
        } finally {
            server.stop(0)
        }
    }
}

