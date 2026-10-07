package com.mcp.apifwd

import com.mcp.LogStore
import com.mcp.deepseek.AuthPrefs
import com.mcp.llm.MessageEvent
import com.mcp.toolbox.ToolCallGuard
import com.mcp.llm.ChatMessage
import com.mcp.llm.LLMClient
import com.mcp.llm.LLMClientFactory
import com.mcp.llm.LLMConfig
import com.mcp.llm.LLMRequest
import kotlinx.coroutines.flow.collect

/**
 * `/v1` 的执行器：**完全绕开 ChatBridge 的会话链**，直接驱动 [LLMClient]。
 *
 * ## 为什么不复用 `ChatBridge.streamLLM`
 * 那是为"App 前台聊天"写的高度有状态函数，带了一堆对外部 API 调用毫无必要、而且有害的副作用：
 * 注册 `StreamTaskManager`（App 里冒出"正在生成"气泡）、经进程级扇出把正文
 * **广播给所有桌面 SSE 订阅者**、把消息落盘进用户会话、触发上下文压缩与 session rotation。
 *
 * 直接驱动 LLMClient 则天然满足设计文档的三条隔离不变量：
 *  - **INV-1**：不碰 `bridge.currentSessionId`（本对象根本不持有 bridge）；
 *  - **INV-2**：不注册 StreamTaskManager、不广播 SSE、不落盘用户会话；
 *  - **INV-3**：不触发 App 前台的策略（重试 / 压缩 / 轮转）。
 *
 * ## 代价（有意接受的）
 * 失去 `streamLLM` 的限流重试与上下文溢出压缩。对 /v1 这是**正确的** ——
 * 那些是 App 前台的策略，不该由外部调用触发；上游限流会如实以错误返回，
 * 由外部客户端按 HTTP 状态码自己退避（这也正是阶段 1 把真实状态码接通的原因）。
 */
internal object CompatChatRunner {

    /**
     * app 侧行式解析器在解析失败时合成的特殊工具名（见 \`DeepSeekApi.kt:1282\`）。
     * 模型从未发起过它，因此**不是**可以交给客户端执行的工具。
     */
    private const val PARSE_ERROR_TOOL = "__parse_error__"

    /** 一次执行的结果。 */
    data class Outcome(
        /** 本轮产生的服务端消息 id；增量通道把它记成下一次续聊的锚点。 */
        val anchorMessageId: String?,
        /** provider 上报的真实 token 用量（有就透传给客户端）。 */
        val usage: MessageEvent.Usage?,
        /** 失败原因；null 表示成功收流。 */
        val error: Throwable?,
        /**
         * 被丢弃的「行式解析失败」合成调用数。
         *
         * 模型输出的文本工具调用不合规范时，app 侧解析器（DeepSeekApi.kt:1282）会合成一条
         * \`name="__parse_error__"\`、\`id=""\` 的**假调用**。它**绝不能**当工具调用转发给客户端：
         * 客户端会去执行一个不存在的工具，再拿一个无法关联的空 id 回传结果 ——
         * 模型收到不认识的结果，对话就此断掉（实测表现就是"没有延续"）。
         */
        val parseErrors: Int = 0,
        /**
         * 本轮 **user 消息**的服务端 id（来自 SSE 的 request_message_id）。
         *
         * 此前端点用 `assistantId - 1` 推算它。正常轮次 id 连续（user3/assistant4）时成立，
         * 但走分叉（edit_message 建兄弟分支）后实测会跳号（…18 → 21/22），推算就指错了 ——
         * 而 edit_message 的锚点必须是真实的 user id。服务端本就把两个 id 都发下来了。
         */
        val userMessageId: String? = null,
        /**
         * 解析失败**本身的原因**（来自 __parse_error__ 的 arguments.error）。
         *
         * 端点要据此生成 502 文案。此前文案是写死的「多行参数必须用 <<< >>> 定界块包裹」，
         * 而真机实测的错因是「参数 'THREW' 重复出现」—— 客户端拿着错的提示去改，白费一轮。
         * 错误必须可读，且必须是**真实**的那一个（§1.4）。
         */
        val parseErrorDetails: List<String> = emptyList(),
    )

