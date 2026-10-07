package com.mcp.floatwin

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * 浮窗宿主抽象：把「窗口从哪来」和「拖拽怎么算」彻底解耦，两种宿主共用同一套拖拽逻辑。
 *
 * - [OverlayWindowHost]：系统级 overlay（WindowManager + TYPE_APPLICATION_OVERLAY），可浮在
 *   其他应用之上，需要 SYSTEM_ALERT_WINDOW 权限；
 * - [InAppHost]：应用内宿主（挂到 Activity 的 content 上），零权限，是无权限时的自动降级形态。
 *
 * 两者坐标系一致：原点为宿主可用区域左上角，[move] 传入浮窗左上角坐标。
 */
interface FloatHost {

    /** 是否系统级（可跨应用显示）。 */
    val isSystemLevel: Boolean

    fun hostWidth(): Int

    fun hostHeight(): Int

    /** 挂载浮窗根视图；w/h 为像素尺寸，x/y 为初始左上角位置。 */
    fun attach(root: View, w: Int, h: Int, x: Int, y: Int)

    fun move(x: Int, y: Int)

    /** 改变浮窗尺寸（缩放手柄拖动时调用）；默认空实现以兼容不需缩放的宿主。 */
    fun updateSize(w: Int, h: Int) {}

    /** 拖动中：overlay 需临时让出窗口焦点，否则会一直占着宿主 App 的输入法与返回键。 */
    fun setDragging(dragging: Boolean)

    /** 浮窗内部被触摸：overlay 恢复窗口焦点（浮窗里的网页才能输入）。 */
    fun setContentFocused(focused: Boolean)

    fun detach()
}

/**
 * 系统级宿主：WindowManager + TYPE_APPLICATION_OVERLAY（API 26+ 必须用该类型，
 * 旧的 TYPE_PHONE 在 8.0+ 会被系统直接拒）。
 *
 * 焦点策略（浮窗最容易踩的坑）：
 * - 默认带 FLAG_NOT_FOCUSABLE，弹出时不抢走宿主 App 的输入法；
 * - 用户点进浮窗内部 → 去掉该 flag，窗口可聚焦，网页输入框能拉起输入法；
 * - 拖动 / 点浮窗外部 → 重新加回该 flag，把焦点还给宿主 App。
 * FLAG_NOT_TOUCH_MODAL 保证浮窗之外的触摸继续穿透给下面的 App。
 */
class OverlayWindowHost(context: Context) : FloatHost {

    private val appContext: Context = context.applicationContext
    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null

    override val isSystemLevel: Boolean = true

    override fun hostWidth(): Int = appContext.resources.displayMetrics.widthPixels

    override fun hostHeight(): Int = appContext.resources.displayMetrics.heightPixels

    @Suppress("DEPRECATION")
    override fun attach(root: View, w: Int, h: Int, x: Int, y: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            w,
            h,
            type,
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        rootView = root
        params = lp
        windowManager.addView(root, lp)
    }

    override fun move(x: Int, y: Int) {
        val lp = params ?: return
        if (lp.x == x && lp.y == y) return
        lp.x = x
        lp.y = y
        runCatching { windowManager.updateViewLayout(rootView, lp) }
    }

    override fun updateSize(w: Int, h: Int) {
        val lp = params ?: return
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        runCatching { windowManager.updateViewLayout(rootView, lp) }
    }

    override fun setDragging(dragging: Boolean) = applyFocusable(!dragging)

    override fun setContentFocused(focused: Boolean) = applyFocusable(focused)

    override fun detach() {
        val view = rootView ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        rootView = null
        params = null
    }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun applyFocusable(focusable: Boolean) {
        val lp = params ?: return
        val flags =
            baseFlags() or if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (lp.flags == flags) return
        lp.flags = flags
        runCatching { windowManager.updateViewLayout(rootView, lp) }
    }
}

/**
 * 应用内宿主：把浮窗作为普通子 View 挂进 Activity 的 content 区。
 * 零权限、零窗口开销，缺点是只能在 App 前台看到（无权限时的降级形态）。
 */
class InAppHost(activity: Activity) : FloatHost {

    private val activityRef = WeakReference(activity)
    private var rootView: View? = null

    override val isSystemLevel: Boolean = false

    override fun hostWidth(): Int =
        container()?.width?.takeIf { it > 0 }
            ?: activityRef.get()?.resources?.displayMetrics?.widthPixels ?: 0

    override fun hostHeight(): Int =
        container()?.height?.takeIf { it > 0 }
            ?: activityRef.get()?.resources?.displayMetrics?.heightPixels ?: 0

    override fun attach(root: View, w: Int, h: Int, x: Int, y: Int) {
        val parent = container() ?: throw IllegalStateException("Activity 内容区不可用")
        val lp = FrameLayout.LayoutParams(w, h)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = x
        lp.topMargin = y
        root.layoutParams = lp
        parent.addView(root)
        rootView = root
    }

    override fun move(x: Int, y: Int) {
        val view = rootView ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.leftMargin == x && lp.topMargin == y) return
        lp.leftMargin = x
        lp.topMargin = y
        view.layoutParams = lp
    }

    override fun updateSize(w: Int, h: Int) {
        val view = rootView ?: return
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        view.layoutParams = lp
    }

    override fun setDragging(dragging: Boolean) {
        // 应用内浮窗与宿主同窗口，焦点/输入法由系统按普通 View 规则处理，无需干预
    }

    override fun setContentFocused(focused: Boolean) {
        // 同上：应用内不需要额外的窗口焦点切换
    }

    override fun detach() {
        val view = rootView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        rootView = null
    }

    private fun container(): ViewGroup? = activityRef.get()?.findViewById(android.R.id.content)
}
