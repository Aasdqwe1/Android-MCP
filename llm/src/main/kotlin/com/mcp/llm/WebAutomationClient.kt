package com.mcp.llm

import com.mcp.core.llm.BackendType
import com.mcp.deepseek.AuthPrefs
import com.mcp.llm.MessageEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Web 自动化后端适配器：把「网页版 AI 对话框」包装为 [LLMClient]。
 *
 * 语义要点：
 *  - **无服务端会话 id**：历史由上层（ChatBridge）在本地维护，每次把完整历史拼进 prompt；
 *  - **无流式**：驱动是「发一次、等完成、返回全文」，这里只在结束时发一次 [MessageEvent.Done]
 *    （思考 + 正文一次收数，不做增量推送——DOM 整段重渲染时增量模型会丢字）；
 *  - **无 tools**：[LLMRequest.tools] 会被忽略（网页版不支持 function calling）。
 */
class WebAutomationClient(
    private val auth: AuthPrefs,
    private val driver: WebAutomationDriver,
) : LLMClient {

    override val backendType: BackendType = BackendType.WEB_AUTOMATION

    override suspend fun isAuthenticated(): Boolean = driver.isReady()

    override suspend fun sendMessage(request: LLMRequest): Flow<MessageEvent> = flow {
        val config = auth.getWebAutomationConfig()
        // 用 isUsable 而不是只查 siteUrl：出厂/新建配置的选择器是空的，
        // 只查 siteUrl 会让流程先导航到内置起始页、再用空选择器 querySelector("") 失败，
        // 最后报出「未找到输入框（选择器：）」这种看不懂的错误。
        if (!config.isUsable) {
            val missing = buildList {
                if (config.siteUrl.isBlank()) add("站点地址")
                if (config.inputSelector.isBlank()) add("输入框选择器")
                if (config.contentSelector.isBlank()) add("正文选择器")
            }
            emit(MessageEvent.Error(IllegalStateException(
                "Web 自动化后端配置不完整，缺少：" + missing.joinToString("、") +
                    "。请到「设置 → LLM 后端 → Web 自动化」填写（输入框与正文是必填项）。"
            )))
            return@flow
        }
        val prompt = WebAutomationPrompt.build(request.messages)
        val reply = try {
            // 非流式：不传 onEvent。驱动只负责「发出去、等结束、返回思考+正文全文」，
            // 中间态一律不推给前端（模拟 SSE 的增量模型在站点整段重渲染时会丢字）。
            driver.ask(config.siteUrl, prompt, config)
        } catch (e: Exception) {
            emit(MessageEvent.Error(e))
            return@flow
        }
        // 正文生成完毕后解析行式协议 tool_call（与 DeepSeek 后端**同一套解析器**，只解析正文、
        // 思考区不执行——口径一致）：ToolCall 事件交 ChatBridge 执行，结果经 continueWebAutomation
        // 以 tool_result 行式块回传站点。Done 带**最终全文**（已剥离调用段），前端据此一次上屏
        // （非流式：全程只有这一次正文/思考下发）。
        val parsed = com.mcp.toolbox.parseToolCallFromText(reply.content)
            .distinctBy { it.first to (it.second to it.third) }
        var content = reply.content
        for ((id, name, args) in parsed) {
            emit(MessageEvent.ToolCall(id, name, args))
        }
        if (parsed.isNotEmpty()) {
            // 剥离已提取的调用段：两套协议都要剥（XML 优先，再行式）。
            // 此前只剥行式，极简模式用 XML 时调用段会残留在正文里，
            // 前端表现为「一坨 DSML 标签」——看起来像工具没被处理。
            content = com.mcp.toolbox.stripLineProtocolCalls(
                com.mcp.toolbox.stripXmlProtocolCalls(reply.content)
            )
        }
        emit(MessageEvent.Done(thinking = reply.thinking, content = content))
    }

    override suspend fun stopStream(token: String, sessionId: String, messageId: String) {
        driver.stop()
    }
}

