package com.mcp.data

import android.content.Context
import com.mcp.core.chat.ChatMessage
import com.mcp.core.chat.ChatSession
import com.mcp.core.llm.BackendType
import com.mcp.data.persistence.JsonlPersistenceBackend
import com.mcp.data.persistence.PersistenceCoordinator
import com.mcp.serialization.McpJson
import java.io.File
import java.io.RandomAccessFile

/**
 * 本地持久化：会话列表 + 每个会话的消息气泡（含思考/回复）。
 *
 * 用途：冷启动或**无网络**时，先渲染本地缓存内容，避免界面空白；
 * 联网成功后再用服务端数据刷新并回写。
 *
 * ⚠️ 后端隔离：不同 LLM 后端使用不同的文件命名空间，避免混用。
 *   统一收拢到 `<filesDir>/Chat/` 下：
 *   - 会话列表：`Chat/<tag>_sessions.json`（ds_sessions.json / oa_sessions.json）
 *   - 每会话目录：`Chat/<tag>/<sid>/`，内含 `Workspace/`（该会话产生的文件）、
 *     `<tag>_msg_<sid>.json`（会话消息日志）、`<tag>_token_flow_<sid>.jsonl`（请求流水）、
 *     `<tag>_sess_cur_<sid>.txt`（续聊锚点）。
 *   - 会话 id 带 `<tag>-` 前缀时（如 `oa-<uuid>`），目录名去掉该前缀（如 `Chat/oa/<uuid>`）。
 * OpenAI 兼容协议本身无"会话列表"概念（无状态，靠 messages 数组带上下文），
 * 其会话列表纯本地管理，不调用任何远端会话 API。
 *
 * 单会话消息已改为「事件溯源 JSONL 会话日志」：由 [PersistenceCoordinator] 编排
 * （append-only 追加、惰性物化 header、torn-tail 崩溃自愈、有界 LRU 游标状态、按会话串行化），
 * 底层物理读写走 [JsonlPersistenceBackend]。本对象仅保留同步门面 API，其余会话列表逻辑不变。
 */
object LocalStore {

    /** 依据后端类型返回文件命名空间标签。 */
    fun backendTag(backend: BackendType): String = when (backend) {
        BackendType.OPENAI -> "oa"
        // Web 自动化：无服务端状态，历史本地维护，独立命名空间避免与另两个后端串味。
        BackendType.WEB_AUTOMATION -> "wa"
        BackendType.LITERT -> "lt"
        // MNN 本地模型：同样无服务端状态，独立命名空间。
        BackendType.MNN -> "mn"
    }

    /**
     * DeepSeek 服务端消息 id 合法性：1..u32 的正整数字符串（无 +/空格/0x/小数）。

     * 用途：加载会话列表时过滤已被污染的续聊锚点。历史上 SSE 帧中的
     * `file-<uuid>` 曾被当作消息 id 落盘为 currentMessageId，若直接放行，
     * 续聊会因 parent_message_id 非法而被服务端视为「新分支」，历史全丢。
     *
     * ⚠️ 与 com.mcp.chat.MessageIdSpace.isValidServerId 语义完全一致；这里复制而非
     * 引用是因为 app 依赖 data（不能反向引用），且本函数是 3 行纯判定，
     * 未来收敛到 core 模块时应两处同步删除。
     */
    private fun isValidServerMessageId(id: String?): Boolean {
        if (id == null || id.isEmpty()) return false
        if (id.length > 10) return false   // u32 最多 10 位
        for (i in id.indices) {
            if (!id[i].isDigit()) return false
        }
        return id.toLongOrNull()?.let { it in 1L..UInt.MAX_VALUE.toLong() } ?: false
    }

    // ───────────────────────── 会话日志协调器 ─────────────────────────

    /** 每 tag 一个协调器（有界 LRU 游标状态），进程内共享、按 tag 串行化。 */
    private val coordinators = HashMap<String, PersistenceCoordinator>()
    private val coordinatorsLock = Any()

