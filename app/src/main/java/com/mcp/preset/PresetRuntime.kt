package com.mcp.preset

import android.content.Context
import com.mcp.LogStore
import com.mcp.ToolPrefs
import com.mcp.composition.ToolRuntime
import com.mcp.core.prompt.PresetPromptOverride
import com.mcp.deepseek.AuthPrefs
import com.mcp.ptc.PersistentShellRegistry
import com.mcp.ptc.RUN_CODE_TOOL
import com.mcp.toolbox.Toolbox

/**
 * 预设运行时：把「选中的预设」真正作用到会话上（工具集 / 系统提示词 / 能力接缝）。
 *
 * 这是 deepseek-harness 的 Preset/Profile 在基板上的落点：预设不再只是文案，
 * 而是一次**会话组合的切换动作**，分三步生效：
 *  1. **工具集**：白名单非空时，只把白名单内（且用户未禁用）的工具注册进 [Toolbox]，
 *     其余全部 unregister——LLM 看不到完整工具清单，也不会被无关工具干扰。
 *  2. **系统提示词**：`systemPromptOverride` 写入 [PresetPromptOverride]，
 *     由 [com.mcp.core.prompt.SystemPromptComposer] 在下次组包时**替换**默认长提示词。
 *  3. **能力接缝**：若白名单包含 `run_bash_persistent`，把 [com.mcp.CapabilityRegistry.shell]
 *     换成持久实现（[PersistentShellRegistry.activate]）；否则切回一次性 PRoot。
 *
 * 所有切换都是**热切换**，不需要重启 App。
 */
object PresetRuntime {

    /** 当前会话生效的预设；null 表示全量模式。 */
    @Volatile
    var current: Preset? = null
        private set

    /** 应用启动调用一次：加载内置 + assets 预设，恢复上次选择。 */
    fun bootstrap(context: Context) {
        if (PresetManager.list().isEmpty()) {
            PresetManager.register(FULL)
            PresetManager.loadFromAssets(context)
        }
        PresetPrefs.selected(context)?.let { id ->
            current = PresetManager.get(id)
        }
    }

    /**
     * 切换预设并立即生效。
     *
     * @param runtime 已组装好的运行时（提供 toolbox 与完整工具目录）
     * @param persistPreference 是否把本次切换写入**全局用户偏好**（[PresetPrefs]）。
     *   只有「用户主动选择」才该为 true；「打开会话时把该会话的预设同步到运行时」必须为 false——
     *   否则打开任意会话都会顺带覆盖全局偏好，造成「选极简 → 打开完整模式旧会话 → 新建回落完整」。
     */
    fun select(context: Context, runtime: ToolRuntime, presetId: String?, persistPreference: Boolean = true): Preset? {
        val preset = presetId?.let { PresetManager.get(it) }
        current = preset
        if (persistPreference) PresetPrefs.select(context, preset?.id)
        apply(context, runtime, preset)
        return preset
    }

    /**
     * 用户**主动选择**的预设 id（持久化偏好，跨重启保持）。
     *
     * 与 [current] 的区别：[current] 是「当前会话生效的预设」，会被
     * [com.mcp.ChatBridge.applySessionPreset] 在打开任意会话时改写（用于会话级隔离）；
     * 本方法读的是用户偏好本身，**不受会话切换影响**。
     *
     * 新建会话必须用本方法取 presetId——否则「选了极简 → 中途打开过完整模式旧会话 →
     * 新建回落完整模式」：全局 current 被上一个会话覆盖，用户偏好丢失。
     *
     * @return 用户偏好 id；从未选择过返回 null（语义等价完整模式）。
     */
    fun userPreferredId(context: Context): String? = PresetPrefs.selected(context)

    /** 把预设作用到运行时（不写持久化）。 */
    fun apply(context: Context, runtime: ToolRuntime, preset: Preset?) {
        applyToolFilter(context, runtime, preset)
        applyPromptOverride(context, preset)
        applyCapabilitySeam(preset)
    }

    // ── 1. 工具集过滤 ────────────────────────────────────────

