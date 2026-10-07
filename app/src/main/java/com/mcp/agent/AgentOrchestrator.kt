package com.mcp.agent

import android.content.Context
import com.mcp.LogStore
import com.mcp.SessionStateStore
import com.mcp.compaction.ContextCompactor
import com.mcp.deepseek.AuthPrefs
import com.mcp.deepseek.DeepSeekException
import com.mcp.llm.MessageEvent
import com.mcp.core.llm.BackendType
import com.mcp.llm.LLMClient
import com.mcp.llm.LLMClientFactory
import com.mcp.llm.LLMConfig
import com.mcp.llm.LLMRequest
import com.mcp.serialization.McpJson
import com.mcp.toolbox.ToolCompiler
import com.mcp.toolbox.ToolMessages
import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.errorResult
import com.mcp.toolbox.parseToolArguments
import com.mcp.toolbox.ToolArgsParseException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 子 Agent 任务状态。
 */
enum class AgentTaskStatus {
    PENDING,    // 等待执行
    RUNNING,    // 执行中
    DONE,       // 已完成
    FAILED,     // 失败
    CANCELLED,  // 已取消
    SKIPPED     // 被跳过（并行关闭时，非首个委派）
}

/**
 * 单个子 Agent 任务的运行时状态。
 */
data class AgentTask(
    val id: Int,
    val agentType: AgentType,
    val task: String,
    val parentSessionId: String,
    val startingDirectory: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** fork 用的主会话服务端会话 id（DeepSeek 消息树同会话）。null = 新建会话。 */
    val forkSessionId: String? = null,
    /** fork 用的主会话续聊锚点（同消息 id 建兄弟分支）。null 表示从会话根开始。 */
    val forkParentMessageId: String? = null,
) {
    @Volatile var status: AgentTaskStatus = AgentTaskStatus.PENDING
    @Volatile var result: String? = null
    @Volatile var error: String? = null
    @Volatile var subSessionId: String? = null
    /** 子 Agent 通过 agent_complete 主动声明的最终结果；非空时 runAgent 立即结束并返回该值。 */
    @Volatile var completion: String? = null
    /** 子 Agent 通过 agent_progress 推送的进度信息（0-100，或 -1 表示无进度）。 */
    @Volatile var progress: Int = -1
    /** 子 Agent 通过 agent_progress 推送的最新进度描述消息。 */
    @Volatile var progressMessage: String? = null
}

/**
 * 跨 Agent 共享记忆条目：记录某子 Agent 的关键产出摘要，供后续子 Agent 参考复用。
 * 实现「结果缓存 / 共识摘要」，减少不同 Agent 对同一信息的重复探索。
 */
data class AgentMemoryEntry(
    val agentType: AgentType,
    val taskSummary: String,
    val resultSummary: String,
    val at: Long = System.currentTimeMillis()
)

/**
 * 多 Agent 编排器（Orchestrator）。
 *
 * 主控 LLM 通过 delegate_to_agent 工具将任务委派给子 Agent，
 * 编排器负责：
 * 1. 为子 Agent 创建独立的 DeepSeek 会话
 * 2. 注入 Agent 专属系统提示词 + 工具白名单
 * 3. 管理子 Agent 的工具调用循环（自动续聊，最多 N 轮）
 * 4. 收集子 Agent 的最终回复，返回给主控 LLM
 *
 * 使用方式：
 * ```
 * val orchestrator = AgentOrchestrator(auth, toolbox, context)
 * val result = orchestrator.delegate(AgentType.EXPLORE, "搜索所有 .kt 文件", parentSessionId)
 * ```
 */
