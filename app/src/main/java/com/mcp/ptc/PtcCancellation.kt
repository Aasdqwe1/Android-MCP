package com.mcp.ptc

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * run_code 的协作式取消标志（A2）。
 *
 * Rhino 同步执行无法从外部强杀，故用「指令观察者 + 取消标志」：用户点「停止」时置位，
 * JS 引擎每执行若干条指令检查一次并抛出 [PtcCancelledException]，让死循环/长任务真正可中断。
 *
 * ## 按 sessionId 分桶
 *
 * 早期实现是单一全局 AtomicBoolean：会话 A 停止会置位全局标志，导致会话 B 正在执行的
 * run_code 也在下一个检查点被误杀（多会话并发时互相误伤）。现改为 sid -> 标志 的映射，
 * request/clear 指定 sid；无 sid 上下文的老调用点（Rhino 指令观察者、PRoot waitFor 分片）
 * 仍可用无参重载，此时按「任一 sid 被请求」判定——见 isRequested。
 */
object PtcCancellation {
    /** sid -> 取消标志。活跃会话量级很小，无需清理策略。 */
    private val flags = ConcurrentHashMap<String, AtomicBoolean>()

    /** 无 sid 上下文的兜底标志。 */
    private val globalFlag = AtomicBoolean(false)

    /** 请求取消指定会话的 run_code。sid 为空时置全局兜底标志。 */
    fun request(sid: String?) {
        if (sid.isNullOrEmpty()) {
            globalFlag.set(true)
            return
        }
        flags.computeIfAbsent(sid) { AtomicBoolean(false) }.set(true)
    }

    /** 兼容旧调用点：不指定 sid 时置全局兜底标志。 */
    fun request() = request(null)

    /** 清除指定会话的取消标志（新一轮 run_code 开始时调用）。 */
    fun clear(sid: String?) {
        if (sid.isNullOrEmpty()) {
            globalFlag.set(false)
            return
        }
        // 关键修复：新一轮 run_code 启动时必须**同时**清掉全局兜底标志。
        // 否则一旦 globalFlag 被任何无 sid 调用点（Rhino 观察者 / PRoot 分片 / 历史遗留）
        // 置位，之后即使会话自带 sid，isRequested(sid) 里的 `return globalFlag.get()`
        // 也恒为 true —— 所有后续 run_code 一启动就被判「已取消」，
        // 表现为「用户没点停止却报取消」。
        globalFlag.set(false)
        // 桶不存在时也建出来并复位，确保「clear 后=未取消」语义稳定
        // （原 `flags[sid]?.set(false)` 的 ?. 会跳过创建）。
        flags.computeIfAbsent(sid) { AtomicBoolean(false) }.set(false)
    }

    /** 兼容旧调用点：清除全部会话标志 + 全局兜底标志。 */
    fun clear() {
        globalFlag.set(false)
        for (f in flags.values) f.set(false)
    }

    /**
     * 指定会话是否被请求取消。
     *
     * 语义边界：**有明确 sid 时只看本会话桶，不看 globalFlag**。
     * globalFlag 是「无 sid 上下文」的兜底；一旦让带 sid 的查询也吃它，
     * 任何一次无参 request() 都会永久毒化全部会话（见 clear 的修复注释）。
     */
    fun isRequested(sid: String?): Boolean {
        if (sid == null) return globalFlag.get()
        return flags[sid]?.get() == true
    }

    /**
     * 无 sid 上下文的取消判定：**只看全局兜底标志**。
     *
     * ⚠️ 关键修复（跨会话误杀）：原实现遍历 `flags.values`，只要**任一**历史会话桶残留
     * true 就返回 true。Rhino 指令观察者拿不到 sid、只能调本重载 —— 于是：
     *   用户曾在会话 A 点过停止 → flags[A]=true 残留 → 之后任何会话（含新会话/子 Agent）
     *   跑 run_code，观察者在下一个检查点就抛「已取消」，**用户根本没点停止**。
     * 去掉遍历后，无 sid 路径不再被别的会话毒化；带 sid 的精确取消走带参重载。
     *
     * 真正需要「跨会话中断」的场景由调用方优先尝试 [PtcAudit.current] 拿 sid 后调带参
     * 重载；拿不到时才落到这里（仅全局）。
     */
    fun isRequested(): Boolean = globalFlag.get()
}

/** 协作式取消异常：由 Rhino 指令观察者抛出，JsEngine 捕获后转成可读错误。 */
class PtcCancelledException : RuntimeException("run_code 已取消（用户停止生成）")

/**
 * run_root 阻塞等待的轮询时间片（毫秒）。
 *
 * run_root 内部是宿主侧 Process.waitFor（阻塞、不响应协程取消），而 JS 线程此时
 * 正阻塞在子工具调用上，Rhino 指令观察者跑不到检查点——PtcCancellation 标志虽置位
 * 也无人读取，用户点「停止」对 su 命令完全无效，su/sleep 残留进程继续跑。
 *
 * 因此 executeRoot 改为按本时间片分片 waitFor，每片醒来先查 PtcCancellation.isRequested，
 * 命中即 SIGKILL 掉 su 并返回「已取消」。取值与 USER_WAIT_POLL_MS、ChatBridge 的
 * PTC_ASK_POLL_MS 同量级：足够及时，又不会把等待变成忙轮询。
 */
const val ROOT_WAIT_SLICE_MS = 200L