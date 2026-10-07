package com.mcp.litert
    
    import android.os.Build
    import com.google.ai.edge.litertlm.Content
    import com.google.ai.edge.litertlm.Message
    import com.mcp.core.llm.BackendType
    import com.mcp.llm.MessageEvent
    import com.mcp.llm.LLMClient
    import com.mcp.llm.LLMRequest
    import kotlinx.coroutines.CancellationException
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.channelFlow
    import java.util.concurrent.atomic.AtomicInteger
    
    /**
     * LiteRT 本地模型后端：[LLMClient] 的第四实现（前三：DeepSeek 逆向 / OpenAI 兼容 / Web 自动化）。
     *
     * 协议特征（与另三个后端对比）：
     *  - 无服务端会话态：历史由上层维护，本地 Conversation 仅作 KV cache 加速；
     *  - 无服务端 stop 端点：停止生成 = 取消 collect 协程；
     *  - **支持工具调用**：走 LiteRT 原生 function calling（automaticToolCalling=false），
     *    把模型下发的工具调用翻译为 [MessageEvent.ToolCall] 交回上层——
     *    与 OpenAI 分支共用 ChatBridge 的整套闭环（卡片显示、续聊、失败防护）。
     *
     * 工具调用的关键差异：LiteRT 的 ToolCall **没有 id 字段**（只有 name + arguments），
     * 而 ChatBridge 的闭环依赖 id（pendingToolMeta / consumedToolCallIds / tool_call_id）。
     * 故这里为每次调用合成唯一 id（"lite_" + 自增序号），保证同一轮内不冲突即可。
     */
    class LiteRtClient(
        private val holder: LiteRtEngineHolder = LiteRtEngineHolder,
        private val config: LiteRtConfig,
    ) : LLMClient {
    
        override val backendType: BackendType = BackendType.LITERT

    companion object {
        /**
         * 全局单调递增的 tool_call 序号（companion 级，跨 client 实例共享）。
         *
         * 必须是全局：ChatBridge.client() 每次都经工厂新建 LiteRtClient，
         * 若序号是实例字段，每轮都从 1 重来，会与上一轮的 lite_1 撞车——
         * ChatBridge 的 consumedToolCallIds 跨轮去重台账会据此判定「已消费」，
         * 直接跳过工具执行，表现为「工具执行了但没续聊」。
         */
        private val GLOBAL_CALL_SEQ = AtomicInteger(0)
        /** 进程级随机前缀：即使进程重启后计数器归零，id 也不会与上次会话撞车。 */
        private val PROCESS_TAG = java.util.UUID.randomUUID().toString().take(6)
    }
    
    
        override suspend fun sendMessage(request: LLMRequest): Flow<MessageEvent> = channelFlow {
            // API < 26 保护：LiteRT 原生库要求 Android 8.0+，低版本直接给可读错误，
            // 避免触碰 native 层导致 NoClassDefFoundError / UnsatisfiedLinkError。
            if (Build.VERSION.SDK_INT < 26) {
                send(MessageEvent.Error(LiteRtException(
                    "本地模型需要 Android 8.0（API 26）及以上，当前设备为 API ${Build.VERSION.SDK_INT}"
                )))
                return@channelFlow
            }
            val userText = request.messages.lastOrNull { it.role == "user" }?.content
            if (userText.isNullOrBlank()) {
                send(MessageEvent.Error(LiteRtException("本轮没有用户输入")))
                return@channelFlow
            }
    
            val engine = try {
                holder.engine(config)
            } catch (t: Throwable) {
                send(MessageEvent.Error(t))
                return@channelFlow
            }
    
            val sessionId = request.config.sessionId ?: "default"
            val conv = try {
                holder.conversationFor(sessionId, engine, request.messages, request.config, config, request.tools)
            } catch (t: Throwable) {
                send(MessageEvent.Error(LiteRtException("会话创建失败：${t.message}", t)))
                return@channelFlow
            }
    
            val contentBuf = StringBuilder()
            // 流式收文本的同时，记下最近一条「带 toolCalls」的 Message：
            // LiteRT 的流式分片里，工具调用只出现在承载它的那个分片上（其余分片 toolCalls 为空）。
            var pendingToolCalls: List<com.google.ai.edge.litertlm.ToolCall> = emptyList()
            try {
                // automaticToolCalling=false：模型下发的 toolCalls 不会被执行，
                // 而是留在 Message 里由我们翻译成 MessageEvent.ToolCall。
                conv.sendMessageAsync(userText).collect { msg ->
                    val text = msg.extractText()
                    // 诊断：每条分片的角色/文本长度/toolCalls 数
                    android.util.Log.i("LITERT-DIAG", "chunk: role=" + msg.role +
                        " textLen=" + text.length + " toolCalls=" + msg.toolCalls.size +
                        " text=" + text.take(200))
                    if (text.isNotEmpty()) {
                        contentBuf.append(text)
                        send(MessageEvent.Content(text))
                    }
                    if (msg.toolCalls.isNotEmpty()) {
                        msg.toolCalls.forEach { tc ->
                            android.util.Log.i("LITERT-DIAG", "toolCall: name=" + tc.name +
                                " args=" + tc.arguments)
                        }
                        pendingToolCalls = msg.toolCalls
                    }
                }
                // 流结束后统一发工具调用事件（上层 ChatBridge 接管执行与续聊）。
                // 放在流末而非流中：避免工具执行与文本流交叉，保持「先出完整文本、再出工具卡片」的顺序。
                for (tc in pendingToolCalls) {
                    val callId = "lite_" + PROCESS_TAG + "_" + GLOBAL_CALL_SEQ.incrementAndGet()
                    send(MessageEvent.ToolCall(callId, tc.name, mapToJson(tc.arguments)))
                }
                send(MessageEvent.Done("", contentBuf.toString()))
            } catch (e: CancellationException) {
                throw e   // 停止生成：正常取消，向上传播
            } catch (t: Throwable) {
                send(MessageEvent.Error(LiteRtException("推理失败：${t.message}", t)))
            }
        }
    
        override suspend fun isAuthenticated(): Boolean = config.isModelAvailable()
    
        override suspend fun stopStream(token: String, sessionId: String, messageId: String) {
            // LiteRT 无服务端 stop 端点：取消 collect 协程即可终止推理（channelFlow 生产者随之取消）。
        }
    
        /**
         * 把 LiteRT 的 arguments（Map<String, Any>）序列化为 JSON 字符串。
         *
         * ChatBridge 的 dispatch 接受 JSON 字符串，故必须转。手写序列化而非引入
         * Gson/kotlinx：值类型只可能是 String/Number/Boolean/List/Map/Null，
         * 且 LiteRT 内部已用 Gson 解析过一遍，这里做等价还原即可。
         */
        private fun mapToJson(map: Map<String, Any?>): String {
            val sb = StringBuilder("{")
            var first = true
            for ((k, v) in map) {
                if (!first) sb.append(",")
                first = false
                sb.append(jsonString(k)).append(":").append(jsonValue(v))
            }
            sb.append("}")
            return sb.toString()
        }
    
        private fun jsonValue(v: Any?): String = when (v) {
            null -> "null"
            is String -> jsonString(v)
            is Boolean, is Number -> v.toString()
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                mapToJson(v as Map<String, Any?>)
            }
            is List<*> -> v.joinToString(",", "[", "]") { jsonValue(it) }
            else -> jsonString(v.toString())
        }
    
        private fun jsonString(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append("\"")
            return sb.toString()
        }
    }
    
    /**
     * 从 LiteRT [Message] 提取纯文本。
     *
     * Message 本身没有 text 属性，文本内容在 contents 列表的 Content.Text 元素里。
     * 多模态消息可能混有 Image/Audio，这里只取文本部分。
     */
    internal fun Message.extractText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    