class AgentOrchestrator(
    private val auth: AuthPrefs,
    private val toolbox: Toolbox,
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前正在执行的子 Agent 任务（agent_complete 工具写入完成信号用）。 */
    @Volatile
    private var currentTask: AgentTask? = null

    /** 任务 ID 计数器 */
    private val taskIdCounter = AtomicInteger(0)

    /** 活跃任务表（线程安全） */
    private val activeTasks = ConcurrentHashMap<Int, AgentTask>()

    /** 异步委派任务对应的 Job，用于真正取消（cancelTask 使用）。 */
    private val taskJobs = ConcurrentHashMap<Int, Job>()

    /** 当前正在委派的 Agent 类型集合（用于去重，防止同一轮委派两个相同类型的 Agent） */
    private val delegatingTypes = ConcurrentHashMap.newKeySet<AgentType>()

    /** 落盘互斥锁 */
    private val saveMutex = Mutex()

    /** 子 Agent 最大工具调用轮数（防止无限循环） */
    var maxRounds: Int = 8

    /** 子 Agent 超时时间（毫秒），默认 3 分钟 */
    var timeoutMs: Long = 180_000L

    /** 限流重试次数（与主 Agent 共用配置） */
    var retryMax: Int = 3

    /** 限流重试间隔（毫秒，与主 Agent 共用配置） */
    var retryIntervalMs: Long = 3_000L

    /** 子 Agent 并行执行开关（默认 false → 仅执行第一个委派，其余回报开关状态并跳过） */
    var parallelEnabled: Boolean = false

    /** 并行关闭时的「首个委派」占坑计数：CAS 保证同一时刻只有一个子 Agent 在跑 */
    private val activeDelegateCount = AtomicInteger(0)

    /** 跨 Agent 共享记忆（结果缓存 / 共识摘要），容量受限，供后续子 Agent 参考复用。 */
    private val sharedMemory = ConcurrentLinkedQueue<AgentMemoryEntry>()

    /** 共享记忆最大条数（防止随会话无限膨胀）。 */
    var maxMemoryEntries: Int = 12

    // ──────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────

    /**
     * 从 SharedPreferences 刷新设置（由 ChatBridge 在 init 和 delegate 前调用）。
     */
    fun refreshSettings() {
        val prefs = context.getSharedPreferences(com.mcp.MainActivity.PREF_TAB_MODE, android.content.Context.MODE_PRIVATE)
        maxRounds = prefs.getInt("agent_max_rounds", 8).coerceIn(1, 50)
        timeoutMs = prefs.getInt("agent_timeout_sec", 180).coerceIn(10, 3600) * 1000L
        retryMax = prefs.getInt("retry_max", 3).coerceIn(1, 20)
        retryIntervalMs = (prefs.getFloat("retry_interval_sec", 3f).coerceIn(0.5f, 30f) * 1000).toLong()
        parallelEnabled = prefs.getBoolean("agent_parallel", false)
    }

    /**
     * 同步委派任务给子 Agent（阻塞当前协程，等待子 Agent 完成后返回结果）。
     *
     * @param agentType 子 Agent 类型
     * @param task      任务描述
     * @param parentSessionId 主控会话 ID（用于日志关联）
     * @return 子 Agent 的最终回复
     */
    suspend fun delegate(
        agentType: AgentType,
        task: String,
        parentSessionId: String,
        startingDirectory: String? = null
    ): String {
        refreshSettings()  // 每次委派前刷新设置，确保使用最新配置

        // 去重检查：同一轮中相同类型的 Agent 只能委派一次
        if (!delegatingTypes.add(agentType)) {
            LogStore.w("AGENT", "重复委派被拒绝: ${agentType.name}")
            return buildDuplicateAgentResult(agentType)
        }

        return try {
            val taskId = taskIdCounter.incrementAndGet()
            val fork = parentSessionId.takeIf { it.isNotBlank() }?.let { SessionStateStore.forkAnchor(it) }
            val agentTask = AgentTask(
                id = taskId,
                agentType = agentType,
                task = task,
                parentSessionId = parentSessionId,
                startingDirectory = startingDirectory,
                forkSessionId = fork?.first,
                forkParentMessageId = fork?.second
            )
            activeTasks[taskId] = agentTask

            // 并行关闭时：同一时刻只允许一个子 Agent 在跑（CAS 占坑）。
            // 第一个占坑的成功执行；其余并发委派直接回报开关状态、跳过。
            if (!parallelEnabled) {
                if (!activeDelegateCount.compareAndSet(0, 1)) {
                    val msg = parallelDisabledMessage(agentType)
                    agentTask.status = AgentTaskStatus.SKIPPED
                    agentTask.error = msg
                    LogStore.w("AGENT", "[#$taskId] 并行关闭，跳过非首个委派: ${agentType.displayName}")
                    scope.launch {
                        kotlinx.coroutines.delay(60_000)
                        activeTasks.remove(taskId)
                    }
                    return buildParallelDisabledResult(agentType)
                }
                return try {
                    runDelegated(agentTask, taskId, agentType)
                } finally {
                    activeDelegateCount.set(0)
                }
            }

            return runDelegated(agentTask, taskId, agentType)
        } finally {
            delegatingTypes.remove(agentType)
        }
    }

    /**
     * 子 Agent 通过 agent_complete 主动声明任务完成。写入当前任务的 completion 信号，
     * runAgent 检测到后立即结束并返回该结果。
     * @return 写入成功与否的提示（作为工具结果回传给 LLM）
     */
    fun completeCurrentTask(result: String): String {
        val t = currentTask ?: return """{"error": "当前没有正在运行的子 Agent 任务，无法完成"}"""
        t.completion = result
        LogStore.i("AGENT", "[#${t.id}] 子Agent 主动完成任务, 结果长度=${result.length}")
        return """{"ok": true, "info": "任务已完成，结果已回报"}"""
    }

    /**
     * 子 Agent 通过 agent_progress 推送进度信息。不结束任务，仅更新进度字段。
     * @param message 进度描述信息
     * @param progress 进度百分比（0-100），传 -1 表示无进度
     * @return 写入成功与否的提示
     */
    fun updateProgress(message: String, progress: Int): String {
        val t = currentTask ?: return """{"error": "当前没有正在运行的子 Agent 任务，无法更新进度"}"""
        val clampedProgress = if (progress in 0..100) progress else -1
        t.progress = clampedProgress
        t.progressMessage = message
        LogStore.i("AGENT", "[#${t.id}] 子Agent 进度更新: $progress% - $message")
        return """{"ok": true, "progress": $clampedProgress, "message": "$message"}"""
    }

    /** 执行单个子 Agent 委派（被 delegate 在并行/串行分支下复用）。 */
    private suspend fun runDelegated(agentTask: AgentTask, taskId: Int, agentType: AgentType): String {
        try {
            agentTask.status = AgentTaskStatus.RUNNING
            val result = withTimeout(timeoutMs) {
                runAgent(agentTask)
            }
            agentTask.status = AgentTaskStatus.DONE
            agentTask.result = result
            remember(agentType, agentTask.task, result)
            LogStore.i("AGENT", "[#$taskId] ${agentType.displayName} 完成, 结果长度=${result.length}")
            return result
        } catch (e: Exception) {
            agentTask.status = AgentTaskStatus.FAILED
            val msg = when (e) {
                is kotlinx.coroutines.TimeoutCancellationException ->
                    "${agentType.displayName} 执行超时 (${timeoutMs / 1000}s)"
                else -> "${agentType.displayName} 执行失败: ${e.message}"
            }
            agentTask.error = msg
            LogStore.e("AGENT", "[#$taskId] $msg")
            return """{"error": "$msg"}"""
        } finally {
            // 延迟清理（保留一段时间供查询）
            scope.launch {
                kotlinx.coroutines.delay(60_000)
                activeTasks.remove(taskId)
            }
        }
    }

    /**
     * 并行关闭时，为被跳过的委派生成回报给 LLM 的结果：说明开关状态，并提示仅执行了第一个。
     */
    private fun buildParallelDisabledResult(agentType: AgentType): String {
        return JSONObject().apply {
            put("info", parallelDisabledMessage(agentType))
            put("parallel_enabled", false)
            put("agent", agentType.name)
        }.toString()
    }

    /** 并行关闭时被跳过委派的说明文案。 */
    private fun parallelDisabledMessage(agentType: AgentType): String {
        return "「Agent 并行」开关当前为关闭状态（agent_parallel = false）。同一时刻仅允许一个子 Agent 运行，" +
            "本回合仅执行了第一个委派任务（${agentType.displayName}），其余委派未执行。" +
            "如需多个子 Agent 并行执行，请在「设置 → 子 Agent 设置 → Agent 并行」中开启该开关，或改在后续回合逐个委派。"
    }

    /**
     * 相同 Agent 类型重复委派时，为被拒绝的委派生成回报结果。
     */
    private fun buildDuplicateAgentResult(agentType: AgentType): String {
        return JSONObject().apply {
            put("info", "Agent 类型 '${agentType.name}' 已有正在执行的任务，跳过重复委派。如需处理多个任务，请逐个委派——先等第一个完成，再委派第二个。")
            put("agent", agentType.name)
            put("status", "skipped")
            put("reason", "duplicate_agent_type")
        }.toString()
    }

    /**
     * 异步委派任务给子 Agent（立即返回，通过 agent_status 查询进度）。
     */
    fun delegateAsync(
        agentType: AgentType,
        task: String,
        parentSessionId: String,
        startingDirectory: String? = null
    ): Int {
        val taskId = taskIdCounter.incrementAndGet()
        val fork = parentSessionId.takeIf { it.isNotBlank() }?.let { SessionStateStore.forkAnchor(it) }
        val agentTask = AgentTask(
            id = taskId,
            agentType = agentType,
            task = task,
            parentSessionId = parentSessionId,
            startingDirectory = startingDirectory,
            forkSessionId = fork?.first,
            forkParentMessageId = fork?.second
        )
        activeTasks[taskId] = agentTask

        val job = scope.launch {
            refreshSettings()
            // 并行关闭时：同一时刻只允许一个子 Agent 在跑（CAS 占坑）。
            if (!parallelEnabled && !activeDelegateCount.compareAndSet(0, 1)) {
                val msg = parallelDisabledMessage(agentType)
                agentTask.status = AgentTaskStatus.SKIPPED
                agentTask.error = msg
                LogStore.w("AGENT", "[#$taskId] 并行关闭，跳过异步委派: ${agentType.displayName}")
                scope.launch {
                    kotlinx.coroutines.delay(60_000)
                    activeTasks.remove(taskId)
                }
                return@launch
            }
            try {
                runDelegated(agentTask, taskId, agentType)
            } finally {
                if (!parallelEnabled) activeDelegateCount.set(0)
                taskJobs.remove(taskId)
            }
        }
        taskJobs[taskId] = job
        return taskId
    }

    /**
     * 工作流委派：直接执行单个子 Agent（不做同类型去重、不受「Agent 并行」开关限制）。
     * 供 `agent_workflow` 工具在 DAG 的同一层并发调用多个子 Agent。
     */
    suspend fun delegateWorkflow(
        agentType: AgentType,
        task: String,
        parentSessionId: String,
        startingDirectory: String? = null
    ): String {
        val taskId = taskIdCounter.incrementAndGet()
        val fork = parentSessionId.takeIf { it.isNotBlank() }?.let { SessionStateStore.forkAnchor(it) }
        val agentTask = AgentTask(
            id = taskId,
            agentType = agentType,
            task = task,
            parentSessionId = parentSessionId,
            startingDirectory = startingDirectory,
            forkSessionId = fork?.first,
            forkParentMessageId = fork?.second
        )
        activeTasks[taskId] = agentTask
        return runDelegated(agentTask, taskId, agentType)
    }

    /** 查询任务状态 */
    fun getTaskStatus(taskId: Int): AgentTask? = activeTasks[taskId]

    /** 列出所有活跃任务 */
    fun listActiveTasks(): List<AgentTask> = activeTasks.values.toList()

    /** 取消任务（真正取消协程，而非仅修改状态） */
    fun cancelTask(taskId: Int): Boolean {
        val task = activeTasks[taskId] ?: return false
        if (task.status == AgentTaskStatus.RUNNING || task.status == AgentTaskStatus.PENDING) {
            task.status = AgentTaskStatus.CANCELLED
            task.error = "已取消"
            taskJobs.remove(taskId)?.cancel()
            return true
        }
        return false
    }

    /** 列出所有可用的 Agent 类型 */
    fun listAgentTypes(): String {
        return AgentType.deployable.joinToString("\n") { type ->
            "- **${type.name}** (${type.displayName}): ${type.role}"
        }
    }

    /** 把子 Agent 的关键产出写入共享记忆（FIFO，容量受限）。 */
    private fun remember(agentType: AgentType, task: String, result: String) {
        val entry = AgentMemoryEntry(
            agentType = agentType,
            taskSummary = task.replace(Regex("\\s+"), " ").trim().take(120),
            resultSummary = result.replace(Regex("\\s+"), " ").trim().take(400)
        )
        sharedMemory.add(entry)
        while (sharedMemory.size > maxMemoryEntries.coerceAtLeast(1)) sharedMemory.poll()
    }

    /** 生成共享记忆的简洁摘要（注入到新委派子 Agent 的系统提示词）。 */
    private fun sharedMemoryBrief(): String {
        val entries = sharedMemory.toList()
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { e ->
            "- [${e.agentType.name}] ${e.taskSummary} → ${e.resultSummary}"
        }
    }

    fun destroy() {
        scope.cancel()
    }

    // ──────────────────────────────────────────────
    //  Private: Agent Runner
    // ──────────────────────────────────────────────

    /**
     * 执行子 Agent 的完整工作流：
     * 1. 创建独立 DeepSeek 会话
     * 2. 构建 Agent 专属提示词（系统提示词 + 工具清单 + 任务）
     * 3. 发送消息，消费 SSE 流
     * 4. 遇到工具调用 → 执行 → 续聊 → 直到 LLM 返回最终文本
     */
    private suspend fun runAgent(task: AgentTask): String {
        // DeepSeek 逆向后端依赖服务端会话态，必须有 token；OpenAI 兼容后端无会话态、
        // 凭据只是 Base URL / API Key，不该要求 DeepSeek 登录——否则选 OpenAI 的用户
        // 一委派子 Agent 就会拿到「未登录」。token 在下游只用于 DeepSeek 建会话（见
        // runAgentInner 的 backend 分支），OpenAI 下传空串是安全的。
        val token = auth.getToken()

        val agentDef = task.agentType
        // 登记当前任务（agent_complete 工具写入完成信号），退出时清空
        currentTask = task
        try {
            return runAgentInner(task, token.orEmpty())
        } finally {
            currentTask = null
        }
    }

    private suspend fun runAgentInner(task: AgentTask, token: String): String {
        val agentDef = task.agentType
        // 工具执行作用域。
        //
        // ⚠️ 关键修复（死锁）：**不能**用 `SupervisorJob(parentJob)` 把工具作用域挂在
        // 当前协程（withTimeout 的 Job）下。原因：
        //   - 结构化并发下，parent 协程「完成」前必须等所有 child 结束；
        //   - runAgentInner 收到 agent_complete 后 `return`，其 parent（withTimeout）要收尾，
        //     而收尾需等 toolLaunchScope 里刚 complete 的 executeFilteredTool 协程退出；
        //   - 该协程又跑在 parent 的调度栈里 → 互相等待 → **永久卡死**。
        // 现象：日志停在「第 N 轮工具完成」后再无输出，子 Agent 结果永远回不到主 Agent。
        //
        // 改为**独立 SupervisorJob**（不与 withTimeout 结构化耦合），并由 finally 主动 cancel：
        //  - 工具协程不再阻塞 parent 的 return；
        //  - 超时/正常结束都会触发 finally → cancel 掉仍在跑的工具协程（不泄漏）；
        //  - SupervisorJob 仍保证单工具失败不影响兄弟工具。
        val toolLaunchScope = CoroutineScope(currentCoroutineContext() + SupervisorJob())

        // 1) 子 Agent 会话：优先 fork 主会话锚点（同会话 + 同消息 id → 服务端消息树建兄弟分支），
        //    于是子 Agent 继承主会话上下文、又不污染主链。拿不到锚点才新建会话（与旧行为一致）。
        val backend = auth.getBackend()
        var subSessionId: String? = null
        var parentMessageId: String? = task.forkParentMessageId
        // fork 锚点（本轮 user 消息 id）。非空 = 走 fork：**首轮必须用 edit_message**，
        // 因为「兄弟分支」只能由「编辑那条 user 消息」表达，见下方调度点注释。
        var forkAnchorId: String? = null
        LogStore.i("AGENT", "[#${task.id}] ${agentDef.displayName} OpenAI 后端, 任务: ${task.task.take(80)}")
        task.subSessionId = subSessionId

        // 2) 构建 Agent 专属系统提示词 + 工具清单（按后端分支：OpenAI 走原生 function calling JSON，
        //    DeepSeek 走行式协议）
        val filteredTools = if (agentDef.allowedTools.isEmpty()) {
            toolbox.all()
        } else {
            toolbox.all().filter { it.name in agentDef.allowedTools }
        }
        // PTC 呈现折叠：模型直接可见工具只剩 run_code，其余走程序内 tools 对象。
        val ptcActive = com.mcp.preset.PresetRuntime.current?.ptc == true
        // ⚠️ **死局修复**：PTC 生效时 run_code 是唯一可直接调用的入口，而子 Agent 的白名单
        // （如 EXPLORE 只有 read_file/web_search/…）里没有它 —— 于是
        //   直调白名单里的工具 → 被 PTC 折叠拒（见下方 dispatch 910 行）
        //   改用 run_code        → 被白名单拒（900 行）
        // 两条路都死，子 Agent 什么都不做（真机实测：委派后无任何工具调用）。
        // 白名单约束的是「这个子 Agent 能用哪些工具」，不该把**入口本身**挡掉；
        // 程序内能调用什么仍由白名单在 dispatch 处把关。
        val effectiveTools = if (ptcActive && filteredTools.none { it.name == com.mcp.ptc.RUN_CODE_TOOL }) {
            filteredTools + toolbox.all().filter { it.name == com.mcp.ptc.RUN_CODE_TOOL }
        } else {
            filteredTools
        }
        // 子 Agent 上下文：需要 agent_complete/agent_progress 直调（终止/进度协议），传 true。
        val modelFacingTools = com.mcp.core.prompt.modelFacingTools(effectiveTools, ptcActive, includeAgentControls = true)
        val tools: String
        val toolInventory = effectiveTools.joinToString("\n") {
            "- `${it.name}` — ${it.description.replace("\n", " ").take(160)}"
        }
        val systemContent = buildString {
            append(agentDef.systemPrompt)
            append("\n\n## 你当前可调用的工具（以下为权威清单）\n")
            append(toolInventory)
            if (backend == BackendType.OPENAI) {
                // OpenAI：原生 function calling，工具定义经 tools 参数传递，模型按原生协议输出
                tools = ToolCompiler.toOpenAiTools(modelFacingTools)
                append("\n\n工具调用通过原生 function calling 完成：当需要工具时，直接输出 tool_calls（系统已配置 tools 数组），不需要在正文里手写 JSON。拿到工具结果后继续推理，直到任务完成输出最终回复。\n\n")
            } else {
                // DeepSeek：行式协议，零转义
                // 必须用 effectiveTools：PTC 下 filteredTools 里没有 run_code，
                // 直接声明白名单工具等于教模型去撞 dispatch 的折叠拒绝。
                // 只用「模型可直调」集合：PTC 折叠后 = run_code + 流程控制工具（agent_complete/agent_progress）。
                // 此前用 effectiveTools（含全部业务工具），等于告诉模型「这些都能行式直调」——
                // 模型于是把 agent_complete 当普通工具（甚至包进 run_code）调用，链路错乱。
                // 与 OpenAI 分支的 modelFacingTools 保持一致。
                tools = ToolCompiler.toProtocolTools(modelFacingTools)
                append("\n\n工具调用格式（行式协议）\n\n")
                append("调用时按「行式协议」输出（直接顶格写，**不要用 ``` 代码围栏包裹**；围栏内的调用一律视为示例、不会执行）：\n")
                append("tool_call: 工具名\nid: call_1\n参数名1: 值1\n参数名2: 值2\n")
                append("多行参数必须用定界块：`参数名 <<<` 单独一行开始、`>>>` 单独一行结束，内容原样保留。\n\n")
                if (ptcActive) {
                    // PTC 分层：模型**直接**只能调 run_code 与流程控制工具；其余业务工具在程序内调。
                    append("**调用约束（PTC 分层，硬规则）**：\n")
                    append("- 你**直接**输出的行式调用只允许 `run_code`、`agent_complete`、`agent_progress` 三个工具名；\n")
                    append("- 其余工具（read_file / run_bash / write_file / search_files 等）**必须**在 `run_code` 程序内用 `tools.工具名({ 参数: 值 })` 调用，直接写它们会被拒绝；\n")
                    append("- 程序内**禁止**再调用 `run_code`（防嵌套失控）；\n")
                    append("- `agent_complete` / `agent_progress` 是**流程控制信号**，必须**直接**调用，**绝不要**包进 run_code。\n\n")
                }
                append("规则：\n")
                append("- 只有【真实】的工具调用才输出 tool_call: 行；解释、演示、示例里的工具调用每行前加 // 前缀（如 //tool_call: read_file），带 // 的行不会被系统执行\n")
                append("- tool_call: 后是工具名，必须是工具清单中的工具名；其余每行「参数名: 值」，值到行尾即止，不加引号，直接写原文\n")
                append("- id 为本轮唯一标识（call_1、call_2…），并行/串行各自独立\n")
                append("- 决定调用工具时，本轮只输出工具调用，不要同时输出正文\n")
                append("- 互不依赖的多个工具可以并行：连续写多个 tool_call: 段，系统会一次性全部执行；存在依赖时必须串行：先调用 A，等系统返回结果（tool_result）后，再决定并调用下一个依赖 A 结果的工具\n")
                append("- 任务完成后，直接输出最终回复，不要再输出工具调用\n\n")
                append("工具结果格式（行式协议）\n\n")
                append("系统执行完工具后，会把结果按行式块回传给你：\n")
                append("```\ntool_result: call_1 <<<\n<工具输出原文>\n>>>\n```\n\n")
                append("- tool_result: 后的 id 与你本轮工具调用 id 一一对应，据此把结果关联到具体调用\n")
                append("- 并行调用时会连续回传多个 tool_result 块，顺序与你的调用顺序一致，按 id 认领\n")
                append("- <<< 与 >>> 之间是工具输出原文（JSON 文本或纯文本），原样透传，无信封\n")
                append("- 工具失败时原文形如 {\"error\": \"…\"}，请据此修正参数重试或改用其它方案，不要假装成功\n")
                append("- 拿到结果后可继续输出新的工具调用（多轮），直到不再需要工具时才输出自然语言最终回复\n\n")
            }
            // 任务跟踪与完成约定（两种后端共用）：
            // 用 list_todos / add_todo / update_todo 跟踪任务进度（可选）；完成任务时【必须】调用
            // agent_complete(result) 显式回报最终结果并结束工作流，而不是只输出文字。
            append("\n任务跟踪与完成\n")
            append("- 可用 list_todos / add_todo / update_todo 记录任务进度与待办，便于主控 Agent 与用户跟踪。\n")
            append("- 任务完成后必须调用 agent_complete 工具，传入 result（最终结果的结构化文本），工作流随即结束并将结果回报给主控 Agent。\n")
            if (ptcActive && backend == BackendType.OPENAI) {
                // OpenAI 后端：用**原生 function calling** 直调 agent_complete（不是行式文本！）。
                // 不能复用下面 DeepSeek 的 `tool_call:` 行式示例——那会让模型手写行式文本而非发 tool_calls，
                // 与上方「用原生 function calling、不要手写 JSON」自相矛盾。
                append("- **调用方式**：直接发起 `agent_complete` 的 function call（原生 tool_calls），**不要**手写 `tool_call:` 文本、**不要**包进 run_code。\n")
                append("- 参数：`result`（最终结果的结构化文本，必须自包含）。\n")
            } else if (ptcActive) {
                // DeepSeek 后端：行式协议格式（与上面的行式工具调用一致）。
                // agent_complete 是流程控制信号，必须直调、绝不能包进 run_code。
                append("- **调用格式（直调，不要包进 run_code）**：\n")
                append("  tool_call: agent_complete\n")
                append("  id: call_1\n")
                append("  result <<<\n")
                append("  <最终结果的结构化文本，必须自包含>\n")
                append("  >>>\n")
            }
            append("- 不要在多轮工具调用中无限循环：完成任务立即 agent_complete；无法完成时如实说明原因后 agent_complete。\n")
            append("- 你的最终结果（agent_complete 的 result）必须自包含：主控 Agent 看不到你的中间过程、工具调用与上下文，请把结论、关键改动、验证结果完整写在 result 里，不要引用对话中未展示的内容。\n\n")
            append("开始目录\n\n")
            if (task.startingDirectory.isNullOrBlank()) {
                append("本次未指定固定的开始目录。请从文件系统合理根目录（如 /sdcard 或 SAF 已授权根目录）自行定位目标，不要假设未经验证的路径。\n\n")
            } else {
                append("你本次工作的开始目录（起始工作目录）为：\n")
                append(task.startingDirectory + "\n\n")
                append("所有文件操作（read_file / read_with_context / list_files / search_files / glob / write_file / edit_file / delete_file 等）应优先在此目录范围内开展；相对路径相对于该目录解析。\n\n")
            }

            // 跨 Agent 共享记忆：让子 Agent 参考其他子 Agent 已产出的关键结论，减少重复探索
            val memoryBrief = sharedMemoryBrief()
            if (memoryBrief.isNotBlank()) {
                append("\n跨 Agent 共享记忆\n\n")
                append("以下是其他子 Agent 最近的关键产出摘要，供参考复用，避免重复劳动：\n")
                append(memoryBrief)
                append("\n")
            }
        }
        // 子 Agent 系统提示词。
        // OpenAI：工具定义走 `LLMRequest.tools` 原生参数，**不**把 `tools` JSON 拼进 system——
        //   否则模型可能误以为要在正文里输出这段 JSON，与「用原生 function calling」自相矛盾。
        // DeepSeek：无原生 tools 参数，工具清单必须以文本注入 system（这是唯一的工具定义来源）。
        val systemPrompt = buildString {
            append(systemContent)
            if (backend != BackendType.OPENAI) {
                append("\n\n---\n\n")
                append(tools)
            }
            append("\n\n---\n\n")
            append("任务\n\n")
            append(task.task)
        }

        // 统一 LLM 客户端（内部按后端分发：OpenAI 原生 function calling / DeepSeek 逆向协议）
        val llmClient = LLMClientFactory.create(auth)
        // OpenAI 无状态消息缓冲（仅 OPENAI 后端使用；DeepSeek 走服务端会话态）
        val openAIMessages = ArrayList<com.mcp.llm.ChatMessage>()

        var currentPrompt = systemPrompt
        var rounds = 0

        // OpenAI 后端的 fork 上下文：无状态后端只能靠「主会话已完成历史当消息前缀」继承上下文。
        // 用 history（不含悬空 tool_call）而非 openAIMessages，否则会被 OpenAI 400。
        val openAIForkContext: List<com.mcp.llm.ChatMessage> =
            if (backend == BackendType.OPENAI && task.parentSessionId.isNotBlank())
                SessionStateStore.openAIContextSnapshot(task.parentSessionId)
            else emptyList()

        // 循环外记录「最后一轮的非空文本内容」：达到轮数上限仍未收到 agent_complete 时作为兜底结果。
        // 原实现直接返回 error JSON，会丢失子 Agent 已产出的有用内容。
        var lastContent = ""

        // toolLaunchScope 独立于 withTimeout（见上方说明），因此必须由本函数在退出时主动清理，
        // 否则超时/提前 return 时仍在跑的工具协程（如长 run_bash）会脱离边界、永久泄漏。
        try {
        // 3) 工具调用循环
        while (rounds < maxRounds) {
            rounds++
            // 子 Agent 主动完成（agent_complete）→ 立即结束，不再续聊
            task.completion?.let { return it }
            val toolJobs = LinkedHashMap<String, CompletableDeferred<String>>()
            // OpenAI 侧记录本轮 assistant(tool_calls) 元信息，续聊时按序回填 tool 消息
            val pendingToolMeta = LinkedHashMap<String, Pair<String, String>>()
            var finalContent = ""
            var finalThinking = ""
            var hasToolCalls = false
            var hasDone = false

            // 限流重试循环（与主 Agent 共用 retry_max / retry_interval_sec 配置）
            var attempt = 0
            var rateLimited = false
            while (attempt < retryMax) {
                attempt++
                rateLimited = false
                try {
                    // 按后端构造请求
                    val request = if (backend == BackendType.OPENAI) {
                        // 首轮：system + 任务；后续轮：追加 assistant(tool_calls) + tool 结果消息
                        if (rounds == 1) {
                            if (openAIForkContext.isEmpty()) {
                                openAIMessages.add(com.mcp.llm.ChatMessage(role = "system", content = systemPrompt))
                            } else {
                                // fork：system 不带任务 + 主会话已完成历史 + 任务作**末条 user**。
                                // 任务必须移到末条 —— 模型响应的是最后一条消息，留在 system 里它就会
                                // 接着主会话往下聊，而不是执行子任务。
                                // OpenAI：工具定义走 `LLMRequest.tools` 原生参数，**不**在 system 里拼 tools JSON
                                // （与非 fork 路径 systemPrompt 的处理一致；拼进去会让模型误以为要在正文输出它）。
                                openAIMessages.add(
                                    com.mcp.llm.ChatMessage(
                                        role = "system",
                                        content = systemContent
                                    )
                                )
                                openAIMessages.addAll(openAIForkContext)
                                openAIMessages.add(com.mcp.llm.ChatMessage(role = "user", content = task.task))
                            }
                        }
                        // 上下文压缩（对齐 deepseek-harness）：工具结果往往是 token 大户，
                        // 每轮发送前先剪枝，压力超阈值时 LLM 摘要替换旧消息（替换而非追加）
                        if (rounds > 1) {
                            // 按实际发送的工具集估算 token（OpenAI 走 modelFacingTools，不是 filteredTools）。
                            compactAgentContext(openAIMessages, llmClient, contextCompactor.estimateToolsTokens(modelFacingTools))
                        }
                        LLMRequest(
                            messages = openAIMessages.toList(),
                            // 用 modelFacingTools 而非 filteredTools：OpenAI 原生 function calling 下，
                            // `tools` 参数就是「模型可直接调用的函数集」，必须与 PTC 呈现折叠一致——
                            // PTC 生效时只有 run_code + 流程控制工具（agent_complete/agent_progress）可直调，
                            // 其余业务工具须在 run_code 程序内调。传 filteredTools 会把全部业务工具暴露成
                            // 可直接调用，模型于是直调它们、被 modelDirectToolAllowed 拒绝，链路错乱。
                            // 与 DeepSeek 分支的 `toProtocolTools(modelFacingTools)` 保持一致。
                            tools = if (modelFacingTools.isNotEmpty()) modelFacingTools else null,
                            config = LLMConfig(
                                model = auth.getOpenAIModel(),
                                temperature = auth.getOpenAITemperature(),
                                maxTokens = auth.getOpenAIMaxTokens(),
                                stream = true,
                                thinkingEnabled = false
                            )
                        )
                    } else {
                        LLMRequest(
                            messages = listOf(com.mcp.llm.ChatMessage(role = "user", content = currentPrompt)),
                            tools = null,
                            config = LLMConfig(
                                sessionId = subSessionId,
                                parentMessageId = parentMessageId,
                                thinkingEnabled = false,
                                searchEnabled = false
                            )
                        )
                    }
                    // ── fork 首轮必须走 edit_message ──
                    // 真机实测：fork 锚点（本轮 user 消息 id，如 15）被当作
                    // /chat/completion 的 parent_message_id 发出去，服务端直接拒绝：
                    // {"biz_code":2,"biz_msg":"invalid message role"} —— 该字段语义是
                    // 「我在回复谁」，必须是**助手**消息；传 user 消息不合法。
                    // 「兄弟分支」只能由 /chat/edit_message（编辑那条 user 消息）表达 ——
                    // /v1 的分叉路径一直就是这么做的（CompatChatRunner.editMessage）。
                    // 只有首轮需要：拿到新助手 id 后 parentMessageId 已更新，后续照常续聊。
                    val forked = forkAnchorId != null && rounds == 1
                    if (forked) {
                        LogStore.i("AGENT", "[#${task.id}] fork 首轮改用 edit_message 建兄弟分支 @ $forkAnchorId")
                    }
                    val upstream = if (forked) {
                        llmClient.editMessage(request, forkAnchorId!!)
                    } else {
                        llmClient.sendMessage(request)
                    }
                    upstream.collect { event ->
                        when (event) {
                            is MessageEvent.PowProgress -> { /* 子 Agent 不报告 PoW 进度 */ }
                            is MessageEvent.Thinking -> { finalThinking += event.delta }
                            is MessageEvent.Content -> { finalContent += event.delta }
                            is MessageEvent.MessageId -> { parentMessageId = event.id }
                            is MessageEvent.ToolCall -> {
                                hasToolCalls = true
                                LogStore.d("AGENT", "[#${task.id}] 子Agent工具调用: ${event.name}")
                                // 记录元信息（OpenAI 续聊需按序回填 assistant(tool_calls) + tool 消息）
                                pendingToolMeta[event.id] = event.name to event.arguments
                                // 防御：同 callId 重复到达时只执行首次，避免二次执行/覆盖 deferred 挂起
                                if (toolJobs.containsKey(event.id)) {
                                    LogStore.w("AGENT", "[#${task.id}] 忽略重复 ToolCall: ${event.id} ${event.name}")
                                } else {
                                    val deferred = CompletableDeferred<String>()
                                    toolJobs[event.id] = deferred
                                    // 使用 toolLaunchScope（与 withTimeout 同生命周期），
                                    // 确保超时取消时工具协程也一并取消，不再逃脱边界
                                    toolLaunchScope.launch {
                                        executeFilteredTool(event.id, event.name, event.arguments, agentDef, deferred, task.subSessionId)
                                    }
                                }
                            }
                            is MessageEvent.Done -> {
                                hasDone = true
                            }
                            is MessageEvent.Error -> {
                                val msg = (event.throwable as? DeepSeekException)
                                    ?.let { "HTTP ${it.httpCode}: ${it.message}" }
                                    ?: (event.throwable.message ?: event.throwable.toString())
                                rateLimited = msg.contains("过于频繁") || msg.contains("rate_limit")
                                if (rateLimited && attempt < retryMax) {
                                    LogStore.w("AGENT", "[#${task.id}] 限流，${retryIntervalMs}ms 后重试 ($attempt/$retryMax)")
                                } else {
                                    LogStore.e("AGENT", "[#${task.id}] SSE 错误: ${event.throwable.message}")
                                    throw event.throwable
                                }
                            }
                        
                        else -> {}}
                    }
                    break  // 成功，跳出重试循环
                } catch (e: DeepSeekException) {
                    val msg = "HTTP ${e.httpCode}: ${e.message}"
                    rateLimited = rateLimited || msg.contains("过于频繁") || msg.contains("rate_limit")
                    if (!rateLimited || attempt >= retryMax) {
                        LogStore.e("AGENT", "[#${task.id}] DeepSeek 异常: ${e.message}")
                        return """{"error": "子Agent通信失败: ${e.message}"}"""
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: e.toString()
                    rateLimited = rateLimited || msg.contains("过于频繁") || msg.contains("rate_limit")
                    if (!rateLimited || attempt >= retryMax) {
                        LogStore.e("AGENT", "[#${task.id}] 异常: ${e.message}")
                        return """{"error": "子Agent异常: ${e.message}"}"""
                    }
                }
                if (rateLimited && attempt < retryMax) {
                    delay(retryIntervalMs)
                }
            }

            if (!hasToolCalls && hasDone) {
                // 关键修复：本轮**没有工具调用**时，绝不能直接判定「完成」并 return。
                //
                // 原实现：`return finalContent.ifEmpty { finalThinking }` —— 子 Agent 只要
                // 输出一段纯文本（哪怕只是「你好」），就被当成任务完成。后果：
                //   1) agent_complete 从未被调用 → 主控收不到结构化结果（表现为「只识别不执行」）；
                //   2) 主控 Agent 侧看不到子 Agent 产出，链路中断。
                //
                // 现改为：把这一轮的文本作为 assistant 输出记入上下文，再注入一条**续聊提示**，
                // 要求子 Agent 显式二选一：任务完成 → 调 agent_complete；未完成 → 继续调工具。
                // 只有 `task.completion`（agent_complete 写入）才真正结束循环。
                LogStore.i("AGENT", "[#${task.id}] ${agentDef.displayName} 第 $rounds 轮无工具调用，注入续聊提示等待 agent_complete")
                if (backend == BackendType.OPENAI) {
                    // 无状态后端：把本轮的纯文本输出追加为 assistant 消息，保持上下文连贯
                    openAIMessages.add(
                        com.mcp.llm.ChatMessage(
                            role = "assistant",
                            content = finalContent.ifEmpty { finalThinking }
                        )
                    )
                    openAIMessages.add(
                        com.mcp.llm.ChatMessage(
                            role = "user",
                            content = agentContinuationHint(agentDef.displayName)
                        )
                    )
                } else {
                    currentPrompt = agentContinuationHint(agentDef.displayName)
                }
                // 不 return，继续下一轮：只有 agent_complete（task.completion）或轮数上限才结束
            } else if (hasToolCalls) {
                // 等待所有工具执行完成。toolJobs 是 LinkedHashMap，遍历顺序即 tool_calls 顺序，
                // 保证并行调用时回传的 tool 消息顺序与模型给出的调用顺序一致。
                val results = toolJobs.map { (callId, job) -> callId to job.await() }
                LogStore.d("AGENT", "[#${task.id}] ${agentDef.displayName} 第 $rounds 轮工具完成: ${results.size} 个")
                // 工具执行期间若子 Agent 已通过 agent_complete 主动完成 → 直接返回结果，不续聊
                task.completion?.let { return it }
                // 兜底：agent_complete 被**包进 run_code** 执行时，其 handler 同样会写 task.completion，
                // 但若因时序/上下文原因没写成功（例如子调用走了另一条 dispatch 链），这里从工具结果里
                // 识别「agent_complete 已成功回报」的信号，避免结果丢失、循环空转。
                // 判据：本轮某个工具结果里出现 agent_complete 的成功响应体（"任务已完成，结果已回报"）。
                val completedViaRunCode = results.any { (_, r) -> r.contains("任务已完成，结果已回报") }
                if (completedViaRunCode) {
                    // completeCurrentTask 刚写入，可能还没被本线程观察到；短等一瞬再取。
                    if (task.completion == null) {
                        kotlinx.coroutines.delay(50)
                    }
                    task.completion?.let { return it }
                    // 仍为空：说明确实没写进 completion。退回把该 run_code 的返回体当结果回报，
                    // 至少不丢链路（主控能拿到「已完成」的迹象）。
                    val fallback = results.firstOrNull { (_, r) -> r.contains("任务已完成，结果已回报") }?.second
                    if (!fallback.isNullOrBlank()) {
                        LogStore.w("AGENT", "[#${task.id}] agent_complete 经 run_code 执行但 completion 为空，用工具结果兜底回报")
                        return fallback
                    }
                }

                // 按后端续聊：
                //  - OpenAI：追加 assistant(tool_calls) + 每条 tool 结果消息（原生 function calling JSON）
                //  - DeepSeek：工具结果以「tool_result: <id> <<< ... >>>」行式块回传（零转义）
                if (backend == BackendType.OPENAI) {
                    val toolCalls = pendingToolMeta.map { (id, pair) ->
                        com.mcp.llm.ToolCall(id, "function", com.mcp.llm.ToolCallFunction(pair.first, pair.second))
                    }
                    openAIMessages.add(com.mcp.llm.ChatMessage(role = "assistant", toolCalls = toolCalls))
                    val byId = results.toMap()
                    for ((id, _) in pendingToolMeta) {
                        openAIMessages.add(
                            com.mcp.llm.ChatMessage(
                                role = "tool",
                                content = ToolMessages.extractContent(byId[id] ?: ""),
                                toolCallId = id
                            )
                        )
                    }
                } else {
                    currentPrompt = ToolMessages.lineResults(results)
                }
            } else {
                // 没有工具调用但也没有 Done（异常情况：流中断 / 上游未发 Done 事件）
                // 与「无工具调用有 Done」同样处理：注入续聊提示、继续循环，而不是直接把
                // 半截内容当作最终结果返回（否则 agent_complete 永远不被调用，主控拿不到结果）。
                LogStore.w("AGENT", "[#${task.id}] 第 $rounds 轮无工具调用也无 Done，注入续聊提示")
                if (backend == BackendType.OPENAI) {
                    openAIMessages.add(
                        com.mcp.llm.ChatMessage(
                            role = "assistant",
                            content = finalContent.ifEmpty { finalThinking }
                        )
                    )
                    openAIMessages.add(
                        com.mcp.llm.ChatMessage(
                            role = "user",
                            content = agentContinuationHint(agentDef.displayName)
                        )
                    )
                } else {
                    currentPrompt = agentContinuationHint(agentDef.displayName)
                }
                // 不 return，继续下一轮
            }
        }

        // 达到轮数上限仍未收到 agent_complete：把最后一轮内容作为兜底结果返回，
        // 并在日志里明确标注「未显式完成」，便于主控/用户判断。
        LogStore.w("AGENT", "[#${task.id}] ${agentDef.displayName} 超过最大轮数 ($maxRounds)，任务可能过于复杂")
        return lastContent.ifEmpty {
            """{"error": "${agentDef.displayName} 超过最大轮数 ($maxRounds)，任务可能过于复杂，且未收到 agent_complete"}"""
        }
        } finally {
            // 无论正常 return / 抛异常 / 被 withTimeout 取消，都回收工具作用域。
            toolLaunchScope.cancel()
        }
    }

    /**
     * 子 Agent 无工具调用时的续聊提示。
     *
     * 背景：原实现把「无工具调用」等同于「任务完成」直接 return，导致子 Agent 输出一句
     * 纯文本就被判定结束、agent_complete 从未被调用，主控拿不到结构化结果。
     * 现在改为注入本提示，让子 Agent 显式二选一，把「是否完成」的决定权交回模型。
     */
    private fun agentContinuationHint(displayName: String): String = buildString {
        append("<continuation-required>\n")
        append("你上一条回复没有调用任何工具。请立即判断当前任务状态并二选一：\n")
        append("1) 任务已完成 → 调用 agent_complete 工具，把最终结果（结论、关键改动、验证结果）完整写入 result 参数；\n")
        append("2) 任务尚未完成 → 继续调用所需工具推进，不要只输出文字说明。\n")
        append("注意：仅输出文字不会被当作完成；必须调用 agent_complete 才能结束并回报结果。\n")
        append("</continuation-required>")
    }

    // ── 子 Agent 上下文压缩（对齐 deepseek-harness dsh-compaction-basic）────────────────
    // 子 Agent 的 OpenAI 无状态消息缓冲会随工具轮数无界增长，
    // 在每轮发送前先做模型无关工具结果剪枝，压力超阈值时 LLM 摘要替换旧消息。

    /** 上下文压缩策略（纯策略，无 Android 依赖）。 */
    private val contextCompactor = ContextCompactor()

    /**
     * 对子 Agent 的 OpenAI 消息缓冲做一次按需压缩（就地修改 [messages]）。
     * 与 ChatBridge 主会话共用同一套策略：模型感知阈值、token 预算保留、
     * tool-pairing 平衡切点、收缩验证、替换而非追加。
     */
    /** 用户手填的上下文窗口（tokens）；0 = 按模型自动推断（设置页「上下文窗口」输入项）。 */
    private fun configuredContextWindow(): Int = auth.getOpenAIContextWindow()

    /** 用户手填的最大输入（tokens）；0 = 不限制（设置页「最大输入」输入项）。 */
    private fun configuredMaxInput(): Int = auth.getOpenAIMaxInput()

    private suspend fun compactAgentContext(messages: ArrayList<com.mcp.llm.ChatMessage>, llmClient: LLMClient, extraTokens: Int = 0) {
        contextCompactor.pruneToolResults(messages)
        val threshold = contextCompactor.thresholdTokens(auth.getOpenAIModel(), configuredContextWindow(), configuredMaxInput())
        // 压力 = 消息估算 + 工具定义估算（白名单工具同样全量携带）
        val tokens = contextCompactor.estimateTokens(messages) + extraTokens
        if (tokens <= threshold) return

        val systemMsg = messages.firstOrNull { it.role == "system" }
        val nonSystem = messages.filter { it.role != "system" }
        val retainTokens = contextCompactor.retainTokensFor(auth.getOpenAIModel(), configuredContextWindow())
        val selection = contextCompactor.selectRetainedTail(nonSystem, retainTokens) ?: return

        val summary = try {
            summarizeAgentContext(selection.shadowed, llmClient)
        } catch (e: Exception) {
            LogStore.w("AGENT", "子Agent上下文摘要失败，降级截断: ${e.message?.take(80)}")
            messages.clear()
            if (systemMsg != null) messages.add(systemMsg)
            messages.addAll(selection.retained)
            LogStore.i("AGENT", "子Agent上下文降级截断: $tokens → ${contextCompactor.estimateTokens(messages)}")
            return
        }

        // 收缩验证（收敛）：摘要必须比被替换范围小
        val checkpointTokens = contextCompactor.checkpointTokens(summary)
        if (checkpointTokens >= selection.shadowedTokens) {
            LogStore.w("AGENT", "子Agent摘要未收缩（$checkpointTokens ≥ ${selection.shadowedTokens}），降级截断")
            messages.clear()
            if (systemMsg != null) messages.add(systemMsg)
            messages.addAll(selection.retained)
            return
        }

        // 替换（replace）而非追加
        val replacement = contextCompactor.buildCheckpoint(systemMsg, summary, selection.retained)
        messages.clear()
        messages.addAll(replacement)
        LogStore.i("AGENT", "子Agent上下文压缩完成: ${selection.shadowed.size} 条→摘要, 保留 ${selection.retained.size} 条, $tokens → ${contextCompactor.estimateTokens(messages)}")
    }

    /** 调用 LLM 对子 Agent 的旧消息做结构化摘要（渲染为文本 + 压缩指令）。 */
    private suspend fun summarizeAgentContext(
        messages: List<com.mcp.llm.ChatMessage>,
        llmClient: LLMClient
    ): String {
        val historyText = contextCompactor.renderForSummary(messages)
        val req = LLMRequest(
            messages = listOf(
                com.mcp.llm.ChatMessage(
                    role = "user",
                    content = "$historyText\n---\n\n${ContextCompactor.COMPACT_INSTRUCTION}"
                )
            ),
            tools = null,
            config = LLMConfig(
                model = auth.getOpenAIModel(),
                maxTokens = ContextCompactor.summaryMaxTokens(auth.getOpenAIMaxTokens()),
                stream = true,
                // 摘要请求必须关闭思考模式（thinking 计入 maxTokens，会占满 4096 导致 content 为空）
                thinkingEnabled = false
            )
        )
        val sb = StringBuilder()
        llmClient.sendMessage(req).collect { event ->
            if (event is MessageEvent.Content) sb.append(event.delta)
        }
        val result = sb.toString().trim()
        if (result.isEmpty()) throw IllegalStateException("LLM 摘要结果为空")
        return result
    }

    /**
     * 执行工具调用，但受 Agent 类型白名单限制。
     */
    private suspend fun executeFilteredTool(
        callId: String,
        name: String,
        arguments: String,
        agentDef: AgentType,
        deferred: CompletableDeferred<String>,
        sessionId: String? = null
    ) {
        // 白名单检查。
        // run_code 例外：PTC 生效时它是唯一可直接调用的入口，被白名单挡掉即死局（见上方
        // effectiveTools 的说明）。放行入口不等于放权 —— 程序内实际调用了什么仍走这里校验。
        val ptcEntry = name == com.mcp.ptc.RUN_CODE_TOOL && com.mcp.preset.PresetRuntime.current?.ptc == true
        if (!ptcEntry && agentDef.allowedTools.isNotEmpty() && name !in agentDef.allowedTools) {
            val errMsg = """{"error": "工具 '$name' 不在 ${agentDef.displayName} 的权限范围内"}"""
            LogStore.w("AGENT", "工具 $name 被 ${agentDef.name} 拒绝（白名单）")
            deferred.complete(errMsg)
            return
        }

        val rawResult = try {
            val args = parseToolArguments(arguments)
            // PTC 折叠：与主链路一致，dispatch 层拒绝直调非 run_code 工具
            if (!com.mcp.core.prompt.modelDirectToolAllowed(name, com.mcp.preset.PresetRuntime.current?.ptc == true)) {
                errorResult(com.mcp.core.prompt.ptcDirectCallRejection(name))
            } else {
                // 工具执行带超时（与主链路 ChatBridge 对齐）：
                //  - run_code 用其声明的 timeout_seconds（默认 300s）；
                //  - 其余工具 120s 兜底。
                // ⚠️ 关键：没有这层超时，工具一旦内部挂起（Rhino 死循环 / 子调用卡住 / 外部进程
                //    waitFor 不返回），deferred 永远不 complete → 子 Agent 的 job.await() 永久挂起，
                //    整个子 Agent 死锁、结果回不到主 Agent。超时后把「执行超时」作为工具结果回传，
                //    让子 Agent 至少能自愈（看到错误、换方案），而不是无声卡死。
                val timeoutMs = runCatching {
                    val raw = (args["timeout_seconds"] ?: args["timeout_sec"]) as? kotlinx.serialization.json.JsonPrimitive
                    raw?.content?.toLongOrNull()?.times(1000)
                }.getOrNull() ?: if (name == com.mcp.ptc.RUN_CODE_TOOL) 300_000L else 120_000L
                val out = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    toolbox.dispatch(name, args)
                }
                out ?: errorResult(
                    "工具 '$name' 执行超时（${timeoutMs / 1000}s），已中止。" +
                        "请换更小的 timeout_seconds、拆分任务，或改用其它方案。"
                )
            }
        } catch (e: Exception) {
            // 解析失败：返回正确转义的错误 JSON（含自愈提示），绝不让工具调用把会话打挂
            val msg = (e as? ToolArgsParseException)?.message
                ?: "工具参数解析失败：${e.message ?: e::class.simpleName}"
            errorResult(msg)
        }

        // ── 推进模式：子 Agent 工具结果追加自己的待办摘要（与主控逻辑一致） ──
        val prefs = context.getSharedPreferences(com.mcp.MainActivity.PREF_TAB_MODE, android.content.Context.MODE_PRIVATE)
        val pushMode = prefs.getBoolean("push_mode", false)

        // 排除：Agent 工具 + 待办任务工具 + 只读工具（避免重复/递归嵌套）
        val excludedTools = setOf(
            // Agent 工具
            "delegate_to_agent", "list_agents", "agent_status",
            "agent_cancel", "agent_complete", "agent_progress",
            // 待办任务工具（自身已经返回待办信息，追加会重复）
            "list_todos", "add_todo", "update_todo", "import_todos", "delete_todo",
            // 只读工具（不产生副作用，无需追加待办）
            "read_file", "get_block", "search_files", "glob", "grep",
            "search_and_read", "list_files", "saf_list_roots"
        )

        // 获取子 Agent 的 sessionId（用于待办隔离）
        val effectiveSessionId = sessionId ?: currentTask?.subSessionId

        val finalResult = if (pushMode && name !in excludedTools) {
            val todoCtx = com.mcp.TodoStore.toMarkdown(context, excludeDone = true, sessionId = effectiveSessionId)
            if (todoCtx.isNotBlank() && todoCtx != "当前没有任何待办任务。") {
                val stats = com.mcp.TodoStore.stats(context, sessionId = effectiveSessionId)
                val openCount = (stats[com.mcp.TodoStatus.PENDING] ?: 0) + (stats[com.mcp.TodoStatus.IN_PROGRESS] ?: 0)
                buildString {
                    append(rawResult)
                    append("\n\n<current-todos>\n")
                    append(todoCtx)
                    append("\n</current-todos>\n")
                    if (openCount > 0) {
                        append("提示：还有 $openCount 个待办步骤未完成。")
                    }
                }
            } else {
                rawResult
            }
        } else {
            rawResult
        }

        val toolResult = buildToolResult(callId, finalResult)
        deferred.complete(toolResult)
    }

    /**
     * 把工具执行结果包装为行式工具结果块：
     *   tool_result: <callId> <<< <原始工具输出> >>>
     * 与主 Agent 共用同一份 [ToolMessages]，零转义。
     */
    private fun buildToolResult(callId: String, rawResult: String): String =
        ToolMessages.lineResult(callId, rawResult)
}
