package com.mcp

import android.content.Context

/**
 * MCP 服务端的访问控制偏好。
 *
 * - enabled：MCP 端点总开关（默认开）。关闭后 /mcp 返回 404，相当于不对外暴露。
 * - bearerToken：为空表示不校验（内网裸跑）；配置后客户端必须带
 *   `Authorization: Bearer <token>` 才能调用。
 *
 * 与 [ToolPrefs] 同风格：SharedPreferences 持久化，读写都是静态方法。
 */
object McpPrefs {
    private const val PREF_FILE = "mcp_server"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "bearer_token"
    private const val KEY_POLL_ENABLED = "poll_enabled"
    private const val KEY_POLL_INTERVAL = "poll_interval_ms"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** MCP 端点是否启用（默认 true）。 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Bearer token；空字符串表示不校验。 */
    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "") ?: ""

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    /**
     * 从 `Authorization` 头解析出 Bearer 凭据。
     *
     * RFC 6750 §2.1：scheme（"Bearer"）**大小写不敏感**，凭据与 scheme 之间至少一个空白。
     * 旧实现用 `removePrefix("Bearer ")` + `removePrefix("bearer ")` 两条硬编码前缀，
     * 遇到 `BEARER` / `bearer\t` / 多空格就会解析失败 —— 表现为"token 明明填对了却 401"。
     *
     * @return 凭据字符串；头缺失、scheme 不匹配、或凭据为空时返回 null。
     */
    fun bearerFrom(header: String?): String? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val m = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE).find(raw) ?: return null
        return m.groupValues[1].trim().ifEmpty { null }
    }

    /**
     * 统一的 Bearer 校验：返回 null 表示放行，否则是拒绝原因。
     *
     * token 为空时**不校验**（内网裸跑语义，见 [token]）—— 这是既定策略，不是遗漏。
     *
     * ⚠️ /mcp、/v1/models、/v1/chat/completions 三处必须共用本方法。
     * 历史上这三处各写了一份解析逻辑，口径已经不一致（且都是硬编码前缀版本），
     * 改一处漏两处只是时间问题。
     */
    fun checkBearer(context: Context, authHeader: String?): String? {
        val expected = token(context)
        if (expected.isEmpty()) return null
        if (bearerFrom(authHeader) != expected) return "缺少或无效的 Bearer token"
        return null
    }

    // ───────── 远程轮询 ─────────

    /** 是否周期探测远程 MCP 服务器（默认开）。 */
    fun isPollingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POLL_ENABLED, true)

    fun setPollingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_POLL_ENABLED, enabled).apply()
    }

    /** 轮询间隔（毫秒），默认 5000；限制在 1s ~ 10min 之间。 */
    fun pollIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_POLL_INTERVAL, 5_000L)

    fun setPollIntervalMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_POLL_INTERVAL, ms.coerceIn(1_000L, 600_000L)).apply()
    }
}
