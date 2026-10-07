package com.mcp.apifwd

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * `/v1` 的并发闸门。
 *
 * ## 为什么要按对话互斥
 * 同一对话的两个并发请求会同时读到同一个锚点、各自发出请求，
 * 而服务端返回的 MessageId 会把锚点**交替覆盖** —— 结果是服务端消息树分叉、
 * 两边都丢历史。串行化是按对话粒度的最小必要保护。
 *
 * ## 为什么还要全局上限
 * 外部客户端可能开几十路并发。上游烧的是**用户自己的额度**，
 * 无上限会让一次误配置直接打空配额，也把手机网络打满。
 *
 * ## 已知不足：这不是"在途 join"
 * 设计文档 §7.10 的决策是「同 key + 同 turn 指纹正在跑 → join（同一个流喂给两个请求）」。
 * 本实现只做到**串行化**：第二个请求排队，等第一个跑完后按"已完成"走重新生成路径。
 * 好处是**正确性**（不撕裂锚点、不并发跑两次），代价是超时重试仍会多花一次生成。
 * 真正的 join 需要跨请求共享一个上游流与事件缓冲，留待后续。
 */
internal object CompatConcurrency {

    /** 同时进行的 /v1 请求上限。 */
    const val MAX_CONCURRENT = 8

    /** 取全局槽位的等待上限：超过就回 429，让客户端自己退避。 */
    private const val ACQUIRE_TIMEOUT_SEC = 5L

    private val global = Semaphore(MAX_CONCURRENT, true)
    private val perKey = ConcurrentHashMap<String, Semaphore>()

    private fun lockFor(convKey: String): Semaphore =
        perKey.computeIfAbsent(convKey) { Semaphore(1, true) }

    /**
     * 在闸门内执行 [block]。
     *
     * @return null 表示**全局并发已满**，调用方应回 429 + `Retry-After`。
     */
    fun <T> run(convKey: String, block: () -> T): T? {
        if (!global.tryAcquire(ACQUIRE_TIMEOUT_SEC, TimeUnit.SECONDS)) return null
        try {
            val lock = lockFor(convKey)
            lock.acquire()
            try {
                return block()
            } finally {
                lock.release()
            }
        } finally {
            global.release()
        }
    }
}
