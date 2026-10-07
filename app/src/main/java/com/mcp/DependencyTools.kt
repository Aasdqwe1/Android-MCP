package com.mcp

import android.content.Context
import com.mcp.toolbox.tool
import com.mcp.toolbox.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URL
import java.net.HttpURLConnection

/**
 * 依赖管理工具集。
 *
 * 提供项目依赖的查看、更新和建议功能。
 */
fun dependencyTools(context: Context): List<ToolDef> = listOf(
    listDependenciesTool(context),
    updateDependencyTool(context),
    suggestUpdatesTool(context)
)

/**
 * 查看项目依赖列表。
 *
 * 参数：
 * - module (可选): 模块路径，如 :app
 * - group (可选): 过滤分组，如 org.jetbrains.kotlin
 *
 * 返回：依赖列表
 */
fun listDependenciesTool(context: Context): ToolDef = tool("list_dependencies") {
    description = "查看项目依赖列表（按模块分组）。返回依赖树结构。"
    string("module") {
        description = "模块路径，如 :app、:tool-compiler（可选）"
        required = false
    }
    string("group") {
        description = "过滤分组，如 org.jetbrains.kotlin（可选）"
        required = false
    }
    string("project_root") {
        description = "Gradle 项目根目录绝对路径（可选；不填则自动探测常见位置或沿用上次使用的项目）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val module = args["module"]?.jsonPrimitive?.content.orEmpty()
            val group = args["group"]?.jsonPrimitive?.content.orEmpty()
            val resolution = resolveGradleProjectRoot(
                context, (args["project_root"] as? JsonPrimitive)?.content.orEmpty())
            if (resolution.error != null) return@withContext resolution.error
            val projectRoot = resolution.root!!

            val task = if (module.isNotEmpty()) {
                "${module}:dependencies"
            } else {
                "dependencies"
            }

            val script = """
                |#!/bin/bash
                |cd '${projectRoot.absolutePath}'
                |$JDK_PROBE_SCRIPT
                |sh ./gradlew $task --no-daemon 2>&1
            """.trimMargin()

            val result = runBashCommand(context, script, 120)
            parseDependencyList(result, group)
        }
    }
}

/**
 * 更新依赖版本。
 *
 * 参数：
 * - dependency (必填): 依赖坐标，如 org.jetbrains.kotlin:kotlin-stdlib
 * - new_version (必填): 新版本号
 * - build_file (可选): build.gradle.kts 文件路径，不填则自动查找
 *
 * 返回：更新结果
 */
