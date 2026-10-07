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
import com.mcp.copyFileTool
import com.mcp.deleteFileTool
import com.mcp.deleteRangeTool
import com.mcp.deleteTodo
import com.mcp.dependencyTools
import com.mcp.diffTools
import com.mcp.editFile
import com.mcp.getBlockTool
import com.mcp.globTool
import com.mcp.grepTool
import com.mcp.importTodos
import com.mcp.listFilesTool
import com.mcp.listTodos
import com.mcp.logTools
import com.mcp.moveFileTool
import com.mcp.multiEditFile
import com.mcp.qualityTools
import com.mcp.readFileTool
import com.mcp.undoEditTool
import com.mcp.runBash
import com.mcp.runBashBg
import com.mcp.bashTaskStatus
import com.mcp.bashTaskLogs
import com.mcp.bashTaskKill
import com.mcp.bashTaskList
import com.mcp.runRoot
import com.mcp.safListRoots
import com.mcp.safOpenDirectory
import com.mcp.safRemoveRoot
import com.mcp.searchAndReadTool
import com.mcp.searchFiles
import com.mcp.searchReplaceTool
import com.mcp.prootStatusTool
import com.mcp.describeImageTool
import com.mcp.generateImageTool
import com.mcp.readImageTool
import com.mcp.textToSpeechTool
import com.mcp.transcribeAudioTool
import com.mcp.updateTodo
import com.mcp.wechatTools
import com.mcp.writeFileTool
import com.mcp.browser.WebBrowser
import com.mcp.deepseek.AuthPrefs
import com.mcp.agent.AgentOrchestrator
import com.mcp.skill.SkillManager
import com.mcp.agent.agentCancelTool
import com.mcp.agent.agentCompleteTool
import com.mcp.agent.agentProgressTool
import com.mcp.agent.agentStatusTool
import com.mcp.agent.agentWorkflowTool
import com.mcp.agent.delegateToAgentTool
import com.mcp.agent.listAgentsTool
import com.mcp.skill.skillManagementTools
import com.mcp.ToolPrefs
import com.mcp.preset.Preset
import com.mcp.preset.PresetRuntime
import com.mcp.toolbox.Toolbox

/**
 * Chat/渠道运行时依赖。
 *
 * 这是应用的组合根：只有这里负责把工具、Agent 编排器和 Android Context 组装起来。
 * UI、会话桥接和渠道接入只依赖已经组装好的 [Toolbox]，不再各自维护注册清单。
 */
