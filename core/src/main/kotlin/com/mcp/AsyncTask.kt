package com.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 异步任务状态机：RUNNING 为进行中，其余为终态。 */
enum class AsyncTaskStatus { RUNNING, DONE, FAILED, CANCELLED }

/**
 * 一个异步任务（纯 JVM、无 Android 依赖）。
 *
 * 通用异步任务协议的「任务」实体：把「提交 → 轮询状态 → 取日志/结果 → 取消」从
 * Bash 后台任务中解耦，任何长任务（bash 脚本、下载、训练、构建）都可用同一套
 * [AsyncTaskManager] 跟踪。
 *
 * - 日志：默认走进程内环形缓冲 [appendLog]；runner 也可把 [logFile] 指向落地日志文件
 *   （如 Bash 把 stdout 重定向到文件），由调用方（app 层工具）按需读取文件尾部。
 * - 取消：runner 通过 handle.onCancel 注册清理回调（如 destroy 进程），[job] 负责协程取消信号。
 * - [meta]：runner 的附加元数据（如 log_file 路径），避免为每个 runner 加字段。
 */
class AsyncTask(
    val id: String,
    val type: String,
    val label: String,
    val startedAt: Long = System.currentTimeMillis()
) {
    @Volatile var status: AsyncTaskStatus = AsyncTaskStatus.RUNNING
    @Volatile var result: String? = null
    @Volatile var error: String? = null
    @Volatile var finishedAt: Long? = null

    /** 可选进度 0..100。 */
    @Volatile var progress: Int? = null

    /** runner 附加元数据（如 "log_file" → 路径）。 */
    val meta = ConcurrentHashMap<String, Any?>()

    /** 取消时的清理回调（runner 注册，如销毁底层进程）。 */
    @Volatile var onCancel: (() -> Unit)? = null

    /** 承载 runner 的协程 Job，用于协作取消。 */
    @Volatile var job: Job? = null

    private val buffer = ArrayDeque<String>()

    /** 追加一行日志到内存环形缓冲。 */
    fun appendLog(line: String) {
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LOG_LINES) buffer.removeFirst()
        }
    }

    /** 读取内存环形缓冲的最近 [n] 行。 */
    fun tailLogs(n: Int): List<String> = synchronized(buffer) {
        if (buffer.isEmpty()) emptyList() else buffer.toList().takeLast(n)
    }

    private companion object {
        /** 每个任务内存日志缓冲上限，超出丢弃最旧。 */
        const val MAX_LOG_LINES = 2000
    }
}

/**
 * 异步任务的运行时句柄：runner 用它写日志、上报进度、探测取消、注册清理回调与元数据。
 */
class AsyncTaskHandle(private val task: AsyncTask) {
    val id: String get() = task.id

    fun log(line: String) = task.appendLog(line)
    fun setProgress(percent: Int) {
        task.progress = percent.coerceIn(0, 100)
    }

    fun isCancelled(): Boolean = task.status == AsyncTaskStatus.CANCELLED

    fun onCancel(action: () -> Unit) {
        task.onCancel = action
    }

    fun meta(key: String, value: Any?) {
        task.meta[key] = value
    }
}

/**
 * 通用异步任务管理器（进程内单例）。
 *
 * 与 Bash 后台任务解耦的任务注册表 + 协程执行器：runner 是一个挂起函数，完成时把返回值
 * 写入 result、抛出异常写入 error、捕获取消写入 CANCELLED。任务超上限时自动清理已结束的旧任务。
 */
object AsyncTaskManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = ConcurrentHashMap<String, AsyncTask>()

    /** 进程内最大任务数，超出时先清理已结束任务。 */
    private const val MAX_TASKS = 500

    /** 已结束任务保留时长（毫秒），供 [cleanupFinished] 清理。 */
    private const val FINISHED_RETENTION_MS = 3_600_000L

    /**
     * 提交一个异步任务并立即返回其句柄。
     *
     * @param type  任务类型标签（如 "bash"），供 list 过滤与 runner 派发。
     * @param label 人类可读描述。
     * @param run   runner：在工作协程中执行，返回值成为 [AsyncTask.result]；抛异常进入 FAILED。
     */
    fun submit(type: String, label: String, run: suspend (AsyncTaskHandle) -> String?): AsyncTask {
        // 每次提交都清一次：既回收内存里的终态任务，也删除它们落盘的日志文件。
        // 原实现只在 tasks.size >= MAX_TASKS 时才清，且清理只删内存不删盘，
        // 任务 ID 又是随机的 → async_tasks/<id>.log 只增不减，长期可堆积数十 GB。
        cleanupFinished()
        if (tasks.size >= MAX_TASKS) cleanupFinished(maxAgeMs = 0L)
        val task = AsyncTask("task_${UUID.randomUUID().toString().take(8)}", type, label)
        tasks[task.id] = task

        task.job = scope.launch {
            val handle = AsyncTaskHandle(task)
            try {
                val result = run(handle)
                // 已被取消则不再覆盖终态（cancel 已把 status 置为 CANCELLED）
                if (task.status == AsyncTaskStatus.RUNNING) {
                    task.result = result
                    task.status = AsyncTaskStatus.DONE
                }
            } catch (e: CancellationException) {
                task.error = "任务已取消"
                task.status = AsyncTaskStatus.CANCELLED
            } catch (e: Exception) {
                task.error = e.message ?: e::class.simpleName
                task.status = AsyncTaskStatus.FAILED
            } finally {
                task.finishedAt = System.currentTimeMillis()
            }
        }
        return task
    }

    fun get(id: String): AsyncTask? = tasks[id]

    /** 列出任务，可按 [type] 过滤。 */
    fun list(type: String? = null): List<AsyncTask> =
        tasks.values.filter { type == null || it.type == type }

    /**
     * 取消任务：置 CANCELLED、调用 runner 注册的清理回调、并向协程发取消信号。
     * @return 任务存在且处于 RUNNING 时返回 true。
     */
    fun cancel(id: String): Boolean {
        val task = tasks[id] ?: return false
        if (task.status != AsyncTaskStatus.RUNNING) return false
        task.status = AsyncTaskStatus.CANCELLED
        runCatching { task.onCancel?.invoke() }
        task.job?.cancel()
        return true
    }

    /**
     * 清理已结束且过期（默认 1 小时）的任务，并删除它们落盘的日志文件。
     *
     * runner 通过 `handle.meta("log_file", ...)` 登记日志路径（如 BashAsyncRunner 的
     * async_tasks/<id>.log、gradle_build 转异步后的构建日志）。这些文件原先没有任何
     * 清理路径，任务表把条目移除后文件就成了永远无人认领的孤儿，目录只增不减。
     * 这里在移除内存条目的同时一并删除磁盘文件；RUNNING 的任务日志永不触碰。
     */
    fun cleanupFinished(maxAgeMs: Long = FINISHED_RETENTION_MS) {
        val now = System.currentTimeMillis()
        val expired = mutableListOf<AsyncTask>()
        tasks.entries.removeAll { (_, t) ->
            val e = t.status != AsyncTaskStatus.RUNNING && (now - t.startedAt) > maxAgeMs
            if (e) expired.add(t)
            e
        }
        expired.forEach { t ->
            (t.meta["log_file"] as? String)?.let { path ->
                runCatching { File(path).takeIf { it.isFile }?.delete() }
            }
        }
    }
}