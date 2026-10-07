package com.mcp.skill

import com.mcp.serialization.McpJson
import kotlinx.serialization.Serializable

/**
 * 技能脚本声明 — 技能绑定的可执行脚本入口。
 *
 * 借鉴 browser-use 的 skill 机制：技能不只是 markdown 文档，还可以绑定
 * 真正可执行的脚本。加载技能时，每个脚本会被注册成一个 MCP 工具
 * （工具名 skill_<技能名>_<脚本名>），LLM 可以直接调用，脚本在 Debian
 * PRoot 完整 Linux 环境里运行（复用 run_bash 的执行通道）。
 *
 * @param name        脚本名（工具后缀）
 * @param description 给 LLM 的说明
 * @param entry       脚本文件相对技能目录的路径（如 scripts/status.sh）
 * @param interpreter 解释器：bash / sh / python3 / node / wasm，默认 bash。
 *                    wasm 指向 WASI 命令模块（.wasm），运行时自动探测 wasmtime，退化到 Node WASI。
 * @param params      脚本接受的参数（按声明顺序作为位置参数传入）
 * @param timeout     单次执行超时（秒）；<=0 用全局默认（300s），用于对技能脚本做资源上限（rlimit 思路）
 * @param background  后台常驻执行：经 run_bash_bg 的宿主侧常驻 PRoot 进程承载，立即返回任务 ID
 *                    （bg_ 开头），脚本保持前台运行，无命令超时，可跨命令存活；用
 *                    bash_task_status / bash_task_logs / bash_task_kill 管理。
 *                    典型用途：HTTP 服务器、SMB 服务端等守护进程（脚本须前台等待，不能 & 后退出，
 *                    否则 PRoot --kill-on-exit 会在脚本结束时回收服务进程）。
 */
@Serializable
data class SkillScript(
    val name: String,
    val description: String = "",
    val entry: String = "",
    val interpreter: String = "bash",
    val params: List<SkillScriptParam> = emptyList(),
    val timeout: Int = 0,
    val background: Boolean = false
)

/** 技能脚本的参数声明。 */
@Serializable
data class SkillScriptParam(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = false
)

/**
 * 技能元数据 — 描述一个可安装/卸载的能力单元。
 *
 * 每个技能暴露一个或多个 MCP 工具，自动注册到全局 Toolbox。
 *
 * @param name        技能唯一标识（如 "git"）
 * @param version     语义版本号（如 "1.0.0"）
 * @param description 给 LLM 和用户看的自然语言说明
 * @param author      作者（可选）
 * @param tools       此技能暴露的工具名列表（与 Toolbox 联动）
 * @param scripts     技能绑定的可执行脚本（每个脚本注册为一个工具）
 * @param source      来源路径或 built-in 标记
 * @param enabled     是否启用（可动态开关）
 * @param requires    依赖的技能名列表（加载前校验其已存在并启用），市场化分发的依赖声明
 * @param minApp      最低宿主应用版本（语义版本，如 "1.0.1"）；为空不校验
 * @param dirName     技能目录名（加载时由 SkillManager 写入，不要求作品牌声明）。**目录名可以与
 *                    [name] 不同**（内置技能 github-actions-skill 的 name 是 github-actions，
 *                    git clone 安装的技能目录也取自 URL 名），而脚本 entry 与 requirements.txt
 *                    一律相对技能目录解析——所以文件查找必须用 [rootDirName] 而不是 [name]。
 */
@Serializable
data class SkillDefinition(
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val tools: List<String> = emptyList(),
    val scripts: List<SkillScript> = emptyList(),
    val source: String = "",
    var enabled: Boolean = true,
    val requires: List<String> = emptyList(),
    val minApp: String = "",
    val dirName: String = ""
) {
    /** 用于显示的简短标识（计算属性，不参与序列化）。 */
    val displayName: String get() = "$name v$version"

    /**
     * 技能文件所在的目录名：脚本 entry、requirements.txt 都相对它解析。
     * 未记录目录（手工构造的 SkillDefinition）时退回 [name]。
     */
    val rootDirName: String get() = dirName.ifBlank { name }

    companion object {
        /** 从 JSON 字符串解析技能元数据（McpJson 宽松解析，对不规范 JSON 更鲁棒）。 */
        fun fromJson(json: String): SkillDefinition? = try {
            McpJson.decodeFromString<SkillDefinition>(json)
        } catch (_: Exception) { null }

        /**
         * 从 org.json.JSONObject 解析（兼容既有调用方 SkillManager）。
         * 内部转字符串后用 McpJson 解析，逐步收敛到 kotlinx.serialization。
         */
        fun fromJsonObject(obj: org.json.JSONObject): SkillDefinition =
            McpJson.decodeFromString<SkillDefinition>(obj.toString())
    }
}

/**
 * 宿主应用版本信息。用于技能 `minApp` 字段的版本门槛校验。
 *
 * 未启用 `buildConfig`（AGP 默认关闭），这里以常量集中声明当前版本号，
 * 与 app/build.gradle.kts 的 `versionName` 保持一致；改版时同步更新此常量。
 */
object AppVersion {
    const val CURRENT = "1.0.1"
}

/**
 * 语义版本比较（仅比较 `major.minor.patch` 数字段，忽略预发布/构建后缀）。
 * @return a<b 为负、a==b 为零、a>b 为正。
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.')
    val pb = b.trim().split('.')
    val len = maxOf(pa.size, pb.size)
    for (i in 0 until len) {
        val na = pa.getOrNull(i)?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val nb = pb.getOrNull(i)?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (na != nb) return na.compareTo(nb)
    }
    return 0
}