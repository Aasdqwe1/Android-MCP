package com.mcp.apifwd

import com.mcp.core.llm.BackendType
import com.mcp.llm.ChatMessage
import com.mcp.llm.WebAutomationPrompt
import com.mcp.toolbox.ToolMessages

/**
 * 该后端的上游是否接受**完整** `messages[]` 数组。
 *
 * 这是设计文档 §7.1 的唯一判据 —— 按**接口形态**分，不是按后端名字猜：
 *  - OPENAI / WEB_AUTOMATION / LITERT：上游接全量 messages，透传即可，**不需要**本地记录；
 *  - DEEPSEEK_REVERSE：上游是「单条 prompt + chat_session_id + parent_message_id」，
 *    **没有批量投喂接口** —— 所以本地记录是必需品，而不是优化。
 */
fun acceptsFullMessages(backend: BackendType): Boolean = when (backend) {
    BackendType.OPENAI, BackendType.WEB_AUTOMATION, BackendType.LITERT, BackendType.MNN -> true
}

/**
 * 客户端自跑工具循环时，末尾会追加 `role:"tool"` 的结果（OpenAI 语义）。
 * 返回按 App 行式协议封装的结果块；没有工具结果时返回 null。
 *
 * 为什么必须有这一步：/v1 在增量/绑定模式下只发"最后一条 user"。
 * 客户端执行完工具后把结果以 `tool` 角色追加进来，那不是 user ——
 * 于是我们会**把旧问题再发一遍**，模型看不到结果，只好重复调用同一个工具，
 * 连环 tool_call 之后被上游限流。实测 DSH 连调 9 次后 DeepSeek 返回 RATE_LIMIT。
 */
fun toolResultsPrompt(messages: List<ChatMessage>): String? {
    val tail = messages.takeLastWhile { it.role == "tool" }
    if (tail.isEmpty()) return null
    return tail.joinToString("\n") { m ->
        ToolMessages.lineResult(
            m.toolCallId.orEmpty().ifEmpty { "call" },
            // CRLF 归一为 LF：工具结果常来自 Windows（pwsh 输出、路径），原样透传会把 

            // 带进 prompt，既让提示词长度统计偏大，也让同一内容在 LF/CRLF 下字节不同
            // （指纹比对已归一，但实际下发的文本没有）。
            m.content.orEmpty().replace("\r\n", "\n").replace('\r', '\n'),
        )
    }
}

// ───────────────────────── 绑定模式决策（纯函数，便于单测） ─────────────────────────

/**
 * 绑定模式下「本轮发什么、能否推进轮次表」的决策结果。
 *
 * 抽成纯函数的原因：这段判断原先内联在 `chatCompletions` 里，与 Context / org.json 耦合，
 * **无法单测** —— 而它恰恰是绑定路径的核心（前几轮多起缺陷都出在这里）。
 */
data class BoundDecision(
    /** 是否发送客户端全量转录（而非只发最后一条 user）。 */
    val sendTranscript: Boolean,
    /** 是否允许推进轮次表（失配时禁止，避免覆盖链依据）。 */
    val canAdvanceTable: Boolean,
    /** 分叉且能定位被编辑的用户消息时，走 edit_message 原位建分支；null 表示不走。 */
    val editBranchMessageId: String?,
)

/**
 * 绑定模式决策。
 *
 * @param tableReady 记录里已有轮次（即链的依据已建立）
 * @param hasToolResults 本轮携带工具结果
 * @param isFirstUpstreamTurn 服务端会话还没有锚点（首次进入该会话）
 * @param match 客户端 messages 与本地轮次表的比对结果
 * @param nonSystemCount 非 system 消息条数（决定转录是否有意义）
 * @param editServerMessageId 分叉点可定位到的「被编辑用户消息」的服务端 id
 */
