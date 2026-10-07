package com.mcp

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 持久 bash 会话 Provider —— 实现 [ShellProvider] 接缝，但持有一个**长活 bash 进程**，
 * 每次 [execute] 把脚本写入进程 stdin 并读取到 sentinel，从而跨调用保持 cwd / 环境变量 / 历史。
 *
 * 这忠实还原 deepseek-harness 极简模式的 `persistent-bash`：状态在多次工具调用间持续存在。
 *
 * 与 [ProotShellProvider]（每次 `run_bash` 起一个新 PRoot 进程、脚本走 stdin、结束即销毁）
 * 的区别：
 * - 进程只启动一次（[ensureInitialized]），后续调用复用；
 * - `cd /tmp && export FOO=1` 之类的状态在下一次调用仍然生效；
 * - 输出通过一个**常驻读线程**按行分发：当前有 [execute] 在飞时归入结果缓冲，
 *   否则丢弃（避免上一次残留输出污染下一次结果）。
 *
 * @param starter 启动长活进程的工厂。默认按 root 状态分发：root 模式且 su 可用 →
 *   [NativeChrootEnvironment.startPersistent]（零损耗），否则 [ProotEnvironment.startPersistent]。
 */
class PersistentShellProvider(
    private val starter: (Context) -> Process = { ctx ->
        if (HostSu.isRootModeEnabled(ctx) && HostSu.verifySu())
            NativeChrootEnvironment.startPersistent(ctx)
        else
            ProotEnvironment.startPersistent(ctx)
    }
) : ShellProvider {

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var sink: ((String) -> Unit)? = null
    @Volatile private var sentinel: String? = null
    @Volatile private var latch: CountDownLatch = CountDownLatch(0)
    private val ready = AtomicBoolean(false)
    private val lock = Any()
    private val execLock = Any()
    override var lastError: String? = null
        private set

    override val id = "persistent-proot"

    override fun isReady(ctx: Context): Boolean = ready.get() && process?.isAlive == true

    override suspend fun ensureInitialized(ctx: Context, onProgress: (String) -> Unit): Boolean =
        start(ctx)

    private fun start(ctx: Context): Boolean {
        synchronized(lock) {
            if (isReady(ctx)) return true
            runCatching {
                val p = starter(ctx)
                writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
                reader = p.inputStream.bufferedReader(Charsets.UTF_8)
                val r = reader
                if (r != null) startReader(r)
                process = p
                ready.set(true)
                lastError = null
            }.onFailure { e ->
                lastError = e.message ?: e::class.simpleName
                ready.set(false)
                destroy()
            }
        }
        return ready.get()
    }

    /**
     * 常驻读线程：把进程 stdout 按行分发。
     * 命中 sentinel 视为本次执行结束（并解析出退出码），否则交给当前 [sink]。
     */
    private fun startReader(r: BufferedReader) {
        thread(name = "persistent-shell-reader", isDaemon = true) {
            try {
                while (true) {
                    val line = r.readLine() ?: break
                    val s = sentinel
                    if (s != null && line.startsWith(s)) {
                        val rc = line.removePrefix(s).trim().toIntOrNull() ?: 0
                        lastExitCode = rc
                        sentinel = null
                        sink = null
                        latch.countDown()
                        continue
                    }
                    sink?.invoke(line)
                }
            } catch (_: Exception) {
                // 进程退出/管道断开时 readLine 抛异常，结束读线程即可
            } finally {
                ready.set(false)
                latch.countDown() // 防止 execute 悬挂
            }
        }
    }

    @Volatile private var lastExitCode: Int = 0

    private fun newSentinel(): String = "__PTC_BASH_DONE_${UUID.randomUUID().toString().replace("-", "")}__"

    override fun execute(
        ctx: Context,
        script: String,
        timeoutSeconds: Long,
        onLine: ((String) -> Unit)?
    ): ShellResult {
        if (!isReady(ctx) && !start(ctx)) {
            return ShellResult(-1, """{"error":"持久 shell 未就绪: ${lastError ?: "未知"}"}""")
        }
        // 串行化：持久会话只有一份状态，并发执行会互相交错输出与 cwd。
        synchronized(execLock) {
            // 先做存活性检查/重启，再取 writer——否则会拿到已销毁进程的旧 writer。
            if (process?.isAlive != true) {
                destroy()
                if (!start(ctx)) return ShellResult(-1, """{"error":"持久 shell 已退出且重启失败"}""")
            }
            val w = writer ?: return ShellResult(-1, """{"error":"持久 shell writer 未初始化"}""")
            val done = newSentinel()
            val sb = StringBuilder()
            val gate = CountDownLatch(1)
            lastExitCode = 0
            sentinel = done
            latch = gate
            sink = { line ->
                sb.appendLine(line)
                onLine?.invoke(line)
            }
            val written = runCatching {
                w.appendLine(script)
                // sentinel 行同时携带退出码：`echo "$SENTINEL $?"`
                w.appendLine("echo \"$done $?\"")
                w.flush()
            }.isSuccess
            if (!written) {
                sentinel = null
                sink = null
                return ShellResult(-1, """{"error":"写入持久 shell 失败（进程可能已退出）"}""")
            }

            val timeoutMs = if (timeoutSeconds <= 0) Long.MAX_VALUE else timeoutSeconds.coerceIn(1, 3600) * 1000
            val ok = if (timeoutMs == Long.MAX_VALUE) {
                gate.await()
                true
            } else {
                gate.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
            if (!ok) {
                // 超时：销毁并重启，避免悬挂命令污染后续状态
                sentinel = null
                sink = null
                destroy()
                return ShellResult(-1, """{"error":"超时（${timeoutSeconds}秒）","partial_output":${json(sb.toString())}}""", timedOut = true)
            }
            return ShellResult(lastExitCode, sb.toString().trimEnd('\n'), timedOut = false, truncated = false)
        }
    }

    override fun status(ctx: Context): String =
        if (isReady(ctx)) "persistent-proot 就绪"
        else "persistent-proot 未就绪: ${lastError ?: "未初始化"}"

    /** 销毁长活进程并清空状态（超时、切换预设或需要重置工作目录时调用）。 */
    fun destroy() {
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        process = null
        writer = null
        reader = null
        sink = null
        sentinel = null
        ready.set(false)
    }

    private fun json(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()
}
