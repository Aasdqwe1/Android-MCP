package com.mcp

import android.content.Context
import com.mcp.serialization.McpJson
import java.io.File

/**
 * 待办任务持久化：支持会话隔离
 *
 * 主控使用 sessionId = null → 文件为 ds_todos.json
 * 子 Agent 使用 sessionId = subSessionId → 文件为 ds_todos_{subSessionId}.json
 */
object TodoStore {

    private const val FILE_PREFIX = "ds_todos"
    
    private fun getFileName(sessionId: String? = null): String =
        if (sessionId != null) "${FILE_PREFIX}_${sessionId}.json" else "${FILE_PREFIX}.json"
    
    /** 写入锁，保证多线程并发写入时不会互相覆盖 */
    private val writeLock = Any()
    
    /** 数据版本号：每次增/改/删递增，供 UI 层判断是否需要刷新。 */
    @Volatile
    var version: Long = 0
        private set
    
    private fun bump() { version++ }

    // ───────────────────── CRUD ─────────────────────

    fun load(ctx: Context, sessionId: String? = null): MutableList<TodoTask> {
        val f = File(ctx.filesDir, getFileName(sessionId))
        if (!f.exists()) return mutableListOf()
        return runCatching {
            McpJson.decodeFromString<List<TodoTask>>(f.readText()).toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun save(ctx: Context, tasks: List<TodoTask>, sessionId: String? = null) {
        synchronized(writeLock) {
            runCatching {
                File(ctx.filesDir, getFileName(sessionId)).writeText(McpJson.encodeToString(tasks))
            }
            bump()
        }
    }

    fun add(ctx: Context, title: String, status: TodoStatus = TodoStatus.PENDING, notes: String? = null, sessionId: String? = null): TodoTask {
        synchronized(writeLock) {
            val tasks = load(ctx, sessionId)
            val task = TodoTask(title = title.trim(), status = status, notes = notes)
            tasks.add(task)
            save(ctx, tasks, sessionId)
            return task
        }
    }

    /** 通过 ID 精确匹配，或标题关键词模糊匹配；返回修改后的任务，找不到返回 null。 */
    fun updateStatus(ctx: Context, idOrTitle: String, status: TodoStatus, sessionId: String? = null): TodoTask? {
        synchronized(writeLock) {
            val tasks = load(ctx, sessionId)
            val idx = tasks.indexOfFirst { it.id == idOrTitle }
                .takeIf { it >= 0 }
                ?: tasks.indexOfFirst { it.title.contains(idOrTitle, ignoreCase = true) }
                    .takeIf { it >= 0 }
                ?: return null
            val updated = tasks[idx].copy(status = status, updatedAt = System.currentTimeMillis())
            tasks[idx] = updated
            save(ctx, tasks, sessionId)
            return updated
        }
    }

    fun delete(ctx: Context, idOrTitle: String, sessionId: String? = null): Boolean {
        synchronized(writeLock) {
            val tasks = load(ctx, sessionId)
            val before = tasks.size
            tasks.removeAll { it.id == idOrTitle || it.title.equals(idOrTitle, ignoreCase = true) }
            if (tasks.size < before) { save(ctx, tasks, sessionId); return true }
            return false
        }
    }

    fun deleteAll(ctx: Context, keys: List<String>, sessionId: String? = null): Int {
        if (keys.isEmpty()) return 0
        synchronized(writeLock) {
            val tasks = load(ctx, sessionId)
            val before = tasks.size
            tasks.removeAll { task ->
                keys.any { key -> task.id == key || task.title.equals(key, ignoreCase = true) }
            }
            val deleted = before - tasks.size
            if (deleted > 0) save(ctx, tasks, sessionId)
            return deleted
        }
    }

    // ───────────────────── 批量导入 ─────────────────────

    /**
     * 从 Markdown 复选框语法批量导入任务：
     *   - [ ]  → PENDING
     *   - [x]  → DONE
     *   - [~]  → IN_PROGRESS
     *   - [!]  → FAILED
     *   - [-]  → SKIPPED
     */
    fun importFromMarkdown(ctx: Context, markdown: String, sessionId: String? = null): List<TodoTask> {
        val parsed = mutableListOf<TodoTask>()
        val regex = Regex("""^[ \t]*-[ \t]*\[([^\]]*)][ \t]+(.+)$""", RegexOption.MULTILINE)
        regex.findAll(markdown).forEach { m ->
            val box    = m.groupValues[1].trim()
            val title  = m.groupValues[2].trim().trimEnd()
            if (title.isNotEmpty()) {
                val status = TodoStatus.fromString(box)
                parsed.add(TodoTask(title = title, status = status))
            }
        }
        if (parsed.isEmpty()) return emptyList()
        val existing = load(ctx, sessionId)
        existing.addAll(parsed)
        save(ctx, existing, sessionId)
        return parsed
    }

    // ───────────────────── Markdown 渲染（供 LLM 读取）─────────────────────

    /**
     * 将所有任务渲染为 LLM 可读的 Markdown 报告。
     * @param excludeDone true 时跳过「已完成」任务（推进模式注入用，避免把已完成任务再推给 LLM）
     */
    fun toMarkdown(ctx: Context, excludeDone: Boolean = false, sessionId: String? = null): String {
        val tasks = load(ctx, sessionId).filter { !(excludeDone && it.status == TodoStatus.DONE) }
        if (tasks.isEmpty()) return "当前没有任何待办任务。"

        val grouped = tasks.groupBy { it.status }

        // 摘要行
        val summary = TodoStatus.values()
            .filter { (grouped[it]?.size ?: 0) > 0 }
            .joinToString(" | ") { "${it.label}: ${grouped[it]!!.size}" }
        val sb = StringBuilder("**待办任务** | $summary")

        // 按状态分组输出
        for (status in TodoStatus.values()) {
            val group = grouped[status] ?: continue
            sb.append("\n\n### ${status.label} (${group.size})")
            group.forEach { t ->
                sb.append("\n- ${status.mdBox} [#${t.id}] ${t.title}")
                t.notes?.let { sb.append(" — $it") }
            }
        }
        return sb.toString()
    }

    /** 简单统计：各状态数量。 */
    fun stats(ctx: Context, sessionId: String? = null): Map<TodoStatus, Int> {
        val grouped = load(ctx, sessionId).groupBy { it.status }
        return TodoStatus.values().associateWith { grouped[it]?.size ?: 0 }
    }
}
