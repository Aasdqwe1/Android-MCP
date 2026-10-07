package com.mcp

import java.util.UUID
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

@Serializable
data class TodoTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String = "",
    val status: TodoStatus = TodoStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val notes: String? = null
)

@Serializable
enum class TodoStatus(
    val label: String,
    /** Markdown 复选框语法 + UI 显示符号 */
    val mdBox: String
) {
    PENDING    ("待办",   "[ ]"),
    IN_PROGRESS("进行中", "[~]"),
    DONE       ("已完成", "[x]"),
    FAILED     ("失败",   "[!]"),
    SKIPPED    ("跳过",   "[-]");

    /** 下一个可切换状态（UI 点击循环） */
    fun next(): TodoStatus = when (this) {
        PENDING     -> IN_PROGRESS
        IN_PROGRESS -> DONE
        DONE        -> SKIPPED
        FAILED      -> PENDING
        SKIPPED     -> PENDING
    }

    companion object {
        fun fromString(s: String): TodoStatus = when (s.trim().lowercase()) {
            "done", "completed", "finish", "已完成", "完成", "x" -> DONE
            "in_progress", "inprogress", "doing", "进行中", "~", "/" -> IN_PROGRESS
            "failed", "fail", "失败", "!" -> FAILED
            "skipped", "skip", "跳过", "-" -> SKIPPED
            else -> PENDING
        }
    }
}
