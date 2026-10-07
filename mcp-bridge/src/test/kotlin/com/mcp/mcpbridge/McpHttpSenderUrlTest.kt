package com.mcp.mcpbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** McpHttpSender 端点规范化的单元测试。 */
class McpHttpSenderUrlTest {

    @Test
    fun `已带 scheme 的地址原样保留`() {
        assertEquals("https://a.com/mcp", McpHttpSender.normalizeEndpoint("https://a.com/mcp"))
        assertEquals("http://127.0.0.1:8080/mcp", McpHttpSender.normalizeEndpoint("http://127.0.0.1:8080/mcp"))
    }

    @Test
    fun `缺 scheme 时补 https`() {
        assertEquals("https://mcp.example.com/mcp", McpHttpSender.normalizeEndpoint("mcp.example.com/mcp"))
        assertEquals("https://192.168.1.10:3000/mcp", McpHttpSender.normalizeEndpoint("192.168.1.10:3000/mcp"))
    }

    @Test
    fun `协议相对地址补 https`() {
        assertEquals("https://a.com/mcp", McpHttpSender.normalizeEndpoint("//a.com/mcp"))
    }

    @Test
    fun `去首尾空白`() {
        assertEquals("https://a.com/mcp", McpHttpSender.normalizeEndpoint("  https://a.com/mcp\n"))
    }

    @Test
    fun `大写 scheme 也识别`() {
        assertEquals("HTTPS://a.com/mcp", McpHttpSender.normalizeEndpoint("HTTPS://a.com/mcp"))
    }

    @Test
    fun `空地址抛可读异常`() {
        try {
            McpHttpSender.normalizeEndpoint("   ")
            throw AssertionError("应当抛异常")
        } catch (e: McpClientException) {
            assertTrue(e.message!!.contains("为空"))
        }
    }
}
