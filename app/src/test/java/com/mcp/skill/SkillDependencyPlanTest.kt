package com.mcp.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 技能依赖安装计划（幂等判据 + pip/apt 拆分 + python 运行时兜底）的防漂移测试。
 *
 * 背景：installSkillDependencies() 原先对每个技能无条件跑 pip，于是「明明依赖已装好」还在反复安装；
 * 而且随包 rootfs 只是 Debian minbase（**没有 python3 / pip3**），pip 依赖其实永远装不上。
 * SkillDependencyPlan 把这套判据收敛成纯函数，本测试锁住它。
 */
class SkillDependencyPlanTest {

    private val epoch = "debian-trixie-pd-v4.29.0"

    // ── parseRequirements / parseAptRequirements ─────────────────────

    @Test
    fun parseRequirements_dropsBlankAndCommentLines_keepsSpecifiers() {
        val text = """
            # 仅标准库，无第三方包
            flask>=2.3.0

               openpyxl
            # 末尾注释
        """.trimIndent()
        assertEquals(listOf("flask>=2.3.0", "openpyxl"), SkillDependencyPlan.parseRequirements(text))
    }

    @Test
    fun parseAptRequirements_takesPackageNames_allowsInlineComments() {
        val text = """
            # 系统依赖
            python3
            git   # 版本控制

            smbclient
        """.trimIndent()
        assertEquals(listOf("python3", "git", "smbclient"), SkillDependencyPlan.parseAptRequirements(text))
    }

    // ── resolve：无依赖不产生安装动作 ──────────────────────────────────

    @Test
    fun resolve_noDependencies_returnsNull_soNothingRuns() {
        assertNull(SkillDependencyPlan.resolve(emptyList(), emptyList(), epoch))
        // 注释/空行先经 parse* 过滤，再交给 resolve；只有注释 = 无依赖
        assertNull(
            SkillDependencyPlan.resolve(
                SkillDependencyPlan.parseRequirements("  \n# 无"),
                SkillDependencyPlan.parseAptRequirements("# 无"),
                epoch
            )
        )
    }

    // ── pip 去重（flask 被装两遍的老 bug）──────────────────────────────

    @Test
    fun resolve_dedupesSamePackageDeclaredTwice() {
        val plan = SkillDependencyPlan.resolve(listOf("flask>=2.3.0", "flask"), emptyList(), epoch)!!
        assertEquals(listOf("flask>=2.3.0"), plan.pipSpecs)
    }

    @Test
    fun resolve_dedupesIgnoringCaseAndSeparators() {
        val plan = SkillDependencyPlan.resolve(
            listOf("PyYAML>=6.0", "pyyaml", "ruamel_yaml>=0.18", "ruamel-yaml"), emptyList(), epoch
        )!!
        assertEquals(listOf("PyYAML>=6.0", "ruamel_yaml>=0.18"), plan.pipSpecs)
    }

    // ── python 运行时兜底：rootfs 里没有 python3/pip3 ─────────────────

    @Test
    fun resolve_pipDeps_autoBootstrapPythonRuntime() {
        val plan = SkillDependencyPlan.resolve(listOf("openpyxl>=3.1.0"), emptyList(), epoch)!!
        assertEquals(listOf("python3", "python3-pip"), plan.bootstrapApt)
        assertEquals(listOf("python3", "python3-pip"), plan.aptPackages)
    }

    @Test
    fun resolve_pipDeps_doesNotDuplicateExplicitlyDeclaredPython() {
        // 技能已显式声明 python3；只补 python3-pip
        val plan = SkillDependencyPlan.resolve(listOf("flask>=2.3.0"), listOf("python3"), epoch)!!
        assertEquals(listOf("python3-pip"), plan.bootstrapApt)
        assertEquals(listOf("python3", "python3-pip"), plan.aptPackages)
    }

    @Test
    fun resolve_aptOnly_hasNoBootstrap() {
        val plan = SkillDependencyPlan.resolve(emptyList(), listOf("git"), epoch)!!
        assertTrue(plan.pipSpecs.isEmpty())
        assertTrue(plan.bootstrapApt.isEmpty())
        assertEquals(listOf("git"), plan.aptPackages)
    }

    // ── fingerprint：决定「要不要重装」 ────────────────────────────────

