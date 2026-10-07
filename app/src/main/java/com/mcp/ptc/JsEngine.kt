package com.mcp.ptc

import kotlinx.coroutines.asCoroutineDispatcher
import android.util.Log
import com.mcp.toolbox.ToolCallException
import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.ptc.PtcDispatchEvent
import com.mcp.toolbox.ptc.PtcEventKind
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.atomic.AtomicInteger

/** JS dispatch 单次调用的最大时长（与外层 ChatBridge 120s 超时对齐；30s 缓冲）。 */
private const val JS_DISPATCH_TIMEOUT_MS = 280_000L
private const val TAG_JS = "JsEngine"

/** 指令观察者检查间隔（条）：约每 1 万条指令检查一次取消标志，开销可忽略。 */
private const val INSTRUCTION_CHECK_INTERVAL = 10_000

/**
 * P1-C：run_code 的 JS 执行引擎（in-JVM Rhino）。
 *
 * 与老 Python/Node 分支相比，完全去掉 PRoot + 跨进程 JSONL 轮询。
 * 执行链：`model → run_code → JsEngine.run → Rhino eval → bridge.dispatch → Toolbox.dispatch`
 *
 * JS 侧 API：`tools.<name>(arg)`（单 object 参数），返回字符串结果。
 *
 * @author auto (P1-C)
 */
object JsEngine {

    private const val TAG = TAG_JS
    // JS_DISPATCH_TIMEOUT_MS 已提到文件级常量，供 JsDispatchBridge 共用

    data class JsResult(
        val stdout: String,
        val ret: String?,
        val err: String?,
        val costMs: Long,
        /** 程序内子调用清单（工具名/成败/耗时），随 run_code 结果回前端渲染（上限见 MAX_CALLS_IN_RESULT）。 */
        val calls: List<PtcCallSummary> = emptyList(),
        /** 子调用总次数（不受清单上限影响，用于 tool_calls 统计）。 */
        val callCount: Int = 0,
    )

    /**
     * run_code 的 JS 专用执行线程（单线程）：避免占用共享 IO 池，也让「主线程守卫」有确定语义。
     */
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ptc-js") }
            .asCoroutineDispatcher()

