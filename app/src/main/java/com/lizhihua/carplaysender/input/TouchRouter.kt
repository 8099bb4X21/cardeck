package com.lizhihua.carplaysender.input

import android.content.Context
import com.lizhihua.carplaysender.shizuku.CarInputClient
import com.lizhihua.carplaysender.input.TouchReports.Contact

/**
 * 触摸路由：把车机 hidSendReport 触点注入到车机侧虚拟屏（隐形屏模式）。
 *
 * 按主画面 x 坐标分流：x < dock 宽 → dock 屏；其余 → 应用屏（x 平移 dock 宽）。
 * 注入经 Shizuku UserService（1:1 真实坐标与毫秒级注入）。
 *
 * （原「桌面」（Presentation 直投）与「镜像」（无障碍 continueStroke）两种模式
 *   已按需移除，2026-10-10。）
 */
object TouchRouter {

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

        val launcher = com.lizhihua.carplaysender.launcher.VirtualLauncher
        val dockW = launcher.dockWidthPx
        if (dockW <= 0 || launcher.appDisplayId < 0 || launcher.dockDisplayId < 0) return
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
        // 主画面坐标 → 分流：
        //  横屏：dock 左侧竖条（x < dockW），app 在右（x 平移 dockW）
        //  竖屏：dock 底部横条（y > appHeight），app 在上
        val inDock: Boolean
        val targetDisplay: Int
        val localX: Int
        val localY: Int
        if (launcher.dockAtBottom) {
            val appH = launcher.appHeightPx
            inDock = contact.yPixels >= appH
            targetDisplay = if (inDock) launcher.dockDisplayId else launcher.appDisplayId
            localX = contact.xPixels
            localY = if (inDock) contact.yPixels - appH else contact.yPixels
        } else {
            inDock = contact.xPixels < dockW
            // 活动应用屏（原生或超采样）：注入坐标是所注入虚显的物理像素
            targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
            val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
            localX = if (inDock) contact.xPixels else ((contact.xPixels - dockW) * scale).toInt()
            localY = (contact.yPixels * scale).toInt()
        }
        if (targetDisplay < 0) {
            diag("目标屏无效 dock=${launcher.dockDisplayId} app=${launcher.appDisplayId}")
            return
        }
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

    fun reset() {
        pressed = false
        pressedDisplay = -1
    }
}
