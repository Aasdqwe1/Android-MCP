package com.mcp.browser

import com.mcp.serialization.McpJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 浏览器层的**纯逻辑**（不依赖 Android / WebView），单独放这里是为了能用 JVM 单测锁住契约。
 *
 * 这些函数以前是 WebBrowser 的 private 成员，无法测试——而它们恰好是最容易出错的部分：
 * evaluateJavascript 的双层编码、无返回值/语法错误的判定、响应头 charset、按键 keyCode、下载文件名。
 */
internal object BrowserInternals {

    /** 无返回值语句的统一提示：不宣称成功，也不让模型以为拿到了空值。 */
    const val NO_RETURN_HINT =
        "该语句无返回值，无法确认是否生效。如需确认，请用表达式读取状态验证，" +
            "如 (function(){return {value: document.querySelector('#x')?.value}})() 或 " +
            "(function(){return {found: !!document.querySelector('#x')}})()"

    /**
     * 解析 evalJs 回调结果，自动剥离 WebView 的双层 JSON 编码。
     *
     * evaluateJavascript 的回调值是「脚本返回值的 JSON 编码」；本项目所有注入脚本
     * 以 JSON.stringify(...) 返回字符串，因此回调结果是双重编码：
     * 脚本对象 {ok:true} → "{\"ok\":true}" → "\"{\\\"ok\\\":true}\""
     * 直接 parseToJsonElement 会得到 JsonPrimitive（字符串壳）而非 JsonObject。
     */
    fun unwrapEvalResult(raw: String?): JsonElement? {
        if (raw.isNullOrEmpty()) return null
        val first = runCatching { McpJson.parseToJsonElement(raw) }.getOrNull() ?: return null
        if (first is JsonPrimitive && first.isString) {
            val inner = first.content.trim()
            if (inner.startsWith("{") || inner.startsWith("[")) {
                return runCatching { McpJson.parseToJsonElement(inner) }.getOrNull() ?: first
            }
        }
        return first
    }

    /**
     * evaluateJavascript 返回值是 JSON 编码的字符串，这里解码成人类可读结果。
     *
     * 必须先 [unwrapEvalResult] 再判分支：注入脚本自己 JSON.stringify 过一层，
     * 直接 parse 得到的是字符串壳，`__error`/`__undefined`/`{total,content}` 全都读不到。
     */
    fun formatJsResult(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "undefined") return NO_RETURN_HINT
        if (trimmed == "null") {
            return "结果：null。如果这不是你期望的结果，通常是两种情况：①表达式不是「单个表达式」（多语句请用 " +
                "(function(){ …; return x; })() 包裹）；②页面脚本被站点 CSP 拦截（可用 browser_status / browser_extract 判断）。"
        }
        val el = unwrapEvalResult(trimmed) ?: runCatching { McpJson.parseToJsonElement(trimmed) }.getOrNull()
        if (el is JsonObject) {
            el["__error"]?.jsonPrimitive?.contentOrNull?.let { return "JS 执行异常：$it" }
            if (el["__undefined"] != null) return NO_RETURN_HINT
            val content = el["content"]?.jsonPrimitive?.contentOrNull
            val total = el["total"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (content != null && total != null) {
                val more = if (total > content.length) {
                    "\n（共 $total 字符，本次显示 ${content.length}；继续读取请用 browser_evaluate 的 start=${content.length} 参数）"
                } else ""
                return "结果：$content$more"
            }
        }
        return when {
            el is JsonPrimitive && el.contentOrNull != null -> {
                val s = el.contentOrNull!!
                if (s.length > 5000) "结果：${s.take(5000)}…\n（结果过长已截断，共 ${s.length} 字符；可用 browser_evaluate 的 start 参数分段读取完整内容）"
                else "结果：$s"
            }
            el != null -> "结果：${el.toString().take(2000)}"
            else -> "结果：$trimmed"
        }
    }

    /** 从 Content-Type 头解析 (mimeType, encoding)。 */
    fun parseContentType(raw: String?): Pair<String?, String?> {
        if (raw.isNullOrEmpty()) return null to null
        val parts = raw.split(";").map { it.trim() }
        val mime = parts.firstOrNull()?.takeIf { it.isNotEmpty() }
        val enc = parts.drop(1).firstNotNullOfOrNull { p ->
            val eq = p.indexOf('=')
            if (eq > 0 && p.substring(0, eq).trim().equals("charset", ignoreCase = true))
                p.substring(eq + 1).trim().trim('\'').trim('"')
            else null
        }
        return mime to enc
    }

    /** 常见按键的 keyCode（部分站点只读 keyCode/which，不读 key）。 */
    fun keyCodeOf(key: String): Int = when (key) {
        "Enter", "\n" -> 13
        "Tab" -> 9
        "Escape", "Esc" -> 27
        "Backspace" -> 8
        "Delete" -> 46
        " " -> 32
        "ArrowLeft" -> 37
        "ArrowUp" -> 38
        "ArrowRight" -> 39
        "ArrowDown" -> 40
        "Home" -> 36
        "End" -> 35
        "PageUp" -> 33
        "PageDown" -> 34
        else -> key.firstOrNull()?.uppercaseChar()?.code ?: 0
    }

    /** 从 Content-Disposition 或 URL 末段猜一个安全文件名（去掉路径分隔符，避免写到目录外）。 */
    fun guessFileName(url: String, contentDisposition: String?, now: Long = System.currentTimeMillis()): String {
        val fromHeader = contentDisposition
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
        val fromUrl = runCatching { java.net.URI(url).path?.substringAfterLast('/') }.getOrNull()
        val raw = (fromHeader ?: fromUrl)?.takeIf { it.isNotBlank() } ?: "download_$now"
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
    }

    /** 忽略 ASCII 大小写的字节序列查找（from 为起始位置）。 */
    fun indexOfIgnoreCase(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int? {
        if (needle.isEmpty() || needle.size > haystack.size - from) return null
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (toLowerAscii(haystack[i + j]) != toLowerAscii(needle[j])) continue@outer
            }
            return i
        }
        return null
    }

    fun toLowerAscii(b: Byte): Byte {
        val v = b.toInt()
        return if (v in 65..90) (v + 32).toByte() else b
    }
}