    fun run(
        jsCode: String,
        toolbox: Toolbox,
        allows: (String) -> Boolean,
        maxCalls: Int,
        timeoutSeconds: Long,
        parentId: String,
        description: String,
        depth: Int,
        /** 主线程判定（可注入以便单测）：主线程执行 JS 会阻塞 UI 并可能死锁，直接拒绝。 */
        // 注意：必须要求 myLooper() 非空——本地 JVM 单测里 Looper 是 Android 桩，两者都为 null，
        // 若只比较相等会被误判成主线程而拒绝执行。
        isMainThread: () -> Boolean = {
            val looper = android.os.Looper.myLooper()
            looper != null && looper == android.os.Looper.getMainLooper()
        },
    ): JsResult {
        if (isMainThread()) {
            return JsResult(
                stdout = "",
                ret = null,
                err = "run_code 不能在主线程执行：会阻塞 UI 并可能与工具回调死锁；请从 IO 线程调用",
                costMs = 0,
            )
        }
        Log.d(TAG, "[JS enter] parent=$parentId codeLen=${jsCode.length} maxCalls=$maxCalls")
        val started = System.nanoTime()

        // 工具名 JSON 由 Toolbox 缓存（B7：热路径不再每次重建/转义）
        val toolNamesJson = try { toolbox.namesJson() } catch (_: Throwable) { "[]" }

        val bridge = JsDispatchBridge(toolbox, allows, maxCalls, parentId, description, depth)

        // 用户代码前面垫了多少行脚手架——报错行号必须减掉它才能还原成「程序第 N 行」
        var preludeLineCount = 0
        val source = buildString {
            appendLine("var _LOG = [];")
            appendLine("var __err = '';")
            appendLine("var __ret = undefined;")
            appendLine("var __LOG_FINAL = '';")
            appendLine("var __RET_STR = '';")
            appendLine("var __ERR_STR = '';")
            appendLine("")
            appendLine("function _log() {")
            appendLine("  for (var i = 0; i < arguments.length; i++) {")
            appendLine("    var v = arguments[i];")
            appendLine("    if (typeof v === 'string') _LOG.push(v);")
            appendLine("    else if (v === null || v === undefined) _LOG.push('');")
            appendLine("    else { try { _LOG.push(JSON.stringify(v)); } catch(e) { _LOG.push(String(v)); } }")
            appendLine("  }")
            appendLine("}")
            appendLine("function log() { _log.apply(this, arguments); }")
            appendLine("var print = log;")
            appendLine("var console = { log: log, info: log, warn: log, error: log, debug: log };")
            appendLine("")
            appendLine("function ToolCallError(toolName, message) {")
            appendLine("  var e = new Error(message);")
            appendLine("  e.name = 'ToolCallError';")
            appendLine("  e.toolName = toolName;")
            appendLine("  return e;")
            appendLine("}")
            appendLine("var _TOOL_NAMES = ${toolNamesJson};")
            appendLine("var tools = (function() {")
            appendLine("  var t = {};")
            appendLine("  for (var i = 0; i < _TOOL_NAMES.length; i++) {")
            appendLine("    var name = _TOOL_NAMES[i];")
            appendLine("    t[name] = (function(n) {")
            appendLine("      return function(a) {")
            appendLine("        var json;")
            appendLine("        if (arguments.length === 0) { json = '{}'; }")
            appendLine("        else if (typeof a === 'string') { json = a; }")
            appendLine("        else { try { json = JSON.stringify(a || {}); } catch(e) { json = '{}'; } }")
            appendLine("        var r = _dispatch(n, json);")
            appendLine("        if (typeof r === 'string' && r.indexOf('__PTC_ERR__') === 0) {")
            appendLine("          var o; try { o = JSON.parse(r.substring(11)); } catch (e2) { o = { toolName: n, message: String(r) }; }")
            appendLine("          throw ToolCallError(o.toolName || n, o.message || 'tool call failed');")
            appendLine("        }")
            appendLine("        return r;")
            appendLine("      };")
            appendLine("    })(name);")
            appendLine("  }")
            appendLine("  return t;")
            appendLine("})();")
            appendLine("")
            appendLine("")
            appendLine("try {")
            appendLine("  __ret = (function() {")
            preludeLineCount = lineSequence().count()
            for (ln in jsCode.split("\n")) appendLine("    $ln")
            appendLine("    return undefined;")
            appendLine("  })();")
            appendLine("} catch (e) {")
            appendLine("  __err = (e && e.name ? e.name : 'Error') + (e && e.toolName ? '[' + e.toolName + ']' : '') + ': ' + String(e && (e.message || e));")
            appendLine("}")
            appendLine("")
            appendLine("if (typeof __ret === 'undefined') { __RET_STR = ''; }")
            appendLine("else if (__ret === null) { __RET_STR = 'null'; }")
            appendLine("else if (typeof __ret === 'string') { __RET_STR = __ret; }")
            appendLine("else { try { __RET_STR = JSON.stringify(__ret); } catch(e) { __RET_STR = String(__ret); } }")
            appendLine("__ERR_STR = __err;")
            appendLine("__LOG_FINAL = JSON.stringify(_LOG);")
        }

    // ── 协作式取消（A2）─────────────────────────────────────────────
        // Rhino 同步执行不能从外部强杀；用解释执行 + 指令观察者，每 N 条指令检查取消标志，
        // 让「停止生成」能中断死循环/长任务（此前只能等 280s 超时）。
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                optimizationLevel = -1              // 解释执行：只有此模式才会回调 observeInstructionCount
                languageVersion = Context.VERSION_ES6
                instructionObserverThreshold = INSTRUCTION_CHECK_INTERVAL
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                // 精确到「当前会话」：原实现调无参 isRequested()，会遍历所有 sid 桶 ——
                // 任一历史会话点过停止就误杀当前 run_code。改为主链路用 PtcAudit.current()
                // 拿 sid 走带参重载；拿不到 sid（子 Agent 等）退回仅看 globalFlag 的无参重载。
                val sid = com.mcp.ptc.PtcAudit.current()
                val cancelled =
                    if (sid != null) PtcCancellation.isRequested(sid)
                    else PtcCancellation.isRequested()
                if (cancelled) throw PtcCancelledException()
            }
        }

        return try {
            factory.call { cx ->
                // deny-all：JS 不能访问任何 Java 类（Packages / java.*）。宿主能力只经 _dispatch 一个函数进入，
                // 而 _dispatch 是 Rhino BaseFunction（Scriptable），JS 侧看不到 getClass 等 Java 反射面。
                cx.setClassShutter { _: String -> false }
                val scope = cx.initStandardObjects()
                installJavaAccessTrap(scope)
                scope.put("_dispatch", scope, JDispatch { name, json -> bridge.dispatch(name, json) })

                cx.evaluateString(scope, source, "runCode/$parentId", 1, null)

                val errStr: String = try { scope.get("__ERR_STR", scope).toString() } catch (_: Throwable) { "" }
                val retStr: String = try { scope.get("__RET_STR", scope).toString() } catch (_: Throwable) { "" }
                val logJson: String = try { scope.get("__LOG_FINAL", scope).toString() } catch (_: Throwable) { "[]" }

                // 用 kotlinx.serialization 解析日志数组：org.json 在本地 JVM 单测里是 Android 桩
                // （testOptions.unitTests.isReturnDefaultValues=true 会让 JSONArray 返回空），
                // 换掉后可被单测真实覆盖。
                val stdoutLines: List<String> = try {
                    val el = kotlinx.serialization.json.Json.parseToJsonElement(logJson)
                    val arr = el as? kotlinx.serialization.json.JsonArray
                    arr?.mapNotNull {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    } ?: emptyList()
                } catch (_: Throwable) { emptyList() }

                val costMs = (System.nanoTime() - started) / 1_000_000
                val ret = if (retStr.isEmpty()) null else retStr
                val err = if (errStr.isEmpty()) null else errStr

                Log.d(TAG, "[JS exit] parent=$parentId [${costMs}ms] logN=${stdoutLines.size} ret=${ret?.length ?: 0}B err=${err?.length ?: 0}B calls=${bridge.callsCount}")
                JsResult(
                    stdout = stdoutLines.joinToString("\n"),
                    ret = ret,
                    err = err,
                    costMs = costMs,
                    calls = bridge.callSummaries,
                    callCount = bridge.callsCount,
                )
            }
        } catch (e: PtcCancelledException) {
            val costMs = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "[JS cancel] parent=$parentId [${costMs}ms] calls=${bridge.callsCount}")
            JsResult(
                stdout = "", ret = null, err = e.message, costMs = costMs,
                calls = bridge.callSummaries, callCount = bridge.callsCount,
            )
        } catch (e: RhinoException) {
            // 解析期错误（语法不支持等）会逃到这里：脚手架里那层 JS try/catch 只能覆盖运行期，
            // 编译期根本进不去。此前它裸奔到调用方，被粗糙包成
            //   「JS execution failed: syntax error (runCode/…#53)」
            // 那个 #53 是**包装后**源码的行号（前置 51 行脚手架），模型看不出对应自己程序的第 2 行，
            // 于是完全无法自愈。这里归一化成用户坐标 + 出错行原文 + 已知缺口的改法。
            val costMs = (System.nanoTime() - started) / 1_000_000
            val msg = describeJsFailure(e, jsCode, preludeLineCount)
            Log.w(TAG, "[JS syntax] parent=$parentId [${costMs}ms] $msg")
            JsResult(
                stdout = "", ret = null, err = msg, costMs = costMs,
                calls = bridge.callSummaries, callCount = bridge.callsCount,
            )
        }
    }

    /**
     * 把 Rhino 的失败翻译成模型能直接照做的信息：**用户坐标**的行号、出错行原文、
     * 以及已知语法缺口对应的改法。
     */
    private fun describeJsFailure(e: RhinoException, userCode: String, preludeLines: Int): String {
        // +1：实测 Rhino 的 lineNumber() 比用户代码真实行号**恒小 1**（脚手架 k 行时，
        // 用户第 N 行报 k+N-1）。这个偏移由 JsEngineTest 的多行用例锁定，防止将来漂移。
        val userLine = e.lineNumber() - preludeLines + 1
        val lines = userCode.split("\n")
        val src = if (userLine in 1..lines.size) lines[userLine - 1].trim() else null
        // e.message 形如「syntax error (runCode/xxx#52)」：源名带着一大串 id 与描述，去掉
        val raw = (e.message ?: "JS 执行失败").substringBefore(" (runCode/").trim()

        val sb = StringBuilder(if (e is EvaluatorException) "语法错误" else "JS 执行失败")
        if (src != null) sb.append("（程序第 ").append(userLine).append(" 行）")
        sb.append("：").append(raw)
        if (!src.isNullOrEmpty()) sb.append('\n').append("出错行：").append(src)
        syntaxHint(src)?.let { sb.append('\n').append("改法：").append(it) }
        return sb.toString()
    }

    /**
     * Rhino（VERSION_ES6）**实测**的语法缺口 → 可直接照抄的改法。
     *
     * 最坑的是第一条：`for (const x of arr)` 解析失败，而 `for (let x of arr)` /
     * `for (var x of arr)` 完全正常——差别只在 const，而 const 恰恰是现代 JS 里最自然的写法。
     * 缺了这条提示，模型只会拿到一句「syntax error」，然后原样再写一遍。
     */
    private fun syntaxHint(line: String?): String? {
        if (line == null) return null
        return when {
            line.contains(Regex("for\\s*\\(\\s*const\\b")) ->
                "Rhino 不支持 for (const x of arr)。把 const 换成 let 或 var 即可（for (let x of arr) 实测可用），或改用 arr.forEach(function(x){ ... })。"
            line.contains("[...") ->
                "Rhino 不支持展开运算符 [...]。改用 concat / slice / apply，或手动循环构造数组。"
            line.contains(Regex("\\bclass\\s+\\w+")) ->
                "Rhino 不支持 class 声明。改用 function 构造器 + prototype。"
            line.contains(Regex("function\\s*\\w*\\s*\\([^)]*=")) ->
                "Rhino 不支持函数默认参数。改在函数体内判断 undefined 再赋默认值。"
            else -> null
        }
    }

    /**
     * Rhino 默认会把 Java 互操作入口挂到全局（Packages / java / javax / …）。
     * [Context.setClassShutter] 已把它们 deny-all 封死，**安全上没有问题**；
     * 问题在报错指向不了真因：
     *
     *   java.util.Base64 解析结果是 NativeJavaPackage（不是类对象），对它取方法再调用只会得到
     *     Cannot call property getEncoder in object [JavaPackage java.util.Base64].
     *     It is not a function, it is "object".
     *   读起来像「这个类没这个方法」，于是模型压根意识不到「本环境禁止 Java 互操作」这条边界，
     *   只会换个写法继续撞（实测连续两次都栽在这里）。
     *
     * 这里把这些全局换成「一碰就抛」的陷阱：第一次访问就给出写明边界与替代方案的错误，
     * 让模型能一次自愈。classShutter 保留不动——陷阱负责可读性，shutter 负责安全性（纵深防御）。
     */
    private val JAVA_GLOBAL_NAMES = listOf(
        "Packages", "java", "javax", "org", "com", "edu", "net", "javafx",
        "JavaAdapter", "JavaImporter", "importClass", "importPackage", "getClass"
    )

    private fun installJavaAccessTrap(scope: Scriptable) {
        for (name in JAVA_GLOBAL_NAMES) {
            // Rhino 把这些定义为 PERMANENT 属性，个别版本可能拒绝覆盖：失败就退回
            // classShutter 的原始行为（怪错但安全），不影响正确性。
            runCatching { scope.put(name, scope, JavaAccessTrap(name)) }
        }
    }

    /** 「禁止访问 Java 类」陷阱：任何属性访问 / 调用 / 构造都抛出可自愈的错误。 */
    private class JavaAccessTrap(private val root: String) : ScriptableObject(), Function {

        private fun deny(path: String): Nothing = throw JavaScriptException(
            "本环境禁止访问 Java 类（" + path + "）。JS 跑在禁用全部 Java 互操作的 Rhino 沙箱里：" +
                "java.* / Packages / importClass / Java.type 一律不可用，也没有 require / process / " +
                "fetch / Buffer / setTimeout。宿主能力只能通过 tools.工具名({ 参数: 值 }) 调用；" +
                "base64、JSON、时间戳等请用 tools.* 里的对应工具，或在程序内自己用 JS 实现。",
            "runCode", 0
        )

        override fun getClassName(): String = "JavaAccessTrap"
        override fun get(name: String, start: Scriptable): Any = deny(root + "." + name)
        override fun get(index: Int, start: Scriptable): Any = deny(root + "[" + index + "]")
        override fun has(name: String, start: Scriptable): Boolean = true
        override fun has(index: Int, start: Scriptable): Boolean = true
        override fun call(
            cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any>
        ): Any = deny(root + "(...)")
        override fun construct(cx: Context, scope: Scriptable, args: Array<Any>): Scriptable =
            deny("new " + root + "(...)")
    }
}