    @Test
    fun fingerprint_isStableAndSensitiveToEveryInput() {
        val base = SkillDependencyPlan.resolve(listOf("flask>=2.3.0"), listOf("python3"), epoch)!!
        assertEquals(base.fingerprint,
            SkillDependencyPlan.resolve(listOf("flask>=2.3.0"), listOf("python3"), epoch)!!.fingerprint)
        assertNotEquals("pip 清单变了必须重装", base.fingerprint,
            SkillDependencyPlan.resolve(listOf("flask>=2.4.0"), listOf("python3"), epoch)!!.fingerprint)
        assertNotEquals("apt 清单变了必须重装", base.fingerprint,
            SkillDependencyPlan.resolve(listOf("flask>=2.3.0"), listOf("python3", "git"), epoch)!!.fingerprint)
        assertNotEquals("rootfs 重装后必须重装", base.fingerprint,
            SkillDependencyPlan.resolve(listOf("flask>=2.3.0"), listOf("python3"), "rootfs-v2")!!.fingerprint)
    }

    // ── distributionName ─────────────────────────────────────────────

    @Test
    fun distributionName_stripsSpecifiersExtrasAndMarkers() {
        assertEquals("flask", SkillDependencyPlan.distributionName("flask"))
        assertEquals("flask", SkillDependencyPlan.distributionName("flask>=2.3.0"))
        assertEquals("requests", SkillDependencyPlan.distributionName("requests[socks]==2.31.0"))
        assertEquals("ruamel-yaml", SkillDependencyPlan.distributionName("ruamel_yaml>=0.18"))
        assertEquals("pkg", SkillDependencyPlan.distributionName("pkg; python_version >= '3.8'"))
        assertEquals("-e .", SkillDependencyPlan.distributionName("-e ."))
    }

    // ── 与仓库内真实 assets 对齐 ──────────────────────────────────────

    private fun asset(rel: String): File? =
        listOf(File(rel), File("../app/$rel")).firstOrNull { it.exists() }

    @Test
    fun bundledSkillsHaveParseableDeclarations() {
        val root = asset("src/main/assets/skills")
        assumeTrue(root != null)
        val skills = root!!.listFiles { f: File -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        assertTrue(skills.isNotEmpty())
        for (dir in skills) {
            val aptFile = File(dir, "apt-requirements.txt")
            val pipFile = File(dir, "requirements.txt")
            val apt = if (aptFile.isFile) SkillDependencyPlan.parseAptRequirements(aptFile.readText()) else emptyList()
            val pip = if (pipFile.isFile) SkillDependencyPlan.parseRequirements(pipFile.readText()) else emptyList()
            if (aptFile.isFile) {
                val meaningful = aptFile.readText().lines()
                    .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                assertEquals("${dir.name}/apt-requirements.txt 有无法解析的行", meaningful.size, apt.size)
            }
            assertNotNull("${dir.name} 的依赖声明解析失败",
                SkillDependencyPlan.resolve(pip, apt, epoch))
        }
    }

    @Test
    fun bundledSkillDeclarations_matchExpectations() {
        val zero = asset("src/main/assets/skills/zero-termux-api-skill/requirements.txt")
        val zeroApt = asset("src/main/assets/skills/zero-termux-api-skill/apt-requirements.txt")
        val excel = asset("src/main/assets/skills/excel-generator-skill/requirements.txt")
        val gha = asset("src/main/assets/skills/github-actions-skill/requirements.txt")
        val gitApt = asset("src/main/assets/skills/git/apt-requirements.txt")
        val smbApt = asset("src/main/assets/skills/smb-windows-share/apt-requirements.txt")
        assumeTrue(listOf(zero, zeroApt, excel, gha, gitApt, smbApt).all { it != null })

        assertEquals(listOf("flask>=2.3.0"), SkillDependencyPlan.parseRequirements(zero!!.readText()))
        assertEquals(listOf("openpyxl>=3.1.0"), SkillDependencyPlan.parseRequirements(excel!!.readText()))
        // github-actions：绑定脚本只用标准库，但随附辅助库 github_actions.py 需要 requests
        assertEquals(listOf("requests>=2.31.0"), SkillDependencyPlan.parseRequirements(gha!!.readText()))

        assertEquals(listOf("python3"), SkillDependencyPlan.parseAptRequirements(zeroApt!!.readText()))
        assertEquals(listOf("git"), SkillDependencyPlan.parseAptRequirements(gitApt!!.readText()))
        assertEquals(listOf("smbclient", "samba"), SkillDependencyPlan.parseAptRequirements(smbApt!!.readText()))
    }
}
