package com.mcp

import android.content.Context
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LLM 可调用的待办任务工具组。
 *
 * 注册后 LLM 可通过 function calling 完成：
 *  - list_todos      列出全部任务（Markdown 格式）
 *  - add_todo        添加单个任务
 *  - update_todo     更新任务状态
 *  - import_todos    从 Markdown 批量导入
 *  - delete_todo     删除指定任务
 */

fun listTodos(context: Context): ToolDef = tool("list_todos") {
    description = "列出所有待办任务（Markdown 格式），按状态分组显示 ID、标题和完成情况。用于了解当前任务列表和进度。"
    handler {
        withContext(Dispatchers.IO) { TodoStore.toMarkdown(context) }
    }
}

fun addTodo(context: Context): ToolDef = tool("add_todo") {
    description = "添加一个新的待办任务，返回新建任务的 ID。"
    string("title") {
        description = "任务标题"
    }
    string("status") {
        description = "初始状态。可选：pending（未完成，默认）、in_progress（进行中）、done（已完成）、failed（失败）、skipped（跳过）"
        required = false
    }
    string("notes") {
        description = "备注说明（可选）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val title  = args.requireStr("title", "add_todo")
                val status = TodoStatus.fromString(args["status"]?.jsonPrimitive?.content ?: "pending")
                val notes  = args["notes"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                val task = TodoStore.add(context, title, status, notes)
                """{"ok":true,"id":"${task.id}","title":"${task.title}","status":"${task.status.label}"}"""
            }.getOrElse { e -> """{"error":"添加失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

fun updateTodo(context: Context): ToolDef = tool("update_todo") {
    description = "更新待办任务的状态。可用任务 ID（#开头）或任务标题关键词定位。"
    string("id_or_title") {
        description = "任务 ID（如 #abc12345）或任务标题关键词（模糊匹配）"
    }
    string("status") {
        description = "新状态：pending（未完成）、in_progress（进行中）、done（已完成）、failed（失败）、skipped（跳过）"
        enumValues = listOf("pending", "in_progress", "done", "failed", "skipped")
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val key    = args.requireStr("id_or_title", "update_todo").trimStart('#')
                val status = TodoStatus.fromString(args.requireStr("status", "update_todo"))
                val task = TodoStore.updateStatus(context, key, status)
                if (task != null)
                    """{"ok":true,"id":"${task.id}","title":"${task.title}","status":"${task.status.label}"}"""
                else
                    """{"ok":false,"error":"未找到任务：$key"}"""
            }.getOrElse { e -> """{"error":"更新失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

fun importTodos(context: Context): ToolDef = tool("import_todos") {
    description = """从 Markdown 格式批量导入任务。
复选框格式：
  - [ ]  → 未完成
  - [x]  → 已完成
  - [~]  → 进行中
  - [!]  → 失败
  - [-]  → 跳过
示例：
  - [x] 完成季度汇报 PPT
  - [ ] 阅读第3章
  - [~] 整理桌面文件"""
    string("markdown") {
        description = "包含任务列表的 Markdown 文本"
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val md = args.requireStr("markdown", "import_todos")
                val added = TodoStore.importFromMarkdown(context, md)
                val titles = added.take(5).joinToString("、") { it.title }
                val more = if (added.size > 5) "…等 ${added.size} 项" else "${added.size} 项"
                """{"ok":true,"added":${added.size},"preview":"$titles ($more)"}"""
            }.getOrElse { e -> """{"error":"导入失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

fun deleteTodo(context: Context): ToolDef = tool("delete_todo") {
    description = "删除指定的待办任务。支持单个 ID/标题，也支持用逗号、空格或换行分隔的多个 ID/标题，批量删除。"
    string("id_or_title") {
        description = "任务 ID（如 #abc12345）或任务完整标题。多个 ID 用逗号、空格或换行分隔，如 '#abc,#def,#ghi' 或 '#abc #def #ghi'"
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = args.requireStr("id_or_title", "delete_todo")
                val keys = raw.split(Regex("[,、\\s\\n]+")).map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }
                
                if (keys.isEmpty()) {
                    return@runCatching """{"ok":true,"deleted":0,"message":"没有传入任何 ID"}"""
                }
                
                if (keys.size == 1) {
                    val ok = TodoStore.delete(context, keys[0])
                    if (ok) """{"ok":true,"deleted":1}""" else """{"ok":false,"error":"未找到任务：${keys[0]}"}"""
                } else {
                    val deleted = TodoStore.deleteAll(context, keys)
                    """{"ok":true,"deleted":$deleted,"requested":${keys.size}}"""
                }
            }.getOrElse { e -> """{"error":"删除失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

