package com.lizhihua.carplaysender.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
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

    /** dock 宽度（dp；应用屏可用宽度 = 总宽 - 本值换算的像素）。 */
    const val DOCK_WIDTH_DP = 88

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

    /** dock 在主合成画面中占据的像素宽度（TouchRouter 分流阈值）。 */
    @Volatile
    var dockWidthPx: Int = 0
        private set

    private var dockDisplay: VirtualDisplay? = null
    private var appDisplay: VirtualDisplay? = null
    private var composer: DualDisplayComposer? = null

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
        dockWidthPx = (DOCK_WIDTH_DP * density).toInt().coerceAtLeast(120)
        val appWidth = (totalWidth - dockWidthPx).coerceAtLeast(200)
        // 横屏应用屏用「手机密度」：竖屏锁定应用被 letterbox 的窗口 ≈ 390dp 宽（正常手机布局），
        // 字号/间距不再是「平板放大版」；竖屏应用屏（本就手机比例）保持原密度
        val appDensity = if (appWidth > totalHeight) {
            val letterboxWidthPx = totalHeight * 9f / 16f
            (letterboxWidthPx / 390f * 160f).toInt().coerceIn(240, densityDpi)
        } else {
            densityDpi
        }

        val composer = DualDisplayComposer(encoderSurface, totalWidth, totalHeight)
        this.composer = composer
        val displaysCreated = arrayOf(false)
        composer.onSurfacesReady = { dockSurface, appSurface ->
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dockDisplay = dm.createVirtualDisplay(
                "CarPlayDock",
                dockWidthPx,
                totalHeight,
                densityDpi,
                dockSurface,
                // 私有屏：SystemUI 不在其上渲染锁屏（息屏后车机侧不再显示锁屏界面）；
                // shell（Shizuku）不受私有屏启动限制
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                null,
                null,
            )
            appDisplay = dm.createVirtualDisplay(
                "CarPlayApp",
                appWidth,
                totalHeight,
                appDensity,
                appSurface,
                // 私有屏：SystemUI 不在其上渲染锁屏（息屏后车机侧不再显示锁屏界面）；
                // shell（Shizuku）不受私有屏启动限制
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                null,
                null,
            )
            dockDisplayId = dockDisplay?.display?.displayId ?: -1
            appDisplayId = appDisplay?.display?.displayId ?: -1
            displaysCreated[0] = dockDisplayId >= 0 && appDisplayId >= 0
            log("LAUNCHER: 双屏创建 dock=$dockDisplayId(${dockWidthPx}px) app=$appDisplayId(${appWidth}px)")
        }
        return try {
            composer.start(dockWidthPx, totalHeight, appWidth, totalHeight)
            if (!displaysCreated[0]) {
                log("LAUNCHER: 虚拟屏创建失败")
                detach()
                return false
            }
            // 应用屏方向策略：高德等横屏应用在竖屏虚拟屏上会被 letterbox
            Shell.exec("cmd window set-ignore-orientation-request -d $appDisplayId reset")
            val dockOk = startOn(dockDisplayId, "com.lizhihua.carplaysender/.launcher.DockActivity")
            val gridOk = startOn(appDisplayId, "com.lizhihua.carplaysender/.launcher.GridActivity")
            log("LAUNCHER: dock=$dockOk grid=$gridOk appDensity=${appDensity}dpi")
            startWatchdog(log)
            if (com.lizhihua.carplaysender.ui.ScreenDimmer.enabled) {
                com.lizhihua.carplaysender.ui.ScreenDimmer.attach(context)
            }
            dockOk && gridOk
        } catch (error: IllegalStateException) {
            log("LAUNCHER: GL 合成器启动失败 - ${error.message}")
            detach()
            false
        }
    }

    fun detach() {
        com.lizhihua.carplaysender.ui.ScreenDimmer.detach()
        watchdogActive = false
        runCatching { composer?.stop() }
        composer = null
        runCatching { dockDisplay?.release() }
        dockDisplay = null
        runCatching { appDisplay?.release() }
        appDisplay = null
        dockDisplayId = -1
        appDisplayId = -1
        dockWidthPx = 0
    }

    private fun startOn(displayId: Int, component: String): Boolean {
        if (displayId < 0) return false
        val result = Shell.exec(
            "am start -n $component --display $displayId --windowingMode 1",
            timeoutMs = 8000,
        )
        return result.ok && result.output.contains("Starting:", ignoreCase = true)
    }

    /** 回桌面：GridActivity 任务带回前台（后台应用保留）。 */
    fun goGrid(): Boolean {
        val ok = startOn(appDisplayId, "com.lizhihua.carplaysender/.launcher.GridActivity")
        if (!ok) Log.w(TAG, "回桌面失败")
        return ok
    }

    /** 启动/切换应用（同一组件带回既有任务）。 */
    fun launchApp(component: ComponentName): Boolean {
        if (appDisplayId < 0) return false
        val target =
            if (component.packageName == "com.autonavi.amapauto") {
                "com.autonavi.amapauto/.MainMapActivity --windowingMode 1"
            } else {
                component.flattenToShortString()
            }
        val result = Shell.exec(
            "am start -n $target --display $appDisplayId",
            timeoutMs = 8000,
        )
        val ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
        } else {
            Log.w(TAG, "启动失败：${result.output.take(120)}")
        }
        return ok
    }

    /** 可启动应用清单（排除本应用）。 */
    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    component = ComponentName(info.packageName, info.name),
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                )
            }
            .sortedBy { it.label }
            .take(200)
            .toList()
    }

    // ---- 任务看护：应用内跳转产生的任务可能落到主屏（AOSP 多显示限制），自动拉回车机屏 ----

    @Volatile
    private var watchdogActive = false
    private var watchdogThread: Thread? = null

    private val taskRegex = Regex("""Task\{[0-9a-f]+ #(\d+) type=standard A=\d+:(\S+)""")
    private val displayRegex = Regex("""Display: mDisplayId=(\d+)""")

    private fun startWatchdog(log: (String) -> Unit) {
        if (watchdogActive) return
        watchdogActive = true
        watchdogThread = Thread({
            while (watchdogActive && appDisplayId >= 0) {
                try {
                    val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4000).output
                    val runningPackages = synchronized(runningApps) { runningApps.map { it.packageName }.toSet() }
                    var displayId = -1
                    for (line in dump.lineSequence()) {
                        displayRegex.find(line)?.let { displayId = it.groupValues[1].toInt() }
                        if (displayId != 0) continue
                        val task = taskRegex.find(line) ?: continue
                        val (taskId, pkg) = task.destructured
                        if (pkg !in runningPackages) continue
                        // 本 ROM 无 am move-task：用「带 --display 重启入口」拉回（复用既有任务与栈）
                        val moved = Shell.exec(
                            "am start --display $appDisplayId \$(cmd package resolve-activity --brief $pkg | tail -n 1)",
                            timeoutMs = 6000,
                        )
                        if (moved.ok) log("LAUNCHER: 任务拉回车机 #${taskId}(${pkg})")
                        break // 每轮最多拉一个，下一轮继续
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "vd-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    data class AppEntry(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )
}
