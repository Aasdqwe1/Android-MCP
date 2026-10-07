package com.mcp.core.prompt

import com.mcp.toolbox.ParamType
import com.mcp.toolbox.ToolDef

/** PTC 入口工具名（模型唯一可直接调用的工具；与 app 侧 RUN_CODE_TOOL 同值）。 */
const val PTC_ENTRY_TOOL = "run_code"

/**
 * PTC 呈现折叠的硬规则（与 dispatch 层的 [modelDirectToolAllowed] 判定一致）：
 * 模型**只能直接**调用 `run_code`，其余工具一律经程序内 `tools.xxx()` 调用。
 */
/** PTC 行式规则的硬约束段。 */
val PTC_ONLY_RULE = """
    ## 调用约束（硬规则）

    严格两层分离：你**直接**输出的行式调用只能有一个工具名——`run_code`；
    其余工具（read_file / write_file / run_bash 等）一律在 run_code 程序内用 `tools.工具名({...})` 调用。
    直接写其它工具名的行式调用会被拒绝执行。展开说明见下文「工具调用协议」。

""".trimIndent()

/**
 * PTC 硬约束段——**OpenAI 原生 function calling 版**。
 *
 * 与行式版 [PTC_ONLY_RULE] 同义（都约束「只能直调 run_code」），但措辞对齐 OpenAI 的
 * 原生 function calling：工具通过 `tool_calls` 调用，**不是**手写 `tool_call:` 文本。
 *
 * 为什么必须单独一份：OpenAI 后端工具定义走 `tools` 参数，若 system 里仍注入行式
 * 「`tool_call: run_code` + `code <<<>>>`」指南，模型会收到两套冲突指令——
 * 原生 tool_calls 与手写行式文本，行为不可预测（实测会偶发手写行式导致调用不被识别）。
 */
val PTC_ONLY_RULE_FUNCTION_CALLING = """
    ## 调用约束（硬规则）

    严格两层分离：你**通过原生 function calling（`tool_calls`）直接调用的工具**只能有一个——`run_code`；
    其余工具（read_file / write_file / run_bash 等）一律在 run_code 程序内用 `tools.工具名({...})` 调用。
    直接对其它工具发起 function call 会被拒绝执行。程序内工具签名见下文「程序内工具 SDK」段。

    例外：`agent_complete` 与 `agent_progress` 是流程控制信号，允许**直接**调用（不要包进 run_code）。

""".trimIndent()

/** PTC XML 规则的硬约束段（与行式版同义，只是措辞对齐 XML 语法）。 */
val PTC_ONLY_RULE_XML = """
    ## 调用约束（硬规则）

    严格两层分离：你**直接**输出的 XML invoke 段只能有一个工具名——`run_code`；
    其余工具（read_file / write_file / run_bash 等）一律在 run_code 程序内用 `tools.工具名({...})` 调用。
    直接写其它工具名的 invoke 会被拒绝执行。展开说明见下文「工具调用协议」。

    例外：`agent_complete` 与 `agent_progress` 是流程控制信号，允许**直接**调用（不要包进 run_code）。

""".trimIndent()

/**
 * PTC 模式的**专用**行式协议指南：只描述「如何直接调用 run_code」。
 *
 * 与通用 [SystemPromptComposer.DEEPSEEK_PROTOCOL_GUIDE] 的关键区别：后者示例里出现
 * `tool_call: read_file/write_file` 等直调——在 PTC 下这些都被 [PTC_ONLY_RULE] 禁止，
 * 若继续注入会造成「示例教 A、约束禁 A」的自相矛盾（模型照抄后失败）。
 * 因此 PTC 分支必须改用本指南：行式协议只讲 run_code，其余工具一律指向程序内 SDK。
 */
