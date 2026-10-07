package com.mcp.ptc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * run_code 的 result.json 信封解析（A4）。
 *
 * runner 统一写 `{"__ptc_ok":true,"value":...}` / `{"__ptc_ok":false,"error":...}`；
 * 兼容旧格式（裸返回值，无 __ptc_ok 字段）与缺失文件，避免升级期数据不可用。
 */
object PtcResultEnvelope {

    /** 严格 JSON：默认 Json 宽松到会把裸 token（如 not-json）解析成 JsonPrimitive，不能用于判合法性。 */
    private val StrictJson = Json { isLenient = false; ignoreUnknownKeys = true }

    data class Parsed(val ok: Boolean, val valueJson: String?, val error: String?)

    /**
     * 旧格式（无信封）必须是一个合法的 JSON 字面量。
     * 不能只依赖 kotlinx 解析：它会把裸 token（如 `not-json`）当成 JsonPrimitive 接受。
     */
    private val LEGACY_JSON = Regex(
        """^(\{.*\}|\[.*\]|".*"|-?\d+(\.\d+)?([eE][+-]?\d+)?|true|false|null)$""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parse(text: String?): Parsed {
        val trimmed = text?.trim()
        if (trimmed.isNullOrEmpty()) {
            return Parsed(false, null, "程序未产出 result.json（异常退出或被超时终止）")
        }
        val el = runCatching { StrictJson.parseToJsonElement(trimmed) }.getOrNull()
            ?: return Parsed(false, null, "result.json 不是合法 JSON")
        val obj = el as? JsonObject
        val flag = obj?.get("__ptc_ok")?.jsonPrimitive?.booleanOrNull
        if (flag == null) {
            // 旧格式：只有确实是合法 JSON 字面量才算成功，否则判失败（避免把垃圾文本当结果）
            return if (LEGACY_JSON.matches(trimmed)) Parsed(true, el.toString(), null)
            else Parsed(false, null, "result.json 不是合法 JSON")
        }
        return if (flag) {
            val v = obj!!["value"]?.takeIf { it !is JsonNull }
            Parsed(true, v?.toString(), null)
        } else {
            Parsed(false, null, obj!!["error"]?.jsonPrimitive?.contentOrNull ?: "程序执行失败")
        }
    }
}
