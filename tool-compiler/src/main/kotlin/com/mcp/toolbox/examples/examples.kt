package com.mcp.toolbox.examples

import com.mcp.toolbox.Param
import com.mcp.toolbox.Tool
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.ToolScanner
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream


// ───────────────────────────────────────────────────────────
//  共享 HTTP 客户端（OkHttp）
// ───────────────────────────────────────────────────────────
// 借鉴成熟开源 HTTP 客户端 OkHttp 的健壮做法：
//  - followSslRedirects(false)：不跟随 https→http 跳转，从根上避免
//    “Unable to parse TLS packet header”（在 TLS 层读明文）。
//  - retryOnConnectionFailure(true) + 自定义拦截器：对 429/5xx 做退避重试（尊重 Retry-After）。
//  - 透明 gzip 解压、连接池复用、按 Content-Type 正确解码字符集。
private const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)        // 关键：拒绝 https→http 降级，避免 TLS 明文错误
        .retryOnConnectionFailure(true)
        .addInterceptor(retryInterceptor())
        .build()
}

/**
 * 为单次请求派生一个超时受 [timeoutSec] 完全控制的 client。
 * 必须「连 client 级的 connect/read/write 超时也一并改写」——OkHttp 的 per-call 超时
 * 与 client 级超时是独立看门狗、谁先触发谁生效；若只设 per-call 而不动 client 级默认值，
 * 用户设的「更大超时」会被默认的 readTimeout(20s) 静默抢先砍断（即 timeout_sec 不生效）。
 * 保留 retry/follow 等原配置（newBuilder 会复制）。
 */
internal fun scopedHttpClient(base: OkHttpClient, timeoutSec: Int): OkHttpClient =
    base.newBuilder()
        .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
        .build()


/** 对 429/5xx 做有限次退避重试，尊重 Retry-After。借鉴常见 HTTP 客户端容错策略。 */
private fun retryInterceptor() = Interceptor { chain ->
    var request = chain.request()
    var response = chain.proceed(request)
    var attempt = 0
    while (!response.isSuccessful && response.code in setOf(429, 500, 502, 503, 504) && attempt < 2) {
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        val delayMs = if (retryAfter != null && retryAfter in 1..30) retryAfter * 1000 else 500L * (attempt + 1)
        response.close()
        Thread.sleep(delayMs)
        response = chain.proceed(request)
        attempt++
    }
    response
}

// ───────────────────────────────────────────────────────────
//  DSL 风格工具
// ───────────────────────────────────────────────────────────

/** 计算器：支持 + - * / %、括号与一元负号的安全求值（递归下降，无外部依赖）。 */
fun calculator(): ToolDef = tool("calculator") {
    description = "计算数学表达式，支持 + - * / %、括号与一元负号，如 (2+3)*4-1"
    string("expression") {
        description = "要计算的数学表达式"
    }
    handler { args ->
        val expr = args["expression"]!!.jsonPrimitive.content
        runCatching { evalMath(expr).toString() }
            .getOrElse { """{"error":"表达式无效: $it"}""" }
    }
}

/** 当前时间。 */
fun currentTime(): ToolDef = tool("get_current_time") {
    description = "返回当前服务器/设备时间（ISO-8601）与时区"
    handler {
        val now = java.time.OffsetDateTime.now()
        """{"iso":"${now.toString()}","zone":"${now.offset}"}"""
    }
}

/** 字符串长度。 */
fun stringLength(): ToolDef = tool("string_length") {
    description = "返回字符串的字符数"
    string("text") { description = "待统计的字符串" }
    handler { args ->
        val n = args["text"]!!.jsonPrimitive.content.length
        """{"length":$n}"""
    }
}

