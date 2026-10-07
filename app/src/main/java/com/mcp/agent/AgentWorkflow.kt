package com.mcp.agent

import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 多 Agent 工作流（DAG）编排工具。
 *
 * 把「声明式任务图」固化为一个工具：一次声明多个子 Agent 步骤（含依赖/条件），
 * 由编排器拓扑排序后按层并发执行，替代主控 LLM 多轮一对一委派。
 *
 * 步骤结构（JSON 数组）：
 * ```json
 * [
 *   {"id":"explore","agent_type":"EXPLORE","task":"..."},
 *   {"id":"edit","agent_type":"EDIT","task":"根据 {{explore}} 修改...","depends_on":["explore"]},
 *   {"id":"review","agent_type":"REVIEW","task":"...","depends_on":["edit"],"when":{"dep":"edit","op":"success"}}
 * ]
 * ```
 * - `depends_on`：依赖的步骤 id（拓扑排序 + 环检测）
 * - `{{step_id}}`：task 里引用上游步骤结果的占位符，执行前自动替换
 * - `when`：条件执行，op ∈ success / failure / contains / not_contains，不满足则跳过该步
 */
fun agentWorkflowTool(orchestrator: AgentOrchestrator): ToolDef = tool("agent_workflow") {
    description = "声明式多 Agent 工作流编排：一次声明多个子 Agent 步骤（id/agent_type/task/depends_on/when），系统自动拓扑排序并按层并行执行，支持步骤间结果传递（task 里用 {{步骤id}} 引用上游输出）与条件执行（when: success/failure/contains/not_contains）。适合把多步、有依赖的委派合并为一次确定性调用，替代多轮 delegate_to_agent。"
    string("steps") {
        description = "步骤 JSON 数组：每项 {id, agent_type, task, depends_on?, when?}。depends_on 为字符串数组；when 为 {dep, op, value?} 对象，op ∈ success/failure/contains/not_contains。"
    }
    string("starting_directory") {
        description = "所有子 Agent 共用的工作起点目录（绝对路径）。缺省时各子 Agent 自行定位。"
        required = false
    }
    handler { args ->
        val stepsJson = args["steps"]?.jsonPrimitive?.content
            ?: return@handler """{"error": "缺少 steps 参数"}"""
        val startingDirectory = args["starting_directory"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }

        try {
            val steps = parseSteps(stepsJson)
            if (steps.isEmpty()) return@handler """{"error": "steps 不能为空"}"""
            val sorted = topoSort(steps)
            val level = assignLevels(sorted)
            val maxLevel = level.values.maxOrNull() ?: 0

            val results = ConcurrentHashMap<String, String>()
            val statuses = ConcurrentHashMap<String, String>()

            for (lvl in 0..maxLevel) {
                val batch = sorted.filter { level[it.id] == lvl }
                coroutineScope {
                    batch.map { step ->
                        async(Dispatchers.IO) {
                            val shouldRun = step.whenCond == null || evalWhen(step.whenCond!!, results)
                            val stepResult = if (!shouldRun) {
                                statuses[step.id] = "skipped"
                                """{"info":"步骤 ${step.id} 被 when 条件跳过"}"""
                            } else {
                                statuses[step.id] = "running"
                                val resolvedTask = resolveRefs(step.task, results)
                                try {
                                    val r = orchestrator.delegateWorkflow(
                                        step.agentType, resolvedTask,
                                        parentSessionId = com.mcp.ptc.PtcAudit.current().orEmpty(), startingDirectory = startingDirectory
                                    )
                                    statuses[step.id] = "done"
                                    r
                                } catch (e: Exception) {
                                    statuses[step.id] = "failed"
                                    """{"error":"${e.message?.take(300)?.replace("\"", "'")}"}"""
                                }
                            }
                            step.id to stepResult
                        }
                    }.awaitAll().forEach { (id, r) -> results[id] = r }
                }
            }

            val out = JSONArray()
            for (s in sorted) {
                out.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("agent", s.agentType.name)
                        .put("status", statuses[s.id] ?: "unknown")
                        .put("result", (results[s.id] ?: "").take(800))
                )
            }
            JSONObject().put("workflow", "done").put("total_steps", sorted.size).put("steps", out).toString()
        } catch (e: Exception) {
            """{"error":"工作流执行失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
        }
    }
}

// ── 数据模型 ──

private data class WfStep(
    val id: String,
    val agentType: AgentType,
    val task: String,
    val dependsOn: List<String> = emptyList(),
    val whenCond: WfWhen? = null
)

private data class WfWhen(val dep: String, val op: String, val value: String? = null)

// ── 解析 / 排序 ──

private fun parseSteps(json: String): List<WfStep> {
    val arr = JSONArray(json)
    val steps = ArrayList<WfStep>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.getString("id").trim()
        if (id.isEmpty()) throw IllegalArgumentException("步骤 ${i} 缺少 id")
        val agentName = obj.getString("agent_type").trim()
        val agentType = AgentType.find(agentName)
            ?: throw IllegalArgumentException("步骤 $id 的 agent_type 无效: $agentName（可用: ${AgentType.deployable.map { it.name }}）")
        val task = obj.optString("task")
        if (task.isBlank()) throw IllegalArgumentException("步骤 $id 缺少 task")
        val dependsOn = obj.optJSONArray("depends_on")?.let { a ->
            (0 until a.length()).map { a.getString(it) }
        } ?: emptyList()
        val whenObj = obj.optJSONObject("when")
        val whenCond = whenObj?.let { w ->
            WfWhen(
                dep = w.optString("dep"),
                op = w.optString("op").ifBlank { "success" },
                value = w.optString("value").takeIf { it.isNotBlank() }
            )
        }
        steps.add(WfStep(id, agentType, task, dependsOn, whenCond))
    }
    return steps
}

/** Kahn 拓扑排序 + 环检测 + 依赖引用校验。 */
private fun topoSort(steps: List<WfStep>): List<WfStep> {
    val byId = HashMap<String, WfStep>()
    for (s in steps) {
        if (byId.containsKey(s.id)) throw IllegalArgumentException("步骤 id 重复: ${s.id}")
        byId[s.id] = s
    }

    val indegree = LinkedHashMap<String, Int>()
    val dependents = HashMap<String, MutableList<String>>()
    for (s in steps) {
        indegree[s.id] = s.dependsOn.size
        for (d in s.dependsOn) {
            if (!byId.containsKey(d)) throw IllegalArgumentException("步骤 ${s.id} 依赖不存在的步骤: $d")
            dependents.getOrPut(d) { mutableListOf() }.add(s.id)
        }
        s.whenCond?.let {
            if (!byId.containsKey(it.dep)) throw IllegalArgumentException("步骤 ${s.id} 的 when 引用不存在的步骤: ${it.dep}")
        }
    }

    val queue = ArrayDeque<String>()
    indegree.forEach { (id, deg) -> if (deg == 0) queue.add(id) }
    val order = ArrayList<WfStep>(steps.size)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        order.add(byId[id]!!)
        dependents[id]?.forEach { dep ->
            val nd = indegree[dep]!! - 1
            indegree[dep] = nd
            if (nd == 0) queue.add(dep)
        }
    }
    if (order.size != steps.size) throw IllegalArgumentException("步骤依赖存在环，无法编排")
    return order
}

/** 层数 = 依赖的最大层数 + 1（用于分层并行）。 */
private fun assignLevels(sorted: List<WfStep>): Map<String, Int> {
    val level = HashMap<String, Int>()
    for (s in sorted) {
        level[s.id] = if (s.dependsOn.isEmpty()) 0 else s.dependsOn.maxOf { level[it]!! } + 1
    }
    return level
}

// ── 条件 / 引用 ──

private fun evalWhen(cond: WfWhen, results: Map<String, String>): Boolean {
    val res = results[cond.dep] ?: return false
    val hasError = res.contains("\"error\"")
    return when (cond.op) {
        "success" -> !hasError
        "failure" -> hasError
        "contains" -> res.contains(cond.value ?: "")
        "not_contains" -> !res.contains(cond.value ?: "")
        else -> throw IllegalArgumentException("不支持的 when.op: ${cond.op}")
    }
}

/** 把 task 里的 {{step_id}} 占位符替换为对应步骤结果。 */
private fun resolveRefs(task: String, results: Map<String, String>): String {
    val pattern = Regex("\\{\\{([A-Za-z0-9_]+)\\}\\}")
    return pattern.replace(task) { m ->
        results[m.groupValues[1]] ?: m.value
    }
}