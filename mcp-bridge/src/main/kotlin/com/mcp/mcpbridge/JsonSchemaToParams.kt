package com.mcp.mcpbridge

import com.mcp.toolbox.ParamSpec
import com.mcp.toolbox.ParamType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON Schema -> 本地 ParamSpec 映射。
 *
 * 远程 MCP 工具的 inputSchema 是标准 JSON Schema；本地 ToolDef 用 Map<String, ParamSpec>。
 * 这里做「够用即可」的转换：常见类型直通，复杂/未知类型退化为 JSON（空 schema），
 * 保证远程工具能被本地 LLM 看到与调用，不因 schema 差异注册失败。
 */
object JsonSchemaToParams {

    fun convert(schema: JsonObject): Map<String, ParamSpec> {
        val props = schema["properties"] as? JsonObject ?: return emptyMap()
        val required = requiredSet(schema)
        val out = LinkedHashMap<String, ParamSpec>()
        for ((key, value) in props) {
            val obj = value as? JsonObject ?: continue
            out[key] = param(key, obj, required.contains(key))
        }
        return out
    }

    private fun requiredSet(schema: JsonObject): Set<String> {
        val arr = schema["required"] as? JsonArray ?: return emptySet()
        val out = mutableSetOf<String>()
        for (e in arr) {
            val s = e.jsonPrimitive.contentOrNull
            if (s != null) out.add(s)
        }
        return out
    }

    private fun param(name: String, obj: JsonObject, required: Boolean): ParamSpec {
        val typeStr = obj["type"]?.jsonPrimitive?.contentOrNull
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val enumValues = (obj["enum"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val default = obj["default"]
        val type = mapType(typeStr)
        var items: ParamType? = null
        var elementProps: Map<String, ParamSpec> = emptyMap()
        var properties: Map<String, ParamSpec> = emptyMap()
        if (type == ParamType.ARRAY) {
            val itemObj = obj["items"] as? JsonObject
            items = mapType(itemObj?.get("type")?.jsonPrimitive?.contentOrNull)
            if (items == ParamType.OBJECT && itemObj != null) {
                elementProps = convert(itemObj)
            }
        } else if (type == ParamType.OBJECT) {
            properties = convert(obj)
        }
        return ParamSpec(
            name = name,
            type = type,
            description = description,
            required = required,
            enumValues = enumValues,
            default = default,
            items = items,
            properties = properties,
            elementProperties = elementProps,
        )
    }

    private fun mapType(typeStr: String?): ParamType = when (typeStr) {
        "string" -> ParamType.STRING
        "integer" -> ParamType.INTEGER
        "number" -> ParamType.NUMBER
        "boolean" -> ParamType.BOOLEAN
        "array" -> ParamType.ARRAY
        "object" -> ParamType.OBJECT
        else -> ParamType.JSON
    }
}
