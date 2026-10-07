package com.mcp.floatwin

import android.content.Context
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 浮窗缩放手柄控制器：拖动右下角手柄改变浮窗宽高。
 *
 * 与 [FloatDragController]（整体拖拽/吸附）分离：两者都监听触摸，但一个改位置、一个改尺寸，
 * 分开后各自逻辑简单、不会互相干扰。
 *
 * 约束：
 *  - 最小尺寸 [minW] x [minH]，避免缩到看不见；
 *  - 最大不超过宿主可用区域；
 *  - 缩放过程中实时 [FloatHost.updateSize]（若宿主支持），松手后记住尺寸。
 */
class FloatResizeController(
    private val host: FloatHost,
    private val root: View,
    private val handle: View,
    private val prefsKey: String,
    private val getPos: () -> Pair<Int, Int>,
    private val onSizeChanged: (Int, Int) -> Unit,
) {

    private val prefs = root.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val minW = dp(root.context, 200f)
    private val minH = dp(root.context, 160f)

    private var startW = 0
    private var startH = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var resizing = false

    fun attach() {
        handle.setOnTouchListener { _, event -> onTouch(event) }
    }

    fun detach() {
        handle.setOnTouchListener(null)
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizing = true
                host.setDragging(true) // 缩放期间禁用边缘吸附/回弹动画
                downRawX = event.rawX
                downRawY = event.rawY
                startW = root.width
                startH = root.height
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!resizing) return false
                val dw = (event.rawX - downRawX).roundToInt()
                val dh = (event.rawY - downRawY).roundToInt()
                val maxW = max(minW, host.hostWidth() - getPos().first)
                val maxH = max(minH, host.hostHeight() - getPos().second)
                val w = (startW + dw).coerceIn(minW, maxW)
                val h = (startH + dh).coerceIn(minH, maxH)
                host.updateSize(w, h)
                onSizeChanged(w, h)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!resizing) return false
                resizing = false
                host.setDragging(false)
                prefs.edit()
                    .putInt(prefsKey + KEY_W, root.width)
                    .putInt(prefsKey + KEY_H, root.height)
                    .apply()
                return true
            }
        }
        return false
    }

    /** 读取记忆尺寸；无记忆返回 null。 */
    fun rememberedSize(): Pair<Int, Int>? {
        val w = prefs.getInt(prefsKey + KEY_W, 0)
        val h = prefs.getInt(prefsKey + KEY_H, 0)
        return if (w > 0 && h > 0) Pair(w, h) else null
    }

    private companion object {
        const val PREFS_NAME = "float_window"
        const val KEY_W = "_w"
        const val KEY_H = "_h"

        fun dp(context: Context, value: Float): Int =
            (value * context.resources.displayMetrics.density).roundToInt()
    }
}
