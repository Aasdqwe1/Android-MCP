package com.mcp.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 内置技能清单（skill.json / 目录结构 / 文档）的结构契约测试。
 *
 * 这些错误**不会编译失败，只在运行时炸**，且都真实存在过：
 *  - 脚本 `entry` 指向不存在的文件 → readScriptBody 抛「脚本文件不存在」；
 *  - 技能**目录名与 name 不同**（内置 github-actions-skill 的 name 是 github-actions）
 *    却按 name 去找文件 → 脚本工具必然调用失败；文件必须按目录解析（rootDirName）；
 *  - `tools` 里手写脚本工具名（skill_gha_status 之类）→ 技能名/脚本名一改就失真，
 *    而且同名工具根本没注册过；
 *  - 文档里写错工具名（`skill_excel_generate` / `skill_termux_api_start`）→ 用户照着调必然 404。
 */
class SkillManifestContractTest {

    /** 技能市场内置的全局工具（不属于任何技能）。 */
    private val globalSkillTools = setOf(
        "skill_list", "skill_info", "skill_install", "skill_uninstall"
    )

    /**
     * 规范文档（demo-skill）里用于演示命名规则的**假想**工具名——它对应的技能并不存在于本仓库，
     * 只允许出现在 demo-skill 的示例里；真实技能依旧必须写出实际注册的工具名。
     */
    private val illustrativeTools = setOf("skill_my_skill_hello")

    private fun assetsSkillsDir(): File? =
        listOf(File("src/main/assets/skills"), File("../app/src/main/assets/skills"))
            .firstOrNull { it.isDirectory }

    private fun skillDirs(): List<File> =
        assetsSkillsDir()?.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()

    /** 与 SkillManager.scriptToolName 同一规则：非 [A-Za-z0-9_] 一律替换成 _。 */
    private fun toolName(skill: String, script: String): String =
        "skill_${skill}_${script}".replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun loadSkill(dir: File): SkillDefinition {
        val def = SkillDefinition.fromJson(File(dir, "skill.json").readText())
        assertNotNull("${dir.name}/skill.json 解析失败", def)
        // SkillManager 加载时会写入真实目录名
        return def!!.copy(dirName = dir.name)
    }

    @Test
    fun everySkillManifestParses_andEveryScriptEntryExists() {
        val dirs = skillDirs()
        assumeTrue("找不到 assets/skills", dirs.isNotEmpty())
        for (dir in dirs) {
            val def = loadSkill(dir)
            assertTrue("${dir.name} 技能名不能为空", def.name.isNotBlank())
            assertEquals("文件必须按目录解析", dir.name, def.rootDirName)
            for (script in def.scripts) {
                assertTrue("${dir.name} 的脚本 ${script.name} 未声明 entry", script.entry.isNotBlank())
                val entryFile = File(dir, script.entry.removePrefix("/"))
                assertTrue(
                    "${dir.name} 声明的 entry 不存在: ${script.entry}（脚本工具会在调用时报「脚本文件不存在」）",
                    entryFile.isFile
                )
                assertTrue(
                    "${dir.name} 的脚本 ${script.name} 解释器不受支持: ${script.interpreter}",
                    script.interpreter in setOf("bash", "sh", "python3", "python", "node", "wasm")
                )
            }
        }
    }

    @Test
    fun declaredToolsMustNotDuplicateAutoRegisteredScriptTools() {
        val dirs = skillDirs()
        assumeTrue("找不到 assets/skills", dirs.isNotEmpty())
        for (dir in dirs) {
            val def = loadSkill(dir)
            val generated = def.scripts.map { toolName(def.name, it.name) }.toSet()
            for (declared in def.tools) {
                assertTrue(
                    "${dir.name} 的 tools 里声明了 ${declared}，但它正是脚本自动注册的工具名——" +
                        "重复声明只会随技能名/脚本名漂移，请删掉",
                    declared !in generated
                )
            }
        }
    }

    @Test
    fun docsOnlyMentionToolsThatActuallyExist() {
        val dirs = skillDirs()
        assumeTrue("找不到 assets/skills", dirs.isNotEmpty())
        for (dir in dirs) {
            val def = loadSkill(dir)
            val known = def.scripts.map { toolName(def.name, it.name) }.toSet() +
                def.tools.toSet() + globalSkillTools +
                (if (dir.name == "demo-skill") illustrativeTools else emptySet())
            val docs = dir.walkTopDown().filter { it.isFile && it.extension == "md" }.toList()
            for (doc in docs) {
                val mentions = Regex("skill_[A-Za-z0-9_]+")
                    .findAll(doc.readText())
                    .map { it.value }
                    .toSet()
                for (mention in mentions - known) {
                    // 提到**其它技能**的工具名是允许的（跨技能协作说明）
                    val ownedByOtherSkill = dirs.any { other ->
                        other != dir && loadSkill(other).scripts
                            .any { toolName(loadSkill(other).name, it.name) == mention }
                    }
                    assertTrue(
                        "${dir.name}/${doc.name} 提到的工具 $mention 不存在" +
                            "（${def.name} 实际注册: ${known.sorted().joinToString()}）",
                        ownedByOtherSkill
                    )
                }
            }
        }
    }

