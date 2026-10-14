package com.lizhihua.carplaysender.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.lizhihua.carplaysender.render.DualDisplayComposer
import com.lizhihua.carplaysender.shizuku.Shell

/**
 * 虚拟屏启动器（双屏 + GL 合成架构）：
 *
 *   dock 屏（窄条，DockActivity 常驻）──┐
 *                                      ├─ DualDisplayComposer ─→ 编码器 Surface
 *   应用屏（GridActivity / 第三方应用）──┘
 *
 * - 应用启动到应用屏（shell am start --display 应用屏）；同一组件再次启动带回既有任务（续运行）。
 * - 回桌面 = 把 GridActivity 带回前台，后台应用任务不杀。
 * - 触摸由 TouchRouter 按主画面 x 坐标分流：x < dockWidth → dock 屏，否则 → 应用屏（平移）。
 */
object VirtualLauncher {

    private const val TAG = "VirtualLauncher"

    /** dock 宽度（dp；应用屏可用宽度 = 总宽 - 本值换算的像素）。CarPlay 化后含时钟/电量。 */
    const val DOCK_WIDTH_DP = 48

    /**
     * 竖屏锁定应用的 letterbox 宽高比（宽/高，会话建立时经 Shizuku 写入系统）。
     * 默认（约 1.78）下窗口只有 ~303px≈188dp@240dpi；1.05 放宽到约 514px。
     */
    private const val LETTERBOX_ASPECT_RATIO = 1.05f

    /** letterbox 窗口的目标逻辑宽度（dp）：≈ 手机屏宽，触发电商类应用的紧凑手机布局。 */
    private const val LETTERBOX_TARGET_DP = 410f

    /**
     * 横屏应用屏超采样倍率（固定像素占位缩小的唯一杠杆，见 attach 注释）。
     * 2.0 时像素开销 4x（用户实测明显变卡）；1.5 时 2.25x，顶部固定区占比
     * 12%（原始 18%）——画质/流畅的折中点（2026-10-11 用户反馈调参）。
     */
    private const val APP_SUPERSAMPLE = 1.5f

    /** 超采样后的应用屏高度上限（px；控制 GPU/内存开销）。 */
    private const val SUPERSAMPLE_MAX_HEIGHT = 1620

    val runningApps = LinkedHashSet<ComponentName>()

