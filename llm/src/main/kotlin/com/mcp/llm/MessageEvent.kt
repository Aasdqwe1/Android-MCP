package com.mcp.llm

/**
 * LLM 流式事件（原 com.mcp.llm.MessageEvent，提取为后端通用类型）。
 * 所有后端（OpenAI / Web自动化 / LiteRT / MNN）共用。
 */
sealed interface MessageEvent {
    /** PoW 求解进度（tried 已尝试次数，total 为难度上限）。 */
    data class PowProgress(val tried: Int, val total: Int) : MessageEvent
    /** 思考过程增量片段。 */
    data class Thinking(val delta: String) : MessageEvent
    /** 正式回复增量片段。 */
    data class Content(val delta: String) : MessageEvent
    /**
     * 思考区**整段替换**（Web 自动化专用）。
     *
     * 网页站点整段重渲染时，采集到的思考文本不再是旧基准的前缀，增量模型无法
     * 表达「改写」，只能整段覆盖——否则那一轮新增内容丢失，气泡缺字。
     */
    data class ThinkingReplace(val full: String) : MessageEvent
    /** 正文**整段替换**（Web 自动化专用）：理由同 [ThinkingReplace]。 */
    data class ContentReplace(val full: String) : MessageEvent
    /** 服务端下发的助手消息 id（best-effort 提取），用于下一轮 parent_message_id 串联。 */
    /**
 * 助手消息 id。
 *
 * @param requestId **本轮 user 消息 id**（SSE 的 request_message_id）。
 *
 * 逆向协议每轮创建两条消息，但**并不保证 id 相邻**：正常轮次实测是
 * user(3)/assistant(4)、user(5)/assistant(6) 这样连续；一旦走分叉（edit_message 建兄弟分支）
 * 就变成 …18 → 21/22、25/26，跳号。此时用「user = assistant − 1」推算会**指错消息**，
 * 而 edit_message 的锚点正需要**真实**的 user id。服务端本来就把两个 id 都发下来了，
 * 没有理由再推算。
 */
data class MessageId(val id: String, val requestId: String? = null) : MessageEvent
    /** LLM 发起的工具调用（function calling）。 */
    data class ToolCall(val id: String, val name: String, val arguments: String) : MessageEvent
    /**
     * provider 上报的**真实** token 用量（route-priced）。
     *
     * 对齐 deepseek-harness token-meter 的双轨设计：dsh 同时维护
     * `tokens`（provider 回传、按路由计价）与 `heuristicTokens`（本地估算），
     * 并用前者校准后者——纯字符数启发式对中文、代码、JSON 的偏差可达 2 倍以上，
     * 长期不校准会让压缩阈值系统性失真（要么过早压缩，要么直到溢出才恢复）。
     *
     * 仅在响应带 `usage` 字段时发射（流式需 `stream_options.include_usage=true`）；
     * 未提供时上层继续用启发式值，不影响功能。
     */
    data class Usage(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
        /**
         * 推理 token（reasoning_tokens）：OpenAI 兼容网关在
         * `completion_tokens_details.reasoning_tokens` 上报；对齐 dsh translate.ts 的
         * `reasoningTokens`。-1 表示响应未携带该字段（纯文本模型/未启用思考）。
         */
        val reasoningTokens: Int = -1,
        /**
         * 命中前缀缓存的 prompt token（`prompt_tokens_details.cached_tokens` /
         * `prompt_cache_hit_tokens`）：对齐 dsh `cacheReadTokens`。
         * 非零即证明 provider 的暖缓存被复用——直接验证 A1「前缀缓存友好化」是否真命中，
         * 否则字节前缀虽然稳定、却可能因计费口径/路由变化而落空。-1 表示未携带。
         */
        val cacheReadTokens: Int = -1,
        /**
         * 是否**只有 total 是 provider 真实值**、prompt/completion 拆分是本地推算。
         *
         * DeepSeek 逆向 SSE 只回传 `accumulated_token_usage`（总 token 数），
         * 不提供 prompt/completion 拆分。此处 prompt 用本地启发式估算、completion 由
         * `total - prompt` 反推，二者都带噪声。置 true 让上层（ChatBridge）**跳过**
         * `ContextCompactor.calibrate`——用推算值校准 tokenScale 会把噪声灌进压缩阈值，
         * 危害远大于看板数字不精确；看板本身照常展示 total 真实值与估算拆分。
         * OpenAI 兼容后端有完整拆分，恒为 false。
         */
        val providerUsageOnly: Boolean = false,
    ) : MessageEvent

    /**
     * 整段已归并：思考过程 + 正式回复。
     *
     * @param truncated 输出是否被 max_tokens 上限截断（finish_reason == "length"）。
     *   对齐 deepseek-harness `finishError`：`max-tokens` 被当作**失败**而非正常结束
     *   （dsh 原话 "summarization truncated at the token cap (incomplete checkpoint)"）。
     *   调用方据此拒绝残缺结果——尤其是上下文压缩的摘要：装进一个被截断的检查点
     *   会让后续对话永久丢失后半段信息，且不报错。
     *   带默认值，既有的两参数构造点不受影响。
     */
    data class Done(val thinking: String, val content: String, val truncated: Boolean = false) : MessageEvent
    /** 异常（网络/业务/求解）。 */
    data class Error(val throwable: Throwable) : MessageEvent
    /** 自动重试信号（由 close 事件中的 auto_resume=true 触发）。 */
    object AutoRetry : MessageEvent
}