fun decideBound(
    tableReady: Boolean,
    hasToolResults: Boolean,
    isFirstUpstreamTurn: Boolean,
    match: ConvMatch,
    nonSystemCount: Int,
    editServerMessageId: String?,
): BoundDecision {
    // ⚠️ 携带工具结果的请求**不参与分叉判定**：轮次表粒度是「一轮 user + 一轮 assistant」，
    // 而工具循环是「assistant(含 K 个 tool_calls) → K 条 tool 结果 → assistant…」，表里没有 tool 的位置，
    // 拿它比对必然失配。实测：模型一轮发 3 个 tool_call 后**每轮都判 Fork**。
    val consistent = hasToolResults || match is ConvMatch.Incremental || match is ConvMatch.Idempotent
    // 链不可信：首次进入、记录缺失、客户端改了历史、或一次追加多条
    val untrusted = isFirstUpstreamTurn || match is ConvMatch.NoMatch || match is ConvMatch.Fork
    return BoundDecision(
        // 多条非 system 消息时不能只发最后一条（实测 DSH 一轮发 [system,user,user]，只取末条会丢掉真问题）
        sendTranscript = untrusted && nonSystemCount > 1,
        // 表未被建立时可建立；已建立时只有一致的请求才能推进
        canAdvanceTable = !tableReady || consistent,
        // ⚠️ 必须同时排除 hasToolResults：既然工具结果不参与分叉判定（它必然失配），
        // 就不能拿这次失配去触发 edit_message —— 否则工具循环里每一轮都会在链上乱建分支。
        // （此条由单测发现：初版只判了 match is Fork，端点内联版本同样漏判。）
        editBranchMessageId = if (match is ConvMatch.Fork && !hasToolResults) editServerMessageId else null,
    )
}

/** 本次 /v1 请求应当如何投喂上游。由 [planCompat] 纯函数推导，便于单测。 */
sealed interface CompatPlan {
    /** **全量通道**：外部 messages 原样交给上游（无需本地记录）。 */
    data class FullMessages(val messages: List<ChatMessage>) : CompatPlan

    /** **增量通道 · 情形 A**：只发客户端新增的那一条 user，续聊靠服务端锚点。 */
    data class Incremental(val userText: String, val newUserIndex: Int) : CompatPlan

    /** **增量通道 · 情形 B**：客户端重发了已覆盖的内容 —— 重新生成最后一条 user。 */
    data class Regenerate(val userText: String) : CompatPlan

    /**
     * **情形 C 的最优路径**：在分叉点**原位建兄弟分支**。
     *
     * DeepSeek 服务端是消息树，`edit_message` 能以被编辑的那条用户消息为锚点，
     * 在其父节点下新建「新提问 + 新回复」的兄弟分支。于是分叉点**之前**的服务端历史
     * 完全不必重建 —— 比"另起会话 + 打包重放"便宜得多，也不丢上下文。
     *
     * @param editServerMessageId 被编辑的那条**用户**消息的服务端 id（分叉点的左邻）
     */
    data class EditBranch(
        val userText: String,
        val editServerMessageId: String,
        val forkClientIndex: Int,
    ) : CompatPlan

    /**
     * **情形 D / 分叉的兜底**：把整段历史渲染成**一条** prompt 单次投喂。
     *
     * 逐轮重放对 DeepSeek 逆向不可行（30 轮 = 30 次往返，必然拖到 HTTP 超时），
     * 所以打包成一条 —— 只要 1 次往返。代价是模型看到的是转录文本而非原生轮次结构。
     *
     * @param packedCount 被吞进这条 prompt 的客户端消息条数（写入记录的 packedThrough）
     * @param resetSession 是否放弃原服务端会话另起一个。
     *   分叉（客户端改了历史）时必须为 true —— 服务端那棵树已经与客户端叙事不一致，
     *   继续在它上面续聊只会得到自相矛盾的上下文。
     */
    data class PackedReplay(
        val prompt: String,
        val packedCount: Int,
        val resetSession: Boolean,
    ) : CompatPlan
}

/**
 * 推导本次请求的投喂方式。
 *
 * 这是增量通道的决策核心：[ConversationRecord] 是**经过校验的缓存**，
 * 比对一旦失败就必须退化到打包重放，**绝不能带着可能失效的锚点继续发**
 * （那会让服务端开新分支、历史静默丢失）。
 */