    /**
     * 执行一个规划好的投喂。
     *
     * @param serverSessionId DeepSeek 逆向的服务端会话 id；全量通道忽略
     * @param parentMessageId 续聊锚点（= 上一条服务端消息 id）；仅增量 / 重放使用
     */
    suspend fun run(
        auth: AuthPrefs,
        plan: CompatPlan,
        thinking: Boolean,
        serverSessionId: String?,
        parentMessageId: String?,
        onEvent: (MessageEvent) -> Unit,
    ): Outcome {
        val request = buildRequest(plan, thinking, serverSessionId, parentMessageId)
            ?: return Outcome(null, null, null, 0)

        var anchor: String? = null
        var usage: MessageEvent.Usage? = null
        var error: Throwable? = null
        var parseErrors = 0
        var userMsgId: String? = null
        val parseErrorDetails = mutableListOf<String>()
        // 本轮已用过的工具调用 id（保证转发给客户端的 id 唯一）
        val seenToolCallIds = HashSet<String>()
        try {
            val stream = when (plan) {
                // 情形 C 的最优路径：以被编辑的那条用户消息为锚点原位建兄弟分支。
                // 服务端是消息树，所以分叉点之前的历史不必重建。
                is CompatPlan.EditBranch ->
                    LLMClientFactory.create(auth).editMessage(request, plan.editServerMessageId)
                else -> LLMClientFactory.create(auth).sendMessage(request)
            }
            stream.collect { ev ->
                when (ev) {
                    // 服务端分配的消息 id —— 必须捕获，它就是下一轮的 parent_message_id。
                    // 漏掉它，下一次续聊只会带 null 锚点 → 服务端开新分支 → 历史静默丢失。
                    is MessageEvent.MessageId -> {
                        anchor = ev.id
                        // 服务端下发的**本轮 user 消息 id**（request_message_id）。
                        // 有它就不必用「user = assistant − 1」推算 —— 分叉后 id 会跳号，
                        // 推算会指错消息，而 edit_message 的锚点必须是真实的 user id。
                        if (ev.requestId != null) userMsgId = ev.requestId
                        onEvent(ev)
                    }
                    // provider 的真实 token 用量：透传给外部客户端（OpenAI 的 usage 字段）。
                    is MessageEvent.Usage -> {
                        usage = ev
                        onEvent(ev)
                    }
                    is MessageEvent.ToolCall -> {
                        if (ev.name == PARSE_ERROR_TOOL) {
                            // app 侧解析器的合成调用：只计数，不转发（理由见 Outcome.parseErrors）
                            parseErrors++
                            // 留下**真实**错因，供端点生成 502 文案（默认文案会误导客户端）
                            runCatching {
                                org.json.JSONObject(ev.arguments).optString("error")
                            }.getOrNull()?.takeIf { it.isNotBlank() }
                                ?.let { parseErrorDetails.add(it) }
                            LogStore.w(
                                "OPENAI_COMPAT",
                                "模型的行式工具调用无法解析，已丢弃该合成调用（不转发给客户端）",
                            )
                        } else {
                            // 工具 id 分级门禁（委托 ToolCallGuard，统一畸形/重复的判定与自愈）：
                            //  - 空 id -> warning 级：补位后放行（自愈），下次请显式给定唯一 id；
                            //  - 重复 id -> error 级：拒绝该调用（不转发），避免客户端拿到重复 id
                            //    后构造不出合法 tool_call_id、工具结果关联不回来。错误文本由守卫产出，
                            //    调用方按需回传给模型。
                            val verdict = ToolCallGuard.guardId(ev.id, seenToolCallIds)
                            val fixedId = verdict.fixedId
                            when {
                                verdict.allow && fixedId != null -> {
                                    LogStore.w(
                                        "OPENAI_COMPAT",
                                        "工具调用 id 缺失，已补为 " + fixedId +
                                            " 以避免客户端 tool_call_id 关联失败",
                                    )
                                    onEvent(ev.copy(id = fixedId))
                                }
                                verdict.allow -> onEvent(ev)
                                else -> {
                                    LogStore.w(
                                        "OPENAI_COMPAT",
                                        "工具调用 id 重复（" + ev.id + "），已拒绝该调用并将错误回传",
                                    )
                                }
                            }
                        }
                    }
                    is MessageEvent.Error -> {
                        error = ev.throwable
                        onEvent(ev)
                    }
                    else -> onEvent(ev)
                }
            }
        } catch (e: Throwable) {
            error = e
        }
        return Outcome(anchor, usage, error, parseErrors, userMsgId, parseErrorDetails)
    }

    /** 本次投喂要发出去的正文。 */
    fun promptOf(plan: CompatPlan): String = when (plan) {
        is CompatPlan.Incremental -> plan.userText
        is CompatPlan.Regenerate -> plan.userText
        is CompatPlan.EditBranch -> plan.userText
        is CompatPlan.PackedReplay -> plan.prompt
        is CompatPlan.FullMessages -> ""
    }

    private fun buildRequest(
        plan: CompatPlan,
        thinking: Boolean,
        serverSessionId: String?,
        parentMessageId: String?,
    ): LLMRequest? = when (plan) {
        // 全量通道：外部 messages 原样交给上游。上游自己处理前缀缓存，
        // 本地不做任何裁剪 —— system / assistant / tool 一个都不能丢。
        is CompatPlan.FullMessages -> LLMRequest(
            messages = plan.messages,
            tools = null,
            config = LLMConfig(thinkingEnabled = thinking, searchEnabled = false),
        )

        // 增量通道：上游只吃「单条 prompt + 锚点」，历史由服务端会话树承担。
        is CompatPlan.Incremental, is CompatPlan.Regenerate,
        is CompatPlan.EditBranch, is CompatPlan.PackedReplay ->
            LLMRequest(
                messages = listOf(ChatMessage(role = "user", content = promptOf(plan))),
                tools = null,
                config = LLMConfig(
                    sessionId = serverSessionId,
                    parentMessageId = parentMessageId,
                    thinkingEnabled = thinking,
                    searchEnabled = false,
                ),
            )
    }
}
