package com.mcp.toolbox

import kotlinx.serialization.json.*

/**
 * JSON Schema 子集校验器（对齐 deepseek-harness 的 json-schema.ts:76-87）。
 *
 * 只支持可栈安全、可跨语言复刻的关键字：type / oneOf / properties / required /
 * additionalProperties / items / enum / const。违规**一次汇总全部错误**（全量拒绝），
 * 由 [Toolbox] 在 dispatch 入口统一执行——模型直调与 PTC 程序内调用共用同一份契约。
 */
object JsonSchemaSubset {

    /**
     * 校验 [value] 是否符合 [schema]（子集语义）。
     * @return 全部违规描述；空列表表示通过。错误串包含 JSON 路径，便于模型/程序定位。
     */
    fun validate(schema: JsonObject, value: JsonElement, path: String = "$"): List<String> {
        val errors = mutableListOf<String>()

        // const
        schema["const"]?.let { c ->
            if (value != c) errors += path + ": 期望常量 " + c.compact() + ", 实际 " + value.compact()
        }

        // enum
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (allowed.none { it == value }) {
                errors += path + ": 取值 " + value.compact() + " 不在枚举 [" + allowed.joinToString(", ") { it.compact() } + "] 内"
            }
        }

        // oneOf：必须恰好匹配 1 个分支
        (schema["oneOf"] as? JsonArray)?.let { branches ->
            val matched = branches.count { it is JsonObject && validate(it, value, path).isEmpty() }
            when {
                matched == 0 -> errors += path + ": 不匹配 oneOf 的任一分支（共 " + branches.size + " 个）"
                matched > 1 -> errors += path + ": 同时匹配 oneOf 的 " + matched + " 个分支（必须恰好 1 个）"
            }
        }

        // type（支持 "string" 或 ["string","null"]）
        expectedTypes(schema).takeIf { it.isNotEmpty() }?.let { types ->
            val actual = typeOf(value)
            // JSON Schema 规范：integer 是 number 的子类型。声明 number 的参数必须接受整数
            // （如 speed=1、lines=30），否则模型按最自然写法传整数会被误拒。
            val ok = actual in types || (actual == "integer" && "number" in types)
            if (!ok) errors += path + ": 期望类型 " + types.joinToString("|") + ", 实际 " + actual
        }

        // object：required / properties / additionalProperties
        if (value is JsonObject) {
            val props = schema["properties"] as? JsonObject
            (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.forEach { k -> if (!value.containsKey(k)) errors += path + ": 缺少必填参数 \"" + k + "\"" }

            props?.forEach { (k, sub) ->
                val v = value[k] ?: return@forEach
                if (sub is JsonObject) errors += validate(sub, v, path + "." + k)
            }

            when (val add = schema["additionalProperties"]) {
                is JsonPrimitive -> if (add.contentOrNull == "false") {
                    for (k in value.keys) if (props?.containsKey(k) != true) {
                        errors += path + ": 不允许多余参数 \"" + k + "\""
                    }
                }
                is JsonObject -> for ((k, v) in value) {
                    if (props?.containsKey(k) != true) errors += validate(add, v, path + "." + k)
                }
                else -> Unit
            }
        }

        // array：items 为单个 schema（或按位置）
        if (value is JsonArray) {
            when (val items = schema["items"]) {
                is JsonObject -> value.forEachIndexed { i, v -> errors += validate(items, v, path + "[" + i + "]") }
                is JsonArray -> value.forEachIndexed { i, v ->
                    (items.getOrNull(i) as? JsonObject)?.let { errors += validate(it, v, path + "[" + i + "]") }
                }
                else -> Unit
            }
        }

        return errors
    }

    private fun expectedTypes(schema: JsonObject): List<String> = when (val t = schema["type"]) {
        is JsonPrimitive -> listOfNotNull(t.contentOrNull)
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun typeOf(v: JsonElement): String = when (v) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            v.isString -> "string"
            v.booleanOrNull != null -> "boolean"
            v.longOrNull != null -> "integer"
            v.doubleOrNull != null -> "number"
            else -> "unknown"
        }
    }

    private fun JsonElement.compact(): String =
        toString().let { if (it.length > 60) it.take(57) + "..." else it }
}