    private fun coordinatorFor(ctx: Context, tag: String): PersistenceCoordinator =
        synchronized(coordinatorsLock) {
            coordinators.getOrPut(tag) {
                PersistenceCoordinator(JsonlPersistenceBackend(ctx.filesDir, tag), tag)
            }
        }

    /**
     * 会话存储根目录：`<filesDir>/Chat/`。
     * 每个后端一个子目录（ds/oa），每个会话再一个子目录，会话内用 `Workspace/` 存放
     * 该会话产生的文件。会话列表文件位于 `Chat/<tag>_sessions.json`（保持历史命名）。
     */
    private fun chatRoot(ctx: Context) = File(ctx.filesDir, "Chat")

    /** 会话目录：`<filesDir>/Chat/<tag>/<sid>`。会话 id 带 `<tag>-` 前缀时目录名去掉该前缀。 */
    private fun sessionDir(ctx: Context, tag: String, sid: String): File =
        File(File(chatRoot(ctx), tag), sid.removePrefix("$tag-"))

    /** 会话工作目录：模型在本会话中产生/修改文件的可读写落点。 */
    fun workspaceDir(ctx: Context, tag: String, sid: String): File =
        File(sessionDir(ctx, tag, sid), "Workspace")

    /** 确保会话目录与 Workspace 目录存在，返回 Workspace 目录绝对路径（供系统提示词注入等使用）。 */
    fun ensureSessionWorkspace(ctx: Context, tag: String, sid: String): File {
        val ws = workspaceDir(ctx, tag, sid)
        runCatching { ws.mkdirs() }
        return ws
    }

    private fun sessionsFile(ctx: Context, tag: String) = File(chatRoot(ctx), "${tag}_sessions.json")
    private fun sessCurFile(ctx: Context, tag: String, sid: String) = File(sessionDir(ctx, tag, sid), "${tag}_sess_cur_$sid.txt")
    private fun sessRotationFile(ctx: Context, tag: String, sid: String) =
        File(sessionDir(ctx, tag, sid), "${tag}_sess_rotation_$sid.json")
    private fun tokenFlowFile(ctx: Context, tag: String, sid: String) = File(sessionDir(ctx, tag, sid), "${tag}_token_flow_${sid}.jsonl")

    // ───────────────────────── 会话列表 ─────────────────────────

    fun loadSessions(ctx: Context, tag: String): List<ChatSession> {
        val f = sessionsFile(ctx, tag)
        if (!f.exists()) return emptyList()
        return runCatching {
            // McpJson（ignoreUnknownKeys + coerceInputValues + isLenient）宽松解析本地缓存，
            // 对稍不规范的旧缓存 JSON 更鲁棒；字段名经 @SerialName 与磁盘格式对齐。
            val decoded = McpJson.decodeFromString<List<ChatSession>>(f.readText())
            decoded.map { s ->
                // 优先从 per-session 小文件读取续聊锚点（saveSessionCurrentId 写入），
                // 回退到 JSON 字段（saveSessions 时写入）。
                // 仅 ds 命名空间校验锚点合法性（1..u32 正整数）：历史上 ds_sessions.json
                // 曾被 file-<uuid> 污染，脏值若在此放行并回写，会盖掉服务端正确值，
                // 使该会话续聊时 parent_message_id 变成 null，历史全丢。详见
                // com.mcp.chat.MessageIdSpace.isValidServerId。
                val fromFile = runCatching {
                    sessCurFile(ctx, tag, s.id).readText().takeIf { it.isNotEmpty() }
                }.getOrNull()
                val cur = (fromFile ?: s.currentMessageId?.takeIf { it.isNotEmpty() })
                    ?.takeIf { tag != "ds" || isValidServerMessageId(it) }
                if (cur == s.currentMessageId) s else s.copy(currentMessageId = cur)
            }
        }.getOrDefault(emptyList())
    }

