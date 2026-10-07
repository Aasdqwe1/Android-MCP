package com.mcp.data.persistence

import com.mcp.core.chat.ChatMessage
import com.mcp.core.chat.ToolCallData
import com.mcp.serialization.McpJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 会话日志格式版本。写在首行 header 里，供读取方判定是否可解释该日志。 */
const val SESSION_LOG_VERSION = 1

/**
 * 日志专用序列化器：`encodeDefaults=true` 让 `type`/`version`/`seq` 显式落盘，日志自描述、
 * 无需依赖「缺省字段回填默认值」的脆弱约定（对齐 dsh 总是写出 `type: 'session'` 与 `seq`）。
 * 读取仍走宽容的 [McpJson]，两者共享同一组 @Serializable 模型，显式字段只是多解几个已知键。
 */
private val LogJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 会话日志首行：不可变会话元数据，`type: "session"` 用于与事件行区分。
 * 借鉴 deepseek-harness 的 `HeaderLine`：日志是一个「header 行 + 连续事件行」的
 * append-only 文件，header 让读取方无需解析整篇即可识别身份与格式版本。
 */
@Serializable
data class SessionLogHeader(
    val type: String = "session",
    val version: Int = SESSION_LOG_VERSION,
    val id: String,
    val createdAt: Long,
    val tag: String = ""
)

/**
 * 一条仅追加的会话事件。`seq` 从 0 连续递增，是事件溯源日志「连续、可校验」的
 * 不变量来源；消息字段内联（等价于一条 [ChatMessage] 载荷）。
 *
 * 之所以内联而不是嵌套 payload：旧版磁盘上的「裸 ChatMessage JSONL」恰好是本
 * 结构的子集（type/seq 都有默认值），因此可用同一反序列化器读取新旧两种 JSONL，
 * 实现平滑迁移。
 */
@Serializable
data class SessionLogEvent(
    val type: String = "message",
    val seq: Long = 0,
    val isUser: Boolean = false,
    val content: String = "",
    val thinking: String = "",
    val toolCall: ToolCallData? = null,
    val id: String = "",
    val parentId: String = "",
    /**
     * 父事件的 [seq]（嵌套事件用）。PTC 的 `ptc/call` / `ptc/result` 挂在外层
     * `run_code` 调用事件之下，日志重放时可据此还原调用树；顶层事件为 null。
     * 有默认值 ⇒ 旧日志可无字段读取，向后兼容。
     */
    val parentSeq: Long? = null,
    /** 嵌套深度（0 = 顶层）。用于 UI 缩进渲染，避免递归计算 parentSeq。 */
    val depth: Int = 0
) {
    /** 还原为领域消息对象。 */
    fun toMessage(): ChatMessage = ChatMessage(isUser, content, thinking, toolCall, id, parentId)

    companion object {
        fun of(seq: Long, m: ChatMessage): SessionLogEvent = SessionLogEvent(
            seq = seq,
            isUser = m.isUser,
            content = m.content,
            thinking = m.thinking,
            toolCall = m.toolCall,
            id = m.id,
            parentId = m.parentId
        )
    }
}

/**
 * 一次日志扫描的结果：header（新版才有）、可解析的事件前缀、是否存在 torn-tail，
 * 以及是否为带 header 的新版格式。
 */
data class LogScan(
    val meta: SessionLogHeader?,
    val events: List<SessionLogEvent>,
    val tornTail: Boolean,
    val hasHeader: Boolean
)

/**
 * 会话日志编解码 + 增量兼容扫描。
 *
 * 借鉴 deepseek-harness 的 `SessionLogScanner`：扫描只解码「完整的新行结尾记录」，
 * 末尾若存在一行没有换行结尾（写入中途断电/崩溃留下的半行），视为 torn-tail 而不
 * 丢弃已提交的前缀——下次 append 时截断该半行即可自愈。
 *
 * 兼容三种历史磁盘格式：
 *  1. 旧 JSON 数组 `[ {...}, {...} ]`
 *  2. 旧裸 ChatMessage JSONL（无 header、无 type/seq）
 *  3. 新 header + 事件行（`type/seq` 内联）
 */