data class ToolRuntime(
    val toolbox: Toolbox,
    val agentOrchestrator: AgentOrchestrator,
    val skillManager: SkillManager,
    /** 完整工具目录（含用户禁用的工具）。ToolsFragment 用它展示全部工具。 */
    val catalog: ToolCatalog,
    /** 应用上下文。setToolEnabled / refreshTools 需要它来读写 [com.mcp.ToolPrefs]。 */
    val context: Context,
    /** 远程 MCP 连接管理（Client 方向）：接收远程工具并注册进 toolbox。 */
    val remoteRegistry: com.mcp.McpRemoteRegistry
) {
    /**
     * 释放**本实例**持有的资源。
     *
     * 注意不要在这里销毁 [WebBrowser]：运行时是「实例级」的（ChatBridge.destroy() 会被
     * 停本地 Web API 服务、会话 Fragment 销毁等路径触发），而 WebBrowser 是进程级单例，
     * 被顺带拆掉会让浏览器工具此后一直操作死会话。真正的浏览器释放见 [WebBrowser.destroy]，
     * 由 App/UI 生命周期在退出时调用（MainActivity.onDestroy isFinishing）。
     */
    fun destroy() {
        destroyed = true
        agentOrchestrator.destroy()
        remoteRegistry.stopPolling()
    }

    /** 是否已被 [destroy] 释放（Holder 据此丢弃旧实例、按需重建）。 */
    @Volatile
    var destroyed: Boolean = false
        private set

    /**
     * 重新扫描技能目录并注册脚本工具（导入/删除/移动技能后调用）。
     * 先注销全部已注册的脚本工具，再重新 discover，避免残留失效工具。
     */
    fun rescanSkills() {
        skillManager.reloadAll()
        // 技能工具重扫后 PTC SDK 段（程序内可见工具签名）同步刷新。
    }

    /**
     * 用户切换某个工具的启用/禁用状态：
     *  1. 持久化到 [com.mcp.ToolPrefs]（下次启动 App 时按此状态注册）；
     *  2. 立即同步到当前 [toolbox]（LLM 下一次请求工具列表就看到变化）。
     *
     * 关闭时 toolbox.unregister → LLM 请求工具列表看不到该工具、
     * 调用时会返回 {"error":"unknown tool: xxx"}，不会打断会话。
     * 打开时 toolbox.register → LLM 能看到并调用。
     */
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        ToolPrefs.setEnabled(context, toolName, enabled)
        if (enabled) {
            // 当前预设有白名单时不让开关越界：否则用户把白名单外的工具打开后，
            // 它会直接进 toolbox 被模型看到，破坏「极简/PTC 只见白名单」的契约。
            val allowed = preset?.allows(toolName) ?: true
            if (allowed && toolbox.get(toolName) == null) {
                val tool = catalog.all().find { it.name == toolName } ?: return
                toolbox.register(tool)
            }
        } else {
            toolbox.unregister(toolName)
        }
        // PTC 模式下 toolbox 变化后 SDK 签名需要同步刷新，否则模型看到的程序内工具签名过期。
    }

    /**
     * 批量切换一组工具的启用状态（工具页的「整组开关」用）。
     *
     * 为什么不循环调 [setToolEnabled]：后者每次都要 `catalog.all()` 重建整个工具目录
     * （近百个 ToolDef 构造）再 linear find——浏览器组 30 个工具就是 30 次全量重建。
     * 这里一次性建 name→ToolDef 索引，复杂度从 O(组大小 × 目录大小) 降到 O(目录大小 + 组大小)。
     *
     * 语义与 [setToolEnabled] 完全一致（含预设白名单把守），只是省掉了重复劳动：
     *  - 启用时逐个校验 `preset.allows(name)`，白名单外的不注册（也不写偏好，避免假开关）；
     *  - 关闭时无条件 unregister（关掉永远安全）。
     *
     * @param toolNames 目标工具名（不在目录里的名字静默忽略）
     * @param enabled   true 启用 / false 禁用
     * @return 实际生效（被改写偏好）的工具数——被预设挡住的不计入，供 UI 提示用。
     */
    fun setToolsEnabled(toolNames: Collection<String>, enabled: Boolean): Int {
        if (toolNames.isEmpty()) return 0
        val wanted = toolNames.toHashSet()
        val byName = catalog.all().associateBy { it.name }
        var applied = 0
        for (name in wanted) {
            val tool = byName[name] ?: continue
            if (enabled) {
                val allowed = preset?.allows(name) ?: true
                if (!allowed) continue
                ToolPrefs.setEnabled(context, name, true)
                if (toolbox.get(name) == null) toolbox.register(tool)
            } else {
                ToolPrefs.setEnabled(context, name, false)
                toolbox.unregister(name)
            }
            applied++
        }
        return applied
    }

    /**
     * 全量重扫：清空 toolbox，按「[com.mcp.ToolPrefs] ∩ 当前预设白名单」重新注册。
     * 用于「恢复默认」按钮、或外部修改了 ToolPrefs 后强制同步。
     *
     * 走 [PresetRuntime.apply] 而不是自己扫，是为了让预设的三件事
     * （工具集 / 系统提示词 / 能力接缝）始终一起生效，不会出现「工具已切到极简、
     * 提示词还是完整版」这种半切换状态。
     */
    fun refreshTools() {
        PresetRuntime.apply(context, this, PresetRuntime.current)
    }

    /**
     * 切换会话预设（极简模式 / 完整模式 / 用户自定义），立即热生效**并持久化全局用户偏好**。
     * 仅用于「用户主动选择」；打开会话时的运行时同步请用 [activatePreset]。
     * @return 切换后的预设；null 表示回到全量模式。
     */
    fun setPreset(presetId: String?): Preset? = PresetRuntime.select(context, this, presetId)

    /**
     * 把某个会话的预设作用到运行时，但**不改写全局用户偏好**（persistPreference=false）。
     *
     * 用于打开会话时的同步（[com.mcp.ChatBridge.applySessionPreset]）——会话预设是会话级状态，
     * 不应污染用户偏好，否则「打开一个完整模式旧会话」会覆盖用户选的极简偏好，新建会话随之回落完整。
     *
     * @return 生效的预设；null 表示回到全量模式。
     */
    fun activatePreset(presetId: String?): Preset? =
        PresetRuntime.select(context, this, presetId, persistPreference = false)

    /** 当前生效的预设（null = 全量模式）。 */
    val preset: Preset? get() = PresetRuntime.current
}

