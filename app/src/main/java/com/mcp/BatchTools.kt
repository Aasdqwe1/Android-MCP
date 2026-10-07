package com.mcp

import android.content.Context
import com.mcp.toolbox.tool
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.ToolArgsParseException
import com.mcp.toolbox.LenientJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * 批量执行、依赖管理、条件执行与步骤间结果传递。
 *
 * `batch_execute` 是一次「确定性编排」：声明步骤 → 拓扑排序 → 逐步骤执行，
 * 支持跨步骤引用上游结果（`$ref`）与条件跳过（`when`），把多轮 LLM 往返压缩成一次调用。
 */
fun batchTools(context: Context, toolboxProvider: () -> com.mcp.toolbox.Toolbox = { com.mcp.toolbox.Toolbox() }): List<ToolDef> = listOf(
    batchExecuteTool(context, toolboxProvider)
)

/**
 * 一次性编排多个工具调用。
 *
 * 步骤规范（`tools` 数组每项）：
 * ```json
 * {
 *   "id": "read_main",                // 可选，步骤唯一标识（默认用 name）
 *   "name": "read_file",              // 工具名
 *   "arguments": {"path": "..."},     // 参数；可用 {"$ref": "其它步骤id[.json.path]"} 引用上游结果
 *   "depends_on": ["read_main"],      // 可选，前置步骤 id（或 name），建立执行顺序
 *   "when": {                         // 可选，条件执行：不满足则跳过该步骤（不影响整批）
 *     "dep": "read_main",
 *     "op": "success | failure | contains | not_contains | equals",
 *     "value": "..."                  // contains/not_contains/equals 时使用
 *   }
 * }
 * ```
 */