/** HTTP 请求：支持 GET/POST/PUT/DELETE/PATCH/HEAD，自定义头、请求体、可配置连接与读取超时。 */
fun httpRequest(): ToolDef = tool("http_request") {
    description = """发起 HTTP 请求。支持 GET/POST/PUT/DELETE/PATCH/HEAD，可设置请求头、请求体，并通过 timeout_sec 控制连接与读取的整体超时（默认 15 秒）。支持 output_file 将响应体流式写入文件（适合大文件下载），不提供则返回 body 字符串。
**已知限制（写入以消除误导）：**
- 无内建 Cookie Jar：响应 Set-Cookie 不会自动带到下一次请求；需要维持登录态请手动拼 Cookie 请求头。
- 不支持多个同名请求头（OkHttp .header() 是替换，非追加）。
- GET/HEAD + body 组合直接报错；不会像之前那样静默丢弃 body。
"""
    string("url") { description = "目标 URL（含 http/https）" }
    string("method") {
        description = "HTTP 方法，默认 GET"
        required = false
        enumValues = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")
    }
    json("body") {
        description = "请求体。可直接传 JSON 对象/数组（如 {\"cmd\":\"strings /a.apk\"}），也可传纯文本字符串。POST/PUT/PATCH 时使用。"
        required = false
    }
    string("content_type") {
        description = "Content-Type，默认 application/json; charset=utf-8；表单用 application/x-www-form-urlencoded"
        required = false
    }
    json("headers") {
        description = "额外请求头，JSON 对象，如 {\"Authorization\":\"Bearer xxx\",\"X-Key\":\"val\"}。值必须可 JSON 化，null 值的头会被跳过；指定 User-Agent/Accept 会覆盖默认。"
        required = false
    }
    integer("timeout_sec") {
        description = "整体超时秒数（连接 + 读取阶段共用），默认 15，建议范围 1–60"
        required = false
    }
    string("output_file") {
        description = "保存响应体的文件路径（绝对路径）。若提供，则响应体流式写入该文件，不占用内存，适合下载大文件。不提供则返回 body 字符串（仅适合小响应）。"
        required = false
    }

    handler { args ->
        val url         = args["url"]!!.jsonPrimitive.content
        val method      = args["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
        // body 既可能是 JSON 元素（json 类型：对象/数组/数字/布尔/null），也可能是字符串。
        // 统一规约为「可发送的字符串」：原语取文本，复合值原样序列化（避免二次转义）。
        val bodyEl      = args["body"]
        val body: String? = when {
            bodyEl == null || bodyEl is JsonNull -> null
            bodyEl is JsonPrimitive -> bodyEl.jsonPrimitive.content
            else -> bodyEl.toString()
        }
        val contentType = args["content_type"]?.jsonPrimitive?.content ?: "application/json; charset=utf-8"
        // P1-E 修复：headers 现在走 json schema，args["headers"] 直接是 JsonElement（已解析，无需二次 parse）。
        val headersObj = args["headers"]?.jsonObject
        val timeoutSec  = (args["timeout_sec"]?.jsonPrimitive?.content?.toIntOrNull() ?: 15).coerceIn(1, 120)
        val outputFile  = args["output_file"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        withContext(Dispatchers.IO) { runCatching {
            // P1-F 修复：body + GET/HEAD 早失败（不静默丢弃）
            if (body != null && method in setOf("GET", "HEAD")) {
                return@runCatching buildJsonObject {
                    put("error", "方法 $method 不允许携带 body；请改用 POST/PUT，或去掉 body")
                }.toString()
            }

            val reqBuilder = Request.Builder().url(url)
            // P1-J：用户传了 User-Agent / Accept 时保留用户值，不再静默覆盖。
            val userHeaders = headersObj?.toList() ?: emptyList()
            val userHasUA = userHeaders.any { it.first.equals("User-Agent", ignoreCase = true) }
            val userHasAccept = userHeaders.any { it.first.equals("Accept", ignoreCase = true) }
            if (!userHasUA) reqBuilder.header("User-Agent", "MCPAgent/1.0")
            if (!userHasAccept) reqBuilder.header("Accept", "*/*")
            // P1-G 修正：headers 解析失败无风险，且不再 runCatching 吞错——
            // schema 已保证类型正确，此处遇到异常直接抛出，让模型看到真实错误。
            headersObj?.forEach { (k, v) ->
                val valStr = when (v) {
                    is JsonNull -> null
                    is JsonPrimitive -> v.content
                    else -> v.toString()
                }
                if (valStr != null) reqBuilder.header(k, valStr)
            }
            // 发送请求体（P1-F 已在上面拦截 GET/HEAD + body 组合）
            val reqBody = if (body != null)
                body.toRequestBody(contentType.toMediaTypeOrNull())
            else null
            if (reqBody != null) reqBuilder.method(method, reqBody)
            else reqBuilder.method(method, null)

            val call = scopedHttpClient(httpClient, timeoutSec).newCall(reqBuilder.build())
            call.timeout().timeout(timeoutSec.toLong(), TimeUnit.SECONDS) // 整体超时总闸
            val resp = call.execute()
            val ct = resp.body?.contentType()?.toString() ?: ""

            val responseBody = if (outputFile != null) {
                // 流式写入文件
                val file = File(outputFile)
                file.parentFile?.mkdirs()
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytes = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                        totalBytes
                    }
                } ?: 0L
            } else {
                // 原有行为：返回 body 字符串
                resp.body?.string().orEmpty()
            }

            buildJsonObject {
                put("status", resp.code)
                put("content_type", ct)
                when (outputFile) {
                    null -> put("body", responseBody as String)
                    else -> {
                        put("saved_to", outputFile)
                        put("size_bytes", responseBody as Long)
                    }
                }
            }.toString()
        }.getOrElse { e ->
            """{"error":"${e.message?.take(200)?.replace("\"", "'")}"}"""
        } }
    }
}

