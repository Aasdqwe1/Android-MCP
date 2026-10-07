@file:JvmName("ToolboxCore")
package com.mcp.toolbox

import com.mcp.serialization.McpJson
import kotlinx.serialization.json.*

/**
 * 参数 JSON 类型，映射到 JSON Schema 的 `type` 字段。
 */
enum class ParamType(val jsonName: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object"),
    /** 任意 JSON 值（string/number/object/array/bool/null 均可），对应空 schema `{}`。 */
    JSON("json")
}

/**
 * 单个工具参数的定义（编译产物的一部分，最终序列化为 JSON Schema 的 `properties` 条目）。
 *
 * @param name       参数名（LLM 调用时作为 key）
 * @param type       参数类型
 * @param description 给 LLM 看的自然语言说明
 * @param required   是否必填
 * @param enumValues 枚举约束（仅基础类型有效）
 * @param default    默认值（JSON 原语），LLM 未传时使用
 * @param items      ARRAY 的元素类型
 * @param itemEnum   ARRAY 元素枚举约束（可选）
 * @param properties OBJECT 的嵌套属性
 */
data class ParamSpec(
    val name: String,
    val type: ParamType,
    val description: String = "",
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
    val default: JsonElement? = null,
    val items: ParamType? = null,
    val itemEnum: List<String> = emptyList(),
    val properties: Map<String, ParamSpec> = emptyMap(),
    val elementProperties: Map<String, ParamSpec> = emptyMap(),
    /**
     * 是否为「多行值」参数（如 write_file 的 content、edit_file 的 old_string/new_string、run_bash 的 script）。
     * 行式协议下行多行内容必须用 `参数名 <<< … >>>` 定界块包裹，否则内容会被截断或解析歧义。
     * 该标志只用于行式清单提示与解析对齐，不写入 JSON Schema（避免破坏契约字节 / strict 模式）。
     */
    val multiLine: Boolean = false
) {
    /** 渲染成 JSON Schema 片段（JsonObject）。 */
    fun toJsonSchema(): JsonObject = buildJsonObject {
        // JSON 类型不输出 type（等价于空 schema `{}`），让模型可传任意 JSON 值，
        // 避免把整段参数写成字符串后内部 JSON 还得转义、进而破坏外层 JSON 解析。
        if (type != ParamType.JSON) put("type", type.jsonName)
        if (description.isNotEmpty()) put("description", description)
        if (enumValues.isNotEmpty()) putJsonArray("enum") { enumValues.forEach { add(it) } }
        if (default != null) put("default", default)
        when (type) {
            ParamType.ARRAY -> putJsonObject("items") {
                put("type", (items ?: ParamType.STRING).jsonName)
                if (itemEnum.isNotEmpty()) putJsonArray("enum") { itemEnum.forEach { add(it) } }
                // element=OBJECT 时，把元素对象的嵌套属性展开为 items.properties，
                // 引导模型按结构填充数组元素（如 questions 数组的每项 {question,type,options,multi_select}）。
                if (items == ParamType.OBJECT && elementProperties.isNotEmpty()) {
                    putJsonObject("properties") {
                        elementProperties.forEach { (k, v) -> put(k, v.toJsonSchema()) }
                    }
                    val req = elementProperties.filterValues { it.required }.keys
                    if (req.isNotEmpty()) putJsonArray("required") { req.forEach { add(it) } }
                }
            }
            ParamType.OBJECT -> {
                putJsonObject("properties") {
                    properties.forEach { (k, v) -> put(k, v.toJsonSchema()) }
                }
                val req = properties.filterValues { it.required }.keys
                if (req.isNotEmpty()) putJsonArray("required") { req.forEach { add(it) } }
            }
            // JSON：任意值，无 type 约束，这里不追加任何字段。
            ParamType.JSON -> Unit
            else -> Unit
        }
    }
}

/**
 * 一个「编译后」的工具：含元数据、输入 JSON Schema，以及处理函数。
 *
 * 处理函数接收 LLM 下发的 arguments（[JsonObject]），返回给 LLM 的结果文本。
 */
data class ToolDef(
    val name: String,
    val description: String,
    val parameters: Map<String, ParamSpec> = emptyMap(),
    val handler: suspend (JsonObject) -> String
) {
    /** 必填参数名列表（惰性：dispatch 校验热路径每次都会用到）。 */
    val requiredParams: List<String> by lazy { parameters.filterValues { it.required }.keys.toList() }

    /**
     * 完整输入 JSON Schema（properties + required）。
     * 惰性构造并复用同一实例：schema 校验是每次 dispatch 的热路径，不能每次重建。
     */
    val inputSchema: JsonObject by lazy {
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                parameters.forEach { (k, v) -> put(k, v.toJsonSchema()) }
            }
            if (requiredParams.isNotEmpty()) putJsonArray("required") { requiredParams.forEach { add(it) } }
        }
    }
}

