package com.mcp

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量级任务状态持久化，用于在页面销毁/重建后恢复长时间运行的任务状态。
 * 目前主要服务于 PRoot 安装任务。
 */
object InstallTaskStore {

    private const val PREFS_NAME = "install_task_state"
    private const val KEY_IS_RUNNING = "is_running"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_STEP = "step"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_TASK_TYPE = "task_type"
    /** 运行态超过此时长视为僵尸（进程曾被系统终止，真实任务已死），读取时自动清理 */
    private const val DEFAULT_STALE_TIMEOUT_MS = 35 * 60 * 1000L

    enum class TaskType {
        PROOT_INSTALL,
        WX_PLUGIN_INSTALL
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 标记任务开始 */
    fun startTask(context: Context, type: TaskType) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_IS_RUNNING, true)
            putString(KEY_TASK_TYPE, type.name)
            putLong(KEY_STARTED_AT, System.currentTimeMillis())
            putInt(KEY_PROGRESS, 0)
            putString(KEY_STEP, "准备中…")
            apply()
        }
    }

    /** 更新进度和步骤 */
    fun updateProgress(context: Context, progress: Int, step: String) {
        getPrefs(context).edit().apply {
            putInt(KEY_PROGRESS, progress.coerceIn(0, 100))
            putString(KEY_STEP, step.take(80))
            apply()
        }
    }

    /** 标记任务完成（清除状态） */
    fun finishTask(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /** 标记任务失败（保留状态供 UI 显示，但 isRunning = false） */
    fun failTask(context: Context, reason: String) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_IS_RUNNING, false)
            putString(KEY_STEP, "失败：${reason.take(60)}")
            apply()
        }
    }

    /** 获取当前任务状态，返回 null 表示没有进行中的任务 */
    fun getTaskState(context: Context): TaskState? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_RUNNING, false)) {
            // 如果有残留的失败状态，清理掉
            val step = prefs.getString(KEY_STEP, null)
            if (step != null && step.startsWith("失败：")) {
                prefs.edit().clear().apply()
            }
            return null
        }
        return TaskState(
            type = TaskType.valueOf(prefs.getString(KEY_TASK_TYPE, TaskType.PROOT_INSTALL.name) ?: TaskType.PROOT_INSTALL.name),
            progress = prefs.getInt(KEY_PROGRESS, 0),
            step = prefs.getString(KEY_STEP, "运行中…") ?: "运行中…",
            startedAt = prefs.getLong(KEY_STARTED_AT, 0)
        )
    }

    /** 检查是否有任务在运行（不恢复详细状态） */
    fun isTaskRunning(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_IS_RUNNING, false)

    /**
     * 获取仍在时效内的运行中任务；超过 [staleTimeoutMs] 未结束视为僵尸状态
     * （进程曾被系统终止，is_running=true 残留），自动清理并返回 null。
     */
    fun getLiveTaskState(
        context: Context,
        staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS
    ): TaskState? {
        val s = getTaskState(context) ?: return null
        return if (System.currentTimeMillis() - s.startedAt <= staleTimeoutMs) s
        else {
            failTask(context, "超时中断（应用可能已被系统终止）")
            null
        }
    }

    data class TaskState(
        val type: TaskType,
        val progress: Int,
        val step: String,
        val startedAt: Long
    )
}