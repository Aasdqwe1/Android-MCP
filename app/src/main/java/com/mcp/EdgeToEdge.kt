package com.mcp

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 把窗口显式切到「边到边」：内容延伸到状态栏/导航栏之下，系统栏本身透明，
 * 安全区（insets）由各页面自行补内边距。
 *
 * 为什么必须显式统一，而不是交给系统默认：
 *  - 本应用 targetSdk=36，Android 15+ **强制**边到边，adjustResize 与系统自动内缩都失效，
 *    不处理 inset 的页面会直接压在通知栏/状态栏上；
 *  - 低版本默认又不是边到边，系统已经缩进过一次。
 * 若按默认走，同一个布局会在不同 API 上「一个压住状态栏、一个被重复缩进」。
 * 因此这里在所有版本上切成同一种窗口状态，再由各页面用
 * androidx.core.view.WindowInsetsCompat 统一补 padding。
 *
 * 调用时机：setContentView **之前**。
 */
object EdgeToEdge {

    fun apply(activity: Activity) {
        val window = activity.window
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    )
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}