fun batchExecuteTool(context: Context, toolboxProvider: () -> com.mcp.toolbox.Toolbox = { com.mcp.toolbox.Toolbox() }): ToolDef = tool("batch_execute") {
    description = "将多个工具调用编排为一次确定性执行：声明步骤（可选 id / depends_on / when）→ 拓扑排序 → 逐步骤执行。" +
            "支持步骤间结果传递（arguments 里用 {\"\$ref\": \"步骤id[.json.path]\"} 引用上游步骤输出）与" +
            "条件执行（when：success/failure/contains/not_contains/equals 不满足则跳过该步）。" +
            "任一依赖失败（且该步未用 when 覆盖）则中止整批；rollback_on_error 控制是否回滚。"
    string("tools") {
        description = "工具调用列表，JSON 数组，每项 {id?, name, arguments, depends_on?, when?}"
        required = true
    }
    boolean("atomic") {
        description = "是否原子执行，默认 true"
        required = false
    }
    boolean("rollback_on_error") {
        description = "失败时是否回滚，默认 true"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val toolsArray = args["tools"] as? JsonArray
                ?: return@withContext """{"error":"tools 参数必须是数组"}"""

            val rollback = args["rollback_on_error"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

            val toolSpecs = mutableListOf<BatchToolSpec>()
            for (item in toolsArray) {
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content
                    ?: return@withContext """{"error":"工具缺少 name 字段"}"""
                val arguments = obj["arguments"] as? JsonObject
                    ?: return@withContext """{"error":"工具 $name 缺少 arguments 字段"}"""
                val id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: name
                val dependsOn = obj["depends_on"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                val whenCond = obj["when"]?.jsonObject?.let { w ->
                    val dep = w["dep"]?.jsonPrimitive?.content ?: return@let null
                    val op = w["op"]?.jsonPrimitive?.content ?: "success"
                    val value = w["value"]?.jsonPrimitive?.content
                    StepWhen(dep, op, value)
                }
                toolSpecs.add(BatchToolSpec(id, name, arguments, dependsOn, whenCond))
            }

            val cycle = detectCycle(toolSpecs)
            if (cycle != null) {
                return@withContext """{"error":"检测到依赖循环: ${cycle.joinToString(" -> ")}"}"""
            }

            val sorted = topologicalSort(toolSpecs)
            val toolbox = toolboxProvider()
            val results = LinkedHashMap<String, String>()   // 步骤 id → 结果字符串
            val steps = mutableListOf<JsonObject>()
            val snapshots = mutableMapOf<String, String>()

            try {
                for (spec in sorted) {
                    // 1) 依赖解析 + 默认失败传播（未用 when 覆盖的依赖失败才中止）
                    for (dep in spec.dependsOn) {
                        if (spec.whenCond != null && spec.whenCond.dep == dep) continue
                        val depId = resolveStepId(toolSpecs, dep)
                            ?: return@withContext """{"error":"依赖 $dep 不存在"}"""
                        val depResult = results[depId]
                            ?: return@withContext """{"error":"依赖 $dep 尚未执行"}"""
                        if (depResult.contains("\"error\"")) {
                            if (rollback) rollbackSnapshots(snapshots)
                            return@withContext """{"error":"依赖 $dep 执行失败，中止批量执行"}"""
                        }
                    }

                    // 2) 条件执行：不满足则跳过该步（不写盘、不影响整批）
                    if (spec.whenCond != null) {
                        val ok = try {
                            evalWhen(spec.whenCond, results, toolSpecs)
                        } catch (e: Exception) {
                            return@withContext """{"error":"when 求值失败: ${e.message?.take(200)}"}"""
                        }
                        if (!ok) {
                            steps.add(buildJsonObject {
                                put("id", spec.id); put("name", spec.name); put("status", "skipped")
                            })
                            continue
                        }
                    }

                    // 3) 步骤间结果传递（$ref → 上游结果）
                    val resolvedArgs = try {
                        resolveRefs(spec.arguments, results, toolSpecs) as? JsonObject ?: spec.arguments
                    } catch (e: Exception) {
                        return@withContext """{"error":"结果传递失败: ${e.message?.take(300)}"}"""
                    }

                    // 4) 执行工具
                    val result = try {
                        if (!com.mcp.core.prompt.modelDirectToolAllowed(spec.name, com.mcp.preset.PresetRuntime.current?.ptc == true)) {
                            com.mcp.toolbox.errorResult(com.mcp.core.prompt.ptcDirectCallRejection(spec.name))
                        } else {
                            toolbox.dispatch(spec.name, resolvedArgs)
                        }
                    } catch (e: Exception) {
                        val msg = (e as? ToolArgsParseException)?.message
                            ?: "工具执行失败：${e.message ?: e::class.simpleName}"
                        """{"error":"$msg"}"""
                    }
                    results[spec.id] = result

                    if (result.contains("\"error\"")) {
                        steps.add(buildJsonObject {
                            put("id", spec.id); put("name", spec.name); put("status", "error")
                        })
                        if (rollback) {
                            rollbackSnapshots(snapshots)
                            return@withContext """{"error":"工具 ${spec.name} 执行失败，已回滚: $result"}"""
                        }
                    } else {
                        steps.add(buildJsonObject {
                            put("id", spec.id); put("name", spec.name); put("status", "success")
                        })
                    }
                }

                val success = steps.count { it["status"]?.jsonPrimitive?.content == "success" }
                val skipped = steps.count { it["status"]?.jsonPrimitive?.content == "skipped" }
                buildJsonObject {
                    put("status", "success")
                    put("total", steps.size)
                    put("success", success)
                    put("skipped", skipped)
                    putJsonArray("steps") { steps.forEach { add(it) } }
                }.toString()
            } catch (e: Exception) {
                if (rollback) rollbackSnapshots(snapshots)
                """{"error":"批量执行异常: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  数据类与辅助函数
// ─────────────────────────────────────────────────────────────

data class BatchToolSpec(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val dependsOn: List<String>,
    val whenCond: StepWhen?
)

data class StepWhen(
    val dep: String,
    val op: String,
    val value: String? = null
)

/** 解析步骤引用：优先匹配 id，其次匹配 name。 */
private fun resolveStepId(specs: List<BatchToolSpec>, ref: String): String? =
    specs.firstOrNull { it.id == ref }?.id
        ?: specs.firstOrNull { it.name == ref }?.id

/**
 * 解析 `$ref`：把 arguments 里形如 `{"$ref": "步骤id[.json.path]"}` 的节点替换为
 * 上游步骤的输出（默认整段结果；带 path 时取 JSON 字段/数组下标）。
 */
private fun resolveRefs(node: JsonElement, results: Map<String, String>, specs: List<BatchToolSpec>): JsonElement {
    if (node is JsonObject && node.size == 1 && node.containsKey("\$ref")) {
        val ref = (node["\$ref"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("${'$'}ref 必须是字符串")
        return resolveRefValue(ref, results, specs)
    }
    return when (node) {
        is JsonObject -> buildJsonObject {
            node.forEach { (k, v) -> put(k, resolveRefs(v, results, specs)) }
        }
        is JsonArray -> buildJsonArray { node.forEach { add(resolveRefs(it, results, specs)) } }
        else -> node
    }
}

private fun resolveRefValue(ref: String, results: Map<String, String>, specs: List<BatchToolSpec>): JsonElement {
    val parts = ref.split('.', limit = 2)
    val stepRef = parts[0]
    val path = parts.getOrNull(1)
    val stepId = resolveStepId(specs, stepRef)
        ?: throw IllegalArgumentException("引用步骤不存在: $stepRef")
    val resultStr = results[stepId]
        ?: throw IllegalArgumentException("引用步骤尚未执行: $stepRef")

    var element: JsonElement = runCatching { LenientJson.parseToJsonElement(resultStr) }
        .getOrDefault(JsonPrimitive(resultStr))

    if (path != null) {
        for (seg in path.split('.')) {
            element = when (element) {
                is JsonObject -> element.jsonObject[seg]
                    ?: throw IllegalArgumentException("字段缺失: $seg（于 $stepRef 结果中）")
                is JsonArray -> {
                    val idx = seg.toIntOrNull()
                        ?: throw IllegalArgumentException("数组下标非法: $seg")
                    element.jsonArray.getOrNull(idx)
                        ?: throw IllegalArgumentException("数组下标越界: $seg")
                }
                else -> throw IllegalArgumentException("无法对非对象/数组取路径: $seg")
            }
        }
    }
    return element
}

/** 求值 `when` 条件，返回该步骤是否执行。 */
private fun evalWhen(cond: StepWhen, results: Map<String, String>, specs: List<BatchToolSpec>): Boolean {
    val stepId = resolveStepId(specs, cond.dep)
        ?: throw IllegalArgumentException("when 引用的步骤不存在: ${cond.dep}")
    val result = results[stepId]
        ?: throw IllegalArgumentException("when 引用的步骤尚未执行: ${cond.dep}")
    val hasError = result.contains("\"error\"")
    return when (cond.op) {
        "success" -> !hasError
        "failure" -> hasError
        "contains" -> result.contains(cond.value ?: "")
        "not_contains" -> !result.contains(cond.value ?: "")
        "equals" -> result == (cond.value ?: "")
        else -> throw IllegalArgumentException("不支持的 when.op: ${cond.op}")
    }
}

/**
 * 检测依赖循环（DFS）。
 */
private fun detectCycle(specs: List<BatchToolSpec>): List<String>? {
    val graph = specs.associate { it.id to it.dependsOn.mapNotNull { resolveStepId(specs, it) } }
    val visited = mutableSetOf<String>()
    val recursionStack = mutableSetOf<String>()

    fun dfs(node: String, path: MutableList<String>): List<String>? {
        if (node in recursionStack) return path.toList()
        if (node in visited) return null

        visited.add(node)
        recursionStack.add(node)
        path.add(node)

        for (neighbor in graph[node] ?: emptyList()) {
            val result = dfs(neighbor, path)
            if (result != null) return result
        }

        recursionStack.remove(node)
        path.removeAt(path.size - 1)
        return null
    }

    for (node in graph.keys) {
        val result = dfs(node, mutableListOf())
        if (result != null) return result
    }
    return null
}

/**
 * 拓扑排序（Kahn 算法），按 id 进行，返回可执行顺序。
 */
private fun topologicalSort(specs: List<BatchToolSpec>): List<BatchToolSpec> {
    val byId = specs.associateBy { it.id }
    val graph = specs.associate { it.id to it.dependsOn.mapNotNull { resolveStepId(specs, it) } }
    val inDegree = graph.keys.associateWith { 0 }.toMutableMap()
    graph.forEach { (_, deps) -> deps.forEach { inDegree[it] = (inDegree[it] ?: 0) + 1 } }

    val queue = graph.keys.filter { inDegree[it] == 0 }.toMutableList()
    val result = mutableListOf<BatchToolSpec>()

    while (queue.isNotEmpty()) {
        val node = queue.removeAt(0)
        byId[node]?.let { result.add(it) }
        for (dep in graph[node] ?: emptyList()) {
            val next = (inDegree[dep] ?: 0) - 1
            inDegree[dep] = next
            if (next == 0) queue.add(dep)
        }
    }
    return result
}

/**
 * 文件快照（用于回滚）。
 */
private fun snapshotFile(path: String): String? {
    val file = File(path)
    if (!file.exists()) return null
    return runCatching { file.readText() }.getOrNull()
}

private fun restoreSnapshot(path: String, content: String?) {
    val file = File(path)
    if (content == null) {
        file.delete()
    } else {
        file.writeText(content)
    }
}

private fun rollbackSnapshots(snapshots: MutableMap<String, String>) {
    for ((path, content) in snapshots) {
        restoreSnapshot(path, content)
    }
}