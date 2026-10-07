package com.mcp.ptc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * 用户输入等待时钟。
 *
 * ask_user 挂起等待用户回答的时长**不应**计入调用方的超时预算：用户思考几分钟是正常交互，
 * 不是「工具执行超时」。原先 run_code 外层的 withTimeoutOrNull 到点即返回 null，调用方看到
 * 「工具执行超时（run_code 超过 N 秒）」——而此时用户往往刚点完提交，回答被丢弃，
 * 表现为「明明回答了却显示超时」。
 *
 * 这里只做一件事：记录**进程内**所有 ask_user 等待的累计时长（单调递增）。
 * [withUserWaitAwareTimeout] 取「开始时的值」与「当前值」之差，从墙上时钟里扣除，
 * 只对真正的执行时间计时。
 *
 * 为什么是全局累计而不是按会话/按 run_code 记账：ask_user 的处理器是进程级注册表，
 * 等待发生在哪个 run_code 之下并不携带标识；全局累计 + 取差值的组合对「本次调用期间
 * 产生的等待」已经足够准确，且历史累计会被差值抵消，不会让后续调用无限续命。
 */
object UserInputClock {
    private val lock = Any()

    /** 已结束的等待累计毫秒（单调递增）。 */
    private var accumulatedMs = 0L

    /** 仍在等待中的各次开始时刻。 */
    private val activeStarts = ArrayList<Long>()

    fun beginWait() {
        synchronized(lock) { activeStarts.add(System.currentTimeMillis()) }
    }

    fun endWait() {
        synchronized(lock) {
            if (activeStarts.isEmpty()) return
            val start = activeStarts.removeAt(activeStarts.size - 1)
            accumulatedMs += (System.currentTimeMillis() - start).coerceAtLeast(0L)
        }
    }

    /** 累计用户等待毫秒（含正在进行的等待）。单调递增，调用方应取差值使用。 */
    fun totalPausedMs(): Long = synchronized(lock) {
        val now = System.currentTimeMillis()
        var total = accumulatedMs
        for (i in activeStarts.indices) total += (now - activeStarts[i]).coerceAtLeast(0L)
        total
    }

    /** 测试/重置用：清空累计与活跃等待。 */
    fun reset() = synchronized(lock) {
        accumulatedMs = 0L
        activeStarts.clear()
    }
}

/** 轮询时间片：每片重算一次「净执行时长」，兼顾精度与开销。 */
private const val USER_WAIT_POLL_MS = 200L

/**
 * 用户等待感知的超时包装：语义与 withTimeoutOrNull 一致，唯一差别是
 * ask_user 等待用户回答的时长不计入 [timeoutMs] 预算。
 *
 * 实现：block 放进独立 job，按 [USER_WAIT_POLL_MS] 时间片轮询；每片重算
 * `墙上时钟 - 用户等待时长`，只有该净值达到 [timeoutMs] 才取消并返回 null。
 *
 * job 用 SupervisorJob 且不挂在调用方 job 之下，避免「block 卡在不可取消的阻塞调用里」
 * 时把调用方一起拖住（与原先 withTimeoutOrNull「到点即返回、不等收尾」的语义对齐）；
 * 调用方被取消时由 finally 兜底取消 job。
 */
suspend fun <T> withUserWaitAwareTimeout(
    timeoutMs: Long,
    block: suspend CoroutineScope.() -> T
): T? {
    val dispatcher = coroutineContext[ContinuationInterceptor] ?: Dispatchers.IO
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val job = scope.async { block() }
    val pausedAtStart = UserInputClock.totalPausedMs()
    val startedAt = System.currentTimeMillis()
    try {
        while (true) {
            if (withTimeoutOrNull(USER_WAIT_POLL_MS) { job.join(); true } == true) break
            val netMs = System.currentTimeMillis() - startedAt -
                (UserInputClock.totalPausedMs() - pausedAtStart)
            if (netMs >= timeoutMs) {
                job.cancel()
                return null
            }
        }
        return job.await()
    } finally {
        scope.cancel()
    }
}