    private fun applyToolFilter(context: Context, runtime: ToolRuntime, preset: Preset?) {
        val toolbox = runtime.toolbox
        val beforeCount = toolbox.all().size
        // 先清空，再按「ToolPrefs ∩ 预设白名单」重扫。
        // 注意：技能脚本工具（skill_<技能>_<脚本>）由 SkillManager 直接注册进 toolbox、
        // 不在 catalog 里，清空后必须显式补回——否则每次切预设都会把技能工具弄丢。
        toolbox.all().toList().forEach { toolbox.unregister(it.name) }
        runtime.catalog.all().forEach { def ->
            val enabled = ToolPrefs.isEnabled(context, def.name) && (preset?.allows(def.name) ?: true)
            if (enabled) toolbox.register(def)
        }
        // PTC 预设下：程序仍需能调起白名单工具，但模型「直接」可见的只有 run_code（呈现折叠）。
        // 因此需要把 run_code 注册进 toolbox（程序入口），其余工具仅作为「程序内可见」存在。
        if (preset?.ptc == true) {
            runtime.catalog.get(RUN_CODE_TOOL)?.let { toolbox.register(it) }
        }
        // 框架级 Agent 控制工具：`agent_complete` / `agent_progress` 必须**无条件注册**，
        // 不受预设白名单与用户偏好过滤。
        //
        // 原因：它们是子 Agent 工作流的**终止/进度协议**，不是业务工具——
        //  - 子 Agent 主循环把「收到 agent_complete（task.completion）」当作唯一正常结束条件；
        //  - PTC 预设下，子 Agent 若想用 run_code 程序内 `tools.agent_complete(...)` 回报结果，
        //    前提是 toolbox 里**注册了**它，否则 JS 侧 tools.agent_complete 是 undefined → TypeError，
        //    子 Agent 永远无法结束（真机实测：run_code 里调不通）。
        // 白名单约束的是「这个 Agent 能用哪些业务工具」，不该把**终止协议本身**挡掉。
        for (agentControl in AGENT_CONTROL_TOOLS) {
            runtime.catalog.get(agentControl)?.let { toolbox.register(it) }
        }
        // 全量模式下补回技能脚本工具；极简/PTC 模式刻意不补（技能工具不在其白名单内）。
        if (preset == null || !preset.isRestricted) runtime.rescanSkills()
        // 补回远程 MCP 工具（纯内存，不联网）：上面的 unregister 清空了 toolbox，
        // 这里从注册表缓存按「ToolPrefs ∩ 新预设白名单」重新注册。
        runtime.remoteRegistry.reregisterFromCache()
        val afterCount = toolbox.all().size
        val toolNames = toolbox.all().map { it.name }.joinToString(", ")
        LogStore.i("PRESET", "applyToolFilter: preset=${preset?.id} before=$beforeCount after=$afterCount tools=[$toolNames]")
    }

    // ── 2. 系统提示词 ────────────────────────────────────────

    /**
         * 解析实际使用的协议风格。
         *
         * 优先级（高 → 低）：
         *  1. **用户偏好**：用户在设置页显式选的 LINE 或 XML（空串=跟随预设）。
         *     只对文本协议后端（DeepSeek 逆向 / Web 自动化）有意义。
         *  2. **预设声明**：Preset.protocolStyle。
         *
         * 历史：曾有「受限预设（极简）强制 XML」这条最高优先级。现已移除——
         * 极简的 systemPromptOverride 并不含协议说明，调用格式由 SystemPromptComposer
         * 按 protocolStyle 临时追加（xmlProtocolGuide / lineProtocolGuide），
         * 切换协议时追加段跟着换，不存在「提示词写死 XML」的自相矛盾。
         * 保留该特判的实际效果是「极简无法被用户切成行式」，与「协议风格正交于预设」
         * 的设计相悖（PTC 能切、极简不能切，判据不一致）。
         */
        private fun resolveProtocolStyle(context: Context, preset: Preset?): PresetPromptOverride.ProtocolStyle {
            // 用户偏好覆盖（空串=未选，落到预设声明）
            when (AuthPrefs(context).getProtocolStylePref()) {
                "xml" -> return PresetPromptOverride.ProtocolStyle.XML
                "line" -> return PresetPromptOverride.ProtocolStyle.LINE
            }
            // 跟随预设
            return if (preset?.xmlProtocol == true) PresetPromptOverride.ProtocolStyle.XML
            else PresetPromptOverride.ProtocolStyle.LINE
        }
    