val PTC_PROTOCOL_GUIDE = """
    ## 工具调用协议（一层入口 + 程序内 SDK）

    **硬规则：禁止使用 `<｜｜DSML｜｜ calls>` 及任何 `<｜｜DSML｜｜ invoke>` / `<｜｜DSML｜｜ parameter>` 标签。**
    本会话按行式协议输出工具调用；直接调用 run_code 时用下面的 `tool_call:` 行式格式，输出 DSML/XML 标签会被解析器当作普通文本，调用不会执行。

    ### 直接调用 run_code

    直接调用 `run_code` 的协议字段与工具参数见下。格式如下，顶格写、不加 `//`、
    不要用代码围栏包裹：

    tool_call: run_code
    id: call_1
    code <<<
    <程序体>
    >>>
    description: <5-10 词概述本程序做什么>
    language: javascript

    协议字段与工具参数：
    - `tool_call`：固定为 `run_code`。
    - `id`：必填，本次调用唯一标识（如 `call_1`）；并行发起多个 run_code 时不得重复，结果按 id 回传。
    - `code`：必填，多行程序体，必须用 `<<<` / `>>>` 定界块包裹；块内原样保留、不转义。
    - `description`：必填，5-10 词概述本程序做什么。
    - `language`：可选，取值 `javascript`（默认，in-JVM 同步执行，**不支持 await / Promise**）/
      `python` / `node`（后两者走 PRoot 解释器，较慢，此时才需要 await）。
      三者语法与错误类型不同，省略即为 javascript；不要写其它值，未知值会直接报错。
    - `timeout_seconds`：可选，整段程序的执行超时秒数，默认 300、上限 3600；不写即用默认值。

    定界块硬性规则（违反会解析失败）：
    - `<<<` 与 `>>>` 必须成对；`>>>` 必须单独顶格一行，本行除 `>>>` 外不能有任何其它字符。
    - 一个定界块只对应一对 `<<<` / `>>>`：`>>>` 一到即闭合，不要再多写一个。
    - **一条调用里，`>>>` 有且仅有一次。** 块已闭合、参数也写完，就到此为止。
    - 块闭合后可继续写该调用剩余的单行参数（description / language / timeout_seconds），顺序不限。
    - 每个参数只写一次，不要重复输出同一参数。
    - 程序体内**不要出现单独一行的 `>>>`**（会被当成块结束）：需要输出该字面量时用
      字符串拼接（如 ">" + ">>"）构造；同理，代码注释里也不要落单写这一行。

    ⚠️ **最常见的失败形态（务必避免）**：块已闭合、参数已写完，尾部又「顺手」补了一个 `>>>`，
    甚至把 description / language 重抄一遍——这是把「结束」写了两遍，解析器会判为非法并拒绝整条调用。

    错误示范（尾部多了一个 `>>>`，参数也重复）：

        tool_call: run_code
        id: call_9
        code <<<
        return 1;
        >>>
        description: 示例
        language: javascript
        >>>
        description: 示例
        language: javascript

    正确示范（注意：`>>>` 只出现一次，每个参数只出现一次）：

        tool_call: run_code
        id: call_9
        code <<<
        return 1;
        >>>
        description: 示例
        language: javascript

    其余工具**不能**这样直调：全部在 run_code 程序内用 `tools.工具名({ 参数: 值 })` 调用，
    签名见紧接着的「程序内工具 SDK」段。程序内**禁止**再调用 `run_code`（防嵌套失控）。


    ### 结果与错误

    - 结果以 `tool_result: <id> <<< ... >>>` 回传，按 id 关联；并行调用按发出顺序回传。
    - 工具失败返回 `{"error":"..."}`；程序内子调用失败抛 `ToolCallError`（含 `toolName`），可 try/catch。
    - 失败不要假装成功：修正参数重试、换工具，或如实告知用户。
    - 需要向用户提问时用程序内 `tools.ask_user(...)`；等待回答的时长会从本段程序的超时预算里扣除，
      其余子调用的耗时同样计入，请为大任务留足 `timeout_seconds`。

    ### 演示约定

    向用户解释格式（非真实调用）时，每行前加 `//` 前缀；正式调用不要加 `//`。

""".trimIndent()


