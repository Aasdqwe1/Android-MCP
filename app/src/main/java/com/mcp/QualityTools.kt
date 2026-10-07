package com.mcp

import android.content.Context
import com.mcp.toolbox.tool
import com.mcp.toolbox.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 代码质量工具集。
 *
 * 提供 Lint 检查和代码格式化功能。
 * 通过 run_bash 执行 ktlint 或 detekt。
 */
fun qualityTools(context: Context): List<ToolDef> = listOf(
    lintCheckTool(context),
    formatCodeTool(context)
)

/**
 * 运行 Lint 检查。
 *
 * 参数：
 * - path (可选): 指定文件或目录路径。不填则检查整个项目
 * - tool (可选): lint 工具，ktlint（默认）或 detekt
 *
 * 返回：检查结果（问题列表）
 */
fun lintCheckTool(context: Context): ToolDef = tool("lint_check") {
    description = "运行 ktlint/detekt 检查代码风格。返回发现的问题列表（按严重程度分组）。"
    string("path") {
        description = "指定文件或目录路径（可选，不填则检查整个项目）"
        required = false
    }
    string("tool") {
        description = "Lint 工具：ktlint（默认）或 detekt"
        required = false
        enumValues = listOf("ktlint", "detekt")
    }
    string("project_root") {
        description = "Gradle 项目根目录绝对路径（可选；不填则自动探测常见位置或沿用上次使用的项目）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val targetPath = args["path"]?.jsonPrimitive?.content.orEmpty()
            val tool = args["tool"]?.jsonPrimitive?.content?.lowercase() ?: "ktlint"
            val resolution = resolveGradleProjectRoot(
                context, (args["project_root"] as? JsonPrimitive)?.content.orEmpty())
            if (resolution.error != null) return@withContext resolution.error
            val projectRoot = resolution.root!!

            val result = when (tool) {
                "detekt" -> runDetekt(context, projectRoot, targetPath)
                else -> runKtlint(context, projectRoot, targetPath)
            }
            result
        }
    }
}

/**
 * 自动格式化代码。
 *
 * 参数：
 * - path (可选): 指定文件或目录路径。不填则格式化整个项目
 * - tool (可选): 格式化工具，ktlint（默认）
 *
 * 返回：格式化结果（修改的文件列表）
 */
