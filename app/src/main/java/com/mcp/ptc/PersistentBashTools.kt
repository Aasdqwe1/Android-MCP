package com.mcp.ptc

import android.content.Context
import com.mcp.CapabilityRegistry
import com.mcp.PersistentShellProvider
import com.mcp.requireStr
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 持久 shell 的进程注册表。
 *
 * 全局只维持**一个**长活 bash 进程：多个工具/多个会话共用同一份 cwd 与环境变量，
 * 这正是 deepseek-harness 极简模式 `persistent-bash` 的语义（一个会话一个 shell）。
 *
 * 由 [com.mcp.preset.PresetRuntime] 在切换到极简预设时 [activate]（把
 * [CapabilityRegistry.shell] 换成持久实现），切回时 [deactivate] 销毁进程。
 */
object PersistentShellRegistry {
    @Volatile private var provider: PersistentShellProvider? = null

    fun get(): PersistentShellProvider = provider ?: synchronized(this) {
        provider ?: PersistentShellProvider().also { provider = it }
    }

    /** 极简模式启用：能力接缝切换到持久实现。 */
    fun activate() {
        CapabilityRegistry.shell = get()
    }

    /** 退出极简模式：销毁长活进程并把接缝切回当前选定的（proot / 原生）后端。 */
    fun deactivate() {
        provider?.destroy()
        provider = null
        CapabilityRegistry.resetShellToSelected()
    }

    /** 强制重启（用户手动重置，或 shell 状态被搞乱时）。 */
    fun reset() {
        provider?.destroy()
        provider = null
    }
}

/**
 * `run_bash_persistent`：极简模式的主工具（对应 dsh 的 persistent-bash）。
 *
 * 与 `run_bash` 的差异只有一点——**进程不销毁**：
 * `cd /data/work` 之后下一次调用仍在该目录，`export FOO=1` 之后仍可见，
 * 后台 `&` 启动的进程也不会被回收。代价是状态会累积，需要时可用
 * `persistent_bash_reset` 重启。
 */
fun runBashPersistent(context: Context): ToolDef = tool("run_bash_persistent") {
    description = """
        在**持久 bash 会话**中执行命令：同一个 shell 进程跨调用存活，cd / export / shell 函数 / 后台 job 都会保留到下一次调用。
        适合「先 cd 进项目目录，再连续执行多条命令」这类需要保持工作目录的任务。
        超时 300 秒、输出上限 4MB——长任务请用 run_bash_bg；一次性无状态命令用 run_bash 亦可。
        状态被搞乱时（比如 cd 到了不存在的目录、变量污染）用 persistent_bash_reset 重启会话。
    """.trimIndent()
    string("script") {
        description = "要执行的 Bash 脚本内容"
        multiLine = true
    }
    integer("timeout_seconds") {
        description = "超时秒数，默认 300，上限 3600"
        required = false
        default(300)
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val script = args.requireStr("script", "run_bash_persistent")
                val timeout = args["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 300L
                val shell = PersistentShellRegistry.get()
                if (!shell.isReady(context)) {
                    runBlocking { shell.ensureInitialized(context) }
                }
                if (!shell.isReady(context)) {
                    return@runCatching buildJsonObject {
                        put("error", "持久 shell 未就绪: ${shell.lastError ?: "PRoot 环境可能尚未安装完成"}")
                    }.toString()
                }
                val res = shell.execute(context, script, timeout.coerceIn(1, 3600), null)
                when {
                    res.timedOut -> buildJsonObject {
                        put("error", "超时（${timeout}秒），会话已重启")
                        put("partial_output", res.output)
                    }.toString()
                    res.exitCode == 0 -> res.output.ifEmpty { "(无输出)" }
                    else -> buildJsonObject {
                        put("exit_code", res.exitCode)
                        put("output", res.output)
                    }.toString()
                }
            }.getOrElse { e ->
                buildJsonObject {
                    put("error", "持久 bash 执行失败: ${e.message?.take(300)}")
                }.toString()
            }
        }
    }
}

/** 查询持久 shell 会话状态（进程是否存活、PID）。 */
fun persistentBashStatus(context: Context): ToolDef = tool("persistent_bash_status") {
    description = "查询持久 bash 会话状态：是否就绪、PID，以及当前能力接缝指向的 Provider。"
    handler { _ ->
        val shell = PersistentShellRegistry.get()
        buildJsonObject {
            put("status", shell.status(context))
            put("ready", shell.isReady(context))
            put("active_shell_provider", CapabilityRegistry.shell.id)
        }.toString()
    }
}

/** 重启持久 bash 会话（丢弃全部 cwd / 变量 / 后台 job 状态）。 */
fun persistentBashReset(context: Context): ToolDef = tool("persistent_bash_reset") {
    description = "重启持久 bash 会话：销毁当前长活进程，下次调用时重新拉起，工作目录回到 /root、自定义变量清空。"
    handler { _ ->
        PersistentShellRegistry.reset()
        buildJsonObject {
            put("reset", true)
            put("message", "持久 bash 会话已销毁，下次 run_bash_persistent 调用会重新拉起（cwd=/root）")
        }.toString()
    }
}
