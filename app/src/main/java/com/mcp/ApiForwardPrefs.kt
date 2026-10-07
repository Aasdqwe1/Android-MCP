package com.mcp

import android.content.Context

/**
 * API 转发（外部调用）访问控制偏好。
 *
 * - enabled：OpenAI 兼容端点（/v1/...）总开关，**默认关闭**。
 *   关闭时 /v1/... 一律返回 404，相当于不对外暴露；开启后才可用。
 *   与 [McpPrefs] 相互独立：MCP 工具发射（/mcp）与 LLM 兼容转发（/v1）
 *   语义不同，不应互相牵连。
 *
 * - sessionId：外部请求未显式携带会话时，默认归属的会话 id。
 *   空字符串表示「未指定」，此时回退到桌面桥当前焦点会话 / 列表最新。
 *
 * 与 [ToolPrefs] / [McpPrefs] 同风格：SharedPreferences 持久化，静态方法读写。
 */
object ApiForwardPrefs {
    private const val PREF_FILE = "api_forward"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_THINKING = "thinking"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** API 转发是否启用（默认 false，用户需显式开启）。 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 默认归属会话 id；空字符串表示未指定（回退自动解析）。 */
    fun sessionId(context: Context): String =
        prefs(context).getString(KEY_SESSION_ID, "") ?: ""

    fun setSessionId(context: Context, sid: String) {
        prefs(context).edit().putString(KEY_SESSION_ID, sid.trim()).apply()
    }

    /**
     * /v1 在客户端**未显式给出** `reasoning_effort` 时的默认思考开关。
     *
     * 默认 true —— 与改造前的"硬编码 true"行为一致，避免升级后外部客户端的输出风格突变。
     *
     * 客户端显式给出时的映射（见 OpenAICompatEndpoint）：
     *  - `none` → 关闭
     *  - 其他任何档位（`minimal`/`low`/`medium`/`high`/`xhigh`）→ 开启
     *
     * ⚠️ 只能做"开/关"：DeepSeek 逆向协议发的是 `thinking_enabled: bool`，
     * 没有档位概念（真档位 reasoningEffort 是 OpenAIProfile 独有字段）。
     * 设置页文案不要写成"支持推理强度调节"。
     */
    fun isThinkingDefault(context: Context): Boolean =
        prefs(context).getBoolean(KEY_THINKING, true)

    fun setThinkingDefault(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_THINKING, on).apply()
    }
}
