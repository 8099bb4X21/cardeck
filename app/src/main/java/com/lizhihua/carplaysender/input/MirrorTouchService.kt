package com.lizhihua.carplaysender.input

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Path
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager
import kotlin.math.min

/**
 * 镜像模式输入栈。
 *
 * MirrorTouchService：无障碍服务，唯一职责 dispatchGesture（不读取窗口内容）。
 * CoordinateMapper：车机像素 ↔ 手机像素（镜像为 fit-center 等比缩放，黑边点击丢弃）。
 * StrokeInjector：基于真实触点序列的持续手势注入（continueStroke）——
 * 我们持有 hidSendReport 的绝对坐标与按下/移动/抬起序列，无需任何手势重建，
 * 这是相对参考项目（AA onScroll 增量 + 分段拖拽）的根本优势。
 */
class MirrorTouchService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        MirrorTouchHolder.instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        MirrorTouchHolder.instance = null
        super.onDestroy()
    }
}

object MirrorTouchHolder {
    @Volatile
    var instance: AccessibilityService? = null

    val enabled: Boolean get() = instance != null
}

object CoordinateMapper {

    /** 车机 px → 手机 px（fit-center）；黑边返回 null。 */
    fun map(context: Context, carX: Float, carY: Float, carW: Int, carH: Int): FloatArray? {
        val phone = realSize(context)
        if (phone[0] <= 0f || phone[1] <= 0f || carW <= 0 || carH <= 0) return null
        val scale = min(carW / phone[0], carH / phone[1])
        if (!scale.isFinite() || scale <= 0f) return null
        val offX = (carW - phone[0] * scale) / 2f
        val offY = (carH - phone[1] * scale) / 2f
        val px = (carX - offX) / scale
        val py = (carY - offY) / scale
        if (px < 0f || py < 0f || px > phone[0] || py > phone[1]) return null
        return floatArrayOf(px, py)
    }

    /** 手机物理分辨率 [w, h]。 */
    fun realSize(context: Context): FloatArray {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return floatArrayOf(0f, 0f)
        val bounds = windowManager.maximumWindowMetrics.bounds
        return floatArrayOf(bounds.width().toFloat(), bounds.height().toFloat())
    }
}

/** 真实触点序列 → 无障碍持续手势（DOWN 起点 → MOVE 续笔 → UP 收笔）。 */
object StrokeInjector {

    private const val TAG = "StrokeInjector"

    private var lastStroke: android.accessibilityservice.GestureDescription.StrokeDescription? = null
    private var lastX = -1f
    private var lastY = -1f
    private var lastEventAt = 0L

    fun onDown(x: Float, y: Float) {
        val service = MirrorTouchHolder.instance ?: return
        reset()
        lastX = x
        lastY = y
        lastEventAt = SystemClock.uptimeMillis()
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.1f, y + 0.1f)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0, SEGMENT_MS, true,
        )
        dispatch(service, stroke)
        lastStroke = stroke
    }

    fun onMove(x: Float, y: Float) {
        val service = MirrorTouchHolder.instance ?: return
        val stroke = lastStroke ?: return onDown(x, y)
        if (x == lastX && y == lastY) return
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(x, y)
        }
        lastX = x
        lastY = y
        lastEventAt = SystemClock.uptimeMillis()
        val next = try {
            stroke.continueStroke(path, 0, SEGMENT_MS, true)
        } catch (_: Exception) {
            return
        }
        dispatch(service, next)
        lastStroke = next
    }

    fun onUp(x: Float, y: Float) {
        val service = MirrorTouchHolder.instance ?: return
        val stroke = lastStroke ?: run {
            // 没有进行中的笔画（如极快的点按丢失 DOWN）：退化为独立 tap
            tap(x, y)
            return
        }
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(x, y)
        }
        lastX = x
        lastY = y
        val next = try {
            stroke.continueStroke(path, 0, SEGMENT_MS, false)
        } catch (_: Exception) {
            reset()
            return
        }
        dispatch(service, next)
        reset()
    }

    fun tap(x: Float, y: Float) {
        val service = MirrorTouchHolder.instance ?: return
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        dispatch(service, android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
    }

    fun reset() {
        lastStroke = null
        lastX = -1f
        lastY = -1f
    }

    private fun dispatch(
        service: AccessibilityService,
        stroke: android.accessibilityservice.GestureDescription.StrokeDescription,
    ) {
        try {
            service.dispatchGesture(
                android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
                null,
                null,
            )
        } catch (e: Exception) {
            Log.d(TAG, "dispatch 失败：${e.message}")
        }
    }

    private const val SEGMENT_MS = 40L
}
