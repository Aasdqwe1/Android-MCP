package com.mcp.apifwd

import com.mcp.llm.ChatMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 外部对话（`/v1` 意义上的一个 OpenAI 会话）在本地的那份记录。
 *
 * 这是设计文档 §7.2 的「外部上下文库」条目。判据是**上游接口形态**，不是后端名字：
 *  - 接受全量 `messages[]` 的后端（Web 自动化）不需要它；
 *  - DeepSeek 逆向**只接受「单条 prompt + parent_message_id」**，没有"一次投喂整段历史"
 *    的接口 —— 续聊只能靠服务端锚点串链，所以本地记录是必需品而非优化。
 *
 * 字段口径与现有 DeepSeek 逆向那套**一一对应**（不新造机制）：
 *  - [serverSessionId] ↔ `ChatBridge.SessionState.serverSessionId`
 *  - [anchorMessageId] ↔ `ChatBridge.SessionState.parentMessageId`
 *  - [idOffset]        ↔ `ChatBridge.SessionState.idOffset`
 *
 * ## 对齐关系（前缀比对的全部依据）
 *
 * `turns[i]` 对应**客户端 messages 数组**里的第 `packedThrough + i` 条。
 *
 * 打包重放之后，客户端前 N 条被吞进**一条** prompt，不再与 turns 逐条对应，
 * 所以用 [packedThrough] 记住被吞掉的条数，比对从它之后开始。
 * 少了这个字段，**重放后的第一次续聊必然被误判成分叉**，白跑一次重放。
 */
@Serializable
data class ConversationRecord(
    @SerialName("conv_key") val convKey: String,
    @SerialName("internal_sid") val internalSid: String,
    @SerialName("backend") val backend: String,
    /**
     * 该记录是**绑定模式的注入台账**，而非对话记忆。
     *
     * 两种用途的生命周期不同，必须显式区分 —— 早前混在同一个文件里，派生出两个真实缺陷：
     *  1) 台账只维护 serverSessionId / anchor / 声明指纹，**从不维护 turns**，
     *     于是"空 turns"成了任何请求的前缀，前缀反查会误命中它、把无关对话合并进来；
     *  2) 记录自身的结构契约（turns[i] ↔ 客户端第 packedThrough+i 条）被违反。
     * 有了这个标记，反查可以**按语义**排除台账，而不是靠 turns 是否为空去猜。
     */
    @SerialName("bound") val bound: Boolean = false,
    @SerialName("server_session_id") val serverSessionId: String? = null,
    @SerialName("anchor_message_id") val anchorMessageId: String? = null,
    @SerialName("id_offset") val idOffset: Long = 0L,
    @SerialName("packed_through") val packedThrough: Int = 0,
    @SerialName("turns") val turns: List<TurnRecord> = emptyList(),
    /**
     * 已注入过的**客户端系统提示词**指纹（台账）。
     *
     * 不能拿"会话有没有锚点"当"系统提示词注入过没有"的代理 ——
     * 那会让已有历史的会话永远注入不进去，也会在锚点读取有偏差时每轮重复注入。
     * 用指纹比对才是"变了才注入"的正确判据。
     */
    @SerialName("system_fp") val systemFp: String? = null,
    /** 已注入过的**工具声明**指纹（同上）。 */
    @SerialName("tools_fp") val toolsFp: String? = null,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("last_used_at") val lastUsedAt: Long = 0L,
) {
    /** 客户端 messages 中已被本记录覆盖的条数（打包点 + 逐条记录的轮次）。 */
    val coveredMessages: Int get() = packedThrough + turns.size
}

/**
 * 单个轮次在记录里的形态。
 *
 * [fingerprint] 是**归一化内容指纹**，只用于前缀比对 —— 刻意不存全文：
 * 记录要频繁落盘，体积压到最小（单条约 100B），
 * 这样"多留几个记录"的代价远低于"频繁触发打包重放"。
 */
@Serializable
data class TurnRecord(
    @SerialName("role") val role: String,
    @SerialName("fp") val fingerprint: String,
    @SerialName("local_id") val localMessageId: String = "",
    @SerialName("server_id") val serverMessageId: String? = null,
)

// ───────────────────────── 归一化与指纹 ─────────────────────────

/** 客户端一条消息的归一化视图：比对只需要角色与内容指纹。 */
data class ClientTurn(val role: String, val fingerprint: String)

/**
 * 内容指纹。
 *
 * ⚠️ 归一化规则必须与「记录时」**逐字一致**。两边不同，正常续聊就会被误判成分叉，
 * 触发一次代价高昂且有损的打包重放 —— 这是本模块最容易踩的坑。
 *
 * 这里只做**保守**归一：统一换行、去行尾空白、整段 trim、角色小写。
 *  - 覆盖客户端常见的重新序列化（CRLF/LF 混用、行尾空格、首尾空行）；
 *  - **不做**大小写折叠 / 空白压缩 / 标点归一 —— 那些会把语义不同的消息混为一谈，
 *    比"偶尔多一次重放"危险得多。
 */
