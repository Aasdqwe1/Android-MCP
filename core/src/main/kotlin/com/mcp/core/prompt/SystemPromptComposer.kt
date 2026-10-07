package com.mcp.core.prompt

import android.content.Context
import com.mcp.core.skill.SkillPreferences
import com.mcp.core.skill.SkillRepository
import com.mcp.toolbox.ToolCompiler
import com.mcp.toolbox.Toolbox

/**
 * 系统提示词与工具协议的组合器。
 *
 * 提示词属于会话编排层，不属于 WebView、LLM HTTP 客户端或设置页面。
 * 该类统一 DeepSeek 行式协议、OpenAI 原生工具调用和渠道接入的注入规则。
 */
class SystemPromptComposer(
    private val context: Context,
    private val skillRepository: SkillRepository
) {
    /**
     * 远程 MCP 上下文提供者（由 app 层注入，避免 core 依赖 app）。
     *
     * 返回一段 Markdown，描述「有哪些远程 MCP 服务、各自暴露什么工具、端点在哪、怎么调用」；
     * 无远程服务时返回空串。设置方见 ChatBridge / PresetRuntime 的接线处。
     */
    var mcpContextProvider: (() -> String)? = null
    fun preload() {
        // 预热由调用方放到自己的 IO scope 中执行；这里保持同步 API，便于测试和渠道复用。
        systemPrompt()
    }

    fun composeDeepSeek(
        toolbox: Toolbox,
        userText: String,
        workspaceDir: String? = null,
        ptc: PtcRequestContext? = null
    ): String {
        if (toolbox.all().isEmpty()) return userText
        val systemContent = buildDeepSeekSystemContent(workspaceDir, ptc != null)
        return buildString {
            append(systemContent)
            // PTC 呈现折叠：模型直接可见工具只剩 run_code，其余工具签名走 tools:sdk 段。
            // 段序是契约，由 ptcProtocolSections 统一给出：硬约束 → 协议指南 → 程序内工具 SDK。
            // 注意：这里**不能**再注入通用的 DEEPSEEK_PROTOCOL_GUIDE——它的示例是
            // `tool_call: read_file/write_file` 等直调，在 PTC 下被 PTC_ONLY_RULE 禁止，
            // 会造成「示例教 A、约束禁 A」的自相矛盾。PTC 专用指南见 PTC_PROTOCOL_GUIDE。
            if (ptc != null) {
                append("\n\n---\n\n")
                append(ptcProtocolSections(ptc).joinToString("\n\n---\n\n"))
            } else {
                append("\n\n---\n\n")
                // 协议指南按预设风格选择：极简模式教 XML，其余教行式（默认，行为不变）。
                // 解析层两套都认，故这只是「引导模型用哪套」，不是互斥开关。
                val isXml = PresetPromptOverride.protocolStyle == PresetPromptOverride.ProtocolStyle.XML
                val guide = if (isXml) xmlProtocolGuide(toolbox.names()) else lineProtocolGuide(toolbox.names())
                append(guide)
                append("\n\n---\n\n")
                // 工具清单的值格式提示必须与上面的协议指南一致：
                // 指南教 XML 却把参数表写成「冒号后写到行尾」，会让模型收到两套冲突指令。
                append(
                    ToolCompiler.toProtocolTools(
                        toolbox.all(),
                        if (isXml) com.mcp.toolbox.ToolProtocolStyle.XML
                        else com.mcp.toolbox.ToolProtocolStyle.LINE,
                    )
                )
            }
            append("\n\n---\n\n")
            append(userText)
        }
    }

    /** OpenAI 后端通过 tools 参数传递工具定义，这里只组合 system message。 */
    fun composeOpenAiSystemContent(
        todoContext: String? = null,
        operationHistory: String = "",
        workspaceDir: String? = null,
        ptc: PtcRequestContext? = null
    ): String {
        // 技能清单：仅在技能真的可调时注入（受限且非 PTC 时跳过，理由见 DeepSeek 路径同名段落）。
        val skillDesc = if (!PresetPromptOverride.restricted || ptc != null)
            enabledSkillDescription() else ""
        val base = buildString {
            renderSystemPrompt(workspaceDir)?.let {
                append(it)
                append("\n\n---\n\n")
            }
            if (!PresetPromptOverride.complete) {
                agentSoul()?.let {
                    append(it)
                    append("\n\n---\n\n")
                }
            }
            if (skillDesc.isNotEmpty()) {
                append("## 已启用的知识技能\n")
                append("你可以通过 /skill <名称> 命令加载完整技能文档。当前已启用的技能:\n")
                append(skillDesc)
            }
            // 远程 MCP 服务清单（见 DeepSeek 路径同名段落）
            run {
                val mcpDesc = mcpContextProvider?.invoke().orEmpty()
                if (mcpDesc.isNotBlank()) {
                    append("\n\n---\n\n")
                    append(mcpDesc)
                }
            }
            // PTC 呈现折叠：模型直接可见工具只剩 run_code，其余工具签名走 tools:sdk 段。
            if (ptc != null) {
                // OpenAI 走**原生 function calling 版**的 PTC 段：硬约束（原生措辞）+ 程序内 SDK，
                // **不含行式协议指南**。行式指南教「手写 tool_call: run_code」，而本后端工具定义走
                // `tools` 参数、模型应按原生 `tool_calls` 调用——同时注入会自相矛盾，实测会让模型
                // 偶发手写行式文本（调用不被识别）。DeepSeek 路径才用带 guide 的 ptcProtocolSections。
                append("\n\n---\n\n")
                append(ptcProtocolSectionsForOpenAI(ptc).joinToString("\n\n---\n\n"))
            }
        }
        val withTodo = if (!PresetPromptOverride.complete && !todoContext.isNullOrBlank()
            && todoContext != "当前没有任何待办任务。") {
            "$base\n\n---\n\n## 当前待办任务\n$todoContext"
        } else {
            base
        }
        return if (!PresetPromptOverride.complete && operationHistory.isNotBlank()) {
            "$withTodo\n\n---\n\n## 最近操作记录\n" +
                "以下是你最近完成的工具调用，供参考当前进度：\n$operationHistory"
        } else {
            withTodo
        }
    }

    private fun buildDeepSeekSystemContent(workspaceDir: String?, ptcActive: Boolean): String = buildString {
        renderSystemPrompt(workspaceDir)?.let { append(it) }
        if (!PresetPromptOverride.complete) {
            agentSoul()?.let { append("\n\n---\n\n").append(it) }
        }
        // 技能清单是「当前可用能力」的必要上下文，但**只在技能真的可调时才注入**：
        //  - 受限且非 PTC（极简模式）：技能工具未注册，注入等于骗模型能调 /skill，
        //    与工具清单自相矛盾 → 跳过；
        //  - PTC：技能作为「程序内可见工具」经 run_code 可调 → 仍注入；
        //  - 全量模式：技能工具已注册 → 注入。
        if (!PresetPromptOverride.restricted || ptcActive) {
            run {
                val skillDesc = enabledSkillDescription()
                if (skillDesc.isNotEmpty()) {
                    append("\n\n---\n\n## 已启用的知识技能\n")
                    append("你可以通过 /skill <名称> 命令加载完整技能文档。当前已启用的技能:\n")
                    append(skillDesc)
                }
            }
        }
        // 远程 MCP 服务清单：同技能清单，属于「当前可用能力」的必要上下文。
        // 不受 complete 屏蔽——但 provider 自身会在受限预设（如极简模式，白名单不含 remote_*）
        // 下返回空串，因此这类预设不会注入 MCP 清单。
        run {
            val mcpDesc = mcpContextProvider?.invoke().orEmpty()
            if (mcpDesc.isNotBlank()) {
                append("\n\n---\n\n")
                append(mcpDesc)
            }
        }
    }

    private fun enabledSkillDescription(): String = skillRepository.allSkills()
        .filter { SkillPreferences.isEnabled(context, it.name) }
        .joinToString("\n") { "  - /skill ${it.name}: ${it.description}" }

    private fun systemPrompt(): String? = cachedSystemPrompt ?: synchronized(CACHE_LOCK) {
        cachedSystemPrompt ?: runCatching {
            context.assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
        }.getOrNull()?.also { cachedSystemPrompt = it }
    }

    /**
     * 读取 system_prompt 模板并把 `{{WORKSPACE_DIR}}` 替换为当前会话的工作目录。
     *
     * 若当前预设提供了 [PresetPromptOverride]，则**整体替换**默认模板——极简模式的
     * 意义正是「短提示词 + 极少工具」，把通用长提示词（含全部工具使用说明）继续
     * 注入会抵消掉这个收益。
     */
    private fun renderSystemPrompt(workspaceDir: String?): String? {
        val value = workspaceDir?.takeIf { it.isNotBlank() }
            ?: "（当前未指定具体工作目录，写入文件时请使用应用内部可写路径）"
        val override = PresetPromptOverride.current
        if (!override.isNullOrBlank()) return override.replace("{{WORKSPACE_DIR}}", value)
        val template = systemPrompt() ?: return null
        return template.replace("{{WORKSPACE_DIR}}", value)
    }

    private fun agentSoul(): String? = cachedAgentSoul ?: synchronized(CACHE_LOCK) {
        cachedAgentSoul ?: runCatching {
            context.assets.open("Agent.md").bufferedReader().use { it.readText() }
        }.getOrNull()?.also { cachedAgentSoul = it }
    }

    companion object {
        private val CACHE_LOCK = Any()
        @Volatile private var cachedSystemPrompt: String? = null
        @Volatile private var cachedAgentSoul: String? = null

        /** 给微信等非 ChatBridge 调用方提供同一套首轮提示词协议。 */
        fun buildInjectedPrompt(context: Context, toolbox: Toolbox, userText: String): String {
            if (toolbox.all().isEmpty()) return userText
            val repository = SkillRepository(context.applicationContext).also { it.discover() }
            return SystemPromptComposer(context.applicationContext, repository)
                .composeDeepSeek(toolbox, userText)
        }

        /**
         * XML 协议指南（极简模式专用）。
         *
         * 形态：invoke/parameter 的 DSML 标签（竖线为全角 ｜ U+FF5C）。
         * 与行式指南同样要求：演示必须带 // 前缀、正式调用不可用围栏包裹。
         */
        internal fun xmlProtocolGuide(toolNames: List<String>): String = """
    
    ## 工具调用格式（XML 协议）
    
    当需要调用工具时，用下面的 XML 格式输出。直接顶格写，不要用 ``` 代码围栏包裹（围栏内的内容一律视为示例，系统不会执行）。
    
    ### 格式（照这个结构输出）
    
    <｜｜DSML｜｜ invoke name="工具名">
    <｜｜DSML｜｜ parameter name="id" string="true">本轮唯一标识</｜｜DSML｜｜ parameter>
    <｜｜DSML｜｜ parameter name="参数名1" string="true">值1</｜｜DSML｜｜ parameter>
    <｜｜DSML｜｜ parameter name="参数名2">值2</｜｜DSML｜｜ parameter>
    </｜｜DSML｜｜ invoke>
    
    （正式调用就是这个样子，行首不加任何前缀。）
    
    ### 字段含义
    
    - invoke 的 name：（必填）工具名，必须严格等于下方「工具清单」里列出的名字（如 ${toolNames.take(3).joinToString("、")}）。名字写错系统会报 unknown tool 并不执行。
    - parameter 的 name：参数名，要与工具清单一致。
    - parameter 的 string="true"：声明该参数按字符串处理（推荐对 path、content、old_string 等一律写上）。省略时系统按标量推断（true/false/数字会当成对应类型）。
    - id 参数：本轮调用的唯一标识，如 call_1、call_2。并行调用或依赖链中的多次调用请用不同的 id；系统会把工具结果按 id 关联回你的调用。省略时系统自动生成。
    
    ### 值的写法
    
    标签之间的内容就是参数值，原样保留（含引号、反斜杠、冒号、空格、换行），无需任何转义，也不需要定界块。
    
    例：
    
        <｜｜DSML｜｜ invoke name="read_file">
        <｜｜DSML｜｜ parameter name="id" string="true">call_1</｜｜DSML｜｜ parameter>
        <｜｜DSML｜｜ parameter name="path" string="true">/sdcard/Main.kt</｜｜DSML｜｜ parameter>
        <｜｜DSML｜｜ parameter name="start_line">10</｜｜DSML｜｜ parameter>
        </｜｜DSML｜｜ invoke>
    
    多行内容直接写在标签之间即可（不需要定界块）：
    
        <｜｜DSML｜｜ invoke name="write_file">
        <｜｜DSML｜｜ parameter name="id" string="true">call_2</｜｜DSML｜｜ parameter>
        <｜｜DSML｜｜ parameter name="path" string="true">/sdcard/hello.txt</｜｜DSML｜｜ parameter>
        <｜｜DSML｜｜ parameter name="content" string="true">第一行
        第二行（含 "引号" 与 \ 反斜杠，原样保留）</｜｜DSML｜｜ parameter>
        </｜｜DSML｜｜ invoke>
    
    ### 调用纪律
    
    - 在「决定调用工具」的那一轮，只输出工具调用，不要同时输出解释或结论；解释和最终回复留到拿到工具结果之后。
    - 互不依赖的多个工具可以并行：连续写多个 invoke 段（每个各自完整闭合），系统会一次性全部执行，并在回传结果时按 id 对应。
    - 存在依赖时必须串行：先调用 A，等系统返回结果后，再决定并调用下一个依赖 A 结果的工具。
    - 每个 invoke 段必须完整闭合，parameter 段也要闭合——漏写闭合会让该段解析失败。
    - 任务全部完成、不再需要工具时，直接输出自然语言最终回复，不要再输出工具调用。
    
    ### 工具结果格式
    
    工具执行完毕后，系统会把结果按**同样的 XML 格式**回传给你：
    
        <｜｜DSML｜｜ result name="call_1">工具返回的原始内容，可能是 JSON 文本或纯文本</｜｜DSML｜｜ result>
    
    - result 的 name 与你的工具调用 id 一致，据此把结果关联到具体调用。
    - 标签之间是工具返回的原文，原样透传，你按内容继续推理。
    - 并行调用了多个工具时，会连续回传多个 result 段，顺序与你的调用顺序一致，按 name 认领结果。
    - 工具执行失败时原文形如 {"error":"..."}，请据此修正参数重试、换用其它工具，或如实告知用户，不要假装成功。
    - 拿到结果后可以继续输出新的工具调用（多轮），直到不再需要工具时，才整合为自然语言最终回复。
    
    ### 演示 / 示例约定
    
    如果你需要向用户解释或演示工具调用的格式（不是真的调用），每行前加 // 前缀——带 // 前缀的行系统不会执行，仅作说明。
    
        // <｜｜DSML｜｜ invoke name="read_file">
        // <｜｜DSML｜｜ parameter name="path">/x.kt</｜｜DSML｜｜ parameter>
        // </｜｜DSML｜｜ invoke>
    
    正式调用时不要加 // 前缀。
    
    """.trimIndent()

    /** 行式协议指南（internal 供单测校验「示例必须带 //」）。 */
        internal fun lineProtocolGuide(toolNames: List<String>): String = """
    
    ## 工具调用格式（行式协议）
    
    **硬规则：禁止使用 `<｜｜DSML｜｜ calls>` 及任何 `<｜｜DSML｜｜ invoke>` / `<｜｜DSML｜｜ parameter>` 标签。**
    本会话按行式协议输出工具调用，输出 DSML/XML 标签会被解析器当作普通文本，调用不会执行。
    
    当需要调用工具时，用下面的纯文本「行式」格式输出——不要手写 JSON，把工具名和参数按行写出来即可。直接顶格写，不要用 ``` 代码围栏包裹（围栏内的内容一律视为示例，系统不会执行）。
    
    ### 格式（照这个结构输出）
    
    tool_call: 工具名
    id: 本轮唯一标识
    参数名1: 值1
    参数名2: 值2
    多行参数名 <<<
    多行内容第一行
    多行内容第二行
    >>>
    
    （正式调用就是这个样子，行首不加任何前缀。）
    
    ### 字段含义
    
    - tool_call:（必填）工具名，必须严格等于下方「工具清单」里列出的名字（如 ${toolNames.take(3).joinToString("、")}）。名字写错系统会报 unknown tool 并不执行。
    - id:（推荐）本轮调用的唯一标识，如 call_1、call_2。并行调用或依赖链中的多次调用请用不同的 id；系统会把工具结果按 id 关联回你的调用。省略时由系统自动生成。
    - 其余每行是「参数名: 值」：参数名要与工具清单一致；值从冒号后一直写到行尾，直接写原文，不需要加引号，引号、反斜杠、冒号都无需转义。
    
    ### 单行参数
    
    大多数参数都是单行参数（如 path、mode、block、start_line、old_hash、id 等）。写法就是「参数名: 值」，值直接跟在冒号后、写到本行行尾为止，一行只写一个参数。
    
    例：
    
        tool_call: read_file
        id: call_1
        path: /sdcard/Main.kt
        start_line: 10
    
    要点：
    - 值从冒号后的第一个非空白字符开始，到本行行尾结束；不要加引号，引号会被当成值的一部分。
    - 值里可以原样包含冒号、引号、反斜杠、空格（例如 path: /sdcard/a:b.txt 或 note: he said "hi"），无需转义，也不要用定界块包裹。
    - 一个参数只能占一行；如果内容被迫换行了，说明它其实是多行参数，请用下方的定界块写法。
    
    ### 多行参数（必须用定界块）
    
    以下参数的内容通常包含换行，必须用定界块包裹，否则内容会被截断或产生解析歧义：
    - write_file 的 content（要写入文件的完整内容）
    - edit_file 的 old_string / new_string / new_content
    - multi_edit 的 edits 中每项的 old_string / new_string（行式写法用 edit_1_old / edit_1_new / edit_2_old / edit_2_new …）
    - run_bash 的 script
    - run_code 的 code（要执行的程序体）
    - 任何明显会是多行文本的参数
    
    写法：参数名后加 <<< 单独一行开始，>>> 单独一行结束，中间每一行原样保留（含空行），不做任何解析或转义，也不要用引号包裹定界块内容。
    定界块硬性规则（务必遵守，违反会解析失败或参数被吞）：
    - <<< 与 >>> 必须成对出现：有 <<< 就必须有与之配对的 >>>；未闭合的块会把后续所有行都当成块内容吞掉。
    - >>> 必须单独占一行、顶格书写（行首不能有空格或缩进），且本行除 >>> 外不能有任何其它字符。
    - 一个定界块只对应一对 <<< >>>；>>> 一到即闭合，不要再多写一个 >>>。
    - 块闭合（>>>）之后，可以继续写该调用剩余的（单行）参数，例如 run_code 的 description、language、timeout_seconds；参数行顺序不限，习惯上把最长的块放在前面。
    
    例：
    
        tool_call: write_file
        id: call_2
        path: /sdcard/hello.txt
        content <<<
        第一行
        第二行（含 "引号" 与 \ 反斜杠，原样保留）
        >>>
    
    
    例（run_code，多行块闭合并接单行参数）：
        tool_call: run_code
        id: call_3
        code <<<
        var r = tools.read_file({ path: "/x.txt" });
        return r;
        >>>
        description: 读取文件并返回内容
        language: javascript
        timeout_seconds: 60

    ### 调用纪律
    
    - 在「决定调用工具」的那一轮，只输出工具调用，不要同时输出解释或结论；解释和最终回复留到拿到工具结果之后。
    - 互不依赖的多个工具可以并行：连续写多个 tool_call: 段，系统会一次性全部执行，并在回传结果时按 id 对应。
    - 存在依赖时必须串行：先调用 A，等系统返回结果（tool_result）后，再决定并调用下一个依赖 A 结果的工具。
    - 任务全部完成、不再需要工具时，直接输出自然语言最终回复，不要再输出 tool_call:。
    
    ### 工具结果格式
    
    工具执行完毕后，系统会把结果按下面的行式格式回传给你：
    
        tool_result: call_1 <<<
        <工具返回的原始内容，可能是 JSON 文本或纯文本>
        >>>
    
    - tool_result: 后面的 id 与你的工具调用 id 一致，据此把结果关联到具体调用。
    - <<< 与 >>> 之间是工具返回的原文，原样透传，你按内容继续推理。
    - 并行调用了多个工具时，会连续回传多个 tool_result 块，顺序与你的调用顺序一致，按 id 认领结果。
    - 工具执行失败时原文形如 {"error":"..."}，请据此修正参数重试、换用其它工具，或如实告知用户，不要假装成功。
    - 拿到结果后可以继续输出新的工具调用（多轮），直到不再需要工具时，才整合为自然语言最终回复。
    
    ### 演示 / 示例约定
    
    如果你需要向用户解释或演示工具调用的格式（不是真的调用），每行前加 // 前缀——带 // 前缀的行系统不会执行，仅作说明。
    
        // tool_call: read_file
        // path: /x.kt
    
    正式调用时不要加 // 前缀。
    
    """.trimIndent()
    }
}
