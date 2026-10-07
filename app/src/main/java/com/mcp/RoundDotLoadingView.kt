package com.mcp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 环形 8 圆点加载动画（替代 loading.gif）。
 *
 * 视觉参考：8 个紫色圆点沿圆周等距排列（间隔 45°），每个圆点以 1.1s 为周期做
 * 透明度 + 缩放呼吸；相邻圆点延迟 0.1s 启动，形成「接力点亮」的环形旋转观感。
 *
 * 动画驱动：postOnAnimation 自调度循环（vsync 帧回调），绘制按 wall-clock 推算相位。
 * 不用 ValueAnimator——它受系统「动画程序时长缩放=关闭」（部分 ROM 默认/开发者选项）
 * 影响后不产生任何帧，圆点会完全静止；Choreographer 帧回调不受该设置影响。
 *
 * 用法：
 *   val v = RoundDotLoadingView(context)
 *   v.start()   // 开始动画
 *   v.stop()    // 停止动画并静止显示
 */
class RoundDotLoadingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotCount = 8
    private val period = 1100L          // 单点呼吸周期 1.1s
    private val delayStep = 100L        // 相邻点延迟 0.1s
    private val baseColor = 0xFFB87DFF.toInt()  // 静止态紫色（32 位 ARGB）
    private val activeColor = 0xFFA85FF7.toInt() // 激活态紫色

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 自调度重绘循环：每帧 invalidate 后再预约下一帧，直到 [running] 变 false。 */
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

    private var startTime = 0L
    private var running = false

    /** 开始动画（已运行时幂等返回）。 */
    fun start() {
        if (running) return
        running = true
        startTime = SystemClock.uptimeMillis()
        if (isAttachedToWindow) postOnAnimation(tick)
        invalidate()
    }

    /** 停止动画并保留当前帧。 */
    fun stop() {
        if (!running) return
        running = false
        removeCallbacks(tick)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 条目滚出屏幕（detach）期间循环会被摘除，滚回来重新接上。
        if (running) postOnAnimation(tick)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val orbitRadius = size * 0.40f   // 轨道半径（原 CSS translateY(-78px)/100px ≈ 0.78，等比换算到 View 内径）
        val dotRadius = size * 0.14f     // 圆点半径（原 CSS 28px/200px = 0.14）

        val now = SystemClock.uptimeMillis()
        val elapsed = now - startTime

        for (i in 0 until dotCount) {
            // 角度：从顶部（-90°）开始，顺时针每 45° 一个点
            val angle = -Math.PI / 2 + 2 * Math.PI * i / dotCount
            val dotCx = cx + orbitRadius * cos(angle).toFloat()
            val dotCy = cy + orbitRadius * sin(angle).toFloat()

            // 每点相位：考虑延迟 0.1s * i
            val localElapsed = ((elapsed - i * delayStep) % period + period) % period
            val t = localElapsed / period  // 0..1

            // 关键帧插值：0%→(0.25, 0.85)，50%→(1.0, 1.15)，100%→(0.25, 0.85)
            val opacity: Float
            val scale: Float
            if (t < 0.5f) {
                val p = t / 0.5f
                opacity = 0.25f + 0.75f * p
                scale = 0.85f + 0.30f * p
            } else {
                val p = (t - 0.5f) / 0.5f
                opacity = 1.0f - 0.75f * p
                scale = 1.15f - 0.30f * p
            }

            paint.color = if (opacity > 0.6f) activeColor else baseColor
            paint.alpha = (opacity * 255).toInt()
            canvas.drawCircle(dotCx, dotCy, dotRadius * scale, paint)
        }
    }
}
