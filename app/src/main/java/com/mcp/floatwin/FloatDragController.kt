package com.mcp.floatwin

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 浮窗拖拽控制器：拖动跟手 / 边缘吸附 / 越界回弹 / 位置记忆。
 *
 * 与 [FloatHost] 组合使用，因此「系统级 overlay」与「应用内浮窗」共用同一套手势逻辑。
 * 交互设计取自主流开源悬浮窗实现（EasyFloat / FloatingX / FloatWindow 等）的通行做法：
 * 按住把手拖动 → 松手吸附到最近的左/右边缘（带回弹动画）→ 位置按 key 持久化，下次弹出回到原处。
 */
class FloatDragController(
    private var host: FloatHost,
    private val root: View,
    private val handle: View,
    private val prefsKey: String
) {

    private val prefs = root.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val edgeMargin = dp(root.context, 10f)
    private val minVisible = dp(root.context, 40f)

    /** 当前宿主（Activity 重建等场景会被替换）。 */
    val currentHost: FloatHost get() = host

    /** 浮窗尺寸（像素）；0 表示尚未 attach。 */
    var width = 0
        private set

    var height = 0
        private set

    /** 当前左上角位置（供缩放手柄计算可用空间）。 */
    val posX: Int get() = x
    val posY: Int get() = y

    /**
     * 缩放手柄改变尺寸后同步：更新内部宽高，并把新尺寸写进宿主的 LayoutParams。
     *
     * 不调 [snapToEdge]：缩放时用户通常盯着右下角，突然横移会很突兀；
     * 越界由 [clampIntoHost] 在下次拖动时自然纠正。
     */
    fun syncSize(w: Int, h: Int) {
        width = w
        height = h
        host.updateSize(w, h)
    }

    /**
     * 直接设置位置（最大化 / 还原时用）。
     *
     * 与 [syncSize] 配对：最大化要同时改尺寸和位置（挪到左上角），
     * 单靠 syncSize 会留在原位置、右下角超出屏幕。
     */
    fun moveTo(newX: Int, newY: Int) {
        x = newX
        y = newY
        host.move(x, y)
    }

    /** 当前尺寸（供最大化前记录原值）。 */
    fun currentSize(): Pair<Int, Int> = Pair(width, height)

    private var x = 0
    private var y = 0
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var animator: ValueAnimator? = null
    /** 本次按下后是否发生了实质位移（用于区分「点击」与「拖拽」）。 */
    private var moved = false

    /**
     * 点击回调：按下后未发生实质位移、抬手时触发。
     *
     * 为什么需要它：本控制器在 ACTION_DOWN 就 return true 消费事件，
     * 挂在同一 View 上的 setOnClickListener 永远收不到点击——
     * 悬浮球「点一下展开」因此失效。由这里在抬手时判定并回调。
     */
    var onClick: (() -> Unit)? = null

    /** 判定「实质位移」的阈值（触摸抖动容忍）。 */
    private val touchSlop =
        android.view.ViewConfiguration.get(root.context).scaledTouchSlop

    /** 挂载并按记忆位置显示。 */
    fun attach(w: Int, h: Int) {
        width = w
        height = h
        x = prefs.getInt(prefsKey + KEY_X, maxRight())
        y = prefs.getInt(prefsKey + KEY_Y, defaultY())
        clampIntoHost()
        host.attach(root, width, height, x, y)
        handle.setOnTouchListener { _, event -> onTouch(event) }
    }

    /** 换宿主（Activity 重建后，应用内浮窗要重新挂到新窗口上）。 */
    fun reattach(newHost: FloatHost) {
        if (newHost === host) return
        host.detach()
        host = newHost
        clampIntoHost()
        host.attach(root, width, height, x, y)
    }

    /** 卸载并记住当前位置。 */
    fun detach() {
        animator?.cancel()
        animator = null
        remember()
        handle.setOnTouchListener(null)
        host.detach()
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                dragging = true
                moved = false
                host.setDragging(true)
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                // 位移超过阈值才算「拖动」，否则仍可能是点击
                if (!moved &&
                    (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                        kotlin.math.abs(event.rawY - downRawY) > touchSlop)
                ) {
                    moved = true
                }
                // 用 rawX/rawY 的增量换算：两种宿主坐标原点不同，取增量天然抵消原点差异
                x = startX + (event.rawX - downRawX).roundToInt()
                y = startY + (event.rawY - downRawY).roundToInt()
                // 拖动中允许少量越界（跟手感），松手时再吸附回可用区域
                x = x.coerceIn(-width / 2, max(0, host.hostWidth() - width / 2))
                y = y.coerceIn(0, max(0, host.hostHeight() - minVisible))
                host.move(x, y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                host.setDragging(false)
                // 未发生实质位移 → 视为点击（悬浮球靠这个展开）
                if (!moved && event.actionMasked == MotionEvent.ACTION_UP) {
                    onClick?.invoke()
                    return true
                }
                snapToEdge()
                return true
            }
        }
        return false
    }

    /** 松手：吸附到最近的左/右边缘，纵向夹在可用区域内（Overshoot 回弹）。 */
    private fun snapToEdge() {
        val targetX = if (x + width / 2f < host.hostWidth() / 2f) edgeMargin else maxRight()
        val targetY = y.coerceIn(edgeMargin, maxBottom())
        animateTo(targetX, targetY)
        x = targetX
        y = targetY
        remember()
    }

    private fun animateTo(targetX: Int, targetY: Int) {
        val fromX = x
        val fromY = y
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                host.move(
                    (fromX + (targetX - fromX) * f).roundToInt(),
                    (fromY + (targetY - fromY) * f).roundToInt()
                )
            }
            start()
        }
    }

    private fun clampIntoHost() {
        x = x.coerceIn(edgeMargin, maxRight())
        y = y.coerceIn(edgeMargin, maxBottom())
    }

    private fun maxRight(): Int = max(edgeMargin, host.hostWidth() - width - edgeMargin)

    private fun maxBottom(): Int = max(edgeMargin, host.hostHeight() - height - edgeMargin)

    private fun defaultY(): Int = minOf((host.hostHeight() * 0.26f).roundToInt(), maxBottom())

    private fun remember() {
        prefs.edit().putInt(prefsKey + KEY_X, x).putInt(prefsKey + KEY_Y, y).apply()
    }

    private companion object {
        const val PREFS_NAME = "float_window"
        const val KEY_X = "_x"
        const val KEY_Y = "_y"

        fun dp(context: Context, value: Float): Int =
            (value * context.resources.displayMetrics.density).roundToInt()
    }
}