        private fun applyPromptOverride(context: Context, preset: Preset?) {
        val style = resolveProtocolStyle(context, preset)
        PresetPromptOverride.set(
            current = preset?.systemPromptOverride,
            complete = preset?.complete ?: false,
            protocolStyle = style,
            // 受限预设（极简模式）的技能工具未注册，提示词里也不能注入技能清单。
            restricted = preset?.isRestricted ?: false
        )
    }


    /**
     * 指定会话预设是否为 PTC 模式（按 presetId 判定，不再读进程级全局状态）。
     * 传入 null 时回退到当前生效预设（兼容旧会话未持久化 presetId 的情况）。
     */
    fun isPtc(presetId: String?): Boolean {
        val id = presetId?.takeIf { it.isNotBlank() } ?: current?.id
        return id?.let { PresetManager.get(it)?.ptc } ?: false
    }

    /**
     * 指定会话预设是否允许**常规**上下文压缩（按压力阈值触发的那类）。
     *
     * `compaction:false` 的预设（极简）不压：缩短会话、避免摘要替换丢掉状态。
     * 溢出自救（provider 已报窗口溢出后的强制缩减）不经此判定——那是最后手段。
     * 传入 null 时回退到当前生效预设（兼容旧会话未持久化 presetId 的情况）。
     */
    fun isCompactionEnabled(presetId: String?): Boolean {
        val id = presetId?.takeIf { it.isNotBlank() } ?: current?.id
        return id?.let { PresetManager.get(it)?.compaction } ?: true
    }

    // ── 2b. PTC 呈现折叠 ─────────────────────────────────────
    // 说明：PTC 的 SDK 段 / 折叠规则不再在这里生成全局快照——改为按请求从会话预设派生
    // （见 core 的 ptcRequestContext + SystemPromptComposer 的 ptc 参数），
    // 因此工具集变化无需 refresh，也不会出现「SDK 快照过期」与跨会话串味。

    // ── 3. 能力接缝 ──────────────────────────────────────────

    private fun applyCapabilitySeam(preset: Preset?) {
        val wantsPersistent = preset?.allowedTools?.contains(PERSISTENT_BASH_TOOL) == true
        if (wantsPersistent) PersistentShellRegistry.activate()
        else PersistentShellRegistry.deactivate()
    }

    /** 极简模式的标志性工具名。 */
    const val PERSISTENT_BASH_TOOL = "run_bash_persistent"

    /**
     * 框架级 Agent 控制工具：子 Agent 工作流的终止/进度协议，**无条件注册**、
     * 不受预设白名单与用户偏好过滤（见 [applyToolFilter]）。
     *
     * 与 [com.mcp.core.prompt.PTC_DIRECT_ALLOWED] 保持一致：这两个工具在 PTC 下
     * 也允许模型直调（豁免折叠），并额外保证程序内 `tools.agent_complete(...)` 可用。
     */
    val AGENT_CONTROL_TOOLS = listOf("agent_complete", "agent_progress")

    /** 全量模式：空白名单 = 全部工具。 */
    val FULL = Preset(
        id = "full",
        name = "完整模式",
        description = "全部工具可用，沿用默认系统提示词。"
    )
}

/** 选中的预设 id 持久化（跨重启保持，跟随 AuthPrefs 加密存储）。 */
object PresetPrefs {
    fun select(context: Context, id: String?) {
        val authPrefs = AuthPrefs(context)
        if (id == null) authPrefs.clearPreset()
        else authPrefs.savePreset(id)
    }

    fun selected(context: Context): String? = AuthPrefs(context).getPreset()
}

/** 便捷：当前预设是否允许某工具（PTC 桥等处复用）。 */
fun presetAllows(toolName: String): Boolean = PresetRuntime.current?.allows(toolName) ?: true