fun turnFingerprint(role: String, content: String?): String {
    val r = role.trim().lowercase()
    val body = content.orEmpty()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()
    return sha256Hex(r + "\u0000" + body).take(32)
}

/** 把客户端 messages 归一化成可比对序列。 */
fun normalizeClientMessages(messages: List<ChatMessage>): List<ClientTurn> =
    messages.map { ClientTurn(it.role.trim().lowercase(), turnFingerprint(it.role, it.content)) }

/** 由一条消息构造记录项。 */
fun turnRecordOf(
    role: String,
    content: String?,
    localId: String = "",
    serverId: String? = null,
): TurnRecord = TurnRecord(
    role = role.trim().lowercase(),
    fingerprint = turnFingerprint(role, content),
    localMessageId = localId,
    serverMessageId = serverId,
)

/**
 * 任意文本的短指纹，用于"这段声明是否已经注入过"的台账。
 * 归一化与 [turnFingerprint] 同口径（统一换行 + trim），避免同一内容因空白差异被当成变了。
 */
fun textFingerprint(text: String): String =
    sha256Hex(text.replace("\r\n", "\n").trim()).take(16)

private const val HEX = "0123456789abcdef"

/** SHA-256 十六进制。用 JDK 自带实现，避免为一段纯逻辑引入依赖。 */
internal fun sha256Hex(s: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(d.size * 2)
    for (b in d) sb.append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
    return sb.toString()
}

// ───────────────────────── 对话标识 ─────────────────────────

/** 无法从请求推导出任何标识时的兜底 key（所有此类客户端会共用一个对话）。 */
const val DEFAULT_CONVERSATION_KEY = "default"

/**
 * 推导「外部对话」标识。
 *
 * 优先级：
 *  1. 显式 `X-Conversation-Id` 头 —— 最可靠，客户端完全可控；
 *  2. body `session_id` —— 本项目旧字段，保留兼容；
 *  3. body `user` —— OpenAI 标准字段（通常是终端用户标识）；
 *  4. 「首个 system + 首个 user」指纹派生 —— 客户端每轮都重发同样的开头，因此稳定；
 *  5. 兜底常量。
 *
 * ⚠️ 第 4 项无法区分「同一客户端开两个内容相同的全新对话」—— 它们会合并成一个。
 * 这是无 id 客户端的固有代价；带 `X-Conversation-Id` 时不存在该问题。
 * 注意这里**不做**"按前缀反查已有记录"——那需要读全量记录，属于 store 层（见 [ExternalConversationStore.findByMessagePrefix]）。
 *
 * 返回值全部**文件名安全**（只含 `a-z0-9_`），可直接当落盘文件名。
 */
fun deriveConversationKey(
    explicitConversationId: String?,
    bodySessionId: String?,
    bodyUser: String?,
    clientTurns: List<ClientTurn>,
): String {
    // 会话 id **原样绑定**（只做文件名安全化），不再哈希。
    // 理由：出问题时必须能一眼看出「这条外部对话绑在哪个会话 id 上」——
    // 哈希过的 key 只能反查日志，等于把可观测性换成了省几个字符。
    explicitConversationId?.takeIf { it.isNotBlank() }
        ?.let { return "h_" + sanitizeKeyPart(it) }
    bodySessionId?.takeIf { it.isNotBlank() }
        ?.let { return "s_" + sanitizeKeyPart(it) }
    bodyUser?.takeIf { it.isNotBlank() }
        ?.let { return "u_" + sanitizeKeyPart(it) }
    // 客户端没给任何标识时才退化到「按首轮内容派生」——这种 key 无 id 可绑，只能哈希
    val sys = clientTurns.firstOrNull { it.role == "system" }?.fingerprint.orEmpty()
    val usr = clientTurns.firstOrNull { it.role == "user" }?.fingerprint.orEmpty()
    if (sys.isNotEmpty() || usr.isNotEmpty()) return "d_" + sha256Hex(sys + "|" + usr).take(16)
    return DEFAULT_CONVERSATION_KEY
}

/**
 * 把会话 id 变成**文件名安全**的 key 片段：只保留 ASCII 字母数字、连字符、下划线，
 * 其余一律替换成下划线；并小写化，保证大小写不同的同一 id 不会落成两个对话。
 */
fun sanitizeKeyPart(raw: String, maxLen: Int = 48): String {
    val sb = StringBuilder(raw.length.coerceAtMost(maxLen))
    for (ch in raw.trim()) {
        if (sb.length >= maxLen) break
        val ok = ch == '-' || ch == '_' || (ch.code < 128 && ch.isLetterOrDigit())
        sb.append(if (ok) ch.lowercaseChar() else '_')
    }
    return sb.toString().trim('_').ifEmpty { "x" }
}

// ───────────────────────── 前缀比对（设计文档 §7.2 的四种情形） ─────────────────────────

/** 一次前缀比对的结果。 */
sealed interface ConvMatch {
    /** **情形 A**：前缀完全匹配且恰好新增一条 user —— 只发这一条。常规路径。 */
    data class Incremental(val newUserIndex: Int) : ConvMatch

    /** **情形 B**：客户端重发了已覆盖的内容、没有新增（超时重试 / 用户点重新生成）。 */
    data object Idempotent : ConvMatch