/**
 * 解析 LLM 参数用的宽松 JSON。
 * 统一指向 [McpJson]（ignoreUnknownKeys + coerceInputValues + isLenient），
 * 与 :app 端迁移目标一致，避免两套 Json 配置行为分叉。
 */
val LenientJson: Json = McpJson

/** 打印/序列化给 LLM 的 JSON 定义时用的美化实例。 */
val PrettyJson: Json = Json { prettyPrint = true; isLenient = true }

/**
 * 契约条目：provider 可见的工具快照（注册时规范化一次并缓存，保证字节稳定——
 * DeepSeek 前缀缓存命中的前提，对齐官方 contract.go）。
 */
data class ContractEntry(
    val name: String,
    val description: String,
    val readOnly: Boolean,
    val schema: JsonObject
)

/** 递归排序 JsonObject 的键（properties/items 内部也排序），保证 schema 字节稳定。 */
internal fun canonicalizeSchema(el: JsonElement): JsonElement = when (el) {
    is JsonObject -> JsonObject(el.entries.sortedBy { it.key }.associate { (k, v) -> k to canonicalizeSchema(v) })
    is JsonArray -> JsonArray(el.map { canonicalizeSchema(it) })
    else -> el
}

/**
 * 工具注册表：收集所有工具，提供「分发执行」能力。
 *
 * 用法：
 * ```kotlin
 * val box = Toolbox().apply { registerAll(calculator(), currentTime()) }
 * val out = ToolCompiler.toOpenAi(box.all())   // 喂给 LLM 的 tools 定义
 * runBlocking { val r = box.dispatch("calculator", """{"expression":"1+2"}""") }
 * ```
 */
class Toolbox {
    private val tools = LinkedHashMap<String, ToolDef>()
    private var contractCache: List<ContractEntry>? = null
    private var namesJsonCache: String? = null

    fun register(tool: ToolDef): Toolbox = apply {
        tools[tool.name] = tool
        contractCache = null
        namesJsonCache = null
    }
    fun registerAll(vararg ts: ToolDef): Toolbox = apply { ts.forEach { register(it) } }
    fun registerAll(ts: Collection<ToolDef>): Toolbox = apply { ts.forEach { register(it) } }
    fun unregister(name: String): Toolbox = apply {
        tools.remove(name)
        contractCache = null
        namesJsonCache = null
    }
    fun get(name: String): ToolDef? = tools[name]
    fun all(): List<ToolDef> = tools.values.toList()
    fun names(): List<String> = tools.keys.toList()

    /**
     * 工具名数组的 JSON 文本（`["a","b"]`），供 JS 引擎注入 `tools` 命名空间。
     * 缓存：run_code 每次执行都要用，工具注册/注销时失效。
     */
    fun namesJson(): String = namesJsonCache ?: buildNamesJson().also { namesJsonCache = it }

    private fun buildNamesJson(): String = buildString {
        append('[')
        tools.keys.forEachIndexed { i, n ->
            if (i > 0) append(',')
            append(JsonPrimitive(n).toString())
        }
        append(']')
    }

    /**
     * 规范化一次的契约快照（按工具名排序、schema 键排序稳定）。
     * 首次调用生成并缓存；注册/注销会使缓存失效。
     */
    fun contractEntries(): List<ContractEntry> {
        contractCache?.let { return it }
        val entries = tools.values
            .map { ContractEntry(it.name, it.description.trim(), false, canonicalizeSchema(it.inputSchema) as JsonObject) }
            .sortedBy { it.name }
        contractCache = entries
        return entries
    }

    /**
     * 分发一次工具调用：name + arguments(JSON 对象)，返回结果文本。
     * 工具不存在或执行抛错都会被捕获并格式化为 `{"error": "..."}` 文本，绝不让 LLM 调用把整个会话打挂。
     */
    suspend fun dispatch(name: String, arguments: JsonObject): String {
        val t = tools[name] ?: return errorResult(ToolRepair.unknownTool(name, tools.keys))
        val problems = JsonSchemaSubset.validate(t.inputSchema, arguments)
        if (problems.isNotEmpty()) {
            // 聚焦修复（对齐 universal-web-api focused_repair）：只回出错字段 + 正确签名 +
            // 一条修正指令，不再只丢一句聚合错误串让模型连续试错。
            return errorResult(ToolRepair.invalidArguments(name, t, problems))
        }
        return runCatching { t.handler(arguments) }
            .getOrElse { e -> errorResult(e.message ?: e::class.simpleName ?: "tool failed") }
    }

