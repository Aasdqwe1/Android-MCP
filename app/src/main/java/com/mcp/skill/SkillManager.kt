package com.mcp.skill

import android.content.Context
import com.mcp.BashTaskManager
import com.mcp.CapabilityRegistry
import com.mcp.LogStore
import com.mcp.ProotEnvironment
import com.mcp.executeBash
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.ParamType
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File

/**
 * 技能管理系统 — 加载、发现、管理技能，并把技能绑定的脚本注册为真实 MCP 工具。
 *
 * 借鉴 browser-use 的 skill 机制：技能不止是 markdown 文档（知识），
 * 还可以声明可执行脚本（能力）。加载技能时，skill.json 里声明的每个脚本
 * 都会被注册成一个工具 `skill_<技能名>_<脚本名>`，LLM 可直接调用；
 * 脚本在 Debian PRoot 完整 Linux 环境里运行（复用 run_bash 执行通道）。
 *
 * skill.json 格式：
 * ```json
 * {
 *   "name": "git",
 *   "description": "...",
 *   "scripts": [
 *     {
 *       "name": "status",
 *       "description": "查看仓库状态",
 *       "entry": "scripts/status.sh",   // 脚本文件相对技能目录
 *       "interpreter": "bash",           // bash / sh / python3 / node
 *       "params": [{"name":"path","type":"string","description":"仓库路径","required":false}]
 *     }
 *   ]
 * }
 * ```
 *
 * 使用方式:
 * ```kotlin
 * val skillMgr = SkillManager(context, toolbox)
 * skillMgr.discoverAndLoad()   // 注册所有技能的脚本工具
 * ```
 */
