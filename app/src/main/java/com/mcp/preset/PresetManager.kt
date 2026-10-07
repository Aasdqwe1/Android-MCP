package com.mcp.preset

import android.content.Context
import com.mcp.serialization.McpJson
import java.io.File

/**
 * 预设注册表：从 `assets/presets/` 目录下的所有 .json 预设文件、与应用私有目录加载预设，
 * 供运行时按 id 查询。
 *
 * 接入方式（见设计文档）：
 * - 应用启动时 [loadFromAssets] 一次；
 * - 用户导入或删除预设后 [loadFromDir] + [register] 热更新；
 * - 主会话组工具与系统提示词时，用当前会话所选预设的 [Preset.allows] 过滤。
 */
object PresetManager {
    private val presets = LinkedHashMap<String, Preset>()
    private var defaultId: String = "full"

    /** 从应用 assets/presets 目录加载全部 *.json 预设。 */
    fun loadFromAssets(ctx: Context) {
        runCatching {
            ctx.assets.list("presets")?.forEach { name ->
                if (name.endsWith(".json", ignoreCase = true)) {
                    val text = ctx.assets.open("presets/$name").bufferedReader(Charsets.UTF_8).readText()
                    runCatching { McpJson.decodeFromString<Preset>(text) }.getOrNull()?.let { presets[it.id] = it }
                }
            }
        }
    }

    /** 从用户目录加载预设（导入/在线安装后调用）。 */
    fun loadFromDir(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }?.forEach { f ->
            runCatching { McpJson.decodeFromString<Preset>(f.readText()) }.getOrNull()?.let { presets[it.id] = it }
        }
    }

    /** 注册/覆盖一个预设（也供代码内置预设使用）。 */
    fun register(p: Preset) { presets[p.id] = p }

    fun get(id: String): Preset? = presets[id]
    fun list(): List<Preset> = presets.values.toList()
    fun names(): List<String> = presets.keys.toList()

    /** 默认预设（空白名单 = 全量工具）。 */
    val default: Preset? get() = presets[defaultId]
    fun setDefault(id: String) { if (presets.containsKey(id)) defaultId = id }
}
