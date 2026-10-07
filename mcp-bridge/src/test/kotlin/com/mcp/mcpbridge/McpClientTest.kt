package com.mcp.mcpbridge

import com.mcp.toolbox.ParamType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpClientTest {

    /** 假的远程 MCP 服务器：记录方法调用，按方法返回预设结果。 */
    private class FakeRemote {
        val methods = mutableListOf<String>()
        var toolsResult: JsonObject = buildJsonObject { putJsonArray("tools") { } }
        var callResult: JsonObject = buildJsonObject { putJsonArray("content") { } }

        suspend fun send(text: String): String {
            val req = McpWireJson.decodeFromString<com.mcp.mcpbridge.jsonrpc.JsonRpcRequest>(text)
            methods.add(req.method)
            val result: JsonObject = when (req.method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
                    putJsonObject("serverInfo") { put("name", "fake") }
                }
                "tools/list" -> toolsResult
                "tools/call" -> callResult
                else -> JsonObject(emptyMap())
            }
            return McpWireJson.encodeToString(
                com.mcp.mcpbridge.jsonrpc.JsonRpcResponse(id = req.id, result = result)
            )
        }
    }

    @Test
    fun initializeSendsHandshake() = runBlocking {
        val remote = FakeRemote()
        val client = McpClient("fake", { text -> remote.send(text) })
        val result = client.initialize()
        assertNotNull(result["protocolVersion"])
        assertEquals(listOf("initialize"), remote.methods)
    }

    @Test
    fun listToolsParsesRemoteTools() = runBlocking {
        val remote = FakeRemote()
        remote.toolsResult = buildJsonObject {
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("name", "get_weather")
                    put("description", "查天气")
                    put("inputSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            put("city", buildJsonObject { put("type", "string"); put("description", "城市") })
                        }
                        putJsonArray("required") { add("city") }
                    })
                })
            }
        }
        val client = McpClient("weather", { text -> remote.send(text) })
        val tools = client.listTools()
        assertEquals(1, tools.size)
        assertEquals("get_weather", tools[0].name)
    }

    @Test
    fun callToolExtractsText() = runBlocking {
        val remote = FakeRemote()
        remote.callResult = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", "晴 25 度") })
            }
            put("isError", false)
        }
        val client = McpClient("weather", { text -> remote.send(text) })
        val out = client.callTool("get_weather", buildJsonObject { put("city", "北京") })
        assertEquals("晴 25 度", out)
        assertTrue(remote.methods.contains("tools/call"))
    }

    @Test
    fun callToolErrorWrapsAsErrorJson() = runBlocking {
        val remote = FakeRemote()
        remote.callResult = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", "boom") })
            }
            put("isError", true)
        }
        val client = McpClient("weather", { text -> remote.send(text) })
        val out = client.callTool("x", buildJsonObject { })
        assertTrue(out.contains("error"))
        assertTrue(out.contains("boom"))
    }

    @Test
    fun wrapAsToolDefsPrefixesNameAndConvertsSchema() = runBlocking {
        val remote = FakeRemote()
        remote.toolsResult = buildJsonObject {
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("name", "get_weather")
                    put("description", "查天气")
                    put("inputSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            put("city", buildJsonObject { put("type", "string") })
                        }
                        putJsonArray("required") { add("city") }
                    })
                })
            }
        }
        val client = McpClient("weather", { text -> remote.send(text) })
        val defs = client.wrapAsToolDefs(client.listTools())
        assertEquals(1, defs.size)
        assertEquals("remote_weather_get_weather", defs[0].name)
        val spec = defs[0].parameters["city"]
        assertNotNull(spec)
        assertEquals(ParamType.STRING, spec!!.type)
        assertEquals(true, spec.required)
    }
}
