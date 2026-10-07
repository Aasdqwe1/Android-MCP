package com.mcp.llm

import com.mcp.core.llm.BackendType
import com.mcp.LogStore
import com.mcp.llm.MessageEvent
import com.mcp.toolbox.ToolCompiler
import com.mcp.toolbox.parseToolArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID

/**
 * OpenAI 兼容协议客户端（零依赖：HttpURLConnection + org.json）。
 *
 * 借助可配置的 [OpenAIClientConfig.baseUrl] 与 [OpenAIClientConfig.apiKey]，
 * 同一实现即可覆盖 OpenAI / Azure OpenAI / Ollama / vLLM / LM Studio / Groq /
 * OpenRouter / 任意" OpenAI 兼容" 本地或云端推理服务（"其他模型调用协议"）。
 *
 * 协议要点：
 *  - 请求 POST `{baseUrl}`（baseUrl 为用户填写的**完整**请求地址，原样使用，不做路径拼接），
 *    body 为 OpenAI chat 格式
 *    （messages / model / stream / temperature / max_tokens / enable_thinking / tools）。
 *  - 响应为标准 SSE：`data: {json}\n\n` 多帧，最后 `data: [DONE]` 结束。
 *  - 思考过程（reasoning_content，DeepSeek/OpenRouter 等扩展字段）→ [MessageEvent.Thinking]。
 *  - 正式回复（delta.content）→ [MessageEvent.Content]。
 *  - 工具调用（delta.tool_calls，按 index 分片流式到达）→ 累积后发出 [MessageEvent.ToolCall]。
 *  - 流结束后发出 [MessageEvent.Done]（思考 + 内容），由上层决定是否自动续聊。
 */
data class OpenAIClientConfig(
    /** API Key；本地服务（如 Ollama）可留空（不发 Authorization 头）。 */
    val apiKey: String = "",
    /** 兼容服务基址，例如 "https://api.openai.com/v1" 或 "http://localhost:11434/v1"。 */
    val baseUrl: String = "https://api.openai.com/v1",
    /** 模型名（覆盖 [LLMConfig.model] 的兜底）。 */
    val model: String = "gpt-4o",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    /** 单请求读取超时（毫秒），本地大模型可能生成较慢，默认放宽到 5 分钟。 */
    val readTimeoutMs: Int = 300_000,
    /**
     * 跳过 TLS 证书校验（信任任意服务端证书）。仅用于自签/内网 CA 的 OpenAI 兼容服务
     * （如局域网 vLLM、带 https 反向代理的 Ollama、公司内网网关），此时服务端证书不在
     * 系统/内置 CA 信任链中，会报 CERTIFICATE_UNKNOWN。
     * 注意：该选项会关闭证书校验，仅建议在可信内网环境开启。
     */
    val insecureSkipVerify: Boolean = false,
    /** 思考强度（reasoning_effort）：4 个档位 low / medium / high / xhigh，默认 high。
     * 开不开思考不由这里决定——由请求配置的 thinkingEnabled（聊天页「深度思考」开关）决定，
     * 关闭时发 "none"；非法档位（历史遗留的 max）由 [reasoningEffortLevel] 归一化。 */
    val reasoningEffort: String = "high",
)

/** 流式 tool_calls 累积器：OpenAI 把一次调用按 index 切成多个 delta 片段。 */
private class ToolCallAccum {
    var id: String = ""
    var type: String = "function"
    var name: String = ""
    val arguments = StringBuilder()
}

/**
 * [streamOnce] 的执行结果，供 [sendMessage] 决策是否触发重试。
 * - [Ok]                   ：请求正常结束（含已发出的 Error，如业务错误帧 / 空响应）。
 * - [HttpError]            ：服务端返回了可读的 HTTP 错误状态码（4xx/5xx）。
 * - [Handshake]            ：建连 / TLS 握手失败（多为 https 连到明文 HTTP 服务），可回退 http 重试。
 * - [RetryWithoutStream]   ：启用了 stream 但服务端拒绝（通常 400），需同 URL 改用非流式重试。
 */
private sealed interface StreamOutcome {
    object Ok : StreamOutcome
    data class HttpError(
        val code: Int,
        val message: String,
        /** 服务端 Retry-After 头解析出的等待毫秒数（秒值或 HTTP-date）；无则 null。 */
        val retryAfterMs: Long? = null
    ) : StreamOutcome
    data class Handshake(val ex: Throwable) : StreamOutcome
    object RetryWithoutStream : StreamOutcome

    /**
     * 上游瞬时故障被网关包在 **HTTP 200 的 SSE error 帧**里返回。
     *
     * 实测 opencode.ai/zen 会返回
     * `Streaming response failed: [502] Upstream error from Nvidia: Service temporarily overloaded`
     * （8 次请求撞到 3 次）。这类响应状态码是 200，若不单独成类、`streamOnce` 会照常返回 [Ok]，
     * 上层重试循环当成成功直接 break —— 一次都不重试。
     */
    data class StreamError(val message: String) : StreamOutcome
}