    /**
     * 便捷：解析 arguments JSON 字符串后分发。
     * 解析失败不再向上抛（避免整条会话被打挂），而是返回正确转义的错误 JSON，
     * 引导模型重新以合法 JSON 调用（自愈）。
     */
    suspend fun dispatch(name: String, argumentsJson: String): String {
        val args = runCatching { parseToolArguments(argumentsJson) }
            .getOrElse { e ->
                val msg = (e as? ToolArgsParseException)?.message
                    ?: "工具参数解析失败：${e.message ?: e::class.simpleName}"
                return errorResult(ToolRepair.parseError(name, msg, tools[name]))
            }
        return dispatch(name, args)
    }

    /**
     * 严格分发（PTC 程序内调用）：失败抛 [ToolCallException]（对齐 dsh 的 ToolCallError），
     * 让程序里的 try/catch 真正可用；未知工具 / 参数解析失败 / schema 违规 / handler 抛错同一契约。
     */
    suspend fun dispatchOrThrow(name: String, argumentsJson: String): String {
        val args = try {
            parseToolArguments(argumentsJson)
        } catch (e: ToolArgsParseException) {
            throw ToolCallException(name, ToolRepair.parseError(name, e.message ?: "工具参数解析失败", tools[name]), e)
        }
        return dispatchOrThrow(name, args)
    }

    /** 严格分发（已解析的 JSON 对象）。 */
    suspend fun dispatchOrThrow(name: String, arguments: JsonObject): String {
        val t = tools[name] ?: throw ToolCallException(name, ToolRepair.unknownTool(name, tools.keys))
        val problems = JsonSchemaSubset.validate(t.inputSchema, arguments)
        if (problems.isNotEmpty()) {
            throw ToolCallException(name, ToolRepair.invalidArguments(name, t, problems))
        }
        return try {
            t.handler(arguments)
        } catch (e: ToolCallException) {
            throw e
        } catch (e: Throwable) {
            throw ToolCallException(name, e.message ?: e::class.simpleName ?: "tool failed", e)
        }
    }
}

/**
 * 「工具编译器」：把 [ToolDef] 列表编译成不同 LLM / 协议所需的工具定义 JSON。
 */
object ToolCompiler {
    /**
     * OpenAI / DeepSeek 兼容的 `tools` 数组（function calling 格式）。
     *
     * @param strict 是否启用 OpenAI 严格模式（structured output / 约束生成）。
     *   启用时会附加 `"strict": true` 并将 schema 规范化为严格兼容形态
     *   （顶层与嵌套对象均 `additionalProperties: false`、所有属性移入 `required`），
     *   由解码端保证输出合法 JSON，从根源消除转义错误（对应项目方案 1）。
     *   注意：严格模式要求所有属性必填，且依赖提供方支持（DeepSeek 兼容性不确定），
     *   故默认关闭；开启前请确认目标模型支持 strict tool calls。
     */
    fun toOpenAi(tools: List<ToolDef>, strict: Boolean = false): JsonArray = buildJsonArray {
        tools.forEach { t ->
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name)
                    put("description", t.description)
                    if (strict) {
                        put("strict", true)
                        put("parameters", t.inputSchema.strictifySchema())
                    } else {
                        put("parameters", t.inputSchema)
                    }
                }
            }
        }
    }

    /** MCP（Model Context Protocol）的 tools 数组。 */
    fun toMcp(tools: List<ToolDef>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            addJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.inputSchema)
            }
        }
    }

    /** MCP `tools/list` 响应的 `tools` 字段。 */
    fun toMcpToolsList(tools: List<ToolDef>): JsonObject = buildJsonObject {
        putJsonArray("tools") {
        tools.forEach { t ->
            addJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.inputSchema)
            }
        }
    }
}

/**
 * 将 JSON Schema 规范化为 OpenAI 严格模式兼容形态（递归）：
 *  - `object` 类型：复制其余字段，`properties` 递归规范化，所有属性名移入 `required`，
 *    追加 `additionalProperties: false`
 *  - `array` 类型：`items` 递归规范化
 *  - 其他类型：原样复制
 * 这保证启用 [ToolCompiler.toOpenAi] 的 `strict` 时 schema 满足「全必填 + 无额外属性」约束。
 */