fun formatCodeTool(context: Context): ToolDef = tool("format_code") {
    description = "自动格式化 Kotlin/Java/XML 代码。返回格式化结果（修改的文件列表）。"
    string("path") {
        description = "指定文件或目录路径（可选，不填则格式化整个项目）"
        required = false
    }
    string("tool") {
        description = "格式化工具：ktlint（默认）"
        required = false
        enumValues = listOf("ktlint")
    }
    string("project_root") {
        description = "Gradle 项目根目录绝对路径（可选；不填则自动探测常见位置或沿用上次使用的项目）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val targetPath = args["path"]?.jsonPrimitive?.content.orEmpty()
            val tool = args["tool"]?.jsonPrimitive?.content?.lowercase() ?: "ktlint"
            val resolution = resolveGradleProjectRoot(
                context, (args["project_root"] as? JsonPrimitive)?.content.orEmpty())
            if (resolution.error != null) return@withContext resolution.error
            val projectRoot = resolution.root!!

            when (tool) {
                "ktlint" -> runKtlintFormat(context, projectRoot, targetPath)
                else -> """{"error":"不支持的格式化工具: $tool"}"""
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  内部实现
// ─────────────────────────────────────────────────────────────

/**
 * 检查 ktlint 是否已安装，未安装则自动下载。
 */
private fun ensureKtlint(context: Context): File? {
    val cacheDir = context.cacheDir
    val ktlintJar = File(cacheDir, "ktlint")
    if (ktlintJar.exists() && ktlintJar.canExecute()) {
        return ktlintJar
    }

    // 尝试从 assets 复制
    return runCatching {
        context.assets.open("ktlint").use { input ->
            ktlintJar.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        ktlintJar.setExecutable(true)
        ktlintJar
    }.getOrNull()
}

private fun runKtlint(context: Context, projectRoot: File, targetPath: String): String {
    val ktlint = ensureKtlint(context)
    if (ktlint == null) {
        return """{"error":"ktlint 未安装且无法从 assets 提取。请手动安装或使用 Gradle ktlint 插件"}"""
    }

    val target = if (targetPath.isNotEmpty()) {
        File(targetPath).absolutePath
    } else {
        projectRoot.absolutePath
    }

    val script = """
        |#!/bin/bash
        |cd '${projectRoot.absolutePath}'
        |java -jar '${ktlint.absolutePath}' '$target' 2>&1
    """.trimMargin()

    val result = runBashCommand(context, script, 120)
    return parseLintResult(result, "ktlint")
}

private fun runDetekt(context: Context, projectRoot: File, targetPath: String): String {
    // detekt 通过 Gradle 运行
    val gradlew = File(projectRoot, "gradlew")
    if (!gradlew.exists()) {
        return """{"error":"gradlew 不存在于 $projectRoot"}"""
    }
    gradlew.setExecutable(true)

    val target = if (targetPath.isNotEmpty()) {
        "--input $targetPath"
    } else {
        ""
    }

    val script = """
        |#!/bin/bash
        |cd '${projectRoot.absolutePath}'
        |$JDK_PROBE_SCRIPT
        |sh ./gradlew detekt $target --no-daemon 2>&1
    """.trimMargin()

    val result = runBashCommand(context, script, 180)
    return parseLintResult(result, "detekt")
}

private fun runKtlintFormat(context: Context, projectRoot: File, targetPath: String): String {
    val ktlint = ensureKtlint(context)
    if (ktlint == null) {
        return """{"error":"ktlint 未安装且无法从 assets 提取"}"""
    }

    val target = if (targetPath.isNotEmpty()) {
        File(targetPath).absolutePath
    } else {
        projectRoot.absolutePath
    }

    val script = """
        |#!/bin/bash
        |cd '${projectRoot.absolutePath}'
        |java -jar '${ktlint.absolutePath}' -F '$target' 2>&1
    """.trimMargin()

    val result = runBashCommand(context, script, 120)
    return parseFormatResult(result)
}

private fun parseLintResult(output: String, tool: String): String {
    val lines = output.split("\n")
    val errorLines = lines.filter { it.contains("error") || it.contains("ERROR") || it.contains("warning") || it.contains("WARNING") }
    val totalErrors = errorLines.size

    return buildString {
        appendLine("""{"tool":"$tool","total_issues":$totalErrors,"issues":""")
        val issues = errorLines.take(50).joinToString("\n").replace("\"", "\\\"")
        appendLine(issues)
        if (totalErrors > 50) {
            appendLine("\n... (还有 ${totalErrors - 50} 个问题)")
        }
        appendLine("\"}")
    }
}

private fun parseFormatResult(output: String): String {
    val lines = output.split("\n")
    val formatted = lines.filter { it.contains("Formatted") || it.contains("Fixed") || it.contains("modified") }
    val totalFormatted = formatted.size

    return buildString {
        appendLine("""{"formatted_files":$totalFormatted,"detail":""")
        val detail = formatted.take(30).joinToString("\n").replace("\"", "\\\"")
        appendLine(detail)
        if (totalFormatted > 30) {
            appendLine("\n... (还有 ${totalFormatted - 30} 个文件)")
        }
        appendLine("\"}")
    }
}

/**
 * 通过 run_bash 执行命令的辅助函数。
 */
private fun runBashCommand(context: Context, script: String, timeoutSeconds: Int): String {
    return try {
        executeBash(context, script, timeoutSeconds = timeoutSeconds.toLong())
    } catch (e: Exception) {
        """{"error":"执行命令失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
    }
}
