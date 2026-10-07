package com.mcp.ptc

import android.content.Context
import com.mcp.LogStore
import com.mcp.data.LocalStore
import com.mcp.deepseek.AuthPrefs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * run_code 执行期间的会话归属提示：PtcDispatchEvent 本身不带 sessionId，
 * 由 ChatBridge 在调用 run_code 前 bind、结束后 unbind，审计订阅据此把事件写进正确会话。
 */
object PtcAudit {
    @Volatile private var sid: String? = null

    fun bind(sessionId: String?) { sid = sessionId }

    fun current(): String? = sid
}

/**
 * PTC 审计落盘：把 PtcEventBus 的嵌套子调用事件写成会话日志事件（ptc/call · ptc/result）。
 *
 * - 只订阅一次（进程内多 ChatBridge 实例共享，避免重复写入）；
 * - 事件进会话日志但不进模型历史（SessionEventLog.messagesOf 只取 type=message）；
 * - 无会话归属（如工具测试直接调用）时只写进程日志。
 */
object PtcAuditSink {
    private val started = AtomicBoolean(false)
    private val seq = AtomicLong(0)

    fun ensureSubscribed(context: Context, auth: AuthPrefs) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        PtcEventBus.subscribe { e ->
            val ok = e.error == null
            LogStore.d("PTC", "[" + e.callId + "] " + e.name + (if (ok) " ✓" else " ✗ " + e.error))
            val sid = PtcAudit.current()
            if (sid.isNullOrBlank()) return@subscribe
            runCatching {
                val tag = LocalStore.backendTag(auth.getBackend())
                LocalStore.appendSessionEvent(appContext, tag, sid, e.toLogEvent(seq.incrementAndGet()))
            }.onFailure { LogStore.w("PTC", "审计落盘失败: " + it.message) }
        }
    }
}
