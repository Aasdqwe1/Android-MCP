package com.mcp.wechat

import android.content.Context
import com.mcp.LogStore
import com.mcp.core.chat.ChatSession
import com.mcp.core.llm.BackendType
import com.mcp.core.prompt.SystemPromptComposer
import com.mcp.core.prompt.modelFacingTools
import com.mcp.core.skill.SkillRepository
import com.mcp.deepseek.AuthPrefs
import com.mcp.chat.MessageIdSpace
import com.mcp.data.LocalStore
import com.mcp.llm.MessageEvent
import com.mcp.llm.ChatMessage
import com.mcp.llm.LLMClientFactory
import com.mcp.llm.LLMConfig
import com.mcp.llm.LLMRequest
import com.mcp.llm.ToolCall
import com.mcp.llm.ToolCallFunction
import com.mcp.toolbox.ToolMessages
import com.mcp.toolbox.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 原生 iLink 微信消息的 LLM 适配层。
 *
 * 这里不把两个后端的网络协议揉成一种：
 * - DeepSeek 继续走服务端 session + parent_message_id + 行式工具协议；
 * - OpenAI 继续走 Chat Completions + 本地 messages + 原生 function calling。
 * 微信循环只负责收发消息，具体会话和工具续聊由本类按后端分支处理。
 */
