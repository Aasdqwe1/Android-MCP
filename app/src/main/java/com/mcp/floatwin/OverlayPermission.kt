package com.mcp.floatwin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 系统级悬浮窗（「显示在其他应用上层」）权限工具。
 *
 * SYSTEM_ALERT_WINDOW 属于「特殊权限」：API 23+ 起不能像普通运行时权限那样弹窗申请，
 * 只能先 [granted] 检查、再 [request] 跳系统设置页由用户手动开启。
 * 因此浮窗统一走「有权限 → 系统级 overlay；无权限 → 应用内降级」策略（见 [BrowserFloatWindow]）。
 *
 * 国产 ROM 的入口名称各不相同，[guideText] 按厂商给出更准确的路径，减少用户找不到的情况。
 */
object OverlayPermission {

    /** 是否已获得系统悬浮窗权限。 */
    fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 跳转系统授权页；返回 false 表示系统没有该设置页（此时只能走应用内降级）。 */
    fun request(context: Context): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 「已降级为应用内浮窗」时给用户的引导文案（带厂商对应的设置路径）。 */
    fun guideText(context: Context): String {
        val brand = Build.BRAND.orEmpty().lowercase()
        val path = when {
            brand.contains("xiaomi") || brand.contains("redmi") ->
                "设置 → 应用设置 → 权限管理 → 显示在其他应用上层"
            brand.contains("huawei") || brand.contains("honor") ->
                "设置 → 应用 → 权限 → 悬浮窗"
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                "设置 → 应用管理 → 悬浮窗"
            brand.contains("vivo") || brand.contains("iqoo") ->
                "设置 → 更多设置 → 权限管理 → 悬浮窗"
            else ->
                "设置 → 应用 → 特殊应用权限 → 显示在其他应用上层"
        }
        return "已弹出应用内小窗（只在本 App 内可见）。若想让网页浮在其他应用之上，请开启悬浮窗权限：" + path
    }
}
