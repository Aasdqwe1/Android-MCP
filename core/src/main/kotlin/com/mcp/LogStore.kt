package com.mcp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/** 日志级别（用于着色与过滤）。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 一条日志。
 * @param id      全局自增唯一 id（用于稳定 diff）。
 * @param time    写入时的毫秒时间戳。
 * @param tag     来源标签，如 POW / SSE / AUTH / NET / ERROR。
 * @param level   级别。
 * @param message 原始文本（SSE 场景下为全量 delta）。
 */
data class LogEntry(
    val id: Long,
    val time: Long,
    val tag: String,
    val level: LogLevel,
    val message: String
)

/**
 * 全局日志总线（进程内单例）。
 *
 * 设计目标：app 任意线程、任意位置都能 `log(...)` 一条，调用不挂起（[tryEmit]）；
 * 日志页通过 [newFlow] 实时订阅新增、通过 [trimFlow] 同步头部裁剪。环形缓冲上限
 * [MAX] 防止长时间运行后内存无限增长。
 *
 * 典型用法：
 *  - 写：`LogStore.i("POW", "算力校验中 42%")`
 *  - 读（日志页）：`launch { LogStore.newFlow.collect { ... } }`
 */
object LogStore {

    /** 环形缓冲上限（= 日志页可见行上限），超出后丢弃最旧条目（并通知日志页裁掉头部）。 */
    private const val MAX = 1000

    private val seq = AtomicLong(0)
    private val buffer = ArrayDeque<LogEntry>(MAX + 1)

    // extraBufferCapacity 让慢消费者（如未打开的日志页）不反向阻塞写方 tryEmit
    private val _new = MutableSharedFlow<LogEntry>(extraBufferCapacity = 1024)
    private val _trim = MutableSharedFlow<Int>(extraBufferCapacity = 16)

    /** 实时新增的日志条目流（replay=0，仅订阅后可见）。 */
    val newFlow: SharedFlow<LogEntry> = _new.asSharedFlow()

    /** 缓冲被裁剪掉的条目数（日志页据此移除对应数量的头部旧条目）。 */
    val trimFlow: SharedFlow<Int> = _trim.asSharedFlow()

    /** 当前缓冲的完整快照（供日志页首次打开时回灌历史）。 */
    fun snapshot(): List<LogEntry> = synchronized(buffer) { ArrayList(buffer) }

    /** 写入一条日志（线程安全，任意线程可调用，不挂起）。 */
    fun log(tag: String, level: LogLevel, message: String) {
        val e = LogEntry(seq.incrementAndGet(), System.currentTimeMillis(), tag, level, message)
        var trimmed = 0
        synchronized(buffer) {
            buffer.add(e)
            while (buffer.size > MAX) {
                buffer.removeFirst()  // ArrayDeque O(1) 头部删除
                trimmed++
            }
        }
        _new.tryEmit(e)
        if (trimmed > 0) _trim.tryEmit(trimmed)
    }

    fun d(tag: String, msg: String) = log(tag, LogLevel.DEBUG, msg)
    fun i(tag: String, msg: String) = log(tag, LogLevel.INFO, msg)
    fun w(tag: String, msg: String) = log(tag, LogLevel.WARN, msg)
    fun e(tag: String, msg: String) = log(tag, LogLevel.ERROR, msg)

    /** 清空缓冲（同时清空内存与流的历史，日志页应同步清空 UI）。 */
    fun clear() = synchronized(buffer) { buffer.clear() }
}
