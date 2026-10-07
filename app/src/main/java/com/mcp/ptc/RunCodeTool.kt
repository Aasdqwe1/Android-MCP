package com.mcp.ptc

import android.content.Context
import com.mcp.executeBash
import com.mcp.preset.PresetRuntime
import com.mcp.requireStr
import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.ptc.PtcDispatchEvent
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.UUID

/** `run_code` 工具名常量（PTC 桥内据此拒绝递归调用）。 */
const val RUN_CODE_TOOL = "run_code"

/** run_code 支持的运行时语言（未知值显式报错，不静默回退）。 */
private val SUPPORTED_LANGUAGES = setOf("javascript", "js", "python", "node", "nodejs")

/**
 * PTC 事件总线：把程序内部子调用事件广播给会话日志 / UI。
 *
 * 之所以用总线而不是把 listener 塞进工具定义：`run_code` 的 handler 只拿到参数 JSON，
 * 拿不到「当前会话」上下文；而 ChatBridge 等订阅方生命周期长于单次工具调用。
 */
object PtcEventBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(PtcDispatchEvent) -> Unit>()

    fun subscribe(l: (PtcDispatchEvent) -> Unit) { listeners.add(l) }
    fun unsubscribe(l: (PtcDispatchEvent) -> Unit) { listeners.remove(l) }
    fun emit(e: PtcDispatchEvent) { listeners.forEach { runCatching { it(e) } } }
}

/**
 * PTC 子调用「向用户提问」桥：把程序内 `tools.ask_user(...)` 转成真实的弹窗并挂起等待回答。
 *
 * 背景：ask_user 的弹窗拦截逻辑在 ChatBridge（模型直调路径）。PTC 模式下 ask_user 是
 * run_code 的**程序内子调用**，走 PtcBridge / JsDispatchBridge 独立分发链，不会触发弹窗——
 * 若不做处理，子调用只会拿到一段 {"action":"ask_user",...} 字符串，用户根本收不到提问，
 * 程序却继续往下跑（静默失效）。
 *
 * 这里用与 [PtcEventBus] 同款的可注册全局处理器：ChatBridge 在启动时 [register] 一个实现
 * （复用其 pendingUserInput 弹窗机制），两条子调用路径检测到 ask_user 时 [awaitUserInput] 挂起，
 * 直到用户提交回答再作为子调用结果写回程序。未注册时返回 null，调用方按普通结果透传（保持旧行为）。
 */
/** ask_user 工具产出的特殊结果前缀：命中即交由 [PtcAskUserBridge] 弹窗等待用户输入。 */
internal const val ASK_USER_ACTION_PREFIX = "{\"action\":\"ask_user\""

object PtcAskUserBridge {
    /**
     * suspend 处理器表：入参为 ask_user 工具产出的原始 JSON，返回用户回答；不处理时返回 null。
     *
     * 为什么是「表」而不是单个 handler：
     * 进程内可同时存活多个 ChatBridge 实例（手机聊天桥 + WebApiServer 桌面桥），每个实例的
     * init 都会注册、destroy 都会反注册。原实现 `handler = h` 是覆盖式单值，后注册的实例会把
     * 先注册的挤掉；任一实例 destroy 又无脑置 null，把仍在运行的另一个实例的弹窗能力一并抹掉。
     * 后果：程序内 tools.ask_user(...) 只拿到原始 {"action":"ask_user",...} 字符串静默透传，
     * 用户完全看不到提问，程序却继续往下跑。改成注册表后多实例共存、各自注销互不影响。
     */
    private val handlers = java.util.concurrent.CopyOnWriteArrayList<suspend (String) -> String?>()

    fun register(h: suspend (String) -> String?) { handlers.addIfAbsent(h) }
    fun unregister(h: suspend (String) -> String?) { handlers.remove(h) }

    /** 是否至少有一个处理器（无则调用方跳过拦截，按普通工具结果处理）。 */
    fun available(): Boolean = handlers.isNotEmpty()

