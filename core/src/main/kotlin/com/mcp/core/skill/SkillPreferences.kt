package com.mcp.core.skill

import android.content.Context

/** 技能开关的持久化边界，避免业务层直接依赖设置页实现。 */
object SkillPreferences {
    const val PREF_FILE = "tab_bar_mode"
    const val PREF_SKILL_ENABLED = "skill_enabled_"

    fun isEnabled(context: Context, name: String): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(PREF_SKILL_ENABLED + name, false)

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_SKILL_ENABLED + name, enabled)
            .apply()
    }
}
