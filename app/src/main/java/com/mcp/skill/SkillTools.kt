package com.mcp.skill

import android.content.Context
import com.mcp.LogStore
import com.mcp.executeBash
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 技能市场的工具集（在线分发）：把「从 URL 拉技能 → 校验 skill.json → 热加载」固化为
 * 可直接被 LLM 调用的工具，无需用户手动走 SAF 导入。
 *
 * - `skill_list`    列出已安装技能（含版本、来源、脚本数、依赖、版本门槛）
 * - `skill_info`    查看单个技能详情
 * - `skill_install` 从 URL 安装技能（.zip 包或 git 仓库），安装后热加载
 * - `skill_uninstall` 卸载技能并注销其工具
 *
 * @param context  Android 上下文
 * @param manager  惰性返回当前 [SkillManager]（组合根创建完 SkillManager 后再注册这些工具）
 */
fun skillManagementTools(
    context: Context,
    manager: () -> SkillManager
): List<ToolDef> = listOf(
    skillListTool(context, manager),
    skillInfoTool(context, manager),
    skillInstallTool(context, manager),
    skillUninstallTool(context, manager)
)

private fun skillListTool(context: Context, manager: () -> SkillManager): ToolDef = tool("skill_list") {
    description = "列出所有已安装技能，含 name/version/source/dirName/enabled/脚本数/技能依赖(requires)/最低版本门槛(minApp)/依赖声明(pipDependencies、aptDependencies)。用于了解当前可用的技能能力，以及确认某技能是否已安装、需要哪些依赖。"
    handler {
        withContext(Dispatchers.IO) {
            runCatching {
                val skills = manager().allSkills()
                val arr = JSONArray()
                for (def in skills) arr.put(skillSummaryJson(def, manager().declaredDependencies(def)))
                JSONObject().put("count", skills.size).put("skills", arr).toString()
            }.getOrElse { e ->
                """{"error":"列出技能失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

private fun skillInfoTool(context: Context, manager: () -> SkillManager): ToolDef = tool("skill_info") {
    description = "查看单个技能的完整元数据：描述、作者、来源、目录名、是否启用、绑定的脚本（解释器、入口、参数、超时、是否后台常驻）、依赖声明（pipDependencies / aptDependencies）、技能依赖(requires)与最低版本门槛。"
    string("name") {
        description = "技能名（与 skill.json 的 name 一致，用 skill_list 查询）"
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isEmpty()) throw IllegalArgumentException("缺少必填参数 name")
                val def = manager().getSkill(name)
                    ?: throw IllegalArgumentException("技能不存在: $name（用 skill_list 查看已安装技能）")
                skillDetailJson(def, manager().declaredDependencies(def)).toString()
            }.getOrElse { e ->
                """{"error":"${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

private fun skillInstallTool(context: Context, manager: () -> SkillManager): ToolDef = tool("skill_install") {
    description = "从 URL 安装技能并热加载（无需重启）。url 以 .zip 结尾视为 zip 技能包（下载后解压、校验 skill.json）；否则视为 git 仓库（在 PRoot 中 git clone）。安装完成后自动重扫注册脚本工具，缺 skill.json 时自动生成最小元数据。"
    string("url") {
        description = "技能包地址：http(s) 的 .zip 压缩包，或任意 git 仓库 URL（https/ssh）"
    }
    string("name") {
        description = "安装后的技能目录名/技能名（可选，缺省从 URL 文件名推导）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (url.isEmpty()) throw IllegalArgumentException("缺少必填参数 url")
                val skillName = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: deriveName(url)

                val ok = if (url.endsWith(".zip", ignoreCase = true)) {
                    installFromZip(context, url, skillName)
                } else {
                    installFromGit(context, url, skillName)
                }
                if (!ok) throw IllegalStateException("安装失败：技能目录未生成")
                manager().reloadAll()
                // 新装技能的依赖立即补齐（pip/apt 都按技能目录声明；指纹保证已装好的不动）。
                // PRoot 未就绪或离线时内部直接返回/告警，下次打开「工具」页会自动重试，不影响安装结果。
                runCatching { manager().installSkillDependencies() }
                    .onFailure { LogStore.w("SKILL", "安装 $skillName 后补齐依赖失败: ${it.message}") }
                JSONObject()
                    .put("installed", skillName)
                    .put("total_skills", manager().allSkills().size)
                    .toString()
            }.getOrElse { e ->
                """{"error":"安装技能失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

private fun skillUninstallTool(context: Context, manager: () -> SkillManager): ToolDef = tool("skill_uninstall") {
    description = "卸载技能：删除其文件目录并注销已注册的脚本工具（内置只读技能不可卸载）。"
    string("name") {
        description = "要卸载的技能名（与 skill.json 的 name 一致）"
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (name.isEmpty()) throw IllegalArgumentException("缺少必填参数 name")
                // 目录名可能与技能名不同（name 取自 skill.json），必须按真实目录删，
                // 否则删不掉、甚至删错别的技能目录
                val dirName = manager().getSkill(name)?.rootDirName ?: name
                val target = File(context.filesDir, "skills/$dirName")
                if (target.exists()) target.deleteRecursively()
                val removed = manager().uninstall(name)
                JSONObject().put("name", name).put("removed", removed).toString()
            }.getOrElse { e ->
                """{"error":"卸载技能失败: ${e.message?.take(300)?.replace("\"", "'")}"}"""
            }
        }
    }
}

// ── 安装实现 ──

/** 从 .zip 技能包安装：下载 → 解压到 filesDir/skills/<name> → 归一化 → 补 skill.json。 */
private fun installFromZip(context: Context, url: String, skillName: String): Boolean {
    val target = File(File(context.filesDir, "skills").apply { mkdirs() }, skillName)
    if (target.exists()) target.deleteRecursively()
    target.mkdirs()

    val bytes = download(url)
    val count = extractZip(bytes, target)
    if (count == 0) {
        target.deleteRecursively()
        return false
    }
    normalizeSkillDir(target)
    ensureSkillJson(target, skillName)
    return true
}

/** 从 git 仓库安装：在 PRoot 中 git clone 到 /host/app/skills/<name>（即 filesDir/skills/<name>）。 */
private fun installFromGit(context: Context, url: String, skillName: String): Boolean {
    File(context.filesDir, "skills").mkdirs()
    val target = File(context.filesDir, "skills/$skillName")
    if (target.exists()) target.deleteRecursively()

    val guestPath = "/host/app/skills/$skillName"
    val script = "rm -rf ${shq(guestPath)} && git clone --depth 1 ${shq(url)} ${shq(guestPath)}"
    executeBash(context, script, timeoutSeconds = 300L)

    if (!target.isDirectory) return false
    ensureSkillJson(target, skillName)
    return true
}

private fun download(url: String): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 120_000
    conn.instanceFollowRedirects = true
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "AgentToolbox-SkillInstaller/1.0")
    return try {
        conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}

/** 解压 zip；跳过多余路径（含 .. 或绝对路径的条目），返回落盘文件数。 */
private fun extractZip(bytes: ByteArray, dest: File): Int {
    var count = 0
    ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val name = entry.name
            if (!name.contains("..") && !name.startsWith("/")) {
                val out = File(dest, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                    count++
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return count
}

/** 若 zip 内把技能放在单层子目录里，把内容提升到技能根目录，保证 skill.json 位于根。 */
private fun normalizeSkillDir(target: File) {
    if (File(target, "skill.json").isFile) return
    val children = target.listFiles { f -> f.isDirectory } ?: return
    if (children.size != 1) return
    val inner = children[0]
    if (!File(inner, "skill.json").isFile) return
    inner.listFiles()?.forEach { f ->
        val dest = File(target, f.name)
        if (f.isDirectory) f.copyRecursively(dest, overwrite = true)
        else f.copyTo(dest, overwrite = true)
    }
    inner.deleteRecursively()
}

/** 缺 skill.json 时写一个最小元数据，保证技能可被 SkillManager 正常加载。 */
private fun ensureSkillJson(dir: File, name: String) {
    val meta = File(dir, "skill.json")
    if (!meta.exists()) {
        meta.writeText(
            """{"name":"${name.replace("\"", "\\\"")}","description":"在线分发技能","version":"1.0.0"}"""
        )
    }
}

/** 从 URL 推导技能名：取路径最后一段并去掉扩展名（.zip / .git）。 */
private fun deriveName(url: String): String {
    val path = url.substringBefore('?').substringBefore('#')
    val base = path.substringAfterLast('/').substringBeforeLast('.')
    return base.ifBlank { "imported_skill" }
}

// ── JSON 序列化 ──

/**
 * 技能摘要 JSON。
 *
 * @param deps 声明的依赖（pip 列表, apt 列表），来自技能目录的 requirements.txt / apt-requirements.txt；
 *             让 LLM 能直接看到「这个技能要什么才能跑」，而不是等脚本报 command not found。
 */
private fun skillSummaryJson(
    def: SkillDefinition,
    deps: Pair<List<String>, List<String>>? = null
): JSONObject = JSONObject()
    .put("name", def.name)
    .put("version", def.version)
    .put("description", def.description)
    .put("source", def.source)
    .put("dirName", def.rootDirName)
    .put("enabled", def.enabled)
    .put("scripts", def.scripts.size)
    .put("requires", JSONArray(def.requires))
    .put("minApp", def.minApp)
    .put("pipDependencies", JSONArray(deps?.first ?: emptyList<String>()))
    .put("aptDependencies", JSONArray(deps?.second ?: emptyList<String>()))

private fun skillDetailJson(
    def: SkillDefinition,
    deps: Pair<List<String>, List<String>>? = null
): JSONObject {
    val scripts = JSONArray()
    for (s in def.scripts) {
        val params = JSONArray()
        for (p in s.params) {
            params.put(
                JSONObject()
                    .put("name", p.name)
                    .put("type", p.type)
                    .put("required", p.required)
                    .put("description", p.description)
            )
        }
        scripts.put(
            JSONObject()
                .put("name", s.name)
                .put("interpreter", s.interpreter)
                .put("entry", s.entry)
                .put("timeout", s.timeout)
                .put("background", s.background)
                .put("description", s.description)
                .put("params", params)
        )
    }
    return skillSummaryJson(def, deps)
        .put("author", def.author)
        .put("tools", JSONArray(def.tools))
        .put("scripts", scripts)
}

/** shell 单引号转义（安全拼接参数）。 */
private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"