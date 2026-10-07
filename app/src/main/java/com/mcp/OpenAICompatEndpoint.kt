package com.mcp

import android.content.Context
import com.mcp.core.llm.BackendType
import com.mcp.apifwd.CompatChatRunner
import com.mcp.apifwd.CompatConcurrency
import com.mcp.apifwd.CompatPlan
import com.mcp.apifwd.afterFork
import com.mcp.apifwd.afterIncremental
import com.mcp.apifwd.afterPacked
import com.mcp.apifwd.ExternalConversationStore
import com.mcp.apifwd.deriveConversationKey
import com.mcp.apifwd.normalizeClientMessages
import com.mcp.apifwd.newConversationRecord
import com.mcp.apifwd.ConvMatch
import com.mcp.apifwd.decideBound
import com.mcp.apifwd.matchConversation
import com.mcp.apifwd.planCompat
import com.mcp.apifwd.renderTranscript
import com.mcp.apifwd.sanitizeKeyPart
import com.mcp.apifwd.textFingerprint
import com.mcp.apifwd.toolResultsPrompt
import com.mcp.data.LocalStore
import com.mcp.apifwd.turnRecordOf
import com.mcp.deepseek.AuthPrefs
import com.mcp.llm.MessageEvent
import com.mcp.llm.ChatMessage
import com.mcp.llm.ToolCall
import com.mcp.llm.ToolCallFunction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * OpenAI 兼容 API 端点（发射方向：把本机 LLM 能力以 OpenAI 格式暴露出去）。
 *
 * 与 `/mcp`（把**工具**发射给外部）互补：这里发射的是**对话能力**——
 * 外部客户端（OpenAI SDK / Cherry Studio / NextChat / LangChain / Cursor 自定义模型…）
 * 把本机当成一个 OpenAI 兼容的 LLM 服务端点。
 *
 * 设计要点（按用户确认的语义）：
 *  - **透传优先**：外部带 `tools` 就原样带给上游，不带就不带；本机工具**不注入**。
 *    本质是让外部「使用」本机模型通道，而不是让本机工具掺和进来。
 *  - **上下文以外部 messages 为准**：完全忽略内部会话历史，符合 OpenAI 无状态语义。
 *  - **会话仅作归属**：自动使用 App 当前选中会话，用于日志 / Token 统计，不影响上下文。
 *  - **鉴权复用 MCP**：`Authorization: Bearer <McpPrefs.token>`，token 为空则不校验。
 *  - **支持流式**：`stream=true` 走 SSE（`data: {...}` 帧 + `data: [DONE]`）。
 *
 * 路径：`/v1/chat/completions`、`/v1/models`（以及 `/web` 前缀下的等价路径）。
 */
object OpenAICompatEndpoint {

    private const val TAG = "OPENAI_COMPAT"


    /** 非流式等待上游的硬上限（秒）。超时必须走错误路径，不能把截断结果当成功。 */
    private const val UPSTREAM_TIMEOUT_SEC = 280L

    /** SSE 流的终止帧。**任何**终止路径（成功 / 错误 / 超时）都必须发它。 */
    private const val DONE_FRAME = "data: [DONE]\n\n"

    /**
     * 一次 /v1/chat/completions 的结果。
     *
     * 用类型携带状态码，取代旧的"返回 String、由路由层 contains("error") 嗅探"。
     * 旧写法有两个独立缺陷：模型正文里出现 error 字样就会把 200 判成 400；
     * 而 401 / 500 又一律被压成 400，客户端无法据状态码决定重新认证或退避重试。
     */
    sealed interface CompatResult {
        /** 成功。非流式时 [body] 是完整响应 JSON；流式时帧已由 emit 写出，[body] 为空串。 */
        data class Ok(val body: String) : CompatResult

        /** 失败。[httpStatus] 是**应当返回给客户端的真实 HTTP 状态码**。 */
        data class Err(val httpStatus: Int, val type: String, val message: String) : CompatResult
    }

    /**
     * 请求是否要求流式。
     *
     * 路由层必须**先**知道这一点：错误响应要与成功响应同形态
     * （流式走 SSE 帧，非流式走 JSON），否则流式客户端会收到一段裸 JSON 而解析失败。
     * 因此它要在鉴权之前就能算出来。
     */
    fun wantsStream(bodyJson: String): Boolean =
        runCatching { JSONObject(bodyJson).optBoolean("stream", false) }.getOrDefault(false)

    /**
     * 是否需要鉴权；返回 null 表示放行，否则是拒绝原因。
     *
     * 复用 [McpPrefs.checkBearer]（三处调用点共用一份解析，避免"同一份 token 三种口径"）。
     *
     * 路由层已经**先**校验过一次（鉴权必须早于会话解析，见 openAiCompatRoute）；
     * 这里是纵深防御 —— 本对象是公开 API，将来若有第二个调用点，不该默认它已经鉴权过。
     */
    private fun checkAuth(context: Context, authHeader: String?): String? =
        McpPrefs.checkBearer(context, authHeader)

    /** `GET /v1/models` 响应：暴露当前后端与模型名，供客户端探测。 */
    fun models(context: Context, auth: AuthPrefs): JSONObject {
        val model = currentModelName(auth)
        return JSONObject()
            .put("object", "list")
            .put("data", JSONArray().put(
                JSONObject()
                    .put("id", model)
                    .put("object", "model")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("owned_by", "agent-toolbox")
            ))
    }

    private fun currentModelName(auth: AuthPrefs): String = runCatching {
        when (auth.getBackend()) {
            BackendType.OPENAI -> auth.getOpenAIModel().ifBlank { "gpt-4o" }
            BackendType.WEB_AUTOMATION -> "web-automation"
            BackendType.LITERT -> "litert-local"
            BackendType.MNN -> "mnn-local"
        }
    }.getOrDefault("agent-toolbox")