    fun saveSessions(ctx: Context, tag: String, list: List<ChatSession>) {
        runCatching {
            val f = sessionsFile(ctx, tag)
            f.parentFile?.mkdirs()
            f.writeText(McpJson.encodeToString(list))
        }
    }

    /**
     * 更新某会话的"当前消息锚点"（续聊 parent 锚点），并落盘。
     *
     * 优化：写 per-session 小文件而不是全量 load+save 会话列表（O(1) vs O(N)）。
     * 下次 loadSessions 时优先从此文件读取 currentMessageId，回退到 JSON 中的字段。
     */
    fun saveSessionCurrentId(ctx: Context, tag: String, sessionId: String, currentMessageId: String?) {
        val file = sessCurFile(ctx, tag, sessionId)
        runCatching {
            if (currentMessageId != null) {
                file.parentFile?.mkdirs()
                file.writeText(currentMessageId)
            } else file.delete()
        }
    }

    // ───────────────────────── 分支选择状态（◀ 分支 k/N ▶ 箭头） ─────────────────────────

    private fun branchStateFile(ctx: Context, tag: String, sid: String) =
        File(sessionDir(ctx, tag, sid), "${tag}_sess_branch_$sid.json")

    /**
     * 保存某会话的**分支选择状态**（分叉点 ◀/▶ 箭头选中的分支）。
     *
     * 前端 `activeTurn`（parentId -> 选中 turn 索引）与 `userSwitched`（parentId -> 用户是否
     * 手动切换过）此前只活在 WebView 内存里：切走/重启后 `activeTurn` 为空，`recomputeVisibility`
     * 会退回「最新一轮」默认值——用户手动切到的旧分支丢失，必须重新点箭头找回来。
     * 这里把它按会话落盘，加载历史后回填，实现「重启后仍停在原分支」。
     *
     * @param json 前端序列化的 `{"activeTurn":{...},"userSwitched":{...}}`；空/无状态则删除文件
     */
    fun saveBranchState(ctx: Context, tag: String, sid: String, json: String?) {
        val file = branchStateFile(ctx, tag, sid)
        runCatching {
            if (json.isNullOrBlank() || json == "{}" || json == "{\"activeTurn\":{},\"userSwitched\":{}}") {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(json)
            }
        }
    }

    /** 读取某会话的分支选择状态（JSON）；文件不存在/损坏返回空串（前端按「无状态=默认最新」处理）。 */
    fun loadBranchState(ctx: Context, tag: String, sid: String): String = runCatching {
        val f = branchStateFile(ctx, tag, sid)
        if (!f.exists()) "" else f.readText()
    }.getOrDefault("")

    /**
     * 会话轮转状态（DeepSeek session rotation 专用，随会话落盘）。
     *
     * 上下文压缩后会新建一个服务端会话：新的 chat_session_id、消息 id 重新从 1 开始。
     * 这两项都必须在本地留痕，否则进程被杀后：
     *  - 请求会退回旧的（已满的）服务端会话，轮转被静默撤销；
     *  - 本地气泡 id 的换算基准丢失，锚点会被算错。
     *
     * @param serverSessionId 轮转后实际使用的服务端会话 id（空 = 未轮转过，沿用本地 sid）
     * @param idOffset        本地 id = 服务端 id + idOffset（见 MessageIdSpace）
     */
    @kotlinx.serialization.Serializable
    data class SessionRotation(
        @kotlinx.serialization.SerialName("server_session_id")
        val serverSessionId: String = "",
        @kotlinx.serialization.SerialName("id_offset")
        val idOffset: Long = 0L
    )

    /** 读取会话轮转状态；文件不存在/损坏返回 null（调用方按「未轮转过」处理）。 */
    fun loadSessionRotation(ctx: Context, tag: String, sid: String): SessionRotation? = runCatching {
        val f = sessRotationFile(ctx, tag, sid)
        if (!f.exists()) null else McpJson.decodeFromString<SessionRotation>(f.readText())
    }.getOrNull()

