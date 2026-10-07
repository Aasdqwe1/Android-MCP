@file:JvmName("ToolboxAnnotations")
package com.mcp.toolbox

import kotlinx.serialization.json.*

/**
 * 标记一个**普通（非 suspend）**函数为 MCP 工具。
 *
 * 例：
 * ```kotlin
 * class MyTools {
 *     @Tool("greet", "向某人问好")
 *     fun greet(@Param("name", "对方名字") name: String): String = "Hello, $name!"
 * }
 * // 注册：ToolScanner.scan(MyTools()).forEach { box.register(it) }
 * ```
 *
 * 注意：
 * - 扫描使用 `java.lang.reflect`（兼容 Android，无需 kotlin-reflect）。
 * - 参数名优先取 `@Param(name=)`；若省略，则取编译期参数名（需 `-java-parameters`，本模块已开启）。
 * - 标注的方法应为普通函数；suspend 函数请用 DSL 的 [tool] 定义。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(val name: String = "", val description: String = "")

/**
 * 标注工具方法的参数元信息。
 *
 * @param name       参数名（LLM 调用时的 key）；为空则回退到编译期参数名
 * @param description 给 LLM 的说明
 * @param required   是否必填
 * @param enum       枚举约束
 * @param default    默认值（字符串形式）
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(
    val name: String = "",
    val description: String = "",
    val required: Boolean = true,
    val enum: Array<String> = [],
    val default: String = ""
)

/**
 * 通过反射把对象上的 `@Tool` 方法编译为 [Tool]。
 */
object ToolScanner {
    fun scan(instance: Any): List<ToolDef> =
        instance::class.java.declaredMethods
            .filter { it.isAnnotationPresent(Tool::class.java) }
            .map { compileMethod(instance, it) }

    private fun compileMethod(instance: Any, method: java.lang.reflect.Method): ToolDef {
        val ann = method.getAnnotation(Tool::class.java)
        val name = ann.name.ifEmpty { method.name }
        val desc = ann.description.ifEmpty { method.name }
        val specs = LinkedHashMap<String, ParamSpec>()

        for (p in method.parameters) {
            val pa = p.getAnnotation(Param::class.java) ?: continue
            val pname = pa.name.ifEmpty { p.name }
            val type = javaTypeToParamType(p.type)
            val def = if (pa.default.isNotEmpty()) defaultFor(type, pa.default) else null
            specs[pname] = ParamSpec(
                name = pname,
                type = type,
                description = pa.description,
                required = pa.required,
                enumValues = pa.enum.toList(),
                default = def
            )
        }

        val handler: suspend (JsonObject) -> String = handler@{ args ->
            val callArgs = arrayOfNulls<Any?>(method.parameterCount)
            method.parameters.forEachIndexed { i, p ->
                val pa = p.getAnnotation(Param::class.java) ?: return@forEachIndexed
                val pname = pa.name.ifEmpty { p.name }
                val spec = specs[pname] ?: return@forEachIndexed
                val raw = args[pname]
                callArgs[i] = when {
                    raw != null && raw !is JsonNull -> coerce(raw, spec.type)
                    spec.default != null -> coerce(spec.default, spec.type)
                    spec.required -> throw IllegalArgumentException("缺少必填参数: $pname")
                    else -> null
                }
            }
            method.isAccessible = true
            val ret = method.invoke(instance, *callArgs)
            when (ret) {
                is String -> ret
                null -> "null"
                is JsonElement -> ret.toString()
                else -> ret.toString()
            }
        }
        return ToolDef(name, desc, specs, handler)
    }

    private fun javaTypeToParamType(c: Class<*>): ParamType = when {
        c == String::class.java -> ParamType.STRING
        c == Int::class.java || c == Integer::class.java || c == Long::class.java -> ParamType.INTEGER
        c == Double::class.java || c == Float::class.java -> ParamType.NUMBER
        c == Boolean::class.java -> ParamType.BOOLEAN
        c == JsonElement::class.java -> ParamType.JSON
        List::class.java.isAssignableFrom(c) || Collection::class.java.isAssignableFrom(c) -> ParamType.ARRAY
        else -> ParamType.STRING
    }

    private fun defaultFor(type: ParamType, v: String): JsonElement = when (type) {
        ParamType.INTEGER -> JsonPrimitive(v.toIntOrNull() ?: 0)
        ParamType.NUMBER -> JsonPrimitive(v.toDoubleOrNull() ?: 0.0)
        ParamType.BOOLEAN -> JsonPrimitive(v.toBooleanStrictOrNull() ?: false)
        ParamType.JSON -> JsonPrimitive(v)
        else -> JsonPrimitive(v)
    }

    private fun coerce(el: JsonElement, type: ParamType): Any? = when (type) {
        ParamType.STRING -> el.jsonPrimitive.content
        ParamType.INTEGER -> el.jsonPrimitive.content.toInt()
        ParamType.NUMBER -> el.jsonPrimitive.content.toDouble()
        ParamType.BOOLEAN -> el.jsonPrimitive.boolean
        ParamType.ARRAY -> (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList<String>()
        ParamType.OBJECT -> el.toString()
        // 任意 JSON 值：原样回传 JsonElement，交由 handler 决定如何序列化/使用。
        ParamType.JSON -> el
    }
}
