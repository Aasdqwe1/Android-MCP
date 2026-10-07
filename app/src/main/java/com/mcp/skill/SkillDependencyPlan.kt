package com.mcp.skill

import java.security.MessageDigest

/**
 * 技能依赖的安装计划 — 纯逻辑，无 Android 依赖，便于用 JVM 单测锁住契约。
 *
 * 依赖**只在技能侧声明**，分两类文件（都在技能目录下）：
 *  - `requirements.txt`：pip 依赖（标准 pip 行，如 `flask>=2.3.0`）；
 *  - `apt-requirements.txt`：系统依赖（每行一个 apt 包名，如 `git` / `smbclient`）。
 *
 * 背景：随包 rootfs 只是 Debian minbase，**不含 python3 / pip3 / git / curl / node**，
 * 所以「pip 依赖」必须先有解释器。因此当技能声明了 pip 依赖时，本对象自动把
 * `python3` + `python3-pip` 并入系统依赖（保证 requirements.txt 不是空话）。
 *
 * 历史教训（这套判据要防的坑）：
 *  1. 打开一次「工具」页就重跑一遍全量 pip（ToolsFragment.onViewCreated 每次都调）；
 *  2. zero-termux-api 的 flask 被 requirements.txt 与 Kotlin 硬编码各装一次；
 *  3. requirements.txt 只有注释的技能也照样起一次 PRoot，纯浪费；
 *  4. 依赖装不上却被 `|| true` 静默吞掉（pip3 根本不存在时也一样）。
 */
internal object SkillDependencyPlan {

    /**
     * 一个技能的安装计划。
     *
     * @param pipSpecs     pip 依赖（去重后的 requirements.txt 行）
     * @param declaredApt  技能显式声明的系统依赖（apt-requirements.txt）
     * @param bootstrapApt 由 pip 依赖推导出的隐含系统依赖（python3 / python3-pip）
     * @param fingerprint  指纹；与环境世代一起决定「是否需要重装」
     */
    data class Plan(
        val pipSpecs: List<String>,
        val declaredApt: List<String>,
        val bootstrapApt: List<String>,
        val fingerprint: String
    ) {
        /** 实际要安装的系统依赖（显式声明 + 隐含运行时）。 */
        val aptPackages: List<String> get() = declaredApt + bootstrapApt
    }

    /** requirements.txt 文本 → 有效依赖行（丢空行与 # 注释行，保留版本限定符）。 */
    fun parseRequirements(text: String): List<String> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * apt-requirements.txt 文本 → 包名列表。
     * 每行一个包名，允许行尾 `#` 注释与多余空白；只取第一个 token（包名不含空格）。
     */
    fun parseAptRequirements(text: String): List<String> =
        text.lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")).first() }

    /**
     * 生成安装计划。
     *
     * @param pip   requirements.txt 的有效行
     * @param apt   apt-requirements.txt 的包名
     * @param epoch 环境世代（PRoot rootfs 版本目录名）：重装/升级环境后指纹自动失效
     * @return 需要安装时返回计划；无任何依赖时返回 null（不产生安装动作、不进 PRoot）
     */
    fun resolve(pip: List<String>, apt: List<String>, epoch: String): Plan? {
        // 同一分发包写多遍（含大小写 / -_ 差异）只保留第一条，pip 少解析一次
        val seen = LinkedHashSet<String>()
        val pipSpecs = pip.map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(distributionName(it)) }
        val declaredApt = apt.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // pip 依赖必须有解释器：rootfs 里没有 python3/pip3，缺了就是「声明了但永远装不上」
        val bootstrapApt = if (pipSpecs.isEmpty()) emptyList()
        else listOf("python3", "python3-pip").filter { it !in declaredApt }
        if (pipSpecs.isEmpty() && declaredApt.isEmpty()) return null
        return Plan(
            pipSpecs = pipSpecs,
            declaredApt = declaredApt,
            bootstrapApt = bootstrapApt,
            fingerprint = fingerprint(epoch, pipSpecs, declaredApt + bootstrapApt)
        )
    }

    /**
     * pip 需求串 → 分发包名：去掉版本限定符、扩展标记（[extra]）与环境标记，统一大小写与 -/_ 差异。
     * 以 '-' 开头的 pip 选项行（如 `-e ...`）原样返回小写形式，不参与去重。
     */
    fun distributionName(spec: String): String {
        val trimmed = spec.trim()
        if (trimmed.startsWith("-")) return trimmed.lowercase()
        // Kotlin 没有 substringBefore(Regex) 重载：用 split(limit = 2) 取包名部分
        val name = trimmed.split(Regex("[<>=!~\\[;\\s]"), limit = 2).first().trim()
        return name.lowercase().replace('_', '-')
    }

    /** 依赖指纹 = SHA-256(环境世代 + pip 依赖 + 系统依赖) 的前 32 个十六进制字符。 */
    fun fingerprint(epoch: String, pip: List<String>, apt: List<String>): String {
        val raw = (listOf(epoch) + pip + listOf("|") + apt).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
