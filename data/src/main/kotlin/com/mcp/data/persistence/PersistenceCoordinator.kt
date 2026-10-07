package com.mcp.data.persistence

import com.mcp.core.chat.ChatMessage
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 会话持久化写路径编排器（同步门面）。
 *
 * 借鉴 deepseek-harness 的 `PersistenceCoordinator`：把「读/写/物化/游标/崩溃修复」的编排
 * 与物理存储（[PersistenceBackend]）解耦。相比 dsh 的差异是刻意收敛的：
 *  - dsh 是事件总线 + Promise 链 + AbortSignal + structuredClone 的异步模型；本项目是
 *    Android 单进程同步文件模型，用一把 [ReentrantLock] 做「按会话串行化」即可，避免并发
 *    append 交错或并发物化竞态。
 *  - dsh 的有界 LRU 针对「未发布的 prepared Session」；这里的 LRU 针对「内存中的每会话
 *    游标状态」，超过 [maxStates] 时淘汰最久未访问的会话，避免内存无界增长。
 *  - 事件序列由协调器内部按游标分配（ChatMessage 无 seq），因此「seq 连续」是内置不变量，
 *    无需调用方传递预编号事件。
 *
 * 每个 tag（ds/oa 后端命名空间）对应一个协调器实例，由 [com.mcp.data.LocalStore] 持有。
 */
