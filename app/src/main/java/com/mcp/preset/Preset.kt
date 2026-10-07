package com.mcp.preset

import kotlinx.serialization.Serializable

/**
 * 一个 per-session Agent 预设（借鉴 deepseek-harness 的 Profile/Preset）。
 *
 * 一个预设把「系统提示词 + 工具白名单 + 拒绝工具」打包成可外部配置的组合单元，
 * 让「极简模式」这类场景化 Agent 不必硬编码进 `AgentType` 枚举，而是 yaml/json 声明。
 *
 * 对应 ROADMAP #13（Preset：per-session agent 组合）。
 *
 * @param id                   唯一标识（如 `minimal`、`full`、`ptc`）。
 * @param name                 展示名（如 `极简模式`）。
 * @param description          一句话说明，展示给用户与 LLM。
 * @param systemPromptOverride 可选的覆盖系统提示词；null 表示沿用默认。
 * @param allowedTools         工具白名单；**非空即视为限制**（空 = 全部工具可用，同 ORCHESTRATOR）。
 *                           注意：在 PTC 预设下，这里列出的是「程序内可见工具」，模型**直接**可见的
 *                           只有 `run_code`（呈现折叠）。
 * @param denyTools            额外拒绝的工具（即使在白名单或全量模式下也禁用）。
 * @param ptc                  PTC 呈现模式：模型只直接调用 `run_code`，其余工具经程序内 `tools.xxx()` 调用。
 *                           同时触发提示词里的 `tools:sdk` 段（生成白名单工具的签名）。
 * @param complete             `complete:true` —— 不追加 identity（Agent.md）、待办、操作记录，
 *                           只用 [systemPromptOverride] 作为人设/流程部分。对应 dsh persona 的
 *                           `complete: true`（`includeRuntimeContext:false` 的同义呈现）。
 *                           **技能清单与远程 MCP 清单不受本开关控制**——它们属于「当前可用能力」，
 *                           判据是 [isRestricted] / `ptc`：受限预设的技能工具未注册，注入即自相矛盾；
 *                           PTC 预设的技能可经 run_code 调用，必须注入。
 *                           协议指南同样不受本开关控制：模型必须知道工具的调用格式。
 * @param compaction           `compaction:false` —— 关闭**按压力阈值触发**的常规上下文压缩
 *                           （极简/长会话避免丢状态）。
 *                           注意：provider 已报窗口溢出时的**溢出恢复**不受本开关影响——
 *                           那是逃出溢出的最后手段，关掉会让长会话直接失败。
 * @param protocolStyle        工具调用协议风格：`line`（默认，行式）/ `xml`（DSML 标签）。
 *                           解析层两套都认，本字段只决定「教模型用哪套」。
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPromptOverride: String? = null,
    val allowedTools: List<String> = emptyList(),
    val denyTools: List<String> = emptyList(),
    val ptc: Boolean = false,
    val complete: Boolean = false,
    val compaction: Boolean = true,
    val protocolStyle: String = "line"
) {
    /** 是否使用 XML 工具调用协议（对应 [com.mcp.core.prompt.PresetPromptOverride.ProtocolStyle.XML]）。 */
    val xmlProtocol: Boolean get() = protocolStyle.equals("xml", ignoreCase = true)

    /** 是否对工具集合做白名单限制（allowedTools 非空即限制）。 */
    val isRestricted: Boolean get() = allowedTools.isNotEmpty()

    /**
     * 判断某个工具是否在该预设允许范围内（用于 toolbox 注册与 PTC 子调用 gate）。
     * - 白名单非空：必须在 [allowedTools] 内；
     * - 白名单为空：除 [denyTools] 外全部允许。
     */
    fun allows(toolName: String): Boolean =
        if (isRestricted) toolName in allowedTools else toolName !in denyTools
}
