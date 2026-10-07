package com.mcp

import com.mcp.core.llm.BackendType
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局流式任务管理器。
 *
 * 解决退出会话窗口后，正在进行的 SSE 流丢失引用导致：
 * - 重新进入看不到实时进度
 * - 停止按钮失效
 *
 * ChatBridge 在启动流时注册任务到 Manager，退出时不解注册（让流继续跑），
 * 重新进入时从 Manager 恢复任务状态并重新绑定 UI。
 */
object StreamTaskManager {

    private val activeTasks = ConcurrentHashMap<String, StreamTask>()

    data class StreamTask(
        val sessionId: String,
        val messageId: String,          // 当前正在生成的 message_id
        val job: Job,                   // 协程 Job，用于取消
        val backend: BackendType,
        var lastContent: String = "",   // 已生成的内容（用于恢复时追显）
        var lastThinking: String = "",  // 已生成的思考内容（用于恢复时追显）
        var isBusy: Boolean = true,     // 是否还在生成中
        var isStopping: Boolean = false,
        var startedAt: Long = System.currentTimeMillis()
    )

    /** 注册一个流任务 */
    fun register(task: StreamTask) {
        activeTasks[task.sessionId] = task
        LogStore.i("STREAM_TASK", "注册流任务 session=${task.sessionId} msgId=${task.messageId}")
    }

    /** 获取指定会话的流任务（如果有） */
    fun get(sessionId: String): StreamTask? = activeTasks[sessionId]

    /** 检查指定会话是否有活跃流 */
    fun hasActiveTask(sessionId: String): Boolean = activeTasks.containsKey(sessionId)

    /** 更新流内容（供 SSE 回调使用） */
    fun updateContent(sessionId: String, content: String) {
        activeTasks[sessionId]?.let {
            it.lastContent = content
        }
    }

    /** 更新流思考内容（供 SSE 回调使用） */
    fun updateThinking(sessionId: String, thinking: String) {
        activeTasks[sessionId]?.let {
            it.lastThinking = thinking
        }
    }

    /** 标记任务为完成（从 Manager 移除） */
    fun complete(sessionId: String) {
        activeTasks.remove(sessionId)
        LogStore.i("STREAM_TASK", "完成/移除流任务 session=$sessionId")
    }

    /** 停止指定会话的流（取消协程） */
    fun stop(sessionId: String): Boolean {
        val task = activeTasks[sessionId] ?: return false
        task.isStopping = true
        task.job.cancel()
        activeTasks.remove(sessionId)
        LogStore.i("STREAM_TASK", "停止流任务 session=$sessionId")
        return true
    }

    /** 获取所有活跃任务（用于调试） */
    fun listActive(): Map<String, StreamTask> = activeTasks.toMap()

    /** 清空所有任务（仅用于测试/重置） */
    fun clearAll() {
        activeTasks.values.forEach { it.job.cancel() }
        activeTasks.clear()
        LogStore.i("STREAM_TASK", "清空所有流任务")
    }
}