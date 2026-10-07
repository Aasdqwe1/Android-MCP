package com.mcp.browser

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器纯逻辑的单元测试。
 *
 * 这些点此前都是 WebBrowser 的 private 成员、零覆盖，却正好是最容易出错的地方：
 * evaluateJavascript 的双层编码、undefined / 语法错误的判定、响应头 charset、按键 keyCode、下载文件名。
 */
class BrowserInternalsTest {

    /** 模拟 WebView：注入脚本 JSON.stringify 一层，WebView 再 JSON 编码一层。 */
    private fun encode(scriptReturn: String): String =
        "\"" + scriptReturn.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `分段结果能穿透双层编码`() {
        val raw = encode("{\"total\":42,\"content\":\"hello\"}")
        val out = BrowserInternals.formatJsResult(raw)
        assertTrue("应显示正文而不是 JSON 壳：$out", out.contains("结果：hello"))
        assertTrue("超长时应给出分段提示：$out", out.contains("start=5"))
    }

    @Test
    fun `undefined 命中无返回值提示`() {
        assertEquals(BrowserInternals.NO_RETURN_HINT, BrowserInternals.formatJsResult(encode("{\"__undefined\":true}")))
        assertEquals(BrowserInternals.NO_RETURN_HINT, BrowserInternals.formatJsResult("undefined"))
    }

    @Test
    fun `用户JS异常给出明确前缀`() {
        assertEquals("JS 执行异常：boom", BrowserInternals.formatJsResult(encode("{\"__error\":\"boom\"}")))
    }

    @Test
    fun `裸 null 会给出成因提示`() {
        val out = BrowserInternals.formatJsResult("null")
        assertTrue(out, out.startsWith("结果：null"))
        assertTrue("应提示「多语句要包 IIFE」这一成因：$out", out.contains("(function(){"))
        assertTrue("应提示 CSP 这一成因：$out", out.contains("CSP"))
    }

    @Test
    fun `字符串结果剥壳后原样返回`() {
        assertEquals("结果：https://example.com", BrowserInternals.formatJsResult("\"https://example.com\""))
    }

    @Test
    fun `unwrapEvalResult 单层与双层都能解`() {
        assertTrue(BrowserInternals.unwrapEvalResult("{\"ok\":true}") is JsonObject)
        assertTrue(BrowserInternals.unwrapEvalResult(encode("{\"ok\":true}")) is JsonObject)
        assertNull(BrowserInternals.unwrapEvalResult(null))
        assertNull(BrowserInternals.unwrapEvalResult(""))
    }

    @Test
    fun `parseContentType 处理 charset 与缺省`() {
        assertEquals("text/html" to "gbk", BrowserInternals.parseContentType("text/html; charset=gbk"))
        assertEquals("text/html" to "UTF-8", BrowserInternals.parseContentType("text/html;charset=\"UTF-8\""))
        assertEquals("application/json" to null, BrowserInternals.parseContentType("application/json"))
        assertEquals(null to null, BrowserInternals.parseContentType(null))
    }

    @Test
    fun `keyCodeOf 覆盖常用键`() {
        assertEquals(13, BrowserInternals.keyCodeOf("Enter"))
        assertEquals(27, BrowserInternals.keyCodeOf("Escape"))
        assertEquals(40, BrowserInternals.keyCodeOf("ArrowDown"))
        assertEquals(65, BrowserInternals.keyCodeOf("a"))
        assertEquals(0, BrowserInternals.keyCodeOf(""))
    }

    @Test
    fun `guessFileName 优先响应头并去掉路径分隔符`() {
        assertEquals("report.pdf", BrowserInternals.guessFileName("https://x/y", "attachment; filename=\"report.pdf\""))
        assertEquals("a.csv", BrowserInternals.guessFileName("https://x/path/a.csv", null))
        val evil = BrowserInternals.guessFileName("https://x/y", "attachment; filename=\"../../etc/passwd\"")
        assertFalse("不能保留路径分隔符：$evil", evil.contains("/"))
        assertEquals("download_7", BrowserInternals.guessFileName("https://x/", null, now = 7))
    }

    @Test
    fun `indexOfIgnoreCase 大小写不敏感且尊重起点`() {
        val hay = "<META http-equiv=Content-Security-Policy>".toByteArray()
        assertEquals(0, BrowserInternals.indexOfIgnoreCase(hay, "<meta".toByteArray()))
        assertNull(BrowserInternals.indexOfIgnoreCase(hay, "<meta".toByteArray(), from = 1))
        assertNull(BrowserInternals.indexOfIgnoreCase(hay, ByteArray(0)))
    }
}
