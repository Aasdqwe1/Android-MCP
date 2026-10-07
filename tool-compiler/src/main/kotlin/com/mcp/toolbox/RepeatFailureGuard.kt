package com.mcp.toolbox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 重复失败防护（对齐 DeepSeek-Reasonix repeat_failure_guard.go）。
 *
 * 防 LLM 在同一「锚点」上反复失败重试（如 old_string 找不到却不断重试、或同一参数重复报错）。
 * - 语义化失败签名：工具名 + 规范化路径 + 关键参数（edit_file 取 path+old_string，multi_edit 取 path+edits 的 old_strings）；
 * - 错误分类：锚点类（old_string_not_found / old_string_not_unique）阻止后引导重建锚点；
 * - 同类失败 >= [MAX_REPEAT_COUNT] 次后阻止，返回面向模型的引导文案；
 * - 成功的变更按路径重叠清除非锚点类失败历史（锚点类错误不因其它写入而失效）。
 */
class RepeatFailureGuard {

    companion object {
        private const val MAX_REPEAT_COUNT = 2
        private val ANCHOR_CLASSES = setOf("old_string_not_found", "old_string_not_unique")
        private const val PATH_KEY = "path"
        private const val OLD_STRING_KEY = "old_string"
        private const val EDITS_KEY = "edits"

        /** 把错误文本归类：锚点类 / 其它。 */
        internal fun errorClass(toolName: String, errorText: String): String {
            val t = errorText
            return when {
                t.contains("未在文件中找到匹配的 old_string") || t.contains("最接近的匹配") || t.contains("找到匹配的 old_string") ->
                    "old_string_not_found"
                t.contains("出现 ") && t.contains(" 次") || t.contains("不唯一") || t.contains("模糊匹配到") ->
                    "old_string_not_unique"
                else -> "other"
            }
        }

        /** 规范化路径（对齐 filepath.Clean 的轻量版）：合并重复斜杠、去掉 . 段。 */
        internal fun normalizePath(p: String): String {
            val cleaned = p.replace('\\', '/')
                .replace(Regex("/+"), "/")
                .replace(Regex("(^|/)\\./"), "$1")
            return cleaned.trimEnd('/')
        }

        /** 失败签名：同一锚点的重复失败共享同一签名。 */
        internal fun signature(toolName: String, args: JsonObject): String? {
            val path = (args[PATH_KEY] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
            val np = normalizePath(path)
            return when (toolName) {
                "edit_file" -> "edit_file|$np|${(args[OLD_STRING_KEY] as? JsonPrimitive)?.content ?: ""}"
                "multi_edit" -> "multi_edit|$np|${extractMultiEditOldStrings(args).take(120)}"
                else -> "$toolName|$np"
            }
        }

        /**
         * 从 multi_edit 参数中提取所有 old_string（支持 JSON 数组、字符串编码数组、行式编号键），
         * 用于失败签名——确保不同 old_string 的失败不会共享同一签名（避免误阻止）。
         */
        internal fun extractMultiEditOldStrings(args: JsonObject): String {
            // 1. edits 为 JSON 数组或字符串编码的 JSON 数组
            args[EDITS_KEY]?.let { el ->
                val list = when (el) {
                    is JsonArray -> el
                    is JsonPrimitive -> runCatching {
                        LenientJson.parseToJsonElement(el.content) as? JsonArray
                    }.getOrNull()
                    else -> null
                }
                if (list != null) {
                    return list.mapNotNull { item ->
                        (item as? JsonObject)?.let { o ->
                            (o[OLD_STRING_KEY] as? JsonPrimitive)?.content
                        }
                    }.joinToString("\u0001")
                }
            }
            // 2. 行式编号键：edit_N_old
            val re = Regex("^edit_(\\d+)_old$")
            val olds = ArrayList<Pair<Int, String>>()
            for ((k, v) in args) {
                val m = re.find(k) ?: continue
                val idx = m.groupValues[1].toInt()
                val content = (v as? JsonPrimitive)?.content ?: continue
                olds.add(idx to content)
            }
            return olds.sortedBy { it.first }.joinToString("\u0001") { it.second }
        }
    }

    private data class FailureRecord(
        val count: Int,
        val errClass: String,
        val path: String,
        /** 最近一次失败的错误摘要，熔断时随消息带回，帮模型避免重复同一错误。 */
        var lastError: String = "",
        var lastBlockReason: String? = null
    )

    private val records = HashMap<String, FailureRecord>()

    /** 记录一次失败。 */
    @Synchronized
    fun recordFailure(toolName: String, args: JsonObject, errorText: String, path: String?) {
        val sig = signature(toolName, args) ?: return
        val cls = errorClass(toolName, errorText)
        val prev = records[sig]
        records[sig] = FailureRecord(
            count = (prev?.count ?: 0) + 1,
            errClass = cls,
            path = path?.let { normalizePath(it) } ?: "",
            lastError = errorText.replace("\n", " ").take(200)
        )
    }

    /** 返回阻止消息（null = 不阻止）。 */
    @Synchronized
    fun shouldBlock(toolName: String, args: JsonObject): String? {
        val sig = signature(toolName, args) ?: return null
        val rec = records[sig] ?: return null
        if (rec.count < MAX_REPEAT_COUNT) return null
        return if (rec.errClass in ANCHOR_CLASSES) {
            "该操作已连续失败 ${rec.count} 次（锚点类错误）。请停止盲目重试：先用 read_file(return_format=raw) 或 get_block 获取文件当前实际内容，基于最新内容重建 old_string 后再调用。" +
                (if (rec.lastError.isNotBlank()) "；最近一次失败原因：${rec.lastError}" else "")
        } else {
            "该操作已连续失败 ${rec.count} 次。请停止重试，换用其它方案（如 get_block 定位 + read_file 复核），或检查参数是否符合工具说明。" +
                (if (rec.lastError.isNotBlank()) "；最近一次失败原因：${rec.lastError}" else "")
        }
    }

    /** 成功的变更按路径重叠清除失败历史；锚点类失败（old_string 相关）不因其它写入而失效，保留。 */
    @Synchronized
    fun clearAfterMutation(toolName: String, args: JsonObject, path: String?) {
        val p = path?.let { normalizePath(it) } ?: return
        val it = records.entries.iterator()
        while (it.hasNext()) {
            val (sig, rec) = it.next()
            if (rec.errClass in ANCHOR_CLASSES) continue  // 锚点类失败不因其它写入变有效
            if (rec.path.isNotEmpty() && rec.path == p) it.remove()
        }
    }
}
