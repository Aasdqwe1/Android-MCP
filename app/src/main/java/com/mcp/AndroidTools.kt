package com.mcp

import android.content.Context
import android.util.Log
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import com.mcp.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

private const val TAG_TOOLS = "AndroidTools"

/**
 * Execute a Bash script in the Debian ARM64 PRoot guest.
 *
 * There is deliberately no Android shell fallback: a successful
 * result always means that the command ran in the same full Linux userland.
 */
fun runBash(context: Context): ToolDef = tool("run_bash") {
    description = """
        在完整 Debian ARM64 PRoot Linux 环境中同步执行 Bash 脚本或单条命令，阻塞等待完成。
        同时支持两个字段（二选一；都传则 command 优先，两者都缺失则报错）：
        - script：多行 Bash 脚本内容（推荐），脚本通过 stdin 传入，不落地临时脚本
        - command：单条 shell 命令，等价于把该内容原样交给 Bash 执行
        超时默认 300 秒（用 timeout_seconds 调整），输出上限 4MB——仅适合短命令（< 5 分钟），
        如 git clone、ls、grep、快速脚本。**务必避免全盘遍历**（如 find / -maxdepth 6）：
        根文件系统上万个文件，很容易跑满超时并把整段 run_code 一起拖死。
        不要用于 Gradle 构建/测试（会超时）：≤ 20 分钟且要立即拿到构建结果用 gradle_build；> 20 分钟用 async_task_submit。
        不要用于长期服务（HTTP/SMB/API 等跨调用常驻进程）：请用 run_bash_bg。
        支持 apt、Git、Node.js、Python、管道、重定向、变量和循环。
    """.trimIndent()
    string("script") {
        description = "要执行的多行 Bash 脚本内容（与 command 二选一）"
        multiLine = true
        required = false
    }
    string("command") {
        description = "要执行的单条 Bash 命令（与 script 二选一；两者都提供时优先使用 command）"
        required = false
    }
    // 这个参数以前**不存在**：模型传了 timeout_seconds 也不会报错（inputSchema 不带
    // additionalProperties:false，未知参数既不校验也不生效），于是它以为把命令限制在 120 秒，
    // 实际按默认 300 秒跑满，最后被外层 run_code 的超时兜底切断——排查时完全看不出因果。
    // 与其让模型的意图被静默丢弃，不如把它变成可表达的。
    integer("timeout_seconds") {
        description = "本次命令的超时秒数（1..3600，默认 300）。预计较慢的命令可传更小的值，让失败更早回到程序里；传 0 表示不限时（慎用）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                var command = args["command"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                var script = args["script"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val timeout = args["timeout_seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L
                when {
                    command != null && script != null -> {
                        Log.w(TAG_TOOLS, "run_bash: 同时提供 command 与 script，优先使用 command，忽略 script")
                        executeBash(context, command, timeoutSeconds = timeout)
                    }
                    command != null -> executeBash(context, command, timeoutSeconds = timeout)
                    script != null -> executeBash(context, script, timeoutSeconds = timeout)
                    else -> throw MissingArgException("script 或 command", "run_bash")
                }
            }.getOrElse { e ->
                """{"error":"执行失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** PRoot runtime health and installation diagnostics. */
fun prootStatusTool(context: Context): ToolDef = tool("proot_status") {
    description = "查询完整 PRoot Debian Linux 环境状态：版本、rootfs、PRoot loader、ARM64 ABI、安装大小和最近失败原因。"
    handler { _ -> CapabilityRegistry.shell.status(context) }
}

/**
 * run_bash_bg: 后台运行 Bash 脚本（不阻塞）。
 * 适用于启动 HTTP 服务器、监听脚本等长期运行任务。
 * 立即返回任务 ID，可通过配套工具查询状态/日志/停止。
 */
fun runBashBg(context: Context): ToolDef = tool("run_bash_bg") {
    description = """
        【长期服务专用】在后台启动一个 Bash 脚本（不阻塞），仅用于**跨调用常驻的服务进程**：HTTP/API/SMB 服务器、监听器、守护脚本等。
        脚本必须在**前台阻塞运行直到结束**（例如 `smbd -F ...`、`flask run ...`、`while true; do ...; done`），承载它的宿主 PRoot 进程会一直持有。
        不要写 `nohup ... &` 或 `... & exit` 后立即退出——PRoot 的 --kill-on-exit 会在 shell 结束时连带回收服务进程，服务器起不来。
        不要在此放**一次性长任务**（构建/下载/训练）：一次性任务请改用 async_task_submit（有明确终态 DONE/FAILED/CANCELLED，可随时取消）。
        立即返回任务 ID（`bg_` 前缀），日志落在 `filesDir/bash_tasks/<id>.log`；查询用 bash_task_status / bash_task_logs，停止用 bash_task_kill。
    """.trimIndent()
    string("script") {
        description = "要执行的 Bash 脚本内容"
        multiLine = true
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val script = args.requireStr("script", "run_bash_bg")
                val task = BashTaskManager.startTask(context, script)
                buildJsonObject {
                    put("task_id", task.taskId)
                    put("pid", task.pid)
                    put("status", task.status.name)
                    put("log_file", task.logFile.absolutePath)
                }.toString()
            }.getOrElse { e ->
                """{"error":"启动后台任务失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** 查询后台任务状态 */
fun bashTaskStatus(context: Context): ToolDef = tool("bash_task_status") {
    description = "查询后台 Bash 任务的状态。返回任务是否在运行、PID、退出码等。"
    string("task_id") {
        description = "任务 ID（由 run_bash_bg 返回）"
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val taskId = args.requireStr("task_id", "bash_task_status")
                val task = BashTaskManager.getTask(taskId)
                if (task == null) {
                    // ID 命名空间互查：task_ 前缀属 async_task_submit 注册表（本表任务以 bg_ 开头）
                    if (taskId.startsWith("task_")) {
                        val at = AsyncTaskManager.get(taskId)
                        if (at != null) {
                            return@withContext buildJsonObject {
                                put("task_id", at.id)
                                put("type", at.type)
                                put("label", at.label)
                                put("status", at.status.name)
                                put("started_at", at.startedAt)
                                if (at.finishedAt != null) put("finished_at", at.finishedAt)
                                if (at.result != null) put("result", at.result)
                                if (at.error != null) put("error", at.error)
                                put("note", "该 ID 属于 async_task_submit 注册表，后续请用 async_task_status / async_task_logs / async_task_cancel 管理")
                            }.toString()
                        }
                    }
                    """{"error":"任务不存在: $taskId（run_bash_bg 返回的任务以 bg_ 开头）"}"""
                } else {
                    buildJsonObject {
                        put("task_id", task.taskId)
                        put("pid", task.pid)
                        put("status", task.status.name)
                        put("started_at", task.startedAt)
                        if (task.exitCode != null) put("exit_code", task.exitCode)
                        put("log_file", task.logFile.absolutePath)
                        put("log_exists", task.logFile.exists())
                    }.toString()
                }
            }.getOrElse { e ->
                """{"error":"查询失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** 获取后台任务日志 */
fun bashTaskLogs(context: Context): ToolDef = tool("bash_task_logs") {
    description = "获取后台 Bash 任务的日志输出（默认最近 100 行）。"
    string("task_id") {
        description = "任务 ID（由 run_bash_bg 返回）"
    }
    number("lines") {
        description = "返回行数，默认 100，最大 500"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val taskId = args.requireStr("task_id", "bash_task_logs")
                val lines = args["lines"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                val logs = BashTaskManager.getLogs(taskId, lines)
                if (logs == null) {
                    // 跨表 fallback：task_ 前缀的任务读其异步日志文件尾部
                    if (taskId.startsWith("task_")) {
                        val at = AsyncTaskManager.get(taskId)
                        if (at != null) {
                            val logFile = at.meta["log_file"] as? String
                            val text = logFile?.let { p ->
                                File(p).takeIf { it.exists() }
                                    ?.readLines()?.takeLast(lines)?.joinToString("\n")
                            } ?: "(日志文件尚未生成)"
                            return@withContext buildJsonObject {
                                put("task_id", taskId)
                                put("lines", lines)
                                put("logs", text)
                                put("truncated", text.length > 10000)
                                put("note", "该 ID 属于 async_task_submit 注册表，后续请用 async_task_logs 管理")
                            }.toString()
                        }
                    }
                    """{"error":"任务不存在: $taskId（run_bash_bg 返回的任务以 bg_ 开头）"}"""
                } else {
                    buildJsonObject {
                        put("task_id", taskId)
                        put("lines", lines)
                        put("logs", logs)
                        put("truncated", logs.length > 10000)
                    }.toString()
                }
            }.getOrElse { e ->
                """{"error":"获取日志失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** 停止后台任务 */
fun bashTaskKill(context: Context): ToolDef = tool("bash_task_kill") {
    description = """
        停止一个正在运行的后台 Bash 任务（run_bash_bg 提交的任务，`bg_` 前缀）。
        语义：立即返回、非阻塞；把任务状态置为 KILLED、exit_code=137，然后对承载它的宿主 PRoot 常驻进程 destroyForcibly（连带杀掉 guest 内的服务进程）。
        幂等性：任务不存在或已处于终态时返回 error（不会再触发动作）；task_id 属于 async_task_submit 时（`task_` 前缀）会提示改用 async_task_cancel。
    """.trimIndent()
    string("task_id") {
        description = "任务 ID（由 run_bash_bg 返回）"
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val taskId = args.requireStr("task_id", "bash_task_kill")
                val ok = BashTaskManager.killTask(taskId, context)
                if (!ok) {
                    val hint = if (taskId.startsWith("task_"))
                        "（该 ID 属于 async_task_submit 注册表，取消请用 async_task_cancel）" else ""
                    """{"error":"任务不存在或已停止: $taskId$hint"}"""
                } else {
                    buildJsonObject {
                        put("task_id", taskId)
                        put("status", "killed")
                        put("message", "任务已停止")
                    }.toString()
                }
            }.getOrElse { e ->
                """{"error":"停止失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** 列出所有后台任务 */
fun bashTaskList(context: Context): ToolDef = tool("bash_task_list") {
    description = "列出所有后台 Bash 任务（包括运行中、已完成、已停止）。"
    handler { _ ->
        withContext(Dispatchers.IO) {
            runCatching {
                val tasks = BashTaskManager.listTasks()
                if (tasks.isEmpty()) {
                    """{"tasks":[],"count":0}"""
                } else {
                    val list = tasks.map { (id, task) ->
                        mapOf(
                            "task_id" to id,
                            "pid" to task.pid,
                            "status" to task.status.name,
                            "started_at" to task.startedAt,
                            "exit_code" to (task.exitCode ?: "null")
                        )
                    }
                    buildJsonObject {
                        put("tasks", JsonPrimitive(list.toString()))
                        put("count", list.size)
                    }.toString()
                }
            }.getOrElse { e ->
                """{"error":"列出任务失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/** Synchronous bridge used by the tool handlers and the OpenClaw integration. */
internal fun executeBash(
    context: Context,
    script: String,
    onLine: ((String) -> Unit)? = null,
    timeoutSeconds: Long = 300L
): String {
    return try {
        // 确保后端与当前 root 状态一致：root 模式 + 可用 su → 原生 chroot，否则 PRoot
        CapabilityRegistry.selectBackend(context)
        val shell = CapabilityRegistry.shell
        if (!shell.isReady(context)) {
            kotlinx.coroutines.runBlocking {
                shell.ensureInitialized(context)
            }
        }
        if (!shell.isReady(context)) {
            val reason = shell.lastError ?: "PRoot 环境未就绪"
            LogStore.e("PROOT", reason)
            return """{"error":"PRoot 环境未就绪: ${reason.take(500).replace("\"", "'")}"}"""
        }
        // timeoutSeconds <= 0 表示不超时（等待命令自然执行完毕）；否则夹到 [1, 3600]
        val effectiveTimeout = if (timeoutSeconds <= 0) Long.MAX_VALUE else timeoutSeconds.coerceIn(1, 3600)
        val result = shell.execute(context, script, onLine = onLine, timeoutSeconds = effectiveTimeout)
        when {
            result.timedOut -> """{"error":"超时（${timeoutSeconds}秒）","partial_output":${JsonPrimitive(result.output)}}"""
            result.exitCode == 0 -> result.output.ifEmpty { "(无输出)" } +
                if (result.truncated) "\n[输出已截断：超过 4MB]" else ""
            else -> """{"exit_code":${result.exitCode},"output":${JsonPrimitive(result.output)},"truncated":${result.truncated}}"""
        }
    } catch (e: Exception) {
        Log.e(TAG_TOOLS, "PRoot 执行失败", e)
        LogStore.e("PROOT", "PRoot 执行失败: ${e.message}")
        """{"error":"PRoot 执行失败: ${e.message?.take(500)?.replace("\"", "'")}"}"""
    }
}

/**
 * run_root 工具：在 Android 物理机（宿主）上以 root 执行 shell 命令（需设备已 root）。
 *
 * 与 run_bash 的区别：
 * - run_bash 在 PRoot Debian 沙箱中执行（用户态隔离，非 root）
 * - run_root 直接操作 Android 物理机，可读 /data、改系统文件（真 root）
 *
 * 执行方式：su 无参数启动 + 命令写入 stdin，由物理机 shell 以 root 执行，
 * 不经过 PRoot 沙箱。
 */
fun runRoot(context: Context): ToolDef = tool("run_root") {
    description = "在 Android 物理机上以 root 执行 shell 命令（需设备已 root，Magisk/厂商 su）。" +
            "与 run_bash 不同：run_bash 在 PRoot Debian 沙箱内（非 root），本工具直接在宿主系统执行，" +
            "可读取 /data 根目录、其他 App 数据、修改系统文件。" +
            "自动经 su -M 切入全局挂载命名空间（Android 10+ 每个 App 有独立 mount namespace，不加 -M 时其他 App 目录在本进程里不可见）。" +
            "支持管道、重定向等标准 shell 语法；timeout_sec 控制超时（默认 30，最大 3600）。" +
            "高危：拥有完整 root 权限，请谨慎使用。"
    string("command") {
        description = "要执行的 shell 命令（如 'cat /data/misc/wifi/WifiConfigStore.xml'）"
    }
    number("timeout_sec") {
        description = "超时秒数，默认 30，最大 3600"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val command = args.requireStr("command", "run_root")
                val timeout = args["timeout_sec"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?.coerceIn(1, 3600) ?: 30
                executeRoot(context, command, timeout)
            }.getOrElse { e ->
                """{"error":"执行失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }
        }
    }
}

/**
 * 执行 root 命令的核心逻辑（Android 物理机宿主层，不经过 PRoot）。
 *
 * 安全与健壮性：
 * - 复用 [HostSu] 探测/验证 su（统一路径表 + uid=0 预检），不重复实现。
 * - stdin 模式：su 无参数启动、命令原样写入 stdin，无引号嵌套/注入问题，
 *   也不依赖各 ROM 对 su -c 的参数语义（部分设备把 -c 当命令名 exec）。
 * - 挂载命名空间：Android 10+ 每个 App 进程有独立 mount namespace。裸 su 只换
 *   uid、不换 namespace，于是 /data/user/0/<其它 App> 在本进程里根本不存在，
 *   File.exists() 返回 false，工具只能报「文件不存在」——与「权限拒绝」是两种
 *   完全不同的故障，排查时极易被误导。支持时自动附加 -M 切到 init 全局命名空间
 *   （MT 管理器同款），让 /data 下所有 App 目录可见；不支持 -M 的 su 退回原行为。
 * - 超时先销毁进程再读取，避免 readText 卡死。
 * - 输出限制到 4MB（与 run_bash 对齐），防止失控输出占满内存。
 */
private const val MAX_SU_OUTPUT_BYTES = 4 * 1024 * 1024

private fun executeRoot(context: Context, command: String, timeoutSec: Long): String {
    // 1. 判定 root：直接 verifySu（真跑 su -c id）。
    //    不再依赖 findSu 的文件存在性检查——KernelSU / APatch 是内核级方案，
    //    su 通过挂载或 PATH 注入，普通 App 常 stat 不到。
    if (!HostSu.verifySu()) {
        return """{"error":"无法获取 root（uid 非 0）。请确认设备已 root，并在 KernelSU / Magisk / APatch 授权管理器中允许本应用。"}"""
    }
    val suBinary = HostSu.findSu() ?: "su"

    // 1.5 挂载命名空间参数：-M / --mount-master 让 su 在 init 的全局 mount
    //     namespace 里执行，从而看见 /data 下全部 App 目录。探测失败则返回空
    //     列表，行为与改动前一致（不因加了参数让 AOSP su 直接 usage 退出）。
    val nsArgs = HostSu.rootNamespaceArgs()

    // 2. 执行方式：stdin 模式（su 无参数启动，从 stdin 读命令）。
    //    设备的 su 把 -c 参数当单个命令名 exec（带空格会 127 not found），
    //    stdin 模式是 Magisk/厂商 su 的通用交互方式，命令原样进 shell，
    //    无引号嵌套/注入问题，也不依赖 -c 语义。
    val pb = ProcessBuilder(listOf(suBinary) + nsArgs)
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.PIPE)

    // 清掉 PRoot 可能设置的环境变量，避免污染宿主 su 会话
    val env = pb.environment()
    env.remove("LD_LIBRARY_PATH")
    env.remove("PREFIX")
    env.remove("TERMUX_VERSION")
    env.remove("LD_PRELOAD")
    env.remove("PROOT_LOADER")
    env.remove("PROOT_TMP_DIR")

    val process = pb.start()
    // 命令原样写入 stdin，shell 以 root 身份执行
    runCatching {
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(command)
            w.newLine()
            w.flush()
        }
    }.onFailure { LogStore.w("SU", "写入命令失败: ${it.message}") }

    // 登记到全局注册表：用户点「停止」时由 ChatBridge.stopStream 无条件 killAllForcibly()。
    //
    // 为什么不用 PtcCancellation：那个标志只在 run_code 执行期间（PtcAudit.current()==sid）
    // 才置位，直调 run_root 时永远为 false；而工具跑在独立 toolScope.launch 里，
    // clearPendingTools 只 cancel 了结果 deferred、并未取消该协程——两条取消链都到不了
    // 这个宿主侧 su 进程。注册表与取消机制解耦，直调 / 嵌套 / run_code 内调用全覆盖。
    com.mcp.ptc.RunRootProcessRegistry.register(process)

    // 阻塞等待：按片轮询，每片最多 [ROOT_WAIT_SLICE_MS] 毫秒。
    //
    // 醒来只做一件事——看进程是否还活着。若 !isAlive，不能直接判定成「被外部杀」：
    // 200ms 切片边界上，进程完全可能在 waitFor 超时后的一瞬间正常退出，此时 isAlive
    // 也为 false，若一律当取消就会返回假的 {"cancelled":true}，掩盖真实的 exit_code。
    // 因此改为向 RunRootProcessRegistry 求证——只有它确实主动杀过，才算用户停止。
    var killedExternally = false
    val waitStartMs = System.currentTimeMillis()
    while (true) {
        val slice = com.mcp.ptc.ROOT_WAIT_SLICE_MS
        val finished = process.waitFor(slice, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (finished) break
        if (!process.isAlive) {
            // 进程已不在：先问注册表是不是「我主动杀的」。
            if (com.mcp.ptc.RunRootProcessRegistry.wasKilledExternally(process)) {
                killedExternally = true
            } else {
                // 未标记 → 自然退出，只是 waitFor 没赶上。补一次短暂等待让内核回收
                // exit 状态，随后走正常退出分支读 exitValue，不再误报取消。
                runCatching { process.waitFor(50, java.util.concurrent.TimeUnit.MILLISECONDS) }
            }
            break
        }
        if ((System.currentTimeMillis() - waitStartMs) >= timeoutSec * 1000L) break
    }
    // 统一收尾：无论超时、被外部杀死还是正常结束，只要进程还活着就 SIGKILL。
    // 原实现只在超时分支 destroyForcibly，取消路径完全没清理——这就是
    // 「停止后 su/sleep 还在跑」的根因。
    try {
        if (killedExternally) {
            return """{"error":"已取消（用户停止生成）","cancelled":true}"""
        }
        val stillRunning = process.isAlive
        if (stillRunning) {
            // 先销毁进程（destroyForcibly 会向进程发送 SIGKILL），再关流读取残量。
            // minSdk 24 无 Process.pid()，无法按进程组杀；su 常驻场景下子进程
            // 通常随父进程退出（sh -c 是前台同步执行）。
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
            val partial = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            return """{"error":"超时（${timeoutSec}秒）","partial_output":${JsonPrimitive(partial.take(1000))}}"""
        }

        // 4. 读取完整输出（限制大小防失控）
        val out = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        var truncated = false
        val buf = CharArray(8192)
        while (true) {
            val n = reader.read(buf)
            if (n <= 0) break
            if (out.length < MAX_SU_OUTPUT_BYTES) {
                val room = MAX_SU_OUTPUT_BYTES - out.length
                val take = minOf(n, room)
                out.append(buf, 0, take)
                if (n > room) truncated = true
            } else {
                truncated = true
            }
        }
        runCatching { reader.close() }

        val output = out.toString()
        val exitCode = process.exitValue()
        // SIGKILL(9) 的退出码是 137。正常走完的循环不会产生它；一旦出现，
        // 说明进程是被外部强杀（注册表标记之外的第二重兜底），按取消返回而非
        // 当成一条「exit_code=137 的命令失败」误报给模型。
        if (exitCode == 137) {
            return """{"error":"已取消（用户停止生成）","cancelled":true}"""
        }
        return when {
            exitCode == 0 -> output.ifEmpty { "(无输出)" } +
                if (truncated) "\n[输出已截断：超过 4MB]" else ""
            else -> """{"exit_code":$exitCode,"output":${JsonPrimitive(output)},"truncated":$truncated}"""
        }
    } finally {
        // 兜底：任何异常/提前返回路径都不留下孤儿 su 进程，并从注册表注销。
        runCatching { if (process.isAlive) process.destroyForcibly() }
        com.mcp.ptc.RunRootProcessRegistry.unregister(process)
    }
}