    /**
     * 处理一次 `POST /v1/chat/completions`。
     *
     * @param bodyJson 原始请求体
     * @param authHeader Authorization 头（纵深防御；路由层已先校验过一次）
     * @param conversationIdHeader X-Conversation-Id 头（外部对话标识，优先级最高）
     * @param emit 流式帧写出回调（非流式传空实现）
     */
    fun chatCompletions(
        context: Context,
        auth: AuthPrefs,
        bodyJson: String,
        authHeader: String?,
        conversationIdHeader: String?,
        sessionIdHeader: String?,
        emit: (String) -> Unit,
    ): CompatResult {
        val stream = wantsStream(bodyJson)

        // 是否已向客户端写过帧。写过就只能"流内报错 + 收尾"（HTTP 状态码早已定格为 200）；
        // 没写过则回 Err，由路由层按真实状态码渲染。
        var started = false
        fun send(frame: String) {
            started = true
            emit(frame)
        }

        fun fail(code: Int, msg: String, type: String): CompatResult {
            LogStore.e(TAG, "chat/completions 失败($code): $msg")
            if (stream && started) {
                emit(errorFrame(msg))
                emit(DONE_FRAME)
                return CompatResult.Ok("")
            }
            return CompatResult.Err(code, type, msg)
        }

        checkAuth(context, authHeader)?.let { reason ->
            return fail(401, reason, "invalid_request_error")
        }
        val body = runCatching { JSONObject(bodyJson) }.getOrElse {
            return fail(400, "请求体不是合法 JSON: ${it.message}", "invalid_request_error")
        }

        // n > 1 显式拒绝：本机后端每次只产出一个候选，
        // 静默只回 1 条会让客户端按 choices[0..n-1] 取用时直接错位。
        if (body.optInt("n", 1) > 1) {
            return fail(400, "不支持 n > 1（本机后端每次只产出一个候选）", "invalid_request_error")
        }

        // ── tools：转成**文本声明**下发，而不是拒绝 ──
        // 本机两个在用后端都没有原生 function calling（Web 自动化忽略 tools 参数、
        // DeepSeek 逆向根本没有该参数），但它们的**文本工具协议**是现成的：
        // 把工具清单按同一套行式协议写进 prompt，模型就会按文本输出调用。
        //
        // 这与 App 内部的做法完全一致（PromptComposer 首轮注入「协议指南 + 工具清单」），
        // 所以 /v1 只需要负责把外部声明的工具**如实转发成同一份文本**，
        // 剩下的交给模型按文本回复即可 —— 不需要替客户端执行工具。
        val toolsJson = body.optJSONArray("tools")
        val toolsDeclaration = if (toolsJson != null && toolsJson.length() > 0) {
            renderToolsDeclaration(toolsJson)
        } else null

        val model = body.optString("model", currentModelName(auth))

        // thinking：映射客户端的 reasoning_effort。只能做"开/关"——
        // DeepSeek 逆向协议发的是 thinking_enabled: bool，没有档位概念。
        val effort = body.optString("reasoning_effort", "").trim().lowercase()
        val thinking = when {
            effort == "none" -> false
            effort.isNotEmpty() -> true
            else -> ApiForwardPrefs.isThinkingDefault(context)
        }

        // 参数可执行性：本机两个在用后端都不暴露采样参数（DeepSeek 逆向是网页会话协议、
        // Web 自动化是驱动网页）。这里**不**回 400 —— 绝大多数客户端默认就带 temperature，
        // 拒绝会让端点直接不可用；但必须留痕，否则用户会以为自己设的值生效了。
        if (body.has("temperature") || body.has("top_p")) {
            LogStore.w(TAG, "temperature/top_p 对当前后端 ${auth.getBackend()} 不可执行，已忽略")
        }
        if (body.has("max_tokens") || body.has("max_completion_tokens")) {
            LogStore.w(TAG, "max_tokens 对当前后端 ${auth.getBackend()} 不可执行，已忽略")
        }

        // ── 参数校验：语义上做不到的**显式拒绝**，绝不静默忽略 ──
        // 判据是"客户端是否依赖该参数的结果"，而不是"我们能否原样转发"：
        //  - logprobs / top_logprobs：客户端要拿 token 概率分布，本机后端产不出来 → 必须拒；
        //  - logit_bias：客户端要干预词表 → 拒；
        //  - seed：客户端要可复现采样 → 拒（置 null 是常见默认值，不算）。
        //
        // 采样类（temperature / top_p / presence_penalty / frequency_penalty）**不拒** ——
        // 绝大多数客户端默认就带这些字段，拒绝了端点直接不可用；
        // 但它们在本机后端同样不生效，所以非默认值时会留痕（见上方日志）。
        val paramRejection = when {
            body.optBoolean("logprobs", false) ->
                "logprobs=true：本机后端不产出 token 概率"
            body.has("top_logprobs") && !body.isNull("top_logprobs") ->
                "top_logprobs：本机后端不产出 token 概率"
            body.has("logit_bias") && (body.optJSONObject("logit_bias")?.length() ?: 0) > 0 ->
                "logit_bias：本机后端不支持词表干预"
            body.has("seed") && !body.isNull("seed") ->
                "seed：本机后端不支持可复现采样"
            else -> null
        }
        paramRejection?.let { return fail(400, "$it，请移除该参数后重试。", "invalid_request_error") }

        val messagesJson = body.optJSONArray("messages")
            ?: return fail(400, "缺少 messages 字段", "invalid_request_error")
        // 多模态：本机两个后端都不支持图片输入。**显式拒绝**，不静默丢弃 ——
        // 静默把 image_url 丢掉会让模型"看不见图却照常作答"，客户端无从察觉
        // （违反仓库契约 §1.4「错误必须可读，不许静默失败」；原先 extractContent 只取 text 部分）。
        if (containsImagePart(messagesJson)) {
            return fail(
                400,
                "本机后端不支持多模态输入（messages 里含 image_url/image 内容），请勿在请求中附带图片。",
                "invalid_request_error",
            )
        }
        val messages = parseMessages(messagesJson)

        // ── 外部对话：解析归属 + 规划投喂方式（设计文档 §7.1 / §7.2）──
        // 这里**不再解析用户的 App 会话**。旧实现把 /v1 的上下文挂在用户会话上，
        // 于是外部调用与 App 聊天互相串味、并跟随桌面页焦点漂移（P0-1）。
        // 现在增量通道用的是「外部对话自己的隔离记录」，App 会话完全不参与。
        val store = ExternalConversationStore(ExternalConversationStore.defaultRoot(context.filesDir))
        val now = System.currentTimeMillis()
        runCatching { store.evict(now) }   // 顺手回收（TTL / LRU）

        val clientTurns = normalizeClientMessages(messages)
        // ── 会话绑定（用户确认语义：设置页选定的会话是**唯一绑定**）──
        // 一旦设置页配了会话，所有 /v1 请求都归入它，客户端每轮换 session_id 也不影响 ——
        // 否则每换一次 id 就会新建一个 DeepSeek 服务端会话，会话列表里会不停冒出新会话。
        // 未配置时才退回：X-Conversation-Id > X-Session-Id > body.session_id > body.user > 首轮内容派生。
        // 注：曾按「无 system + 无 tools + 单条 user」把一次性请求（如 DSH 的会话标题生成）
        // 分流到独立会话，避免它污染绑定的消息树。**已撤回** ——
        // 该形状与「极简客户端的正常一轮对话」完全同形，而客户端语义（谁是一次性调用）
        // 不上线，判据不足以支撑这个决定：一旦误判，极简客户端会整体失去绑定连续性。
        // 代价（有意接受）：客户端若发辅助调用，它的问答会留在绑定会话树里。
        val settingsSeed = ApiForwardPrefs.sessionId(context).takeIf { it.isNotBlank() }
        val explicitSessionId = conversationIdHeader ?: sessionIdHeader
            ?: body.optString("session_id").takeIf { it.isNotBlank() }
            ?: settingsSeed
        val derivedKey = if (settingsSeed != null) {
            "s_" + sanitizeKeyPart(settingsSeed)
        } else {
            deriveConversationKey(
                explicitConversationId = explicitSessionId,
                bodySessionId = null,
                bodyUser = body.optString("user").takeIf { it.isNotBlank() },
                clientTurns = clientTurns,
            )
        }
        // 绑定到某个**会话 id** 时（s_ 前缀），复用该会话已有的服务端会话与续聊锚点 ——
        // 否则"归入那个会话"只是名义上的：记录里没有服务端会话，仍会新建一个。
        // 客户端传来的会话 id，**只有在长得像上游 UUID 时**才能当服务端 chat_session_id 用。
        // 实测：`X-Conversation-Id: forktest-alpha` 被原样塞进上游 → DeepSeek 直接 500
        // （"chat_session_id: UUID parsing failed ... found `o` at 2"），我们又把这个
        // 上游反序列化错误当成自己的 500 抛回客户端 —— 客户端拿不到任何可行动的提示（§1.4）。
        fun isUpstreamSessionId(s: String): Boolean =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .matches(s)
        val explicitUpstreamId = explicitSessionId?.takeIf { isUpstreamSessionId(it) }
        if (explicitSessionId != null && explicitUpstreamId == null) {
            // 不静默：告诉调用方这个 id 被忽略了，以及为什么
            LogStore.w(
                TAG,
                "客户端会话 id 不是合法 UUID，已忽略、不作为上游 chat_session_id：$explicitSessionId",
            )
        }
        // 无效的客户端 id 退回设置页绑定的会话；绑定会话也不是 UUID 时同为 null（→ 新建会话）
        val boundSessionId = if (derivedKey.startsWith("s_")) {
            explicitUpstreamId ?: settingsSeed?.takeIf { isUpstreamSessionId(it) }
        } else null
        val backend = auth.getBackend()

        // ── 绑定模式 ──
        // 绑定了具体会话时，续聊位置以**该会话自己的持久化锚点**为准：
        // 不参与前缀比对，也**不新建服务端会话**。
        //
        // 为什么必须这样：客户端不一定会把助手回复回传进 messages（很多客户端自己管历史，
        // 只发它认为必要的部分）。那样前缀比对必然失败 → 判为分叉 → resetSession →
        // 另起一个服务端会话，表现就是**每轮都冒出新会话**。
        // 绑定语义下不该给这条路径任何自主权：客户端 messages 只用于决定"这一轮发哪条"，
        // 续聊接在哪里由会话自身的锚点决定。
        val boundState = if (boundSessionId != null && false) {
            readBoundSessionState(context, AuthPrefs(context), boundSessionId)
        } else {
            null
        }

        // 显式 key 查不到时用 messages 前缀反查 —— 很多客户端不传任何标识，
        // 这是唯一能把它们认回同一个对话的手段（仅非绑定模式需要）。
        // 绑定模式也读一条轻量记录 —— 它只当"系统提示词 / 工具声明是否已注入过"的台账用
        val boundLedger = if (boundState != null) store.load(derivedKey) else null
        val found = if (boundState != null) null
        else store.load(derivedKey) ?: store.findByMessagePrefix(clientTurns)
        val convKey = found?.convKey ?: derivedKey

        // 注入台账：本轮实际注入了哪些声明（供下面落盘）
        var ledgerSysFp: String? = null
        var ledgerToolsFp: String? = null
        // 本轮投喂形态：决定记录怎么推进（与结构契约 turns[i] ↔ 客户端第 packedThrough+i 条对齐）
        var ledgerPackedThrough: Int? = null
        var ledgerUserText: String? = null
        // 本轮是否允许推进轮次表（失配时禁止，避免覆盖链依据）
        var ledgerCanAdvance = true
        // 本轮是否发了全量转录（明确标志，不再拿字符串猜）
        var ledgerUsedTranscript = false

        var plan: CompatPlan
        if (boundState != null) {
            // 绑定模式下只发「最后一条 user」；历史由该会话的服务端消息树承担。
            //
            // 例外：客户端刚回传工具结果时，本轮要发的是**结果**而不是旧问题 ——
            // 否则模型看不到结果，会重复调用同一个工具，连环 tool_call 后被上游限流。
            val lastUserIdx = messages.indexOfLast { it.role == "user" }
            val toolRes = toolResultsPrompt(messages)

            // 首轮（服务端会话还是空的）必须发**客户端全量内容**，不能只发最后一条 user。
            // 反例（实测 DSH）：它一轮会发 [system, user("你好"), user(运行时上下文)]，
            // 只取最后一条 user 就会把用户真正的问题丢掉，发过去的只剩样板文 ——
            // 表现就是"排版不对"（内容组装错了）与"没有延续"（模型答的不是被问的）。
            // 之后服务端会话记住了历史，才退回"只发新增"。
            // 与 App 内同源：首条注入完整提示词，之后只发新消息原文。
            // ── 客户端历史 ↔ 上游链 的一致性校验（绑定模式下也曾被跳过，是个回归）──
            // 「绑定用哪个会话」与「在该会话里从哪儿续」是两件正交的事：
            // 前者由设置页决定，后者必须靠 messages 前缀比对决定 —— 缺了后者，
            // 客户端一旦编辑/截断历史，我们仍按旧锚点硬发，服务端会在一条已经对不上的链上续，
            // 表现为答非所问且无从察觉。
            val match = boundLedger?.let { matchConversation(it, clientTurns) } ?: ConvMatch.NoMatch

            // 表是否可推进：只有**与现有表一致**的请求（增量/幂等），或首次建立（表为空）时才推进。
            // ⚠️ 失配时若照样改写表，会把整条链的依据覆盖掉 ——
            // 实测场景：绑定会话被两条请求流共用（主对话 + 客户端的辅助调用，如会话标题生成），
            // 它们各自按自己的 messages 推进同一张表、互相覆盖，
            // 下一轮主对话就"第 0 条即分叉"，进而误触 edit_message 在好链上建分支。
            // 宁可表暂时落后，也不能让它被无关请求改写。
            // ⚠️ 携带工具结果的请求**不参与分叉判定**：轮次表的粒度是「一轮 user + 一轮 assistant」，
            // 而 OpenAI 的工具循环是「assistant(可能含 K 个 tool_calls) → K 条 tool 结果 → assistant…」，
            // 表里没有 tool 的位置 —— 拿它去比对必然失配。
            // 实测：模型一轮发 3 个 tool_call，此后**每轮都判 Fork**、每轮发全量转录，
            // 而锚点 2→4→6 一直正常推进，说明链本身是连续的，是检测错了。
            // ── 决策统一交给纯函数 decideBound（本仓库 §1.2 单一事实源）──
            // 这段判断原先内联在此，与 Context/org.json 耦合而无法单测；抽出后：
            //  - 判据只有一处实现，不会出现"测试覆盖的规格"与"实际执行的代码"分叉；
            //  - 五类分支（首轮 / 链一致 / 带工具结果 / 分叉可定位 / 分叉不可定位）已有单测。
            val hasToolResults = toolResultsPrompt(messages) != null
            val tableReady = !boundLedger?.turns.isNullOrEmpty()
            // 分叉点可定位到的「被编辑用户消息」服务端 id（仅 Fork 时有意义；
            // 是否真走 edit_message 由 decideBound 决定，它还会排除 hasToolResults）
            val forkEditIdRaw: String? = run {
                if (match !is ConvMatch.Fork) return@run null
                val idx = match.matchedClientMessages - (boundLedger?.packedThrough ?: 0)
                val t = boundLedger?.turns?.getOrNull(idx) ?: return@run null
                t.serverMessageId?.takeIf { t.role == "user" && it.isNotBlank() }
            }
            val d = decideBound(
                tableReady = tableReady,
                hasToolResults = hasToolResults,
                isFirstUpstreamTurn = boundState.second == null,
                match = match,
                nonSystemCount = messages.count { it.role != "system" },
                editServerMessageId = forkEditIdRaw,
            )
            ledgerCanAdvance = d.canAdvanceTable
            if (tableReady && !d.canAdvanceTable) {
                LogStore.w(
                    TAG,
                    "本轮与上游链不一致（${match::class.simpleName}），仅发内容、**不推进轮次表**（避免覆盖链依据）",
                )
            }
            if (match is ConvMatch.Fork && !hasToolResults) {
                // 降为 INFO：fork 是客户端的**正常语义**（子代理分叉会话就是从主链岔出去），
                // 服务端消息树本就支持分支，主链读不到岔出的轮次。按 WARN 报会淹没真问题。
                LogStore.i(
                    TAG,
                    "客户端历史与上游链在第 ${match.matchedClientMessages} 条不一致，" +
                        "本轮改发全量转录对齐（fork/编辑场景属预期）",
                )
            }
            // 诊断：把「客户端消息形状」与「台账形状」一起打出来。
            // 轮次表表示不了工具循环（表里只有 user/assistant，客户端还有 tool），
            // 要断定错位发生在第几条、差多少，必须同时看到两侧的真实序列。
            // 这条日志是**临时测量**，定位完就删。
            LogStore.i(
                TAG,
                "形状诊断：client=[" + messages.joinToString(",") { it.role } + "] " +
                    "turns=" + (boundLedger?.turns?.size ?: -1) +
                    " packedThrough=" + (boundLedger?.packedThrough ?: -1) +
                    " nonSystem=" + messages.count { it.role != "system" } +
                    " match=" + match::class.simpleName,
            )
            val nonSystem = messages.filter { it.role != "system" }
            val body = when {
                toolRes != null -> toolRes
                // 多条非 system 消息且链不可信 → 整段转录（只发末条会丢掉真问题）
                d.sendTranscript -> {
                    ledgerUsedTranscript = true
                    renderTranscript(nonSystem)
                }
                // 常规：只发最后一条 user 原文（单轮场景与非绑定模式字节一致）
                else -> messages.getOrNull(lastUserIdx)?.content.orEmpty()
            }

            // ── 系统提示词 / 工具声明：按**注入台账**决定，不用"有没有锚点"猜 ──
            // 绑定模式只发"最后一条 user"，而 system 的角色不是 user，不注入就等于**整条丢弃**
            // （CompatChatRunner 绕开了 ChatBridge，App 自己的 composeDeepSeek 注入也不会跑）。
            //
            // 判据是「这次的内容指纹 ≠ 上次已注入的指纹」——首次、或客户端换了提示词时注入；
            // 没变就不重复注入（每轮重发会撑大上下文并造成指令冲突，表现就是"重发了上次"）。
            val systemText = messages.filter { it.role == "system" }
                .mapNotNull { it.content?.takeIf { c -> c.isNotBlank() } }
                .joinToString("\n\n")
                .trim()
            val sysFp = textFingerprint(systemText)
            val toolsFp = toolsDeclaration?.let { textFingerprint(it) }
            val needSys = systemText.isNotEmpty() && boundLedger?.systemFp != sysFp
            val needTools = toolsDeclaration != null && boundLedger?.toolsFp != toolsFp

            val prefix = buildString {
                if (needSys) append(systemText).append("\n\n---\n\n")
                if (needTools) append(toolsDeclaration).append("\n\n---\n\n")
            }
            if (needSys) ledgerSysFp = sysFp
            if (needTools) ledgerToolsFp = toolsFp

            // 记录本轮投喂形态：发全量转录 → packedThrough；只发一条 → 追加一轮。
            // 不维护 turns 的话，下一轮的 matchConversation 拿不到任何依据
            // （记录里 turns 恒为空 = 空前缀 = 任何请求的前缀，还会让 findByMessagePrefix 误命中）。
            val lastUserText = messages.getOrNull(lastUserIdx)?.content?.takeIf { it.isNotBlank() }
            ledgerUserText = lastUserText ?: (toolRes ?: "")
            // 用明确标志判断投喂形态，而不是事后比较字符串引用/内容
            // （旧写法 `body !== (...) && body != (...)` 是写歪的启发式，易判错）
            ledgerPackedThrough = if (ledgerUsedTranscript) nonSystem.size else null
            val forkEditId = d.editBranchMessageId
            plan = if (forkEditId != null) {
                LogStore.i(TAG, "分叉点定位到用户消息 id=$forkEditId，改用 edit_message 原位建分支")
                CompatPlan.EditBranch(
                    userText = prefix + body,
                    editServerMessageId = forkEditId,
                    forkClientIndex = (match as? ConvMatch.Fork)?.matchedClientMessages ?: 0,
                )
            } else {
                CompatPlan.Incremental(
                    userText = prefix + body,
                    newUserIndex = lastUserIdx.coerceAtLeast(0),
                )
            }
        } else {
            // 工具声明只在「上游会话刚开始」时注入一次（首次请求 / 刚重建过服务端会话 / 打包重放）：
            // DeepSeek 逆行的工具清单本就是仅首条注入，之后由服务端会话记住；
            // 每轮重复注入只会撑大上下文并造成指令冲突。
            plan = planCompat(backend, found, messages, toolsDeclaration = null)
            val needsFreshInjection = found?.serverSessionId == null ||
                (plan is CompatPlan.PackedReplay && plan.resetSession)
            if (needsFreshInjection && toolsDeclaration != null) {
                plan = planCompat(backend, found, messages, toolsDeclaration)
            }
        }

        if (plan is CompatPlan.FullMessages && messages.isEmpty()) {
            return fail(400, "messages 中没有可用内容", "invalid_request_error")
        }
        if (plan !is CompatPlan.FullMessages && CompatChatRunner.promptOf(plan).isBlank()) {
            return fail(400, "messages 中没有可用内容", "invalid_request_error")
        }
        LogStore.i(
            TAG,
            "投喂规划 backend=$backend convKey=$convKey plan=${plan::class.simpleName} messages=${messages.size}"
        )

        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000
        val acc = AssistantAccum()

        // 流式下两个必须维护的状态：
        //  - roleSent：OpenAI 规范要求首帧带 role。旧实现 chunkFrame 的 role 形参
        //    在全部 8 个调用点都传 null，**从未发过 role**。
        //  - sentContentLen / sentThinkingLen：ContentReplace 是"整段替换"语义，
        //    而 SSE delta 是**追加**语义。直接把 full 当 delta 发，客户端会追加成
        //    "半截 + 整段"的重复正文。必须转成后缀增量。
        var roleSent = false
        var sentContentLen = 0
        var sentThinkingLen = 0
        fun ensureRole() {
            if (!roleSent) {
                roleSent = true
                send(chunkFrame(id, created, model, role = "assistant"))
            }
        }

        // ── 服务端会话：仅 DeepSeek 逆向后端使用，已在去 DS 化中移除 ──
        // 全量通道的上游自带全量 messages，不需要服务端会话。
        val serverSid: String? = if (boundState != null) boundState.first else found?.serverSessionId

        // 事件出口：只喂给本请求的 emit（转成 OpenAI 帧），不进 StreamTaskManager、不广播 SSE。
        val onEv: (MessageEvent) -> Unit = { ev ->
                acc.apply(ev)
                if (stream) {
                    when (ev) {
                        is MessageEvent.Content -> {
                            ensureRole()
                            sentContentLen += ev.delta.length
                            send(chunkFrame(id, created, model, null, content = ev.delta))
                        }
                        is MessageEvent.Thinking -> {
                            ensureRole()
                            sentThinkingLen += ev.delta.length
                            send(chunkFrame(id, created, model, null, reasoning = ev.delta))
                        }
                        is MessageEvent.ContentReplace -> {
                            ensureRole()
                            val full = ev.full
                            if (full.length > sentContentLen) {
                                send(chunkFrame(id, created, model, null, content = full.substring(sentContentLen)))
                            } else if (full.length < sentContentLen) {
                                LogStore.w(
                                    TAG,
                                    "ContentReplace 缩短了正文(${sentContentLen}→${full.length})，SSE 无法表达回退"
                                )
                            }
                            sentContentLen = full.length
                        }
                        is MessageEvent.ThinkingReplace -> {
                            ensureRole()
                            val full = ev.full
                            if (full.length > sentThinkingLen) {
                                send(chunkFrame(id, created, model, null, reasoning = full.substring(sentThinkingLen)))
                            } else if (full.length < sentThinkingLen) {
                                LogStore.w(
                                    TAG,
                                    "ThinkingReplace 缩短了思考(${sentThinkingLen}→${full.length})，SSE 无法表达回退"
                                )
                            }
                            sentThinkingLen = full.length
                        }
                        is MessageEvent.ToolCall -> {
                            ensureRole()
                            send(toolCallFrame(id, created, model, acc.toolCalls.size - 1, ev.id, ev.name, ev.arguments))
                        }
                        else -> {}
                    }
                }
        }

        val startedAt = System.currentTimeMillis()
        val outcome: CompatChatRunner.Outcome = try {
            // 并发闸门：按 convKey 串行化（并发请求会交替覆盖服务端锚点 →
            // 消息树分叉、两边都丢历史），同时限制全局并发（上游烧的是用户自己的额度）。
            CompatConcurrency.run(convKey) {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeout(UPSTREAM_TIMEOUT_SEC * 1000) {
                        CompatChatRunner.run(
                            auth = auth,
                            plan = plan,
                            thinking = thinking,
                            serverSessionId = serverSid,
                            parentMessageId = if (boundState != null) boundState.second else found?.anchorMessageId,
                            onEvent = onEv,
                        )
                    }
                }
            } ?: return fail(
                429,
                "本机 /v1 并发已满（上限 ${CompatConcurrency.MAX_CONCURRENT} 路），请稍后重试",
                "rate_limit_error",
            )
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // 超时必须走错误路径。旧实现丢弃了 latch.await 的返回值：超时后用**部分累积**的
            // 内容拼出一个 finish_reason:"stop" 的"成功"响应 —— 客户端拿到腰斩的正文
            // 却完全无法察觉。宁可报 504 让客户端自己决定重试。
            return fail(504, "上游 ${UPSTREAM_TIMEOUT_SEC}s 内未返回（可能仍在生成，请重试或改用流式）", "server_error")
        }

