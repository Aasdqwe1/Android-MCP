package com.mcp

import android.content.Context
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 通用异步任务协议的 Bash runner：把「在 PRoot 环境后台执行脚本」接入 [AsyncTaskManager]。
 *
 * 这是 async_task_* 协议的第一个 runner 实现；未来 download / train / build 等长任务
 * 只需按同样的 [AsyncTaskHandle] 契约注册新 runner，即可复用同一套提交/轮询/取日志/取消工具。
 */
object BashAsyncRunner {

    /**
     * 后台执行 [script]（常驻 PRoot 进程，立即返回），并把 stdout/stderr 落到日志文件。
     * 返回结果字符串（exit_code=N）写入任务 result。
     */
    suspend fun run(context: Context, handle: AsyncTaskHandle, script: String): String {
        val logDir = File(context.filesDir, "async_tasks")
        logDir.mkdirs()
        val logFile = File(logDir, "${handle.id}.log")
        logFile.writeText("[TASK_START] ${handle.id}\n")
        handle.meta("log_file", logFile.absolutePath)

        // 只注入 set -o pipefail（不注入 set -e）：管道失败时退出码正确传播（如 `cmd | tail`
        // 的 cmd 失败时退出码 = cmd 的而非 tail 的 0），但不中途打断脚本（grep/test 等非 0 中间命令不受影响）。
        val safeScript = "set -o pipefail\n$script"
        // 按 root 状态选后端（root 模式 + 可用 su → 原生 chroot，否则 PRoot）
        CapabilityRegistry.selectBackend(context)
        val process = CapabilityRegistry.subprocess.start(context, safeScript, logFile)
        handle.onCancel { runCatching { process.destroyForcibly() } }

        val exitCode = try {
            while (true) {
                // 有界等待：既能在退出后快速返回，又能每 800ms 检查一次取消标志
                val exited = process.waitFor(800, TimeUnit.MILLISECONDS)
                if (handle.isCancelled()) {
                    runCatching { process.destroyForcibly() }
                    return "cancelled"
                }
                if (exited) break
            }
            process.exitValue()
        } finally {
            runCatching { process.destroy() }
        }
        // exit_code≠0 抛异常 → AsyncTaskManager 标记 FAILED（而非 DONE），调用方看 status 即知成败
        if (exitCode != 0) throw IllegalStateException("exit_code=$exitCode")
        return "exit_code=$exitCode"
    }
}

