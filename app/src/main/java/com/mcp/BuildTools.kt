package com.mcp

import android.content.Context
import com.mcp.CapabilityRegistry
import com.mcp.toolbox.tool
import com.mcp.toolbox.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 构建与测试工具集。
 *
 * 提供 Gradle 构建、测试运行、依赖查看等功能。
 * 所有工具通过 run_bash 执行 Gradle 命令，输出解析后返回结构化结果。
 */
fun buildTools(context: Context): List<ToolDef> = listOf(
    gradleBuildTool(context),
    gradleTestTool(context),
    gradleDependenciesTool(context)
)

/**
 * 执行 Gradle 构建任务。
 *
 * 参数：
 * - task (必填): 构建任务名称，如 assembleDebug, assembleRelease, clean 等
 * - module (可选): 模块路径，如 :app, :tool-compiler。不填则在整个项目根执行
 *
 * 返回：构建输出摘要（成功/失败 + 日志尾部）
 */
fun gradleBuildTool(context: Context): ToolDef = tool("gradle_build") {
    description = """
        执行 Gradle 构建任务（assembleDebug、assembleRelease、clean 等），**同步阻塞**等待完成，返回结构化摘要（成功/失败 + 最后 50 行输出）。
        首选：需要**立即拿到构建结果**并继续下一步（如紧接着 gradle_test、install APK）时用它。
        超时自动转异步：构建耗时超过等待上限（默认 105 秒，可传 timeout 覆盖，上限 105）时，**进程不中断、不重新编译**，自动移交 AsyncTaskManager 并返回 `{"status":"running","task_id":"..."}`，用 async_task_status 轮询最终结果。
        不要用于短命令：请用 run_bash（300 秒超时，同步等待）。
        不要用于**预计 > 2 分钟**的构建（会撞宿主 120 秒响应上限）、或需要**与别的任务并行**、或需要**随时中断**的场景：请改用 async_task_submit（无超时、异步轮询、可 async_task_cancel）——超过 120 秒的构建应默认走它。
    """.trimIndent()
    string("task") {
        description = "构建任务名称，如 assembleDebug、assembleRelease、clean"
        required = true
    }
    string("module") {
        description = "模块路径，如 :app、:tool-compiler（可选，不填则在整个项目根执行）"
        required = false
    }
    string("project_root") {
        description = "Gradle 项目根目录绝对路径（可选；不填则自动探测常见位置或沿用上次使用的项目）"
        required = false
    }
    string("timeout") {
        description = "超时秒数（可选，纯数字）；不传默认 105，上限 105（宿主响应限制）。超时后进程不中断，自动转异步返回 task_id"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val task = args.requireStr("task", "gradle_build")
            val module = args["module"]?.jsonPrimitive?.content.orEmpty()
            val resolution = resolveGradleProjectRoot(
                context, (args["project_root"] as? JsonPrimitive)?.content.orEmpty())
            if (resolution.error != null) return@withContext resolution.error
            val projectRoot = resolution.root!!

            val cmd = buildGradleCommand(projectRoot, module, task)
            // 用 subprocess 非阻塞启动 Gradle，有界等待（默认 105 秒，留余量给宿主 120 秒响应上限）。
            // 完成则读日志返回结构化摘要；未完成则移交 AsyncTaskManager 返回 task_id——
            // 进程不中断、不重新编译，调用方可轮询 async_task_status 拿最终结果。
            // 构建日志集中到 gradle_logs/ 并顺手清理 1 小时前的旧文件。
            // 原先直接写 filesDir 根、文件名带时间戳，每次构建新增一个且从不删除；
            // Gradle 输出动辄数 MB～数十 MB，长期累积很可观。
            val logDir = File(context.filesDir, "gradle_logs").apply { mkdirs() }
            runCatching {
                val cutoff = System.currentTimeMillis() - 3_600_000L
                logDir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) f.delete() }
                // 历史遗留：早期版本把构建日志直接散落在 filesDir 根，一并回收
                context.filesDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith("gradle_build_") &&
                        f.name.endsWith(".log") && f.lastModified() < cutoff
                    ) f.delete()
                }
            }
            val logFile = File(logDir, "gradle_build_${System.currentTimeMillis()}.log")
            val script = "set -o pipefail\n$cmd"
            // 按 root 状态选后端（root 模式 + 可用 su → 原生 chroot，否则 PRoot）
            CapabilityRegistry.selectBackend(context)
            val process = CapabilityRegistry.subprocess.start(context, script, logFile)
            val waitSecs = (args["timeout"]?.jsonPrimitive?.content?.toIntOrNull() ?: 105).coerceIn(1, 105)
            val completed = process.waitFor(waitSecs.toLong(), TimeUnit.SECONDS)
            if (completed) {
                val output = runCatching { logFile.readText(Charsets.UTF_8) }.getOrDefault("")
                runCatching { process.destroy() }
                parseBuildResult(output, task, module)
            } else {
                // 进程仍在跑——移交 AsyncTaskManager，返回 task_id 供轮询（进程不中断）
                val asyncTask = com.mcp.AsyncTaskManager.submit("bash", "gradle_build: $task") { handle ->
                    handle.meta("log_file", logFile.absolutePath)
                    handle.onCancel { runCatching { process.destroyForcibly() } }
                    while (true) {
                        val exited = process.waitFor(800, TimeUnit.MILLISECONDS)
                        if (handle.isCancelled()) {
                            runCatching { process.destroyForcibly() }
                            return@submit "cancelled"
                        }
                        if (exited) break
                    }
                    val exitCode = process.exitValue()
                    if (exitCode != 0) throw IllegalStateException("exit_code=$exitCode")
                    "exit_code=$exitCode"
                }
                """{"status":"running","task_id":"${asyncTask.id}","message":"构建仍在后台进行，用 async_task_status 查询进度"}"""
            }
        }
    }
}

