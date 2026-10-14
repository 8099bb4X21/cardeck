package com.lizhihua.carplaysender.input

import android.content.Context
import com.lizhihua.carplaysender.input.TouchReports.Contact
import com.lizhihua.carplaysender.shizuku.CarInputClient

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

    // 车机触摸坐标（通告视图坐标系，如 1160×540）→ 流坐标（缩放后 1450×674）的换算系数。
    // 分辨率缩放 ≠100% 时流比视图大，不换算会导致点击整体向右下漂移（dock 分流也错位）。
    @Volatile
    private var coordScaleX = 1f

    @Volatile
    private var coordScaleY = 1f

    /** 会话建立时设置：视图（车机通告 display）尺寸 → 流（编码/虚拟屏）尺寸。 */
    fun setCoordinateScale(
        viewW: Int,
        viewH: Int,
        streamW: Int,
        streamH: Int,
    ) {
        coordScaleX = if (viewW > 0) streamW.toFloat() / viewW else 1f
        coordScaleY = if (viewH > 0) streamH.toFloat() / viewH else 1f
    }

    fun onContact(
        context: Context,
        contact: Contact,
    ) {
        // 空闲槽占位（未按下且 0,0）忽略
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
        val px = (contact.xPixels * coordScaleX).toInt().coerceAtLeast(0)
        val py = (contact.yPixels * coordScaleY).toInt().coerceAtLeast(0)

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
        //  地图屏（仪表盘/全屏，优先于应用区）：全屏=应用区全部；分屏=dock 右侧 tile 区域。
        //  地图屏与流 1:1 创建，坐标直接平移；其余走活动应用屏（含超采样倍率换算）。
        var inDock: Boolean
        var targetDisplay = -1
        var localX = 0
        var localY = 0
        if (launcher.dockAtBottom) {
            val appH = launcher.appHeightPx
            inDock = py >= appH
            targetDisplay = if (inDock) launcher.dockDisplayId else launcher.appDisplayId
            localX = px
            localY = if (inDock) py - appH else py
        } else {
            inDock = px < dockW
            var routedToMap = false
            if (!inDock &&
                launcher.mapDisplayId >= 0 &&
                (launcher.mapFullscreen || launcher.dashboardMode) &&
                launcher.tileSlideFraction <= 0.01f
            ) {
                val tileX = px - dockW
                val tileVisibleW = if (launcher.mapFullscreen) Int.MAX_VALUE else launcher.mapTileWidth
                if (tileX in 0 until tileVisibleW) {
                    inDock = false
                    targetDisplay = launcher.mapDisplayId
                    // 地图屏按布局动态 resize（分屏=tile 尺寸、全屏=应用区尺寸），
                    // 屏内容即坐标域：相对应用区左缘的偏移就是屏内坐标，直接平移
                    localX = tileX
                    localY = py
                    routedToMap = true
                }
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
                val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
                localX = if (inDock) px else ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        }
        if (targetDisplay < 0) {
            diag("目标屏无效 dock=${launcher.dockDisplayId} app=${launcher.appDisplayId}")
            return
        }
        try {
            val ok: Boolean =
                when {
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

        // 仪表盘模式：地图 tile 的触摸同时喂给「点按 → 全屏地图」检测器
        // （注入逻辑不受影响；点按 = 快速轻点，平移/双击/双指等地图手势不触发）
        if (!launcher.dockAtBottom) {
            val tileHit =
                !inDock &&
                    targetDisplay == launcher.mapDisplayId &&
                    launcher.dashboardMode &&
                    !launcher.mapFullscreen
            updateMapTileTap(launcher, contact, tileHit, px - dockW)
        }
    }

    fun reset() {
        pressed = false
        pressedDisplay = -1
        cancelMapTileTap()
        coordScaleX = 1f
        coordScaleY = 1f
    }

    // ---- 仪表盘地图 tile「快速点按 → 全屏地图」检测 ----
    // 点按判定：按下→抬起 ≤400ms 且位移 ≤30px；抬起后 300ms 内无第二次按下
    // （确认窗规避地图双击缩放误触）。拖动/拖出 tile/双指等地图手势不触发。
    private const val TAP_MAX_MS = 400L
    private const val TAP_MAX_MOVE_PX = 30
    private const val TAP_CONFIRM_MS = 300L
    private val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var tapArmSlot = -1
    private var tapDownAt = 0L
    private var tapX = 0
    private var tapY = 0
    private var tapConfirm: Runnable? = null

    private fun updateMapTileTap(
        launcher: com.lizhihua.carplaysender.launcher.VirtualLauncher,
        contact: Contact,
        inTile: Boolean,
        tileX: Int,
    ) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!launcher.dashboardMode || launcher.mapFullscreen) {
            cancelMapTileTap()
            return
        }
        if (contact.down) {
            if (inTile && tapConfirm != null) {
                cancelMapTileTap() // 确认窗内再次按下 = 双击缩放，取消全屏触发
                return
            }
            if (tapArmSlot < 0 && inTile) {
                tapArmSlot = contact.slot
                tapDownAt = now
                tapX = tileX
                tapY = contact.yPixels
            } else if (tapArmSlot >= 0 &&
                (
                    !inTile ||
                        contact.slot != tapArmSlot ||
                        kotlin.math.abs(tileX - tapX) + kotlin.math.abs(contact.yPixels - tapY) > TAP_MAX_MOVE_PX
                )
            ) {
                cancelMapTileTap() // 拖动 / 拖出 tile / 双指
            }
        } else if (tapArmSlot >= 0 && contact.slot == tapArmSlot) {
            val ok =
                inTile &&
                    now - tapDownAt <= TAP_MAX_MS &&
                    kotlin.math.abs(tileX - tapX) + kotlin.math.abs(contact.yPixels - tapY) <= TAP_MAX_MOVE_PX
            cancelMapTileTap()
            if (ok) {
                tapConfirm =
                    Runnable {
                        tapConfirm = null
                        if (launcher.dashboardMode && !launcher.mapFullscreen) {
                            logSink?.invoke("LAUNCHER: 地图卡片点按 → 全屏地图")
                            launcher.setMapFullscreen(true)
                        }
                    }
                tapHandler.postDelayed(tapConfirm!!, TAP_CONFIRM_MS)
            }
        }
    }

    private fun cancelMapTileTap() {
        tapArmSlot = -1
        tapConfirm?.let { tapHandler.removeCallbacks(it) }
        tapConfirm = null
    }
}