fun planCompat(
    backend: BackendType,
    record: ConversationRecord?,
    messages: List<ChatMessage>,
    toolsDeclaration: String? = null,
): CompatPlan {
    // 外部声明的 tools 以**文本声明**形式随 prompt 下发，与 App 内部同一套行式协议 ——
    // 本机后端没有原生 function calling，声明只能走文本，模型按文本回复即可。
    //
    // 只在「上游会话刚开始」时注入一次：DeepSeek 逆行的工具清单本就是仅首条注入，
    // 之后由服务端会话记住；每轮重复注入只会撑大上下文并造成指令冲突。
    fun withDecl(text: String): String =
        if (toolsDeclaration.isNullOrBlank()) text
        else toolsDeclaration + "\n\n---\n\n" + text

    if (acceptsFullMessages(backend)) {
        val msgs = if (toolsDeclaration.isNullOrBlank()) messages
        else listOf(ChatMessage(role = "system", content = toolsDeclaration)) + messages
        return CompatPlan.FullMessages(msgs)
    }

    // 客户端刚回传了工具结果 → 那就是本轮该发给上游的内容（与已有记录无关）
    toolResultsPrompt(messages)?.let {
        return CompatPlan.Incremental(withDecl(it), messages.lastIndex)
    }

    val clientTurns = normalizeClientMessages(messages)
    val match = record?.let { matchConversation(it, clientTurns) } ?: ConvMatch.NoMatch

    return when (match) {
        is ConvMatch.Incremental ->
            CompatPlan.Incremental(withDecl(textAt(messages, match.newUserIndex)), match.newUserIndex)

        // 情形 B：请求体与上一轮完全相同 —— 无法区分"超时重试"与"用户点了重新生成"，
        // 按设计文档 §7.10 的决策：不缓存正文，重发就重新生成。
        ConvMatch.Idempotent ->
            CompatPlan.Regenerate(withDecl(lastUserText(messages) ?: messages.last().content.orEmpty()))

        // 情形 C：客户端改了历史。
        // 优先走 edit_message 原位建兄弟分支 —— 服务端是消息树，分叉点之前的历史不必重建。
        // 只有当分叉点上没有可编辑的用户消息（例如客户端只是把尾部删了、
        // 或那条消息没拿到服务端 id）时，才退化为"另起会话 + 打包重放"。
        is ConvMatch.Fork -> {
            val idx = match.matchedClientMessages
            val editTurn = record?.turns?.getOrNull(idx - record.packedThrough)
            val clientMsg = messages.getOrNull(idx)
            val editId = editTurn?.serverMessageId
            // 抽成局部 val：跨模块的 public 属性无法参与 smart cast
            val clientText = clientMsg?.content
            if (editTurn != null && editId != null && editTurn.role == "user" &&
                clientMsg?.role == "user" && !clientText.isNullOrBlank()
            ) {
                CompatPlan.EditBranch(withDecl(clientText), editId, idx)
            } else {
                CompatPlan.PackedReplay(renderTranscript(messages), messages.size, resetSession = true)
            }
        }

        // 情形 D：新对话，或记录已失效 / 被截断到打包点之前
        ConvMatch.NoMatch ->
            CompatPlan.PackedReplay(
                prompt = withDecl(renderTranscript(messages)),
                packedCount = messages.size,
                resetSession = record != null,
            )
    }
}

/**
 * 把整段历史渲染成一条 prompt（打包重放用）。
 *
 * 复用 [WebAutomationPrompt.build] 而**不是**另写一个渲染器：
 * 它在 Web 自动化通道上本来就承担同一件事（`[系统]/[用户]/[助手]/[工具]` 逐条渲染），
 * 共用一份可以避免两条通道出现"渲染方言"漂移。
 *
 * 注意它对单条消息会直接返回原文 —— 所以"1 条消息的打包重放"等价于原样直发，无额外损耗。
 */
fun renderTranscript(messages: List<ChatMessage>): String =
    // renderToolCalls = true：助手轮的 tool_calls 必须进转录，否则那一轮 content 为空会被整行跳过，
    // 模型只看到悬空的 [工具] 结果，也拿不到行式协议的在场示例（真机实测会退化成裸 JSON 参数块）。
    WebAutomationPrompt.build(messages, renderToolCalls = true)

private fun textAt(messages: List<ChatMessage>, index: Int): String =
    messages.getOrNull(index)?.content.orEmpty()

private fun lastUserText(messages: List<ChatMessage>): String? =
    messages.lastOrNull { it.role == "user" }?.content?.takeIf { it.isNotBlank() }