private fun JsonObject.strictifySchema(): JsonObject = buildJsonObject {
    val type = this@strictifySchema["type"]?.jsonPrimitive?.content
    // 先复制除 properties/required 外的字段（required 由本函数重新计算）
    this@strictifySchema.forEach { (k, v) ->
        if (k != "properties" && k != "required") put(k, v)
    }
    when (type) {
        "object" -> {
            val props = this@strictifySchema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            putJsonObject("properties") {
                props.forEach { (pk, pv) -> put(pk, pv.jsonObject.strictifySchema()) }
            }
            putJsonArray("required") { props.keys.forEach { add(it) } }
            put("additionalProperties", false)
        }
        "array" -> {
            this@strictifySchema["items"]?.jsonObject?.let { put("items", it.strictifySchema()) }
        }
        else -> Unit
    }
}

    /**
     * DeepSeek 逆向后端专用：把工具编译成 OpenAI function calling 的 `tools` JSON 数组文本，
     * 直接注入到 system prompt（DeepSeek 不支持原生 tools 参数，只能以文本形式对齐发送格式）。
     *
     * 产物与 [toOpenAi] 完全一致：`[{"type":"function","function":{"name","description","parameters"}}]`，
     * 只是序列化为字符串，**不再包裹 markdown 代码块、不再逐工具分段**——真正实现「function calling
     * 发送格式」与 OpenAI 对齐，模型看到的就是一份标准 OpenAI `tools` 数组。
     */
    fun toOpenAiTools(tools: List<ToolDef>, strict: Boolean = false): String =
        PrettyJson.encodeToString(JsonElement.serializer(), toOpenAi(tools, strict))

    /**
     * 纯文本工具清单（零 JSON），直接注入 system prompt。
     *
     * 格式：
     * ```
     * [工具清单]
     * - read_file: 读取文件...
     *     参数: path(必填) 文件路径（单行值：冒号后写到行尾，无需引号、不转义）; start_line(可选) 起始行号; ...
     * - write_file: 写入文件...
     *     参数: path(必填) 文件路径（单行值）; content(必填) 要写入的内容（多行值，必须用 `content <<< … >>>` 定界块包裹）; ...
     * ```
     * 每工具一行描述 + 一行参数表；参数「名(必填/可选) 说明（值格式提示）」分号分隔。
     *
     * **值格式提示必须与调用协议一致**：行式说「冒号后写到行尾」「用 <<< >>> 定界块」，
     * XML 说「写在 parameter 标签之间」「多行直接写、无需定界块」。
     * 否则模型会同时看到两套互相冲突的值写法（指南教 XML、参数表却教行式）。
     *
     * @param style 调用协议风格；默认 [ToolProtocolStyle.LINE]（现有行为不变）。
     */
    fun toProtocolTools(
        tools: List<ToolDef>,
        style: ToolProtocolStyle = ToolProtocolStyle.LINE,
    ): String {
        val sb = StringBuilder("[工具清单]\n")
        for (t in tools) {
            sb.append("- ").append(t.name).append(": ")
                .append(t.description.replace('\n', ' ').trim()).append('\n')
            if (t.parameters.isNotEmpty()) {
                val params = t.parameters.map { (k, v) ->
                    val req = if (v.required) "必填" else "可选"
                    val suffix = when {
                        style == ToolProtocolStyle.XML && v.multiLine ->
                            "（多行内容直接写在 `<$k>` 标签之间，无需定界块）"
                        style == ToolProtocolStyle.XML &&
                            (v.type == ParamType.ARRAY || v.type == ParamType.OBJECT || v.type == ParamType.JSON) ->
                            "（数组/对象写成 JSON 原文，放在 parameter name=\"$k\" 的标签之间）"
                        style == ToolProtocolStyle.XML ->
                            "（值写在 parameter name=\"$k\" 的标签之间）"
                        v.multiLine -> "（多行值，必须用 `$k <<< … >>>` 定界块包裹）"
                        else -> "（单行值：冒号后写到行尾，无需引号、不转义）"
                    }
                    "$k($req) ${v.description.replace('\n', ' ').trim()}$suffix"
                }.joinToString("; ")
                sb.append("    参数: ").append(params).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * [toProtocolTools] 的旧名（历史命名：该函数原名只服务于行式协议，后扩展出 XML）。
     *
     * 保留委托而非直接删除，是为了不打断既有调用方（AgentOrchestrator、测试等）的编译；
     * 新代码请用 [toProtocolTools]。
     */
    @Deprecated("改名以免误导：该函数同时支持 LINE 与 XML，请用 toProtocolTools",
        ReplaceWith("toProtocolTools(tools, style)"))
    fun toLineProtocolTools(
        tools: List<ToolDef>,
        style: ToolProtocolStyle = ToolProtocolStyle.LINE,
    ): String = toProtocolTools(tools, style)
}

/** 工具清单的值格式提示风格（须与当前调用协议一致，见 [ToolCompiler.toProtocolTools]）。 */
enum class ToolProtocolStyle { LINE, XML }
