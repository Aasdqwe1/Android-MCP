package com.mcp

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 配置导出 / 导入（JSON 明文）。
 *
 * 设计取向：
 *  - **全量、键无关**：不逐键硬编码（键会随迭代漂移、易漏），而是枚举所有已知的
 *    SharedPreferences 文件，导出其**全部键值**（类型保真），导入时写回同名文件。
 *    新增一个偏好键无需改这里；新增一个 prefs 文件只需往 [PREF_FILES] 补一项。
 *  - **明文**：按需求含 API key / token，便于整机迁移。文件即敏感信息，用户自担。
 *  - **默认落下载目录**：`Download/agent-toolbox-config-<时间戳>.json`；导入由调用方
 *    经 SAF 选择器拿到 Uri，再交给 [importFromStream]。
 *
 * 已知不覆盖：AuthPrefs 底层的 **加密** SharedPreferences（EncryptedSharedPreferences
 * 的物理文件名 `deepseek_auth_enc`）。它的值由 Keystore 主密钥加密，换机后密钥不同、
 * 密文不可解，导出密文毫无意义。凭据类字段改由公开 API（[AuthPrefs] 的 get/set）逐项
 * 读写，见 [exportToJson] / [applyFromJson] 里的 `auth` 段。
 */
object ConfigBackup {

    private const val TAG = "ConfigBackup"

    /** 导出 JSON 的顶层结构版本，便于将来迁移。 */
    private const val FORMAT_VERSION = 1

    /**
     * 需要整体导出的明文 SharedPreferences 文件（物理文件名）。
     *
     * 注意排除：
     *  - `deepseek_auth_enc`（加密存储，密文不可跨机迁移，改走 AuthPrefs 公开 API）
     *  - `deepseek_auth`（旧版明文，仅迁移期存在，无导出价值）
     *  - `install_task_state`（运行时任务进度，非配置）
     */
    private val PREF_FILES = listOf(
        "tab_bar_mode",          // 主题、推送模式、上下文窗口、Agent 设置等（MainActivity.PREF_TAB_MODE）
        "tool_prefs",            // 工具启用/禁用（ToolPrefs）
        "mcp_server",            // MCP 本机服务端开关 / token / 轮询（McpPrefs）
        "mcp_remote",            // 远程 MCP 服务器列表 JSON（McpRemotePrefs）
        "saf_roots",             // SAF 授权根目录路径↔URI 映射（SafManager）
        "host_su",               // 宿主 root 模式（HostSu）
        "skill_deps",            // 技能依赖指纹（SkillManager）
        "tool_env",              // 上次 Gradle 根目录等（BuildTools）
        "float_window",          // 悬浮窗位置/尺寸（BrowserFloatWindow）
        "wx_conv_guide",         // 微信会话引导每页条数（WeixinConversationGuide）
        "api_forward",           // API 转发开关 / 默认会话 / 思考默认（ApiForwardPrefs）
    )

    // ───────────────────────── 导出 ─────────────────────────

    /**
     * 收集全部配置为 JSON 字符串。
     *
     * 结构：
     * ```
     * {
     *   "format": 1,
     *   "exportedAt": 1690000000000,
     *   "app": "agent-toolbox-kotlin",
     *   "prefs": { "<文件名>": { "<键>": {"t":"s","v":"..."} , ... }, ... },
     *   "auth": { "backend": "...", "preset": "...", "openaiProfiles": [...], ... }
     * }
     * ```
     * prefs 段的值统一包成 `{t: 类型, v: 值}`，因为 JSON 无法自描述 SharedPreferences
     * 的 Int/Long/Float/Set 类型；导入时按 `t` 还原，避免把 Int 存成 Double 之类。
     */
    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("app", "agent-toolbox-kotlin")

        val prefsObj = JSONObject()
        for (name in PREF_FILES) {
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefsObj.put(name, dumpPrefs(sp))
        }
        root.put("prefs", prefsObj)

        // 凭据 / 后端选择等走加密存储，用公开 API 读出后明文写入（换机后可还原）。
        root.put("auth", dumpAuth(context))

