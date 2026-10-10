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
        val appWidth = (totalWidth - dockWidthPx).coerceAtLeast(200)        // 横屏应用屏用「手机密度」：竖屏锁定应用被 letterbox 的窗口 ≈ 390dp 宽（正常手机布局），
        // 字号/间距不再是「平板放大版」；竖屏应用屏（本就手机比例）保持原密度
        val appDensity = if (appWidth > totalHeight) {
            val letterboxWidthPx = totalHeight * 9f / 16f
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

        val composer = DualDisplayComposer(encoderSurface, totalWidth, totalHeight)
        this.composer = composer
        val displaysCreated = arrayOf(false)
        composer.onSurfacesReady = { dockSurface, appSurface ->
            // 优先：Shizuku shell 创建受信虚拟屏（VIRTUAL_DISPLAY_FLAG_TRUSTED）——
            // 允许第三方应用的新任务落在该屏；解决「应用内跳转的任务被重定向到手机主屏」。
            var viaService = false
            if (useTrusted) {
                runCatching {
                    val dockId = svc!!.createDisplay("CarPlayDock", dockWidthPx, totalHeight, densityDpi, dockSurface)
                    val appId = svc!!.createDisplay("CarPlayApp", appWidth, totalHeight, appDensity, appSurface)
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
                    totalHeight,
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
                    totalHeight,
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
            appContextRef = context.applicationContext
            log("LAUNCHER: dock=$dockOk grid=$gridOk appDensity=${appDensity}dpi")
            startWatchdog(log)
            // 触摸探针始终挂载：任务看护用「手机侧最近真实触摸」区分
            // 「用户主动在手机侧打开应用」（跟随到手机）与「车机内跳转」（拉回车机）
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
        carPackages.clear()
        userMovedToPhone.clear()
        pullFailUntil.clear()
        lastPulledMainTask.clear()
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
            // 车机侧主动启动：清除「跟随手机」标记，标记该包来过车机屏
            userMovedToPhone.remove(component.packageName)
            carPackages.add(component.packageName)
            pullFailUntil.remove(component.packageName)
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

    // ---- 任务看护：应用内跳转产生的任务可能落到主屏（AOSP 多显示限制），自动拉回车机屏 ----

    @Volatile
    private var watchdogActive = false
    private var watchdogThread: Thread? = null

    val taskRegex = Regex("""Task\{[0-9a-f]+ #(\d+) type=standard A=\d+:(\S+)""")
    private val displayRegex = Regex("""Display: mDisplayId=(\d+)""")

    /** 本会话曾在车机屏显示过的包（跟随判定用：不因中途跑到主屏而丢失「来过车机」事实）。 */
    private val carPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 用户主动从手机侧打开、跟随到手机的包：不再自动拉回，直至回到车机屏。 */
    private val userMovedToPhone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 拉回失败后的冷却截止时间（防止「拉不动→每 2 秒重试→任务反复弹前台」死循环）。 */
    private val pullFailUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 触摸后多久内视为「正在进行该侧操作」。 */
    private const val TOUCH_GRACE_MS = 15_000L

    /** 拉回失败后的冷却时长。 */
    private const val PULL_FAIL_COOLDOWN_MS = 60_000L

    /** 诊断日志限流（每包 10s 一条）。 */
    private val diagAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 已为其拉回过的「主屏残留任务 id」：同一残留只拉一次，避免每轮重复抢前台。 */
    private val lastPulledMainTask = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** 应用上下文（看护循环里的 Shizuku 自愈需要）。 */
    @Volatile
    private var appContextRef: android.content.Context? = null

    /** 车机侧最近一次触摸时间（TouchRouter 更新）。 */
    @Volatile
    var lastCarTouchAt: Long = 0L
        private set

    /** 车机侧发生触摸（HID 事件）时调用。 */
    fun onCarTouch() {
        lastCarTouchAt = SystemClock.uptimeMillis()
    }

    private fun startWatchdog(log: (String) -> Unit) {
        if (watchdogActive) return
        watchdogActive = true
        watchdogThread = Thread({
            while (watchdogActive && appDisplayId >= 0) {
                try {
                    val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4000).output
                    val runningPackages = synchronized(runningApps) { runningApps.map { it.packageName }.toSet() }
                    val packagesOnMain = LinkedHashSet<String>()
                    val packagesOnApp = LinkedHashSet<String>()
                    val taskIdOnMain = HashMap<String, String>()
                    var displayId = -1
                    for (line in dump.lineSequence()) {
                        displayRegex.find(line)?.let { displayId = it.groupValues[1].toInt() }
                        val task = taskRegex.find(line) ?: continue
                        val id = task.groupValues[1]
                        val pkg = task.groupValues[2]
                        when (displayId) {
                            0 -> {
                                packagesOnMain.add(pkg)
                                taskIdOnMain.putIfAbsent(pkg, id)
                            }

                            appDisplayId -> packagesOnApp.add(pkg)
                        }
                    }
                    val now = SystemClock.uptimeMillis()
                    val phoneTouchAt = com.lizhihua.carplaysender.ui.ScreenDimmer.lastUserTouchAt
                    val carTouchAt = lastCarTouchAt
                    val phoneRecent = now - phoneTouchAt < TOUCH_GRACE_MS
                    val carRecent = now - carTouchAt < TOUCH_GRACE_MS
                    // 谁摸得更晚，谁就是用户当前所在的一侧；都比较旧时默认车机侧（自动跳转也拉回）
                    val phoneIsNewer = phoneRecent && (!carRecent || phoneTouchAt > carTouchAt)
                    for (pkg in runningPackages) {
                        val inMain = pkg in packagesOnMain
                        val inApp = pkg in packagesOnApp
                        if (inApp && !inMain) {
                            // 干净状态：只在车机屏
                            carPackages.add(pkg)
                            lastPulledMainTask.remove(pkg)
                            if (userMovedToPhone.remove(pkg)) {
                                log("LAUNCHER: $pkg 已回到车机侧")
                            }
                            continue
                        }
                        if (!inMain) continue
                        // —— 以下处理「主屏有任务」：两种场景 ——
                        // A) inApp=true（双屏并存）：应用内跳转产生的新任务落主屏（原任务仍在车机屏）
                        // B) inApp=false（整体移动）：用户在手机侧打开该应用（系统把任务移走）
                        // 关键：A 场景此前被「if (inApp) continue」跳过，永远拉不回（2026-10-09 定位）。
                        if (pkg in userMovedToPhone) continue
                        if (phoneIsNewer && pkg in carPackages && !inApp) {
                            // B 场景 + 手机操作更新：跟随到手机，不再拉回
                            userMovedToPhone.add(pkg)
                            log(
                                "LAUNCHER: $pkg 跟随到手机侧（手机触摸 -${(now - phoneTouchAt) / 1000}s，" +
                                    "车机触摸 -${(now - carTouchAt) / 1000}s）",
                            )
                            continue
                        }
                        if (phoneIsNewer && inApp) {
                            // A 场景 + 用户正拿着手机操作：本轮不拉，等车机侧触摸更新再拉
                            continue
                        }
                        val until = pullFailUntil[pkg] ?: 0L
                        if (now < until) continue
                        val currentMainTask = taskIdOnMain[pkg]
                        // 同一主屏残留任务只拉一次（新跳转会产生新 taskId，那时再拉）
                        if (currentMainTask != null && currentMainTask == lastPulledMainTask[pkg]) continue
                        // 本 ROM 无可靠任务移动（am task move 静默无效）：
                        // 拉回 = 在车机屏新建实例（MULTIPLE_TASK）。
                        // inApp=true（原任务仍在车机屏）：复用带回前台；
                        // inApp=false：直接新建。
                        val resolve = Shell.exec(
                            "cmd package resolve-activity --brief $pkg | tail -n 1",
                            timeoutMs = 4000,
                        ).output.trim()
                        if (resolve.isEmpty()) continue
                        val cmd = if (inApp) {
                            "am start -n $resolve --display $appDisplayId"
                        } else {
                            "am start -n $resolve --display $appDisplayId -f 0x18000000"
                        }
                        val r = Shell.exec(cmd, timeoutMs = 8000)
                        val ok = r.ok && r.output.contains("Starting:", ignoreCase = true)
                        Thread.sleep(1200)
                        if (ok && packagesOnDisplay(pkg, appDisplayId)) {
                            if (currentMainTask != null) lastPulledMainTask[pkg] = currentMainTask
                            log(
                                "LAUNCHER: 应用拉回车机 ($pkg inApp=$inApp)（手机触摸 -${(now - phoneTouchAt) / 1000}s，" +
                                    "车机触摸 -${(now - carTouchAt) / 1000}s）",
                            )
                        } else {
                            pullFailUntil[pkg] = SystemClock.uptimeMillis() + PULL_FAIL_COOLDOWN_MS
                            log(
                                "LAUNCHER: 拉回 $pkg 未生效（inApp=$inApp ok=$ok out=${r.output.take(60)}），" +
                                    "${PULL_FAIL_COOLDOWN_MS / 1000}s 后重试",
                            )
                        }
                        break // 每轮最多一个拉回操作，避免密集 shell 调用
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
                // Shizuku 触摸注入自愈：服务断开时尝试解冻 Shizuku 并重绑（每周期一次；
                // 健康时 service 非空直接跳过，零开销）。解决"必须强行停止 App 重开"问题。
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
                    Thread.sleep(4000) // 降频：dumpsys+移动命令较重，减少系统负载
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "vd-watchdog").apply {
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
