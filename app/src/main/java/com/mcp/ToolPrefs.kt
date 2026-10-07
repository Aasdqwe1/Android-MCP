package com.mcp

import android.content.Context

/**
 * 工具开关持久化：用户在「工具」页面切换某个工具的启用/禁用状态时，
 * 这里保存到 SharedPreferences；下次 App 启动 ToolRuntimeFactory 读取此状态，
 * 决定哪些工具注册进 [Toolbox]（进而决定 LLM 能否看到 / 调用该工具）。
 *
 * 默认全部启用（[getBoolean] 缺省值 = true）。用户主动关闭后保存 false；
 * 重新打开恢复 true。这样即使某个工具出问题，用户也能一键禁用而不用改代码。
 */
object ToolPrefs {
    private const val PREFS_NAME = "tool_prefs"
    private const val KEY_PREFIX = "tool_enabled_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 某工具当前是否启用。未保存过则返回 true（默认启用）。 */
    fun isEnabled(context: Context, toolName: String): Boolean =
        prefs(context).getBoolean(KEY_PREFIX + toolName, true)

    /** 保存某工具的启用状态（持久化）。 */
    fun setEnabled(context: Context, toolName: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + toolName, enabled).apply()
    }

    /**
     * 工具页的分组折叠状态。
     *
     * 与启用开关共用同一个 SharedPreferences 文件，但 key 前缀不同（[KEY_GROUP_COLLAPSED]）——
     * 「开关」和「折叠」是两类互不相干的用户偏好，共用文件只是省一个 prefs 句柄，
     * 前缀分开保证 [resetAll] 之外不会互相误伤。
     *
     * 默认**展开**（缺省 false）：折叠是用户主动收起的结果，不该是首次进入的默认视图。
     */
    private const val KEY_GROUP_COLLAPSED = "group_collapsed_"

    /** 某分组当前是否折叠。未保存过则返回 false（默认展开）。 */
    fun isGroupCollapsed(context: Context, groupId: String): Boolean =
        prefs(context).getBoolean(KEY_GROUP_COLLAPSED + groupId, false)

    /** 保存某分组的折叠状态。 */
    fun setGroupCollapsed(context: Context, groupId: String, collapsed: Boolean) {
        prefs(context).edit().putBoolean(KEY_GROUP_COLLAPSED + groupId, collapsed).apply()
    }

    /**
     * 重置所有开关到默认（全启用 + 全展开）。用于「恢复默认」菜单项。
     *
     * 注意 clear() 会**同时**清掉折叠状态——这是刻意的：既然叫「恢复默认」，
     * 视图状态也该回到默认，否则用户点了恢复默认却仍看到一堆收起的组，会以为没生效。
     */
    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