        outcome.error?.let { err ->
            return fail(500, err.message ?: err.javaClass.simpleName, "server_error")
        }
        // 上游把错误做成 MessageEvent.Error 时不抛异常，必须显式检查 ——
        // 否则会把上游报错静默转成"内容为空的成功响应"（P1-8）。
        acc.error?.let { return fail(500, it, "server_error") }

        // 丢弃合成调用后如果**什么都没剩下**，不能给客户端一个空成功响应 ——
        // 那会让它以为模型无话可说，而真实原因是模型的行式工具调用写得不合规范。
        // 如实报错，并给出可行动的说明（模型下一轮会据此改正写法）。
        // ⚠️ 判据是「解析失败 **且没有可用的工具调用**」，**不看是否有正文**。
        // 旧写法加了 acc.content.isBlank()，于是"有正文 + 调用解析失败"时会静默丢弃 ——
        // 实测：模型一轮里同时写了 tool_call 与多余的 >>> 导致解析失败，客户端的正文有了，
        // 却**在等一个永远不会来的 tool_call**，无从察觉。这违反了「错误必须可读、不许静默失败」。
        // 有正文不代表调用意图消失：模型想调工具，我们送不到，就必须如实告知。
        if (outcome.parseErrors > 0 && acc.toolCalls.isEmpty()) {
            return fail(
                502,
                // 用**解析器给出的真实错因**，不要写死通用提示 —— 实测真因是「参数 'THREW' 重复出现」，
                // 而写死的「多行参数必须用 <<< >>> 定界块」会把客户端引向错误方向，白费一轮。
                "模型输出的工具调用无法解析（${outcome.parseErrors} 处）：" +
                    outcome.parseErrorDetails.joinToString("；") +
                    " 请按提示改写该调用后重试；若持续出现，请在该客户端关闭工具调用。",
                "server_error",
            )
        }