        return root.toString(2)
    }

    /** 把一个 SharedPreferences 的全部键值转成 {键: {t,v}}。 */
    private fun dumpPrefs(sp: SharedPreferences): JSONObject {
        val obj = JSONObject()
        for ((key, value) in sp.all) {
            val entry = JSONObject()
            when (value) {
                is String -> { entry.put("t", "s"); entry.put("v", value) }
                is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                is Int -> { entry.put("t", "i"); entry.put("v", value) }
                is Long -> { entry.put("t", "l"); entry.put("v", value) }
                is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                is Set<*> -> {
                    entry.put("t", "ss")
                    val arr = org.json.JSONArray()
                    value.forEach { arr.put(it?.toString() ?: "") }
                    entry.put("v", arr)
                }
                else -> { entry.put("t", "s"); entry.put("v", value?.toString() ?: "") }
            }
            obj.put(key, entry)
        }
        return obj
    }

    /**
     * 凭据 / 后端相关（加密存储）。
     *
     * 具体导出哪些字段由 [com.mcp.deepseek.AuthPrefs.exportAll] 决定——把键清单留在
     * AuthPrefs 内部，新增配置项时改那边的人顺手在同一文件里补上，避免这里维护第二份
     * 清单导致静默漏项（LiteRT / MNN / 协议风格等历史上就是这么漏的）。
     */
    private fun dumpAuth(context: Context): JSONObject =
        com.mcp.deepseek.AuthPrefs(context).exportAll()

    /** 导出到默认目录：`Download/agent-toolbox-config-<yyyyMMdd-HHmmss>.json`。 */
    fun exportToDownloads(context: Context): File {
        val json = exportToJson(context)
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "agent-toolbox-config-$stamp.json")
        file.writeText(json, Charsets.UTF_8)
        Log.i(TAG, "配置已导出: ${file.absolutePath} (${json.length} chars)")
        return file
    }

    // ───────────────────────── 导入 ─────────────────────────

    /**
     * 从输入流导入配置（覆盖写回各 prefs + 凭据）。
     *
     * 返回人类可读的结果摘要。任一步 JSON 结构不对就抛异常，避免半写。
     */
    fun importFromStream(context: Context, json: String): String {
        val root = JSONObject(json)
        val version = root.optInt("format", 0)
        if (version <= 0) throw IllegalArgumentException("不是有效的配置文件（缺少 format 字段）")

        // 1) 明文 prefs：整体覆盖
        val prefsObj = root.optJSONObject("prefs") ?: JSONObject()
        var prefFileCount = 0
        var prefKeyCount = 0
        for (name in PREF_FILES) {
            val obj = prefsObj.optJSONObject(name) ?: continue
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = sp.edit().clear()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = obj.optJSONObject(key) ?: continue
                when (entry.optString("t")) {
                    "s" -> editor.putString(key, entry.optString("v", ""))
                    "b" -> editor.putBoolean(key, entry.optBoolean("v", false))
                    "i" -> editor.putInt(key, entry.optInt("v", 0))
                    "l" -> editor.putLong(key, entry.optLong("v", 0L))
                    "f" -> editor.putFloat(key, entry.optDouble("v", 0.0).toFloat())
                    "ss" -> {
                        val arr = entry.optJSONArray("v") ?: org.json.JSONArray()
                        val set = mutableSetOf<String>()
                        for (i in 0 until arr.length()) set.add(arr.optString(i, ""))
                        editor.putStringSet(key, set)
                    }
                }
                prefKeyCount++
            }
            editor.apply()
            prefFileCount++
        }

        // 2) 凭据 / 后端（加密存储）
        val authObj = root.optJSONObject("auth")
        if (authObj != null) {
            applyAuth(context, authObj)
        }

        val summary = "已导入：$prefFileCount 个配置分组、$prefKeyCount 个键" +
            if (authObj != null) " + 凭据/后端" else ""
        Log.i(TAG, summary)
        return summary
    }

    /**
     * 凭据 / 后端导入。
     *
     * 具体写回哪些字段由 [com.mcp.deepseek.AuthPrefs.importAll] 决定（与 exportAll 互逆，
     * 同样把键清单留在 AuthPrefs 内部）。token 为空视为「不覆盖」等策略也在那边实现。
     */
    private fun applyAuth(context: Context, o: JSONObject) {
        com.mcp.deepseek.AuthPrefs(context).importAll(o)
    }
}