package io.github.shaonialife.cardeck.shizuku

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method

/**
 * Shizuku UserService 实现：运行在常驻 shell 进程（uid 2000），
 * 通过反射 InputManager.injectInputEvent 直接注入触摸事件到指定 display。
 *
 * injectInputEvent 的位置随版本变化：
 * - Android 13 及以下：InputManager 实例方法；
 * - Android 14+：迁移到 InputManagerGlobal.getInstance() 单例。
 * 部分版本签名带第三参 targetDisplayId，按形参类型自适应；
 * 老签名（两参）依赖 InputEvent.setDisplayId 指定目标屏。
 *
 * 反射失败（ROM hidden API 差异）由 ping()/diag() 暴露，调用方回退 `input -d`。
 * （移植自参考项目 android-auto 的 CarInputServiceImpl）
 */
class CarInputServiceImpl(
    private val context: Context,
) : ICarInput.Stub() {
    /**
     * 物理屏是否被我们置为 OFF。服务自尽（APK 升级导致的过期自清）前必须恢复，
     * 否则手机会停在一块「逻辑醒着、物理黑着」的屏上（按电源键两次可恢复）。
     */
    @Volatile
    private var physicalScreenOff = false

    /**
     * 僵尸自清（治本，2026-10-16）：Shizuku 按「组件+进程名+版本(APK lastUpdateTime)」复用
     * 常驻 UserService 进程。重装 APK 后旧版本进程既不受 force-stop 影响、Shizuku 也不回收；
     * 而它创建的虚拟屏**只有它自己能释放**（releaseDisplay 只查本进程的 map）——表现为
     * 「重启后检测到残留屏，点清理却释放 0/N」。本进程周期性比对「启动时读到的
     * lastUpdateTime」与当前安装值：不一致即自身 dex 已过期，自尽交系统回收进程，
     * 它持有的全部虚拟屏随之释放。
     */
    private val startupLastUpdateTime: Long =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(-1L)

    init {
        fileLog("service start: pkg=${context.packageName} lastUpdate=$startupLastUpdateTime pid=${android.os.Process.myPid()}")
        Thread({
            while (true) {
                try {
                    Thread.sleep(STALE_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now =
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                    }.getOrDefault(-1L)
                if (startupLastUpdateTime > 0 && now > 0 && now != startupLastUpdateTime) {
                    // 物理屏若被我们置为 OFF，自尽前必须恢复：否则新版本进程还没起来，
                    // 手机就停在一块「逻辑醒着、物理黑着」的屏上（按电源键两次才恢复）。
                    if (physicalScreenOff) {
                        fileLog("stale self-reap: 恢复物理屏点亮（避免升级后停在黑屏）")
                        runCatching { setPhysicalScreenPower(true) }
                    }
                    fileLog(
                        "stale self-reap: lastUpdateTime $startupLastUpdateTime -> $now，" +
                            "exiting pid=${android.os.Process.myPid()}（其持有的虚拟屏随进程回收）",
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@Thread
                }
            }
        }, "carinput-stale-check").apply {
            isDaemon = true
            start()
        }
    }

    private class Injector(
        val receiver: Any,
        val method: Method,
    )

    private val injector: Injector? by lazy {
        val im =
            try {
                context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            } catch (t: Throwable) {
                Log.d(TAG, "probe getSystemService(INPUT_SERVICE) 异常", t)
                null
            }
        if (im != null) {
            val m = findInjectMethod(im.javaClass.methods)
            if (m != null) return@lazy Injector(im, m)
        }
        try {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val global = cls.getMethod("getInstance").invoke(null)
            val m = findInjectMethod(cls.methods)
            if (m != null) return@lazy Injector(global, m)
        } catch (t: Throwable) {
            Log.d(TAG, "probe InputManagerGlobal 异常", t)
        }
        null
    }

    private fun findInjectMethod(methods: Array<Method>): Method? =
        methods.firstOrNull {
            it.name == "injectInputEvent" &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[0] == InputEvent::class.java &&
                it.parameterTypes[1] == Integer.TYPE
        }

    private val setDisplayIdMethod: Method? by lazy {
        (InputEvent::class.java.methods + MotionEvent::class.java.methods)
            .firstOrNull { it.name == "setDisplayId" && it.parameterTypes.size == 1 }
    }

    private var injectFailLogged = false

    private fun inject(
        event: InputEvent,
        displayId: Int,
    ): Boolean {
        val inj = injector
        if (inj == null) {
            recycle(event)
            return false
        }
        return try {
            setDisplayIdMethod?.invoke(event, displayId)
            val m = inj.method
            val result: Boolean =
                when {
                    m.parameterTypes.size == 2 -> m.invoke(inj.receiver, event, 2) as Boolean
                    m.parameterTypes[2] == java.lang.Long.TYPE -> m.invoke(inj.receiver, event, 2, 0L) as Boolean
                    else -> m.invoke(inj.receiver, event, 2, displayId) as Boolean
                }
            if (!result && !injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "injectInputEvent 返回 false method=$m")
            }
            result
        } catch (t: Throwable) {
            if (!injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "inject 异常 method=${inj.method}", t)
            }
            false
        } finally {
            recycle(event)
        }
    }

    /** KeyEvent 没有可复用的池化语义（recycle 已废弃），只有 MotionEvent 需要回收。 */
    private fun recycle(event: InputEvent) {
        (event as? MotionEvent)?.recycle()
    }

    /**
     * 按键注入（iDrive 旋钮 → 方向键/确认键）：与触摸共用 injectInputEvent，
     * 差别只是 event 换成 KeyEvent 且同样 setDisplayId 到目标虚拟屏。
     */
    override fun keyEvent(
        displayId: Int,
        keyCode: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        if (!inject(down, displayId)) return false
        val up = KeyEvent(now, now + 24, KeyEvent.ACTION_UP, keyCode, 0)
        return inject(up, displayId)
    }

    @Suppress("DEPRECATION")
    private fun build(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent? =
        try {
            val e =
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    x,
                    y,
                    1f,
                    1f,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                )
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            e
        } catch (t: Throwable) {
            Log.d(TAG, "build MotionEvent 异常", t)
            null
        }

    override fun tap(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val up = build(now, now + 50, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    override fun swipe(
        displayId: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val duration = durationMs.coerceIn(50, 1500)
        val down = build(now, now, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val steps = 8
        for (i in 1 until steps) {
            val t = duration * i / steps
            val ix = startX + (endX - startX) * i / steps
            val iy = startY + (endY - startY) * i / steps
            val move = build(now, now + t, MotionEvent.ACTION_MOVE, ix.toFloat(), iy.toFloat()) ?: return false
            if (!inject(move, displayId)) return false
        }
        val up = build(now, now + duration, MotionEvent.ACTION_UP, endX.toFloat(), endY.toFloat()) ?: return false
        return inject(up, displayId)
    }

    private var dragDownTime = 0L
    private var dragDisplay = -1

    override fun pointerDown(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        dragDownTime = now
        dragDisplay = displayId
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        return inject(down, displayId)
    }

    override fun pointerMove(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (dragDisplay != displayId) return false
        val now = SystemClock.uptimeMillis()
        val move = build(dragDownTime, now, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat()) ?: return false
        return inject(move, displayId)
    }

    override fun pointerUp(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (dragDisplay != displayId) return false
        dragDisplay = -1
        val now = SystemClock.uptimeMillis()
        val up = build(dragDownTime, now, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    // ---- 受信虚拟屏 ----

    // VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 30（@hide/@SystemApi，编译期不可引用，写原始值）
    private val virtualDisplays = HashMap<Int, android.hardware.display.VirtualDisplay>()

    // ---- IME 展示策略 ----

    /**
     * 受信虚拟屏的 IME 策略默认是 FALLBACK——键盘被系统路由到手机默认屏，车机侧永远
     * 看不到（2026-10-17 定谳，车机「设置」搜索框点击无键盘根因）。创建受信屏后显式
     * 设 LOCAL 让键盘画进本屏、随合成流推给车机。
     *
     * 枚举值反射自 SystemApi 常量而非硬编码 0：厂商 ROM 有魔改枚举值的风险（2026-10-17
     * 排查时按此防御；本机实测与 AOSP 一致 = 0）。读不到时回落 0 并记日志。
     */
    private val imePolicyLocal: Int by lazy {
        val value =
            try {
                Class
                    .forName("android.view.WindowManager")
                    .getField("DISPLAY_IME_POLICY_LOCAL")
                    .getInt(null)
            } catch (t: Throwable) {
                fileLog("DISPLAY_IME_POLICY_LOCAL 反射失败，回退 AOSP 值 0：$t")
                0
            }
        fileLog("DISPLAY_IME_POLICY_LOCAL=$value")
        value
    }

    /**
     * IWindowManager 代理（按方法名反射，不依赖 AIDL 事务号）。
     * 该 ROM 上走 ServiceManager+Stub.asInterface；WindowManagerGlobal.getWindowManagerService
     * 路径会致进程被杀（2026-10-17 实测），勿用。
     */
    private val windowManagerProxy: Any? by lazy {
        try {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "window")
            Class
                .forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (t: Throwable) {
            fileLog("IWindowManager 反射失败（IME 策略将不生效）：$t")
            null
        }
    }

    /**
     * 把受信虚拟屏的 IME 策略设为 LOCAL（键盘画进本屏）。
     * 失败不影响主链路：最坏情况与改动前一致——键盘留在手机主屏、车机侧不可见。
     */
    private fun applyImePolicyLocal(displayId: Int) {
        val proxy = windowManagerProxy ?: return
        try {
            proxy.javaClass
                .getMethod("setDisplayImePolicy", Integer.TYPE, Integer.TYPE)
                .invoke(proxy, displayId, imePolicyLocal)
            val readBack =
                runCatching {
                    proxy.javaClass
                        .getMethod("getDisplayImePolicy", Integer.TYPE)
                        .invoke(proxy, displayId)
                }.getOrNull()
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) ok, 读回=$readBack")
        } catch (t: Throwable) {
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) 失败：$t")
        }
    }

    /** 文件日志：shell 进程 logcat 被 HKS 加密，写 /data/local/tmp 供 adb 读取。 */
    private fun fileLog(message: String) {
        try {
            java.io.FileWriter("/data/local/tmp/carinput_svc.log", true).use { w ->
                w
                    .append(
                        android.text.format.DateFormat
                            .format("HH:mm:ss.SSS", System.currentTimeMillis())
                            .toString(),
                    ).append(' ')
                    .append(message)
                    .append('\n')
            }
        } catch (_: Throwable) {
        }
    }

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        trusted: Boolean,
    ): Int {
        return try {
            fileLog("createDisplay $name ${width}x$height@$dpi start")
            // 关键：必须用「shell 包的 Context」——系统检查 packageName 与调用 uid(2000) 匹配，
            // 用本 app 的 Context 会报 SecurityException('packageName must match the owner uid')
            val shellContext =
                try {
                    context.createPackageContext("com.android.shell", 0)
                } catch (t: Throwable) {
                    fileLog("createPackageContext(com.android.shell) 失败：$t，回退本 app context")
                    context
                }
            fileLog("ctx package=${shellContext.packageName}")
            val dm = shellContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6（@hide）：
            // 不声明时应用看到 "-touch" 配置（无触摸设备）→ 部分应用（如头条）
            // 切 TV/桌面式大头部布局（2026-10-11 实测：搜索区占满半屏的根因）。
            //
            // VIRTUAL_DISPLAY_FLAG_PUBLIC（公开 API = 1）必须随受信屏一起声明（2026-10-17 定谳）：
            // 不声明时 DisplayDeviceInfo 带 FLAG_PRIVATE（owner=shell 2000），而 WMS.addWindow
            // 的访问闸 displayContent.hasAccess(session.mUid) → Display.hasAccess 对私有屏只放行
            // owner/system/root/UID-present——第三方输入法（uid 10xxx）的键盘窗口会被
            // ADD_INVALID_DISPLAY(-9) 拒绝，永远加不进该屏（车机侧「设置」点搜索框无键盘的
            // 最终根因：策略已 LOCAL、令牌已迁屏，IME 服务也 showWindow 了，但窗口加不进）。
            // 公开化只放宽窗口访问；ROM 的「公开屏默认自动镜像」仅在未声明
            // OWN_CONTENT_ONLY 时生效，我们已声明，不受影响。dock 屏不受信、无输入场景，
            // 维持私有（也避免无关窗口挂上去）。
            val baseFlags =
                (if (trusted) FLAG_TRUSTED or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC else 0) or
                    FLAG_SUPPORTS_TOUCH or
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            // 受信屏额外要「独立显示组 + 永远已解锁」：
            //  · OWN_DISPLAY_GROUP：手机主屏休眠时虚拟屏不跟着休眠（物理屏断电后仍出帧）；
            //  · ALWAYS_UNLOCKED：设备锁屏时该屏不画 Keyguard（否则车机看到锁屏/黑屏）。
            // OWN_DISPLAY_GROUP 复用 ADD_TRUSTED_DISPLAY（受信屏本来就要）；ALWAYS_UNLOCKED
            // 另需 ADD_ALWAYS_UNLOCKED_DISPLAY——部分 ROM 未授予 shell，带上会抛
            // SecurityException 把整块屏打挂，因此逐级降级重试。
            var vd: android.hardware.display.VirtualDisplay? = null
            if (trusted) {
                vd =
                    createVirtualDisplayAttempt(
                        dm,
                        name,
                        width,
                        height,
                        dpi,
                        surface,
                        baseFlags or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
                        "受信+独立组+ALWAYS_UNLOCKED",
                    )
                if (vd == null) {
                    fileLog("$name 带 ALWAYS_UNLOCKED 失败——降级为独立组（ROM 可能未给 shell 该权限）")
                    vd =
                        createVirtualDisplayAttempt(
                            dm,
                            name,
                            width,
                            height,
                            dpi,
                            surface,
                            baseFlags or FLAG_OWN_DISPLAY_GROUP,
                            "受信+独立组",
                        )
                }
                if (vd == null) {
                    fileLog("$name 带 OWN_DISPLAY_GROUP 失败——回退基础 flags")
                    vd = createVirtualDisplayAttempt(dm, name, width, height, dpi, surface, baseFlags, "受信基础")
                }
            } else {
                vd = createVirtualDisplayAttempt(dm, name, width, height, dpi, surface, baseFlags, "非受信")
            }
            val created = vd
            val id = created?.display?.displayId ?: -1
            fileLog("createVirtualDisplay -> vd=$created id=$id")
            if (created == null || id < 0) {
                Log.w(TAG, "createDisplay $name -> null")
                return -1
            }
            synchronized(virtualDisplays) { virtualDisplays[id] = created }
            // 只有受信屏能设 IME 策略（WMS 对非受信屏抛 SecurityException），且正需要它：
            // dock 屏只承载自有 DockActivity，无输入场景
            if (trusted) applyImePolicyLocal(id)
            Log.i(TAG, "createDisplay $name id=$id size=${width}x$height@$dpi")
            id
        } catch (t: Throwable) {
            fileLog("createDisplay $name failed: $t")
            Log.w(TAG, "createDisplay $name failed: $t")
            -1
        }
    }

    override fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        dpi: Int,
    ): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays[displayId] } ?: return false
        return try {
            vd.resize(width, height, dpi)
            Log.i(TAG, "resizeDisplay id=$displayId -> ${width}x$height@$dpi")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "resizeDisplay $displayId failed: $t")
            false
        }
    }

    override fun releaseDisplay(displayId: Int): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays.remove(displayId) }
        if (vd == null) {
            // 不在本进程 map：该屏由其它（旧版本）UserService 进程创建，只有创建者能释放。
            // 应用侧兜底见 CarInputClient.reapStaleUserServices（杀持有进程）。
            fileLog("releaseDisplay id=$displayId -> 非本进程持有（跨进程无法释放）")
            return false
        }
        return try {
            vd.release()
            Log.i(TAG, "releaseDisplay id=$displayId")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "releaseDisplay $displayId failed: $t")
            false
        }
    }

    /** 创建一次虚拟屏；失败（含 SecurityException / ROM 静默返回 null）返回 null 并留痕。 */
    private fun createVirtualDisplayAttempt(
        dm: android.hardware.display.DisplayManager,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
        label: String,
    ): android.hardware.display.VirtualDisplay? =
        try {
            fileLog("createDisplay($name) $label flags=0x${flags.toString(16)}")
            dm.createVirtualDisplay(name, width, height, dpi, surface, flags, null, null)
        } catch (t: Throwable) {
            fileLog("createDisplay($name) $label 异常：$t")
            null
        }

    // ---- 物理主屏电源（真息屏；系统保持唤醒） ----

    /**
     * 对**物理主屏**做硬件级断电/点亮（同 scrcpy `-S/--turn-screen-off`）：
     * 不按电源键（那会锁屏），而是直接调 SurfaceFlinger 的 setDisplayPowerMode。
     * 系统仍认为屏幕亮着 → 不锁屏、不进 Doze，虚拟屏继续出帧。
     */
    override fun setPhysicalScreenPower(on: Boolean): Boolean {
        val token = internalDisplayToken()
        if (token == null) {
            fileLog("setPhysicalScreenPower($on) 失败：拿不到物理屏 token")
            return false
        }
        val ok = setDisplayPowerMode(token, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)
        if (ok) physicalScreenOff = !on
        fileLog("setPhysicalScreenPower($on) ok=$ok")
        return ok
    }

    private fun setDisplayPowerMode(
        token: IBinder,
        mode: Int,
    ): Boolean =
        try {
            Class
                .forName("android.view.SurfaceControl")
                .getMethod("setDisplayPowerMode", IBinder::class.java, Integer.TYPE)
                .invoke(null, token, mode)
            true
        } catch (t: Throwable) {
            fileLog("SurfaceControl.setDisplayPowerMode($mode) 失败：$t")
            false
        }

    /**
     * 物理主屏 token。Android 14（SDK 34）删除了 `SurfaceControl.getPhysicalDisplayIds /
     * getPhysicalDisplayToken`，改由 `com.android.server.display.DisplayControl` 提供——
     * 它在 services.jar 里，必须用 `ClassLoaderFactory` + `SYSTEMSERVERCLASSPATH` 造一个
     * 类加载器才能看到（scrcpy 同款做法），并把 `android_servers` 加载进该 ClassLoader，
     * 否则 JNI 方法是未注册状态（UnsatisfiedLinkError）。
     */
    private fun internalDisplayToken(): IBinder? {
        if (Build.VERSION.SDK_INT >= 34) {
            displayControlClass?.let { cls ->
                try {
                    val ids = cls.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                    if (ids.isNotEmpty()) {
                        return cls
                            .getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!)
                            .invoke(null, ids[0]) as? IBinder
                    }
                } catch (t: Throwable) {
                    fileLog("DisplayControl 取 token 失败：$t")
                }
            }
        }
        // SDK < 34（或上面的路径失败）：SurfaceControl 的旧 API
        try {
            val sc = Class.forName("android.view.SurfaceControl")
            val token =
                runCatching { sc.getMethod("getInternalDisplayToken").invoke(null) }
                    .getOrElse {
                        val ids = sc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                        sc
                            .getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!)
                            .invoke(null, ids[0])
                    }
            return token as? IBinder
        } catch (t: Throwable) {
            fileLog("SurfaceControl 取 token 失败：$t")
        }
        return null
    }

    private val displayControlClass: Class<*>? by lazy {
        try {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create =
                factory.getDeclaredMethod(
                    "createClassLoader",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                    Integer.TYPE,
                    java.lang.Boolean.TYPE,
                    String::class.java,
                )
            val classpath = System.getenv("SYSTEMSERVERCLASSPATH")
            if (classpath.isNullOrEmpty()) {
                fileLog("SYSTEMSERVERCLASSPATH 为空——无法加载 DisplayControl")
                return@lazy null
            }
            val loader =
                create.invoke(
                    null,
                    classpath,
                    null,
                    null,
                    ClassLoader.getSystemClassLoader(),
                    0,
                    true,
                    null,
                ) as ClassLoader
            val cls = loader.loadClass("com.android.server.display.DisplayControl")
            // 静态 JNI 方法在 libandroid_servers.so 里注册；必须加载进这个 ClassLoader
            val loadLibrary0 =
                Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            loadLibrary0.isAccessible = true
            loadLibrary0.invoke(Runtime.getRuntime(), cls, "android_servers")
            fileLog("DisplayControl 已就绪")
            cls
        } catch (t: Throwable) {
            fileLog("DisplayControl 加载失败（SDK ${Build.VERSION.SDK_INT}）：$t")
            null
        }
    }

    override fun ping(): Boolean = injector != null

    override fun diag(): String =
        "injector=" + (injector?.let { "${it.receiver.javaClass.name} ${it.method}" } ?: "null") +
            " setDisplayId=${setDisplayIdMethod != null}"

    override fun pid(): Int = android.os.Process.myPid()

    companion object {
        private const val TAG = "CarInputSvc"

        /** 自清巡检周期：重装 APK 后旧进程最迟约 15s 内自行退出（成本仅一次 PM 查询）。 */
        private const val STALE_CHECK_INTERVAL_MS = 15_000L

        /** VIRTUAL_DISPLAY_FLAG_TRUSTED（@hide；AOSP 定义 = 1 << 10 = 0x400）。 */
        private const val FLAG_TRUSTED = 1 shl 10

        /** VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH（@hide；AOSP 定义 = 1 << 6 = 0x40）。 */
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6

        /**
         * VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP（@hide；AOSP 定义 = 1 << 11 = 0x800）。
         * 虚拟屏进自己的 DisplayGroup：手机主屏休眠/断电时，它不跟着进入 OFF。
         * 需要 ADD_TRUSTED_DISPLAY（受信屏本来就要）——见 DisplayManagerService 的校验。
         */
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

        /**
         * VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED（@hide；AOSP 定义 = 1 << 12 = 0x1000）。
         * 锁屏状态下该屏不显示 Keyguard。**另需 ADD_ALWAYS_UNLOCKED_DISPLAY**，
         * 部分 ROM 未授予 shell——createDisplay 里必须能降级重试，否则整块屏创建失败。
         */
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12

        /** SurfaceControl 显示电源模式：OFF=硬件断电，NORMAL=正常点亮。 */
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2
    }
}