        LogStore.i(
            TAG,
            "完成 convKey=$convKey plan=${plan::class.simpleName} " +
                "用时=${System.currentTimeMillis() - startedAt}ms " +
                "锚点=${outcome.anchorMessageId ?: "-"} " +
                "tokens=${outcome.usage?.totalTokens ?: -1}"
        )

        // 绑定到 App 会话时把新锚点写回该会话：否则 App 里再聊这个会话会用旧锚点
        // 另开一条分支，两条链就分叉了（表现为"同一会话里历史接不上"）。
        if (boundSessionId != null && outcome.anchorMessageId != null) {
            runCatching {
                LocalStore.saveSessionCurrentId(
                    context,
                    LocalStore.backendTag(auth.getBackend()),
                    boundSessionId,
                    outcome.anchorMessageId,
                )
            }.onFailure { LogStore.w(TAG, "回写会话锚点失败 sid=$boundSessionId: ${it.message}") }
        }

        // ── 记录推进：把本轮结果写回外部上下文库 ──
        // 失败不致命（下次会退化为打包重放），但必须留痕 —— 否则表现为"偶尔变慢变差"。
        // 绑定模式：把注入台账与锚点落盘（否则下轮无法判断"要不要再注入"）
        if (boundState != null) {
            runCatching {
                val base = (boundLedger ?: newConversationRecord(convKey, "ext-$convKey", backend.name, now))
                    .copy(bound = true)
                // 记下服务端消息 id —— 分叉时 edit_message 要按「被编辑的那条**用户**消息」
                // 定位，缺了它就只能退化为重发全量。
                // 优先用服务端下发的**真实** user 消息 id（SSE 的 request_message_id）。
                //
                // 此前用「user = assistant − 1」推算。它在连续轮次里成立（实测 user3/assistant4、
                // user5/assistant6），但**分叉之后 id 会跳号**（台账实测 …18 → 21/22、25/26），
                // 推算出来的 id 会指向**另一条消息** —— 而分叉定位（edit_message 锚点）用的
                // 正是它，指错就等于在错误的位置建分支。两个 id 服务端本来就都发下来了。
                // 拿不到时才退回推算；非数字（本地合成 id）仍留空，不猜。
                val assistantId = outcome.anchorMessageId
                val userId = outcome.userMessageId
                    ?: assistantId?.toLongOrNull()?.minus(1)?.toString()
                val assistantTurn = turnRecordOf("assistant", acc.content.toString(), serverId = assistantId)
                val packed = ledgerPackedThrough
                val advanced = if (!ledgerCanAdvance) {
                    // 不能推进 ⇒ **重定基线**，不是冻结。
                    //
                    // 旧行为是原样保留既有 turns / packedThrough。真机实测的后果是**永久 Fork**：
                    // 表一旦与客户端错位，canAdvanceTable 就恒为 false，表永远停在旧长度
                    // （实测连续四轮 turns=27 纹丝不动、每轮都「在第 3 条不一致」），
                    // 而 Fork 又让下一轮继续不推进 —— 再也回不到对齐状态。
                    //
                    // 本轮已经把客户端**全量转录**发上去了（服务端叙事已跟上），所以本地表也
                    // 跟着重定：packedThrough 推到客户端当前消息数，turns 从本轮助手重新开始。
                    // 这样即使下一轮还对不上，也只是**再重定一次**，不会卡死。
                    base.afterPacked(
                        clientMessageCount = messages.size,
                        assistantTurn = assistantTurn,
                        serverSessionId = serverSid,
                        anchorMessageId = outcome.anchorMessageId,
                        now = now,
                    )
                } else if (packed != null) {
                    base.afterPacked(
                        clientMessageCount = packed,
                        assistantTurn = assistantTurn,
                        serverSessionId = serverSid,
                        anchorMessageId = outcome.anchorMessageId,
                        now = now,
                    )
                } else {
                    // 轮次表必须**逐条镜像客户端的消息序列**。
                    //
                    // 比对是逐位置的：turns[i] ↔ client[packedThrough + i]，而 client 是客户端
                    // messages 的**全表**（含 system）。所以表里少一条、多一条，后面**全部**错位。
                    // 真机实测：工具循环轮里客户端新增的是 [assistant(tool_calls), tool, tool]，
                    // 旧实现却只记 (lastUserText, 本轮助手)—— 于是同一条 user 连记 7 次、
                    // tool 条目一条不记，下一轮前缀比对直接落在 Fork(packedThrough)（连比较循环
                    // 都没进），表现为「每轮 Fork → edit_message 重建分支 → 表不推进 → 再 Fork」，
                    // prompt 6788→7254→9085 逐轮膨胀。
                    //
                    // 改法：把客户端**本轮新增的那些消息原样记录**（连同 tool），再接上本轮助手。
                    // coveredNow = 已被表覆盖到的客户端消息数。
                    val coveredNow = base.packedThrough + base.turns.size
                    val appended = messages.drop(coveredNow)
                        .map { turnRecordOf(it.role, it.content.orEmpty()) }
                    base.copy(
                        turns = base.turns + appended + assistantTurn,
                        serverSessionId = serverSid ?: base.serverSessionId,
                        anchorMessageId = outcome.anchorMessageId,
                        lastUsedAt = now,
                    )
                }
                // 乐观并发：闸门（CompatConcurrency）只护住了**上游调用**这一段，
                // load → 计划 → save 都在闸门外。并发流（主对话 / 标题生成 / 子代理 fork）
                // 会基于同一份旧记录各自计算，后写覆盖先写（日志实证）。
                // 这里在落盘前复核：若记录已被别的请求改写，则**本轮跳过落盘**，不覆盖。
                val current = store.load(convKey)
                if (boundLedger != null && current != null &&
                    current.lastUsedAt != boundLedger.lastUsedAt
                ) {
                    LogStore.w(TAG, "记录已被其他请求更新，本轮跳过落盘以免覆盖（并发流）")
                } else {
                    store.save(
                        advanced.copy(
                            systemFp = ledgerSysFp ?: advanced.systemFp,
                            toolsFp = ledgerToolsFp ?: advanced.toolsFp,
                        )
                    )
                }
            }.onFailure { LogStore.w(TAG, "注入台账落盘失败: ${it.message}") }
        }

