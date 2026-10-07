package com.mcp.composition

import android.content.Context
import com.mcp.addTodo
import com.mcp.askUserTool
import com.mcp.asyncTaskCancel
import com.mcp.asyncTaskList
import com.mcp.asyncTaskLogs
import com.mcp.asyncTaskStatus
import com.mcp.asyncTaskSubmit
import com.mcp.batchTools
import com.mcp.browserTools
import com.mcp.buildTools
import com.mcp.bashTaskKill
import com.mcp.bashTaskList
import com.mcp.bashTaskLogs
import com.mcp.bashTaskStatus
import com.mcp.copyFileTool
import com.mcp.deleteFileTool
import com.mcp.deleteRangeTool
import com.mcp.deleteTodo
import com.mcp.dependencyTools
import com.mcp.describeImageTool
import com.mcp.diffTools
import com.mcp.editFile
import com.mcp.getBlockTool
import com.mcp.globTool
import com.mcp.generateImageTool
import com.mcp.grepTool
import com.mcp.importTodos
import com.mcp.listFilesTool
import com.mcp.listTodos
import com.mcp.logTools
import com.mcp.moveFileTool
import com.mcp.multiEditFile
import com.mcp.prootStatusTool
import com.mcp.ptc.persistentBashReset
import com.mcp.ptc.persistentBashStatus
import com.mcp.ptc.runBashPersistent
import com.mcp.ptc.runCodeTool
import com.mcp.qualityTools
import com.mcp.readFileTool
import com.mcp.readImageTool
import com.mcp.runBash
import com.mcp.runBashBg
import com.mcp.runRoot
import com.mcp.safListRoots
import com.mcp.safOpenDirectory
import com.mcp.sandboxMountAllow
import com.mcp.sandboxMountList
import com.mcp.sandboxMountRevoke
import com.mcp.safRemoveRoot
import com.mcp.searchAndReadTool
import com.mcp.searchFiles
import com.mcp.searchReplaceTool
import com.mcp.textToSpeechTool
import com.mcp.transcribeAudioTool
import com.mcp.undoEditTool
import com.mcp.updateTodo
import com.mcp.wechatTools
import com.mcp.writeFileTool
import com.mcp.agent.agentCancelTool
import com.mcp.agent.agentCompleteTool
import com.mcp.agent.agentProgressTool
import com.mcp.agent.agentStatusTool
import com.mcp.agent.agentWorkflowTool
import com.mcp.agent.delegateToAgentTool
import com.mcp.agent.listAgentsTool
import com.mcp.agent.AgentOrchestrator
import com.mcp.skill.SkillManager
import com.mcp.skill.skillManagementTools
import com.mcp.deepseek.AuthPrefs
import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.examples.calculator
import com.mcp.toolbox.examples.currentTime
import com.mcp.toolbox.examples.httpRequest
import com.mcp.toolbox.examples.stringLength
import com.mcp.toolbox.examples.webSearch

/**
 * 工具目录：把应用全部工具（示例 / 终端 / 异步 / 待办 / 文件 / 搜索 /
 * 多模态 / SAF / Agent / 聚合 / 微信 / 浏览器 / Skill 管理 / Batch）集中
 * 到一个清单，供两处共用：
 *
 *  1. [ToolRuntimeFactory.create] — 按 [com.mcp.ToolPrefs] 开关状态决定
 *     哪些工具注册进 [Toolbox]（进而决定 LLM 能否看到 / 调用）。
 *  2. [com.mcp.ToolsFragment] — 展示全部工具给用户看，每行一个开关，
 *     让用户自行控制「LLM 能不能用这个工具」。
 *
 * 关键设计：`all()` 返回的是**全部工具定义**（含被禁用的），
 * 过滤逻辑在注册阶段做。这样 UI 层始终能看到完整目录，
 * 不会因为关闭某个工具导致它从 UI 上消失（用户就找不回来打开了）。
 *
 * @param context         Android ApplicationContext
 * @param auth            DeepSeek/OpenAI 认证偏好（多模态工具需要）
 * @param orchestrator    Agent 编排器（Agent 工具需要）
 * @param skillManager    技能管理器（Skill 管理工具需要）
 * @param toolboxProvider 惰性返回最终 Toolbox（batchTools 需要反向引用）
 */