    /**
     * 挂起等待用户回答；无处理器或全部放弃处理时返回 null。
     * 依次询问各实例：能定位到发起会话的处理器会挂起并返回回答，其余返回 null 被跳过——
     * 因此一次提问只会弹一个窗，不会因多实例而重复。
     *
     * 异常处理是这个函数唯一需要小心的地方：`runCatching` 图省事会**吞掉控制流异常**。
     * handlePtcAskUser 用「抛异常」表达两种终止语义——
     *   - [PtcCancelledException]：用户点了停止；
     *   - [ToolCallException]：用户取消了提问（未作答）。
     * 两者都必须穿透到 JsDispatchBridge.dispatch 的 catch，才能被转成 JS 侧的 ToolCallError
     * 并终止程序。若在这里被吞成 null，调用方会拿着原始 {"action":"ask_user",...} 字符串
     * 当结果继续往下跑——用户明明取消了，程序却以为拿到了回答。
     * 协程取消（CancellationException）同理不能吞，否则 run_code 的取消失效。
     * 其余异常才按「这个实例处理不了」对待，继续询问下一个。
     */
    suspend fun awaitUserInput(payloadJson: String): String? {
        for (h in handlers) {
            val answer = try {
                h(payloadJson)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PtcCancelledException) {
                throw e
            } catch (e: com.mcp.toolbox.ToolCallException) {
                throw e
            } catch (e: Throwable) {
                // 该实例自身出错（如会话已销毁后的竞态）：跳过它，让其余实例有机会处理。
                null
            }
            if (answer != null) return answer
        }
        return null
    }
}

/**
 * PTC（Programmatic Tool Call）入口工具 —— 借鉴 deepseek-harness 的 `run_code` 模式。
 *
 * 模型写一段**异步函数体**，函数体内用 `await tools.工具名(参数)`（Node）或
 * `tools.工具名(**参数)`（Python）调用注册表里的工具。相对「一次一轮回」的传统
 * function calling，PTC 的价值：
 *
 * - **省上下文**：N 次工具调用的中间结果留在程序里，只有最终精简结果进入模型历史；
 * - **省往返**：循环 / 条件 / 批处理在程序内完成，不再每步都回模型决策；
 * - **不绕过 guard**：子调用仍经 [Toolbox.dispatch] 走同一条管线（白名单、预设过滤、
 *   参数校验、错误捕获、审计全部生效）。
 *
 * 执行链路：
 * ```
 * 模型 → run_code(code) → 宿主拼装 prelude+code+runner → 写脚本到 filesDir/ptc/<runId>
 *      → PtcBridge 启动（尾随读请求文件）→ PRoot 内 python3/node 执行
 *      → 程序内 tools.x() 写请求文件 → 宿主 dispatch → 写回结果文件 → 程序继续
 *      → 程序结束 → 停桥、清理、返回 stdout
 * ```
 */