        if (plan !is CompatPlan.FullMessages && boundState == null) {
            runCatching {
                val base = found ?: newConversationRecord(convKey, "ext-$convKey", backend.name, now)
                val assistantTurn = turnRecordOf("assistant", acc.content.toString())
                val next = when (plan) {
                    is CompatPlan.Incremental -> base.afterIncremental(
                        userTurn = turnRecordOf("user", plan.userText),
                        assistantTurn = assistantTurn,
                        serverSessionId = serverSid,
                        anchorMessageId = outcome.anchorMessageId,
                        now = now,
                    )
                    // 情形 B：重发即重新生成 —— 覆盖上一条 assistant，而不是再追加一轮
                    is CompatPlan.Regenerate -> base.copy(
                        turns = base.turns.dropLast(1) + assistantTurn,
                        serverSessionId = serverSid ?: base.serverSessionId,
                        anchorMessageId = outcome.anchorMessageId,
                        lastUsedAt = now,
                    )
                    // 情形 C 最优路径：分叉点原位建兄弟分支。
                    // 截断到分叉点，再接上"被编辑的新提问 + 新回复"。
                    is CompatPlan.EditBranch -> base.afterFork(
                        forkClientIndex = plan.forkClientIndex,
                        userTurn = turnRecordOf("user", plan.userText),
                        assistantTurn = assistantTurn,
                        serverSessionId = serverSid,
                        anchorMessageId = outcome.anchorMessageId,
                        now = now,
                    )
                    is CompatPlan.PackedReplay -> base.afterPacked(
                        clientMessageCount = plan.packedCount,
                        assistantTurn = assistantTurn,
                        serverSessionId = serverSid,
                        anchorMessageId = outcome.anchorMessageId,
                        now = now,
                    )
                    is CompatPlan.FullMessages -> base
                }
                store.save(next)
            }.onFailure {
                LogStore.w(TAG, "外部对话记录落盘失败（下次将退化为打包重放）: ${it.message}")
            }
        }