/** PTC XML 协议指南：run_code 入口的 DSML invoke 语法 + 程序内 SDK 指路。 */
val PTC_PROTOCOL_GUIDE_XML = """
    ## 工具调用协议（一层入口 + 程序内 SDK）

    ### 直接调用 run_code

    直接调用 run_code：用 DSML invoke 段，顶格写、不加 //、不要用代码围栏包裹。

    格式（照这个结构输出，标签前缀 <｜｜DSML｜｜ 与结尾 ｜｜> 必须完整，竖线为全角 ｜）：

    <｜｜DSML｜｜ invoke name="run_code">
    <｜｜DSML｜｜ parameter name="id" string="true">call_1</｜｜DSML｜｜ parameter>
    <｜｜DSML｜｜ parameter name="code" string="true"><程序体></｜｜DSML｜｜ parameter>
    <｜｜DSML｜｜ parameter name="description" string="true"><5-10 词概述本程序做什么></｜｜DSML｜｜ parameter>
    <｜｜DSML｜｜ parameter name="language" string="true">javascript</｜｜DSML｜｜ parameter>
    </｜｜DSML｜｜ invoke>

    （正式调用就是这个样子，行首不加任何前缀、不要包代码围栏。）

    invoke 段（name 固定为 run_code）包含以下 parameter 子标签：
    - parameter name="id" string="true" —— 本轮唯一标识，如 call_1
    - parameter name="code" string="true" —— 程序体（多行直接写在标签之间）
    - parameter name="description" string="true" —— 5-10 词概述本程序做什么
    - parameter name="language" string="true" —— javascript / python / node（可选）

    字段含义：
    - invoke 的 name：固定为 run_code。
    - id：必填，本次调用唯一标识（如 call_1）；并行发起多个 run_code 时不得重复，结果按 id 回传。
    - code：必填，多行程序体，直接写在标签之间（无需定界块）；标签之间的内容原样保留（含引号、反斜杠、换行），不做任何转义。
    - description：必填，5-10 词概述本程序做什么。
    - language：可选，取值 javascript（默认，in-JVM 同步执行，不支持 await / Promise）/ python / node（后两者走 PRoot 解释器，较慢，此时才需要 await）。三者语法与错误类型不同，省略即为 javascript；不要写其它值，未知值会直接报错。
    - timeout_seconds：可选，整段程序的执行超时秒数，默认 300、上限 3600；不写即用默认值。

    标签书写硬性规则（违反会解析失败）：
    - 每个 invoke 段必须完整闭合：结束标签单独一行，不要漏写。
    - parameter 段同样必须闭合。
    - 每个参数只写一次，不要重复输出同一参数。
    - 程序体内不要出现结束标签的字面量（会被当成标签结束）；需要输出时用字符串拼接构造，注释里也不要落单写这类行。

    其余工具不能这样直调：全部在 run_code 程序内用 tools.工具名({ 参数: 值 }) 调用，签名见紧接着的「程序内工具 SDK」段。程序内禁止再调用 run_code（防嵌套失控）。

    ### 流程控制工具（例外，必须直调）

    `agent_complete` / `agent_progress` 是工作流控制信号，**必须直接调用**、不要包进 run_code：
    - `agent_complete`：任务完成时直接调用，传入 result（最终结果的结构化文本，必须自包含），
      用 DSML invoke 段调用（name="agent_complete"，parameter name="result"），结束后工作流立即终止。
    - `agent_progress`：长任务中直接调用上报进度（message 必填，progress 可选）。

    注意：完成任务后必须直接调 agent_complete；仅输出文字不会被当作完成。

    ### 结果与错误

    - 结果以 result 段回传（带 name 属性，与调用 id 一致），按 name 关联；并行调用按发出顺序回传。
    - 工具失败返回 error JSON；程序内子调用失败抛 ToolCallError（含 toolName），可 try/catch。
    - 失败不要假装成功：修正参数重试、换工具，或如实告知用户。
    - 需要向用户提问时用程序内 tools.ask_user(...)；等待回答的时长会从本段程序的超时预算里扣除，其余子调用的耗时同样计入，请为大任务留足 timeout_seconds。

    ### 演示约定

    向用户解释格式（非真实调用）时，每行前加 // 前缀；正式调用不要加 //。

""".trimIndent()
/** 程序内 SDK 的语言：决定签名渲染风格，必须与 run_code 的 language 默认值一致。 */
enum class PtcSdkLanguage { JAVASCRIPT, PYTHON }

/**
 * 一次请求的 PTC 呈现上下文（按**会话预设**派生，不再是进程级可变状态）。
 *
 * 之前用全局 `PtcPresentation.active/sdkSection`，手机桥与 WebApiServer 桥同进程共存、
 * 会话各自 presetId 不同（PTC / 普通混用）时，切会话会翻转全局状态，导致正在构造的请求
 * 按错误模式组包或误拒工具。现在由调用方在组包时按 sid 的 preset 计算后传入。
 */
