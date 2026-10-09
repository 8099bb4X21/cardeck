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

    private var pressed = false
    private var pressedDisplay = -1

    fun onContact(context: Context, contact: Contact) {
        // 空闲槽占位（未按下且 0,0）忽略
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
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
                val svc = CarInputClient.service
                if (svc == null) {
                    Log.w("TouchRouter", "隐形屏注入服务不可用")
                    return
                }
                // 主画面坐标 → 分流：dock 区（x < dockW）→ dock 屏；其余 → 应用屏（x 平移）
                val inDock = contact.xPixels < dockW
                val targetDisplay = if (inDock) launcher.dockDisplayId else launcher.appDisplayId
                if (targetDisplay < 0) return
                val localX = if (inDock) contact.xPixels else contact.xPixels - dockW
                val localY = contact.yPixels
                try {
                    if (contact.down && !pressed) {
                        pressed = true
                        pressedDisplay = targetDisplay
                        svc.pointerDown(targetDisplay, localX, localY)
                    } else if (contact.down) {
                        // 跨屏拖动：先在旧屏收笔，再在新屏按下
                        if (pressedDisplay != targetDisplay) {
                            runCatching { svc.pointerUp(pressedDisplay, 0, 0) }
                            svc.pointerDown(targetDisplay, localX, localY)
                            pressedDisplay = targetDisplay
                        } else {
                            svc.pointerMove(targetDisplay, localX, localY)
                        }
                    } else {
                        pressed = false
                        svc.pointerUp(pressedDisplay, localX, localY)
                    }
                } catch (e: Exception) {
                    Log.w("TouchRouter", "注入失败：${e.message}")
                }
            }
        }
    }

    private var lastX = 0f
    private var lastY = 0f

    fun reset() {
        pressed = false
        StrokeInjector.reset()
    }
}