fun runCodeTool(
    context: Context,
    toolboxProvider: () -> Toolbox,
    presetToolGate: () -> (String) -> Boolean = {
        // 流程控制工具（agent_complete / agent_progress）无条件放行：
        //  - 它们是子 Agent 工作流的终止/进度协议，不是业务工具，不该受预设白名单约束；
        //  - 否则子 Agent 在 run_code 程序内 `tools.agent_complete(...)` 会被 gate 拒
        //    （"当前预设不允许在程序内调用工具"），结果永远回不到主 Agent。
        // 见 com.mcp.core.prompt.PTC_DIRECT_ALLOWED 的同名豁免（模型直调侧）。
        { name ->
            name in com.mcp.core.prompt.PTC_DIRECT_ALLOWED ||
                (PresetRuntime.current?.allows(name) ?: true)
        }
    }
): ToolDef = tool(RUN_CODE_TOOL) {
    description = """
        执行一段**程序**，程序内部可直接调用本会话的工具（PTC，Programmatic Tool Call）。
        适用场景：需要**多次工具调用 + 中间结果加工**的任务（批量读文件再汇总、循环探测、先搜后改再验证），
        用一段程序替代多轮「调用→回模型→再调用」，显著省上下文与往返。

        **语法子集、沙箱边界与完整说明统一写在系统提示词的「程序内工具 SDK」段，以那段为准**，
        此处不重复（避免两处表述漂移）。要点：默认 language=javascript（in-JVM Rhino，同步，
        不支持 await/Promise）；程序里通过 `tools.工具名({ 参数: 值 })` 调用工具；禁止在程序内再调用
        $RUN_CODE_TOOL（防嵌套失控）。

        language 可选 python / node：走 PRoot 解释器（较慢），语法与错误类型不同（Python 关键字参数；
        Node 需 `await tools.x({...})`）。**SDK 段只保证 javascript 的签名**。
    """.trimIndent()
    string("code") {
        description = "程序体（Python 为 async 函数体缩进代码段；Node 为 async 函数体）。用 print/console.log 输出日志，用 return 返回结构化结果。"
        multiLine = true
    }
    string("description") {
        description = "这段文字会以 5-10 词主动语态概述本程序做什么，显示在 UI 上（如 \"统计各包内的 TODO 标记\"、\"读取失败测试及其夹具\"）。必填。"
        required = true
    }
    string("language") {
        description = "运行时语言：javascript（in-JVM Rhino，默认无 PRoot 跨进程）/ python（fallback：走 PRoot+python3）/ node（走 PRoot+node）。"
        required = false
        default("javascript")
    }
    integer("timeout_seconds") {
        description = "整段程序的执行超时（秒），默认 300，上限 3600"
        required = false
        default(300)
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val code = args.requireStr("code", RUN_CODE_TOOL)
                val description = args.requireStr("description", RUN_CODE_TOOL)
                if (description.trim().isEmpty()) {
                    return@runCatching buildJsonObject {
                        put("ok", false)
                        put("error", "invalid description: expected a non-empty string")
                    }.toString()
                }
                val lang = args["language"]?.jsonPrimitive?.contentOrNull ?: "javascript"
                // 语言白名单：未知值直接报错，避免静默回退到 Python 分支造成难查的失败
                if (lang !in SUPPORTED_LANGUAGES) {
                    return@runCatching buildJsonObject {
                        put("ok", false)
                        put("error", "unsupported language: " + lang + "（支持 javascript/python/node）")
                    }.toString()
                }
                val timeout = args["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 300L
                val safeTimeout = timeout.coerceIn(1, 3600)

                // P1-C：js/javascript 走 in-JVM Rhino（无跨进程 IPC，直接反射到 Toolbox.dispatch）
                if (lang == "javascript" || lang == "js") {
                    return@runCatching executeJsInJvm(
                        toolbox = toolboxProvider(),
                        allows = presetToolGate(),
                        description = description,
                        code = code,
                        timeoutSeconds = safeTimeout,
                    )
                }

                // 旧分支：python/node 走 PRoot + Python/Node 解释器 + PtcBridge 文件 IPC
                val runtime: CodeRuntime = when (lang) {
                    "node", "nodejs" -> NodeRuntime
                    else -> PythonRuntime
                }
                executeProgram(
                    context, toolboxProvider(), presetToolGate(), runtime,
                    description, code, safeTimeout
                )
            }.getOrElse { e ->
                buildJsonObject {
                    put("ok", false)
                    put("error", e.message?.take(500) ?: e::class.simpleName ?: "run_code 失败")
                }.toString()
            }
        }
    }
}

/** 一次 PTC 执行的完整生命周期：落盘脚本 → 起桥 → guest 执行 → 收尾清理。 */
private fun executeProgram(
    context: Context,
    toolbox: Toolbox,
    allows: (String) -> Boolean,
    runtime: CodeRuntime,
    description: String,
    code: String,
    timeoutSeconds: Long
): String {
    val runId = UUID.randomUUID().toString()
    // 宿主侧目录；PRoot 把 filesDir 绑定到 guest 的 /host/app，故两侧同一份文件。
    val hostDir = File(context.filesDir, "ptc/$runId").apply { mkdirs() }
    val resDir = File(hostDir, "res").apply { mkdirs() }
    val reqFile = File(hostDir, "req.jsonl").apply { if (!exists()) createNewFile() }
    val resultFile = File(hostDir, "result.json")
    val guestDir = "/host/app/ptc/$runId"
    val scriptName = if (runtime.language == "node") "program.js" else "program.py"
    val scriptFile = File(hostDir, scriptName)
    scriptFile.writeText(
        runtime.buildProgram(
            code, "$guestDir/req.jsonl", "$guestDir/res",
            minOf(timeoutSeconds, DEFAULT_CALL_TIMEOUT_SECONDS)
        ),
        Charsets.UTF_8
    )

    val bridge = PtcBridge(
        toolbox = toolbox,
        reqFile = reqFile,
        resDir = resDir,
        parentId = runId,
        description = description,
        allows = allows,
        onEvent = PtcEventBus::emit
    )
    bridge.start()
    val started = System.currentTimeMillis()
    val output = try {
        val cmd = runtime.interpreterArgs("$guestDir/$scriptName").joinToString(" ")
        executeBash(context, cmd, timeoutSeconds = timeoutSeconds)
    } finally {
        bridge.stop()
    }
    val elapsed = System.currentTimeMillis() - started

    // 结构化信封（A4）：runner 统一写 {"__ptc_ok":true,"value":...} / {"__ptc_ok":false,"error":...}。
    // 旧启发式（stdout 里出现 "error" 即失败）会误判正常输出，这里彻底去掉。
    val envelope = PtcResultEnvelope.parse(
        if (resultFile.exists()) resultFile.readText(Charsets.UTF_8) else null
    )
    val resultElement: JsonElement? = envelope.valueJson?.let {
        runCatching { Json.parseToJsonElement(it) }.getOrNull()
    }

    // 失败时保留脚本，便于用户/模型排查；成功则清理，避免 ptc 目录堆积。
    val failed = !envelope.ok
    if (!failed) runCatching { hostDir.deleteRecursively() }

    return buildJsonObject {
        put("ok", !failed)
        put("language", runtime.language)
        put("run_id", runId)
        put("description", description)
        put("logs", buildJsonArray {
            PtcOutputBudget.capLogs(output.lines()).forEach { add(JsonPrimitive(it)) }
        })
        resultElement?.let { el ->
            val (capped, truncated) = PtcOutputBudget.capResult(el.toString())
            if (truncated) {
                put("result", JsonPrimitive(capped))
                put("result_truncated", true)
            } else {
                put("result", el)
            }
        }
        put("tool_calls", bridge.callCount)
        // 程序内子调用清单：前端据此在 run_code 卡片里列出「调用了哪些工具、每个成没成」
        val summaries = bridge.callSummaries
        if (summaries.isNotEmpty()) {
            put("calls", summaries.toJsonArray())
            if (bridge.callCount > summaries.size) put("calls_truncated", true)
        }
        put("dispatch_ms", bridge.totalDispatchMs)
        put("elapsed_ms", elapsed)
        if (failed || bridge.lastError != null) {
            put("script_path", "$guestDir/$scriptName")
            (envelope.error ?: bridge.lastError)?.let { put("last_sub_call_error", it) }
        }
    }.toString()
}

