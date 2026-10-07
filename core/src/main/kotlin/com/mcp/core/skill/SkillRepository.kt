package com.mcp.core.skill

import android.content.Context
import android.util.Log
import com.mcp.serialization.McpJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Markdown 技能仓库 — 从 assets/skills/ 和 filesDir/skills/ 扫描、加载技能。
 *
 * 技能目录结构:
 * ```
 * skills/<name>/
 *   skill.json    — { "name": "...", "description": "...", "version": "1.0.0" }
 *   README.md     — 主文档（注入时使用）
 *   quick_ref.md  — 快速参考（选填，追加到 content）
 *   *.md          — 其他 markdown（按字母序追加）
 * ```
 *
 * 使用方式:
 * ```kotlin
 * val repo = SkillRepository(context)
 * repo.discover()
 * repo.getSkill("git")?.content  // 获取完整 markdown
 * ```
 */
class SkillRepository(private val context: Context) {

    private val skills = LinkedHashMap<String, MarkdownSkill>()

    /** 所有已发现的技能 */
    fun allSkills(): List<MarkdownSkill> = skills.values.toList()

    /** 所有技能名 */
    fun skillNames(): List<String> = skills.keys.toList()

    /** 按名称查找技能 */
    fun getSkill(name: String): MarkdownSkill? = skills[name]

    /** 技能是否已加载 */
    fun isLoaded(name: String): Boolean = skills.containsKey(name)

    /**
     * 扫描并加载所有技能。
     * 1) assets/skills/ — 内置技能（不可修改）
     * 2) filesDir/skills/ — 用户安装的技能（同名覆盖内置）
     */
    fun discover() {
        val loaded = mutableListOf<String>()

        // 1) 加载内置技能（assets）
        try {
            val assetList = context.assets.list("skills")
            if (assetList != null) {
                for (dirName in assetList) {
                    try {
                        loadFromAssets(dirName)
                        loaded.add(dirName)
                    } catch (e: Exception) {
                        Log.w("SKILL", "跳过内置技能 $dirName: ${e.message}")
                    }
                }
            }
        } catch (_: Exception) { /* assets/skills/ 不存在 */ }

        // 2) 加载用户技能（filesDir），同名覆盖内置
        val userSkillsDir = File(context.filesDir, "skills")
        if (userSkillsDir.isDirectory()) {
            val subDirs = userSkillsDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (skillDir in subDirs) {
                try {
                    loadFromDirectory(skillDir)
                    loaded.add(skillDir.name)
                } catch (e: Exception) {
                    Log.w("SKILL", "跳过用户技能 ${skillDir.name}: ${e.message}")
                }
            }
        }

        if (loaded.isNotEmpty()) {
            Log.i("SKILL", "Markdown 技能已加载: ${skills.keys}")
        }
    }

    /**
     * 从 assets/skills/<name>/ 加载一个内置技能。
     */
    private fun loadFromAssets(dirName: String) {
        val metaJson = try {
            context.assets.open("skills/$dirName/skill.json")
                .bufferedReader().readText()
        } catch (_: Exception) { null }

        val (name, description, version) = parseMeta(metaJson, dirName)

        val mdFiles = mutableListOf<String>()
        val content = buildString {
            // 按顺序收集所有 .md 文件
            // README.md 优先
            if (hasAsset("skills/$dirName/README.md")) mdFiles.add("README.md")
            // 其他 .md 文件（字母序，排除 README 和 index）
            val assets = context.assets.list("skills/$dirName") ?: emptyArray()
            for (f in assets.sorted()) {
                if (f.endsWith(".md") && f != "README.md" && f != "index.md") {
                    mdFiles.add(f)
                }
            }
            // index.md 最后
            if (hasAsset("skills/$dirName/index.md")) mdFiles.add("index.md")

            for ((i, file) in mdFiles.withIndex()) {
                if (i > 0) append("\n\n---\n\n")
                try {
                    append(context.assets.open("skills/$dirName/$file").bufferedReader().readText())
                } catch (_: Exception) { /* 跳过缺失文件 */ }
            }
        }

        if (content.isNotBlank()) {
            skills[name] = MarkdownSkill(name, description, content, version, mdFiles.toList())
            Log.d("SKILL", "内置技能已加载: $name (${content.length} chars)")
        }
    }

    /**
     * 从 filesDir/skills/<name>/ 加载用户技能。
     */
    private fun loadFromDirectory(skillDir: File) {
        val metaFile = File(skillDir, "skill.json")
        val metaJson = if (metaFile.exists()) metaFile.readText() else null
        val (name, description, version) = parseMeta(metaJson, skillDir.name)

        val content = buildString {
            val mdFiles = skillDir.listFiles { f -> f.name.endsWith(".md") && f.isFile }
                ?.map { it.name }?.sorted() ?: emptyList()
            // README.md 优先
            val reordered = mutableListOf<String>()
            if ("README.md" in mdFiles) { reordered.add("README.md"); mdFiles.filter { it != "README.md" && it != "index.md" }.forEach { reordered.add(it) } }
            else { mdFiles.filter { it != "index.md" }.forEach { reordered.add(it) } }
            if ("index.md" in mdFiles) reordered.add("index.md")

            for ((i, file) in reordered.withIndex()) {
                if (i > 0) append("\n\n---\n\n")
                try {
                    append(File(skillDir, file).readText())
                } catch (_: Exception) { /* 跳过 */ }
            }
        }

        if (content.isNotBlank()) {
            // Re-scan files outside buildString for the MarkdownSkill constructor
            val allFiles = skillDir.listFiles { f -> f.name.endsWith(".md") && f.isFile }
                ?.map { it.name }?.sorted() ?: emptyList()
            skills[name] = MarkdownSkill(name, description, content, version, allFiles)
            Log.d("SKILL", "用户技能已加载: $name (${content.length} chars)")
        }
    }

    /**
     * 解析 skill.json 元数据。
     * @return Triple(name, description, version)
     */
    private fun parseMeta(metaJson: String?, fallbackName: String): Triple<String, String, String> {
        if (metaJson == null) return Triple(fallbackName, "", "1.0.0")
        return try {
            // McpJson 宽松解析 skill.json；字段缺失/类型不符回落默认值。
            val obj = McpJson.parseToJsonElement(metaJson).jsonObject
            Triple(
                obj["name"]?.jsonPrimitive?.content ?: fallbackName,
                obj["description"]?.jsonPrimitive?.content ?: "",
                obj["version"]?.jsonPrimitive?.content ?: "1.0.0"
            )
        } catch (_: Exception) {
            Triple(fallbackName, "", "1.0.0")
        }
    }

    private fun hasAsset(path: String): Boolean = try {
        context.assets.open(path).use { true }
    } catch (_: Exception) { false }
}
