package com.mcp

import android.content.Context
import com.mcp.toolbox.tool
import com.mcp.toolbox.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 日志分析工具集。
 *
 * 提供日志查看和分析功能。
 */
fun logTools(context: Context): List<ToolDef> = listOf(
    tailLogTool(context),
    analyzeLogTool(context)
)

/**
 * 查看日志文件尾部。
 *
 * 参数：
 * - path (必填): 日志文件路径
 * - lines (可选): 读取行数，默认 50
 *
 * 返回：日志尾部内容
 */
fun tailLogTool(context: Context): ToolDef = tool("tail_log") {
    description = "查看日志文件尾部（默认 50 行）。返回指定行数的日志内容。"
    string("path") {
        description = "日志文件路径（绝对路径）"
        required = true
    }
    integer("lines") {
        description = "读取行数，默认 50，最大 500"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val path = args.requireStr("path", "tail_log")
            val lines = args["lines"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 500) ?: 50

            val file = File(path)
            if (!file.exists()) {
                return@withContext """{"error":"文件不存在: $path"}"""
            }
            if (!file.canRead()) {
                return@withContext """{"error":"无法读取文件: $path"}"""
            }

            // 读取尾部行
            val content = runCatching {
                file.readLines().takeLast(lines).joinToString("\n")
            }.getOrElse { e ->
                return@withContext """{"error":"读取文件失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }

            val totalLines = runCatching { file.readLines().size }.getOrElse { 0 }

            buildString {
                appendLine("""{"path":"$path","total_lines":$totalLines,"tail_lines":$lines,"content":""")
                appendLine(content.replace("\"", "\\\""))
                appendLine("\"}")
            }
        }
    }
}

/**
 * 分析日志文件。
 *
 * 参数：
 * - path (必填): 日志文件路径
 * - pattern (可选): 过滤关键词
 * - group_by (可选): 分组方式，error / warning / all（默认 all）
 *
 * 返回：按类型分组的统计结果
 */
fun analyzeLogTool(context: Context): ToolDef = tool("analyze_log") {
    description = "解析日志中的错误/警告/异常，按类型分组统计。"
    string("path") {
        description = "日志文件路径（绝对路径）"
        required = true
    }
    string("pattern") {
        description = "过滤关键词（可选，如 \"Exception\", \"ERROR\"）"
        required = false
    }
    string("group_by") {
        description = "分组方式：error、warning、all（默认 all）"
        required = false
        enumValues = listOf("error", "warning", "all")
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val path = args.requireStr("path", "analyze_log")
            val pattern = args["pattern"]?.jsonPrimitive?.content.orEmpty()
            val groupBy = args["group_by"]?.jsonPrimitive?.content ?: "all"

            val file = File(path)
            if (!file.exists()) {
                return@withContext """{"error":"文件不存在: $path"}"""
            }
            if (!file.canRead()) {
                return@withContext """{"error":"无法读取文件: $path"}"""
            }

            val lines = runCatching { file.readLines() }.getOrElse { e ->
                return@withContext """{"error":"读取文件失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }

            val filteredLines = if (pattern.isNotEmpty()) {
                lines.filter { it.contains(pattern, ignoreCase = true) }
            } else {
                lines
            }

            val result = analyzeLogContent(filteredLines, groupBy)
            buildString {
                appendLine("""{"path":"$path","total_lines":${lines.size},"filtered_lines":${filteredLines.size},"group_by":"$groupBy","analysis":""")
                appendLine(result.replace("\"", "\\\""))
                appendLine("\"}")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  内部实现
// ─────────────────────────────────────────────────────────────

private fun analyzeLogContent(lines: List<String>, groupBy: String): String {
    // 按类型分类
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val exceptions = mutableListOf<String>()
    val others = mutableListOf<String>()

    val errorPatterns = listOf("ERROR", "FATAL", "CRITICAL", "SEVERE")
    val warningPatterns = listOf("WARN", "WARNING")
    val exceptionPatterns = listOf("Exception", "Throwable", "at ", "Caused by")

    for (line in lines) {
        val upper = line.uppercase()
        when {
            errorPatterns.any { upper.contains(it) } -> errors.add(line)
            exceptionPatterns.any { line.contains(it) } -> exceptions.add(line)
            warningPatterns.any { upper.contains(it) } -> warnings.add(line)
            else -> others.add(line)
        }
    }

    // 按 group_by 过滤
    val result = when (groupBy) {
        "error" -> mapOf("errors" to errors, "exceptions" to exceptions)
        "warning" -> mapOf("warnings" to warnings)
        else -> mapOf(
            "errors" to errors,
            "warnings" to warnings,
            "exceptions" to exceptions,
            "others" to others
        )
    }

    return buildString {
        appendLine("{")
        result.forEach { (key, list) ->
            appendLine("  \"$key\": {")
            appendLine("    \"count\": ${list.size},")
            val samples = list.take(20).joinToString("\n    ") { "\"${it.replace("\"", "\\\"")}\"" }
            appendLine("    \"samples\": [")
            append(samples)
            if (list.size > 20) {
                appendLine(",")
                appendLine("      \"... (还有 ${list.size - 20} 条)\"")
            } else if (list.isNotEmpty()) {
                appendLine()
            }
            appendLine("    ]")
            appendLine("  },")
        }
        appendLine("}")
    }
}