/** 通用异步任务协议 —— 提交任务。 */
fun asyncTaskSubmit(context: Context): ToolDef = tool("async_task_submit") {
    description = """
        【一次性长任务专用】提交异步长任务并立即返回 task_id（`task_` 前缀），不阻塞。任务有明确终态：DONE / FAILED / CANCELLED。
        适合：长耗时的一次性任务——Gradle 全量构建/测试（预计 > 20 分钟）、大文件下载、模型训练、批量处理、需要与别的任务并行、需要随时中断的任务。
        无超时限制，可运行数小时甚至数天。
        短命令（< 5 分钟，如 ls/grep/git clone）请用 run_bash（同步等结果，省一次轮询）。
        Gradle 短构建（≤ 20 分钟且要立即拿结果）请用 gradle_build（同步阻塞 + 结构化摘要）。
        跨调用常驻的服务进程（HTTP/SMB/API 服务器）请用 run_bash_bg（无终态，长期持有）。
        流程：提交 → 轮询 async_task_status → 需要时取 async_task_logs → 需要中断时 async_task_cancel（置 CANCELLED + destroyForcibly 底层进程）。
        当前支持 type=bash（在 PRoot 环境后台执行脚本），日志落 `filesDir/async_tasks/<id>.log`。
        脚本自动注入 `set -o pipefail`：管道失败时退出码正确传播（无需手写），exit_code≠0 时任务标记 FAILED（而非 DONE）。不注入 `set -e`——脚本中 grep/test 等非 0 中间命令不会被中途打断。
    """.trimIndent()
    string("type") {
        description = "任务类型，当前支持 bash（默认）"
        required = false
    }
    string("label") {
        description = "任务描述（可选，便于在 list 中识别）"
        required = false
    }
    string("script") {
        description = "任务负载：type=bash 时为要在 PRoot 环境执行的 Bash 脚本"
        multiLine = true
    }
    handler { args ->
        runCatching {
            val type = args["type"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: "bash"
            val label = args["label"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: type
            val script = args.requireStr("script", "async_task_submit")

            when (type) {
                "bash" -> Unit // 已支持
                else -> return@runCatching """{"error":"不支持的任务类型: $type（当前支持：bash）"}"""
            }

            val runner: suspend (AsyncTaskHandle) -> String? = { h ->
                BashAsyncRunner.run(context, h, script)
            }
            val task = AsyncTaskManager.submit(type, label, runner)
            buildJsonObject {
                put("task_id", task.id)
                put("type", task.type)
                put("label", task.label)
                put("status", task.status.name)
                put("started_at", task.startedAt)
            }.toString()
        }.getOrElse { e ->
            """{"error":"提交任务失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
        }
    }
}

/** 通用异步任务协议 —— 查询状态。 */
fun asyncTaskStatus(context: Context): ToolDef = tool("async_task_status") {
    description = "查询异步任务状态：返回是否运行、类型、结果/错误、进度、时间戳、日志文件路径。"
    string("task_id") { description = "任务 ID（由 async_task_submit 返回）" }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val taskId = args.requireStr("task_id", "async_task_status")
                val task = AsyncTaskManager.get(taskId)
                if (task == null) {
                    """{"error":"任务不存在: $taskId"}"""
                } else {
                    buildJsonObject {
                        put("task_id", task.id)
                        put("type", task.type)
                        put("label", task.label)
                        put("status", task.status.name)
                        put("started_at", task.startedAt)
                        if (task.finishedAt != null) put("finished_at", task.finishedAt)
                        if (task.progress != null) put("progress", task.progress)
                        if (task.result != null) put("result", task.result)
                        if (task.error != null) put("error", task.error)
                        val logFile = task.meta["log_file"] as? String
                        if (logFile != null) {
                            put("log_file", logFile)
                            put("log_exists", File(logFile).exists())
                        }
                    }.toString()
                }
            }.getOrElse { e ->
                """{"error":"查询失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** 通用异步任务协议 —— 取日志。 */
fun asyncTaskLogs(context: Context): ToolDef = tool("async_task_logs") {
    description = "获取异步任务日志：runner 落到日志文件时读文件尾部，否则读内存环形缓冲。默认最近 100 行。"
    string("task_id") { description = "任务 ID（由 async_task_submit 返回）" }
    number("lines") {
        description = "返回行数，默认 100，最大 500"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val taskId = args.requireStr("task_id", "async_task_logs")
                val lines = args["lines"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val task = AsyncTaskManager.get(taskId)
                if (task == null) {
                    """{"error":"任务不存在: $taskId"}"""
                } else {
                    val logFile = task.meta["log_file"] as? String
                    val text = if (logFile != null) {
                        val f = File(logFile)
                        if (!f.exists()) "(日志文件不存在)"
                        else f.readLines().takeLast(lines).joinToString("\n")
                    } else {
                        task.tailLogs(lines).joinToString("\n")
                    }
                    buildJsonObject {
                        put("task_id", taskId)
                        put("lines", lines)
                        put("logs", text)
                        put("truncated", text.length > 10000)
                    }.toString()
                }
            }.getOrElse { e ->
                """{"error":"获取日志失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** 通用异步任务协议 —— 取消任务。 */
fun asyncTaskCancel(context: Context): ToolDef = tool("async_task_cancel") {
    description = """
        取消一个正在运行的异步任务（async_task_submit 提交的任务，`task_` 前缀）。
        语义：立即返回、非阻塞；把任务状态置为 CANCELLED，然后调用 runner 注册的清理回调（Bash runner 会对底层 PRoot 进程 destroyForcibly）并发送协程取消信号。Bash runner 的等待循环会在 800ms 内感知取消并返回 "cancelled"。
        幂等性：任务不存在或已处于终态时返回 error（不会再触发动作）；task_id 属于 run_bash_bg 时（`bg_` 前缀）应改用 bash_task_kill。
    """.trimIndent()
    string("task_id") { description = "任务 ID（由 async_task_submit 返回）" }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val taskId = args.requireStr("task_id", "async_task_cancel")
                val ok = AsyncTaskManager.cancel(taskId)
                if (!ok) {
                    """{"error":"任务不存在或已结束: $taskId"}"""
                } else {
                    buildJsonObject {
                        put("task_id", taskId)
                        put("status", AsyncTaskStatus.CANCELLED.name)
                        put("message", "任务已取消")
                    }.toString()
                }
            }.getOrElse { e ->
                """{"error":"取消失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** 通用异步任务协议 —— 列出任务。 */
fun asyncTaskList(context: Context): ToolDef = tool("async_task_list") {
    description = "列出异步任务（含运行中与已结束），可按 type / status 过滤。"
    string("type") {
        description = "按任务类型过滤（如 bash）"
        required = false
    }
    string("status") {
        description = "按状态过滤（RUNNING / DONE / FAILED / CANCELLED）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val type = args["type"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                val statusText = args["status"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                val status = statusText?.let { runCatching { AsyncTaskStatus.valueOf(it) }.getOrNull() }
                val list = AsyncTaskManager.list(type).filter { status == null || it.status == status }
                val arr = list.map { t ->
                    buildJsonObject {
                        put("task_id", t.id)
                        put("type", t.type)
                        put("label", t.label)
                        put("status", t.status.name)
                        put("started_at", t.startedAt)
                        if (t.finishedAt != null) put("finished_at", t.finishedAt)
                        if (t.progress != null) put("progress", t.progress)
                        if (t.result != null) put("result", t.result)
                        if (t.error != null) put("error", t.error)
                    }.toString()
                }
                """{"count":${arr.size},"tasks":[${arr.joinToString(",")}]}"""
            }.getOrElse { e ->
                """{"error":"列出任务失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}