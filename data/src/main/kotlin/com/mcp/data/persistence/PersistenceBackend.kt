package com.mcp.data.persistence

/**
 * 会话日志的物理存储接缝（capability seam）。
 *
 * 借鉴 deepseek-harness 的 `PersistenceBackend`：协调器（[PersistenceCoordinator]）只
 * 依赖这组最小原语做缓冲/串行化/游标/采纳/崩溃修复，而不关心底层是文件、SQLite 还是
 * 对象存储。当前唯一的实现是 [JsonlPersistenceBackend]（每会话一个 append-only 文件）。
 */
interface PersistenceBackend {
    /** 读取某会话日志的原始文本；不存在返回 null。 */
    fun readRaw(sid: String): String?

    /** 原子覆写整个日志文件（materialize / rebuild / torn-tail 修复用）。 */
    fun write(sid: String, text: String)

    /** 追加一行（调用方负责行内容与换行结尾）。 */
    fun appendLine(sid: String, line: String)

    /** 删除某会话的日志文件。 */
    fun delete(sid: String)

    /** 某会话日志文件的最后修改时间（Unix 秒）；不存在返回 0.0。 */
    fun lastModified(sid: String): Double

    /** 某会话日志文件的字节长度；不存在返回 0。用于读缓存的新鲜度判定（B8）。 */
    fun length(sid: String): Long
}