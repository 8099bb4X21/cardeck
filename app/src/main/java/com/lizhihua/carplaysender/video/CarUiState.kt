package com.lizhihua.carplaysender.video

import com.lizhihua.carplaysender.input.TouchReports
import java.util.concurrent.atomic.AtomicInteger

/**
 * 车机桌面 UI 状态（当前为交互测试界面：三个按钮 + 点击计数）。
 * VideoSender 每帧从这读取渲染；车机触摸经 TouchReports 解析后写入。
 * 这是「点车机屏 → 手机状态变更 → 画面回传车机」闭环的可视载体，
 * 后续由正式车机桌面（导航/音乐）替换。
 */
class CarUiState(val width: Int, val height: Int) {
    val taps = AtomicInteger(0)

    @Volatile
    var activeButton = -1
        private set

    @Volatile
    var lastTouch = "—"

    val buttonLabels = listOf("红色", "绿色", "蓝色")
    val buttonColors = listOf(0xFFE53935, 0xFF43A047, 0xFF1E88E5)

    fun buttonTop(index: Int): Float = height * (0.35f + index * 0.2f)
    val buttonHeight: Float get() = height * 0.14f
    val buttonLeft: Float get() = width * 0.15f
    val buttonWidth: Float get() = width * 0.7f

    fun onTouch(contact: TouchReports.Contact) {
        // 空闲槽占位（未按下且坐标为 0）不处理——否则会覆盖真实手指的坐标并误清高亮
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
        lastTouch = "(${contact.xPixels},${contact.yPixels}) ${if (contact.down) "按下" else "抬起"}"
        if (contact.down) {
            val hit = hitTest(contact.xPixels.toFloat(), contact.yPixels.toFloat())
            if (hit >= 0) {
                activeButton = hit
                taps.incrementAndGet()
            }
        } else {
            activeButton = -1
        }
    }

    fun hitTest(x: Float, y: Float): Int {
        for (index in buttonLabels.indices) {
            if (x >= buttonLeft && x <= buttonLeft + buttonWidth &&
                y >= buttonTop(index) && y <= buttonTop(index) + buttonHeight
            ) {
                return index
            }
        }
        return -1
    }
}