/**
 * Web 自动化后端的 prompt 拼接策略。
 *
 * 网页版输入框只接受一段文本，没有 system/user/assistant 的 role 概念，
 * 因此把消息列表按角色前缀拍平成纯文本。
 *
 * TODO（后续按需调整）：
 *  - 长历史可能超出输入框长度上限，需要截断策略（保留最近 N 轮 / 按字符预算）；
 *  - 是否保留 system 段、是否带 assistant 前缀，可做成配置项。
 */
object WebAutomationPrompt {

    /**
     * @param renderToolCalls 是否把 assistant 的 tool_calls 与 tool 结果的 call_id 一并渲染。
     *
     *   OpenAI 兼容端点（`/v1`）的全量转录路径**必须为 true**：助手那一轮常常只有 tool_calls、content 为空，
     *   旧实现下会被 `if (text.isBlank()) return@forEach` 整行跳过，模型只看到悬空的
     *   `[工具] 结果`，既丢了「我调用过什么」，也**拿不到正确格式的在场示例**。
     *   真机实测的后果：模型自己发明格式（退化成 JSON 裸参数块），解析器认不出 →
     *   调用被静默丢弃 → 模型下一轮抱怨「我的工具调用没有真正发出」并继续重试。
     *
     *   Web 自动化通道保持默认 false，不改它的投喂字节（那条通道有长度上限 TODO）。
     */
    fun build(messages: List<ChatMessage>, renderToolCalls: Boolean = false): String {
        if (messages.size == 1) return messages.first().content.orEmpty()
        val sb = StringBuilder()
        messages.forEach { m ->
            val text = m.content.orEmpty()
            val calls = if (renderToolCalls) m.toolCalls.orEmpty() else emptyList()
            if (text.isBlank() && calls.isEmpty()) return@forEach
            when (m.role) {
                "system" -> sb.append("[系统] ").append(text).append("\n")
                "user" -> sb.append("[用户] ").append(text).append("\n")
                "assistant" -> {
                    sb.append("[助手] ").append(text).append("\n")
                    calls.forEach { sb.append(renderToolCallAsLineProtocol(it)) }
                }
                "tool" -> {
                    sb.append("[工具]")
                    if (renderToolCalls && !m.toolCallId.isNullOrBlank()) {
                        sb.append("(调用 id=").append(m.toolCallId).append(")")
                    }
                    sb.append(" ").append(text).append("\n")
                }
                else -> sb.append(text).append("\n")
            }
        }
        return sb.toString().trim()
    }

    /**
     * 把一条 tool_call 渲染成**行式协议原文** —— 即模型本就该使用的格式。
     *
     * 刻意**不**渲染成 OpenAI 的 JSON 形态：那等于在上下文里持续示范一个上游解析器
     * 不认识的格式。模型会照抄在场示例，于是「历史里的 JSON」会自我强化成
     * 「新的一轮又是 JSON」（真机实测连续三轮，越滚越偏）。**别改成 JSON。**
     */
    private fun renderToolCallAsLineProtocol(tc: ToolCall): String {
        val sb = StringBuilder()
        sb.append("tool_call: ").append(tc.function.name).append("\n")
        if (tc.id.isNotBlank()) sb.append("id: ").append(tc.id).append("\n")
        // 参数按行式协议拆成「名: 值」；多行值用 <<< >>> 定界块（与协议解析器同一套约定）
        val args = runCatching { org.json.JSONObject(tc.function.arguments) }.getOrNull()
        if (args == null) {
            sb.append(tc.function.arguments).append("\n")
        } else {
            args.keys().forEach { k ->
                val v = args.opt(k)?.toString().orEmpty()
                if (v.contains('\n')) {
                    sb.append(k).append(" <<<\n").append(v).append("\n>>>\n")
                } else {
                    sb.append(k).append(": ").append(v).append("\n")
                }
            }
        }
        return sb.toString()
    }
}