    /**
     * 写入会话轮转状态。两项都归零时删除文件（回到「未轮转过」的语义），
     * 避免留下一个空的 rotation 文件让后续判断变复杂。
     */
    fun saveSessionRotation(ctx: Context, tag: String, sid: String, rotation: SessionRotation) {
        runCatching {
            val f = sessRotationFile(ctx, tag, sid)
            if (rotation.serverSessionId.isBlank() && rotation.idOffset <= 0L) {
                f.delete()
                return
            }
            f.parentFile?.mkdirs()
            f.writeText(McpJson.encodeToString(rotation))
        }
    }

    // ───────────────────────── 单会话消息气泡（事件溯源日志） ─────────────────────────

    /** 读取某会话的已提交消息前缀（扫描 + torn-tail 自愈）。 */
    fun loadMessages(ctx: Context, tag: String, sid: String): List<ChatMessage> =
        coordinatorFor(ctx, tag).load(sid)

    /**
     * 追加一条消息事件到会话日志（append-only，不整体重写文件）。
     * 借鉴 deepseek-harness 的追加式会话日志：每条消息即时、独立落盘，
     * 会话进行中切走再切回也不会因「最后统一快照覆盖」而丢失中间消息。
     * 首次写入会自动「惰性物化」header 行，旧 JSON 数组/裸 JSONL 数据在下次写入时自然迁移。
     */
    fun appendMessage(ctx: Context, tag: String, sid: String, msg: ChatMessage) {
        coordinatorFor(ctx, tag).append(sid, msg)
    }

    /**
     * 追加一条任意类型的会话事件（PTC 审计：ptc/call · ptc/result）。
     * 与消息共用同一份 append-only 日志，但不会进入 [loadMessages]（按 type 过滤）。
     */
    fun appendSessionEvent(ctx: Context, tag: String, sid: String, ev: com.mcp.data.persistence.SessionLogEvent) {
        coordinatorFor(ctx, tag).appendEvent(sid, ev)
    }

    /** 全量重建会话日志（拉取服务端历史、编辑/重新生成改写历史时用）。 */
    fun rebuildMessages(ctx: Context, tag: String, sid: String, list: List<ChatMessage>) {
        coordinatorFor(ctx, tag).rebuild(sid, list)
    }

    /** 删除某会话的本地消息缓存（如需要清理时调用）。 */
    fun deleteMessages(ctx: Context, tag: String, sid: String) {
        coordinatorFor(ctx, tag).delete(sid)
    }

    /**
     * 删除某会话中指定 id 的消息（从本地消息日志中移除并重建）。
     * 删除目标消息及其之后的所有消息（保持 parentId 链完整）。
     */
    fun deleteMessage(ctx: Context, tag: String, sid: String, messageId: String) {
        val msgs = loadMessages(ctx, tag, sid)
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val trimmed = msgs.take(idx)
        if (trimmed.isEmpty()) deleteMessages(ctx, tag, sid) else rebuildMessages(ctx, tag, sid, trimmed)
    }