class ToolCatalog(
    private val context: Context,
    private val auth: AuthPrefs,
    private val orchestrator: AgentOrchestrator,
    private val skillManager: SkillManager,
    private val toolboxProvider: () -> Toolbox,
    /** 远程 MCP 注册表（Client 方向）；组合根在创建 catalog 前先建好并传入。 */
    private val mcpRegistryProvider: () -> com.mcp.McpRemoteRegistry
) {

    /** 按名字取单个工具定义（找不到返回 null）。 */
    fun get(name: String): ToolDef? = all().firstOrNull { it.name == name }

    /** 返回全部工具定义。调用方按需过滤（例如 [com.mcp.ToolPrefs.isEnabled]）。 */
    fun all(): List<ToolDef> = buildList {
        // 1. 示例 / 自测工具（calculator / currentTime / stringLength / httpRequest / webSearch）
        add(calculator())
        add(currentTime())
        add(stringLength())
        add(httpRequest())
        add(webSearch())

        // 2. 终端 / 系统
        add(runBash(context))
        // 极简模式主工具：跨调用保持 cwd / 环境变量的长活 bash（dsh persistent-bash）
        add(runBashPersistent(context))
        add(persistentBashStatus(context))
        add(persistentBashReset(context))
        add(runBashBg(context))
        add(bashTaskStatus(context))
        add(bashTaskLogs(context))
        add(bashTaskKill(context))
        add(bashTaskList(context))

        // 3. 异步长任务
        add(asyncTaskSubmit(context))
        add(asyncTaskStatus(context))
        add(asyncTaskLogs(context))
        add(asyncTaskCancel(context))
        add(asyncTaskList(context))

        // 4. Root 命令
        add(runRoot(context))

        // 5. 待办
        add(listTodos(context))
        add(addTodo(context))
        add(updateTodo(context))
        add(importTodos(context))
        add(deleteTodo(context))

        // 6. 文件
        add(readFileTool(context))
        add(writeFileTool(context))
        add(editFile(context))
        add(multiEditFile(context))
        add(getBlockTool(context))
        add(deleteFileTool(context))
        add(deleteRangeTool(context))
        add(listFilesTool(context))
        add(copyFileTool(context))
        add(moveFileTool(context))
        add(undoEditTool(context))

        // 7. 搜索
        add(searchFiles(context))
        add(searchReplaceTool(context))
        add(globTool(context))
        add(grepTool(context))
        add(prootStatusTool(context))
        add(searchAndReadTool(context))
        add(askUserTool())

        // 8. 多模态（图像 / 音频）
        add(readImageTool(context))
        add(describeImageTool(context, auth))
        add(generateImageTool(context, auth))
        add(transcribeAudioTool(context, auth))
        add(textToSpeechTool(context, auth))

        // 9. SAF 存储访问
        add(safOpenDirectory(context))
        add(safListRoots(context))
        add(safRemoveRoot(context))

        // 9.5 沙盒宿主挂载授权（默认零暴露，用户显式授权才放行）
        add(sandboxMountAllow(context))
        add(sandboxMountList(context))
        add(sandboxMountRevoke(context))

        // 10. Agent 编排
        add(delegateToAgentTool(orchestrator))
        add(agentStatusTool(orchestrator))
        add(agentCancelTool(orchestrator))
        add(agentCompleteTool(orchestrator))
        add(listAgentsTool(orchestrator))
        add(agentProgressTool(orchestrator))
        add(agentWorkflowTool(orchestrator))

        // 11. 聚合工具（每个聚合返回一组）
        addAll(buildTools(context))
        addAll(qualityTools(context))
        addAll(logTools(context))
        addAll(diffTools(context))
        addAll(dependencyTools(context))
        addAll(wechatTools(context))
        addAll(browserTools(context))

        // 12. 技能市场
        addAll(skillManagementTools(context) { skillManager })

        // 13. 批量执行（需要反向引用最终注册完成的 Toolbox，最后补注册）
        addAll(batchTools(context, toolboxProvider))

        // 14. PTC（Programmatic Tool Call）：一段程序内直接调用注册表工具。
        //     需要反向引用 Toolbox 做子调用分发，故与 batchTools 一样最后补注册。
        add(runCodeTool(context, toolboxProvider))

        // 15. MCP 远程连接管理（Client 方向）：列出/添加/移除/刷新远程 MCP 服务器。
        //     注册表由组合根创建，这里惰性取用，避免循环依赖。
        addAll(com.mcp.mcpTools(context) { mcpRegistryProvider() })
    }
}