class WeixinLlmResponder(
    context: Context,
    private val auth: AuthPrefs,
    private val toolbox: Toolbox
) {
    private val appContext = context.applicationContext
    private val states = ConcurrentHashMap<String, ConversationState>()
    private val promptComposer = SystemPromptComposer(
        appContext,
        SkillRepository(appContext).also { it.discover() }
    ).apply {
        // 远程 MCP 服务清单注入（与 ChatBridge 同一策略）
        mcpContextProvider = {
            runCatching {
                com.mcp.composition.ToolRuntimeHolder.get(appContext, auth).remoteRegistry.describeForPrompt()
            }.getOrDefault("")
        }
    }

    /** 会话引导：用户正常聊天前必须先选择会话（聊天内数字菜单，复用会话页列表）。 */
    private val guide = WeixinConversationGuide(appContext)

    /** 本地落盘串行锁：多会话并发回写会话列表/消息文件时避免写坏 JSON。 */
    private val saveMutex = Mutex()

    private class ConversationState(val backend: BackendType) {
        val mutex = Mutex()

        var deepSeekSessionId: String? = null
        var deepSeekParentMessageId: String? = null
        var deepSeekPromptInjected: Boolean = false

        val openAiMessages = ArrayList<ChatMessage>()
    }

    suspend fun reply(accountId: String, fromUserId: String, rawText: String): String {
        val backend = auth.getBackend()
        // 会话引导拦截：未选定会话时返回菜单/确认；选定后放行到对应会话线程。
        val outcome = when (val o = guide.intercept(accountId, fromUserId, rawText, backend)) {
            is WeixinConversationGuide.Outcome.Reply -> return o.text
            is WeixinConversationGuide.Outcome.Chat -> o
        }
        // 选定会话后，状态按 (account, fromUser, sessionId) 隔离，切换会话即切换线程。
        // outcome.sessionId：新会话时为本地生成的 wx_ uuid；已有会话时为该会话真实的 DeepSeek 会话 id。
        // outcome.isNew：区分「开新会话」与「续已有会话」，后者需要直接续上其远端上下文。
        val stateKey = "$accountId\u0000$fromUserId\u0000${outcome.sessionId}"
        val state = states.compute(stateKey) { _, old ->
            if (old?.backend == backend) old else ConversationState(backend)
        } ?: ConversationState(backend)

        return state.mutex.withLock {
            when (backend) {
                BackendType.OPENAI -> replyWithOpenAI(state, rawText, outcome.sessionId)
                // 微信渠道依赖后台常驻回复，而 Web 自动化必须由 WebView 前台驱动，
                // 二者不兼容：明确拒绝而不是静默失败。
                BackendType.WEB_AUTOMATION ->
                    "微信渠道暂不支持 Web 自动化后端（需前台 WebView 驱动）。请改用 OpenAI 或 DeepSeek 后端。"
                    // LiteRT 本地模型：推理期间占用大量内存与算力，与微信渠道常驻后台的需求冲突；
                    // 且模型加载需用户先交互选文件，暂不适配渠道。明确拒绝而非静默失败。
                    // MNN 本地模型：与 LiteRT 同样的理由（推理占用大、与微信常驻后台冲突），明确拒绝。
                    BackendType.MNN ->
                        "微信渠道暂不支持 MNN 本地模型后端（推理占用大，与常驻后台冲突）。请改用 OpenAI 或 DeepSeek 后端。"
                    BackendType.LITERT ->
                        "微信渠道暂不支持本地模型后端（推理资源占用高）。请改用 OpenAI 或 DeepSeek 后端。"
            }
        }
    }


    private suspend fun replyWithOpenAI(
        state: ConversationState,
        rawText: String,
        sessionId: String
    ): String {
        // 首次进入该会话：从本地缓存恢复历史上下文（OpenAI 无状态，必须显式带历史，
        // 否则切换会话后模型看不到任何前文，表现为「没有加载上下文」）。
        if (state.openAiMessages.isEmpty()) {
            seedOpenAiContext(state, sessionId)
        }
        state.openAiMessages += ChatMessage(role = "user", content = rawText)

        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            val replyBuilder = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()
            var streamError: Throwable? = null
            val request = LLMRequest(
                messages = state.openAiMessages.toList(),
                tools = modelFacingTools(toolbox.all(), com.mcp.preset.PresetRuntime.current?.ptc == true).takeIf { it.isNotEmpty() },
                config = LLMConfig(
                    model = auth.getOpenAIModel(),
                    temperature = auth.getOpenAITemperature(),
                    maxTokens = auth.getOpenAIMaxTokens(),
                    stream = true,
                    thinkingEnabled = false
                )
            )

            try {
                LLMClientFactory.create(auth).sendMessage(request).collect { event ->
                    when (event) {
                        is MessageEvent.Content -> replyBuilder.append(event.delta)
                        is MessageEvent.Done -> if (replyBuilder.isEmpty()) replyBuilder.append(event.content)
                        is MessageEvent.ToolCall -> toolCalls += ToolCall(
                            id = event.id.ifBlank { "wx_${UUID.randomUUID()}" },
                            type = "function",
                            function = ToolCallFunction(event.name, event.arguments)
                        )
                        is MessageEvent.Error -> streamError = event.throwable
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                streamError = t
            }

            if (streamError != null) {
                return "OpenAI 调用失败：${streamError?.message?.take(160) ?: streamError!!::class.simpleName}"
            }

            if (toolCalls.isEmpty()) {
                val answer = replyBuilder.toString().trim()
                if (answer.isNotEmpty()) {
                    state.openAiMessages += ChatMessage(role = "assistant", content = answer)
                    persistExchange(BackendType.OPENAI, sessionId, rawText, answer)
                    return answer
                }
                return "（OpenAI 未返回回复）"
            }

            state.openAiMessages += ChatMessage(
                role = "assistant",
                toolCalls = toolCalls
            )
            for (call in toolCalls) {
                val rawResult = dispatchTool(call.function.name, call.function.arguments)
                LogStore.i("Weixin", "原生微信 OpenAI 工具调用: ${call.function.name} -> ${rawResult.take(120)}")
                state.openAiMessages += ChatMessage(
                    role = "tool",
                    content = rawResult,
                    toolCallId = call.id
                )
            }
        }
        return "（工具调用超过 ${MAX_TOOL_ROUNDS} 轮限制，已终止）"
    }

    /**
     * 从本地缓存恢复该会话的历史上下文，还原成 OpenAI 无状态消息缓冲。
     * 与 App 会话页 ChatBridge.seedOpenAIMessages 保持同构：system 置顶，
     * 工具调用消息还原为 assistant(tool_calls) + tool 两段（保证 tool 消息有合法前驱）。
     */
    private fun seedOpenAiContext(state: ConversationState, sessionId: String) {
        val system = promptComposer.composeOpenAiSystemContent()
        if (system.isNotBlank()) {
            state.openAiMessages += ChatMessage(role = "system", content = system)
        }
        val history = LocalStore.loadMessages(appContext, LocalStore.backendTag(BackendType.OPENAI), sessionId)
        for (h in history) {
            val tc = h.toolCall
            if (tc != null) {
                state.openAiMessages += ChatMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        ToolCall(
                            id = tc.id,
                            function = ToolCallFunction(tc.name, tc.arguments)
                        )
                    )
                )
                state.openAiMessages += ChatMessage(
                    role = "tool",
                    content = ToolMessages.extractContent(tc.result),
                    toolCallId = tc.id
                )
            } else if (h.content.isNotBlank()) {
                state.openAiMessages += ChatMessage(
                    role = if (h.isUser) "user" else "assistant",
                    content = h.content
                )
            }
        }
        LogStore.i("Weixin", "OpenAI 会话 $sessionId 恢复本地历史 ${history.size} 条，上下文共 ${state.openAiMessages.size} 条")
    }

    /**
     * 把一轮微信问答「桥接」回 App 会话列表的本地缓存：登记/刷新会话，并追加用户+回复两条气泡。
     * 这是让微信聊天窗口与会话页共享同一份历史的关键——否则微信消息只回推到微信，
     * 会话页（LocalStore 文件）里看不到任何变化，表现为「没写入已有的会话文件」。
     */
    private suspend fun persistExchange(
        backend: BackendType,
        sessionId: String,
        userText: String,
        replyText: String,
        anchorMessageId: String? = null
    ) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            val tag = LocalStore.backendTag(backend)
            val now = System.currentTimeMillis() / 1000.0
            // 会话列表：已有会话刷新标题/时间/锚点，新会话登记一条
            val sessions = LocalStore.loadSessions(appContext, tag).toMutableList()
            val idx = sessions.indexOfFirst { it.id == sessionId }
            if (idx >= 0) {
                val cur = sessions[idx]
                val title = if (cur.title.isBlank()) suggestTitle(userText) else cur.title
                sessions[idx] = cur.copy(
                    title = title,
                    updatedAt = now,
                    currentMessageId = anchorMessageId ?: cur.currentMessageId
                )
            } else {
                sessions.add(
                    ChatSession(
                        id = sessionId,
                        title = suggestTitle(userText),
                        updatedAt = now,
                        currentMessageId = anchorMessageId
                    )
                )
            }
            LocalStore.saveSessions(appContext, tag, sessions)
            // 消息气泡：把用户提问与回复作为事件追加到会话日志（append-only）
            LocalStore.appendMessage(appContext, tag, sessionId, com.mcp.core.chat.ChatMessage(isUser = true, content = userText))
            LocalStore.appendMessage(appContext, tag, sessionId, com.mcp.core.chat.ChatMessage(isUser = false, content = replyText))
        }
    }

    private fun suggestTitle(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= 20) t else t.take(20) + "…"
    }

    private suspend fun dispatchTool(name: String, arguments: String): String {
        // PTC 折叠：微信链路与 App 主链路同一门控
        if (!com.mcp.core.prompt.modelDirectToolAllowed(name, com.mcp.preset.PresetRuntime.current?.ptc == true)) {
            return com.mcp.toolbox.errorResult(com.mcp.core.prompt.ptcDirectCallRejection(name))
        }
        return runCatching {
            val args = com.mcp.toolbox.parseToolArguments(arguments)
            toolbox.dispatch(name, args)
        }.getOrElse { t ->
            com.mcp.toolbox.errorResult("工具执行异常：${t.message?.take(160) ?: t::class.simpleName}")
        }
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 15
    }
}