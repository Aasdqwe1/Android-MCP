package com.mcp.ptc

import android.util.Log
import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.ptc.PtcCallRequest
import com.mcp.toolbox.ptc.PtcCallResult
import com.mcp.toolbox.ptc.PtcDispatchEvent
import com.mcp.toolbox.ptc.PtcEventKind
import com.mcp.toolbox.ptc.PtcIpc
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PTC 宿主侧 IPC 桥。
 *
 * 职责（对应 dsh `packages/core/tools/src/ptc.ts` 的 code-dispatch 循环）：
 *  1. 尾随读取 guest 程序**追加写入**的请求文件（JSONL，每行一个 [com.mcp.toolbox.ptc.PtcCallRequest]）；
 *  2. 每次请求经 [Toolbox.dispatch] 回到**同一条工具执行管线**——因此白名单、预设过滤、
 *     参数校验、错误捕获、审计全部与模型直调完全一致（这是 PTC 相对「程序里直接 curl」
 *     的核心价值：不绕过任何 guard）；
 *  3. 把结果写到 `resDir/<callId>.json`，guest 侧轮询到文件后恢复执行；
 *  4. 每个子调用产生一对 [PtcDispatchEvent] 事件（`ptc/call` / `ptc/result`），
 *     供会话日志与 UI 渲染嵌套调用树。
 *
 * 线程模型：`start()` 在专用 `CoroutineScope(Dispatchers.IO)` 里跑一个 `pump` 协程，
 * 读取请求行并调用 `handle`；`dispatchOne` 内部是 `suspend`，用 `withTimeout` 直接包住分发，
 * 不再用 `runBlocking`（P1-C）。裸 `runBlocking` 会在 `Dispatchers.Main` 挂死的 handler
 * 上死锁——用协程作用域后，pump 与 dispatch 都跑在 IO dispatcher 上，互不阻塞。
 *
 * 文件 IPC 而非 socket 的原因：guest 跑在 PRoot 里，宿主目录经 `--bind` 双向可见
 * （`filesDir` → `/host/app`），文件读写比跨世界开端口更稳、无需额外权限。
 *
 * @param toolbox   宿主工具箱（分发目标）
 * @param reqFile   guest 追加写、宿主尾随读的请求文件
 * @param resDir    宿主写、**guest 轮询读**的结果目录
 * @param parentId  外层 run_code 的 tool call id（嵌套事件挂载点）
 * @param allows    工具准入判定（接入当前预设白名单）；返回 false 时子调用直接失败
 * @param onEvent   嵌套事件回调（接会话日志 / UI）
 */