/**
 * 运行 Gradle 测试套件。
 *
 * 参数：
 * - module (可选): 模块路径，如 :app。不填则运行所有模块测试
 * - test_class (可选): 指定单个测试类（全限定名），如 com.mcp.FileToolsTest
 *
 * 返回：测试结果摘要（通过/失败/跳过数量 + 失败详情）
 */
fun gradleTestTool(context: Context): ToolDef = tool("gradle_test") {
    description = "运行测试套件，返回测试结果摘要（通过/失败/跳过数量）和失败详情。"
    string("module") {
        description = "模块路径，如 :app、:tool-compiler（可选，不填则运行所有模块测试）"
        required = false
    }
    string("test_class") {
        description = "指定单个测试类（全限定名），如 com.mcp.FileToolsTest（可选）"
        required = false
    }
    string("project_root") {
        description = "Gradle 项目根目录绝对路径（可选；不填则自动探测常见位置或沿用上次使用的项目）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val module = args["module"]?.jsonPrimitive?.content.orEmpty()
            val testClass = args["test_class"]?.jsonPrimitive?.content.orEmpty()
            val resolution = resolveGradleProjectRoot(
                context, (args["project_root"] as? JsonPrimitive)?.content.orEmpty())
            if (resolution.error != null) return@withContext resolution.error
            val projectRoot = resolution.root!!

            val task = if (testClass.isNotEmpty()) {
                val modulePrefix = if (module.isNotEmpty()) "$module:" else ""
                "${modulePrefix}test --tests $testClass"
            } else {
                val modulePrefix = if (module.isNotEmpty()) "$module:" else ""
                "${modulePrefix}test"
            }
            val cmd = buildGradleCommand(projectRoot, "", task)
            val result = runBashCommand(context, cmd, timeoutSeconds = 600)

            parseTestResult(result, testClass)
        }
    }
}

/**
 * 查看 Gradle 依赖树。
 *
 * 参数：
 * - module (可选): 模块路径，如 :app。不填则查看根项目依赖
 * - configuration (可选): 依赖配置名称，如 implementation、compileClasspath。不填则显示所有配置
 *
 * 返回：依赖树文本（截断至 2000 行）
 */
