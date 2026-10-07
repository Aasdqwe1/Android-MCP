@file:JvmName("ToolboxDsl")
package com.mcp.toolbox

import kotlinx.serialization.json.*

/**
 * 声明一个工具的类型安全 DSL。
 *
 * 示例：
 * ```kotlin
 * val calculator = tool("calculator") {
 *     description = "计算数学表达式，支持 + - * / % 与括号"
 *     string("expression") {
 *         description = "要计算的表达式，如 (2+3)*4"
 *     }
 *     handler { args ->
 *         val expr = args["expression"]!!.jsonPrimitive.content
 *         evalMath(expr).toString()
 *     }
 * }
 * ```
 */
fun tool(name: String, block: ToolDsl.() -> Unit): ToolDef = ToolDsl(name).apply(block).build()

/** 工具级 DSL 作用域。 */
class ToolDsl internal constructor(val name: String) {
    var description: String = ""
    /** 该工具整体是否必填上下文（用于 OBJECT 参数自身），默认 true。 */
    var required: Boolean = true
    internal val params = LinkedHashMap<String, ParamSpec>()
    private var handler: suspend (JsonObject) -> String = { "{}" }

    fun string(name: String, block: ParamDsl.() -> Unit = {}) = add(name, ParamType.STRING, block)
    fun integer(name: String, block: ParamDsl.() -> Unit = {}) = add(name, ParamType.INTEGER, block)
    fun number(name: String, block: ParamDsl.() -> Unit = {}) = add(name, ParamType.NUMBER, block)
    fun boolean(name: String, block: ParamDsl.() -> Unit = {}) = add(name, ParamType.BOOLEAN, block)

    /**
     * 任意 JSON 值参数（string / number / object / array / bool / null 均可）。
     * 用于「请求体」等希望模型直接传 JSON 对象而非转义成字符串的场景，
     * 从根源避免嵌套 JSON 的转义错误（如 `{"cmd":"..."}` 直接写，无需 `\"` 转义）。
     */
    fun json(name: String, block: ParamDsl.() -> Unit = {}) = add(name, ParamType.JSON, block)

    /**
     * 数组参数。`element` 为元素类型；当 element=OBJECT 时，可用 [block]（嵌套 ToolDsl）
     * 描述每个对象的属性，从而生成 `items: { type: object, properties: {...} }` 的完整 schema，
     * 引导模型按要求结构填充数组元素（如 ask_user 的 questions 数组）。
     */
    fun array(name: String, element: ParamType, block: ToolDsl.() -> Unit = {}) {
        val nested = if (element == ParamType.OBJECT) ToolDsl(name).apply(block) else null
        val d = ParamDsl(name, ParamType.ARRAY).apply {
            elementType = element
            elementProperties = nested?.params ?: emptyMap()
        }
        params[name] = d.build()
    }

    /** 对象参数：用嵌套 DSL 描述其属性。 */
    fun obj(name: String, block: ToolDsl.() -> Unit = {}) {
        val nested = ToolDsl(name).apply(block)
        params[name] = ParamSpec(
            name = name,
            type = ParamType.OBJECT,
            description = nested.description,
            required = nested.required,
            properties = nested.params
        )
    }

    fun handler(block: suspend (JsonObject) -> String) { handler = block }

    internal fun build(): ToolDef = ToolDef(name, description, params, handler)

    private fun add(name: String, type: ParamType, block: ParamDsl.() -> Unit) {
        val d = ParamDsl(name, type).apply(block)
        params[name] = d.build()
    }
}

/** 参数级 DSL 作用域。 */
class ParamDsl internal constructor(val name: String, val type: ParamType) {
    var description: String = ""
    var required: Boolean = true
    var enumValues: List<String> = emptyList()
    var default: JsonElement? = null
    var elementType: ParamType = ParamType.STRING
    /** 当本参数为 ARRAY 且 element=OBJECT 时，元素对象的嵌套属性（用于生成 items.properties）。 */
    var elementProperties: Map<String, ParamSpec> = emptyMap()
    /** 是否为多行值参数（如 content / old_string / script）。行式协议下要求用 `参数名 <<< … >>>` 定界块包裹。 */
    var multiLine: Boolean = false

    /** 设置默认值（字符串形式，内部按类型转换）。 */
    fun default(value: String) { default = JsonPrimitive(value) }
    fun default(value: Number) { default = JsonPrimitive(value) }
    fun default(value: Boolean) { default = JsonPrimitive(value) }

    internal fun build(): ParamSpec = ParamSpec(
        name = name,
        type = type,
        description = description,
        required = required,
        enumValues = enumValues,
        default = default,
        items = elementType,
        elementProperties = elementProperties,
        multiLine = multiLine
    )
}