    /** **情形 C**：前缀在第 k 条客户端消息处断开 —— 客户端改了历史（编辑 / 删消息 / 回滚）。 */
    data class Fork(val matchedClientMessages: Int) : ConvMatch

    /** **情形 D**：无记录 / 记录已失效 / 对齐被破坏 —— 新建 + 打包重放。 */
    data object NoMatch : ConvMatch
}

/**
 * 把客户端发来的 messages 与本地记录做前缀比对。
 *
 * 这是增量通道的正确性核心：[ConversationRecord] 是**经过校验的缓存**，不是替代品。
 * 任何比对失败都必须能退化到「新建 + 打包重放」——**绝不能带着可能失效的
 * [ConversationRecord.anchorMessageId] 继续发**，那会让服务端开新分支、历史静默丢失
 * （现有 `ChatBridge.ensureDeepSeekAnchor` 的守卫注释防的正是这类事）。
 */
fun matchConversation(record: ConversationRecord, client: List<ClientTurn>): ConvMatch {
    // 客户端历史被截断到打包点之前 → 整条记录作废（已无法对齐）
    if (client.size < record.packedThrough) return ConvMatch.NoMatch

    val turns = record.turns
    var matched = 0
    while (matched < turns.size && record.packedThrough + matched < client.size) {
        if (turns[matched].fingerprint != client[record.packedThrough + matched].fingerprint) break
        matched++
    }

    // 前缀在第 matched 条断开（含"客户端把尾部删掉了"这种回滚）
    if (matched < turns.size) return ConvMatch.Fork(record.packedThrough + matched)

    val covered = record.coveredMessages
    return when {
        client.size == covered -> ConvMatch.Idempotent
        client.size == covered + 1 && client.last().role == "user" -> ConvMatch.Incremental(covered)
        // 客户端一次追加了两条以上（例如它自己生成的 assistant 回复，或历史整体被换掉）：
        // 增量通道一轮只能发一条 prompt，无法表达，交给重放路径
        else -> ConvMatch.Fork(covered)
    }
}

/** 新对话的起点记录（情形 D 的新建分支）。 */
fun newConversationRecord(
    convKey: String,
    internalSid: String,
    backend: String,
    now: Long,
): ConversationRecord = ConversationRecord(
    convKey = convKey,
    internalSid = internalSid,
    backend = backend,
    createdAt = now,
    lastUsedAt = now,
)

/**
 * **情形 A**（增量续聊）成功后推进记录。
 *
 * @param userTurn 本轮新发的用户消息（客户端下标 = newUserIndex）
 * @param assistantTurn 本轮产生的回复（客户端下标 = newUserIndex + 1）
 */
fun ConversationRecord.afterIncremental(
    userTurn: TurnRecord,
    assistantTurn: TurnRecord,
    serverSessionId: String?,
    anchorMessageId: String?,
    now: Long,
): ConversationRecord = copy(
    turns = turns + listOf(userTurn, assistantTurn),
    serverSessionId = serverSessionId ?: this.serverSessionId,
    anchorMessageId = anchorMessageId,
    lastUsedAt = now,
)

/**
 * **情形 D**（打包重放）成功后推进记录。
 *
 * 前 [clientMessageCount] 条客户端消息已被吞进一条 prompt，因此把 [packedThrough]
 * 推进到该位置，`turns` 从本轮 assistant 重新开始 ——
 * 它在客户端下一轮请求里正是第 [clientMessageCount] 条。
 */
fun ConversationRecord.afterPacked(
    clientMessageCount: Int,
    assistantTurn: TurnRecord,
    serverSessionId: String?,
    anchorMessageId: String?,
    now: Long,
): ConversationRecord = copy(
    packedThrough = clientMessageCount,
    turns = listOf(assistantTurn),
    serverSessionId = serverSessionId ?: this.serverSessionId,
    anchorMessageId = anchorMessageId,
    lastUsedAt = now,
)

/**
 * **情形 C**（分叉）成功后推进记录。
 *
 * DeepSeek 服务端是消息树，可从分叉点 `edit_message` 建兄弟分支；
 * 因此分叉点**之前**的轮次与锚点仍然有效，不必重建会话。
 * 这里把记录截断到分叉点，再接上新的一轮。
 *
 * @param forkClientIndex 客户端 messages 中第一个不再匹配的下标
 * @param userTurn 客户端在该下标处的新 user 消息（若是纯回滚、此处没有新消息则传 null）
 */
fun ConversationRecord.afterFork(
    forkClientIndex: Int,
    userTurn: TurnRecord?,
    assistantTurn: TurnRecord,
    serverSessionId: String?,
    anchorMessageId: String?,
    now: Long,
): ConversationRecord {
    val keep = (forkClientIndex - packedThrough).coerceIn(0, turns.size)
    return copy(
        turns = turns.take(keep) + listOfNotNull(userTurn, assistantTurn),
        serverSessionId = serverSessionId ?: this.serverSessionId,
        anchorMessageId = anchorMessageId,
        lastUsedAt = now,
    )
}
