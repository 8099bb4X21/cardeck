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

    /** dock 宽度（dp；应用屏可用宽度 = 总宽 - 本值换算的像素）。CarPlay 化后含时钟/电量，54dp。 */
    const val DOCK_WIDTH_DP = 48

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
        val thicknessPx = (DOCK_WIDTH_DP * density).toInt().coerceAtLeast(80)
        // 车机方向决定 dock 位置：横屏 → 左侧竖条；竖屏 → 底部横条（2026-10-11 用户要求）
        dockAtBottom = totalHeight > totalWidth
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
        // 横屏应用屏用「手机密度」：竖屏锁定应用被 letterbox 的窗口 ≈ 390dp 宽（正常手机布局），
        // 字号/间距不再是「平板放大版」；竖屏应用屏（本就手机比例）保持原密度
        val appDensity = if (appWidth > appHeightPx && !dockAtBottom) {
            val letterboxWidthPx = appHeightPx * 9f / 16f
            (letterboxWidthPx / 390f * 160f).toInt().coerceIn(240, densityDpi)
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
        composer.onSurfacesReady = { dockSurface, appSurface ->
            // 优先：Shizuku shell 创建受信虚拟屏（VIRTUAL_DISPLAY_FLAG_TRUSTED）——
            // 允许第三方应用的新任务落在该屏；解决「应用内跳转的任务被重定向到手机主屏」。
            var viaService = false
            if (useTrusted) {
                runCatching {
                    // 残留清理：进程重启/上轮会话遗留的同名虚拟屏不释放会一直占位
                    // （新宽度/新 surface 不生效——2026-10-11 dock 改宽后视觉无变化的根因）
                    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    dm.displays
                        .filter { it.name == "CarPlayDock" || it.name == "CarPlayApp" }
                        .forEach { stale ->
                            runCatching { svc!!.releaseDisplay(stale.displayId) }
                                .onSuccess { log("LAUNCHER: 已释放残留虚拟屏 ${stale.name}#${stale.displayId}") }
                        }
                    Thread.sleep(150) // 等系统完成注销
                    val dockId = svc!!.createDisplay("CarPlayDock", dockWidthPx, dockHeightPx, densityDpi, dockSurface)
                    val appId = svc!!.createDisplay("CarPlayApp", appWidth, appHeightPx, appDensity, appSurface)
                    if (dockId >= 0 && appId >= 0) {
                        dockDisplayId = dockId
                        appDisplayId = appId
                        viaService = true
                    } else {
                        // 半成功：释放已建的
                        if (dockId >= 0) runCatching { svc!!.releaseDisplay(dockId) }
                        if (appId >= 0) runCatching { svc!!.releaseDisplay(appId) }
                    }
                }.onFailure { log("LAUNCHER: 受信屏创建异常 ${it.message}") }
            }
            if (viaService) {
                displaysViaService = true
                log("LAUNCHER: 双屏创建(受信) dock=$dockDisplayId(${dockWidthPx}px) app=$appDisplayId(${appWidth}px)")
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
                    appDensity,
                    appSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null,
                    null,
                )
                dockDisplayId = dockDisplay?.display?.displayId ?: -1
                appDisplayId = appDisplay?.display?.displayId ?: -1
                log("LAUNCHER: 双屏创建(回退) dock=$dockDisplayId(${dockWidthPx}px) app=$appDisplayId(${appWidth}px)")
            }
            displaysCreated[0] = dockDisplayId >= 0 && appDisplayId >= 0
        }
        return try {
            composer.start(dockWidthPx, dockHeightPx, appWidth, appHeightPx)
            if (!displaysCreated[0]) {
                log("LAUNCHER: 虚拟屏创建失败")
                detach()
                return false
            }
            // 应用屏方向策略：高德等横屏应用在竖屏虚拟屏上会被 letterbox
            Shell.exec("cmd window set-ignore-orientation-request -d $appDisplayId reset")
            val dockOk = startOn(dockDisplayId, "com.lizhihua.carplaysender/.launcher.DockActivity")
            val gridOk = startOn(appDisplayId, "com.lizhihua.carplaysender/.launcher.GridActivity")
            appContextRef = context.applicationContext
            log("LAUNCHER: dock=$dockOk grid=$gridOk appDensity=${appDensity}dpi")
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
            }
            displaysViaService = false
        }
        appContextRef = null
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
        dockHeightPx = 0
        appHeightPx = 0
        dockAtBottom = false
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

    /**
     * 启动/切换应用。
     * 本 ROM（MagicOS）实测行为：`am start --display X` 对已有任务只做"带到前台"，
     * 不落到 X 屏；`am task move` 静默无效。可靠做法：
     *  1) 车机屏已有该包任务 → 普通启动带回前台（复用，保留任务栈）
     *  2) 车机屏没有该包任务 → `-f 0x18000000`（NEW_TASK|MULTIPLE_TASK）在车机屏新建实例
     *
     * 已知局限（2026-10-10 实测）：应用在手机侧也有实例时，进程基准密度停留在
     * 手机屏（560dpi），车机屏渲染文字偏大（≈2.2 倍）。唯一根治是强制重启该进程，
     * 但会杀掉用户手机侧正在用的实例（不可接受）——故维持现状不处理。
     */
    fun launchApp(component: ComponentName): Boolean {
        if (appDisplayId < 0) return false
        val target =
            if (component.packageName == "com.autonavi.amapauto") {
                "com.autonavi.amapauto/.MainMapActivity --windowingMode 1"
            } else {
                component.flattenToShortString()
            }
        val onApp = packagesOnDisplay(component.packageName, appDisplayId)
        val result = if (onApp) {
            Shell.exec("am start -n $target --display $appDisplayId", timeoutMs = 8000)
        } else {
            // MULTIPLE_TASK：即使该应用在其他屏有任务，也强制在车机屏新建实例
            Shell.exec("am start -n $target --display $appDisplayId -f 0x18000000", timeoutMs = 8000)
        }
        var ok = result.ok && result.output.contains("Starting:", ignoreCase = true)
        if (!ok && onApp) {
            // 复用路径失败（任务可能已被系统移走）：退回 MULTIPLE_TASK 新建
            val retry = Shell.exec("am start -n $target --display $appDisplayId -f 0x18000000", timeoutMs = 8000)
            ok = retry.ok && retry.output.contains("Starting:", ignoreCase = true)
        }
        logSink?.invoke(
            "LAUNCH(${component.packageName}): onApp=$onApp ok=$ok out=${result.output.take(80)}",
        )
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
            .take(200)
            .toList()
            .sortedWith(appComparator())
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

    /** 应用排序：按拼音键字典序（数字组在前，随后 A-Z，汉字按拼音并入对应字母）。 */
    private fun appComparator(): Comparator<AppEntry> =
        Comparator { a, b -> pinyinKey(a.label).compareTo(pinyinKey(b.label)) }

    /** 字母导航条分组键：数字→"#"，ASCII 字母→大写字母，汉字→拼音首字母。 */
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

    data class AppEntry(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )
}
