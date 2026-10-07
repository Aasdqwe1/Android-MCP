package com.mcp.compaction

import com.mcp.llm.ChatMessage
import com.mcp.toolbox.ToolDef
import kotlin.math.roundToInt

/**
 * 上下文压缩策略——对齐 deepseek-harness dsh-compaction-basic 的设计（纯策略，无 Android / LLM 客户端依赖）。
 *
 * 移植自 deepseek-harness 的核心机制：
 * 1. **压力阈值（模型感知）**——threshold = contextWindow × thresholdRatio，而非硬编码 token 数；
 *    模型窗口由 [contextWindowFor] 按已知模型表解析，未知模型回退 [DEFAULT_CONTEXT_WINDOW]。
 * 2. **Token 预算保留**——retain = contextWindow × retainRatio，从尾部累计 token 直到预算，
 *    另设最小消息条数地板，防止把整段对话都摘要掉。
 * 3. **Tool-pairing 平衡切点**——切点必须位于"工具调用已全部闭合"的位置：
 *    绝不切断 assistant(tool_calls) 与其 tool 结果对（否则 OpenAI 协议 400）。
 * 4. **收敛验证**——摘要检查点必须明显小于被替换范围（shrink 验证），
 *    不缩小时由调用方降级为模型无关截断；替换后仍超阈值可递归再压一轮（合并旧检查点）。
 * 5. **溢出分类**——[isContextWindowExceededError] 识别 provider 的上下文窗口溢出错误，
 *    供上层走溢出恢复路径（绕过正常压力阈值）。
 */
