package com.mcp.compaction

import com.mcp.llm.ChatMessage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 上下文压缩事务——对齐 deepseek-harness `compactSurfaceRegion` 的事务语义。
 *
 * 单独成类的原因：压缩**不是**一个纯函数。它中间要异步调用 LLM 生成摘要（秒级），
 * 而这段时间内上下文会继续变化（用户发新消息、工具回结果）。若直接「选范围 → 摘要 →
 * 替换」，摘要回来时拿的是旧切片，替换会把期间新增的内容一起抹掉。
 *
 * 因此必须按 dsh 的事务形态封装：
 *
 * ```
 * lock（防止并发双压）
 *   ├─ 选范围（只读）
 *   ├─ 异步摘要           ← 上下文在此窗口内可能变化
 *   ├─ 稳定性复检          ← 范围被改写则抛 SurfaceChangedException 放弃本轮
 *   ├─ 收缩验证            ← 摘要没变小则拒绝提交
 *   ├─ 提交：replace 而非 append（用**最新**尾部，不是摘要前的旧尾部）
 *   └─ unlock（finally，任何失败都必须释放）
 * ```
 *
 * 与 dsh 的对应：
 * | dsh                              | 本类                          |
 * |----------------------------------|-------------------------------|
 * | `compaction/start` 未闭合 = 锁    | [inProgress] CAS              |
 * | `assertSelectedSpanStable`       | [ContextCompactor.assertSpanStable] |
 * | shrink 检查                       | [ContextCompactor.validateShrink]   |
 * | `surfaceOp: replace(start, end)` | 用最新尾部重建列表            |
 * | `compaction/end`（失败也写）      | `finally { unlock }`          |
 *
 * 纯 Kotlin 协程，不依赖 Android / LLM 客户端：[summarize] 由调用方注入。
 */