class PtcBridge(
    private val toolbox: Toolbox,
    private val reqFile: File,
    private val resDir: File,
    private val parentId: String = "",
    private val description: String = "",
    private val depth: Int = 0,
    private val maxCalls: Int = 200,
    private val callTimeoutMs: Long = 300_000L,
    private val allows: (String) -> Boolean = { true },
    private val onEvent: (PtcDispatchEvent) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val pending = ByteArrayOutputStream()

    // P1-C：pump 由 CoroutineScope 上的协程承载，取代 `kotlin.concurrent.thread + runBlocking`。
    // SupervisorJob 保证单次 handle / dispatch 崩溃不会连带整个 scope；
    // CoroutineExceptionHandler 兜底未处理异常到 lastError，避免线程级崩溃吞掉现场。
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            // 只在 lastError 尚为 null 时写入（先写的错误更贴现场），不覆盖真实业务错误
            if (lastError == null) {
                lastError = "PTC 泵未处理异常: ${e.message ?: e::class.simpleName}"
            }
        }
    )
    private var pumpJob: Job? = null
    private val counter = AtomicInteger(0)
    private val seq = AtomicLong(0)

    /** 已完成的子调用次数（用于 run_code 结果里给模型一个可观测的统计）。 */
    val callCount: Int get() = counter.get()

    /**
     * 子调用清单（工具名 / 成败 / 耗时），随 run_code 结果 JSON 回前端，
     * 在 run_code 卡片里渲染「程序内部调用了哪些工具、每个成没成」。
     * 上限 [MAX_CALLS_IN_RESULT]，超出只计数（tool_calls）不再逐条展开。
     */
    private val callSummaryList = java.util.Collections.synchronizedList(ArrayList<PtcCallSummary>())
    val callSummaries: List<PtcCallSummary> get() = synchronized(callSummaryList) { ArrayList(callSummaryList) }

    private fun recordCallSummary(name: String, ok: Boolean, ms: Long, error: String?) {
        if (callSummaryList.size >= MAX_CALLS_IN_RESULT) return
        callSummaryList.add(PtcCallSummary(name = name, ok = ok, ms = ms, error = error))
    }

    /** 子调用耗时合计（毫秒）。 */
    @Volatile var totalDispatchMs: Long = 0L
        private set

    @Volatile var lastError: String? = null
        private set

    fun start() {
        reqFile.parentFile?.mkdirs()
        resDir.mkdirs()
        runCatching { if (!reqFile.exists()) reqFile.createNewFile() }
        // P1-D：端到端自校验。协议编解码往返 + 结果目录 tmp→rename 写权限在启动时验证。
        // 出问题直接抛，比跑一半再在 guest 600s deadline 后无反馈地挂更省事。
        selfCheckOrFail()
        running.set(true)
        pumpJob = scope.launch { pumpCoro() }
    }

    fun stop() {
        running.set(false)
        // P1-C 附注：不能像原来那样 pumpJob.join() 阻塞等待——join() 是 suspend 函数，
        // stop() 从 executeProgram 的 finally 块（非 suspend）被调用。
        // cancel 会立即发取消信号；scope.cancel() 会兜底 cancel 掉 scope 里的所有子 job。
        // daemon 线程不再存在，pump 协程在下一个 delay 边界观察到 running=false 后自然退出。
        runCatching { pumpJob?.cancel() }
        pumpJob = null
        runCatching { scope.cancel() }
    }

    // ── 请求泵 ────────────────────────────────────────────────

    private suspend fun pumpCoro() {
        // P1-E：open 失败也留诊断信号（正常情况下 start() 已 mkdirs+createNewFile，此处为真防御）
        val inputOpt = runCatching { FileInputStream(reqFile) }
        if (inputOpt.isFailure) {
            val openMsg = "pump 无法打开请求文件: ${inputOpt.exceptionOrNull()?.message ?: reqFile.absolutePath}"
            Log.e("PTCBridge", openMsg, inputOpt.exceptionOrNull())
            if (lastError == null) lastError = openMsg
            emit(PtcEventKind.PTC_RESULT, "__pump_open_failed__", "ptc/pump-crash", error = openMsg)
            return
        }
        val input = inputOpt.getOrThrow()
        try {
            while (running.get()) {
                val lineBytes = readLineBytes(input)
                if (lineBytes == null) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                runCatching { handle(String(lineBytes, Charsets.UTF_8)) }
                    .onFailure { if (lastError == null) lastError = it.message }
            }
        } catch (e: Exception) {
            // P1-E：pump 意外终止不能静默——设 lastError 并广播合成事件，让上游/UI/后续
            // 诊断能看到 bridge dropped；guest 侧靠 90s deadline 自终止，不再空转到 600s。
            val exMsg = e.message ?: e::class.simpleName ?: "未知异常"
            Log.w("PTCBridge", "pump terminated: $exMsg", e)
            if (lastError == null) {
                lastError = "pump 线程异常退出: $exMsg"
            }
            emit(
                PtcEventKind.PTC_RESULT, "__pump_died__", "ptc/pump-crash",
                error = lastError
            )
        } finally {
            runCatching { input.close() }
        }
    }

    /**
     * 逐字节读到 `\n` 为止，返回**不含换行**的完整一行；未读到换行返回 null。
     * 未成行的字节留在 [pending] 里等下一轮补全——避免半行（尤其是多字节中文被切断）被误解析。
     */
    private fun readLineBytes(input: FileInputStream): ByteArray? {
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) {
                val full = pending.toByteArray()
                pending.reset()
                return full
            }
            pending.write(b)
        }
    }

    /**
     * 处理一行请求。suspend：内部的 [dispatchOne] 会挂起等待分发结果。
     *
     * P0-B：`decodeRequest` 失败时**必须**留下可见的诊断信号，不能 `?: return` 静默吞掉——
     * 否则 guest 会一直空等它自造的 `resDir/<callId>.json` 直到 600s deadline，
     * 而 run_code 早就被外层 timeout 强杀、模型永远看不到任何错误反馈。
     * 处理方式：设 `lastError` 并在 event 总线写一条 synthetic result，方便 UI 与后续诊断。
     * guest 本身不会因这条 synthetic result 解绑（它还在 poll 自己的合法 callId），但错误信息
     * 至少被留存在 `run_code` 的返回体 `last_sub_call_error` 字段里。
     */
    private suspend fun handle(line: String) {
        val req = PtcIpc.decodeRequest(line)
        if (req == null) {
            val msg = "PTC 请求 JSON 格式非法（已跳过）: ${line.take(160)}"
            lastError = msg
            emit(PtcEventKind.PTC_RESULT, badCallId(), "__parse_error__", error = msg)
            return
        }
        // callId 由 guest 生成，必须限制在安全字符集内，否则直接拼进结果文件路径会路径穿越。
        if (!isValidCallId(req.callId)) {
            val msg = "非法 callId（拒绝路径穿越）: ${req.callId.take(64)}"
            lastError = msg
            // 无法用非法 callId 安全拼装结果文件名；guest 端的 _invoke 会等到它自己的
            // 600s deadline 才自行结束。这里至少留 event + lastError 供后续诊断。
            emit(PtcEventKind.PTC_RESULT, badCallId(), req.name, error = msg)
            return
        }
        emit(PtcEventKind.PTC_CALL, req.callId, req.name, arguments = req.arguments)

        val started = System.currentTimeMillis()
        val result = dispatchOne(req.name, req.callId, req.arguments)
        val elapsedMs = System.currentTimeMillis() - started
        totalDispatchMs += elapsedMs
        recordCallSummary(req.name, result.error == null, elapsedMs, result.error)

        writeResult(result)
        emit(
            PtcEventKind.PTC_RESULT, req.callId, req.name,
            result = result.value, error = result.error
        )
    }

    private fun badCallId(): String = "bad_${System.nanoTime()}"

    /**
     * callId 仅允许 `[A-Za-z0-9_-]`，长度 1..64。guest 侧生成 `c_<hex>` 满足此约束；
     * 任何含 `/` `..` 等非法字符的请求在此被拒，从根上消除结果文件路径穿越。
     */
    private fun isValidCallId(id: String): Boolean =
        id.length in 1..64 && id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    /**
     * P1-C：改挂 suspend；直接 `withTimeout` 包住 suspend `toolbox.dispatch`，不再包一层 runBlocking。
     * 好处：
     *   1) `Dispatchers.Main` 挂死的 handler 不会把 pump 卡在半阻塞状态（原 runBlocking 会）
     *   2) withTimeout 直接 cancel 当前 coroutine，无需 runBlocking 线程等
     */
    private suspend fun dispatchOne(name: String, callId: String, arguments: String): PtcCallResult {
        if (name == RUN_CODE_TOOL) {
            return PtcCallResult(callId, error = "PTC 程序内禁止递归调用 $RUN_CODE_TOOL（避免会话嵌套失控）")
        }
        if (!allows(name)) {
            return PtcCallResult(callId, error = "当前预设不允许在程序内调用工具: $name")
        }
        if (counter.incrementAndGet() > maxCalls) {
            return PtcCallResult(callId, error = "PTC 子调用次数超过上限 $maxCalls，请改写为批处理/循环复用单次结果")
        }
        return try {
            // 严格分发：schema 违规 / 未知工具 / handler 抛错都抛 ToolCallException（guest 侧 ToolCallError），
            // 与模型直调路径共用同一份校验契约。
            val value = withTimeout(callTimeoutMs) { toolbox.dispatchOrThrow(name, arguments) }
            // ask_user 子调用：dispatch 只产出 {"action":"ask_user",...} 字符串，不会弹窗。
            // 交给已注册的 PtcAskUserBridge 弹窗并挂起等待用户回答，再把回答作为结果写回程序；
            // 未注册处理器时按普通结果透传（保持旧行为）。
            if (name == "ask_user" && value.trimStart().startsWith(ASK_USER_ACTION_PREFIX) && PtcAskUserBridge.available()) {
                val answer = PtcAskUserBridge.awaitUserInput(value)
                if (answer != null) return PtcCallResult(callId, value = answer)
            }
            PtcCallResult(callId, value = value)
        } catch (e: Exception) {
            val msg = when (e) {
                is kotlinx.coroutines.TimeoutCancellationException -> "子调用超时（" + callTimeoutMs + "ms）: " + name
                is com.mcp.toolbox.ToolCallException -> e.message ?: "子调用失败: " + name
                else -> e.message ?: e::class.simpleName ?: "子调用失败"
            }
            lastError = msg
            PtcCallResult(callId, error = msg)
        }
    }

    /** 先写临时文件再 rename：避免 guest 读到写了一半的 JSON。 */
    private fun writeResult(result: PtcCallResult) {
        if (!isValidCallId(result.callId)) return // 双保险：非法 callId 不落盘
        val tmp = File(resDir, "${result.callId}.json.tmp")
        val dst = File(resDir, "${result.callId}.json")
        runCatching {
            tmp.writeText(PtcIpc.encodeResult(result), Charsets.UTF_8)
            tmp.renameTo(dst)
        }.onFailure {
            runCatching { dst.writeText(PtcIpc.encodeResult(result), Charsets.UTF_8) }
        }
    }

    private fun emit(
        kind: PtcEventKind,
        callId: String,
        name: String,
        arguments: String = "",
        result: String? = null,
        error: String? = null
    ) {
        runCatching {
            onEvent(
                PtcDispatchEvent(
                    kind = kind.type,
                    callId = callId,
                    name = name,
                    parentId = parentId,
                    depth = depth,
                    description = description,
                    arguments = arguments,
                    result = result?.take(RESULT_EVENT_LIMIT),
                    error = error
                )
            )
        }
        seq.incrementAndGet()
    }

    /**
     * P1-D：启动前的一次性端到端自校验。
     *
     * 目的：任何**协议漂移 / 路径不可写 / 序列化库差异** 都在 start 阶段就暴露，
     * 而不是运行到 guest `_invoke` 的 600s deadline 才无信号地挂掉。
     *
     * 校验内容：
     * 1) 构造真实 PtcCallRequest → 编码为 JSONL 行 → 解码 → 对比 callId / name / arguments；
     * 2) 构造 PtcCallResult → 写入 resDir 的 `<callId>.json.tmp` → rename 到 `<callId>.json` →
     *    读出并解码校验；随后清理临时文件。
     *
     * 失败即抛 [IllegalStateException]，由 start() 冒泡到 run_code，模型立刻看见诊断信息。
     */
    private fun selfCheckOrFail() {
        val probeId = "selfcheck_${System.nanoTime()}"

        // (1) 请求编解码往返
        val probeRequest = PtcCallRequest(
            callId = probeId,
            name = "selfcheck_probe",
            arguments = "{}"
        )
        val fixtureLine = PtcIpc.encodeRequest(probeRequest)
        val decoded = PtcIpc.decodeRequest(fixtureLine)
            ?: throw IllegalStateException(
                "PTC 启动自校验失败：encodeRequest 输出无法被 decodeRequest 解析（fixture: " +
                        fixtureLine.take(160) + "）"
            )
        if (decoded.callId != probeId || decoded.name != "selfcheck_probe" || decoded.arguments != "{}") {
            throw IllegalStateException(
                "PTC 启动自校验失败：请求字段不守恒（round-trip mismatch，callId/name/arguments 应保持一致）"
            )
        }

        // (2) 结果写入 resDir + rename 可行性
        val probeResult = PtcCallResult(probeId, value = "selfcheck_ok")
        val tmpPath = File(resDir, "$probeId.json.tmp")
        val dstPath = File(resDir, "$probeId.json")
        try {
            tmpPath.writeText(PtcIpc.encodeResult(probeResult), Charsets.UTF_8)
            if (!tmpPath.renameTo(dstPath)) {
                throw IllegalStateException(
                    "PTC 启动自校验失败：resDir 结果 tmp→rename 失败（${resDir.absolutePath}）"
                )
            }
            val readBack = PtcIpc.decodeResult(dstPath.readText(Charsets.UTF_8))
            if (readBack == null || readBack.callId != probeId || readBack.value != "selfcheck_ok") {
                throw IllegalStateException(
                    "PTC 启动自校验失败：encodeResult/decodeResult 往返不一致"
                )
            }
        } finally {
            runCatching { tmpPath.delete() }
            runCatching { dstPath.delete() }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10L
        const val RESULT_EVENT_LIMIT = 2000
    }
}
