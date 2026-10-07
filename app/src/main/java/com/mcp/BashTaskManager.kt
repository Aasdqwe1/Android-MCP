package com.mcp

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * 后台 Bash 任务管理器。
 *
 * 用于管理通过 run_bash_bg 启动的长期运行任务（HTTP 服务器、监听脚本等）。
 * 每个任务有独立的日志文件，支持查询状态、停止、获取日志。
 */
object BashTaskManager {

    private val tasks = ConcurrentHashMap<String, BashTask>()

    data class BashTask(
        val taskId: String,
        var pid: Int,
        val logFile: File,
        val startedAt: Long = System.currentTimeMillis(),
        var status: TaskStatus = TaskStatus.RUNNING,
        var exitCode: Int? = null
    ) {
        /** 承载任务的常驻 PRoot 进程（宿主侧），用于监控退出与停止。 */
        @Volatile var process: Process? = null
    }

    enum class TaskStatus {
        RUNNING,
        DONE,
        FAILED,
        KILLED
    }

    /** 启动一个后台任务（常驻 PRoot 进程，立即返回）。 */
    fun startTask(context: Context, script: String): BashTask {
        val taskId = "bg_${UUID.randomUUID().toString().take(8)}"
        val logDir = File(context.filesDir, "bash_tasks")
        logDir.mkdirs()
        // 顺手清理：终态超期任务 + 内存中已无对应任务的孤儿日志。
        // 原先 cleanFinished 既没有调用点、也只清内存不删盘，任务 ID 又随机，
        // 导致 bash_tasks 目录只增不减。启动新任务时兜一次，保证有写入就一定有回收。
        runCatching { cleanFinished(context) }
        val logFile = File(logDir, "$taskId.log")
        logFile.writeText("[TASK_START] $taskId\n")

        // 直接在常驻进程内前台运行用户脚本，进程本身即后台任务。
        // 不再包装 nohup + wait：--kill-on-exit 会让子进程随父进程退出被连带，
        // 而 wait 会把启动卡到 300 秒超时，两者都会让服务器类任务无法长驻。
        // 先按 root 状态选后端（root 模式 + 可用 su → 原生 chroot，否则 PRoot）。
        CapabilityRegistry.selectBackend(context)
        val process = CapabilityRegistry.subprocess.start(context, script, logFile)

        val task = BashTask(
            taskId = taskId,
            // 后台任务改由宿主侧常驻 PRoot 进程承载，guest 内已无独立可展示 pid。
            pid = -1,
            logFile = logFile
        )
        task.process = process
        tasks[taskId] = task

        // 独立线程等待进程退出并回填终态，供 bash_task_status 查询。
        Thread({
            val code = process.waitFor()
            runCatching { process.destroy() }
            tasks[taskId]?.let {
                if (it.status == TaskStatus.RUNNING) {
                    it.status = if (code == 0) TaskStatus.DONE else TaskStatus.FAILED
                    it.exitCode = code
                }
            }
            LogStore.i("BASH_TASK", "后台任务退出: $taskId code=$code")
        }, "bash-task-waiter-$taskId").start()

        LogStore.i("BASH_TASK", "启动后台任务: $taskId, pid=${task.pid}")
        return task
    }

    /** 获取任务状态 */
    fun getTask(taskId: String): BashTask? = tasks[taskId]

    /** 更新任务状态（由外部定期刷新或由 Proot 进程退出时回调） */
    fun updateStatus(taskId: String, status: TaskStatus, exitCode: Int? = null) {
        tasks[taskId]?.let {
            it.status = status
            it.exitCode = exitCode
            LogStore.i("BASH_TASK", "更新任务状态: $taskId -> $status")
        }
    }

    /** 停止任务（宿主侧终止承载它的 PRoot 进程，连带结束 guest 内服务器进程）。 */
    fun killTask(taskId: String, context: Context): Boolean {
        val task = tasks[taskId] ?: return false
        if (task.status != TaskStatus.RUNNING) return false

        // 先置终态再终止：监控线程 waitFor() 返回时看到已非 RUNNING，不会覆盖为 FAILED。
        task.status = TaskStatus.KILLED
        task.exitCode = 137
        task.process?.let { p -> runCatching { p.destroyForcibly() } }
        LogStore.i("BASH_TASK", "停止任务: $taskId")
        return true
    }

    /** 获取任务日志 */
    fun getLogs(taskId: String, lines: Int = 100): String? {
        val task = tasks[taskId] ?: return null
        if (!task.logFile.exists()) return "(日志文件不存在)"
        return try {
            task.logFile.readLines().takeLast(lines).joinToString("\n")
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /** 获取所有任务 */
    fun listTasks(): Map<String, BashTask> = tasks.toMap()

    /**
     * 清理已完成/已停止的任务（超过 1 小时）。
     *
     * 同时回收磁盘日志：一是被移除任务自己的 <id>.log，二是内存中已无对应任务的
     * 孤儿日志（App 重启后 tasks 表清空，旧日志就再也没人认领了）。
     * 正在 RUNNING 的任务日志永远不会被删。
     */
    fun cleanFinished(context: Context, maxAgeMs: Long = 3600_000L) {
        val now = System.currentTimeMillis()

        // 1) 内存条目：移除终态且超期的任务，记下其日志文件待删
        val expiredLogs = mutableListOf<File>()
        tasks.entries.removeAll { (_, task) ->
            val finished = task.status == TaskStatus.DONE ||
                task.status == TaskStatus.KILLED ||
                task.status == TaskStatus.FAILED
            val expired = finished && (now - task.startedAt) > maxAgeMs
            if (expired) expiredLogs.add(task.logFile)
            expired
        }

        // 2) 磁盘：删掉已移除任务的日志
        expiredLogs.forEach { f -> runCatching { f.delete() } }

        // 3) 磁盘：清孤儿日志（内存无对应任务，且文件本身已够旧）
        val aliveNames = tasks.values.map { it.logFile.name }.toHashSet()
        File(context.filesDir, "bash_tasks").listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".log") &&
                f.name !in aliveNames &&
                (now - f.lastModified()) > maxAgeMs
            ) {
                runCatching { f.delete() }
            }
        }

        LogStore.i("BASH_TASK", "清理过期任务完成，删除日志 ${expiredLogs.size} 个，当前存活 ${tasks.size} 个")
    }

    /** 重启任务（先停止再启动） */
    fun restartTask(taskId: String, context: Context, script: String): BashTask? {
        killTask(taskId, context)
        return startTask(context, script)
    }
}