package com.mcp.agent

import com.mcp.toolbox.tool
import com.mcp.toolbox.ToolDef
import kotlinx.serialization.json.jsonPrimitive

/**
 * 多 Agent 协作系统的工具定义。
 *
 * 这些工具注册到主控 Toolbox 后，LLM 即可通过 function calling（OpenAI 格式）调用它们来委派子 Agent。
 */

/** 委派任务给子 Agent（同步，等待完成）。 */
fun delegateToAgentTool(orchestrator: AgentOrchestrator): ToolDef = tool("delegate_to_agent") {
    description = "委派任务给子 Agent 执行。子 Agent 有独立的会话和工具权限，完成后返回结果给主控 Agent。支持并行委派多个不同 Agent。"
    string("agent_type") {
        description = "子Agent类型"
        required = true
        enumValues = AgentType.deployable.map { it.name }
    }
    string("task") {
        description = "任务描述，越详细越好。子Agent会独立完成这个任务。"
        required = true
    }
    string("starting_directory") {
        description = "子 Agent 的工作起点目录（绝对路径，如 /sdcard/gk 或 SAF 已授权根目录下的路径）。子 Agent 所有文件操作都优先在此目录内开展，否则它不知道从哪开始。必填。"
        required = true
    }
    handler { args ->
        val agentTypeName = args["agent_type"]?.jsonPrimitive?.content
            ?: return@handler """{"error": "缺少 agent_type 参数"}"""
        val task = args["task"]?.jsonPrimitive?.content
            ?: return@handler """{"error": "缺少 task 参数"}"""
        val startingDirectory = args["starting_directory"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?: return@handler """{"error": "缺少 starting_directory 参数（子 Agent 需要知道从哪个目录开始工作）"}"""

        val agentType = AgentType.find(agentTypeName)
            ?: return@handler """{"error": "未知 Agent 类型: $agentTypeName。可用: ${AgentType.deployable.map { it.name }}"}"""

        val result = orchestrator.delegate(agentType, task, parentSessionId = com.mcp.ptc.PtcAudit.current().orEmpty(), startingDirectory = startingDirectory)
        """
        |## 子Agent: ${agentType.displayName} (${agentType.name})
        |
        |$result
        """.trimMargin()
    }
}

/** 查询委派任务的状态。 */
fun agentStatusTool(orchestrator: AgentOrchestrator): ToolDef = tool("agent_status") {
    description = "查询已委派的子 Agent 任务状态。不传 task_id 则列出所有活跃任务。"
    integer("task_id") {
        description = "任务ID（从 delegate_to_agent 返回的 id）。不传则列出所有。"
        required = false
    }
    handler { args ->
        val taskId = args["task_id"]?.jsonPrimitive?.content?.toIntOrNull()

        if (taskId != null) {
            val task = orchestrator.getTaskStatus(taskId)
            if (task == null) {
                """{"error": "任务 #$taskId 不存在或已过期"}"""
            } else {
                """
                |任务 #${task.id}: ${task.agentType.displayName}
                |状态: ${task.status}
                |${if (task.result != null) "结果: ${task.result!!.take(500)}" else ""}
                |${if (task.error != null) "错误: ${task.error}" else ""}
                """.trimMargin()
            }
        } else {
            val tasks = orchestrator.listActiveTasks()
            if (tasks.isEmpty()) {
                "当前没有活跃的子 Agent 任务。"
            } else {
                buildString {
                    appendLine("活跃的子 Agent 任务 (${tasks.size}):")
                    appendLine()
                    tasks.forEach { t ->
                        appendLine("- #${t.id}: ${t.agentType.displayName} [${t.status}] — ${t.task.take(60)}")
                    }
                }
            }
        }
    }
}

/** 子 Agent 主动声明任务完成（用于子 Agent 内部调用，回报最终结果并结束自己的工作流）。 */
fun agentCompleteTool(orchestrator: AgentOrchestrator): ToolDef = tool("agent_complete") {
    description = "【子Agent专用】声明当前任务已完成，并回报最终结果。调用后你的工作流立即结束，结果返回给主控 Agent。完成任务后必须调用本工具，而不是只输出文字。"
    string("result") {
        description = "任务的最终结果（结构化文本，如变更报告 / 调查结论 / 测试报告等）"
        required = true
    }
    handler { args ->
        val result = args["result"]?.jsonPrimitive?.content
            ?: return@handler """{"error": "缺少 result 参数"}"""
        orchestrator.completeCurrentTask(result)
    }
}

/** 子 Agent 推送进度信息（不结束任务，仅更新进度）。 */
fun agentProgressTool(orchestrator: AgentOrchestrator): ToolDef = tool("agent_progress") {
    description = "【子Agent专用】推送任务进度信息，不结束任务，任务继续执行。在长任务中定期调用，让主控 Agent 了解执行进展。"
    string("message") {
        description = "进度描述信息（如 \"已完成 3/10 个文件\"）"
        required = true
    }
    integer("progress") {
        description = "进度百分比（0-100），可选。传 -1 表示无进度"
        required = false
    }
    handler { args ->
        val message = args["message"]?.jsonPrimitive?.content
            ?: return@handler """{"error": "缺少 message 参数"}"""
        val progress = args["progress"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        orchestrator.updateProgress(message, progress)
    }
}

/** 取消正在运行的子 Agent 任务。 */
fun agentCancelTool(orchestrator: AgentOrchestrator): ToolDef = tool("agent_cancel") {
    description = "取消正在运行的子 Agent 任务。"
    integer("task_id") {
        description = "要取消的任务ID"
        required = true
    }
    handler { args ->
        val taskId = args["task_id"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return@handler """{"error": "缺少 task_id 参数"}"""

        if (orchestrator.cancelTask(taskId)) {
            "任务 #$taskId 已取消。"
        } else {
            """{"error": "任务 #$taskId 不存在或无法取消（可能已完成）"}"""
        }
    }
}

/** 列出所有可用的子 Agent 类型。 */
fun listAgentsTool(orchestrator: AgentOrchestrator): ToolDef = tool("list_agents") {
    description = "列出所有可用的子 Agent 类型及其职责和权限。在决定委派给哪个 Agent 之前调用。"
    handler {
        """
        |## 可用子Agent类型
        |
        |${orchestrator.listAgentTypes()}
        |
        |## 使用方式
        |
        |调用 `delegate_to_agent` 工具，传入 agent_type、task 和 starting_directory 参数（starting_directory 指定子 Agent 的工作起点目录）。
        |多个独立任务可以并行委派（在同一个回复中输出多个工具调用，遵循 OpenAI function calling 格式）。
        """.trimMargin()
    }
}
