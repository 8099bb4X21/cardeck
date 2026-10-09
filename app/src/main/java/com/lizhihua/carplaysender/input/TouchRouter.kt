package com.lizhihua.carplaysender.input

import android.content.Context
import android.util.Log
import com.lizhihua.carplaysender.capture.HiddenDisplaySource
import com.lizhihua.carplaysender.shizuku.CarInputClient
import com.lizhihua.carplaysender.ui.CarDisplayRenderer
import com.lizhihua.carplaysender.input.TouchReports.Contact

/**
 * 触摸路由：按当前模式把车机 hidSendReport 触点分发给正确的注入通道。
 * - 桌面：Presentation decorView（标准 View 体系）
 * - 镜像：坐标换算（fit-center）→ 无障碍 continueStroke 注入手机主屏
 * - 隐形屏：1:1 直注虚拟屏（真实坐标与时间戳，经 Shizuku UserService 毫秒级注入）
 */
object TouchRouter {

    enum class Mode { DESKTOP, MIRROR, HIDDEN }

    @Volatile
    var mode: Mode = Mode.DESKTOP

    // 各模式的目标（由会话建立时注入）
    @Volatile
    var renderer: CarDisplayRenderer? = null

    @Volatile
    var hiddenSource: HiddenDisplaySource? = null

    /** 镜像模式：车机显示尺寸（坐标换算用）。 */
    @Volatile
    var carWidth: Int = 0

    @Volatile
    var carHeight: Int = 0

    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - diagAt < 1000L) return
        diagAt = now
        logSink?.invoke("TOUCH: $message")
    }

    private var pressed = false
    private var pressedDisplay = -1
    private var failStreak = 0

    fun onContact(context: Context, contact: Contact) {
        // 空闲槽占位（未按下且 0,0）忽略
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
        // 车机侧触摸踪迹：任务看护用它区分「车机操作」与「手机操作」
        com.lizhihua.carplaysender.launcher.VirtualLauncher.onCarTouch()
        when (mode) {
            Mode.DESKTOP -> renderer?.dispatchTouch(contact)

            Mode.MIRROR -> {
                val mapped = CoordinateMapper.map(
                    context,
                    contact.xPixels.toFloat(),
                    contact.yPixels.toFloat(),
                    carWidth,
                    carHeight,
                )
                if (mapped == null) {
                    // 黑边区域：若按住中则收笔
                    if (contact.down.not() && pressed) StrokeInjector.onUp(lastX, lastY)
                    return
                }
                val (px, py) = mapped
                if (contact.down && !pressed) {
                    pressed = true
                    lastX = px
                    lastY = py
                    StrokeInjector.onDown(px, py)
                } else if (contact.down) {
                    lastX = px
                    lastY = py
                    StrokeInjector.onMove(px, py)
                } else {
                    pressed = false
                    StrokeInjector.onUp(px, py)
                }
            }

            Mode.HIDDEN -> {
                val launcher = com.lizhihua.carplaysender.launcher.VirtualLauncher
                val dockW = launcher.dockWidthPx
                if (dockW <= 0 || launcher.appDisplayId < 0) return
                var svc = CarInputClient.service
                if (svc == null) {
                    // 主动触发重绑（手机重启/Shizuku 重启后 binder 丢失）
                    CarInputClient.ensure(context)
                    svc = CarInputClient.service
                }
                if (svc == null) {
                    failStreak++
                    diag(
                        "注入服务不可用（连续 $failStreak 次，自动重绑中；" +
                            "若持续失效请打开 Shizuku App 或在系统设置中允许其后台运行）",
                    )
                    return
                }
                failStreak = 0
                // 主画面坐标 → 分流：dock 区（x < dockW）→ dock 屏；其余 → 应用屏（x 平移）
                val inDock = contact.xPixels < dockW
                val targetDisplay = if (inDock) launcher.dockDisplayId else launcher.appDisplayId
                if (targetDisplay < 0) {
                    diag("目标屏无效 dock=${launcher.dockDisplayId} app=${launcher.appDisplayId}")
                    return
                }
                val localX = if (inDock) contact.xPixels else contact.xPixels - dockW
                val localY = contact.yPixels
                try {
                    val ok: Boolean = when {
                        contact.down && !pressed -> {
                            pressed = true
                            pressedDisplay = targetDisplay
                            svc.pointerDown(targetDisplay, localX, localY)
                        }

                        contact.down && pressedDisplay != targetDisplay -> {
                            // 跨屏拖动：先在旧屏收笔，再在新屏按下
                            runCatching { svc.pointerUp(pressedDisplay, 0, 0) }
                            pressedDisplay = targetDisplay
                            svc.pointerDown(targetDisplay, localX, localY)
                        }

                        contact.down -> svc.pointerMove(targetDisplay, localX, localY)

                        else -> {
                            pressed = false
                            svc.pointerUp(pressedDisplay, localX, localY)
                        }
                    }
                    if (!ok) {
                        diag(
                            "注入被拒 disp=$targetDisplay ($localX,$localY) " +
                                "down=${contact.down} pressed=$pressed pressedDisplay=$pressedDisplay",
                        )
                    } else if (contact.down) {
                        diag("注入OK disp=$targetDisplay ($localX,$localY)")
                    }
                } catch (e: Exception) {
                    diag("注入异常：${e.message}")
                }
            }
        }
    }

    private var lastX = 0f
    private var lastY = 0f

    fun reset() {
        pressed = false
        pressedDisplay = -1
        StrokeInjector.reset()
    }
}