/**
 * PTC 协议的呈现风格。
 *
 * PTC 把「模型直接可见」收敛为单一入口 run_code，但入口本身的**调用语法**
 * 仍有两套：行式（DeepSeek 默认）与 XML（极简模式）。两条路都要能教模型。
 */
enum class PtcProtocolStyle { LINE, XML }

data class PtcRequestContext(
    val sdkSection: String,
    val rule: String,
    val guide: String,
)

/** 按 preset 的 ptc 标记派生请求上下文；非 PTC 返回 null。 */
fun ptcRequestContext(
    tools: List<ToolDef>,
    ptc: Boolean,
    language: PtcSdkLanguage = PtcSdkLanguage.JAVASCRIPT,
    style: PtcProtocolStyle = PtcProtocolStyle.LINE,
): PtcRequestContext? = if (!ptc) null else PtcRequestContext(
    sdkSection = renderToolsSdk(tools, language),
    rule = if (style == PtcProtocolStyle.XML) PTC_ONLY_RULE_XML else PTC_ONLY_RULE,
    guide = if (style == PtcProtocolStyle.XML) PTC_PROTOCOL_GUIDE_XML else PTC_PROTOCOL_GUIDE,
)

/**
 * PTC 协议段的**固定顺序**：硬约束 → 协议指南 → 程序内工具 SDK。
 *
 * 顺序本身是契约：[PTC_PROTOCOL_GUIDE] 末尾把其余工具指向「紧接着的『程序内工具 SDK』段」，
 * 要求 SDK 段紧随其后。曾在两者之间插入 run_code 的行式工具清单
 * （`ToolCompiler.toLineProtocolTools`），一是把指路与目标隔开，二是把 run_code 的参数表
 * 第三次重复（指南字段表 + 程序内 SDK 段已各有一份），故移除。
 *
 * 组包方（[SystemPromptComposer]）与单测共用本函数，保证两个后端的段序一致。
 */
fun ptcProtocolSections(ctx: PtcRequestContext): List<String> =
    listOf(ctx.rule, ctx.guide, ctx.sdkSection)

/**
 * PTC 协议段——**OpenAI 原生 function calling 版**：只给「硬约束（原生措辞）+ 程序内 SDK」，
 * **不含行式协议指南**。
 *
 * 为什么去掉 guide：[PTC_PROTOCOL_GUIDE] 教的是「手写 `tool_call: run_code` + `code <<<>>>`」，
 * 而 OpenAI 后端工具定义走 `tools` 参数、模型应按原生 `tool_calls` 调用。二者同时注入会自相矛盾，
 * 导致模型偶发手写行式文本。原生 function calling 下，run_code 的调用方式由 `tools` 参数里的
 * JSON Schema 描述，无需文本指南。
 */
fun ptcProtocolSectionsForOpenAI(ctx: PtcRequestContext): List<String> =
    listOf(PTC_ONLY_RULE_FUNCTION_CALLING, ctx.sdkSection)

/**
 * 模型直接可见的工具集：PTC 下只暴露 [PTC_ENTRY_TOOL]，其余经程序内 `tools.xxx()`。
 *
 * @param includeAgentControls 是否把 [PTC_DIRECT_ALLOWED]（`agent_complete`/`agent_progress`）
 *   也暴露给模型。**只有子 Agent 上下文该传 true** —— 它们是子 Agent 的终止/进度协议。
 *   主会话/微信渠道传 false（默认）：那两个工具在主会话里语义无效（`currentTask` 为 null，
 *   调用只会拿到 error），暴露出来反而误导模型去调。
 */
fun modelFacingTools(
    tools: List<ToolDef>,
    ptc: Boolean,
    includeAgentControls: Boolean = false,
): List<ToolDef> = if (ptc) {
    tools.filter {
        it.name == PTC_ENTRY_TOOL ||
            (includeAgentControls && it.name in PTC_DIRECT_ALLOWED)
    }
} else tools

