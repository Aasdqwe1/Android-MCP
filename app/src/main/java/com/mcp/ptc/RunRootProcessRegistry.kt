package com.mcp.ptc

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * run_root 活跃进程注册表（全局静态）。
 *
 * ## 为什么需要它
 *
 * run_root 内部是宿主侧 [Process.waitFor]（阻塞、不响应协程取消）。用户点「停止」时，
 * ChatBridge 的取消链有两条，但**都到不了这个进程**：
 *  1. PtcCancellation.request() 只在 run_code 执行期间（PtcAudit.current()==sid）置位，
 *     直调 run_root 时永远为 false——分片检查读不到取消信号；
 *  2. clearPendingTools 只 cancel 了工具结果 deferred，工具本身跑在独立的
 *     toolScope.launch 里，那个协程并未被取消。
 *
 * 结果：su + 其子进程（sleep 等）在用户停止后继续跑，还拖慢后续 root 调用。
 *
 * ## 方案
 *
 * 用一个与取消机制**解耦**的注册表：executeRoot 启动 su 后把 Process 登记进来，
 * 结束时注销；stopStream 无条件调用 [killAllForcibly]，把当前所有活跃 su 进程
 * SIGKILL 掉。不依赖任何协作式标志或协程取消，因此直调 / 嵌套 / run_code 内调用
 * 全部覆盖。
 *
 * 牺牲的精确性：无法区分「哪个会话的 run_root」——停止任意会话会杀掉所有活跃
 * run_root 进程。考虑到 run_root 是宿主侧高危同步操作、并发多会话同时跑 root
 * 命令的场景极少，这个取舍是可接受的（宁可多杀，不可漏杀残留 root 进程）。
 */
object RunRootProcessRegistry {
    private const val TAG = "RUN_ROOT_REG"

    /** 活跃 su 进程表。key 用进程对象自身（identity），value 仅为占位。 */
    private val active = ConcurrentHashMap<Process, Boolean>()

    /**
     * 被 [killAllForcibly] 主动 SIGKILL 过的进程集合。
     *
     * 存在的意义：executeRoot 的等待循环原本靠「!process.isAlive」猜进程是被外部杀的
     * 还是自己结束的——但 200ms 切片边界上，进程可能在 waitFor 超时后的一瞬间正常退出，
     * 于是被误判成「用户停止」，返回假的 {"cancelled":true}。这里显式记下「我杀过」，
     * 让 executeRoot 用 wasKilledExternally() 精确判定，不再靠进程状态反推。
     */
    private val killed = java.util.Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())

    /** 登记一个刚启动的 su 进程，供全局停止时统一清理。 */
    fun register(p: Process) {
        active[p] = true
    }

    /** 注销一个已结束的 su 进程。 */
    fun unregister(p: Process) {
        active.remove(p)
        killed.remove(p)
    }

    /**
     * 该进程是否被 [killAllForcibly] 主动杀死过（读取即消费，避免集合泄漏）。
     *
     * executeRoot 只在判定「进程已不在」时调用一次；返回 true 才是真的用户停止，
     * false 表示进程自然退出（只是 waitFor 没来得及回收），应走正常退出分支读 exitValue。
     */
    fun wasKilledExternally(p: Process): Boolean = killed.remove(p)

    /**
     * 无条件 SIGKILL 掉当前所有活跃 su 进程。
     *
     * 由 ChatBridge.stopStream 在用户点停止时调用。幂等：表空时什么都不做。
     * 返回被杀的进程数，供日志与排查。
     */
    fun killAllForcibly(): Int {
        val snapshot = active.keys.toList()
        var killedCount = 0
        for (p in snapshot) {
            runCatching {
                if (p.isAlive) {
                    killed.add(p)          // 先标记「主动杀」，再发 SIGKILL
                    p.destroyForcibly()
                    killedCount++
                }
            }.onFailure { Log.w(TAG, "杀 run_root 进程失败: ${it.message}") }
            // 不从 active 移除：由 executeRoot 的 finally -> unregister 统一清理。
            // 若此处移除，executeRoot 随后仍会调 unregister(p)，其中 killed.remove(p) 会把
            // 刚标记的「主动杀」状态提前清掉，导致 wasKilledExternally() 返回 false、
            // 进程被误判为「自然退出」。
        }
        if (killedCount > 0) Log.i(TAG, "停止：已强制结束 $killedCount 个 run_root 进程")
        return killedCount
    }
}