class CompactionTransaction(
    private val compactor: ContextCompactor = ContextCompactor()
) {

    private val inProgress = AtomicBoolean(false)

    /** 压缩锁是否已被占用（供 UI 禁用重复触发）。 */
    val isBusy: Boolean get() = inProgress.get()

    /** 并发触发压缩（对齐 dsh `ManualCompactionError('busy')`）。 */
    class BusyException(message: String) : Exception(message)

    /** 一次成功压缩的结果。 */
    data class Outcome(
        /** 压缩后的完整消息列表（system + 检查点 + 保留尾部）。 */
        val messages: List<ChatMessage>,
        /** 被替换掉的消息条数。 */
        val shadowedCount: Int,
        /** 被替换内容的估算 token。 */
        val shadowedTokens: Int,
        /** 检查点的估算 token（必然 < shadowedTokens）。 */
        val summaryTokens: Int
    )

    /**
     * 执行完整压缩事务。
     *
     * @param snapshot 读取**当前**消息列表；事务内会调用两次（选范围前、摘要后复检）。
     *                 必须返回最新状态，返回缓存快照会让稳定性校验形同虚设。
     * @param apply    提交压缩结果（调用方负责写回会话状态并持久化）。
     * @param summarize 摘要生成：接收「被替换范围的原消息」与「会话 system 消息（可为 null）」，
     *                  返回摘要文本。返回空白视为摘要失败。
     *                  **system 必须一并透传**：对齐 dsh `SummarizationInput`，摘要请求要重放
     *                  会话自己的 system + 被阴影消息原文，使辅助请求成为上次路由请求的前缀，
     *                  从而复用 provider 的 KV cache（dsh summarizer.ts:24-30）。
     * @param retainTokens 保留最近尾部的 token 预算，见 [ContextCompactor.retainTokensFor]。
     * @param minRetain 最少保留条数地板，见 [ContextCompactor.selectRetainedTail]。
     * @param requireShrink 是否强制「摘要必须比被替换内容小」。
     *        常规压缩为 true（不收缩就是白干）；**溢出恢复必须为 false**——
     *        此时目标是「无论代价先逃出溢出」，摘要即使没变小也要替换。
     * @return 成功返回 [Outcome]；无可安全压缩范围或摘要为空白时返回 null（未修改任何状态）。
     * @throws BusyException 已有压缩在进行中。
     * @throws SurfaceChangedException 摘要期间被选中范围已变化——正常竞态，调用方应静默跳过。
     * @throws IllegalArgumentException 摘要未收缩（仅 [requireShrink] 为 true 时）。
     */
    suspend fun run(
        snapshot: suspend () -> List<ChatMessage>,
        apply: suspend (List<ChatMessage>) -> Unit,
        summarize: suspend (shadowed: List<ChatMessage>, system: ChatMessage?) -> String,
        retainTokens: Int,
        minRetain: Int = compactor.minRetainMessages,
        requireShrink: Boolean = true
    ): Outcome? {
        if (!inProgress.compareAndSet(false, true)) {
            throw BusyException("压缩已在进行中：会话压缩锁已激活")
        }
        return try {
            runLocked(snapshot, apply, summarize, retainTokens, minRetain, requireShrink)
        } finally {
            // 对齐 dsh：失败也要写 compaction/end，锁必须释放，否则后续压缩永久 busy。
            inProgress.set(false)
        }
    }

    private suspend fun runLocked(
        snapshot: suspend () -> List<ChatMessage>,
        apply: suspend (List<ChatMessage>) -> Unit,
        summarize: suspend (shadowed: List<ChatMessage>, system: ChatMessage?) -> String,
        retainTokens: Int,
        minRetain: Int,
        requireShrink: Boolean
    ): Outcome? {
        // ── 1) 选范围（只读快照） ────────────────────────────────
        val before = snapshot()
        val (systemBefore, nonSystemBefore) = splitSystem(before)
        val selection = compactor.selectRetainedTail(nonSystemBefore, retainTokens, minRetain)
            ?: return null

        // ── 2) 异步摘要（此窗口内上下文可能变化） ──────────────────
        // system 一并透传，供摘要请求做 prefix-cache 对齐（对齐 dsh SummarizationInput）。
        val summary = summarize(selection.shadowed, systemBefore)
        if (summary.isBlank()) return null

        // ── 3) 稳定性复检：回到最新列表，按区间重新定位 ────────────
        val current = snapshot()
        val (systemNow, nonSystemNow) = splitSystem(current)
        compactor.assertSpanStable(nonSystemNow, selection)

        // ── 4) 收缩验证：摘要必须确实更小（溢出恢复跳过，目标是逃出溢出而非"更优"） ──
        if (requireShrink) compactor.validateShrink(summary, selection.shadowedTokens)

        // ── 5) 提交：replace 语义，尾部取**当前**的（可能已增长） ────
        val retainedNow = nonSystemNow.subList(
            selection.shadowedRange.last + 1,
            nonSystemNow.size
        )
        val result = compactor.buildCheckpoint(systemNow ?: systemBefore, summary, retainedNow)
        apply(result)

        return Outcome(
            messages = result,
            shadowedCount = selection.shadowed.size,
            shadowedTokens = selection.shadowedTokens,
            summaryTokens = compactor.checkpointTokens(summary)
        )
    }

    /**
     * 分离 system 头与对话体，语义与 ChatBridge 原实现一致：
     * header 取**首条** system，对话体移除**全部** system 消息。
     *
     * 刻意用 `filter` 而非 `drop(1)`：一是要移除全部 system（原实现即 `filter`），
     * 二是 `drop` 在部分 Kotlin 版本对 MutableList 会走原地删除的优化分支，
     * 在事务里改动传入列表是不可接受的风险；`filter` 恒返回新列表。
     */
    private fun splitSystem(messages: List<ChatMessage>): Pair<ChatMessage?, List<ChatMessage>> {
        val system = messages.firstOrNull { it.role == "system" }
        val body = if (system == null) messages else messages.filter { it.role != "system" }
        return system to body
    }
}