object SessionEventLog {

    fun headerLine(h: SessionLogHeader): String = LogJson.encodeToString(h)

    fun eventLine(e: SessionLogEvent): String = LogJson.encodeToString(e)

    /**
     * 从事件流派生「模型历史」：只取 `type == "message"` 的事件。
     * PTC 的 `ptc/call` / `ptc/result` 是审计事件（子调用不进模型上下文），
     * 若不在此过滤，它们会被当成消息塞回历史并污染后续请求。
     */
    fun messagesOf(events: List<SessionLogEvent>): List<ChatMessage> =
        events.filter { it.type == MESSAGE_TYPE }.map { it.toMessage() }

    /** 消息事件类型（默认值，见 [SessionLogEvent.type]）。 */
    const val MESSAGE_TYPE = "message"

    /**
     * 按行切分文本，并无损保留「结尾是否有换行」这一信息（用于判定最后一行为
     * 完整提交还是 torn tail）。`String.split('\n')` 会吞掉末尾空段，故此处手动处理。
     */
    private fun splitLines(text: String): Pair<List<String>, Boolean> {
        if (text.isEmpty()) return emptyList<String>() to true
        val endsWithNewline = text.endsWith("\n")
        val raw = text.split("\n")
        val lines = if (endsWithNewline) raw.dropLast(1) else raw
        return lines to endsWithNewline
    }

    /** 首行是否为合法 header；是则返回 (header, 事件起始行下标)，否则 (null, 0)。 */
    private fun parseHeader(firstLine: String?): Pair<SessionLogHeader?, Int> {
        val line = firstLine ?: return null to 0
        val h = runCatching { McpJson.decodeFromString<SessionLogHeader>(line) }.getOrNull()
            ?: return null to 0
        // header 要求缺省字段 id/createdAt（无默认值）成立；type 由默认值保证为 "session"。
        return if (h.type == "session" && h.id.isNotEmpty()) h to 1 else null to 0
    }

    /**
     * 从完整日志文本重建事件前缀。
     * - 旧 JSON 数组：解码为 [ChatMessage] 列表，按序赋 seq。
     * - JSONL（新旧皆可）：首行尝试 header，其余每行解码为 [SessionLogEvent]；
     *   seq 以「事件在日志中的位置」为准（旧格式无 seq 记录，靠位置推导）。
     * - torn-tail：最后一行无换行结尾且解析失败时，标记 [LogScan.tornTail] 并保留
     *   已解析前缀（不丢弃中间已提交消息）。
     * - 中间损坏行：停止扫描并保留此前的前缀（与 dsh「保留 committed prefix」一致）。
     */
    fun scan(text: String): LogScan {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return LogScan(null, emptyList(), false, false)

        // 旧 JSON 数组格式
        if (trimmed.startsWith("[")) {
            val list = runCatching { McpJson.decodeFromString<List<ChatMessage>>(text) }.getOrNull()
            if (list == null) return LogScan(null, emptyList(), !text.endsWith("\n"), false)
            val events = list.mapIndexed { i, m -> SessionLogEvent.of(i.toLong(), m) }
            return LogScan(null, events, false, false)
        }

        val (lines, endsWithNewline) = splitLines(text)
        if (lines.isEmpty()) return LogScan(null, emptyList(), false, false)

        val (meta, startIndex) = parseHeader(lines.firstOrNull())
        val events = mutableListOf<SessionLogEvent>()
        var torn = false

        for (idx in lines.indices) {
            if (idx < startIndex) continue
            val line = lines[idx]
            if (line.isBlank()) continue
            val ev = runCatching { McpJson.decodeFromString<SessionLogEvent>(line) }.getOrNull()
            if (ev == null) {
                // 最后一行且无换行结尾 → torn tail（半行），忽略但标记，可自愈。
                if (idx == lines.lastIndex && !endsWithNewline) torn = true
                break
            }
            // 以解析顺序重建 seq（新旧格式统一由位置锚定，避免旧数据 seq 全 0 造成断裂）。
            events.add(ev.copy(seq = events.size.toLong()))
        }
        return LogScan(meta, events, torn, meta != null)
    }
}