class ContextCompactor(
    /** 触发压缩的阈值比例（默认 0.8，与 deepseek-harness 一致）。 */
    val thresholdRatio: Double = DEFAULT_THRESHOLD_RATIO,
    /** 保留最近消息的 token 预算比例（默认 0.16，与 deepseek-harness 一致）。 */
    val retainRatio: Double = DEFAULT_RETAIN_RATIO,
    /** 最小保留消息条数（含最新用户消息），防止过度压缩。 */
    val minRetainMessages: Int = MIN_RETAIN_MESSAGES,
    /**
     * 单条工具结果剪枝阈值（**Unicode code point** 数，非 UTF-16 长度）。
     * 超出则保留头 [pruneHeadChars] + 尾 [pruneTailChars]，中间替换成省略标记。
     * 默认值对齐 deepseek-harness compaction-tool-result-pruner 的
     * `DEFAULTS`（thresholdChars 8192 / headChars 4096 / tailChars 1024）——
     * 那套默认值是照 coding-agent 的工具输出（文件内容、构建日志）调的。
     */
    val toolResultMaxChars: Int = PRUNE_THRESHOLD_CHARS,
    /** 剪枝保留的头部字符数（code point）。 */
    val pruneHeadChars: Int = PRUNE_HEAD_CHARS,
    /** 剪枝保留的尾部字符数（code point）。 */
    val pruneTailChars: Int = PRUNE_TAIL_CHARS
) {

    /**
     * 当前模型对应的压缩触发阈值（token）：threshold = min(window × thresholdRatio, maxInput)。
     * 输入部分必须给输出预留预算（思考/生成），否则会触发 finish_reason=length 截断。
     * @param windowOverride 用户手填的上下文窗口（tokens）；>0 时覆盖模型表/默认推断。
     * @param maxInputOverride 用户手填的最大输入（tokens）；>0 时作为阈值上限，与窗口比例取小者。
     */
    fun thresholdTokens(model: String?, windowOverride: Int = 0, maxInputOverride: Int = 0): Int {
        val window = if (windowOverride > 0) windowOverride else contextWindowFor(model)
        val byWindow = (window * thresholdRatio).toInt()
        return if (maxInputOverride > 0) minOf(byWindow, maxInputOverride) else byWindow
    }

    /**
     * 当前模型对应的保留预算（token）：retain = window × retainRatio。
     * @param windowOverride 用户手填的上下文窗口（tokens）；>0 时覆盖模型表/默认推断。
     */
    fun retainTokensFor(model: String?, windowOverride: Int = 0): Int {
        val window = if (windowOverride > 0) windowOverride else contextWindowFor(model)
        return (window * retainRatio).toInt()
    }

    // ── Token 计量（对齐 dsh token-meter 的双轨：heuristicTokens ↔ tokens）────

    /**
     * 启发式估算的校准系数：真实 token / 启发式 token 的指数滑动平均。
     *
     * 纯字符数启发式（`chars/3 + 12`）对中文、代码、JSON Schema 的偏差可达 2 倍以上：
     * 中文按 code point 算约 1 字/token 而非 1/3，JSON 的括号与键名则远密于 3 字符/token。
     * dsh 因此同时维护 `heuristicTokens`（估算）与 `tokens`（provider 回传、按路由计价），
     * 并用后者持续校准前者。这里做同样的事，否则压缩阈值会系统性失真——
     * 估低了直到上下文溢出才被动恢复，估高了则在窗口还很宽裕时白白丢上下文。
     */
    @Volatile
    private var tokenScale: Double = 1.0

    /** 已用于校准的样本数（0 = 尚未拿到任何 provider 上报值，仍为纯启发式）。 */
    @Volatile
    private var calibratedSamples: Int = 0

    /** 当前校准系数（诊断/日志用）。 */
    val tokenScaleValue: Double get() = tokenScale

    /** 是否已完成至少一次校准。 */
    val isCalibrated: Boolean get() = calibratedSamples > 0

    /**
     * 用 provider 上报的真实 prompt token 数校准启发式估算。
     *
     * @param promptTokens 响应 usage.prompt_tokens（route-priced，含 tools 与 system）。
     * @param heuristicTokens 同一请求按 [rawEstimateTokens] + [rawEstimateToolsTokens]
     *   算出的**未校准**估算值。必须传未校准值：若传已乘过 [tokenScale] 的结果，
     *   比值恒为 1，校准会自我抵消成空转。
     */
    fun calibrate(promptTokens: Int, heuristicTokens: Int) {
        if (promptTokens <= 0 || heuristicTokens <= 0) return
        val ratio = (promptTokens.toDouble() / heuristicTokens).coerceIn(MIN_TOKEN_SCALE, MAX_TOKEN_SCALE)
        // 首个样本直接采信；之后走 EMA（α=0.3）——单次观测噪声很大
        // （工具定义条数、多模态附件、网关自身的计费口径都会扰动），
        // 但长期偏移必须收敛，故只用新样本拉动三成。
        val next = if (calibratedSamples == 0) ratio
        else tokenScale * (1.0 - EMA_ALPHA) + ratio * EMA_ALPHA
        tokenScale = next.coerceIn(MIN_TOKEN_SCALE, MAX_TOKEN_SCALE)
        calibratedSamples++
    }

    /**
     * **未校准**的粗估 token 数（中英混合按 3 字符/token，每条消息加结构开销）。
     * 对齐 deepseek-harness token-meter 的固定启发式回退（字符数 + 结构开销）。
     *
     * 校准只作用于 [estimateTokens]；[calibrate] 必须基于这个未校准口径做比值，
     * 否则会形成「自己除自己」的空转回路。
     */
    fun rawEstimateTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { msg ->
            val textLen = msg.content?.length ?: 0
            val toolLen = msg.toolCalls?.sumOf { tc ->
                tc.function.name.length + tc.function.arguments.length + 20
            } ?: 0
            val reasonLen = msg.reasoning?.length ?: 0
            (textLen + toolLen + reasonLen) / 3 + 12
        }

    /** 校准后的 token 估算。压缩阈值、保留预算、收缩验证全部走这个口径。 */
    fun estimateTokens(messages: List<ChatMessage>): Int =
        (rawEstimateTokens(messages) * tokenScale).roundToInt()

    /**
     * 模型无关的工具结果剪枝（就地）：超长 tool 消息保留头 [pruneHeadChars] + 尾 [pruneTailChars]，
     * 中间替换为省略标记。对齐 deepseek-harness compaction-tool-result-pruner 的裁剪语义。
     *
     * 全部按 **Unicode code point** 计数（对齐 dsh `codePointLength`）：Kotlin 的
     * `String.length` 是 UTF-16 code unit 数，直接 take/takeLast 可能把 emoji、
     * CJK 扩展区等增补平面字符的**代理对拦腰截断**，产出非法字符串发给上游。
     */
    fun pruneToolResults(messages: MutableList<ChatMessage>) {
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.role != "tool") continue
            val c = msg.content ?: continue
            if (codePointLength(c) <= toolResultMaxChars) continue
            val head = headByCodePoints(c, pruneHeadChars)
            val tail = tailByCodePoints(c, pruneTailChars)
            val removed = codePointLength(c) - codePointLength(head) - codePointLength(tail)
            messages[i] = msg.copy(
                content = "$head\n…[已截断 $removed 字符]…\n$tail"
            )
        }
    }

    // ── Unicode code point 安全截取（对齐 dsh codePointLength 的计数口径） ──────

    /** 按 Unicode code point 计数的字符串长度（避免增补平面字符被算成 2）。 */
    fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

    /** 取前 [n] 个 code point，绝不切断代理对。 */
    fun headByCodePoints(text: String, n: Int): String {
        if (n <= 0) return ""
        if (codePointLength(text) <= n) return text
        var idx = 0
        var count = 0
        while (count < n && idx < text.length) {
            idx += Character.charCount(text.codePointAt(idx))
            count++
        }
        return text.substring(0, idx)
    }

    /** 取后 [n] 个 code point，绝不切断代理对。 */
    fun tailByCodePoints(text: String, n: Int): String {
        if (n <= 0) return ""
        if (codePointLength(text) <= n) return text
        var idx = text.length
        var count = 0
        while (count < n && idx > 0) {
            val cp = text.codePointBefore(idx)
            idx -= Character.charCount(cp)
            count++
        }
        return text.substring(idx)
    }

    /**
     * 粗估 tools 定义的 token 数（工具名 + 描述 + 参数 JSON Schema）。
     * tools 参数每次请求全量携带，大量工具可达数万 tokens，必须计入压缩压力判断
     * （对齐 deepseek-harness token-meter 将工具计入压力），否则估算偏低、压缩阈值失真。
     */
    fun estimateToolsTokens(tools: List<ToolDef>?): Int =
        (rawEstimateToolsTokens(tools) * tokenScale).roundToInt()

    /** 未校准的工具定义估算（[calibrate] 的比值分母走这个口径）。 */
    fun rawEstimateToolsTokens(tools: List<ToolDef>?): Int {
        if (tools.isNullOrEmpty()) return 0
        return tools.sumOf { t ->
            val schema = t.inputSchema.toString()
            (t.name.length + t.description.length + schema.length) / 3 + 12
        }
    }

    // ── Token 类别细分（对齐 deepseek-harness token-meter 的 system/tools/message 分类） ──

    /** 按消息角色/类型细分的 token 估算（未校准口径，用于流水上报）。 */
    data class TokenCategoryBreakdown(
        val system: Int = 0,
        val user: Int = 0,
        val toolRequest: Int = 0,
        val toolResponse: Int = 0,
        val tools: Int = 0,
        val content: Int = 0,
        val thinking: Int = 0,
        val other: Int = 0
    ) {
        val total get() = system + user + toolRequest + toolResponse + tools + content + thinking + other
    }

    /** 按消息角色/类型估算 token 分布（未校准，与 rawEstimateTokens 口径一致）。 */
    fun rawEstimateTokensByCategory(messages: List<ChatMessage>, tools: List<ToolDef>?): TokenCategoryBreakdown {
        var system = 0; var user = 0; var toolRequest = 0; var toolResponse = 0; var content = 0; var thinking = 0; var other = 0
        for (msg in messages) {
            val textLen = msg.content?.length ?: 0
            val reasonLen = msg.reasoning?.length ?: 0
            val msgTokens = (textLen + reasonLen) / 3 + 12
            when (msg.role) {
                "system" -> system += msgTokens
                "user" -> user += msgTokens
                "tool" -> toolResponse += msgTokens
                "assistant" -> {
                    if (msg.toolCalls != null && msg.toolCalls!!.isNotEmpty()) {
                        val tcTokens = msg.toolCalls!!.sumOf { tc ->
                            (tc.function.name.length + tc.function.arguments.length + 20) / 3 + 4
                        }
                        toolRequest += tcTokens
                    } else {
                        val textLen = msg.content?.length ?: 0
                        val reasonLen = msg.reasoning?.length ?: 0
                        content += textLen / 3 + 6
                        thinking += reasonLen / 3 + 6
                    }
                }
                else -> other += msgTokens
            }
        }
        return TokenCategoryBreakdown(
            system = system, user = user, toolRequest = toolRequest,
            toolResponse = toolResponse, tools = rawEstimateToolsTokens(tools),
            content = content, thinking = thinking, other = other
        )
    }

    /**
     * 选择要保留的最近尾部（保留预算 + 条数地板 + 平衡切点）。
     *
     * 对齐 deepseek-harness selectCompactableRange：
     * 1. 从尾部累计 token 直到 >= retainTokens，得到首个保留下标；
     * 2. 条数地板：至少保留 minRetainMessages 条；
     * 3. 平衡修正：切点前移（保留更多）直到 tool-pairing 平衡；
     * 4. 尾部本身必须是闭合的（无未闭合工具调用），否则拒绝压缩。
     *
     * @return null 表示没有可安全压缩的范围（对话太短 / 全部要保留 / 尾部未闭合）。
     */
    fun selectRetainedTail(
        nonSystem: List<ChatMessage>,
        retainTokens: Int,
        minRetain: Int = minRetainMessages
    ): RetainedSelection? {
        if (nonSystem.isEmpty()) return null
        // 对话太短，不值得压缩
        if (nonSystem.size <= minRetain + 2) return null
        // 尾部未闭合（最新消息仍是未配对的工具调用）→ 拒绝压缩
        if (!toolPairingBalanced(nonSystem, nonSystem.size)) return null

        // 1) token 预算：从尾部累加，记录首个满足预算的下标（budgetIdx；预算未满足时为 0）
        var acc = 0
        var budgetIdx = 0
        for (i in nonSystem.indices.reversed()) {
            acc += estimateTokens(listOf(nonSystem[i]))
            if (acc >= retainTokens) { budgetIdx = i; break }
        }
        // 2) 条数地板位置：至少保留 minRetain 条（仅当预算宽裕时兜底）
        val floorIdx = nonSystem.size - minRetain
        // 3) 取两者较大者：token 预算优先（单条消息很长时宁可保留更少条），
        //    避免「8 条长思考消息 ≈ 数万 tokens」导致压缩/降级截断几乎无效。
        var keepFrom = maxOf(budgetIdx, floorIdx)
        // 4) 平衡修正：起点边界前移直到平衡（保留更多）。
        //    对齐 dsh：切点必须落在 isBalancedBefore 边界上，绝不切断 tool-call/result 对。
        while (keepFrom > 0 && !isBalancedBefore(nonSystem, keepFrom)) {
            keepFrom -= 1
        }
        if (keepFrom <= 0) return null
        // 5) 终点边界无需再校验：本选择是 head-anchored（范围恒从 0 起），
        //    起点边界恒平衡；终点边界 = 切点自身，已由上面的循环保证。
        //    dsh 需双向校验是因为它支持任意区间（rewind/rewrite 可从中段切）；
        //    若日后支持任意区间，应按 validateSurfaceRegion 的形态补上
        //    isBalancedAfter(nonSystem, keepFrom - 1) 检查。

        val shadowed = nonSystem.subList(0, keepFrom).toList()
        val retained = nonSystem.subList(keepFrom, nonSystem.size).toList()
        return RetainedSelection(
            shadowed = shadowed,
            retained = retained,
            shadowedTokens = estimateTokens(shadowed),
            retainedTokens = estimateTokens(retained),
            shadowedRange = 0..(keepFrom - 1)
        )
    }

    /**
     * 切点 [cutIndex] 是否 tool-pairing 平衡：前 [0, cutIndex) 中所有工具调用均已闭合
     * （assistant(tool_calls) 的调用数 == 后续 tool 消息数），且无孤立 tool 消息。
     */
    fun toolPairingBalanced(messages: List<ChatMessage>, cutIndex: Int): Boolean {
        var open = 0
        for (i in 0 until cutIndex) {
            val m = messages[i]
            val calls = m.toolCalls?.size ?: 0
            when {
                calls > 0 -> open += calls
                m.role == "tool" -> open -= 1
            }
            if (open < 0) return false // 防御：孤立 tool 消息（非法序列）
        }
        return open == 0
    }

    // ── 对齐 dsh toolPairingBalancedBefore / toolPairingBalancedAfter ──────────

    /**
     * 下标 [index] **之前**的边界是否 tool-pairing 平衡（[0, index) 内工具调用全部闭合）。
     *
     * 对齐 dsh `toolPairingBalancedBefore`：压缩范围的**起点**必须落在此类边界上，
     * 否则会把 assistant(tool_calls) 与其 tool 结果切到范围两侧（OpenAI 协议 400）。
     */
    fun isBalancedBefore(messages: List<ChatMessage>, index: Int): Boolean =
        toolPairingBalanced(messages, index)

    /**
     * 下标 [index] **之后**的边界是否 tool-pairing 平衡（[0, index] 内工具调用全部闭合）。
     *
     * 对齐 dsh `toolPairingBalancedAfter`：压缩范围的**终点**必须落在此类边界上，
     * 否则会切进一个尚未闭合的 step。
     */
    fun isBalancedAfter(messages: List<ChatMessage>, index: Int): Boolean =
        toolPairingBalanced(messages, index + 1)

    // ── 稳定性校验（对齐 dsh assertSelectedSpanStable） ────────────────────────

    /**
     * 校验被选中的压缩范围在异步摘要期间**未发生变化**。
     *
     * 摘要需调用 LLM（异步、秒级），期间用户可能继续发消息、工具可能回结果。
     * 采用 dsh 的 `selected-span` 语义：**范围外新增的节点保持可见、不算失效**
     * （追加发生在尾部，不影响头部的被替换范围）；但范围本身若被改写
     * （历史被编辑 / 回退 / 并发压缩），必须拒绝提交，否则会把新内容一起抹掉。
     *
     * @param current 摘要返回后的**最新**消息列表。
     * @param selection 摘要开始前做出的选择。
     * @throws SurfaceChangedException 被选中范围已变化，本次压缩必须放弃。
     */
    fun assertSpanStable(current: List<ChatMessage>, selection: RetainedSelection) {
        val range = selection.shadowedRange
        if (range.isEmpty()) throw SurfaceChangedException("压缩范围为空，拒绝提交")
        if (current.size < range.last + 1) {
            throw SurfaceChangedException(
                "上下文在压缩期间缩短（当前 ${current.size} 条 < 范围末端 ${range.last}），拒绝提交"
            )
        }
        val nowShadowed = current.subList(range.first, range.last + 1)
        if (nowShadowed != selection.shadowed) {
            throw SurfaceChangedException(
                "被选中的压缩范围（${range.first}..${range.last}）在摘要期间已变化，拒绝提交"
            )
        }
    }

    // ── 收缩验证（对齐 dsh summarizeCompaction 的 shrink 检查） ─────────────────

    /**
     * 校验摘要检查点**确实小于**被替换内容，否则压缩毫无意义（dsh 直接抛错拒提交）。
     *
     * @throws IllegalArgumentException 摘要未收缩（检查点 token >= 被替换 token）。
     */
    fun validateShrink(summary: String, shadowedTokens: Int) {
        val checkpoint = checkpointTokens(summary)
        if (checkpoint >= shadowedTokens) {
            throw IllegalArgumentException(
                "摘要未收缩：检查点 $checkpoint tokens >= 被替换内容 $shadowedTokens tokens，拒绝提交"
            )
        }
    }

    /**
     * 把待摘要消息渲染为纯文本（供摘要 LLM 读取；工具结果已先经过剪枝）。
     *
     * @param includeSystem 是否包含 system 消息。DeepSeek session rotation 的场景传 false：
     *   摘要前缀会与同一条消息里的 system + 工具清单重复，既浪费上下文又可能让模型
     *   把同一份指令当成两轮不同的要求。
     */
    fun renderForSummary(messages: List<ChatMessage>, includeSystem: Boolean = true): String = buildString {
        for (m in messages) {
            when {
                m.role == "system" -> if (includeSystem) append("[系统]\n").append(m.content).append("\n\n")
                m.role == "user" -> append("[用户]\n").append(m.content).append("\n\n")
                m.role == "assistant" && m.toolCalls != null -> {
                    append("[助手-工具调用]\n")
                    for (tc in m.toolCalls) {
                        append("  ").append(tc.function.name)
                            .append("(").append(tc.function.arguments.take(MAX_ARG_CHARS)).append(")\n")
                    }
                    m.reasoning?.takeIf { it.isNotBlank() }
                        ?.let { append("  [思考] ").append(it.take(MAX_REASONING_CHARS)).append("\n") }
                    append("\n")
                }
                m.role == "assistant" -> {
                    append("[助手]\n").append(m.content).append("\n\n")
                    m.reasoning?.takeIf { it.isNotBlank() }
                        ?.let { append("  [思考] ").append(it.take(MAX_REASONING_CHARS)).append("\n\n") }
                }
                m.role == "tool" -> append("[工具结果]\n").append(m.content).append("\n\n")
            }
        }
    }

    /**
     * 构造替换消息列表：system + 检查点 user 消息（<compacted-summary>）+ 确认 assistant + 保留尾部。
     * 替换（replace）而非追加，避免在上下文中留下第二份副本。
     */
    fun buildCheckpoint(
        system: ChatMessage?,
        summary: String,
        retained: List<ChatMessage>
    ): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(retained.size + 3)
        if (system != null) out.add(system)
        out.add(
            ChatMessage(
                role = "user",
                content = "$COMPACT_CHECKPOINT_PREAMBLE\n\n<compacted-summary>\n$summary\n</compacted-summary>"
            )
        )
        out.add(ChatMessage(role = "assistant", content = "已了解之前的对话背景，请继续。"))
        out.addAll(retained)
        return out
    }

    /**
     * DeepSeek 逆向协议：是否需要用**服务端上报的 prompt tokens** 触发 session rotation。
     *
     * 判定必须基于「本会话」最近一次上报值：服务端 prompt tokens 描述的是**该服务端会话**的
     * 上下文大小。若用全局（跨会话）的最近值，从一个大上下文会话切到新会话时，
     * 新会话的第一条消息就会误触发轮转——白白新建服务端会话、把锚点清空，
     * 还会把新会话的 prompt 前面塞一段无关摘要。
     *
     * @param lastPromptTokens 本会话最近一次 provider 上报的 promptTokens（0 = 尚无数据）
     * @param threshold 压缩阈值（token），<=0 表示未配置 → 不监控
     */
    fun shouldRotateDeepSeek(lastPromptTokens: Int, threshold: Int): Boolean =
        threshold > 0 && lastPromptTokens >= threshold

    /**
     * 轮转后的首条消息是否需要**重新注入** system + 工具清单。
     *
     * 轮转发生时服务端会话是全新的，它的第一条消息就是唯一的注入机会；而触发轮转的通常是
     * 「非首条消息」（上下文聊满才轮转），此时本地构造的 prompt 里并没有 system 段。
     * 不补注入的话，模型在这个服务端会话余下生命周期里都看不到协议指南与工具清单，
     * 工具调用会静默退化成普通文本。
     */
    fun rotationNeedsSystemReinject(isFirstMessage: Boolean, hasTools: Boolean): Boolean =
        !isFirstMessage && hasTools

    /** 检查点块（不含 system）的 token 估算，用于收缩验证。 */
    fun checkpointTokens(summary: String): Int =
        estimateTokens(
            listOf(
                ChatMessage(role = "user", content = "$COMPACT_CHECKPOINT_PREAMBLE\n\n<compacted-summary>\n$summary\n</compacted-summary>"),
                ChatMessage(role = "assistant", content = "已了解之前的对话背景，请继续。")
            )
        )

    /** 保留选择结果。 */
    data class RetainedSelection(
        /** 被替换（阴影化）的旧消息，交给摘要 LLM。 */
        val shadowed: List<ChatMessage>,
        /** 原样保留的最近消息尾部。 */
        val retained: List<ChatMessage>,
        /** 被替换范围的估算 token。 */
        val shadowedTokens: Int,
        /** 保留尾部的估算 token。 */
        val retainedTokens: Int,
        /**
         * 被替换范围在原列表中的下标区间（**两端闭区间**，对齐 dsh `shadowedRange`）。
         * 稳定性校验与提交阶段用它重新定位范围——摘要是异步的，提交时必须回到最新
         * 列表上按区间取，而不是沿用摘要前的旧切片。
         */
        val shadowedRange: IntRange = 0 until shadowed.size
    )

    companion object {
        const val DEFAULT_THRESHOLD_RATIO = 0.8
        const val DEFAULT_RETAIN_RATIO = 0.16
        const val MIN_RETAIN_MESSAGES = 8
        /**
         * 工具结果剪枝阈值（code point）。对齐 dsh compaction-tool-result-pruner 的
         * `DEFAULTS.thresholdChars`。原为 3000，对文件内容/构建日志这类输出过于激进，
         * 会过早剪掉仍有价值的上下文。
         */
        const val PRUNE_THRESHOLD_CHARS = 8_192
        /** 剪枝保留头部（code point），对齐 dsh `DEFAULTS.headChars`。 */
        const val PRUNE_HEAD_CHARS = 4_096
        /** 剪枝保留尾部（code point），对齐 dsh `DEFAULTS.tailChars`。 */
        const val PRUNE_TAIL_CHARS = 1_024
        /** 未知模型的默认上下文窗口（token）。取 128k 的保守下限：现代主流模型（gpt-4o/claude/deepseek/本地量化）都 ≥ 64k，
         * 32k 会让长思考 + 大量工具定义的会话过早触发压缩；本地小模型由已知模型表或溢出恢复兜底。 */
        const val DEFAULT_CONTEXT_WINDOW = 131_072
        /**
         * 摘要生成预算：**直接取设置页当前选中模型配置的最大输出 token**，不做任何保底抬升。
         *
         * 此前固定 4096：长会话的 8 段检查点必然写不完（finish_reason=length → 视为失败），
         * 压缩永久失败、只能走降级截断。改为跟随用户配置后，压缩预算与实际生成请求同源。
         *
         * @return 配置值；仅在配置值为 0（未设置 / 表示不限制，见 profile 默认值链）时，
         *   回退到 [LLMConfig] 一侧的默认生成上限。
         */
        fun summaryMaxTokens(configuredMaxTokens: Int): Int =
            if (configuredMaxTokens > 0) configuredMaxTokens else DEFAULT_SUMMARY_MAX_TOKENS

        /** 摘要生成预算的回退值：仅当配置值为 0 时使用。 */
        const val DEFAULT_SUMMARY_MAX_TOKENS = 4096

        /**
         * 收敛再压轮数（对齐 dsh `BasicCompactionConfig.compactionRetries`）：
         * 替换后仍超阈值时，允许对替换后的表面再压几轮（旧检查点会被合并进新摘要）。
         * 默认 1 轮；调大可处理极端长会话，但每轮都要多一次 LLM 摘要调用。
         */
        const val DEFAULT_COMPACTION_RETRIES = 1

        /**
         * 校准系数的取值区间。
         * 下界 0.5：启发式最多高估一倍（再低通常是 provider 少报，不采信）；
         * 上界 3.0：中文/代码场景真实值确实可达启发式的 2~3 倍，再高一般说明
         * usage 里混入了缓存/计费的特殊口径，不应让它把阈值整体放大到失真的量级。
         */
        const val MIN_TOKEN_SCALE = 0.5
        const val MAX_TOKEN_SCALE = 3.0
        /** 校准的指数滑动平均系数。 */
        const val EMA_ALPHA = 0.3

        private const val MAX_ARG_CHARS = 500
        private const val MAX_REASONING_CHARS = 500

        /** 检查点前导（与 deepseek-harness 的 checkpoint preamble 语义一致）。 */
        const val COMPACT_CHECKPOINT_PREAMBLE =
            "以下是自动生成的对话摘要检查点，用于释放上下文空间。" +
                "请将其视为已建立的背景，直接从后续消息继续工作，无需复述此检查点。"

        /** 摘要指令（结构化 8 段 Markdown 检查点；已有 <compacted-summary> 时合并而非复制）。 */
        val COMPACT_INSTRUCTION = """
请将上面的对话压缩为结构化检查点，输出以下 Markdown 结构，空章节写"（无）"：

## 核心请求与目标
## 关键技术概念
## 文件与代码
## 错误与修复
## 待完成工作
## 当前进度
## 下一步
## 关键上下文

规则：保留精确的文件路径、命令、错误字符串、标识符；如已有 <compacted-summary> 块，合并而非直接复制；仅输出检查点文本。
        """.trimIndent()

        /**
         * 模型 → 上下文窗口解析。精确规则在前（gpt-4o 必须先于 gpt-4 匹配），
         * 未知模型回退 [DEFAULT_CONTEXT_WINDOW]。对齐 deepseek-harness 从适配器
         * 解析路由模型容量的语义（本项目无适配器容量上报，用表 + 回退）。
         */
        private val CONTEXT_WINDOW_RULES: List<Pair<Regex, Int>> = listOf(
            Regex("gpt-4o|gpt-4.1|gpt-4-turbo") to 128_000,
            Regex("gpt-4-32k") to 32_768,
            Regex("gpt-4") to 8_192,
            Regex("gpt-3.5") to 16_384,
            Regex("deepseek-(chat|reasoner|v3|r1)") to 65_536,
            Regex("claude-(3|opus|sonnet|haiku)") to 200_000,
            Regex("gemini") to 1_000_000,
            Regex("qwen|qwq|kimi|moonshot") to 131_072,
            Regex("nemotron-3-ultra") to 1_048_576,
            Regex("nemotron") to 131_072,
            Regex("llama|mistral|mixtral|phi|gemma|glm") to 32_768
        )

        /** 模型上下文窗口（token），未知模型回退默认值。 */
        fun contextWindowFor(model: String?): Int {
            if (model.isNullOrBlank()) return DEFAULT_CONTEXT_WINDOW
            val m = model.lowercase()
            for ((rule, window) in CONTEXT_WINDOW_RULES) {
                if (rule.containsMatchIn(m)) return window
            }
            return DEFAULT_CONTEXT_WINDOW
        }

        /** 上下文窗口溢出错误模式（对齐 dsh-llm isContextWindowExceededError）。 */
        private val CONTEXT_OVERFLOW_PATTERNS = listOf(
            "context_length_exceeded",
            "context-window-overflowed",
            "context window exceeded",
            "maximum context length",
            "context length exceeded",
            "exceeds the model context",
            "exceeds maximum context",
            "input is too long",
            "too long for this model",
            "request too large for model context",
            "input exceeds the model context window limit",
            // SenseNova 网关对超大请求（输入过长 / messages 条数超限）的通用拒收文案，
            // 不带 context / length 字样，此前会被当成普通错误直接抛给用户；
            // 命中后走同一套溢出恢复（激进保留最近几条 + 重试一次），恢复失败再原样报错。
            "inference request is invalid",
            "request is invalid"
        )

        /** 判断一条错误消息是否属于"上下文窗口溢出"（供溢出恢复路径使用）。 */
        fun isContextWindowExceededError(detail: String): Boolean {
            if (detail.isBlank()) return false
            val d = detail.lowercase()
            return CONTEXT_OVERFLOW_PATTERNS.any { d.contains(it) }
        }
    }
}

/**
 * 被选中的压缩范围在异步摘要期间发生了变化（对齐 dsh `SurfaceChangedError`）。
 *
 * 独立成类而非复用 [IllegalArgumentException]：调用方需要把「上下文变了，放弃本轮」
 * 与「摘要失败」「摘要没变小」区分开——前者是正常竞态，应静默跳过稍后重试；
 * 后两者是需要上报或降级的真实故障。
 */
class SurfaceChangedException(message: String) : Exception(message)