object ToolRuntimeFactory {
    /**
     * 组装应用运行时：Toolbox + AgentOrchestrator + SkillManager + ToolCatalog。
     *
     * 注册策略：
     *  - [ToolCatalog.all()] 返回全部工具定义（不区分启用/禁用）；
     *  - 按 [ToolPrefs.isEnabled] 过滤后注册进 toolbox（用户禁用的不注册，
     *    LLM 请求工具列表时看不到，调用时返回 unknown tool）；
     *  - [ToolRuntime.setToolEnabled] 让用户在「工具」页面切换状态后即时生效，
     *    下次启动 App 时按 ToolPrefs 持久化状态重新注册。
     */
    fun create(context: Context, auth: AuthPrefs): ToolRuntime {
        val appContext = context.applicationContext
        val toolbox = Toolbox()
        val orchestrator = AgentOrchestrator(auth, toolbox, appContext)

        // 技能系统：加载技能并把技能绑定的脚本注册为真实工具（skill_<技能名>_<脚本名>）。
        // 借鉴 browser-use 的 skill 机制：技能 = markdown 知识 + 可执行脚本能力。
        val skillManager = SkillManager(appContext, toolbox)
        skillManager.discoverAndLoad()

        // 远程 MCP 连接管理（Client 方向）：需先于 catalog 建好——catalog 里
        // 的 mcp_* 管理工具要惰性引用它。
        val remoteRegistry = com.mcp.McpRemoteRegistry(appContext, toolbox)

        // 工具目录：抽出到 [ToolCatalog]，与 ToolsFragment 共用。
        // batchTools 需要反向引用最终注册完成的 Toolbox，所以传 lazy provider。
        val catalog = ToolCatalog(appContext, auth, orchestrator, skillManager, { toolbox }, { remoteRegistry })

        // 按 ToolPrefs 状态注册启用的工具。用户禁用的不注册 → LLM 看不到。
        catalog.all().forEach { tool ->
            if (ToolPrefs.isEnabled(appContext, tool.name)) toolbox.register(tool)
        }

        val runtime = ToolRuntime(toolbox, orchestrator, skillManager, catalog, appContext, remoteRegistry)

        // 注册 LiteRT 本地模型后端 provider：把 app 层的配置 + 缓存目录注入 :llm 工厂。
            // :llm 不依赖 :litert（后者含 native 库），故用 provider 解耦。
            com.mcp.llm.LLMClientFactory.litertClientProvider = {
                val path = auth.getLiteRtModelPath()
                if (path.isBlank()) null
                else {
                    val backend = runCatching {
                        com.mcp.litert.LiteRtBackend.valueOf(auth.getLiteRtBackend())
                    }.getOrDefault(com.mcp.litert.LiteRtBackend.CPU)
                    com.mcp.litert.LiteRtClient(
                            config = com.mcp.litert.LiteRtConfig(
                                modelPath = path,
                                backend = backend,
                                cacheDir = appContext.cacheDir.path,
                                temperature = auth.getLiteRtTemperature(),
                                topK = auth.getLiteRtTopK(),
                                topP = auth.getLiteRtTopP(),
                                nativeLibraryDir = appContext.applicationInfo.nativeLibraryDir,
                                maxNumTokens = auth.getLiteRtMaxTokens(),
                                // 工具执行器：与 OpenAI 分支共用同一个 Toolbox 实例，
                                // LiteRtToolAdapter 桥接后由 LiteRT 调用、走项目的 dispatch。
                                toolbox = toolbox,
                            )
                        )
                }
            }
    
            // 注册 MNN 本地模型后端 provider：与 LiteRT 同理，:llm 不依赖 :mnn，
            // 由 app 层注入配置。
            com.mcp.llm.LLMClientFactory.mnnClientProvider = {
                val dir = auth.getMnnModelDir()
                if (dir.isBlank()) null
                else com.mcp.mnn.MnnClient(
                    config = com.mcp.mnn.MnnConfig(
                        modelDir = dir,
                        backendType = auth.getMnnBackend(),
                        threadNum = auth.getMnnThreads(),
                        maxNewTokens = auth.getMnnMaxOutput(),
                    )
                )
            }

            // 启动远程 MCP 周期探测（默认 5s）：工具集变化时自动热更新本地注册。
        // 内部会自行创建协程作用域，不阻塞这里；未配置服务器时只是空转，开销可忽略。
        remoteRegistry.startPolling()

        // 预设（极简模式等）：加载内置 + assets 预设，恢复上次选择后热生效。
        // 只在**确实选了预设**时才重扫 toolbox——全量模式下组合根已经注册好了，
        // 再扫一遍只是白白重建全部工具定义（并多跑一次技能重扫）。
        PresetRuntime.bootstrap(appContext)
        PresetRuntime.current?.let { PresetRuntime.apply(appContext, runtime, it) }
        return runtime
    }
}

/**
 * 应用级共享的 ToolRuntime 单例。
 *
 * 组合根只组装一次：主聊天（ChatBridge）、微信桥接等渠道统一复用这份
 * Toolbox 与 AgentOrchestrator，不再各自维护注册清单。
 */
object ToolRuntimeHolder {
    @Volatile
    private var cached: ToolRuntime? = null

    /**
     * 取运行时；**已被 destroy 的实例会被丢弃并重建**。
     *
     * 之前的实现只认 `cached != null`：一旦运行时被 destroy（停本地 Web API 服务、会话 Fragment
     * 销毁等路径都会触发 ChatBridge.destroy），后续 get() 仍返回那个「半死」实例，
     * 新会话的浏览器/工具全打在死对象上——这正是「点一下开发者工具/停个服务就崩坏」的根因之一。
     */
    fun get(context: Context, auth: AuthPrefs): ToolRuntime {
        cached?.takeIf { !it.destroyed }?.let { return it }
        return synchronized(this) {
            cached?.takeIf { !it.destroyed }
                ?: ToolRuntimeFactory.create(context, auth).also { cached = it }
        }
    }

    /** 运行期重扫技能（导入/删除/移动后调用）；未初始化时为空操作。 */
    fun rescanSkills() {
        cached?.rescanSkills()
    }

    /** 显式释放并清空缓存（App 退出时调用；平时靠 get() 的销毁检测自愈）。 */
    fun reset() {
        synchronized(this) {
            cached?.destroy()
            cached = null
        }
    }
}