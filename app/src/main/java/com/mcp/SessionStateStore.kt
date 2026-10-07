package com.mcp

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级会话态仓库（M6）。
 *
 * 手机桥（Tabs 创建）与桌面桥（WebApiServer 创建）**同进程共存**，各自 new 一个 ChatBridge
 * 就会让同一个 sid 存在两份 [ChatBridge.SessionState]：history 镜像、续聊锚点、
 * isBusy/streamJob、待回答的 ask_user 各一份，而磁盘日志只有一份。表现：
 *
 *  - **信息流不同步**：一端追加的消息只进自己那份内存镜像，另一端（含会话列表的 msgCount、
 *    历史分页、@UI 重建）看到的还是旧快照；模型下一轮的上下文也会缺对方轮次；
 *  - **运行状态不同步**：桌面点「停止」停不掉手机发起的流——stopStream 读的是本实例的
 *    state(sid).isBusy（false）而被守卫静默忽略；ask_user 提交同理（deferred 在对方实例里）；
 *  - 同一会话可能被两端各起一条流（各自的 isBusy 互不可见），日志交错。
 *
 * 修法：会话态提升为进程级唯一持有者，连同 history 锁与落盘互斥锁一起共享，与磁盘日志
 * （唯一的持久化真相）对齐。注意 [ChatBridge.currentSessionId] 仍是**每实例**字段——
 * 手机与桌面各自的 UI 焦点不同，不能共享。
 */
internal object SessionStateStore {

    /** sid → 会话态（进程内唯一）。淘汰策略仍在 ChatBridge.evictIdleStatesIfNeeded 里。 */
    val states = ConcurrentHashMap<String, ChatBridge.SessionState>()

    /**
     * history 列表的进程级同步锁。
     * 原先每个 ChatBridge 各持一把；共享 history 后必须同一把锁，否则两端的
     * 「读上一条 id + 追加」（ChatBridge.addToHistory）会交错，父子链接错。
     */
    val historyLock = Any()

    /**
     * 落盘互斥锁（appendMessage / rebuildMessages 串行化）。
     * 原先每实例一把，但两端写的是**同一份**会话日志，必须跨实例串行，
     * 否则 rebuildMessages（整文件重写）与 appendMessage 并发会丢消息。
     */
    val saveMutex = Mutex()

    /**
     * 取某会话的 DeepSeek fork 锚点（服务端会话 id + **本轮 user 消息 id**）。
     *
     * 子 Agent 用它做「同会话 + 同消息 id」fork：服务端消息树天然支持兄弟分支，
     * 于是子 Agent 继承主会话上下文、又不污染主链。
     *
     * ## 锚点必须是**本轮 user 消息**，不是 parentMessageId
     * 主 Agent 流式响应期间，MessageId 事件一到就把 parentMessageId 推进到了**本轮 assistant 消息**。
     * 拿它当父，子 Agent 会挂成主 Agent 回复的**孩子**（表现为「延续」，如 1-2-3-4-5-6）；
     * 取本轮 user 消息才能让二者成为**兄弟**（真正的分叉，如 1-2-3-{3,4}-…）。
     *
     * 依据：DeepSeek 逆向每轮创建两条连续 id 的消息 —— user(P+1)、assistant(P+2)，
     * 故本轮 user = 当前 assistant 锚点 - 1（ChatBridge 推算「停止消息 id」依赖同一规律）。
     */
    fun forkAnchor(sid: String): Pair<String, String?>? {
        val st = states[sid] ?: return null
        // serverSessionId 为空 = 尚未轮转，此时**本地 sid 就是服务端会话 id**
        // （与 ChatBridge 全链路的 `st.serverSessionId ?: sid` 一致）。
        val ssid = st.serverSessionId?.takeIf { it.isNotBlank() } ?: sid
        // 优先用**服务端下发的真实 user 消息 id**（SSE request_message_id）。
        // 旧的「parentMessageId - 1」只在 id 连续的轮次成立：走分叉后 id 会跳号
        // （台账实测 …18 → 21/22、25/26），推算出来的值指向**另一条消息** ——
        // 而这里正是 edit_message 定位兄弟分支的锚点，指错就在错的位置建分支。
        val real = st.lastUserMessageId?.takeIf { it.isNotBlank() }
        val cur = st.parentMessageId?.toLongOrNull()
        val forkParent = real ?: cur?.let { (it - 1).takeIf { v -> v >= 1 }?.toString() }
        return ssid to forkParent
    }

    /**
     * 把某会话的**已完成历史**转成 OpenAI 消息序列（不含 system，不含悬空 tool_call）。
     *
     * OpenAI 后端无状态，fork 只能靠「把主会话历史当消息前缀」。
     * 必须用 [ChatBridge.SessionState.history] 而非 openAIMessages —— 后者在子 Agent
     * 被调起的时刻尾部往往挂着未闭合的 assistant(tool_calls)（主 Agent 正等 delegate 结果），
     * 直接当前缀会被 OpenAI 400。
     */
    fun openAIContextSnapshot(sid: String): List<com.mcp.llm.ChatMessage> {
        val st = states[sid] ?: return emptyList()
        val out = ArrayList<com.mcp.llm.ChatMessage>()
        synchronized(historyLock) {
            for (h in st.history) {
                val tc = h.toolCall
                if (tc != null) {
                    // 工具结果还原为 function calling 序列：先 assistant(tool_calls)，再 tool 消息
                    out.add(
                        com.mcp.llm.ChatMessage(
                            role = "assistant",
                            reasoning = h.thinking.takeIf { it.isNotBlank() },
                            toolCalls = listOf(
                                com.mcp.llm.ToolCall(
                                    id = tc.id,
                                    function = com.mcp.llm.ToolCallFunction(tc.name, tc.arguments)
                                )
                            )
                        )
                    )
                    out.add(
                        com.mcp.llm.ChatMessage(
                            role = "tool",
                            content = com.mcp.toolbox.ToolMessages.extractContent(tc.result),
                            toolCallId = tc.id
                        )
                    )
                } else {
                    out.add(
                        com.mcp.llm.ChatMessage(
                            role = if (h.isUser) "user" else "assistant",
                            content = h.content,
                            reasoning = if (h.isUser) null else h.thinking.takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
        return out
    }

    /** 仅供测试/重置使用。 */
    fun clear() {
        states.clear()
    }
}