//  联网搜索
// ───────────────────────────────────────────────────────────

/**
 * 联网搜索互联网（无需 API key）。
 *
 * 默认使用 Bing（中文市场 zh-CN，稳定性更好）；若结果过少或请求失败，自动回退到 DuckDuckGo。
 * engine 参数可强制指定 bing / duckduckgo。
 * 返回结构化 JSON：{ query, engine, count, results: [ { title, url, snippet } ] }。
 */
fun webSearch(): ToolDef = tool("web_search") {
    description = "联网搜索互联网，返回与查询相关的结果（标题、链接、摘要）列表。用于获取最新资讯、核实事实、查找文档与资料。无需 API key。engine 可选 bing（默认，抓取中文市场 zh-CN 的网页结果，稳定性更好）或 duckduckgo。"
    string("query") {
        description = "搜索关键词或问题"
        required = true
    }
    integer("num_results") {
        description = "返回结果数量，默认 5，最多 10"
        required = false
    }
    string("engine") {
        description = "搜索引擎：bing（默认）或 duckduckgo"
        required = false
        enumValues = listOf("duckduckgo", "bing")
    }
    handler { args ->
        val query = args["query"]?.jsonPrimitive?.content
            ?: return@handler """{"error":"缺少 query 参数"}"""
        val num = (args["num_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5).coerceIn(1, 10)
        val engine = (args["engine"]?.jsonPrimitive?.content ?: "bing").lowercase()
        val primary = if (engine == "duckduckgo") "duckduckgo" else "bing"
        val secondary = if (primary == "duckduckgo") "bing" else "duckduckgo"
        withContext(Dispatchers.IO) {
            val first = runSearch(primary, query, num)
            if (searchFailed(first)) runSearch(secondary, query, num) else first
        }
    }
}

private fun runSearch(engine: String, query: String, num: Int): String =
    if (engine == "bing") searchBing(query, num) else searchDuckDuckGo(query, num)

private fun searchFailed(json: String): Boolean =
    json.contains("\"error\"") || Regex("\"count\"\\s*:\\s*0").containsMatchIn(json)

/**
 * 统一文本抓取（基于共享 OkHttp 客户端）。
 * 重定向策略由 httpClient 控制：只跟随 https→https，拒绝 https→http 降级（避免 TLS 明文错误）。
 * 对偶发 IO/TLS 异常做 1 次重试（OkHttp 自身也已对连接失败做重试）。
 */
private fun fetchText(url: String, method: String = "GET", body: String? = null): String {
    var lastErr: Throwable? = null
    repeat(2) { attempt ->
        try {
            val reqBuilder = Request.Builder().url(url)
                .header("User-Agent", CHROME_UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (body != null) {
                reqBuilder.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            } else {
                reqBuilder.method(method, null)
            }
            val resp = httpClient.newCall(reqBuilder.build()).execute()
            val code = resp.code
            if (code !in 200..299) {
                resp.close()
                throw java.io.IOException("搜索引擎返回 $code")
            }
            return resp.use { it.body?.string().orEmpty() } // 自动 gzip 解压 + 按字符集解码
        } catch (e: Throwable) { lastErr = e }
        if (attempt == 0) Thread.sleep(800)
    }
    throw lastErr ?: java.io.IOException("fetch 失败")
}

/** DuckDuckGo HTML 接口（keyless），POST q= 后解析 result__a / result__snippet。 */
private fun searchDuckDuckGo(query: String, num: Int): String {
    val url = "https://html.duckduckgo.com/html/"
    val post = "q=${java.net.URLEncoder.encode(query, "UTF-8")}"
    return runCatching {
        val html = fetchText(url, method = "POST", body = post)
        val items = parseDuckDuckGo(html, num)
        buildJsonObject {
            put("query", query)
            put("engine", "duckduckgo")
            put("count", items.size)
            put("results", buildJsonArray {
                items.forEach { (t, u, s) ->
                    add(buildJsonObject {
                        put("title", t)
                        put("url", u)
                        put("snippet", s)
                    })
                }
            })
        }.toString()
    }.getOrElse { e -> """{"error":"搜索失败: ${e.message?.take(200)?.replace("\"", "'")}","query":${JsonPrimitive(query)}}""" }
}

/** Bing 搜索（keyless），解析 b_algo 结果块。 */
private fun searchBing(query: String, num: Int): String {
    val url = "https://cn.bing.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&setmkt=zh-CN&scope=web&count=$num"
    return runCatching {
        val html = fetchText(url)
        val items = parseBing(html, num)
        buildJsonObject {
            put("query", query)
            put("engine", "bing")
            put("count", items.size)
            put("results", buildJsonArray {
                items.forEach { (t, u, s) ->
                    add(buildJsonObject {
                        put("title", t)
                        put("url", u)
                        put("snippet", s)
                    })
                }
            })
        }.toString()
    }.getOrElse { e -> """{"error":"搜索失败: ${e.message?.take(200)?.replace("\"", "'")}","query":${JsonPrimitive(query)}}""" }
}

/** 用 Jsoup 解析 DuckDuckGo HTML 结果（.result 块，标题 a.result__a，摘要 .result__snippet）。 */
private fun parseDuckDuckGo(html: String, num: Int): List<Triple<String, String, String>> {
    val doc = Jsoup.parse(html)
    val out = mutableListOf<Triple<String, String, String>>()
    for (res in doc.select(".result")) {
        if (out.size >= num) break
        val a = res.selectFirst("a.result__a") ?: continue
        val title = a.text().trim()
        val url = decodeDdgUrl(a.attr("href"))
        if (title.isEmpty() || url.isEmpty()) continue
        val snip = res.selectFirst(".result__snippet")?.text()?.trim() ?: ""
        out.add(Triple(title, url, snip))
    }
    return out
}

/**
 * 用 Jsoup（HTML 解析事实标准）解析 Bing 自然结果，远比正则稳健：
 * 能正确处理嵌套标签、属性顺序、残缺 HTML。
 *   - 标题 + 链接：li.b_algo 内的 b_algoheader <a href>，兼容 <h2><a> 变体
 *   - 摘要：b_caption 内的 <p>
 */
private fun parseBing(html: String, num: Int): List<Triple<String, String, String>> {
    val doc = Jsoup.parse(html)
    val out = mutableListOf<Triple<String, String, String>>()
    for (li in doc.select("li.b_algo")) {
        if (out.size >= num) break
        val link = li.selectFirst("div.b_algoheader a[href]")
            ?: li.selectFirst("h2 a[href]")
            ?: li.selectFirst("a[href]")
            ?: continue
        val title = link.text().trim()
        val url = link.attr("href").trim()
        if (title.isEmpty() || url.isEmpty() ||
            url.startsWith("#") || url.startsWith("javascript:") || url.startsWith("data:")) continue
        val snipEl = li.selectFirst("div.b_caption p") ?: li.selectFirst("p")
        val snip = snipEl?.text()?.trim() ?: ""
        out.add(Triple(title, url, snip))
    }
    return out
}

/** DuckDuckGo 结果链接形如 /l/?uddg=<encoded>，提取并 URL 解码真实地址。 */
private fun decodeDdgUrl(rawHref: String): String {
    val m = Regex("""uddg=([^&]+)""").find(rawHref)
    return if (m != null) runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }
        .getOrElse { rawHref }
    else rawHref
}

// ───────────────────────────────────────────────────────────
//  注解风格工具（演示 @Tool + @Param）
// ───────────────────────────────────────────────────────────

class SampleAnnotatedTools {
    @com.mcp.toolbox.Tool("greet", "向某人问好")
    fun greet(
        @Param(name = "name", description = "对方名字") name: String,
        @Param(name = "loud", description = "是否大写", required = false, default = "false") loud: Boolean
    ): String {
        val s = "Hello, $name!"
        return if (loud) s.uppercase() else s
    }

    @com.mcp.toolbox.Tool("add", "两数相加")
    fun add(
        @Param(name = "a", description = "加数") a: Int,
        @Param(name = "b", description = "加数") b: Int
    ): Int = a + b
}

/** 便捷：扫描示例注解工具。 */
fun sampleAnnotatedTools(): List<ToolDef> = ToolScanner.scan(SampleAnnotatedTools())

// ───────────────────────────────────────────────────────────
//  迷你数学求值器（递归下降）
// ───────────────────────────────────────────────────────────

internal fun evalMath(input: String): Double {
    val s = input.replace(" ", "")
    require(s.isNotEmpty()) { "空表达式" }
    return MathParser(s).parse()
}

/** 递归下降表达式求值器（成员方法间前向引用始终可解析）。 */
private class MathParser(private val s: String) {
    private var pos = 0

    fun parse(): Double {
        val v = expr()
        require(pos == s.length) { "无法解析剩余: ${s.substring(pos)}" }
        return v
    }

    private fun peek(): Char? = if (pos < s.length) s[pos] else null

    private fun expr(): Double {
        var v = term()
        while (peek() == '+' || peek() == '-') {
            val op = s[pos++]
            val r = term()
            v = if (op == '+') v + r else v - r
        }
        return v
    }

    private fun term(): Double {
        var v = factor()
        while (peek() == '*' || peek() == '/' || peek() == '%') {
            val op = s[pos++]
            val r = factor()
            v = when (op) {
                '*' -> v * r
                '/' -> v / r
                else -> v % r
            }
        }
        return v
    }

    private fun factor(): Double = when (peek()) {
        '-' -> { pos++; -factor() }
        '+' -> { pos++; factor() }
        '(' -> {
            pos++
            val v = expr()
            require(peek() == ')') { "缺少右括号" }
            pos++
            v
        }
        else -> {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            require(start != pos) { "非法字符：${peek()}" }
            s.substring(start, pos).toDouble()
        }
    }
}