/**
 * P1-C：in-JVM JS 执行分支。
 *
 * 与 [executeProgram] 对称，但无 PRoot/IPC：Rhino 引擎在当前 JVM 里直接执行代码，
 * `tools.<name>(arg)` 经 `JsDispatchBridge.dispatch` 反射回 [Toolbox]，全程同线程。
 *
 * 子调用的 PTC 事件仍然通过 PtcEventBus 广播（与 python/node 分支事件格式一致），
 * 保持会话日志/UI 审计链路不变。
 */
private suspend fun executeJsInJvm(
    toolbox: Toolbox,
    allows: (String) -> Boolean,
    description: String,
    code: String,
    timeoutSeconds: Long,
): String {
    val runId = UUID.randomUUID().toString()
    val parent = "runCode:$runId:$description"
    val started = System.currentTimeMillis()

    val jsResult = try {
        // 固定跑在 JS 专用线程：不与共享 IO 池争用，主线程守卫也在 JsEngine 内部兜底
        kotlinx.coroutines.withContext(JsEngine.dispatcher) {
            kotlinx.coroutines.withTimeout(timeoutSeconds * 1000L) {
                JsEngine.run(
                    jsCode = code,
                    toolbox = toolbox,
                    allows = allows,
                    maxCalls = 200,
                    timeoutSeconds = timeoutSeconds,
                    parentId = parent,
                    description = description,
                    depth = 0,
                )
            }
        }
    } catch (e: Throwable) {
        val errMs = System.currentTimeMillis() - started
        JsEngine.JsResult(
            stdout = "",
            ret = null,
            err = "JS execution failed: ${e.javaClass.simpleName}: ${e.message ?: e}",
            costMs = errMs,
        )
    }

    val elapsed = System.currentTimeMillis() - started
    val ok = jsResult.err == null
    return buildJsonObject {
        put("ok", ok)
        put("language", "javascript")
        put("run_id", runId)
        put("description", description)
        put("logs", buildJsonArray {
            PtcOutputBudget.capLogs(jsResult.stdout.lines()).forEach { add(JsonPrimitive(it)) }
        })
        jsResult.ret?.let { raw ->
            val (capped, truncated) = PtcOutputBudget.capResult(raw)
            put("result", JsonPrimitive(capped))
            if (truncated) put("result_truncated", true)
        }
        // 子调用清单 + 总次数：JS 分支原先缺 tool_calls（前端「调用了 N 次」无从显示）
        put("tool_calls", jsResult.callCount)
        if (jsResult.calls.isNotEmpty()) {
            put("calls", jsResult.calls.toJsonArray())
            if (jsResult.callCount > jsResult.calls.size) put("calls_truncated", true)
        }
        put("dispatch_ms", jsResult.costMs)
        put("elapsed_ms", elapsed)
        if (!ok) {
            put("error", jsResult.err ?: "javascript execution returned no value")
        }
    }.toString()
}
