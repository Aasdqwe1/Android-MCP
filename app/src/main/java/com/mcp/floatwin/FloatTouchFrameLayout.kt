package com.mcp.floatwin

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 浮窗的「内容宿主」容器（XML 里作为 WebView 的挂载点）。
 *
 * 存在的唯一理由：ViewGroup 的 OnTouchListener 只在「没有任何子 View 消费该事件」时才回调，
 * 而 WebView 会吃掉全部触摸，所以只有重写 [dispatchTouchEvent] 才能稳定拿到「用户点进浮窗内部」
 * 这个信号——系统级 overlay 窗口据此把窗口切成可聚焦，让浮窗里的输入框能拉起输入法。
 */
class FloatTouchFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 手指按下回调（在子 View 处理之前触发）。 */
    var onTouchDown: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            runCatching { onTouchDown?.invoke() }
        }
        return super.dispatchTouchEvent(ev)
    }
}