class PersistenceCoordinator(
    private val backend: PersistenceBackend,
    private val tag: String,
    private val maxStates: Int = DEFAULT_MAX_SESSION_STATES,
) {
    companion object {
        /** 有界 LRU：内存中最多保留的会话游标状态数。 */
        const val DEFAULT_MAX_SESSION_STATES = 16
    }

    /**
     * 每会话的持久化游标状态。`cursor` 是「已持久化事件的条数」= 下一条事件的 seq，
     * 与 [SessionEventLog.scan] 按位置重建 seq 的语义一致。
     */
    private class State(
        val meta: SessionLogHeader,
        var cursor: Int,
        var materialized: Boolean,
        var lastAccessAt: Long,
        /**
         * 上次 load 得到的「模型历史」缓存（B8）：冷启动反复 load 同一会话时避免整文件重扫。
         * 以「文件字节长度」作为新鲜度判据；任何写路径都会把它置空。
         */
        var cachedMessages: List<ChatMessage>? = null,
        var cachedLen: Long = -1L,
    )

    private val lock = ReentrantLock()
    private val states = HashMap<String, State>()

    // ───────────────────────── 读路径 ─────────────────────────

    /**
     * 扫描并自愈一份会话日志，返回派生出的消息历史（已提交前缀）。
     * 若末尾存在崩溃留下的半行（torn tail），截断重写为已提交前缀后再返回。
     */
    fun load(sid: String): List<ChatMessage> = lock.withLock {
        // B8：文件长度未变且已缓存 → 直接命中，避免冷启动反复整文件读 + 扫描
        val len = backend.length(sid)
        val cached = states[sid]
        if (cached != null && cached.cachedMessages != null && cached.cachedLen == len && len > 0L) {
            cached.lastAccessAt = nowMillis()
            return@withLock cached.cachedMessages!!
        }
        val raw = backend.readRaw(sid) ?: return@withLock emptyList()
        val scan = SessionEventLog.scan(raw)
        normalizeLog(sid, scan)
        if (scan.events.isEmpty()) return@withLock emptyList()
        val meta = scan.meta ?: SessionLogHeader(id = sid, createdAt = nowMillis(), tag = tag)
        val state = State(meta, scan.events.size, true, nowMillis())
        track(state)
        // 只取 message 事件：PTC 审计事件（ptc/call·result）不进模型历史
        val msgs = SessionEventLog.messagesOf(scan.events)
        state.cachedMessages = msgs
        state.cachedLen = backend.length(sid)   // normalizeLog 可能重写，取写后长度
        msgs
    }

    // ───────────────────────── 写路径 ─────────────────────────

    /**
     * 追加一条消息事件。首次写入会「惰性物化」：header 行 + 首个事件作为一个原子覆写落盘
     * （等价于 dsh 的「materialize 与首个 batch 原子提交」）；之后只做 append-only 追加。
     */
    fun append(sid: String, msg: ChatMessage) = lock.withLock {
        val now = nowMillis()
        val state = adopt(sid, now)
        val ev = SessionLogEvent.of(state.cursor.toLong(), msg)
        if (!state.materialized) {
            backend.write(sid, materializeText(state.meta, listOf(ev)))
            state.materialized = true
        } else {
            backend.appendLine(sid, SessionEventLog.eventLine(ev))
        }
        state.cursor += 1
        state.lastAccessAt = now
        state.cachedMessages = null   // B8：写后读缓存失效
        evictIfNeeded()
    }

    /**
     * 追加一条**任意类型**的会话事件（PTC 审计用）：seq 由协调器游标分配，与消息共用同一条日志。
     * 注意：`rebuild` 会重写整份日志（编辑/重新生成/压缩时），审计事件不参与重建、会被丢弃——
     * 需要长期留存时可后续把审计拆到独立文件。
     */
    fun appendEvent(sid: String, ev: SessionLogEvent) = lock.withLock {
        val now = nowMillis()
        val state = adopt(sid, now)
        val stamped = ev.copy(seq = state.cursor.toLong())
        if (!state.materialized) {
            backend.write(sid, materializeText(state.meta, listOf(stamped)))
            state.materialized = true
        } else {
            backend.appendLine(sid, SessionEventLog.eventLine(stamped))
        }
        state.cursor += 1
        state.lastAccessAt = now
        state.cachedMessages = null   // B8：写后读缓存失效
        evictIfNeeded()
    }

    /**
     * 全量重建会话日志（拉取服务端历史、编辑/重新生成改写历史时用）。
     * 保留既有 header 的 `createdAt`（若有），无则取当前时间。
     */
    fun rebuild(sid: String, msgs: List<ChatMessage>) = lock.withLock {
        val now = nowMillis()
        val events = msgs.mapIndexed { i, m -> SessionLogEvent.of(i.toLong(), m) }
        val createdAt = readMeta(sid)?.createdAt ?: now
        val meta = SessionLogHeader(id = sid, createdAt = createdAt, tag = tag)
        backend.write(sid, materializeText(meta, events))
        track(State(meta, events.size, events.isNotEmpty(), now))
    }

    /** 删除某会话的日志文件并丢弃其内存游标状态。 */
    fun delete(sid: String) = lock.withLock {
        states.remove(sid)
        backend.delete(sid)
    }

    /** 清空内存游标状态（配合 [com.mcp.data.LocalStore.clearAll] 的批量文件删除）。 */
    fun reset() = lock.withLock { states.clear() }

    /** 某会话日志文件的最后修改时间（Unix 秒）；不存在返回 0.0。 */
    fun lastModified(sid: String): Double = backend.lastModified(sid)

    // ───────────────────────── 内部实现 ─────────────────────────

    /** 取某会话的游标状态：内存命中则刷新访问时间；否则从磁盘扫描「采纳」重建游标。 */
    private fun adopt(sid: String, now: Long): State {
        states[sid]?.let { it.lastAccessAt = now; return it }
        val raw = backend.readRaw(sid)
        if (raw != null) {
            val scan = SessionEventLog.scan(raw)
            normalizeLog(sid, scan)
            val meta = scan.meta ?: SessionLogHeader(id = sid, createdAt = now, tag = tag)
            return State(meta, scan.events.size, scan.events.isNotEmpty(), now).also { track(it) }
        }
        // 全新会话：惰性（无文件、尚未物化），首个 append 才落盘。
        return State(SessionLogHeader(id = sid, createdAt = now, tag = tag), 0, false, now).also { track(it) }
    }

    /** 读取既有 header（仅用于 rebuild 保留 createdAt）；无则返回 null。 */
    private fun readMeta(sid: String): SessionLogHeader? =
        backend.readRaw(sid)?.let { SessionEventLog.scan(it).meta }

    /**
     * 日志自愈/迁移（读路径内完成，调用方无需再靠全量回写补课）：
     *  - torn-tail：截断崩溃半行，回写已提交前缀；
     *  - 旧格式迁移：无 header 但有事件（旧 JSON 数组 / 裸 ChatMessage JSONL）一次性重写为
     *    header+事件行。必须在此完成——后续 append 是纯追加，旧格式若不先迁移，
     *    新事件行会接在 JSON 数组结尾之后，下次扫描整体解码失败。
     */
    private fun normalizeLog(sid: String, scan: LogScan) {
        val needsRewrite = scan.tornTail || (!scan.hasHeader && scan.events.isNotEmpty())
        if (!needsRewrite) return
        val meta = scan.meta ?: SessionLogHeader(id = sid, createdAt = nowMillis(), tag = tag)
        backend.write(sid, materializeText(meta, scan.events))
    }

    /** header 行 + 连续事件行的完整 JSONL 文本（末尾补换行）。 */
    private fun materializeText(meta: SessionLogHeader, events: List<SessionLogEvent>): String =
        buildString {
            append(SessionEventLog.headerLine(meta)).append('\n')
            for (e in events) append(SessionEventLog.eventLine(e)).append('\n')
        }

    private fun track(state: State) {
        states[state.meta.id] = state
        evictIfNeeded()
    }

    /** 有界 LRU 淘汰：超过上限时剔除最久未访问的会话游标状态。 */
    private fun evictIfNeeded() {
        if (states.size <= maxStates) return
        var victim: Pair<String, State>? = null
        for ((sid, st) in states) {
            if (victim == null || st.lastAccessAt < victim!!.second.lastAccessAt) {
                victim = sid to st
            }
        }
        victim?.let { (sid, _) -> states.remove(sid) }
    }

    private fun nowMillis(): Long = System.currentTimeMillis()
}