fun gradleDependenciesTool(context: Context): ToolDef = tool("gradle_dependencies") {
    description = "查看项目依赖树。可指定模块和配置。返回依赖树文本（截断至 2000 行）。"
    string("module") {
        description = "模块路径，如 :app、:tool-compiler（可选，不填则查看根项目）"
        required = false
    }
    string("configuration") {
        description = "依赖配置名称，如 implementation、compileClasspath、testImplementation（可选，不填则显示所有配置）"
        required = false
    }
    string("project_root") {
        description = "Gradle 项目根目录绝对路径（可选；不填则自动探测常见位置或沿用上次使用的项目）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val module = args["module"]?.jsonPrimitive?.content.orEmpty()
            val configuration = args["configuration"]?.jsonPrimitive?.content.orEmpty()
            val resolution = resolveGradleProjectRoot(
                context, (args["project_root"] as? JsonPrimitive)?.content.orEmpty())
            if (resolution.error != null) return@withContext resolution.error
            val projectRoot = resolution.root!!

            val task = if (configuration.isNotEmpty()) {
                "${module}dependencies --configuration $configuration".trimStart(':')
            } else {
                "${module}dependencies".trimStart(':')
            }
            val cmd = buildGradleCommand(projectRoot, "", task)
            val result = runBashCommand(context, cmd, timeoutSeconds = 120)

            parseDependenciesResult(result)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  内部辅助函数
// ─────────────────────────────────────────────────────────────

/** 解析结果：[root] 非 null 表示成功；否则 [error] 为可直接返回给模型的错误 JSON。 */
internal class ProjectRootResolution(val root: File?, val error: String?)

/** 自动探测时跳过的媒体/应用私有目录名。 */
private val PROJECT_SCAN_SKIP = setOf(
    "Android", "DCIM", "Download", "Downloads", "Movies", "Music", "Pictures",
    "Recordings", "Alarms", "Notifications", "Podcasts", "Ringtones", "MIUI", "tencent"
)

private const val PREF_TOOL_ENV = "tool_env"
private const val KEY_LAST_GRADLE_ROOT = "last_gradle_project_root"

/**
 * 定位 Gradle 项目根目录（gradle_* / dependency_* / lint_* 工具共用）。按优先级：
 *  1. 显式 [explicitRoot]（project_root 参数）；
 *  2. 上次成功使用的根目录（SharedPreferences 记忆，同一项目反复构建免重复扫描）；
 *  3. 扫描常见外部存储位置的一级子目录，取最近修改的含 gradlew 项目。
 *
 * 旧实现 `filesDir.parentFile×3` 是拿包名路径深度硬猜（得到 /data/user），
 * 项目放在外部存储时永远报「gradlew 不存在于 /data/user」且无法纠正。
 */
internal fun resolveGradleProjectRoot(context: Context, explicitRoot: String): ProjectRootResolution {
    // 1) 显式指定：最可信，校验后记住
    if (explicitRoot.isNotBlank()) {
        val dir = File(explicitRoot)
        if (!dir.isDirectory) {
            return ProjectRootResolution(null, """{"error":"project_root 不是目录: $explicitRoot"}""")
        }
        if (!File(dir, "gradlew").exists()) {
            return ProjectRootResolution(null, """{"error":"$explicitRoot 下没有 gradlew，请确认传的是 Gradle 项目根目录"}""")
        }
        File(dir, "gradlew").setExecutable(true)
        context.getSharedPreferences(PREF_TOOL_ENV, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_GRADLE_ROOT, dir.absolutePath).apply()
        return ProjectRootResolution(dir, null)
    }

    val prefs = context.getSharedPreferences(PREF_TOOL_ENV, Context.MODE_PRIVATE)

    // 2) 上次记忆（项目可能已被删，仍需校验 gradlew 存在）
    prefs.getString(KEY_LAST_GRADLE_ROOT, null)?.let { path ->
        if (File(path, "gradlew").exists()) return ProjectRootResolution(File(path), null)
    }

    // 3) 扫描常见位置的一级子目录，取最近修改的项目
    val candidates = listOf(
        "/storage/emulated/0",
        "/storage/emulated/0/Work",
        "/storage/emulated/0/Projects",
        "/sdcard"
    )
    val found = candidates.asSequence()
        .distinct()
        .map(::File).filter { it.isDirectory }
        .flatMap { parent -> parent.listFiles()?.asSequence() ?: emptySequence() }
        .filter { it.isDirectory && it.name !in PROJECT_SCAN_SKIP && File(it, "gradlew").exists() }
        .maxByOrNull { it.lastModified() }
        ?: return ProjectRootResolution(
            null,
            """{"error":"未自动找到 Gradle 项目（已扫描 ${candidates.joinToString()} 的一级子目录）。请在调用时传 project_root 参数指定项目根目录"}"""
        )
    File(found, "gradlew").setExecutable(true)
    prefs.edit().putString(KEY_LAST_GRADLE_ROOT, found.absolutePath).apply()
    return ProjectRootResolution(found, null)
}

/**
 * 探测 PRoot（guest）里的 JDK 根目录；找不到返回 null。
 *
 * 为什么必须显式探测：executeBash 把脚本送进 PRoot Debian 执行，而 guest 里 `java`
 * **不在 PATH 上**——JDK 是解压在 /opt 下的独立目录（如 /opt/jdk17），只有显式导出
 * JAVA_HOME + PATH 才能被 gradlew 找到。缺了这一步，任何 gradle_* 工具都会以
 *   「ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.」
 * 失败，而模型/用户从错误信息里看不出「其实装了 JDK，只是没告诉 gradle 在哪」。
 *
 * 注意这里只是**拼进 shell 脚本**，不依赖 Kotlin 侧能否访问这些路径：探测命令本身也在
 * PRoot 里跑，和 gradlew 看到的是同一个文件系统。
 */
// 本常量被同包的 QualityTools / DependencyTools 共享：凡是要在 PRoot 里跑 gradlew 的地方
// 都必须先导出 JAVA_HOME，否则一律以「JAVA_HOME is not set」失败。
internal const val JDK_PROBE_SCRIPT = """
  jdk="${'$'}{JAVA_HOME:-}"
  if [ -z "${'$'}jdk" ] || [ ! -x "${'$'}jdk/bin/java" ]; then
    jdk=""
    for c in /opt/jdk* /opt/java* /usr/lib/jvm/*; do
      [ -x "${'$'}c/bin/java" ] || continue
      # 取版本号最大者：按路径排序即可（jdk17 < jdk21，java-17 < java-21）
      if [ -z "${'$'}jdk" ] || [ "${'$'}c" \> "${'$'}jdk" ]; then jdk="${'$'}c"; fi
    done
  fi
  if [ -n "${'$'}jdk" ]; then
    export JAVA_HOME="${'$'}jdk"
    export PATH="${'$'}jdk/bin:${'$'}PATH"
    # 显式补 JDK 自带库（libjli.so 等）搜索路径。
    # Java 启动器通常靠 /proc/self/exe 反推安装目录定位这些库；沙盒已不再整体
    # bind /proc（/proc/self 不存在），自动路径失效会报 libjli.so 找不到。
    # 这里按探测到的 JAVA_HOME 直接拼搜索路径，不依赖 /proc。
    export LD_LIBRARY_PATH="${'$'}jdk/lib:${'$'}jdk/lib/server:${'$'}{LD_LIBRARY_PATH:-}"
  fi
"""
private fun buildGradleCommand(projectRoot: File, module: String, task: String): String {
    val modulePrefix = if (module.isNotEmpty()) "$module:" else ""
    val fullTask = "$modulePrefix$task".trimStart(':')
    // 用 `sh ./gradlew` 而非直接执行：FUSE 挂载（/storage/emulated）上 exec 位不可靠，
    // 直接 ./gradlew 会 Permission denied；sh 解释执行对两种情况都兼容。
    //
    // 先跑 JDK 探测再执行 gradlew：见 [JDK_PROBE_SCRIPT] 的说明——不导出 JAVA_HOME，
    // gradle_* 工具在 PRoot 里必然报「JAVA_HOME is not set」。
    return """
        |cd '${projectRoot.absolutePath}'
        |$JDK_PROBE_SCRIPT
        |sh ./gradlew $fullTask --no-daemon
    """.trimMargin()
}

/**
 * 通过 run_bash 执行命令。
 * 复用 AndroidTools 的 runBash 逻辑。
 */
private fun runBashCommand(context: Context, command: String, timeoutSeconds: Int): String {
    return try {
        val script = """
            |#!/bin/bash
            |set -o pipefail
            |$command
        """.trimMargin()
        executeBash(context, script, timeoutSeconds = timeoutSeconds.toLong())
    } catch (e: Exception) {
        """{"error":"执行命令失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
    }
}

/**
 * 解析构建结果。
 */
private fun parseBuildResult(output: String, task: String, module: String): String {
    // 检查是否成功
    val successKeywords = listOf("BUILD SUCCESSFUL", "Task :", "BUILD SUCCESS")
    val failureKeywords = listOf("BUILD FAILED", "FAILURE", "FAILED")

    val isSuccess = successKeywords.any { output.contains(it) }
    val isFailure = failureKeywords.any { output.contains(it) }

    // 提取最后 50 行
    val lines = output.split("\n")
    val tail = lines.takeLast(50).joinToString("\n")

    val status = when {
        isSuccess -> "success"
        isFailure -> "failed"
        else -> "unknown"
    }

    return buildString {
        appendLine("""{"status":"$status","task":"$task"""")
        if (module.isNotEmpty()) appendLine(""","module":"$module"""")
        appendLine(""","tail":""")
        appendLine(tail.replace("\"", "\\\""))
        appendLine("""","full_output_length":${lines.size}}""")
    }
}

/**
 * 解析测试结果。
 */
private fun parseTestResult(output: String, testClass: String): String {
    val lines = output.split("\n")

    // 提取测试计数
    val passPattern = Regex("""Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)""")
    var total = 0
    var failures = 0
    var errors = 0
    var skipped = 0

    for (line in lines) {
        val match = passPattern.find(line)
        if (match != null) {
            total = match.groupValues[1].toIntOrNull() ?: 0
            failures = match.groupValues[2].toIntOrNull() ?: 0
            errors = match.groupValues[3].toIntOrNull() ?: 0
            skipped = match.groupValues[4].toIntOrNull() ?: 0
            break
        }
    }

    // 提取失败详情
    val failureLines = lines.filter { it.contains("FAILED") || it.contains("Error") || it.contains("Exception") }
    val failuresSummary = failureLines.take(20).joinToString("\n")

    val passed = total - failures - errors - skipped

    return buildString {
        appendLine("""{"total":$total,"passed":$passed,"failures":$failures,"errors":$errors,"skipped":$skipped""")
        if (testClass.isNotEmpty()) appendLine(""","test_class":"$testClass"""")
        if (failures + errors > 0) {
            appendLine(""","failures_summary":""")
            appendLine(failuresSummary.replace("\"", "\\\""))
            appendLine("\"")
        }
        appendLine("}")
    }
}

/**
 * 解析依赖树结果。
 */
private fun parseDependenciesResult(output: String): String {
    val lines = output.split("\n")
    // 截断至 2000 行
    val truncated = if (lines.size > 2000) {
        lines.take(2000) + listOf("... (截断，共 ${lines.size} 行)")
    } else {
        lines
    }
    val content = truncated.joinToString("\n")

    return buildString {
        appendLine("""{"total_lines":${lines.size},"content":""")
        appendLine(content.replace("\"", "\\\""))
        appendLine("\"}")
    }
}
