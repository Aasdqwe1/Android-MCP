package com.mcp.litert
    
    import android.os.Build
import android.util.Log
    import com.google.ai.edge.litertlm.Backend
    import com.google.ai.edge.litertlm.Contents
    import com.google.ai.edge.litertlm.Conversation
    import com.google.ai.edge.litertlm.ConversationConfig
    import com.google.ai.edge.litertlm.Engine
    import com.google.ai.edge.litertlm.EngineConfig
    import com.google.ai.edge.litertlm.Message
    import com.google.ai.edge.litertlm.SamplerConfig
    import com.mcp.llm.ChatMessage
    import com.mcp.llm.LLMConfig
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    
    /**
     * LiteRT 引擎与会话的生命周期管理器。
     *
     * 设计要点（对应设计文档 §3.3 / §5.2）：
     *  - **Engine 单例**：按 [LiteRtConfig.engineKey]（模型路径 + 后端）缓存；键变化时销毁重建。
     *    initialize() 可能耗时 10s，必须在 IO 线程调用。
     *  - **Conversation 按会话缓存**：Conversation 持有 KV cache，必须与上层"会话"一一对应。
     *  - **历史重复规避**：上层每次传完整 messages（无状态接口），而 Conversation 自身累积历史。
     *    因此记录每个 session 上次灌入的消息数，条数变化就重建 Conversation 并重灌历史。
     *
     * 线程安全：所有公开方法都在 synchronized 块内操作 engine/conversations 映射；
     * initialize 这类耗时调用放在锁外，用 volatile 状态位做双重检查。
     */
    object LiteRtEngineHolder {
    
        private const val TAG = "LITERT"
    
        @Volatile private var engine: Engine? = null
        @Volatile private var currentKey: String? = null
    
        private val conversations = LinkedHashMap<String, Conversation>()
        /** 每个 session 上次灌入 Conversation 的历史消息数（不含本轮输入）。 */
        private val sessionHistorySize = LinkedHashMap<String, Int>()
    /** 每个 session 上次灌入的工具集签名（名称列表）；变化即重建 Conversation。 */
    private val sessionToolsKey = LinkedHashMap<String, String>()
    
        /** 当前是否有已初始化的引擎。 */
        fun isReady(): Boolean = engine != null
    
        /** 当前加载的模型路径（诊断用）。 */
        fun currentModelPath(): String? = currentKey?.substringBefore('|')
    
        /**
         * 取（或初始化）引擎。配置未变化时直接复用；变化时销毁旧引擎、清空所有会话。
         * 必须在 IO 上下文调用——initialize() 会阻塞数秒。
         */
        suspend fun engine(cfg: LiteRtConfig): Engine = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < 26) {
                throw LiteRtException("本地模型需要 Android 8.0（API 26）及以上")
            }
            if (!cfg.isModelAvailable()) {
                throw LiteRtException("模型文件不存在或不可读：${cfg.modelPath}")
            }
            val key = cfg.engineKey()
            engine?.takeIf { currentKey == key }?.let { return@withContext it }
    
            // 配置变了：先销毁旧的，再建新的
            synchronized(this@LiteRtEngineHolder) {
                closeAllLocked()
            }
    
            val e = try {
                Engine(buildEngineConfig(cfg)).also { it.initialize() }
            } catch (t: Throwable) {
                throw LiteRtException("LiteRT 引擎初始化失败：${t.message}", t)
            }
    
            synchronized(this@LiteRtEngineHolder) {
                engine = e
                currentKey = key
                conversations.clear()
                sessionHistorySize.clear()
            }
            Log.i(TAG, "引擎就绪：${cfg.modelPath} / ${cfg.backend}")
            e
        }
    
        private fun buildEngineConfig(cfg: LiteRtConfig): EngineConfig {
            val backend = when (cfg.backend) {
                LiteRtBackend.CPU -> Backend.CPU()
                LiteRtBackend.GPU -> Backend.GPU()
                LiteRtBackend.NPU -> {
                    val dir = cfg.nativeLibraryDir
                        ?: throw LiteRtException("NPU 后端需要 nativeLibraryDir（context.applicationInfo.nativeLibraryDir）")
                    Backend.NPU(nativeLibraryDir = dir)
                }
            }
            return EngineConfig(
                modelPath = cfg.modelPath,
                backend = backend,
                cacheDir = cfg.cacheDir,
                maxNumTokens = cfg.maxNumTokens,
            )
        }
    
        /**
         * 取（或重建）指定会话的 Conversation。
         *
         * @param messages 上层传来的完整历史（最后一条是本轮用户输入，不灌入）
         * @return 可直接 sendMessageAsync 的 Conversation
         */
        fun conversationFor(
            sessionId: String,
            engine: Engine,
            messages: List<ChatMessage>,
            cfg: LLMConfig,
            liteCfg: LiteRtConfig,
            tools: List<com.mcp.toolbox.ToolDef>? = null,
        ): Conversation {
            val history = messages.dropLast(1)
            val lastSize = sessionHistorySize[sessionId]
            val cached = conversations[sessionId]
    
            // 命中条件：已有会话，且历史条数未变（说明是纯追加的新一轮，Conversation 内部已累积好）
            // 命中条件：已有会话 且 历史条数未变 且 工具集未变
                // （工具集变化必须重建 Conversation，否则模型看到的工具清单是旧的）
                val toolsKey = tools?.map { it.name }?.joinToString(",") ?: ""
                if (cached != null && lastSize == history.size && sessionToolsKey[sessionId] == toolsKey) return cached
    
            // 否则重建：关闭旧会话，用历史重新灌入
            synchronized(this) {
                conversations.remove(sessionId)?.close()
    
                val system = history.firstOrNull { it.role == "system" }?.content
                val dialog = history.filter { it.role != "system" }.mapNotNull { it.toLiteRtMessage() }
    
                // 诊断：确认工具真的注入（toolsIn 应为 >0，否则模型看不到工具）
                Log.i(TAG, "LITERT-DIAG conv session=" + sessionId +
                    " toolsIn=" + (tools?.size ?: 0) +
                    " history=" + dialog.size +
                    " systemLen=" + (system?.length ?: 0))
                val conv = engine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system ?: ""),
                        initialMessages = dialog,
                        samplerConfig = SamplerConfig(
                            temperature = liteCfg.temperature,
                            topK = liteCfg.topK,
                            topP = liteCfg.topP,
                        ),
                            tools = if (tools.isNullOrEmpty() || liteCfg.toolbox == null) emptyList()
                                else toLiteRtTools(tools, liteCfg.toolbox),
                            // 关键：关闭自动执行。模型下发的 toolCalls 留在返回的 Message 里，
                            // 由 LiteRtClient 翻译成 MessageEvent.ToolCall 交回 ChatBridge，
                            // 复用与 OpenAI 后端一致的「卡片显示 + 安全防护 + 续聊」闭环。
                            automaticToolCalling = false,
                    )
                )
                conversations[sessionId] = conv
                sessionHistorySize[sessionId] = history.size
            sessionToolsKey[sessionId] = toolsKey
                Log.i(TAG, "会话 $sessionId 重建：灌入 ${dialog.size} 条历史")
                return conv
            }
        }
    
        /** 释放指定会话（会话删除 / 清空时调用）。 */
        fun releaseSession(sessionId: String) {
            synchronized(this) {
                conversations.remove(sessionId)?.close()
                sessionHistorySize.remove(sessionId)
            sessionToolsKey.remove(sessionId)
            }
        }
    
        /** 释放全部会话（切换模型 / 退出时调用）。 */
        fun closeAll() {
            synchronized(this) { closeAllLocked() }
        }
    
        /** 调用方须持有本对象锁。 */
        private fun closeAllLocked() {
            conversations.values.forEach { runCatching { it.close() } }
            conversations.clear()
            sessionHistorySize.clear()
            sessionToolsKey.clear()
            runCatching { engine?.close() }
            engine = null
            currentKey = null
        }
    }
    
    /**
     * ChatMessage -> LiteRT Message 映射。
     * system 由 ConversationConfig 单独承载，这里只处理 user / assistant / tool。
     */
    private fun ChatMessage.toLiteRtMessage(): Message? {
        val text = content ?: return null
        return when (role) {
            "user" -> Message.user(text)
            "assistant" -> Message.model(text)
            else -> null   // tool 消息暂不映射（P5 工具调用阶段处理）
        }
    }
    