fun updateDependencyTool(context: Context): ToolDef = tool("update_dependency") {
    description = "更新指定库的版本号。自动在 build.gradle.kts 中替换版本字符串。"
    string("dependency") {
        description = "依赖坐标，如 org.jetbrains.kotlin:kotlin-stdlib"
        required = true
    }
    string("new_version") {
        description = "新版本号，如 2.0.21"
        required = true
    }
    string("build_file") {
        description = "build.gradle.kts 文件路径（可选，不填则自动查找）"
        required = false
    }
    string("project_root") {
        description = "Gradle 项目根目录绝对路径（可选；不填则自动探测常见位置或沿用上次使用的项目）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val dependency = args.requireStr("dependency", "update_dependency")
            val newVersion = args.requireStr("new_version", "update_dependency")
            val buildFile = args["build_file"]?.jsonPrimitive?.content.orEmpty()

            val resolution = resolveGradleProjectRoot(
                context, (args["project_root"] as? JsonPrimitive)?.content.orEmpty())
            if (resolution.error != null) return@withContext resolution.error
            val projectRoot = resolution.root!!

            // 查找 build.gradle.kts
            val buildFiles = findBuildFiles(projectRoot)
            val targetFile = if (buildFile.isNotEmpty()) {
                File(buildFile).takeIf { it.exists() }
            } else {
                // 优先使用 app 模块的 build.gradle.kts
                buildFiles.find { it.absolutePath.contains("/app/") } ?: buildFiles.firstOrNull()
            }

            if (targetFile == null || !targetFile.exists()) {
                return@withContext """{"error":"找不到 build.gradle.kts 文件"}"""
            }

            val content = runCatching { targetFile.readText() }.getOrElse { e ->
                return@withContext """{"error":"读取文件失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }

            // 匹配依赖版本
            val (depName, groupId, artifactId) = parseDependencyCoordinates(dependency)
            if (depName.isEmpty()) {
                return@withContext """{"error":"无法解析依赖坐标: $dependency，请使用 group:artifact 格式"}"""
            }

            // 查找版本号
            val versionPattern = Regex("""$artifactId[:\s]+"([\d.]+)"""")
            val versionMatch = versionPattern.find(content)

            if (versionMatch == null) {
                return@withContext """{"error":"在 ${targetFile.name} 中找不到 $dependency 的版本定义"}"""
            }

            val oldVersion = versionMatch.groupValues[1]
            val newContent = content.replace(versionMatch.value, "$artifactId:\"$newVersion\"")

            runCatching { targetFile.writeText(newContent) }.getOrElse { e ->
                return@withContext """{"error":"写入文件失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }

            """{"dependency":"$dependency","old_version":"$oldVersion","new_version":"$newVersion","file":"${targetFile.absolutePath}","status":"updated"}"""
        }
    }
}

/**
 * 检查依赖是否有更新版本。
 *
 * 参数：
 * - module (可选): 模块路径
 * - outdated_only (可选): 是否只返回过时依赖，默认 true
 *
 * 返回：过时依赖列表（含最新版本）
 */
fun suggestUpdatesTool(context: Context): ToolDef = tool("suggest_updates") {
    description = "检查依赖是否有更新版本（基于 Maven Central）。返回过时依赖列表。"
    string("module") {
        description = "模块路径，如 :app（可选）"
        required = false
    }
    boolean("outdated_only") {
        description = "是否只返回过时依赖，默认 true"
        required = false
    }
    string("project_root") {
        description = "Gradle 项目根目录绝对路径（可选；不填则自动探测常见位置或沿用上次使用的项目）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val module = args["module"]?.jsonPrimitive?.content.orEmpty()
            val outdatedOnly = args["outdated_only"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

            val resolution = resolveGradleProjectRoot(
                context, (args["project_root"] as? JsonPrimitive)?.content.orEmpty())
            if (resolution.error != null) return@withContext resolution.error
            val projectRoot = resolution.root!!

            val task = if (module.isNotEmpty()) {
                "${module}:dependencies"
            } else {
                "dependencies"
            }

            val script = """
                |#!/bin/bash
                |cd '${projectRoot.absolutePath}'
                |$JDK_PROBE_SCRIPT
                |sh ./gradlew $task --no-daemon 2>&1
            """.trimMargin()

            val result = runBashCommand(context, script, 120)
            val dependencies = extractDependencies(result)

            if (dependencies.isEmpty()) {
                return@withContext """{"message":"未找到依赖","outdated":[]}"""
            }

            // 检查每个依赖的最新版本
            val outdated = mutableListOf<Map<String, String>>()
            for (dep in dependencies) {
                val latest = queryMavenLatest(dep["groupId"] ?: "", dep["artifactId"] ?: "")
                if (latest != null && latest != dep["version"]) {
                    outdated.add(mapOf(
                        "dependency" to "${dep["groupId"]}:${dep["artifactId"]}",
                        "current" to (dep["version"] ?: ""),
                        "latest" to latest
                    ))
                }
            }

            val filtered = if (outdatedOnly) outdated else dependencies.map {
                mapOf(
                    "dependency" to "${it["groupId"]}:${it["artifactId"]}",
                    "version" to (it["version"] ?: "")
                )
            }

            buildString {
                appendLine("""{"outdated_count":${outdated.size},"outdated":[""")
                filtered.forEachIndexed { index, dep ->
                    val depStr = dep.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                    append("{$depStr}")
                    if (index < filtered.size - 1) append(",")
                }
                appendLine("]}")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  内部实现
// ─────────────────────────────────────────────────────────────

private fun findBuildFiles(projectRoot: File): List<File> {
    val result = mutableListOf<File>()
    val buildFile = File(projectRoot, "build.gradle.kts")
    if (buildFile.exists()) result.add(buildFile)

    val appBuild = File(projectRoot, "app/build.gradle.kts")
    if (appBuild.exists()) result.add(appBuild)

    // 查找其他模块
    val subdirs = projectRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
    for (dir in subdirs) {
        val moduleBuild = File(dir, "build.gradle.kts")
        if (moduleBuild.exists() && !result.contains(moduleBuild)) {
            result.add(moduleBuild)
        }
    }
    return result
}

private fun parseDependencyCoordinates(dependency: String): Triple<String, String, String> {
    val parts = dependency.split(":")
    if (parts.size < 2) return Triple("", "", "")
    val groupId = parts[0]
    val artifactId = parts[1]
    return Triple("$groupId:$artifactId", groupId, artifactId)
}

private fun extractDependencies(output: String): List<Map<String, String>> {
    val result = mutableListOf<Map<String, String>>()
    val lines = output.split("\n")

    // 匹配依赖行：如 org.jetbrains.kotlin:kotlin-stdlib:1.9.0
    val depPattern = Regex("""([\w.-]+):([\w.-]+):([\d.]+)""")
    for (line in lines) {
        val match = depPattern.find(line)
        if (match != null) {
            val groupId = match.groupValues[1]
            val artifactId = match.groupValues[2]
            val version = match.groupValues[3]
            // 过滤掉 Gradle 内部依赖
            if (!groupId.startsWith("com.android") && !groupId.startsWith("org.gradle")) {
                result.add(mapOf(
                    "groupId" to groupId,
                    "artifactId" to artifactId,
                    "version" to version
                ))
            }
        }
    }
    return result.distinctBy { "${it["groupId"]}:${it["artifactId"]}" }
}

private fun parseDependencyList(output: String, group: String): String {
    val lines = output.split("\n")

    // 找到 dependencies 开始标记
    val startIdx = lines.indexOfFirst { it.contains("dependencies") }
    if (startIdx == -1) {
        return """{"error":"无法解析依赖输出"}"""
    }

    val depLines = lines.drop(startIdx).take(500)
    val filtered = if (group.isNotEmpty()) {
        depLines.filter { it.contains(group) }
    } else {
        depLines
    }

    val content = filtered.joinToString("\n")
    return buildString {
        appendLine("""{"total_lines":${lines.size},"content":""")
        appendLine(content.replace("\"", "\\\""))
        appendLine("\"}")
    }
}

private fun queryMavenLatest(groupId: String, artifactId: String): String? {
    return try {
        val url = "https://search.maven.org/solrsearch/select?q=g:$groupId+AND+a:$artifactId&rows=1&wt=json"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"

        val response = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        // 解析 JSON 响应
        val latestPattern = Regex(""""latestVersion":"([^"]+)"""")
        val match = latestPattern.find(response)
        match?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }
}

private fun runBashCommand(context: Context, script: String, timeoutSeconds: Int): String {
    return try {
        executeBash(context, script, timeoutSeconds = timeoutSeconds.toLong())
    } catch (e: Exception) {
        """{"error":"执行命令失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
    }
}