/**
 * PTC JS bridge：由 JS 调用宿主函数 `_dispatch(name, json)`（JDispatch，无 Java 反射面）。
 * 每个 dispatch 同步运行 suspend `Toolbox.dispatchOrThrow`（`runBlocking` + `withTimeout`）；
 * 失败抛 ToolCallException → JDispatch 返回哨兵串 → JS 侧还原成 ToolCallError（name/toolName/message）。
 */
class JsDispatchBridge(
    private val toolbox: Toolbox,
    private val allows: (String) -> Boolean,
    private val maxCalls: Int,
    private val parentId: String,
    private val description: String,
    private val depth: Int,
) {
    private val seq = AtomicInteger(1)
    private var calls = 0
    val callsCount: Int get() = calls

    /**
     * 程序内子调用清单（工具名 / 成败 / 耗时），随 JsResult 回到 run_code 结果 JSON，
     * 供前端在 run_code 卡片里渲染「调用了哪些工具、每个成没成」。
     * 上限 [MAX_CALLS_IN_RESULT]：超出只计数不再逐条展开，避免结果体积失控。
     */
    private val callSummaryList = java.util.Collections.synchronizedList(ArrayList<PtcCallSummary>())
    val callSummaries: List<PtcCallSummary> get() = synchronized(callSummaryList) { ArrayList(callSummaryList) }

    private fun recordCallSummary(name: String, ok: Boolean, ms: Long, error: String?) {
        if (callSummaryList.size >= MAX_CALLS_IN_RESULT) return
        callSummaryList.add(PtcCallSummary(name = name, ok = ok, ms = ms, error = error))
    }

    fun dispatch(toolName: String, argsJsonStr: String): String {
        val idx = seq.getAndIncrement()
        val callId = parentId.take(8).ifEmpty { "js" } + ".js.$idx"
        val t0 = System.nanoTime()
        try { Log.d(TAG, "[$parentId] -> $callId $toolName args=${argsJsonStr.take(120)}") } catch (_: Throwable) {}
        PtcEventBus.emit(
            PtcDispatchEvent(
                kind = PtcEventKind.PTC_CALL.type,
                callId = callId,
                name = toolName,
                parentId = parentId,
                depth = depth,
                description = description,
                arguments = argsJsonStr,
            )
        )
        return try {
            if (calls >= maxCalls) throw ToolCallException(toolName, "PTC 子调用次数达上限 $maxCalls")
            calls++
            if (!allows(toolName)) throw ToolCallException(toolName, "当前预设不允许在程序内调用工具: $toolName")
            val payload = if (argsJsonStr.isEmpty()) "{}" else argsJsonStr
            // 严格分发：schema 违规/未知工具/handler 抛错都变成 ToolCallException（JS 侧 ToolCallError）
            var res = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(JS_DISPATCH_TIMEOUT_MS) { toolbox.dispatchOrThrow(toolName, payload) }
            }
            // ask_user 子调用：dispatch 只产出 {"action":"ask_user",...} 字符串，不会弹窗。
            // 交给 PtcAskUserBridge 弹窗并挂起等待用户回答，把回答作为结果回给程序；
            // 未注册处理器时按普通结果透传（保持旧行为）。
            if (toolName == "ask_user" && res.trimStart().startsWith(ASK_USER_ACTION_PREFIX) && PtcAskUserBridge.available()) {
                val answer = kotlinx.coroutines.runBlocking { PtcAskUserBridge.awaitUserInput(res) }
                if (answer != null) res = answer
            }
            val cost = (System.nanoTime() - t0) / 1_000_000
            recordCallSummary(toolName, ok = true, ms = cost, error = null)
            try { Log.d(TAG, "[$parentId] <- $callId $toolName [${cost}ms] res=${res.take(120)}") } catch (_: Throwable) {}
            PtcEventBus.emit(
                PtcDispatchEvent(
                    kind = PtcEventKind.PTC_RESULT.type,
                    callId = callId,
                    name = toolName,
                    parentId = parentId,
                    depth = depth,
                    description = description,
                    arguments = argsJsonStr,
                    result = res,
                )
            )
            res
        } catch (e: Throwable) {
            val cost = (System.nanoTime() - t0) / 1_000_000
            val errMsg = "PTC JS dispatchError [${cost}ms]: ${e.javaClass.simpleName}: ${e.message ?: e}"
            recordCallSummary(toolName, ok = false, ms = cost, error = e.message ?: e.javaClass.simpleName)
            try { Log.w(TAG, "[$parentId] !! $callId $toolName $errMsg") } catch (_: Throwable) {}
            PtcEventBus.emit(
                PtcDispatchEvent(
                    kind = PtcEventKind.PTC_RESULT.type,
                    callId = callId,
                    name = toolName,
                    parentId = parentId,
                    depth = depth,
                    description = description,
                    arguments = argsJsonStr,
                    error = errMsg,
                )
            )
            throw when (e) {
                is ToolCallException -> e
                else -> ToolCallException(toolName, errMsg, e)
            }
        }
    }

    companion object {
        private const val TAG = "JsEngine"
    }
}