class OpenAIClient(
    private val config: OpenAIClientConfig
) : LLMClient {

    override val backendType: BackendType = BackendType.OPENAI

    /** Token 看板明细用：抓取本轮原始 HTTP 报文（请求体 + 各 SSE 响应帧）。null 表示不采集。 */
    override var rawCapture: RawCapture? = null

    companion object {
        /** 信任任意服务端证书的 SSLSocketFactory（insecureSkipVerify 开关启用时使用）。 */
        private val INSECURE_SOCKET_FACTORY: SSLSocketFactory by lazy {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
                .socketFactory
        }

        /** 跳过主机名校验（与 INSECURE_SOCKET_FACTORY 配套使用）。 */
        private val INSECURE_HOSTNAME_VERIFIER: HostnameVerifier by lazy {
            HostnameVerifier { _, _ -> true }
        }
    }

    override suspend fun isAuthenticated(): Boolean {
        // OpenAI 兼容服务即便无 key（Ollama）也算"可调用"；这里仅做非空兜底。
        return config.baseUrl.isNotBlank()
    }

    override suspend fun sendMessage(request: LLMRequest): Flow<MessageEvent> = channelFlow {
        val raw = config.baseUrl.trim().trimEnd('/')
        // 用户填写的即完整请求地址，原样使用（不做任何路径拼接）。
        // scheme 容错：
        //  - 填 https 但服务端只讲明文 HTTP（本地服务）→ 握手失败后回退 http 重试。
        //  - 填 http 但为**公网域名**（非 localhost/内网）→ 优先升级试 https（避免明文泄露 token + 302 重定向开销），
        //    失败再回退原 http。本地服务（localhost/127.0.0.1/内网段/IP）保持 http 原意。
        val base = normalizeScheme(raw)
        val endpoints = when {
            base.startsWith("https://", ignoreCase = true) ->
                listOf(base, "http://" + base.removePrefix("https://"))
            base.startsWith("http://", ignoreCase = true) && !isLocalhostOrLan(base) ->
                listOf("https://" + base.removePrefix("http://"), base)
            else ->
                listOf(base)
        }

        // 请求级重试：应对上游间歇不可用（网关 5xx / "Endpoint is unavailable"）。
        // 仅对"上游不可达"类错误重试（指数退避）；参数/认证等致命错误（4xx）不重试。
        // 每次尝试：流式优先，流式失败（拒绝或上游挂）→ 回退非流式保底。
        val MAX_ATTEMPTS = 3
        var attempt = 0
        var streamed = false
        var fatalMessage: String? = null
        var lastUpstreamError: String? = null
        // 最近一次带 Retry-After 指示的等待毫秒数，作为退避下界（T1）。
        var lastRetryAfterMs: Long? = null
        // 429 限流时透传给外层的状态码（外层据此复用设置页「限流重试」）。
        var rateLimitCode: Int? = null

        retry@ while (attempt < MAX_ATTEMPTS) {
            attempt++
            var endpointStreamed = false
            for ((ei, endpoint) in endpoints.withIndex()) {
                val first = streamOnce(this, request, endpoint, useStream = true)
                // 仅上游间歇不可用（5xx / 网关过载）时回退非流式保底；其余错误保持原样交后续分类。
                val outcome = when (first) {
                    StreamOutcome.RetryWithoutStream -> streamOnce(this, request, endpoint, useStream = false)
                    is StreamOutcome.HttpError -> if (classifyHttpError(first.code, first.message) == HttpErrorClass.UPSTREAM)
                        streamOnce(this, request, endpoint, useStream = false) else first
                    else -> first
                }
                when (outcome) {
                    StreamOutcome.Ok -> { endpointStreamed = true; break }
                    is StreamOutcome.HttpError -> when (classifyHttpError(outcome.code, outcome.message)) {
                        HttpErrorClass.FATAL -> {
                            fatalMessage = outcome.message
                            break@retry
                        }
                        HttpErrorClass.RATE_LIMIT -> {
                            // 429 限流：不在内层用硬编码退避重试，透传状态码给外层复用设置页「限流重试」。
                            rateLimitCode = outcome.code
                            fatalMessage = outcome.message
                            break@retry
                        }
                        HttpErrorClass.UPSTREAM -> {
                            // 服务端明确要求等待且超过上限 → 放弃重试（对齐 dsh maxDelayMs）。
                            if (outcome.retryAfterMs != null && outcome.retryAfterMs > MAX_RETRY_AFTER_MS) {
                                fatalMessage = "服务端要求等待 ${outcome.retryAfterMs / 1000}s（超过 ${MAX_RETRY_AFTER_MS / 1000}s 上限），停止重试：${outcome.message}"
                                break@retry
                            }
                            lastUpstreamError = outcome.message
                            outcome.retryAfterMs?.let { lastRetryAfterMs = maxOf(lastRetryAfterMs ?: 0, it) }
                            if (ei < endpoints.lastIndex) {
                                LogStore.w("OPENAI", "端点不可用（${outcome.code}），尝试下一候选: $endpoint")
                                continue
                            }
                        }
                    }
                    is StreamOutcome.Handshake -> {
                        if (lastUpstreamError == null) {
                            val msg = outcome.ex.message ?: ""
                            val isCertError = msg.contains("CERTIFICATE", ignoreCase = true)
                                || msg.contains("SSL", ignoreCase = true)
                                || msg.contains("trust", ignoreCase = true)
                                || msg.contains("cert chain", ignoreCase = true)
                            fatalMessage = if (isCertError && !config.insecureSkipVerify) {
                                "TLS 证书校验失败（$msg）。若服务端使用自签/内网 CA（如局域网 vLLM、公司网关），" +
                                    "请在 OpenAI 后端设置中开启「跳过证书校验(insecureSkipVerify)」后重试。"
                            } else if (config.insecureSkipVerify) {
                                "连接失败（已开启跳过证书校验仍失败）：请确认 baseUrl 可达且协议(http/https)正确。原因：$msg"
                            } else {
                                "连接失败：请确认 baseUrl 可达且协议(http/https)正确。原因：$msg"
                            }
                            LogStore.e("OPENAI", "OpenAIClient 异常: $fatalMessage")
                            break@retry
                        }
                        if (ei < endpoints.lastIndex) continue
                    }
                    is StreamOutcome.StreamError -> {
                        // 配额耗尽立即放弃；否则按上游瞬时故障逻辑（换端点 / 退避）。
                        if (isQuotaExceeded(outcome.message)) {
                            fatalMessage = outcome.message
                            break@retry
                        }
                        if (isUpstreamUnavailable(0, outcome.message)) {
                            lastUpstreamError = outcome.message
                            if (ei < endpoints.lastIndex) {
                                LogStore.w("OPENAI", "流内上游瞬时故障，尝试下一候选: $endpoint")
                                continue
                            }
                        } else {
                            fatalMessage = outcome.message
                            break@retry
                        }
                    }
                    StreamOutcome.RetryWithoutStream -> Unit
                }
            }
            if (endpointStreamed) { streamed = true; break@retry }
            if (fatalMessage != null) break@retry
            if (attempt < MAX_ATTEMPTS) {
                // 退避 = max(二次曲线, 服务端 Retry-After)（T1）：尊重服务端退避指引但不超过上限。
                val quadBackoff = attempt.toLong() * attempt * 800L
                val backoffMs = maxOf(quadBackoff, lastRetryAfterMs ?: 0)
                LogStore.w("OPENAI", "上游间歇不可用（尝试 $attempt/$MAX_ATTEMPTS），${backoffMs}ms 后重试: ${lastUpstreamError ?: ""}")
                delay(backoffMs)
            }
        }

        // 仅在未成功时统一上报一次错误（避免与 streamOnce 内部错误事件重复）。
        if (!streamed) {
            val err = fatalMessage ?: lastUpstreamError
            if (fatalMessage != null) {
                LogStore.e("OPENAI", "chat/completions 失败: $err")
                send(MessageEvent.Error(OpenAIException(rateLimitCode ?: -1, err ?: "未知错误")))
            } else {
                LogStore.e("OPENAI", "chat/completions 失败（上游持续不可用，重试 $MAX_ATTEMPTS 次）: ${err ?: ""}")
                send(MessageEvent.Error(OpenAIException(-1,
                    "请求失败，已重试 $MAX_ATTEMPTS 次仍失败。${err?.let { "（$it）" } ?: ""}" +
                    "建议稍后重试，或在网关侧为该模型配置更稳定的上游。")))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 规范化 baseUrl 的 scheme：
     *  - 已带 http:// 或 https:// → 原样保留（用户填写的即完整请求地址，原样使用）。
     *  - 仅填了 host:port/path（如 10.0.0.2:11434/v1/chat/completions）→ 默认补 http://，
     *    避免 "unknown protocol" 解析失败。
     */
    private fun normalizeScheme(raw: String): String {
        val s = raw.trim()
        return if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            s
        } else {
            "http://$s"
        }
    }

    /** 判断地址是否指向本机/内网（localhost、回环、私有网段、裸 IP），这类应保留用户填写的 http 原意。 */
    private fun isLocalhostOrLan(url: String): Boolean {
        val host = runCatching { URL(url).host }.getOrNull()?.lowercase() ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host == "[::1]") return true
        // 私有网段：10.x / 172.16-31.x / 192.168.x / 169.254.x（链路本地）
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return true
        if (host.startsWith("172.")) {
            val seg = host.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (seg in 16..31) return true
        }
        // 裸 IPv4（不含点分域名）也视为内网/直连，保留 http
        val isBareIp = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host)
        return isBareIp
    }

    /**
     * 判断 HTTP 错误是否属于"上游间歇不可用"，可安全重试。
     * 命中：网关类 5xx（502/503/504/529/500），或错误文案提及上游不可达
     * （如 "Endpoint is unavailable" / "Upstream request failed" / "temporarily unavailable"），
     * 或响应体读取中断（streamOnce 的 EOFException 分支，文案含「连接中断」）。
     * 不命中：4xx（参数/认证/限流）属于致命错误，重试无意义。
     */
    private fun isUpstreamUnavailable(code: Int, msg: String): Boolean {
        if (code == 500 || code == 502 || code == 503 || code == 504 || code == 529) return true
        val m = msg.lowercase()
        return m.contains("endpoint is unavailable")
            || m.contains("upstream request failed")
            || m.contains("upstream request")
            || m.contains("temporarily unavailable")
            || m.contains("bad gateway")
            || m.contains("service unavailable")
            || m.contains("gateway timeout")
            || m.contains("连接中断")
            || m.contains("broken pipe")
            || m.contains("premature close")
    }

    /**
     * 配额/余额耗尽（不可重试，立即放弃）。对齐 dsh error.ts `isQuotaExceededError`：
     * 余额没了还重试只在退避上空耗，且对用户无意义。
     *
     * 与 dsh 一致，优先级高于 429 限流判定（dsh httpErrorCode 先查配额再查 429）。
     * 用小写子串匹配近似 dsh 的"词边界"正则，足够覆盖常见英文文案（insufficient quota /
     * quota exceeded / out of credits / balance depleted …）。
     */
    private fun isQuotaExceeded(detail: String): Boolean {
        val m = detail.lowercase()
        return m.contains("insufficient quota") || m.contains("insufficient balance") || m.contains("insufficient credits")
            || m.contains("quota exceeded") || m.contains("quota exhausted") || m.contains("quota reached")
            || m.contains("usage limit exceeded") || m.contains("usage limit exhausted") || m.contains("usage limit reached")
            || m.contains("exceeded your quota") || m.contains("exceeded the quota") || m.contains("exceeded current quota")
            || m.contains("balance exhausted") || m.contains("balance depleted")
            || m.contains("credits exhausted") || m.contains("credits depleted")
            || m.contains("out of credits") || m.contains("out of budget")
    }

    /** HTTP 错误的最终分类（决定重试策略）。 */
    private enum class HttpErrorClass { FATAL, RATE_LIMIT, UPSTREAM }

    /**
     * 把 HTTP 错误归类：
     *  - [HttpErrorClass.FATAL]       ：4xx 参数/认证、上下文溢出、配额耗尽等，重试无意义。
     *  - [HttpErrorClass.RATE_LIMIT]  ：429 或明确 rate-limit 文案，可重试且应尊重服务端退避（T1）。
     *  - [HttpErrorClass.UPSTREAM]    ：5xx / 网关过载文案，瞬时可重试（换端点 / 退避）。
     *
     * 配额耗尽优先于 429 判定（对齐 dsh httpErrorCode 的次序）。
     */
    private fun classifyHttpError(code: Int, msg: String): HttpErrorClass {
        if (isQuotaExceeded(msg)) return HttpErrorClass.FATAL
        val m = msg.lowercase()
        if (code == 429 || m.contains("rate limit") || m.contains("too many requests")) {
            return HttpErrorClass.RATE_LIMIT
        }
        if (isUpstreamUnavailable(code, msg)) return HttpErrorClass.UPSTREAM
        return HttpErrorClass.FATAL
    }

    /** Retry-After 上限：服务端要求等待超过该值（默认 60s）时放弃重试（对齐 dsh maxDelayMs）。 */
    private val MAX_RETRY_AFTER_MS = 60_000L

    /**
     * 解析服务端 `Retry-After` 头，对齐 dsh adapter.ts `providerRetryAfterMs`：
     *  - 纯数字 → 秒数；
     *  - HTTP-date（如 `Wed, 21 Oct 2026 07:28:00 GMT`）→ 距现在的毫秒差。
     * 无法解析或 ≤0 时返回 null。
     */
    private fun parseRetryAfter(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        if (v.matches(Regex("^\\d+$"))) {
            val secs = v.toLongOrNull() ?: return null
            return if (secs > 0) secs * 1000 else null
        }
        // HTTP-date（RFC 1123）：EEE, dd MMM yyyy HH:mm:ss z
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
        fmt.isLenient = false
        val date = runCatching { fmt.parse(v) }.getOrNull() ?: return null
        val delay = date.time - System.currentTimeMillis()
        return if (delay > 0) delay else null
    }

    /**
     * 把 OpenAI 兼容的 usage 对象映射为 [MessageEvent.Usage]（双轨计量扩展）。
     * 解析顺序：先取 prompt/completion/total，再抽 reasoning_tokens 与 cache hit tokens。
     * 对齐 dsh translate.ts `mapUsage`：reasoning_tokens 来自 completion_tokens_details，
     * cache hit 来自 prompt_tokens_details.cached_tokens（部分网关用顶层 prompt_cache_hit_tokens）。
     */
    private fun parseUsage(u: JSONObject): MessageEvent.Usage {
        val prompt = u.optInt("prompt_tokens", -1)
        val completion = u.optInt("completion_tokens", 0)
        val total = u.optInt("total_tokens", if (prompt >= 0) prompt + completion else 0)
        val reasoningTokens = u.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens", -1) ?: -1
        val cacheReadTokens = u.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", -1)
            ?: u.optInt("prompt_cache_hit_tokens", -1)
        return MessageEvent.Usage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
            reasoningTokens = reasoningTokens,
            cacheReadTokens = cacheReadTokens
        )
    }

    /**
     * 单次请求（含流式 / 非流式两种响应形态）。通过 [useStream] 控制是否请求 SSE 分片流。
     * 响应形态自动识别：
     *  - 首行以 `{` 开头 → 单条 `chat.completion` JSON（非流式），直接解析。
     *  - 否则按 SSE `data:` 帧解析（流式）。
     * 事件（Thinking/Content/ToolCall/Done/Error）通过 [channel] 实时发出。
     */
    private suspend fun streamOnce(
        channel: SendChannel<MessageEvent>,
        request: LLMRequest,
        baseUrl: String,
        useStream: Boolean
    ): StreamOutcome {
        // 3xx 重定向循环：307/308 保留 POST + body 原样重发到 Location；
        // 301/302/303 同样手动跟随（POST 在部分实现上会转 GET，这里保持 POST 语义）。
        // 最多跟随 5 跳，防止重定向环。
        var currentUrl = baseUrl
        var conn: HttpURLConnection? = null
        var hops = 0
        while (hops < 5) {
            hops++
            conn = try {
                openConnection(request, currentUrl, useStream)
            } catch (e: Exception) {
                return StreamOutcome.Handshake(e)
            }

            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                runCatching { conn.disconnect() }
                return StreamOutcome.Handshake(e)
            }
            if (code in 300..399 && code != 304) {
                // 重定向：取 Location，解析为完整 URL（相对路径拼接当前 origin），重开连接
                val loc = conn.getHeaderField("Location")
                runCatching { conn.disconnect() }
                if (loc.isNullOrBlank()) {
                    return StreamOutcome.HttpError(code, "HTTP $code 重定向缺少 Location")
                }
                val next = try {
                    URL(URL(currentUrl), loc).toString()
                } catch (e: Exception) {
                    return StreamOutcome.HttpError(code, "HTTP $code 重定向 Location 无效: $loc")
                }
                LogStore.w("OPENAI", "HTTP $code 重定向: $currentUrl → $next")
                if (next == currentUrl) return StreamOutcome.HttpError(code, "HTTP $code 重定向环: $currentUrl")
                currentUrl = next
                continue
            }
            if (code !in 200..299) {
                if (code == -1) {
                    // 无可读 HTTP 状态码（多为握手 / 连接失败），交给上层回退逻辑。
                    runCatching { conn.disconnect() }
                    return StreamOutcome.Handshake(IOException("responseCode = -1"))
                }
                if (useStream && code in STREAM_UNSUPPORTED_CODES) {
                    // 服务端拒绝流式参数（如 400），上层会改用非流式重试。
                    runCatching { conn.disconnect() }
                    return StreamOutcome.RetryWithoutStream
                }
                val retryAfterHeader = conn.getHeaderField("Retry-After")
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                conn.disconnect()
                val msg = parseErrorMessage(err) ?: "HTTP $code"
                return StreamOutcome.HttpError(code, msg, parseRetryAfter(retryAfterHeader))
            }
            // 2xx：跳出重定向循环，继续读取响应体
            break
        }
        if (conn == null) {
            // 5 跳全部是重定向仍未拿到 2xx
            return StreamOutcome.HttpError(307, "重定向次数过多（5 跳）: $currentUrl")
        }

        val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
        // 读取首个非空行用于判定响应形态（单条 JSON vs SSE data: 帧）
        var firstLine: String? = null
        while (true) {
            val r = reader.readLine() ?: break
            if (r.isNotBlank()) { firstLine = r; break }
        }
        if (firstLine == null) {
            runCatching { conn.disconnect() }
            channel.send(MessageEvent.Error(OpenAIException(-1, "服务未返回任何内容（检查模型名 / baseUrl / 认证）")))
            return StreamOutcome.Ok
        }

        val toolAccum = LinkedHashMap<Int, ToolCallAccum>()
        var thinkingBuf = StringBuilder()
        var contentBuf = StringBuilder()
        // 流终止原因：stop=正常完成；length=服务端因 max_tokens 上限截断（思考+正文合计超限）。
        var finishReason: String? = null

        // ── 非流式：整段作为单个 chat.completion JSON 解析 ──
        if (firstLine!!.trim().startsWith("{")) {
            val buf = StringBuilder(firstLine)
            var l = reader.readLine()
            while (l != null) { buf.append(l); l = reader.readLine() }
            // 抓取原始响应（非流式：整段单条 JSON）—Token 看板明细「响应」块用
            rawCapture?.onResponseChunk(buf.toString())
            runCatching { emitSingleCompletion(channel, buf.toString(), toolAccum, thinkingBuf, contentBuf) }
                .onFailure {
                    LogStore.e("OPENAI", "非流式响应解析失败: ${it.message}")
                    channel.send(MessageEvent.Error(OpenAIException(-1, "非流式响应解析失败: ${it.message}")))
                }
            runCatching { reader.close() }
            runCatching { conn.disconnect() }
            return StreamOutcome.Ok
        }

        // ── 流式 SSE 解析（首行已是 data: 帧，直接接入循环）──
        val stream = reader
        var anyChunk = false
        try {
            var line = firstLine
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    line = stream.readLine()
                    continue
                }
                // SSE 注释行（心跳 ': ...'）忽略
                if (trimmed.startsWith(":")) {
                    line = stream.readLine()
                    continue
                }
                val data = when {
                    trimmed.startsWith("data:") -> trimmed.substring(5).trim()
                    // 非 data: 前缀的行（如 event:）忽略
                    else -> { line = stream.readLine(); continue }
                }
                if (data == "[DONE]") {
                    line = stream.readLine()
                    continue
                }
                if (data.isBlank()) { line = stream.readLine(); continue }

                val obj = runCatching { JSONObject(data) }.getOrNull()
                if (obj == null) {
                    LogStore.w("OPENAI", "跳过无法解析的 SSE 数据帧: ${data.take(200)}")
                    line = stream.readLine()
                    continue
                }
                anyChunk = true
                // 抓取原始响应帧（Token 看板明细「响应」块用）；error 帧也是合法 JSON，一并记录。
                rawCapture?.onResponseChunk(data)

                // 业务错误帧（部分服务在 200 内返回 error 字段）。
                //
                // 关键：网关会把**上游瞬时故障**包成 HTTP 200 + SSE error 帧返回
                // （实测：`Streaming response failed: [502] Upstream error from Nvidia:
                // Service temporarily overloaded`，8 次请求撞到 3 次）。
                // 若照旧「发 Error 事件 + continue」，本次 streamOnce 走到末尾仍返回 Ok，
                // 上层重试循环会当成成功直接 break —— 瞬时故障一次都不重试，
                // 用户侧表现就是「聊天窗口请求失败」而工具请求（另走一轮运气）却是好的。
                //
                // 因此按消息判定分流：
                //  - 瞬时故障（502/503/504/overloaded/unavailable…）→ 中止消费，交还可重试 outcome；
                //  - 认证 / 参数类致命错误 → 立即上报，不浪费重试次数与退避等待。
                if (obj.has("error")) {
                    val msg = parseErrorMessage(obj.toString()) ?: "服务返回 error 帧"
                    // 只有「还没吐出任何内容」时才允许重试：一旦已下发过正文/思考/工具调用，
                    // 重跑一轮会把同样的内容再发一遍，UI 出现重复吐字，比报错更糟。
                    val nothingEmitted = contentBuf.isEmpty() && thinkingBuf.isEmpty() && toolAccum.isEmpty()
                    if (nothingEmitted && isUpstreamUnavailable(0, msg)) {
                        LogStore.w("OPENAI", "流内上游瞬时故障（尚无输出），转为可重试: ${msg.take(160)}")
                        return StreamOutcome.StreamError(msg)
                    }
                    channel.send(MessageEvent.Error(OpenAIException(-1, msg)))
                    line = stream.readLine()
                    continue
                }

                // 真实 token 用量（route-priced）：带 usage 的帧通常同时是最后一帧且 choices 为空数组，
                // 因此必须在 choices 判空**之前**解析，否则会被当成空帧整个丢掉。
                // 对齐 deepseek-harness token-meter：用 provider 回传值校准本地启发式估算。
                obj.optJSONObject("usage")?.let { u ->
                    val prompt = u.optInt("prompt_tokens", -1)
                    if (prompt >= 0) {
                        channel.send(parseUsage(u))
                    }
                }

                val choices = obj.optJSONArray("choices")
                if (choices == null) {
                    // 无 choices（如 usage 帧）直接跳过
                    line = stream.readLine()
                    continue
                }
                for (i in 0 until choices.length()) {
                    val ch = choices.optJSONObject(i) ?: continue
                    // finish_reason 出现在 choices[i] 层（非 delta）：length=max_tokens 截断，stop=正常
                    ch.opt("finish_reason")?.takeIf { it is String && (it as String).isNotEmpty() }
                        ?.let { finishReason = it as String }
                    val delta = ch.optJSONObject("delta") ?: continue

                    // 思考过程：DeepSeek 系用 reasoning_content；OpenRouter 系用 reasoning
                    // （另附 reasoning_details 数组，内容相同，取其一即可）。
                    // 注意：分阶段输出时另一字段的值为 JSON null
                    //（如推理阶段 content=null、回复阶段 reasoning 字段=null）。
                    // 必须用 opt() 判断是否真实存在且非 null，避免 optString 把 null 当成
                    // 字面字符串 "null" 误发给用户（文档确认的流式标准设计）。
                    val rcRaw = delta.opt("reasoning_content")
                    val rRaw = delta.opt("reasoning")
                    val reasoningRaw = if (rcRaw is String) rcRaw else if (rRaw is String) rRaw else null
                    val reasoning = if (reasoningRaw is String) reasoningRaw else ""
                    if (reasoning.isNotEmpty()) {
                        thinkingBuf.append(reasoning)
                        channel.send(MessageEvent.Thinking(reasoning))
                    }

                    // 正式回复增量（同样需排除 JSON null）
                    val contentRaw = delta.opt("content")
                    val content = if (contentRaw is String) contentRaw else ""
                    if (content.isNotEmpty()) {
                        contentBuf.append(content)
                        channel.send(MessageEvent.Content(content))
                    }

                    // 工具调用（按 index 分片流式到达，需累积）
                    val tcs = delta.optJSONArray("tool_calls")
                    if (tcs != null) {
                        for (j in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(j) ?: continue
                            val idx = tc.optInt("index", 0)
                            val acc = toolAccum.getOrPut(idx) { ToolCallAccum() }
                            // 关键：部分网关（如 SenseNova）在 arguments 增量帧里会把 id/type/name
                            // 字段保留但置为空串 ""，因此必须「非空才覆盖」，否则会把首帧已解析出的
                            // 真实 name 覆盖成空串，导致 emitToolCalls 因 name 为空而 skip 整个调用。
                            // 官方 OpenAI 通常直接省略这些字段（不存在），故 has() 判定不会误覆盖；
                            // SenseNova 多发空串字段才暴露此问题。
                            tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { acc.id = it }
                            tc.optString("type", "").takeIf { it.isNotEmpty() }?.let { acc.type = it }
                            val fn = tc.optJSONObject("function")
                            if (fn != null) {
                                fn.optString("name", "").takeIf { it.isNotEmpty() }?.let { acc.name = it }
                                acc.arguments.append(fn.optString("arguments", ""))
                            }
                        }
                    }
                }
                line = stream.readLine()
            }
        } catch (e: CancellationException) {
            // 停止生成（取消 collect 协程）触发：原样上抛，让 finally 关闭 SSE 连接，
            // 取消信号再冒泡到 streamLLM 的 catch(isStopping) 分支发 "stopped" 收尾。
            // 切勿吞掉取消，也勿向已关闭的 channel 发 Error（那只会在 send 时再次抛 CancellationException）。
            throw e
        } catch (e: Exception) {
            val detail = buildList {
                add("type=${e.javaClass.simpleName}")
                e.message?.let { add("msg=$it") }
                e.stackTrace?.firstOrNull()?.let { add("at=${it.className}.${it.methodName}:${it.lineNumber}") }
            }.joinToString(" | ")
            LogStore.e("OPENAI", "OpenAIClient 异常: $detail")
            if (contentBuf.isEmpty() && thinkingBuf.isEmpty() && toolAccum.isEmpty()) {
                // 尚未吐出任何内容（口径同下方 SSE error 帧分支的 nothingEmitted）→ 重试
                // 不会重复输出已发送的 delta，可安全重试。
                // ⚠️ 这里绝不能返回 Ok：Ok 会让 sendMessage 的重试循环把本轮当成成功
                // （endpointStreamed=true）直接 break —— 传输中断一次都不会重试，用户只看到
                // 一条裸 EOFException，且下面的「未成功统一上报」也不会触发。
                //
                // 成因判定：EOFException 无 message（日志里缺 msg= 段即为此特征），由
                // okio RealBufferedSource.require 抛出。Android 框架 OkHttp（com.android.okhttp）
                // 里调用 require 的只有自动解压层：GzipSource.consumeHeader() 读 10 字节 gzip
                // 头、BrotliSource 读压缩字节 —— 即对端声明了 Content-Encoding: gzip/br 后在
                // 响应体传完前关闭连接（网关空闲超时 / 上游崩溃 / LB 重置）。
                //   - readTimeout 超时会抛 SocketTimeoutException，故此处必为「对端提前关连接」
                //     而非「客户端等超时」；
                //   - 分块传输截断可排除：ChunkedSource.readChunkLength 用 readByte + 自行
                //     throw EOFException，栈帧会停在 ChunkedSource 而非 RealBufferedSource.require。
                return StreamOutcome.StreamError(
                    "连接中断：服务端在响应完成前关闭了连接（${e.javaClass.simpleName}）"
                )
            }
            // 已交付部分内容：重试会重放已发送的 delta（ChatBridge 把 content 追加进
            // StreamTaskManager.lastContent 并逐段 emit 给前端），表现为回复文本重复，
            // 故接受部分结果，仅上报一次错误。
            channel.send(
                MessageEvent.Error(
                    OpenAIException(
                        -1,
                        "响应中途中断（已收到部分内容）：${e.message ?: e.javaClass.simpleName}"
                    )
                )
            )
            return StreamOutcome.Ok
        } finally {
            runCatching { stream.close() }
            runCatching { conn.disconnect() }
        }

        if (!anyChunk) {
            channel.send(MessageEvent.Error(OpenAIException(-1, "服务未返回任何内容（检查模型名 / baseUrl / 认证）")))
            return StreamOutcome.Ok
        }

        val droppedCalls = emitToolCalls(channel, toolAccum, finishReason)
        val truncated = finishReason == "length"
        LogStore.i("OPENAI", "流结束, thinking=${thinkingBuf.length} content=${contentBuf.length} toolCalls=${toolAccum.size} droppedTruncated=$droppedCalls finish_reason=${finishReason ?: "?"}")
        // 思考+正文合计被 max_tokens 截断时，把提示并入 content，避免用户看到静默空白；
        // 工具调用参数被截断时同理（emitToolCalls 已跳过残缺调用），优先给出更具体的提示。
        val finalContent = contentBuf.toString()
        val annotatedContent = when {
            droppedCalls > 0 ->
                "\n\n> ⚠️ $droppedCalls 个工具调用的参数因超过 max_tokens 输出上限被截断，已跳过执行。\n> 可在设置中调大 max_tokens 后重试，或拆分任务减少单次输出量。"
            truncated && finalContent.isEmpty() && thinkingBuf.isNotEmpty() ->
                "\n\n> ⚠️ 思考内容过长，已超过 max_tokens 输出上限，正文未能生成。\n> 可在设置中调大 max_tokens，或降低思考强度（reasoning_effort）。"
            else -> finalContent
        }
        // 即使存在工具调用也发 Done —— 上层 handleDone 会检测到 pendingToolJobs 并自动续聊
        channel.send(MessageEvent.Done(thinkingBuf.toString(), annotatedContent, truncated))
        return StreamOutcome.Ok
    }

    /** 解析单条（非流式）chat.completion 响应并发出事件。 */
    private suspend fun emitSingleCompletion(
        channel: SendChannel<MessageEvent>,
        json: String,
        toolAccum: LinkedHashMap<Int, ToolCallAccum>,
        thinkingBuf: StringBuilder,
        contentBuf: StringBuilder
    ) {
        val obj = JSONObject(json)
        // 业务错误帧（部分服务在 200 内返回 error 字段）
        if (obj.has("error")) {
            val msg = parseErrorMessage(obj.toString()) ?: "服务返回 error 帧"
            channel.send(MessageEvent.Error(OpenAIException(-1, msg)))
            return
        }
        val choices = obj.optJSONArray("choices") ?: throw IOException("响应缺少 choices 字段")
        // 真实 token 用量（route-priced），语义同流式路径；先于内容事件发出，
        // 让上层在收到 Done 之前就完成启发式估算的校准。
        obj.optJSONObject("usage")?.let { u ->
            val prompt = u.optInt("prompt_tokens", -1)
            if (prompt >= 0) {
                channel.send(parseUsage(u))
            }
        }
        val ch = choices.optJSONObject(0) ?: throw IOException("choices[0] 缺失")
        // finish_reason：length=服务端因 max_tokens 上限截断（与流式路径同一判定）
        val finishReason = (ch.opt("finish_reason") as? String)?.takeIf { it.isNotEmpty() }
        // 非流式下内容在 message 而非 delta
        val message = ch.optJSONObject("message") ?: throw IOException("message 字段缺失")
        // 排除 JSON null：DeepSeek 等模型在分阶段输出时另一字段为 null，
        // optString 会把 null 当成字面 "null" 字符串。
        // 思考过程逐级回退：DeepSeek 系 reasoning_content；OpenRouter 系 reasoning /
        // reasoning_details[{type:"reasoning.text", text}]。
        val reasoning = listOf(
            if (message.isNull("reasoning_content")) "" else message.optString("reasoning_content", ""),
            if (message.isNull("reasoning")) "" else message.optString("reasoning", ""),
            message.optJSONArray("reasoning_details")?.let { arr ->
                buildString {
                    for (i in 0 until arr.length()) {
                        append(arr.optJSONObject(i)?.optString("text", "") ?: "")
                    }
                }
            } ?: ""
        ).firstOrNull { it.isNotEmpty() } ?: ""
        val content = if (message.isNull("content")) "" else message.optString("content", "")
        if (reasoning.isNotEmpty()) {
            thinkingBuf.append(reasoning)
            channel.send(MessageEvent.Thinking(reasoning))
        }
        if (content.isNotEmpty()) {
            contentBuf.append(content)
            channel.send(MessageEvent.Content(content))
        }
        // 工具调用（非流式为完整对象，无需分片累积）
        val tcs = message.optJSONArray("tool_calls")
        if (tcs != null) {
            for (j in 0 until tcs.length()) {
                val tc = tcs.optJSONObject(j) ?: continue
                val idx = tc.optInt("index", 0)
                val acc = toolAccum.getOrPut(idx) { ToolCallAccum() }
                if (tc.has("id")) acc.id = tc.optString("id", "")
                if (tc.has("type")) acc.type = tc.optString("type", "function")
                val fn = tc.optJSONObject("function")
                if (fn != null) {
                    if (fn.has("name")) acc.name = fn.optString("name", "")
                    acc.arguments.append(fn.optString("arguments", ""))
                }
            }
        }
        emitToolCalls(channel, toolAccum, finishReason)
        channel.send(
            MessageEvent.Done(
                thinkingBuf.toString(),
                contentBuf.toString(),
                finishReason == "length"
            )
        )
    }

    /**
     * 发出累积的完整工具调用。
     *
     * @param finishReason 流终止原因；为 "length" 时说明输出被 max_tokens 截断，
     *   此时累积到的 arguments 很可能是半截 JSON——直接下发会进入工具链路并触发
     *   「工具参数解析失败」自愈重试（浪费一轮对话且大概率再次失败），故跳过执行，
     *   返回丢弃数由调用方并入正文提示模型/用户。
     * @return 因截断且参数残缺而被丢弃的工具调用数量。
     */
    private suspend fun emitToolCalls(
        channel: SendChannel<MessageEvent>,
        toolAccum: LinkedHashMap<Int, ToolCallAccum>,
        finishReason: String?
    ): Int {
        var dropped = 0
        for ((_, acc) in toolAccum) {
            if (acc.name.isEmpty()) continue
            val id = acc.id.ifEmpty { "call_${UUID.randomUUID()}" }
            if (finishReason == "length") {
                val argsComplete = runCatching { parseToolArguments(acc.arguments.toString().ifBlank { "{}" }) }.isSuccess
                if (!argsComplete) {
                    dropped++
                    LogStore.w("OPENAI", "工具调用因 max_tokens 截断且参数残缺，已跳过: id=$id name=${acc.name} args=${acc.arguments.length}字符")
                    continue
                }
            }
            LogStore.i("OPENAI", "工具调用: id=$id name=${acc.name} args=${acc.arguments.length}字符")
            channel.send(MessageEvent.ToolCall(id, acc.name, acc.arguments.toString()))
        }
        return dropped
    }

    /** 打开并写入连接。[baseUrl] 为用户填写的完整请求地址（原样使用）；[useStream] 决定是否请求 SSE 分片流。 */
    private fun openConnection(request: LLMRequest, baseUrl: String, useStream: Boolean): HttpURLConnection {
        // 兼容 OpenAI SDK 习惯：baseUrl 通常填 ".../v1"（网关根），此处自动补 /chat/completions；
        // 若已是完整端点（以 /chat/completions 结尾）则不重复拼接。既允许填完整路径，也允许只填网关根。
        var url = baseUrl.trimEnd('/')
        if (!url.endsWith("/chat/completions")) {
            url = "$url/chat/completions"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        // 关闭自动重定向：307/308 在 Android 上默认不跟随（保留 POST 方法体），
        // 301/302/303 的跟随行为也因实现而异。统一由 streamOnce 手动按 Location 重试，
        // 保证跨端点、跨协议的 3xx 都能正确处理。
        conn.instanceFollowRedirects = false
        // 跳过证书校验：仅作用于本连接，不影响全局默认 SSL（其他 https 请求仍走正常校验）。
        if (config.insecureSkipVerify && conn is HttpsURLConnection) {
            conn.sslSocketFactory = INSECURE_SOCKET_FACTORY
            conn.hostnameVerifier = INSECURE_HOSTNAME_VERIFIER
        }
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = config.readTimeoutMs
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", if (useStream) "text/event-stream" else "application/json")
        conn.setRequestProperty("User-Agent", "AgentToolbox/1.0")
        if (config.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }

        val model = request.config.model.ifBlank { config.model }
        // max_tokens：请求级覆盖 > 客户端配置（>0 才发送，0/负数视为不限制）。
        val maxTokens = request.config.maxTokens.takeIf { it > 0 } ?: config.maxTokens
        val tools = request.tools
        val body = JSONObject().apply {
            put("model", model)
            put("messages", buildMessagesJson(request.messages))
            put("stream", useStream)
            // 流式时附带 usage（对齐 DeepSeek/OpenAI 标准；有界会话管理需要 token 计数）。
            // stream_options 仅在 stream=true 时合法，非流式回退不带。
            if (useStream) put("stream_options", JSONObject().apply { put("include_usage", true) })
            put("temperature", request.config.temperature)
            // 输出长度上限：普通/兼容模型（OpenAI 非推理、Ollama、vLLM、SenseNova、OpenRouter 等）
            // 用 max_tokens；OpenAI 官方推理模型（o1/o3/o4/gpt-5 等）在 Chat Completions 下只能用
            // max_completion_tokens，发 max_tokens 会报错，故对此类端点改用标准字段。
            if (maxTokens > 0) {
                if (isOpenAIReasoningModel(baseUrl, model)) put("max_completion_tokens", maxTokens)
                else put("max_tokens", maxTokens)
            }
            // 深度思考模式开关：开不开思考**唯一**由聊天页的「深度思考」开关决定——
            // chat.html / chat_desktop.html 两个页面各有一个 #think 复选框，均经
            // ChatBridge.sendMessage / sendMessageWithAttachments / regenerate 传到这里。
            //  - DeepSeek 官方端点（《DeepSeek API 请求指南》thinking(object) 形态）：
            //    开启 → thinking:{type:"enabled"} + reasoning_effort 档位；
            //    关闭 → thinking:{type:"disabled"}（显式禁用；不传则服务端默认开启思考）。
            //    该端点枚举仅 low/high，不含 none，故关闭时不发 reasoning_effort 字段。
            //  - OpenRouter 端点（官方标准形态，《Reasoning Tokens》文档的 reasoning 对象，
            //    统一封装各厂商差异）：reasoning:{effort:"xhigh|high|medium|low|max|minimal|none"}。
            //    关闭时不发送（未开启的模型本就不返回思考字段，无需显式 effort:"none"）。
            //  - 其他 OpenAI 兼容网关（SenseNova 等）：只认标准顶层 reasoning_effort，
            //    枚举 low/medium/high/xhigh/none；关闭时显式发 none，免得服务端用
            //    自己的默认值把思考重新开回来。
            // 档位固定 4 档（AuthPrefs.REASONING_EFFORT_LEVELS）；历史遗留的 "max" 由
            // [reasoningEffortLevel] 归一化为 xhigh，否则服务端直接报
            // `field ReasoningEffort invalid, should be one of: low, medium, high, xhigh, none`。
            val effort = if (isDeepSeekEndpoint(baseUrl, model)) {
                deepseekEffortLevel(config.reasoningEffort)
            } else {
                reasoningEffortLevel(config.reasoningEffort)
            }
            if (request.config.thinkingEnabled) {
                put("reasoning_effort", effort)
            } else if (acceptsReasoningEffortNone(baseUrl)) {
                put("reasoning_effort", REASONING_EFFORT_NONE)
            }
            // DeepSeek 官方端点私有扩展：额外发送 thinking:{type:enabled|disabled} 对象。
            // SenseNova 等 OpenAI 兼容网关不使用该对象（已由 isDeepSeekEndpoint 排除），避免误发导致 400。
            if (isDeepSeekEndpoint(baseUrl, model)) {
                put("thinking", JSONObject().apply {
                    put("type", if (request.config.thinkingEnabled) "enabled" else "disabled")
                })
            }
            // OpenRouter 标准 reasoning 配置对象；响应中思考内容在
            // message.reasoning / delta.reasoning（reasoning_details 为同内容的结构化数组）。
            if (request.config.thinkingEnabled && isOpenRouterEndpoint(baseUrl)) {
                put("reasoning", JSONObject().apply {
                    put("effort", reasoningEffortLevel(config.reasoningEffort))
                })
            }
            // 标准 OpenAI response_format：仅显式配置时发送；null 不发送，向后兼容。
            // 当前以 type 字符串驱动（如 "json_object"），即标准形态 response_format:{type:"json_object"}；
            // 带 schema 的 json_schema 完整形态（需额外 json_schema:{name,schema,strict} 体）暂未实现。
            if (!request.config.responseFormat.isNullOrBlank()) {
                put("response_format", JSONObject().apply { put("type", request.config.responseFormat) })
            }
            if (!tools.isNullOrEmpty()) {
                // ToolCompiler.toOpenAi 返回 kotlinx JsonArray，转 org.json 复用同一份工具定义。
                // 转写失败时降级为不带 tools（不阻塞对话）。
                runCatching { JSONArray(ToolCompiler.toOpenAi(tools).toString()) }
                    .onSuccess { put("tools", it) }
                    .onFailure { LogStore.w("OPENAI", "工具定义序列化失败，跳过 tools: ${it.message}") }
            }
        }
        LogStore.i("OPENAI", "请求 $url stream=$useStream model=$model messages=${request.messages.size} tools=${tools?.size ?: 0}")
        conn.doOutput = true
        // 抓取原始请求体（Token 看板明细「请求」块用）
        rawCapture?.onRequest(body.toString())
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return conn
    }

    /**
     * 判断是否为 DeepSeek 端点（baseUrl 或模型名含 deepseek）。
     * 仅在此类端点发送 reasoning 等 DeepSeek 专属扩展字段，
     * 避免对其他 OpenAI 兼容服务（Ollama / LM Studio / 标准 OpenAI 等）发未支持字段导致 400。
     */
    private fun isDeepSeekEndpoint(baseUrl: String, model: String): Boolean {
        val u = baseUrl.lowercase()
        // SenseNova 是 OpenAI 兼容网关（token.sensenova.cn/v1），其 deepseek-v4-flash 等模型
        // 仅接受标准顶层 reasoning_effort，不吃 DeepSeek 官方私有 thinking 对象，须排除。
        if ("sensenova" in u) return false
        val m = model.lowercase()
        return "deepseek" in u || "deepseek" in m
    }

    /** reasoning_effort 枚举里的「关闭思考」取值（不能是 const：它位于类体内）。 */
    private val REASONING_EFFORT_NONE = "none"

    /**
     * 归一化思考强度档位，保证只发出服务端枚举里的值（4 档：low / medium / high / xhigh）。
     * 历史配置里的 "max" 在多数网关（SenseNova 等）会直接报 400，故降级为 xhigh。
     * AuthPrefs 读取时已归一化过一次，这里兜住直接构造 OpenAIClientConfig 的路径。
     */
    private fun reasoningEffortLevel(v: String): String = when (v) {
        "low", "medium", "high", "xhigh" -> v
        "max" -> "xhigh"
        "minimal" -> "low"
        else -> "high"
    }

    /**
     * 该端点的 reasoning_effort 枚举是否含 "none"（显式关闭思考的取值）。
     * SenseNova 网关实测枚举为 low/medium/high/xhigh/none；标准 OpenAI 推理模型
     * （o 系列 / gpt-5）只有 low/medium/high，发 none 会 400，故不列入。
     */
    private fun acceptsReasoningEffortNone(baseUrl: String): Boolean =
        "sensenova" in baseUrl.lowercase()

    /**
     * DeepSeek 官方端点（含 api.deepseek.com 的 OpenAI 兼容网关）的 reasoning_effort
     * 只有 low / high 两档，发 medium / xhigh 会被拒。4 档 UI 里选到这两档时就近降级：
     * medium→low、xhigh→high，保证任何档位都不会打到非法枚举。
     */
    private fun deepseekEffortLevel(v: String): String = when (reasoningEffortLevel(v)) {
        "high", "xhigh" -> "high"
        else -> "low"   // low / medium → low
    }

    /** 判断是否为 OpenRouter 端点（baseUrl 含 openrouter）：思考开关走顶层 reasoning_effort。 */
    private fun isOpenRouterEndpoint(baseUrl: String): Boolean {
        return "openrouter" in baseUrl.lowercase()
    }

    /**
     * 判断是否为 OpenAI 官方推理模型（o1/o3/o4/gpt-5 等）。
     * 这类模型在 Chat Completions 协议下只能用 [max_completion_tokens]，发 max_tokens 会报错，
     * 故对其实行字段替换。仅匹配官方 host（api.openai.com），避免误伤 OpenRouter / Azure 等网关
     * （它们统一用 max_tokens 并在内部转换）。
     */
    private fun isOpenAIReasoningModel(baseUrl: String, model: String): Boolean {
        val u = baseUrl.lowercase()
        if ("openai.com" !in u) return false
        val m = model.lowercase()
        return m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") ||
               m.startsWith("gpt-5") || m.startsWith("gpt-4.5")
    }

    /** 启用 stream 时，服务端以此类状态码拒绝（多为不支持流式）→ 触发非流式重试。 */
    private val STREAM_UNSUPPORTED_CODES = setOf(400, 405, 406, 415, 426, 501)

    /** 把 [ChatMessage] 列表渲染为 OpenAI messages JSON 数组。 */
    private fun buildMessagesJson(messages: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(when (m.role) {
                "assistant" -> JSONObject().apply {
                    put("role", "assistant")
                    // 对齐 DeepSeek 官方 wire 规范（types.ts / serialize.ts）：思考或工具调用轮
                    // content 发空串 ""，绝不给 null——部分网关直接拒 null，且 null 一旦写进会话
                    // 日志会被后续每一轮回放，导致整段会话固化报错。
                    put("content", m.content ?: "")
                    // DeepSeek 思考模式 + function calling 的硬性要求：tool-call 轮必须原样回传上一轮
                    // 的 reasoning_content（CoT passback），否则上游拒绝或丢失思考签名。仅在有思考时带。
                    if (!m.reasoning.isNullOrBlank()) put("reasoning_content", m.reasoning)
                    if (!m.toolCalls.isNullOrEmpty()) {
                        val tca = JSONArray()
                        for (tc in m.toolCalls) {
                            // 防御：OpenAI 要求回传的 tool_call 中 function.name 非空、arguments
                            // 为合法 JSON 串。若模型对无参工具发了空 arguments("")，或重建历史时
                            // 丢了参数，下游会报 "function/name/arguments cannot be empty"(400)。
                            // 故 name 为空跳过该调用，arguments 空白补全为 "{}"。
                            if (tc.function.name.isBlank()) {
                                LogStore.w("OPENAI", "跳过 name 为空的 tool_call(id=${tc.id})，避免回传触发 400")
                                continue
                            }
                            val fnArgs = tc.function.arguments.ifBlank { "{}" }
                            tca.put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", tc.type)
                                put("function", JSONObject().apply {
                                    put("name", tc.function.name)
                                    put("arguments", fnArgs)
                                })
                            })
                        }
                        if (tca.length() > 0) put("tool_calls", tca)
                    }
                }
                "tool" -> JSONObject().apply {
                    put("role", "tool")
                    // 空工具输出仍需有内容（对齐 DeepSeek：空串会被部分网关拒），用占位串。
                    put("content", m.content ?: "(no output)")
                    put("tool_call_id", m.toolCallId ?: "")
                }
                else -> JSONObject().apply {
                    put("role", m.role)
                    // 多模态：imageUrls 非空时，content 渲染为 text + image_url 数组
                    // （OpenAI 多模态消息格式；text 与 image 并存，无 text 时只发 image）。
                    if (!m.imageUrls.isNullOrEmpty()) {
                        val parts = JSONArray()
                        if (!m.content.isNullOrEmpty()) {
                            parts.put(JSONObject().apply {
                                put("type", "text")
                                put("text", m.content)
                            })
                        }
                        for (url in m.imageUrls) {
                            parts.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().put("url", url))
                            })
                        }
                        put("content", parts)
                    } else {
                        put("content", m.content ?: "")
                    }
                }
            })
        }
        return arr
    }

    /** 从错误响应文本/帧中提取可读信息。 */
    private fun parseErrorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            if (o.has("error")) {
                val e = o.get("error")
                when (e) {
                    is JSONObject -> e.optString("message", raw)
                    else -> e.toString()
                }
            } else {
                o.optString("message", raw)
            }
        }.getOrElse { raw.take(500) }
    }
}

/** OpenAI 兼容服务异常。 */
class OpenAIException(val httpCode: Int, message: String) : Exception(message)