    /** 追加一条请求流水记录到该会话的 JSONL 文件。 */
    fun saveTokenFlow(ctx: Context, tag: String, sid: String, line: String) {
        val f = tokenFlowFile(ctx, tag, sid)
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText(if (f.exists()) "\n$line" else line)
        }
    }

    /** 读取某会话的请求流水记录（每行一条 JSON）。 */
    fun loadTokenFlow(ctx: Context, tag: String, sid: String): List<String> {
        val f = tokenFlowFile(ctx, tag, sid)
        return runCatching {
            if (!f.exists()) emptyList() else f.readLines()
        }.getOrDefault(emptyList())
    }

    /** 删除某会话的请求流水文件。 */
    fun deleteTokenFlow(ctx: Context, tag: String, sid: String) {
        runCatching { tokenFlowFile(ctx, tag, sid).delete() }
    }

    /** 删除整个会话：会话列表条目 + 消息缓存 + 续聊锚点 + 流水记录 + 会话目录（含 Workspace）。 */
    fun deleteSession(ctx: Context, tag: String, sid: String) {
        val updated = loadSessions(ctx, tag).filterNot { it.id == sid }
        saveSessions(ctx, tag, updated)
        deleteMessages(ctx, tag, sid)
        // sessionDir 内含分支状态文件，deleteRecursively 一并清掉，无需单独处理。
        runCatching { sessionDir(ctx, tag, sid).deleteRecursively() }
    }

    /** 重命名会话标题并持久化（OpenAI 本地会话用）。 */
    fun renameSession(ctx: Context, tag: String, sid: String, title: String) {
        val updated = loadSessions(ctx, tag).map {
            if (it.id == sid) it.copy(title = title) else it
        }
        saveSessions(ctx, tag, updated)
    }

    /** 切换会话置顶状态并持久化。 */
    fun toggleSessionPinned(ctx: Context, tag: String, sid: String) {
        val updated = loadSessions(ctx, tag).map {
            if (it.id == sid) it.copy(pinned = !it.pinned) else it
        }
        saveSessions(ctx, tag, updated)
    }

    /**
     * 更新会话活跃时间（updatedAt=当前毫秒）并持久化。
     * OpenAI 本地会话创建时 updatedAt 为 0（列表显示「未知」），
     * 每次发消息后调用即可让列表时间恢复正常。
     */
    fun touchSession(ctx: Context, tag: String, sid: String) {
        val now = System.currentTimeMillis() / 1000.0
        val updated = loadSessions(ctx, tag).map {
            if (it.id == sid) it.copy(updatedAt = now) else it
        }
        saveSessions(ctx, tag, updated)
    }

    /** 会话是否存在（列表里有该 id）。 */
    fun hasSession(ctx: Context, tag: String, sid: String): Boolean =
        loadSessions(ctx, tag).any { it.id == sid }

    /**
     * 返回某会话本地消息文件的上次修改时间（Unix 秒）；无消息文件则返回 0.0。
     * 用于旧会话 updatedAt=0（列表显示「未知」）时回退出一个可读时间。
     */
    fun messageMtime(ctx: Context, tag: String, sid: String): Double =
        coordinatorFor(ctx, tag).lastModified(sid)

    /**
     * 列出某命名空间下**确实有消息日志**的会话 id（扫描 `Chat/<tag>/` 目录）。
     *
     * 用途：会话列表文件（`<tag>_sessions.json`）漏写或被覆盖时，仍能把会话从消息日志里找回来——
     * Web 自动化历史上就踩过：列表条目被写进了 OpenAI 命名空间，消息却留在自己的命名空间里，
     * 结果"会话页面没有会话"。
     *
     * 只读目录并检查消息文件；[deleteSession] 会连目录一起删，所以不会把已删除的会话复活。
     */
    fun sessionIdsWithMessages(ctx: Context, tag: String): List<String> = runCatching {
        File(chatRoot(ctx), tag).listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.filter { messageMtime(ctx, tag, it) > 0.0 }
            ?.sorted()
            ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * 清空某后端的全部本地会话数据：会话列表 + 所有单会话消息 + 续聊锚点小文件 + Token 请求流水
     * + 各会话目录（含 Workspace）。仅删除 [tag] 命名空间（`Chat/<tag>/` 与 `Chat/<tag>_sessions.json`），
     * 不影响其他后端。
     */
    fun clearAll(ctx: Context, tag: String) {
        runCatching { sessionsFile(ctx, tag).delete() }
        runCatching { File(chatRoot(ctx), tag).deleteRecursively() }
        // 丢弃该后端的游标状态，避免残留指向已删除文件的陈旧游标。
        synchronized(coordinatorsLock) { coordinators[tag]?.reset() }
    }
}