    private val runningAppsListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** dock 注册回调：运行列表变化时刷新（dock 独占一屏，永远 resumed，onResume 不再触发）。 */
    fun addRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.add(listener)
    }

    fun removeRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.remove(listener)
    }

    private fun notifyRunningAppsChanged() {
        runningAppsListeners.forEach { runCatching { it() } }
    }

    /** 桌面图标缓存（DockActivity 显示运行中图标用）。 */
    val iconCache = HashMap<ComponentName, Drawable>()

    @Volatile
    var dockDisplayId: Int = -1
        private set

    @Volatile
    var appDisplayId: Int = -1
        private set

    /** dock 在主合成画面中占据的像素宽度（TouchRouter 分流阈值；横屏=左侧竖条宽）。 */
    @Volatile
    var dockWidthPx: Int = 0
        private set

    /** 竖屏车机（屏幕高>宽）：dock 改为底部横条。 */
    @Volatile
    var dockAtBottom: Boolean = false
        private set

    /** dock 横条高度（竖屏；TouchRouter 按 y 分流）。 */
    @Volatile
    var dockHeightPx: Int = 0
        private set

    /** 应用区高度（竖屏；y < appHeightPx 属于应用屏）。 */
    @Volatile
    var appHeightPx: Int = 0
        private set

    /**
     * 应用屏超采样倍率（超采样应用屏的渲染倍率；1=未创建超采样屏）。
     * 触摸坐标按当前**活动应用屏**的倍率换算（见 [activeAppScale]）。
     */
    @Volatile
    var hiScale: Float = 1f
        private set

    /** 超采样应用屏（横屏车机时存在；竖屏车机为 -1，全部走原生）。 */
    @Volatile
    var appHiDisplayId: Int = -1
        private set

    /** 主区桌面（GridActivity）所在屏：横屏=超采样屏（保持既有观感），竖屏=原生屏。 */
    @Volatile
    var gridDisplayId: Int = -1
        private set

    /** 视频流画布尺寸（桌面壁纸切片的 aspectFill 映射基准）。 */
    @Volatile
    var streamWidthPx: Int = 0
        private set

    @Volatile
    var streamHeightPx: Int = 0
        private set

    // ---- 地图屏（仪表盘分屏）：地图应用专属屏，分屏=tile 尺寸，全屏=应用区尺寸 ----

    /** 地图屏 displayId（横屏车机且受信屏模式创建；-1 = 无）。 */
    @Volatile
    var mapDisplayId: Int = -1
        private set

    /** 仪表盘↔桌面滑动进度（0=tile 原位，1=完全滑出）：滑动期间抑制 tile 事件注入。 */
    @Volatile
    var tileSlideFraction: Float = 0f
        private set



    /** 由 GridActivity 随 pager 滑动驱动 tile 视觉位移。 */
    fun setTileSlide(fraction: Float) {
        tileSlideFraction = fraction
        composer?.tileSlideFraction = fraction
    }

    /** 地图屏 tile 尺寸（分屏形态；应用区左半）。 */
    @Volatile
    var mapTileWidth: Int = 0
        private set

    @Volatile
    var mapTileHeight: Int = 0
        private set

    /** 应用区尺寸（全屏形态的地图屏目标尺寸）。 */
    @Volatile
    var appAreaWidth: Int = 0
        private set

    @Volatile
    var appAreaHeight: Int = 0
        private set

    @Volatile
    var mapFullscreen: Boolean = false
        private set

    /** 仪表盘（分屏）模式是否激活。 */
    @Volatile
    var dashboardMode: Boolean = false
        private set

    /** 仪表盘模式变化监听（GridActivity 切换布局）。 */
    private val dashboardListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.add(listener)
    }

    fun removeDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.remove(listener)
    }

    private fun notifyDashboard() {
        dashboardListeners.forEach { runCatching { it(dashboardMode) } }
    }

    /** 地图 tile 布局变化监听（隐藏/分屏/全屏）。 */
    private val mapLayoutListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.add(listener)
    }

    fun removeMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.remove(listener)
    }

    private fun notifyMapLayout() {
        mapLayoutListeners.forEach { runCatching { it(mapFullscreen) } }
    }

    /** 地图应用白名单（优先序：车机版高德 → 手机高德 → 百度 → 腾讯）。 */
    val MAP_PACKAGES_PRIORITY = listOf(
        "com.autonavi.amapauto",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.tencent.map",
    )

    fun isMapPackage(packageName: String): Boolean = packageName in MAP_PACKAGES_PRIORITY

    /**
     * 已安装的默认地图（用户显式设置优先——DefaultMapPrefs；否则按优先序取
     * 第一个已安装者；null = 无任何地图应用）。
     */
    fun defaultMapComponent(context: Context): ComponentName? =
        DefaultMapPrefs.resolve(context)

    /**
     * 桌面所在屏相对视频流的渲染倍率（原生屏=1，超采样屏=hiScale）。
     * 桌面内绘制「全流画布壁纸切片」时的尺寸/偏移换算用。
     */
    fun gridScale(): Float =
        if (gridDisplayId >= 0 && gridDisplayId == appHiDisplayId) hiScale else 1f

    private var dockDisplay: VirtualDisplay? = null
    private var appDisplay: VirtualDisplay? = null
    private var appHiDisplay: VirtualDisplay? = null
    private var composer: DualDisplayComposer? = null

    /** 当前活动应用屏 ID（TouchRouter 注入目标）。 */
    fun activeAppDisplayId(): Int =
        if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) appHiDisplayId else appDisplayId

    /** 当前活动应用屏的坐标倍率（超采样屏=hiScale，原生屏=1）。 */
    fun activeAppScale(): Float =
        if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) hiScale else 1f

    /**
     * 挂载：建 GL 合成器 → 就绪回调里建两块虚拟屏 → 拉起 Dock/Grid。
     * [encoderSurface] 为编码器输入 Surface；返回是否全部成功。
     */
    @SuppressLint("WrongConstant")
    fun attach(
        context: Context,
        encoderSurface: Surface,
        totalWidth: Int,
        totalHeight: Int,
        densityDpi: Int,
        log: (String) -> Unit,
    ): Boolean {
        detach()
        val density = densityDpi / 160f
        val thicknessPx = (DOCK_WIDTH_DP * density).toInt().coerceAtLeast(80)
        // 车机方向决定 dock 位置：横屏 → 左侧竖条；竖屏 → 底部横条（2026-10-11 用户要求）
        dockAtBottom = totalHeight > totalWidth
        streamWidthPx = totalWidth
        streamHeightPx = totalHeight
        val appWidth: Int
        if (dockAtBottom) {
            dockWidthPx = totalWidth
            dockHeightPx = thicknessPx
            appHeightPx = (totalHeight - thicknessPx).coerceAtLeast(200)
            appWidth = totalWidth
        } else {
            dockWidthPx = thicknessPx
            dockHeightPx = totalHeight
            appHeightPx = totalHeight
            appWidth = (totalWidth - thicknessPx).coerceAtLeast(200)
        }
        appAreaWidth = appWidth
        appAreaHeight = appHeightPx
        // 地图屏：始终按应用区全尺寸创建（应用窗口稳定，任务不迁移）。
        // 分屏形态由合成器采样画面中央带区实现（地图中心≈车辆位置），无需 resize。
        if (!dockAtBottom) {
            mapTileWidth = appWidth / 2
            mapTileHeight = appHeightPx
        }
        // 横屏应用屏的密度选择（2026-10-11 实测标定）：
        // 竖屏锁定应用（如头条）在横屏屏上被系统 letterbox 成窄条窗口，窗口逻辑宽度
        // = 屏高px/letterbox宽高比，再由屏幕密度换算 dp。密度偏高 → 窗口只有 ~343dp，
        // 应用呈现「大字大搜索框」的窄屏布局；密度 ~200 → 窗口 ~411dp ≈ 手机宽度，
        // 应用呈现紧凑手机布局（截图验证）。目标：letterbox 窗口 ≈ LETTERBOX_TARGET_DP。
        val landscapeApp = appWidth > appHeightPx && !dockAtBottom
        // 超采样：部分应用（头条实测）按「手机状态栏物理像素」预留顶部区域
        // （28dp@560dpi=98px 原始像素，与显示密度无关），在 540px 高的流里占 18%、
        // 手机上仅 3.5%——无法去除（应用内部绘制），只能靠「渲染更多像素再缩回」
        // 把固定像素的占比压小。×2：98px→49px（9%）；超采样高度上限 1620px。
        val appScaleFactor = if (landscapeApp) {
            minOf(APP_SUPERSAMPLE, SUPERSAMPLE_MAX_HEIGHT.toFloat() / appHeightPx)
        } else {
            1f
        }
        val appHiWidth = (appWidth * appScaleFactor).toInt()
        val appHiHeight = (appHeightPx * appScaleFactor).toInt()
        this.hiScale = appScaleFactor
        val appHiDensity = if (landscapeApp) {
            val letterboxWindowPx = appHiHeight / LETTERBOX_ASPECT_RATIO
            (letterboxWindowPx / LETTERBOX_TARGET_DP * 160f).toInt().coerceIn(180, 640)
        } else {
            densityDpi
        }
        // 原生应用屏密度：与超采样屏保持**相同逻辑尺寸**（应用布局不受所在屏影响，
        // 超采样只是渲染分辨率差异）。横屏：原生 1034×540 @~200dpi = 827×432dp，
        // 与超采样屏（1551×810 @301dpi）的 824×431dp 等值；竖屏车机：直接用流密度。
        val appNativeDensity = if (landscapeApp) {
            val hiLogicalHeightDp = appHiHeight * 160f / appHiDensity
            (appHeightPx * 160f / hiLogicalHeightDp).toInt().coerceIn(160, 640)
        } else {
            densityDpi
        }

        // 等待 Shizuku UserService 绑定（受信虚拟屏需要 shell 权限创建）
        var svc = com.lizhihua.carplaysender.shizuku.CarInputClient.service
        if (svc == null &&
            com.lizhihua.carplaysender.shizuku.ShizukuGate.state(context) ==
            com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY
        ) {
            val deadline = SystemClock.uptimeMillis() + 2_500L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                com.lizhihua.carplaysender.shizuku.CarInputClient.ensure(context)
                Thread.sleep(150)
                svc = com.lizhihua.carplaysender.shizuku.CarInputClient.service
            }
        }
        val useTrusted = svc != null
        log("LAUNCHER: 虚拟屏模式=${if (useTrusted) "受信屏(shell)" else "应用屏(回退)"}")

        val composer = DualDisplayComposer(encoderSurface, totalWidth, totalHeight, dockAtBottom)
        this.composer = composer
        val displaysCreated = arrayOf(false)
        composer.onSurfacesReady = { dockSurface, appSurface, appHiSurface, mapSurface ->
            // 优先：Shizuku shell 创建受信虚拟屏（VIRTUAL_DISPLAY_FLAG_TRUSTED）——
            // 允许第三方应用的新任务落在该屏；解决「应用内跳转的任务被重定向到手机主屏」。
            var viaService = false
            if (useTrusted) {
                runCatching {
                    // 残留清理：进程重启/上轮会话遗留的同名虚拟屏不释放会一直占位
                    // （新宽度/新 surface 不生效——2026-10-11 dock 改宽后视觉无变化的根因）
                    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    dm.displays
                        .filter {
                            it.name == "CarPlayDock" || it.name == "CarPlayApp" ||
                                it.name == "CarPlayAppHi" || it.name == "CarPlayMap"
                        }
                        .forEach { stale ->
                            runCatching { svc!!.releaseDisplay(stale.displayId) }
                                .onSuccess { log("LAUNCHER: 已释放残留虚拟屏 ${stale.name}#${stale.displayId}") }
                        }
                    Thread.sleep(150) // 等系统完成注销
                    // dock 屏不声明受信：只承载自有 DockActivity，杜绝第三方任务逃逸上来
                    val dockId = svc!!.createDisplay(
                        "CarPlayDock", dockWidthPx, dockHeightPx, densityDpi, dockSurface, false,
                    )
                    val appId = svc!!.createDisplay(
                        "CarPlayApp", appWidth, appHeightPx, appNativeDensity, appSurface, true,
                    )
                    // 超采样屏（竖屏车机无、应用侧回退时也无）
                    val appHiId = if (appHiSurface != null) {
                        svc!!.createDisplay(
                            "CarPlayAppHi", appHiWidth, appHiHeight, appHiDensity, appHiSurface, true,
                        )
                    } else {
                        -1
                    }
                    // 地图屏（全屏尺寸创建；分屏形态由合成器中央带区采样实现）
                    val mapId = if (mapSurface != null && !dockAtBottom) {
                        svc!!.createDisplay(
                            "CarPlayMap", appWidth, appHeightPx, 200, mapSurface, true,
                        )
                    } else {
                        -1
                    }
                    if (dockId >= 0 && appId >= 0) {
                        dockDisplayId = dockId
                        appDisplayId = appId
                        if (appHiId >= 0) {
                            appHiDisplayId = appHiId
                        } else if (appHiSurface != null) {
                            log("LAUNCHER: 超采样屏创建失败——本次会话全部走原生屏")
                        }
                        if (mapId >= 0) {
                            mapDisplayId = mapId
                        } else if (mapSurface != null) {
                            log("LAUNCHER: 地图屏创建失败——仪表盘无地图 tile")
                        }
                        viaService = true
                    } else {
                        // 半成功：释放已建的
                        if (dockId >= 0) runCatching { svc!!.releaseDisplay(dockId) }
                        if (appId >= 0) runCatching { svc!!.releaseDisplay(appId) }
                        if (appHiId >= 0) runCatching { svc!!.releaseDisplay(appHiId) }
                        if (mapId >= 0) runCatching { svc!!.releaseDisplay(mapId) }
                    }
                }.onFailure { log("LAUNCHER: 受信屏创建异常 ${it.message}") }
            }
            if (viaService) {
                displaysViaService = true
                log(
                    "LAUNCHER: 三屏创建(受信) dock=$dockDisplayId(${dockWidthPx}px) " +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor)",
                )
                // 会话建立：为「强制应用横屏」名单内的应用逐个写入兼容覆盖
                // （会话结束/断开时由 SessionController 统一复原，见 retireSession/onEnded）
                runCatching {
                    val applied = com.lizhihua.carplaysender.launcher.settings.AppOrientationCompat.applyAll(context)
                    if (applied > 0) log("ORIENTATION: 已为 $applied 个应用启用强制横屏（会话期间生效）")
                }
            } else {
                // 回退：应用侧 PUBLIC 屏（应用内跳转任务会逃逸到手机主屏，尽量可用的降级形态）
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dockDisplay = dm.createVirtualDisplay(
                    "CarPlayDock",
                    dockWidthPx,
                    dockHeightPx,
                    densityDpi,
                    dockSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null,
                    null,
                )
                appDisplay = dm.createVirtualDisplay(
                    "CarPlayApp",
                    appWidth,
                    appHeightPx,
                    appNativeDensity,
                    appSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null,
                    null,
                )
                if (appHiSurface != null) {
                    appHiDisplay = dm.createVirtualDisplay(
                        "CarPlayAppHi",
                        appHiWidth,
                        appHiHeight,
                        appHiDensity,
                        appHiSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                    appHiDisplayId = appHiDisplay?.display?.displayId ?: -1
                }
                dockDisplayId = dockDisplay?.display?.displayId ?: -1
                appDisplayId = appDisplay?.display?.displayId ?: -1
                log(
                    "LAUNCHER: 三屏创建(回退) dock=$dockDisplayId(${dockWidthPx}px) " +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor)",
                )
            }
            displaysCreated[0] = dockDisplayId >= 0 && appDisplayId >= 0
        }
        return try {
            composer.start(
                dockWidthPx,
                dockHeightPx,
                appWidth,
                appHeightPx,
                if (landscapeApp) appHiWidth else 0,
                if (landscapeApp) appHiHeight else 0,
                if (landscapeApp) appWidth else 0,
                if (landscapeApp) appHeightPx else 0,
            )
            if (!displaysCreated[0]) {
                log("LAUNCHER: 虚拟屏创建失败")
                detach()
                return false
            }
            // 应用屏方向策略：高德等横屏应用在竖屏虚拟屏上会被 letterbox
            Shell.exec("cmd window set-ignore-orientation-request -d $appDisplayId reset")
            if (appHiDisplayId >= 0) {
                Shell.exec("cmd window set-ignore-orientation-request -d $appHiDisplayId reset")
            }
            // 放宽竖屏应用的 letterbox 窗口（默认窄条 → 手机宽度）；断开时还原（detach）
            Shell.exec("cmd window set-letterbox-style --aspectRatio $LETTERBOX_ASPECT_RATIO")
            log(
                "LAUNCHER: letterbox 宽高比=$LETTERBOX_ASPECT_RATIO " +
                    "appDensity=${appNativeDensity}dpi appHiDensity=${appHiDensity}dpi",
            )
            val dockOk = startOn(dockDisplayId, "com.lizhihua.carplaysender/.launcher.DockActivity")
            gridDisplayId = if (appHiDisplayId >= 0) appHiDisplayId else appDisplayId
            val gridOk = startOn(gridDisplayId, "com.lizhihua.carplaysender/.launcher.GridActivity")
            appContextRef = context.applicationContext
            log("LAUNCHER: dock=$dockOk grid=$gridOk gridDisplay=$gridDisplayId")
            // 桌面在超采样屏时默认活动屏 = 超采样屏（启动即显示桌面）
            if (appHiDisplayId >= 0) composer.requestActiveApp(true)
            startShizukuHealer()
            // 暗幕组件就绪（常亮+暗幕功能；主界面开关控制启停）
            com.lizhihua.carplaysender.ui.ScreenDimmer.attach(context)
            dockOk && gridOk
        } catch (error: IllegalStateException) {
            log("LAUNCHER: GL 合成器启动失败 - ${error.message}")
            detach()
            false
        }
    }

    /** 受信屏标记：true 时虚拟屏由 shell UserService 持有，须经服务释放。 */
    @Volatile
    private var displaysViaService = false

    fun detach() {
        com.lizhihua.carplaysender.ui.ScreenDimmer.detach()
        if (displaysViaService) {
            val svc = com.lizhihua.carplaysender.shizuku.CarInputClient.service
            if (svc != null) {
                if (dockDisplayId >= 0) runCatching { svc.releaseDisplay(dockDisplayId) }
                if (appDisplayId >= 0) runCatching { svc.releaseDisplay(appDisplayId) }
                if (appHiDisplayId >= 0) runCatching { svc.releaseDisplay(appHiDisplayId) }
                // 地图屏同样由 shell 持有：不释放会在断开后残留（重装后由启动检测兜底发现）
                if (mapDisplayId >= 0) runCatching { svc.releaseDisplay(mapDisplayId) }
            }
            displaysViaService = false
        }
        appContextRef = null
        watchdogActive = false
        // 还原 letterbox 宽高比为系统默认（不把会话调参留在系统里）
        runCatching { com.lizhihua.carplaysender.shizuku.Shell.exec("cmd window reset-letterbox-style aspectRatio") }
        runCatching { composer?.stop() }
        composer = null
        runCatching { dockDisplay?.release() }
        dockDisplay = null
        runCatching { appDisplay?.release() }
        appDisplay = null
        runCatching { appHiDisplay?.release() }
        appHiDisplay = null
        dockDisplayId = -1
        appDisplayId = -1
        appHiDisplayId = -1
        gridDisplayId = -1
        mapDisplayId = -1
        mapTileWidth = 0
        mapTileHeight = 0
        appAreaWidth = 0
        appAreaHeight = 0
        mapFullscreen = false
        if (dashboardMode) {
            dashboardMode = false
            notifyDashboard()
        }
        dockWidthPx = 0
        dockHeightPx = 0
        appHeightPx = 0
        dockAtBottom = false
        hiScale = 1f
        streamWidthPx = 0
        streamHeightPx = 0
    }

    private fun startOn(displayId: Int, component: String): Boolean {
        if (displayId < 0) return false
        val result = Shell.exec(
            "am start -n $component --display $displayId --windowingMode 1",
            timeoutMs = 8000,
        )
        return result.ok && result.output.contains("Starting:", ignoreCase = true)
    }

    /** 回桌面：GridActivity 任务带回前台（后台应用保留）；合成器切回桌面所在屏。 */
    fun goGrid(): Boolean {
        val grid = if (gridDisplayId >= 0) gridDisplayId else appDisplayId
        val ok = startOn(grid, "com.lizhihua.carplaysender/.launcher.GridActivity")
        composer?.requestActiveApp(appHiDisplayId >= 0 && grid == appHiDisplayId)
        if (!ok) Log.w(TAG, "回桌面失败")
        return ok
    }

    // ---- 仪表盘（分屏）与 home 键模式循环 ----
    //
    // home 键语义（用户定义）：应用 → 仪表盘；桌面 → 仪表盘；仪表盘 → 桌面。
    // 仪表盘 = 应用区左半为地图 tile（地图屏 resize 至 tile 尺寸）、右半为
    // GridActivity 的音乐卡；地图任务常驻地图屏，全屏 ⇄ 分屏只 resize 不迁移，
    // 导航等应用状态不丢。

    /**
     * dock 的 ⌂ 键：模式循环（仪表盘 ⇄ 桌面；应用/全屏地图 → 仪表盘）。
     * 注意必须先复位 mapFullscreen——路由闸门依赖它，漏复位会把桌面触摸
     * 继续送进已隐藏的地图屏（实测：桌面无法点击而日志注入成功的根因）。
     */
    fun onHomePressed() {
        when {
            mapFullscreen -> {
                setMapFullscreen(false)
                showDashboard()
            }

            dashboardMode -> showDesktop()
            else -> showDashboard()
        }
    }

    /** 进入仪表盘：地图屏缩回 tile、默认地图占屏（缺则启动）、桌面切分屏布局。 */
    fun showDashboard() {
        syncDashboard(true)
        goGrid()
        composer?.requestActiveApp(appHiDisplayId >= 0 && gridDisplayId == appHiDisplayId)
        logSink?.invoke("LAUNCHER: 仪表盘模式（地图 tile + 音乐卡）")
    }

    /** 回普通桌面：隐藏地图层并复位全屏标志，桌面恢复全宽图标网格（地图任务保留）。 */
    /**
     * 仪表盘模式唯一对账口：置 dashboardMode → 通知（dock 图标/GridActivity pager）
     * → 进入时补地图任务并切 SPLIT，退出时隐藏 tile。
     * pager 滑动过程只做 tile 位移预览（showMapTile），状态一律在落定（IDLE）时经此对账。
     */
    fun syncDashboard(dashboard: Boolean) {
        val changed = dashboardMode != dashboard
        dashboardMode = dashboard
        if (changed) notifyDashboard()
        if (dashboard) {
            Thread({
                ensureMapTask()
                if (mapFullscreen) setMapFullscreen(false)
                // 期间用户可能已点开普通应用（launchApp 会置 HIDDEN 并退出仪表盘）：
                // 复查模式，别把地图层又盖回去
                if (dashboardMode) {
                    composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
                }
            }, "dashboard-enter").start()
        } else {
            composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
        }
    }

    /** 滑动预览：仅切 tile 可见（不动 dashboardMode/监听），落定后由 syncDashboard 对账。 */
    fun showMapTile() {
        composer?.setTileLayout(DualDisplayComposer.TileLayout.SPLIT)
    }

    fun showDesktop() {
        syncDashboard(false)
        goGrid()
        logSink?.invoke("LAUNCHER: 桌面模式（图标网格）")
    }

    /**
     * 地图屏全屏 ⇄ 分屏：地图屏尺寸恒定（应用区大小），仅切换合成器的采样布局。
     * 任务不迁移、不 resize，导航等状态天然连续。
     */
    fun setMapFullscreen(fullscreen: Boolean) {
        if (mapDisplayId < 0 || mapFullscreen == fullscreen) return
        mapFullscreen = fullscreen
        composer?.setTileLayout(
            if (fullscreen) DualDisplayComposer.TileLayout.FULLSCREEN
            else DualDisplayComposer.TileLayout.SPLIT,
        )
        notifyMapLayout()
        logSink?.invoke("LAUNCHER: 地图屏 ${if (fullscreen) "全屏" else "分屏"}")
    }

    /**
     * 地图应用启动到地图屏（全屏形态）：resize 地图屏 → 启动 → 合成器全屏布局。
     * 地图应用之间的切换（高德 ↔ 百度）也在同一块屏上进行，旧任务被清（互斥）。
     */
    private fun launchMapOnMapDisplay(component: ComponentName): Boolean {
        dashboardMode = false
        notifyDashboard()
        setMapFullscreen(true)
        val target = if (component.packageName == "com.autonavi.amapauto") {
            "com.autonavi.amapauto/.MainMapActivity"
        } else {
            component.flattenToShortString()
        }
        // 同屏已有其他地图任务时清掉（白名单互斥；默认地图任务保留复用）
        val ctx = appContextRef
        val defaultPkg = ctx?.let { defaultMapComponent(it)?.packageName }
        if (component.packageName != defaultPkg) {
            taskIdsOnDisplayPkg(component.packageName, mapDisplayId).forEach { taskId ->
                Shell.exec("am stack remove $taskId", timeoutMs = 4000)
            }
        }
        val result = Shell.exec(
            "am start -n $target --display $mapDisplayId -f 0x18000000",
            timeoutMs = 8000,
        )
        val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
        logSink?.invoke(
            "LAUNCH(${component.packageName}): → 地图屏全屏 ok=$ok out=${result.output.take(80)}",
        )
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
        }
        return ok
    }

    /** 指定屏上指定包的任务 ID 列表（清屏用）。 */
    private fun taskIdsOnDisplayPkg(pkg: String, displayId: Int): List<String> {
        val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4000).output
        var current = -1
        val ids = ArrayList<String>()
        for (line in dump.lineSequence()) {
            displayRegex.find(line)?.let { current = it.groupValues[1].toInt() }
            val task = taskRegex.find(line) ?: continue
            if (current == displayId && task.groupValues[2] == pkg) {
                ids += task.groupValues[1]
            }
        }
        return ids
    }

    /**
     * 确保默认地图在地图屏：am start 对既有任务=带回前台（状态保留，导航不断），
     * 无任务=新启动。后台线程调用。
     */
    private fun ensureMapTask() {
        if (mapDisplayId < 0) return
        val ctx = appContextRef ?: return
        val component = defaultMapComponent(ctx) ?: run {
            logSink?.invoke("LAUNCHER: 未找到任何地图应用（白名单内）——地图 tile 空")
            return
        }
        val target = if (component.packageName == "com.autonavi.amapauto") {
            "com.autonavi.amapauto/.MainMapActivity"
        } else {
            component.flattenToShortString()
        }
        // 不带 MULTIPLE_TASK：任务已在地图屏时等价于带回前台
        val result = Shell.exec("am start -n $target --display $mapDisplayId", timeoutMs = 8000)
        logSink?.invoke(
            "LAUNCHER: 默认地图 ${component.packageName} → 地图屏 ok=" +
                (result.ok && result.output.contains("Starting:", ignoreCase = true)),
        )
    }

    /**
     * 启动/切换应用。
     * 目标屏按每应用超采样偏好路由（SupersamplePrefs）：
     *  - 超采样开启（或自动判定为竖屏应用）→ 超采样屏（1551×810 缩回，固定像素顶带占比小）
     *  - 超采样关闭（横屏/地图类）→ 原生屏（1:1 渲染，更清晰、GPU 开销 1/2.25）
     *
     * 本 ROM（MagicOS）实测行为：`am start --display X` 对已有任务只做"带到前台"，
     * 不落到 X 屏；`am task move` 静默无效；`am task remove` 不存在，清任务用
     * `am stack remove <taskId>`（本版本 RootTask id == taskId）。可靠做法：
     *  1) 目标屏已有该包任务 → 普通启动带回前台（复用，保留任务栈）
     *  2) 目标屏没有 → 先清掉该包在**另一个应用屏**上的任务（偏好切换后避免双实例），
     *     再 `-f 0x18000000`（NEW_TASK|MULTIPLE_TASK）在目标屏新建实例
     *
     * 已知局限（2026-10-10 实测）：应用在手机侧也有实例时，进程基准密度停留在
     * 手机屏（560dpi），车机屏渲染文字偏大（≈2.2 倍）。唯一根治是强制重启该进程，
     * 但会杀掉用户手机侧正在用的实例（不可接受）——故维持现状不处理。
     */
    fun launchApp(component: ComponentName): Boolean {
        if (appDisplayId < 0) return false
        // 地图应用 → 地图屏全屏（任务常驻地图屏；从桌面 grid 点击同样去地图屏，
        // 与仪表盘共用同一任务，导航状态不丢）
        if (isMapPackage(component.packageName) && mapDisplayId >= 0) {
            return launchMapOnMapDisplay(component)
        }
        val ctx = appContextRef
        val wantHi = ctx != null && appHiDisplayId >= 0 &&
            SupersamplePrefs.shouldSupersample(ctx, component)
        val targetDisplay = if (wantHi) appHiDisplayId else appDisplayId
        val onTarget = packagesOnDisplay(component.packageName, targetDisplay)
        if (!onTarget) {
            // 偏好切换后清理另一块应用屏上的旧任务（不同屏双实例会各自维护状态，易混淆）
            val otherDisplay = if (targetDisplay == appHiDisplayId) appDisplayId else appHiDisplayId
            if (otherDisplay >= 0 && packagesOnDisplay(component.packageName, otherDisplay)) {
                taskIdsOnDisplay(component.packageName, otherDisplay).forEach { taskId ->
                    Shell.exec("am stack remove $taskId", timeoutMs = 4000)
                    Log.i(TAG, "清理旧屏任务 pkg=${component.packageName} task=$taskId display=$otherDisplay")
                }
            }
        }
        val target =
            if (component.packageName == "com.autonavi.amapauto") {
                "com.autonavi.amapauto/.MainMapActivity --windowingMode 1"
            } else {
                component.flattenToShortString()
            }
        val result = if (onTarget) {
            Shell.exec("am start -n $target --display $targetDisplay", timeoutMs = 8000)
        } else {
            // MULTIPLE_TASK：即使该应用在其他屏有任务，也强制在目标屏新建实例
            Shell.exec("am start -n $target --display $targetDisplay -f 0x18000000", timeoutMs = 8000)
        }
        var ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
        if (!ok && onTarget) {
            // 复用路径失败（任务可能已被系统移走）：退回 MULTIPLE_TASK 新建
            val retry = Shell.exec("am start -n $target --display $targetDisplay -f 0x18000000", timeoutMs = 8000)
            ok = retry.ok && retry.output.contains("Starting:", ignoreCase = true)
        }
        logSink?.invoke(
            "LAUNCH(${component.packageName}): hi=$wantHi disp=$targetDisplay onTarget=$onTarget ok=$ok " +
                "out=${result.output.take(80)}",
        )
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
            // 仪表盘/全屏地图中打开普通应用：退出仪表盘并隐藏地图层，
            // 否则 SPLIT 带区继续画在应用上面（实测地图 tile 盖住打开的应用）
            if (dashboardMode || mapFullscreen) {
                dashboardMode = false
                mapFullscreen = false
                notifyDashboard()
                composer?.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
                logSink?.invoke("LAUNCHER: 退出仪表盘（普通应用已打开）")
            }
            // 合成器切到目标屏（超采样屏等其出帧再切；原生屏即时切换）
            composer?.requestActiveApp(targetDisplay == appHiDisplayId)
        } else {
            Log.w(TAG, "启动失败：${result.output.take(120)}")
        }
        return ok
    }


    // ICU 汉字→拼音转写（Android 内置，无需第三方拼音库）。
    // "Han-Latin" 输出带声调，追加 Latin-ASCII 去音调（gāo → gao）。
    // Transliterator 非线程安全，统一加锁访问。
    private val hanLatinLock = Any()
    private val hanLatin by lazy {
        android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")
    }

    private fun toLatin(text: String): String = synchronized(hanLatinLock) {
        runCatching { hanLatin.transliterate(text) }.getOrDefault(text)
    }

    /** 排序键：全名转拼音小写、仅保留字母数字（数字 < 字母，即 # 组自然在前）。 */
    private fun pinyinKey(label: String): String =
        toLatin(label).lowercase().filter { it.isLetterOrDigit() }

    /** 拼音排序键（设置页等外部列表按字母分组排序用）。 */
    fun sortKeyOf(label: String): String = pinyinKey(label)

    @Volatile
    private var appsCache: List<AppEntry>? = null

    /** 应用清单缓存（桌面与车机设置共享；安装/卸载广播时失效）。 */
    fun cachedApps(context: Context): List<AppEntry> =
        appsCache ?: scanLaunchableApps(context).also { appsCache = it }

    /**
     * 仅读取共享缓存，**绝不扫描**：设置页等必须在主线程取列表的界面用这个，
     * 缓存未命中时自行异步扫描（cachedApps 的同步兜底会把整页应用扫描压上 UI 线程）。
     */
    fun peekCachedApps(): List<AppEntry>? = appsCache

    /** 把外部线程扫描的结果纳入共享缓存（桌面扫描后设置页即可秒开）。 */
    fun adoptAppsCache(apps: List<AppEntry>) {
        if (appsCache == null) appsCache = apps
    }

    fun invalidateAppsCache() {
        appsCache = null
    }

    fun launchableApps(context: Context): List<AppEntry> = scanLaunchableApps(context)

    private fun scanLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        // 拼音键每应用只算一次（此前在比较器里逐对重复转写，150 应用 ≈ 上千次 ICU 调用）
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .map { info ->
                val label = info.loadLabel(pm).toString()
                AppEntry(
                    component = ComponentName(info.packageName, info.name),
                    label = label,
                    icon = info.loadIcon(pm),
                ) to pinyinKey(label)
            }
            .take(200)
            .toList()
            .sortedBy { it.second }
            .map { it.first }
    }    /** 字母导航条分组键：数字→"#"，ASCII 字母→大写字母，汉字→拼音首字母。 */
    fun indexLetterOf(label: String): String {
        val first = label.firstOrNull() ?: return "#"
        return when {
            first.isDigit() -> "#"
            first.isLetter() && first.code < 128 -> first.uppercaseChar().toString()
            else -> {
                val latin = toLatin(first.toString()).lowercase()
                latin.firstOrNull { it in 'a'..'z' }?.uppercaseChar()?.toString() ?: "#"
            }
        }
    }

    // ---- Shizuku 自愈 ----

    @Volatile
    private var watchdogActive = false
    private var watchdogThread: Thread? = null

    val taskRegex = Regex("""Task\{[0-9a-f]+ #(\d+) type=standard A=\d+:(\S+)""")
    private val displayRegex = Regex("""Display: mDisplayId=(\d+)""")

    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** 应用上下文（Shizuku 自愈需要）。 */
    @Volatile
    private var appContextRef: android.content.Context? = null

    /**
     * Shizuku 触摸注入自愈：服务断开时尝试解冻并重绑（每 4s 一次；健康时
     * service 非空直接跳过、零开销）。解决「必须强行停止 App 重开」问题。
     *
     * 注：原「任务看护」（应用逃逸到主屏后自动拉回）已于 2026-10-10 实机验证后移除：
     * 受信虚拟屏下应用内跳转天然留在车机屏、手机侧打开自然跟随到手机、从车机 dock
     * 重开又回到车机侧——不再需要任何拉回干预。
     */
    private fun startShizukuHealer() {
        if (watchdogActive) return
        watchdogActive = true
        watchdogThread = Thread({
            while (watchdogActive && appDisplayId >= 0) {
                try {
                    val ctx = appContextRef
                    if (ctx != null && com.lizhihua.carplaysender.shizuku.CarInputClient.service == null) {
                        runCatching {
                            Shell.exec("am unfreeze moe.shizuku.privileged.api", timeoutMs = 2500)
                        }
                        com.lizhihua.carplaysender.shizuku.CarInputClient.ensure(ctx)
                    }
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(4000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "shizuku-healer").apply {
            isDaemon = true
            start()
        }
    }

    /** 重新 dump 一次，判断包是否有任务在指定 display。 */
    private fun packagesOnDisplay(pkg: String, displayId: Int): Boolean {
        val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4000).output
        var current = -1
        for (line in dump.lineSequence()) {
            displayRegex.find(line)?.let { current = it.groupValues[1].toInt() }
            val task = taskRegex.find(line) ?: continue
            if (current == displayId && task.groupValues[2] == pkg) return true
        }
        return false
    }

    /** 列出包在指定 display 上的任务 ID（偏好切换后清理旧屏任务用）。 */
    private fun taskIdsOnDisplay(pkg: String, displayId: Int): List<String> {
        val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4000).output
        var current = -1
        val ids = ArrayList<String>()
        for (line in dump.lineSequence()) {
            displayRegex.find(line)?.let { current = it.groupValues[1].toInt() }
            val task = taskRegex.find(line) ?: continue
            if (current == displayId && task.groupValues[2] == pkg) {
                ids += task.groupValues[1]
            }
        }
        return ids
    }

    data class AppEntry(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )
}