        return if (stream) {
            val finish = when {
                acc.toolCalls.isNotEmpty() -> "tool_calls"
                acc.truncated -> "length"
                else -> "stop"
            }
            send(finishFrame(id, created, model, finish))
            // usage chunk：OpenAI 只在客户端显式要求时下发
            // （stream_options.include_usage=true），这里对齐该约定。
            val includeUsage = runCatching {
                body.optJSONObject("stream_options")?.optBoolean("include_usage", false) == true
            }.getOrDefault(false)
            if (includeUsage) {
                outcome.usage?.let { send(usageFrame(id, created, model, it)) }
            }
            send(DONE_FRAME)
            CompatResult.Ok("")
        } else {
            CompatResult.Ok(buildCompletion(id, created, model, acc, outcome.usage))
        }
    }

    /**
     * 读取某个 App 会话的「服务端会话 id + 续聊锚点」。
     *
     * 绑定到设置页选定的会话时用它播种外部对话记录 —— 这样 /v1 是**接着那个会话**聊，
     * 而不是给它另开一条平行的服务端会话（后者会在会话列表里冒出一个新会话）。
     *
     * 服务端会话取不到时回落到本地 sid 本身：DeepSeek 的会话 id 就是建会话时服务端给的，
     * 未轮转过就没有 rotation 文件。
     */
    private fun readBoundSessionState(context: Context, auth: AuthPrefs, sid: String): Pair<String?, String?> {
        val tag = LocalStore.backendTag(auth.getBackend())
        val rotation = runCatching { LocalStore.loadSessionRotation(context, tag, sid) }.getOrNull()
        val anchor = runCatching {
            LocalStore.loadSessions(context, tag).firstOrNull { it.id == sid }?.currentMessageId
        }.getOrNull()
        val server = rotation?.serverSessionId?.takeIf { it.isNotBlank() } ?: sid
        return server to anchor?.takeIf { it.isNotBlank() }
    }

    /**
     * 把外部声明的 tools 渲染成**文本工具声明**（与 App 内部同一套行式协议）。
     *
     * 直接序列化客户端给的原样 JSON Schema，**不做 ParamSpec 往返** ——
     * 那是有损的（丢 format / oneOf / $ref / 数组完整 items），
     * 而这里根本不需要理解 schema，只要如实转达。
     *
     * 调用格式说明与 PromptComposer 的行式协议指南一致：模型需要知道
     * 「怎么在正文里写调用」，这是纯文本声明能生效的前提。
     */
    private fun renderToolsDeclaration(tools: JSONArray): String = buildString {
        append("[工具清单]\n")
        append("你可以调用下列工具。需要调用时按下方格式在正文里直接输出，不要用代码围栏包裹。\n\n")
        append(tools.toString(2))
        append("\n\n## 工具调用格式（行式协议）\n\n")
        append("tool_call: 工具名\n")
        append("id: 本轮唯一标识\n")
        append("参数名: 值\n")
        append("需要多行值的参数用 参数名 <<< ... >>> 包裹。\n\n")
        append("直接顶格写，不要加任何前缀；不要在调用之外再解释一遍。")
    }

    /** content 数组里是否含图片部分（多模态）。用于显式拒绝，而不是静默丢弃。 */
    private fun containsImagePart(arr: JSONArray): Boolean {
        for (i in 0 until arr.length()) {
            val content = arr.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until content.length()) {
                when (content.optJSONObject(j)?.optString("type", "")) {
                    "image_url", "image", "input_image" -> return true
                }
            }
        }
        return false
    }

    /** 解析 OpenAI messages 数组 → 内部 ChatMessage 列表。 */
    private fun parseMessages(arr: JSONArray): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = extractContent(m)
            val toolCalls = m.optJSONArray("tool_calls")?.let { tc ->
                (0 until tc.length()).mapNotNull { j ->
                    val t = tc.optJSONObject(j) ?: return@mapNotNull null
                    val fn = t.optJSONObject("function") ?: return@mapNotNull null
                    ToolCall(
                        id = t.optString("id", "call_$j"),
                        type = t.optString("type", "function"),
                        function = ToolCallFunction(
                            name = fn.optString("name", ""),
                            arguments = fn.optString("arguments", "{}")
                        )
                    )
                }
            }
            out.add(
                ChatMessage(
                    role = role,
                    content = content,
                    toolCalls = toolCalls?.takeIf { it.isNotEmpty() },
                    toolCallId = m.optString("tool_call_id").takeIf { it.isNotBlank() },
                    name = m.optString("name").takeIf { it.isNotBlank() },
                )
            )
        }
        return out
    }

    /** 提取 content：字符串直接取；数组（多模态）取 text 部分拼接。 */
    private fun extractContent(m: JSONObject): String? {
        if (m.isNull("content")) return null
        val c = m.opt("content")
        return when (c) {
            is String -> c
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until c.length()) {
                    val part = c.optJSONObject(i) ?: continue
                    val t = part.optString("type", "")
                    if (t == "text") sb.append(part.optString("text", ""))
                }
                sb.toString()
            }
            null -> null
            else -> c.toString()
        }
    }

    private fun buildCompletion(
        id: String,
        created: Long,
        model: String,
        acc: AssistantAccum,
        usage: MessageEvent.Usage? = null,
    ): String {
        val msg = JSONObject().put("role", "assistant")
        msg.put("content", if (acc.content.isEmpty()) JSONObject.NULL else acc.content.toString())
        if (acc.reasoning.isNotEmpty()) msg.put("reasoning_content", acc.reasoning.toString())
        if (acc.toolCalls.isNotEmpty()) {
            val tc = JSONArray()
            acc.toolCalls.forEachIndexed { i, t ->
                tc.put(
                    JSONObject()
                        .put("id", t.first)
                        .put("type", "function")
                        .put("index", i)
                        .put("function", JSONObject().put("name", t.second).put("arguments", t.third))
                )
            }
            msg.put("tool_calls", tc)
        }
        val finish = if (acc.toolCalls.isNotEmpty()) "tool_calls" else if (acc.truncated) "length" else "stop"
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("index", 0)
                    .put("message", msg)
                    .put("finish_reason", finish)
            ))
        usage?.let {
            obj.put("usage", usageJson(it))
            // DeepSeek 逆向只回传 total，拆分是本地推算 —— 用非标准顶层字段如实标注，
            // 免得客户端把估算当 provider 真实值。
            if (it.providerUsageOnly) obj.put("x_usage_split_estimated", true)
        }
        return obj.toString()
    }

    /** MessageEvent.Usage → OpenAI 的 usage 对象。 */
    private fun usageJson(u: MessageEvent.Usage): JSONObject = JSONObject()
        .put("prompt_tokens", u.promptTokens)
        .put("completion_tokens", u.completionTokens)
        .put("total_tokens", u.totalTokens)
        .apply {
            if (u.reasoningTokens >= 0) {
                put(
                    "completion_tokens_details",
                    JSONObject().put("reasoning_tokens", u.reasoningTokens)
                )
            }
            if (u.cacheReadTokens >= 0) {
                put(
                    "prompt_tokens_details",
                    JSONObject().put("cached_tokens", u.cacheReadTokens)
                )
            }
            // 注：「拆分为估算值」的标记**放在 usage 之外**（见 buildCompletion / usageFrame 的
            // x_usage_split_estimated 顶层字段）。塞进 usage 里会让它看起来是 OpenAI usage 的一部分。
        }

    /**
     * 流式的 usage 帧。
     *
     * 对齐 OpenAI：`choices` 为空数组，usage 挂在顶层，
     * 且只在客户端传了 `stream_options.include_usage=true` 时下发。
     */
    private fun usageFrame(id: String, created: Long, model: String, u: MessageEvent.Usage): String {
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray())
            .put("usage", usageJson(u))
        if (u.providerUsageOnly) obj.put("x_usage_split_estimated", true)
        return "data: $obj\n\n"
    }

    private fun chunkFrame(
        id: String, created: Long, model: String,
        role: String?, content: String? = null, reasoning: String? = null,
    ): String {
        val delta = JSONObject()
        role?.let { delta.put("role", it) }
        content?.let { delta.put("content", it) }
        reasoning?.let { delta.put("reasoning_content", it) }
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject().put("index", 0).put("delta", delta).put("finish_reason", JSONObject.NULL)
            ))
        return "data: $obj\n\n"
    }

    private fun toolCallFrame(
        id: String, created: Long, model: String,
        index: Int, callId: String, name: String, args: String,
    ): String {
        val delta = JSONObject().put("tool_calls", JSONArray().put(
            JSONObject()
                .put("index", index)
                .put("id", callId)
                .put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", args))
        ))
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject().put("index", 0).put("delta", delta).put("finish_reason", JSONObject.NULL)
            ))
        return "data: $obj\n\n"
    }

    private fun finishFrame(id: String, created: Long, model: String, finish: String): String {
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(
                JSONObject().put("index", 0).put("delta", JSONObject()).put("finish_reason", finish)
            ))
        return "data: $obj\n\n"
    }

    private fun errorFrame(msg: String): String {
        val obj = JSONObject().put("error", JSONObject().put("message", msg).put("type", "server_error"))
        return "data: $obj\n\n"
    }

    /** 非流式错误响应 JSON。 */
    private fun errorJson(code: Int, msg: String, type: String): String =
        JSONObject()
            .put("error", JSONObject()
                .put("message", msg)
                .put("type", type)
                .put("code", code)
            )
            .toString()

    /** 事件累积器：把流式增量拼成完整 assistant 消息。 */
    private class AssistantAccum {
        var content = StringBuilder()
        var reasoning = StringBuilder()
        val toolCalls = ArrayList<Triple<String, String, String>>()
        var done = false
        var truncated = false
        var error: String? = null

        fun apply(ev: MessageEvent) {
            when (ev) {
                is MessageEvent.Content -> content.append(ev.delta)
                is MessageEvent.ContentReplace -> content = StringBuilder(ev.full)
                is MessageEvent.Thinking -> reasoning.append(ev.delta)
                is MessageEvent.ThinkingReplace -> reasoning = StringBuilder(ev.full)
                is MessageEvent.ToolCall -> toolCalls.add(Triple(ev.id, ev.name, ev.arguments))
                is MessageEvent.Done -> {
                    done = true; truncated = ev.truncated
                    // Done 里的正文是**清洗过的最终文本**（上游已剥离工具调用段），
                    // 增量累积的则是**原始文本**（含 `tool_call: ...` 原文）。
                    // 必须用 Done 覆盖累积值 —— 否则工具调用会以"正文"形态泄漏给外部客户端；
                    // /v1 只是协议转换，不该把本机文本协议的原文转发出去。
                    //
                    // ⚠️ 判据是「本轮出现过工具调用 **或** Done 带正文」，而不是「Done 正文非空」：
                    // 模型整条回复就是一串调用时，剥离后正文**本来就是空的**，
                    // 用"非空"当条件会跳过覆盖、把增量攒下的调用原文留在正文里（实测踩到）。
                    if (toolCalls.isNotEmpty() || ev.content.isNotEmpty()) {
                        content = StringBuilder(ev.content)
                    }
                    if (ev.thinking.isNotEmpty() && reasoning.isEmpty()) {
                        reasoning = StringBuilder(ev.thinking)
                    }
                }
                is MessageEvent.Error -> error = ev.throwable.message ?: "upstream error"
                else -> {}
            }
        }
    }
}