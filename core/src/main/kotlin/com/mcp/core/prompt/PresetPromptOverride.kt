package com.mcp.core.prompt

/**
 * 当前会话预设的系统提示词覆盖（极简 / PTC 等）。
 *
 * 放在 core 而不是 app：`SystemPromptComposer` 在 core，渠道接入（微信等）也复用它，
 * 覆盖必须对**所有**入口一致生效。字段说明：
 *
 *  - [current]：覆盖文本；null 即恢复默认长提示词（[com.mcp.core.prompt.SystemPromptComposer]
 *    会用 `system_prompt.txt` 模板渲染）。
 *  - [complete]：`true` 表示「完整替换」——只渲染 [current]，不追加 identity、技能描述、
 *    待办、操作记录、行式协议指南（对应 dsh persona 的 `complete: true`）。
 *  - [protocolStyle]：工具调用的协议风格。默认 LINE（行式，现有全部行为不变）；
 *    XML 供极简模式使用——它的提示词只教 XML 调用格式，解析层两套都认。
 *  - [restricted]：当前预设是否对工具集做白名单限制（allowedTools 非空）。
 *    受限预设（极简模式）的技能工具**未被注册**，因此提示词里也不能注入技能清单，
 *    否则模型会以为能调 /skill 而实际工具不存在（上下文自相矛盾）。
 *    PTC 模式虽受限，但技能作为「程序内可见工具」经 run_code 可调，故仍注入——
 *    判据由调用方结合 ptc 上下文给出（见 [SystemPromptComposer]）。
 *  - PTC 呈现折叠不再放这里：它按会话预设派生（[PtcRequestContext]），由调用方在组包时传入。
 *
 * 均由 app 侧 `com.mcp.preset.PresetRuntime.applyPromptOverride` 同步写入。
 */
object PresetPromptOverride {

    /** 工具调用协议风格。 */
    enum class ProtocolStyle { LINE, XML }

    @Volatile var current: String? = null
        private set
    @Volatile var complete: Boolean = false
        private set
    @Volatile var protocolStyle: ProtocolStyle = ProtocolStyle.LINE
        private set
    @Volatile var restricted: Boolean = false
        private set

    fun set(
        current: String?,
        complete: Boolean,
        protocolStyle: ProtocolStyle = ProtocolStyle.LINE,
        restricted: Boolean = false,
    ) {
        this.current = current
        this.complete = complete
        this.protocolStyle = protocolStyle
        this.restricted = restricted
    }

    fun reset() = set(null, false)
}