/**
 * PTC 折叠的**豁免工具**：这些工具即使 PTC 激活也允许模型直调。
 *
 * 它们是**流程控制信号**（结束子 Agent 工作流 / 上报进度），不是普通数据工具：
 *  - `agent_complete` 若被折叠进 run_code，子 Agent 的结束信号会走内层 dispatch，
 *    与「必须调用 agent_complete 才结束」的契约错位；
 *  - `agent_progress` 同理，进度上报被折叠会丢实时性。
 * 折叠它们会直接破坏子 Agent 的终止协议，故豁免。
 */
val PTC_DIRECT_ALLOWED: Set<String> = setOf("agent_complete", "agent_progress")

/** 模型直调门控（在 dispatch 层执行，兑现 [PTC_ONLY_RULE]）。 */
fun modelDirectToolAllowed(name: String, ptc: Boolean): Boolean =
    !ptc || name == PTC_ENTRY_TOOL || name in PTC_DIRECT_ALLOWED

/**
 * PTC 直调被拒时的错误文案（全链路共用：App 主链路 / 子 Agent / 微信 / 批量执行）。
 *
 * 措辞随当前协议走：XML 会话若回行式术语（「行式调用」），模型会按错误语法自愈，
 * 多绕一轮；给 DSML 指引才能让它下一轮直接改对。
 *
 * @param name 被拒的工具名（回显给模型，指明是哪一个）。
 * @param xml 当前会话是否 XML 协议。
 */
fun ptcDirectCallRejection(name: String, xml: Boolean): String =
    if (xml) "PTC 模式下只能直接调用 run_code：请改用 DSML invoke 段调用 run_code，" +
        "并在程序内用 tools.$name(...) 调用该工具。"
    else "PTC 模式下只能直接调用 run_code：请在 run_code 程序内用 tools.$name(...) 调用"

/**
 * [ptcDirectCallRejection] 的无会话重载：按**进程级**当前风格取措辞。
 *
 * 供拿不到 sessionId 的链路使用（子 Agent 的 tool dispatch、批量执行、微信渠道——
 * 它们各自持有独立会话上下文，不经过 ChatBridge 的会话态表）。
 * 这些链路本身就是「全局预设」语义，读全局值与它们的 ptc 判定（PresetRuntime.current.ptc）同源。
 */
fun ptcDirectCallRejection(name: String): String =
    ptcDirectCallRejection(name, PresetPromptOverride.protocolStyle == PresetPromptOverride.ProtocolStyle.XML)

/** 按语言渲染程序内 SDK 段。 */
fun renderToolsSdk(tools: List<ToolDef>, language: PtcSdkLanguage = PtcSdkLanguage.JAVASCRIPT): String =
    when (language) {
        PtcSdkLanguage.JAVASCRIPT -> renderToolsSdkJs(tools)
        PtcSdkLanguage.PYTHON -> renderToolsSdkPy(tools)
    }

/**
 * 把可见工具投影成 **JavaScript（同步）** 风格 SDK 声明。
 *
 * 必须与 run_code 的默认语言（in-JVM Rhino，javascript）一致：Rhino 不支持 async/await，
 * 因此这里是**同步契约**（`const r = tools.x({...})`），并显式提示不要写 await/Promise。
 */