class SkillManager(
    private val context: Context,
    private val toolbox: Toolbox
) {
    companion object {
        /** 依赖安装指纹的持久化文件：记录「某技能在当前环境世代下已装好的依赖」。 */
        private const val DEPS_PREF_FILE = "skill_deps"
        private const val DEPS_PREF_KEY_PREFIX = "installed_"

        /** 依赖声明文件名（技能目录下）。 */
        private const val PIP_REQUIREMENTS = "requirements.txt"
        private const val APT_REQUIREMENTS = "apt-requirements.txt"

        /** 安装成功哨兵：只在全部依赖真的装好后输出，用来区分「装好了」与「静默失败」。 */
        private const val DEPS_OK_SENTINEL = "__SKILL_DEPS_OK__"
    }

    private val skills = LinkedHashMap<String, SkillDefinition>()

    /** 所有已加载技能 */
    fun allSkills(): List<SkillDefinition> = skills.values.toList()

    /** 按名称查找技能 */
    fun getSkill(name: String): SkillDefinition? = skills[name]

    /** 技能是否已加载 */
    fun isLoaded(name: String): Boolean = skills.containsKey(name)

    /**
     * 从 assets 和 filesDir 扫描并加载所有技能，并把每个技能的脚本注册为工具。
     */
    fun discoverAndLoad() {
        // 0) 把 APK 内置技能（assets/skills/）种子解压到 filesDir/skills/，
        //    让已装 App 的内置技能也有稳定绝对目录（脚本/README 可被工具读取、引用），
        //    而不是只存在于 APK 内部。已存在的目录不覆盖（保护用户安装/修改过的技能）。
        seedBundledSkillsToFiles()

        // 1) 从 filesDir/skills/ 扫描用户安装的技能
        val userSkillsDir = File(context.filesDir, "skills")
        if (userSkillsDir.isDirectory()) {
            loadSkillsFromDir(userSkillsDir, sourcePrefix = "files")
        }

        // 2) 从 assets/skills/ 扫描内置技能
        try {
            val assets = context.assets.list("skills")
            if (assets != null) {
                for (dirName in assets) {
                    val skillDir = File(userSkillsDir, dirName)
                    // 如果用户目录已有同名的，跳过 assets（用户覆盖）
                    if (skillDir.isDirectory() && skillDir.listFiles()?.isNotEmpty() == true) continue
                    loadSkillFromAssets(dirName)
                }
            }
        } catch (_: Exception) { /* assets/skills/ 可能不存在 */ }

        // 3) 校验版本门槛与依赖（市场化分发前置条件：minApp / requires）
        validateAll()

        // 注意：这里**不再**安装技能的 Python 依赖（原先的第 4 步）。
        // installPythonDependencies() 走的是同步阻塞的 executeBash，而 PRoot 未就绪时它会
        // runBlocking 去解压整个 Debian rootfs；本方法的调用链是
        //   MainActivity.onCreate → ToolsFragment.onViewCreated → ToolRuntimeHolder.get
        //   → ToolRuntimeFactory.create → discoverAndLoad()
        // **全程在主线程**，首次启动于是整段黑屏卡住。
        // 依赖安装改由 PRoot 就绪后（初始化向导 / 设置页安装完成）在后台线程触发。

        // 4) 为所有技能注册脚本工具（以设置页持久化开关为准：
        //    SkillPreferences 是唯一事实来源，内存 def.enabled 仅反映当前会话状态）
        var scriptTools = 0
        for (def in skills.values) {
            def.enabled = com.mcp.core.skill.SkillPreferences
                .isEnabled(context, def.name)
            scriptTools += registerScriptTools(def)
        }
        LogStore.i("SKILL", "技能系统初始化完成，已加载 ${skills.size} 个技能、注册 $scriptTools 个脚本工具: ${skills.keys}")
    }

    /**
     * 安装技能声明的依赖（幂等：装好了就跳过）。
     *
     * 依赖**只在技能侧声明**，技能目录下两类文件（都按 [SkillDefinition.rootDirName] 查找，
     * 允许目录名与技能名不同）：
     *  - `requirements.txt` → pip 依赖；
     *  - `apt-requirements.txt` → 系统依赖（每行一个 apt 包，如 `git` / `smbclient`）。
     * 不再按技能名在 Kotlin 里硬编码包名——那正是 flask 被装两遍、声明与实现两处漂移的根因。
     *
     * 为什么需要系统依赖声明：随包 rootfs 只是 Debian minbase（**没有 python3 / pip3 / git / curl / node**），
     * pip 依赖与 git/smbclient 这类命令都得先 apt 装进来。技能声明了 pip 依赖时，
     * [SkillDependencyPlan] 会自动并入 `python3` + `python3-pip`，否则 requirements.txt 永远装不上。
     *
     * 「明明依赖都装好了还重复安装」的四处修正：
     *  1. 指纹短路：装成功后把 SHA-256(rootfs 版本 + pip 依赖 + 系统依赖) 记进 [DEPS_PREF_FILE]，
     *     下次进来指纹一致直接返回，**完全不进 PRoot**；清单变了或 rootfs 重装才真正重装；
     *  2. 无依赖的技能（文件不存在、或只有注释）不再空跑；
     *  3. 系统依赖逐包 `dpkg -s` 判断，缺了才 `apt-get update` + 安装（避免每次都刷新索引）；
     *  4. 不再用 `|| true` 吞掉失败：任一步失败就不输出哨兵、不写指纹，下次自动重试并告警。
     *
     * **必须在后台线程调用，且调用时 PRoot 环境应已就绪。** 内部走同步阻塞的 [executeBash]，
     * 环境未就绪时它会 runBlocking 去解压整个 Debian rootfs——放在主线程上就是首启黑屏。
     * 故此处再加一道防御：环境未就绪直接返回，绝不从这里触发安装。
     *
     * @param force 忽略已安装指纹强制重装（供「修复依赖」类入口使用）
     */
    fun installSkillDependencies(force: Boolean = false) {
        if (!CapabilityRegistry.shell.isReady(context)) {
            LogStore.d("SKILL", "PRoot 环境未就绪，跳过技能依赖安装（不在此处触发安装）")
            return
        }
        val skillsDir = File(context.filesDir, "skills")
        if (!skillsDir.isDirectory()) return
        val prefs = context.getSharedPreferences(DEPS_PREF_FILE, Context.MODE_PRIVATE)
        // 环境世代 = PRoot rootfs 版本目录名：重装/升级 rootfs 后指纹自动失效，依赖会重装。
        val epoch = ProotEnvironment.rootfsDir(context).name
        for (def in skills.values) {
            try {
                val pipFile = resolveDeclaredFile(skillsDir, def.rootDirName, PIP_REQUIREMENTS)
                val aptFile = resolveDeclaredFile(skillsDir, def.rootDirName, APT_REQUIREMENTS)
                val pipSpecs = pipFile?.let { SkillDependencyPlan.parseRequirements(it.readText()) } ?: emptyList()
                val aptSpecs = aptFile?.let { SkillDependencyPlan.parseAptRequirements(it.readText()) } ?: emptyList()
                val plan = SkillDependencyPlan.resolve(pipSpecs, aptSpecs, epoch)
                if (plan == null) {
                    LogStore.d("SKILL", "技能 ${def.name} 未声明依赖，跳过依赖安装")
                    continue
                }
                val prefKey = DEPS_PREF_KEY_PREFIX + def.name
                if (!force && prefs.getString(prefKey, null) == plan.fingerprint) {
                    LogStore.d("SKILL", "技能 ${def.name} 依赖已就绪（指纹未变），跳过重复安装")
                    continue
                }
                LogStore.i(
                    "SKILL",
                    "安装技能 ${def.name} 的依赖: apt=[${plan.aptPackages.joinToString(", ")}] " +
                        "pip=[${plan.pipSpecs.joinToString(", ")}]" +
                        (if (plan.bootstrapApt.isNotEmpty()) "（含 pip 运行时自动补装）" else "")
                )
                val out = executeBash(context, buildDependencyScript(plan, pipFile), timeoutSeconds = 300L)
                if (out.contains(DEPS_OK_SENTINEL)) {
                    prefs.edit().putString(prefKey, plan.fingerprint).apply()
                    LogStore.i("SKILL", "技能 ${def.name} 依赖安装完成")
                } else {
                    // 不写指纹：依赖确实缺失时，下次进入工具页会自动重试
                    LogStore.w("SKILL", "技能 ${def.name} 依赖安装失败: ${out.take(300)}")
                }
            } catch (e: Exception) {
                LogStore.w("SKILL", "安装 ${def.name} 依赖失败: ${e.message}")
            }
        }
    }

    /**
     * 构造依赖安装脚本：系统依赖逐包判缺（`dpkg -s`）再 apt，pip 依赖最后装；
     * `set -e` 保证任一步失败即中止、不输出哨兵（因此不会被记成「已安装」）。
     */
    private fun buildDependencyScript(plan: SkillDependencyPlan.Plan, pipFile: File?): String = buildString {
        appendLine("set -e")
        if (plan.aptPackages.isNotEmpty()) {
            appendLine("missing=''")
            appendLine("for p in " + plan.aptPackages.joinToString(" ") { shellQuote(it) } + "; do")
            // 注意：这里是 shell 变量，Kotlin 侧必须转义成 \$p / \$missing
            appendLine("  dpkg -s \"\$p\" >/dev/null 2>&1 || missing=\"\$missing \$p\"")
            appendLine("done")
            appendLine("if [ -n \"\$missing\" ]; then")
            appendLine("  echo \"[skill-deps] apt 安装:\$missing\"")
            appendLine("  apt-get update -qq")
            appendLine("  apt-get install -y --no-install-recommends \$missing")
            appendLine("fi")
        }
        if (pipFile != null && plan.pipSpecs.isNotEmpty()) {
            appendLine(
                "pip3 install -r " + shellQuote(pipFile.absolutePath) +
                    " --break-system-packages --disable-pip-version-check -q 2>&1"
            )
        }
        appendLine("echo $DEPS_OK_SENTINEL")
    }

    /** 读取技能声明的依赖（pip 列表, apt 列表），供 skill_list / skill_info 透出——不做任何安装。 */
    fun declaredDependencies(def: SkillDefinition): Pair<List<String>, List<String>> {
        val skillsDir = File(context.filesDir, "skills")
        val pip = resolveDeclaredFile(skillsDir, def.rootDirName, PIP_REQUIREMENTS)
            ?.let { SkillDependencyPlan.parseRequirements(it.readText()) } ?: emptyList()
        val apt = resolveDeclaredFile(skillsDir, def.rootDirName, APT_REQUIREMENTS)
            ?.let { SkillDependencyPlan.parseAptRequirements(it.readText()) } ?: emptyList()
        return pip to apt
    }

    /**
     * 取技能目录下的依赖声明文件：优先用户目录（filesDir/skills/&lt;目录名&gt;/&lt;文件名&gt;），
     * 缺失时回退到 APK 内置 assets/skills/&lt;目录名&gt;/&lt;文件名&gt;，并落地到
     * filesDir/.skill_deps/&lt;目录名&gt;.&lt;文件名&gt;（pip -r 需要真实文件路径）。
     *
     * 为什么要回退：种子解压只在技能目录**不存在**时执行（否则会覆盖用户改过的技能），
     * 所以 App 升级时给内置技能新增的依赖声明进不到老安装的 filesDir。
     * 回退只读 assets、只写独立缓存目录，不会污染用户技能目录。
     *
     * @return 可直接使用的文件；技能没有该声明文件时返回 null
     */
    private fun resolveDeclaredFile(skillsDir: File, dirName: String, fileName: String): File? {
        val userFile = File(skillsDir, "$dirName/$fileName")
        if (userFile.isFile) return userFile
        return try {
            val text = context.assets.open("skills/$dirName/$fileName")
                .use { it.bufferedReader().readText() }
            val cached = File(context.filesDir, ".skill_deps/$dirName.$fileName").apply { parentFile?.mkdirs() }
            cached.writeText(text)
            cached
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 注销所有技能已注册的脚本工具（重扫前调用，避免残留失效工具）。
     */
    fun unregisterAllScriptTools() {
        for (def in skills.values) {
            for (script in def.scripts) {
                toolbox.unregister(scriptToolName(def.name, script.name))
            }
        }
    }

    /**
     * 重载全部技能：先注销所有脚本工具，再重新扫描注册。
     * 供技能市场安装/卸载后调用（等价 ToolRuntime.rescanSkills，但 SkillManager 自洽）。
     */
    fun reloadAll() {
        unregisterAllScriptTools()
        skills.clear()
        discoverAndLoad()
    }

    /**
     * 卸载技能 — 注销该技能注册的所有脚本工具并从内存删除。
     */
    fun uninstall(name: String): Boolean {
        val def = skills[name] ?: return false
        // 注销脚本工具
        for (script in def.scripts) {
            toolbox.unregister(scriptToolName(def.name, script.name))
        }
        // 注销声明的普通工具名
        for (toolName in def.tools) {
            toolbox.unregister(toolName)
        }
        skills.remove(name)
        LogStore.i("SKILL", "技能已卸载: $name")
        return true
    }

    /**
     * 启用/禁用技能（禁用时注销脚本工具，启用时重新注册）。
     */
    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val def = skills[name] ?: return false
        def.enabled = enabled
        // 与设置页开关共用同一持久化存储，保证 reloadAll / 重启后状态一致
        com.mcp.core.skill.SkillPreferences.setEnabled(context, name, enabled)
        if (enabled) {
            registerScriptTools(def)
        } else {
            for (script in def.scripts) {
                toolbox.unregister(scriptToolName(def.name, script.name))
            }
        }
        LogStore.i("SKILL", "技能 ${if (enabled) "启用" else "禁用"}: $name")
        return true
    }

    // ── 内部实现 ──

    /**
     * 校验已加载技能的市场化前置条件：
     *  - `minApp`：不为空且高于宿主版本 → 警告（不阻断，仅提示）
     *  - `requires`：引用的技能不存在或未启用 → 警告
     *
     * 不阻断加载：缺依赖/版本不匹配时降级为日志告警，由 skill_info/市场工具透出，
     * 技能脚本仍可运行（Linux 环境能力不依赖声明）。
     */
    private fun validateAll() {
        for (def in skills.values) {
            if (def.minApp.isNotBlank() && compareVersions(AppVersion.CURRENT, def.minApp) < 0) {
                LogStore.w("SKILL", "技能 ${def.name} 需要宿主版本 >= ${def.minApp}（当前 ${AppVersion.CURRENT}）")
            }
            val missing = def.requires.filter { req -> skills[req] == null || skills[req]?.enabled != true }
            if (missing.isNotEmpty()) {
                LogStore.w("SKILL", "技能 ${def.name} 缺少依赖（未安装或未启用）: ${missing.joinToString(", ")}")
            }
        }
    }

    /** 脚本工具名：skill_<技能名>_<脚本名>，避免与全局工具冲突。 */
    private fun scriptToolName(skillName: String, scriptName: String): String =
        "skill_${skillName}_${scriptName}".replace(Regex("[^A-Za-z0-9_]")) { "_" }

    /**
     * 为技能声明的一个脚本生成 MCP 工具并注册到 Toolbox。
     * 脚本内容从技能目录读取（assets 或 filesDir），经 stdin 交给 PRoot 中的解释器执行。
     * @return 注册的工具数
     */
    private fun registerScriptTools(def: SkillDefinition): Int {
        if (!def.enabled || def.scripts.isEmpty()) return 0
        var count = 0
        for (script in def.scripts) {
            if (script.name.isBlank() || script.entry.isBlank()) continue
            val toolName = scriptToolName(def.name, script.name)
            try {
                val tool = buildScriptTool(def, script, toolName)
                toolbox.register(tool)
                count++
                LogStore.d("SKILL", "已注册脚本工具: $toolName (${script.interpreter})")
            } catch (e: Exception) {
                LogStore.w("SKILL", "注册脚本工具失败 $toolName: ${e.message}")
            }
        }
        return count
    }

    private fun buildScriptTool(def: SkillDefinition, script: SkillScript, toolName: String): ToolDef =
        tool(toolName) {
            description = "执行技能「${def.name}」的脚本 ${script.name}（在完整 Debian PRoot Linux 环境）。" +
                (if (script.description.isNotEmpty()) script.description + "。" else "") +
                "参数按声明顺序作为位置参数传给脚本；输出为脚本 stdout。" +
                if (script.background)
                    "【后台常驻】立即返回任务 ID（bg_ 开头）而非脚本输出；脚本由宿主侧常驻 PRoot 进程承载并保持运行，服务可跨命令存活；随后用 bash_task_status / bash_task_logs / bash_task_kill 查看状态/日志或停止。" else ""

            // 声明参数
            for (p in script.params) {
                when (p.type) {
                    "integer" -> integer(p.name) {
                        description = p.description
                        required = p.required
                    }
                    "number" -> number(p.name) {
                        description = p.description
                        required = p.required
                    }
                    "boolean" -> boolean(p.name) {
                        description = p.description
                        required = p.required
                    }
                    else -> string(p.name) {
                        description = p.description
                        required = p.required
                    }
                }
            }

            handler { args ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val argList = script.params.map { p ->
                            args[p.name]?.jsonPrimitive?.contentOrNull ?: ""
                        }
                        val invocation = if (script.interpreter == "wasm") {
                            buildWasmInvocation(def, script, argList)
                        } else {
                            val scriptBody = readScriptBody(def, script.entry)
                            buildInvocation(script.interpreter, scriptBody, argList)
                        }
                        if (script.background) {
                            // 后台常驻：宿主侧常驻 PRoot 进程承载，立即返回任务 ID，无命令超时。
                            // 脚本须前台保持运行（守护进程用 -F 前台 + wait），PRoot --kill-on-exit
                            // 只会在脚本结束时回收服务进程，因此脚本不退服务即可跨命令存活。
                            val task = BashTaskManager.startTask(context, invocation)
                            buildJsonObject {
                                put("task_id", task.taskId)
                                put("status", task.status.name)
                                put("log_file", task.logFile.absolutePath)
                                put("note", "后台任务已启动（宿主侧常驻 PRoot 进程承载）。用 bash_task_status / bash_task_logs / bash_task_kill 管理；任务被 kill 或 App 进程退出后服务随之停止。")
                            }.toString()
                        } else {
                            val timeoutSec = if (script.timeout > 0) script.timeout.toLong() else 300L
                            executeBash(context, invocation, timeoutSeconds = timeoutSec)
                        }
                    }.getOrElse { e ->
                        """{"error":"执行技能脚本失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
                    }
                }
            }
        }

    /**
     * 从技能目录读取脚本文件内容（filesDir 优先，assets 内置回退）。
     *
     * 返回值统一做换行归一化（CRLF → LF）：脚本经 stdin/heredoc 交给 bash，
     * CRLF 会让每行末尾多一个 \r 从而直接语法报错（详见 [SkillScriptSource]）。
     */
    private fun readScriptBody(def: SkillDefinition, entry: String): String {
        val relative = entry.removePrefix("/")
        // 1) 用户安装目录（filesDir/skills/）——按**目录名**解析，name 与目录名可以不同
        val userFile = File(context.filesDir, "skills/${def.rootDirName}/$relative")
        if (userFile.isFile) {
            LogStore.d("SKILL", "从 filesDir 读取脚本: ${userFile.absolutePath}")
            return SkillScriptSource.normalizeLineEndings(userFile.readText())
        }

        // 2) assets 内置技能
        val assetPath = "skills/${def.rootDirName}/$relative"
        try {
            context.assets.open(assetPath).use { stream ->
                LogStore.d("SKILL", "从 assets 读取脚本: $assetPath")
                return SkillScriptSource.normalizeLineEndings(stream.bufferedReader().readText())
            }
        } catch (e: Exception) {
            // 枚举 assets/skills/ 下文件，帮助诊断
            try {
                val parentDir = assetPath.substringBeforeLast('/')
                val list = context.assets.list(parentDir)
                LogStore.w("SKILL", "assets 读取失败: $assetPath，${parentDir} 下文件: ${list?.joinToString() ?: "无"}")
            } catch (_: Exception) {
                LogStore.w("SKILL", "assets 读取失败: $assetPath，且无法枚举目录")
            }
            throw RuntimeException("脚本文件不存在: $assetPath，请检查 APK 打包是否包含 assets/skills/")
        }
    }

    /** 从技能目录读取脚本原始字节（用于 .wasm 等二进制入口，避免 readText 破坏二进制）。 */
    private fun readScriptBytes(def: SkillDefinition, entry: String): ByteArray {
        val relative = entry.removePrefix("/")
        val userFile = File(context.filesDir, "skills/${def.rootDirName}/$relative")
        if (userFile.isFile) return userFile.readBytes()
        return context.assets.open("skills/${def.rootDirName}/$relative").use { it.readBytes() }
    }

    /**
     * 构造 WASM 入口的执行调用：把 .wasm 二进制落地到 filesDir/wasm_cache（PRoot 内映射为
     * /host/app/wasm_cache），再交给 wasmtime 运行（WASI 命令模块）。wasmtime 缺失时给出可自愈报错。
     */
    private fun buildWasmInvocation(def: SkillDefinition, script: SkillScript, args: List<String>): String {
        val bytes = readScriptBytes(def, script.entry)
        val cacheDir = File(context.filesDir, "wasm_cache")
        cacheDir.mkdirs()
        val wasmFile = File(cacheDir, "${def.name}_${script.name}.wasm")
        wasmFile.writeBytes(bytes)
        val guestPath = "/host/app/wasm_cache/${def.name}_${script.name}.wasm"
        val shellArgs = args.joinToString(" ") { shellQuote(it) }
        return "if command -v wasmtime >/dev/null 2>&1; then " +
            "wasmtime $guestPath $shellArgs; else " +
            "echo '{\"error\":\"WASM 运行时 wasmtime 未安装，请在 PRoot 中 apt install wasmtime 后重试\"}'; exit 1; fi"
    }

    /** 构造执行调用：脚本内容经 stdin 交给解释器（避免落地临时文件）。 */
    private fun buildInvocation(interpreter: String, scriptBody: String, args: List<String>): String {
        val shellArgs = args.joinToString(" ") { shellQuote(it) }
        return when (interpreter) {
            "python3", "python" -> "python3 -c " + shellQuote(scriptBody) + " " + shellArgs
            "node" -> "node -e " + shellQuote(scriptBody) + " " + shellArgs
            "sh" -> "sh -s " + shellArgs + " <<'SCRIPT_EOF'\n" + scriptBody + "\nSCRIPT_EOF"
            else -> "bash -s " + shellArgs + " <<'SCRIPT_EOF'\n" + scriptBody + "\nSCRIPT_EOF"
        }
    }

    /** shell 单引号转义（安全拼接参数）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun loadSkillsFromDir(dir: File, sourcePrefix: String): List<String> {
        val loaded = mutableListOf<String>()
        val subDirs = dir.listFiles { f -> f.isDirectory } ?: return loaded
        for (skillDir in subDirs) {
            try {
                loadSkillFromDir(skillDir, sourcePrefix)
                loaded.add(skillDir.name)
            } catch (e: Exception) {
                LogStore.w("SKILL", "跳过技能 ${skillDir.name}: ${e.message}")
            }
        }
        return loaded
    }

    private fun loadSkillFromDir(skillDir: File, sourcePrefix: String) {
        val metaFile = File(skillDir, "skill.json")
        // 无 skill.json 也加载：手动移动到 files/skills 的目录可能只有脚本/markdown。
        // 此时用目录名作为技能名，脚本工具仍可注册（entry 相对目录解析）。
        val def = if (metaFile.exists()) {
            val metaJson = metaFile.readText()
            val meta = JSONObject(metaJson)
            val name = meta.optString("name", skillDir.name)
            if (name.isBlank()) throw IllegalArgumentException("技能名称为空")
            // source 与 dirName 都取真实目录：name 只是技能标识，未必等于目录名
            // （内置 github-actions-skill 的 name 是 github-actions）。
            meta.put("source", "$sourcePrefix/${skillDir.name}")
            meta.put("dirName", skillDir.name)
            SkillDefinition.fromJsonObject(meta)
        } else {
            SkillDefinition(
                name = skillDir.name,
                source = "$sourcePrefix/${skillDir.name}",
                dirName = skillDir.name
            )
        }

        skills[def.name] = def
        if (def.rootDirName != def.name) {
            LogStore.d("SKILL", "技能 ${def.name} 的声明名与目录名不同（dir=${def.rootDirName}）：脚本/依赖按目录解析")
        }
        LogStore.d("SKILL", "技能已加载: ${def.displayName} (scripts=${def.scripts.size})")
    }


    /**
     * 把 APK 内置技能（assets/skills/）递归种子解压到 filesDir/skills/。
     *
     * 目的：让「已装 App 的内置技能」也有稳定、可被文件工具读取的绝对目录，
     * 而不是只存在于 APK 内部（assets 无文件系统路径，read_file 读不到）。
     *
     * 策略：仅在目标文件不存在时写入——不覆盖用户已安装/修改过的同名技能，
     * 保护用户数据；内置技能内容更新由后续版本机制处理（此处不处理覆盖）。
     */
    private fun seedBundledSkillsToFiles() {
        val root = File(context.filesDir, "skills")
        try {
            root.mkdirs()
            val top = context.assets.list("skills") ?: return
            for (skillDirName in top) {
                val destDir = File(root, skillDirName)
                // 已存在（用户安装或上次已解压）则跳过整个技能目录，不覆盖。
                if (destDir.exists()) continue
                val assetBase = "skills/$skillDirName"
                // 判断是目录（能列出子项）而非单个文件
                val children = try { context.assets.list(assetBase) } catch (_: Exception) { null }
                if (children == null || children.isEmpty()) continue
                destDir.mkdirs()
                copyAssetTree(assetBase, destDir)
                LogStore.d("SKILL", "已种子解压内置技能到 filesDir: ${destDir.absolutePath}")
            }
        } catch (e: Exception) {
            LogStore.w("SKILL", "种子解压内置技能失败（不影响 assets 直读回退）: ${e.message}")
        }
    }

    /** 递归复制 assets 目录树到目标 File 目录（仅写不存在的文件）。 */
    private fun copyAssetTree(assetPath: String, destDir: File) {
        val children = try { context.assets.list(assetPath) } catch (_: Exception) { null }
        if (children == null) return
        for (name in children) {
            val childAsset = "$assetPath/$name"
            val childDest = File(destDir, name)
            val grandChildren = try { context.assets.list(childAsset) } catch (_: Exception) { null }
            if (grandChildren != null && grandChildren.isNotEmpty()) {
                // 目录
                childDest.mkdirs()
                copyAssetTree(childAsset, childDest)
            } else {
                // 文件（或空目录）：尝试作为文件复制
                try {
                    if (childDest.exists()) continue
                    context.assets.open(childAsset).use { input ->
                        childDest.outputStream().use { out -> input.copyTo(out) }
                    }
                } catch (_: Exception) {
                    // 复制失败（可能是空目录）——忽略单个文件，不阻断整体
                }
            }
        }
    }

    private fun loadSkillFromAssets(dirName: String) {
        try {
            val metaJson = context.assets.open("skills/$dirName/skill.json")
                .bufferedReader().readText()
            val meta = JSONObject(metaJson)
            val name = meta.optString("name", dirName)
            if (name.isBlank()) return
            meta.put("source", "assets/$dirName")
            meta.put("dirName", dirName)

            val def = SkillDefinition.fromJsonObject(meta)
            skills[name] = def
            LogStore.d("SKILL", "内置技能已加载: ${def.displayName} (scripts=${def.scripts.size})")
        } catch (e: Exception) {
            LogStore.w("SKILL", "加载 assets 技能失败: $dirName — ${e.message}")
        }
    }
}