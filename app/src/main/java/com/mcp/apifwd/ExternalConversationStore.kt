package com.mcp.apifwd

import com.mcp.serialization.McpJson
import java.io.File

/**
 * 外部上下文库的落盘与有界回收。
 *
 * ## 为什么必须落盘
 * 手机进程随时可能被杀。记录一丢，下一次请求就退化成**打包重放** ——
 * 在 DeepSeek 上那是一整段历史重新投喂，又慢又降质（设计文档 §7.11 / INV-B）。
 *
 * ## 为什么用独立目录
 * `Chat/ext/` 不在 LocalStore 的 tag 命名空间里（`ds` / `oa` / `wa` / `lt`），
 * 因此**天然不会出现在 App 的会话列表** —— 外部调用与用户会话彻底隔离，
 * 满足设计文档 INV-1 的一部分。
 *
 * ## 容量与 TTL 只是回收旋钮
 * 它们**不是正确性旋钮**：正确性由「前缀比对 + 失败降级」保证（见 [matchConversation]）。
 * 所以取值刻意放宽 —— 宁可多留几个记录（单条约 100B），
 * 也别因为淘汰而频繁触发有损的打包重放。
 */
class ExternalConversationStore(private val root: File) {

    companion object {
        /** 最多保留多少个外部对话。 */
        const val MAX_RECORDS = 64

        /** 记录闲置多久后回收：7 天 ——「隔天回来接着同一个外部对话」是常态。 */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** App 侧根目录约定：`<filesDir>/Chat/ext/`（与 LocalStore 的 `Chat/<tag>/` 同级）。 */
        fun defaultRoot(filesDir: File): File = File(File(filesDir, "Chat"), "ext")
    }

    private val lock = Any()
    private val cache = HashMap<String, ConversationRecord>()

    /** 读取记录；不存在或文件损坏返回 null（调用方按情形 D 处理）。 */
    fun load(convKey: String): ConversationRecord? = synchronized(lock) {
        cache[convKey]?.let { return it }
        val f = fileFor(convKey)
        if (!f.exists()) return null
        val rec = runCatching { McpJson.decodeFromString<ConversationRecord>(f.readText()) }.getOrNull()
            ?: return null
        cache[convKey] = rec
        rec
    }

    /** 写入记录（内存 + 磁盘）。 */
    fun save(record: ConversationRecord) = synchronized(lock) {
        cache[record.convKey] = record
        writeAtomic(record)
    }

    fun delete(convKey: String) = synchronized(lock) {
        cache.remove(convKey)
        fileFor(convKey).delete()
        Unit
    }

    /** 列出全部记录，按最近使用倒序。 */
    fun listAll(): List<ConversationRecord> = synchronized(lock) {
        val files = root.listFiles { f: File -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        files.mapNotNull { f ->
            val key = f.name.removeSuffix(".json")
            cache[key] ?: runCatching { McpJson.decodeFromString<ConversationRecord>(f.readText()) }
                .getOrNull()?.also { cache[key] = it }
        }.sortedByDescending { it.lastUsedAt }
    }

    /**
     * 无 id 客户端的前缀反查。
     *
     * 很多客户端**不传任何标识**（纯按 OpenAI 无状态语义每轮发全量 messages），
     * 此时"用 messages 前缀反查已有记录"是唯一能把它们认回同一个对话的手段。
     *
     * 命中条件：记录是客户端消息的前缀，且客户端恰好比它多一条 user（情形 A）；
     * 或两者完全一致（情形 B，重试）。多条命中优先取"覆盖更长"的，其次取最近使用的。
     */
    fun findByMessagePrefix(client: List<ClientTurn>): ConversationRecord? = synchronized(lock) {
        listAll()
            .mapNotNull { rec ->
                // ⚠️ 必须排除 turns 为空的记录：空 turns 是**任何请求的前缀**，
                // matchConversation 会判定为 Incremental/Idempotent，导致无关对话被误合并
                // （绑定模式的注入台账记录曾经 turns 恒为空，可稳定触发）。
                // 台账记录按**语义**排除：它是绑定模式的注入台账，不是对话记忆，从不维护 turns，
                // 而空 turns 是任何请求的前缀（会被判为 Incremental/Idempotent 而误命中）。
                // turns 为空再兜一层：结构上无依据的记录不该参与反查。
                if (rec.bound) return@mapNotNull null
                if (rec.turns.isEmpty()) return@mapNotNull null
                when (matchConversation(rec, client)) {
                    is ConvMatch.Incremental, ConvMatch.Idempotent -> rec to rec.coveredMessages
                    else -> null
                }
            }
            .sortedWith(
                compareByDescending<Pair<ConversationRecord, Int>> { it.second }
                    .thenByDescending { it.first.lastUsedAt }
            )
            .firstOrNull()?.first
    }

    /**
     * TTL + LRU 回收。
     *
     * 顺序：先清**超过 TTL** 的，再对剩下的按 LRU 淘汰超额部分。
     * 先 TTL 后 LRU 是有意的 —— 闲置过久的记录本来也该走人了，
     * 优先淘汰它们可以少动最近还在用的那些。
     *
     * @return 被删除的 conversationKey 列表（供日志与单测断言）。
     */
    fun evict(
        now: Long,
        maxRecords: Int = MAX_RECORDS,
        ttlMs: Long = TTL_MS,
    ): List<String> = synchronized(lock) {
        val all = listAll()
        val expired = all.filter { now - it.lastUsedAt > ttlMs }
        expired.forEach { delete(it.convKey) }
        val expiredKeys = expired.map { it.convKey }.toHashSet()
        val alive = all.filterNot { it.convKey in expiredKeys }
        val overflow =
            if (alive.size > maxRecords) alive.sortedBy { it.lastUsedAt }.take(alive.size - maxRecords)
            else emptyList()
        overflow.forEach { delete(it.convKey) }
        expiredKeys.toList() + overflow.map { it.convKey }
    }

    private fun fileFor(convKey: String) = File(root, convKey + ".json")

    /**
     * 原子写：先写 `.tmp` 再 rename。
     * 直接覆盖目标文件时，进程在写一半被杀会留下**半截 JSON**，
     * 下次读取解析失败 → 记录静默丢失 → 退化成打包重放。
     */
    private fun writeAtomic(record: ConversationRecord) {
        runCatching {
            root.mkdirs()
            val tmp = File(root, record.convKey + ".tmp")
            tmp.writeText(McpJson.encodeToString(record))
            val dst = fileFor(record.convKey)
            if (!tmp.renameTo(dst)) {
                // 某些文件系统上 rename 会因目标存在而失败：退回覆盖写后再删临时文件
                dst.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