fun renderToolsSdkJs(tools: List<ToolDef>): String {
    // 排除 run_code（入口本身）与 PTC 豁免工具（agent_complete/agent_progress 应**直调**，
    // 不应教模型把它们包进 run_code —— 那会走内层 dispatch，与终止协议契约错位）。
    val visible = tools.filter { it.name != PTC_ENTRY_TOOL && it.name !in PTC_DIRECT_ALLOWED }
    if (visible.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("### 程序内工具 SDK（JavaScript，同步）\n\n")
    sb.append("通过 `tools.工具名({ 参数: 值 })` 调用以下工具（已按当前会话可见工具投影）。\n")
    sb.append("调用约定：**同步返回字符串，不要写 await/Promise**；失败抛 `ToolCallError`（带 `toolName`），可在程序内 try/catch。\n")
    sb.append("用 `print` / `console.log` 输出日志，用 `return` 返回结构化结果；子调用次数上限 200、单个子调用超时 280 秒。\n")
    // 沙箱边界必须显式告知：Rhino 最出名的能力就是 Java 互操作，本引擎却刻意全禁。
    // 不写清楚，模型会写出 java.util.Base64.getEncoder() 之类代码，然后拿到
    // 「Cannot call property getEncoder in object [JavaPackage ...]」这种看不懂的错，无法自愈。
    sb.append("**沙箱边界**：禁止访问 Java 类（`java.*` / `Packages` / `importClass` / `Java.type` 均不可用），也没有 `require` / `process` / `fetch` / `Buffer`；宿主能力只能经 `tools.*`。\n")
    sb.append("拼大段文本别用模板字符串：内容里的 ${'$'}{...} 会被真插值并抛 ReferenceError。\n")
    sb.append("需要 base64 / 时间戳 / 文件读写时，一律走 `tools.*` 里的对应工具，或在程序内用纯 JS 自己实现。\n")
    // 语法缺口同样是实测出来的：最坑的是 for (const x of arr) 解析失败，而 let/var 形式正常。
    sb.append("**语法子集**：Rhino 的 ES6 不完整——`for (const x of arr)` 要写成 `for (let x of arr)`；展开 `[...a]`、`class`、函数默认参数均不支持；`const`/`let`、箭头函数、模板字符串、解构、`for...in`、`forEach` 可用。\n\n")
    for (t in visible) {
        val sig = t.parameters.values.joinToString(", ") { p ->
            val ph = jsPlaceholder(p.type)
            if (p.required) p.name + ": " + ph else p.name + "?: " + ph
        }
        sb.append("- `tools.").append(t.name).append("({ ").append(sig).append(" }) -> string`")
        if (t.description.isNotBlank()) {
            sb.append(" — ").append(t.description.replace('\n', ' ').trim())
        }
        sb.append('\n')
    }
    return sb.toString()
}

/**
 * 把可见工具投影成 Python 风格 SDK 声明（程序内 `tools.xxx()` 的签名）。
 */
fun renderToolsSdkPy(tools: List<ToolDef>): String {
    val visible = tools.filter { it.name != PTC_ENTRY_TOOL }
    if (visible.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("### 程序内工具 SDK（Python）\n\n")
    sb.append("通过 `tools.工具名(参数)` 调用以下工具（已按当前会话可见工具投影）。")
    sb.append("必填参数在前、可选参数带默认值；返回字符串，失败时抛异常，可在程序内 try/except。")
    sb.append("子调用次数上限 200、单个子调用超时 280 秒。\n\n")
    for (t in visible) {
        val sig = t.parameters.values.joinToString(", ") { p ->
            val ty = pyType(p.type)
            if (p.required) p.name + ": " + ty else p.name + ": " + ty + " = " + defaultRepr(p)
        }
        sb.append("- `").append(t.name).append("(").append(sig).append(") -> str`")
        if (t.description.isNotBlank()) {
            sb.append(" — ").append(t.description.replace('\n', ' ').trim())
        }
        sb.append('\n')
    }
    return sb.toString()
}

/** ParamType → JS 占位符（仅用于签名提示，不参与校验）。 */
private fun jsPlaceholder(t: ParamType): String = when (t) {
    ParamType.STRING -> "string"
    ParamType.INTEGER -> "int"
    ParamType.NUMBER -> "number"
    ParamType.BOOLEAN -> "boolean"
    ParamType.ARRAY -> "array"
    ParamType.OBJECT -> "object"
    ParamType.JSON -> "any"
}

/** ParamType → Python 类型注解。 */
private fun pyType(t: ParamType): String = when (t) {
    ParamType.STRING -> "str"
    ParamType.INTEGER -> "int"
    ParamType.NUMBER -> "float"
    ParamType.BOOLEAN -> "bool"
    ParamType.ARRAY -> "list"
    ParamType.OBJECT -> "dict"
    ParamType.JSON -> "Any"
}

/** 可选参数的默认值文本（JSON 原语 → Python 字面量）。 */
private fun defaultRepr(p: com.mcp.toolbox.ParamSpec): String {
    return when {
        p.default != null -> when (p.type) {
            ParamType.STRING -> "\"" + (p.default as kotlinx.serialization.json.JsonPrimitive).content + "\""
            ParamType.BOOLEAN -> (p.default as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toBooleanStrictOrNull()?.toString() ?: "None"
            ParamType.INTEGER, ParamType.NUMBER -> (p.default as kotlinx.serialization.json.JsonPrimitive).content
            else -> "None"
        }
        else -> "None"
    }
}