    @Test
    fun dependencyDeclarationsLiveInTheSkillDirectory() {
        val dirs = skillDirs()
        assumeTrue("找不到 assets/skills", dirs.isNotEmpty())
        // 需要第三方包的技能必须在技能目录里声明 requirements.txt（不再由 Kotlin 按技能名硬编码）
        for (name in listOf("zero-termux-api-skill", "excel-generator-skill")) {
            val dir = dirs.firstOrNull { it.name == name }
            assumeTrue("$name 不在内置技能里", dir != null)
            val req = File(dir!!, "requirements.txt")
            assertTrue("$name 缺 requirements.txt：依赖声明应随技能走", req.isFile)
            assertTrue(
                "$name 的 requirements.txt 必须能解析出至少一个包",
                SkillDependencyPlan.resolve(SkillDependencyPlan.parseRequirements(req.readText()), emptyList(), "e") != null
            )
        }
    }

    /**
     * 用 python3 解释器的技能必须能拿到解释器：随包 rootfs 只是 Debian minbase，
     * 没有 python3 / pip3。要么显式在 apt-requirements.txt 声明 python3，
     * 要么靠「声明了 pip 依赖 → 自动补 python3 + python3-pip」兜底。
     */
    @Test
    fun pythonSkillsMustDeclareTheirRuntime() {
        val dirs = skillDirs()
        assumeTrue("找不到 assets/skills", dirs.isNotEmpty())
        for (dir in dirs) {
            val def = loadSkill(dir)
            val needsPython = def.scripts.any { it.interpreter == "python3" || it.interpreter == "python" }
            if (!needsPython) continue
            val apt = File(dir, "apt-requirements.txt")
            val pip = File(dir, "requirements.txt")
            val aptPkgs = if (apt.isFile) SkillDependencyPlan.parseAptRequirements(apt.readText()) else emptyList()
            val pipSpecs = if (pip.isFile) SkillDependencyPlan.parseRequirements(pip.readText()) else emptyList()
            val plan = SkillDependencyPlan.resolve(pipSpecs, aptPkgs, "e")
            val covered = "python3" in aptPkgs || (plan != null && "python3" in plan.aptPackages)
            assertTrue(
                "${dir.name} 用 python3 解释器，却没有声明 python3 运行时" +
                    "（apt-requirements.txt 写 python3，或声明 pip 依赖以触发自动补装）",
                covered
            )
        }
    }

    /** 依赖声明是唯一事实来源：脚本里临时 apt-get install 必然与声明文件漂移。 */
    @Test
    fun scriptsMustNotInstallPackagesThemselves() {
        val dirs = skillDirs()
        assumeTrue("找不到 assets/skills", dirs.isNotEmpty())
        val pattern = Regex("apt(-get)?\\s+(update|install)")
        for (dir in dirs) {
            val scripts = dir.walkTopDown().filter { it.isFile && (it.extension == "sh" || it.extension == "py") }
            for (file in scripts) {
                for ((idx, line) in file.readLines().withIndex()) {
                    if (line.trimStart().startsWith("#")) continue
                    assertFalse(
                        "${dir.name}/${file.name}:${idx + 1} 出现 apt 安装命令：" +
                            "依赖请写进 apt-requirements.txt，由宿主统一安装",
                        pattern.containsMatchIn(line)
                    )
                }
            }
        }
    }

    /**
     * 脚本经 stdin/heredoc 交给 bash：CRLF 会让每行末尾多一个 \r 直接语法报错
     * （内置技能里 33 个文件曾经都是 CRLF）。读取处必须做归一化。
     */
    @Test
    fun scriptTextNormalization_makesCrlfSafe() {
        val crlf = "if [ -z \"\$1\" ]; then\r\necho hi\r\nfi\r\n"
        assertEquals("if [ -z \"\$1\" ]; then\necho hi\nfi\n", SkillScriptSource.normalizeLineEndings(crlf))
        assertEquals("a\nb\n", SkillScriptSource.normalizeLineEndings("a\rb\r"))
        assertEquals("a\nb\n", SkillScriptSource.normalizeLineEndings("a\nb\n"))
    }

    /**
     * 出厂脚本必须是 LF：它们也会被文档指引**在 PRoot 里手工执行**（`bash scripts/greet.sh 世界 zh`），
     * 那条路径没有宿主归一化兜底，CRLF 会直接语法报错。仓库侧由 .gitattributes 保证。
     */
    @Test
    fun bundledScriptsMustUseLfLineEndings() {
        val dirs = skillDirs()
        assumeTrue("找不到 assets/skills", dirs.isNotEmpty())
        for (dir in dirs) {
            val scripts = dir.walkTopDown().filter { it.isFile && (it.extension == "sh" || it.extension == "py") }
            for (file in scripts) {
                val text = file.readText()
                assertFalse(
                    "${dir.name}/${file.name} 含 CRLF：脚本必须存成 LF（bash heredoc 与手工执行都会被 \r 打断）",
                    text.contains("\r")
                )
            }
        }
    }
}
