package io.github.shaonialife.cardeck.shizuku

import android.content.Context
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
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
        event: MotionEvent,
        displayId: Int,
    ): Boolean {
        val inj = injector
        if (inj == null) {
            event.recycle()
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
            event.recycle()
        }
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

    // WrongConstant：flags 里的 TRUSTED/ALWAYS_UNLOCKED/OWN_DISPLAY_GROUP 是 @hide 常量
    // （运行时可行性已真机实证），lint 的 IntDef 模型只认公开 API。
    @android.annotation.SuppressLint("WrongConstant")
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
            // VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED（@hide；AOSP 定义 = 1 << 12）：
            // KeyguardController.setKeyguardShown 对带此 flag 的屏**直接忽略锁屏状态**
            // （AOSP KeyguardController.java：isKeyguardAlwaysUnlocked → early return），
            // 锁屏（含 PIN 安全锁）不会给该屏发 keyguard sleep token，屏上活动保持
            // 可见/可交互——手机锁屏后车机画面冻结、触摸失效的根治手段。
            // 权限 ADD_ALWAYS_UNLOCKED_DISPLAY（signature|privileged）经实测 shell 已持有
            // （dumpsys package com.android.shell granted=true，2026-10-23 荣耀 Magic7 Pro）。
            // 注意必须同时声明 OWN_DISPLAY_GROUP（1 << 11，需 ADD_TRUSTED_DISPLAY）：
            // 该 flag「只对不在默认 display group 的虚拟屏有效」（DisplayManager 文档），
            // 默认组内的屏会被系统剥掉（2026-10-23 实测：不带 OWN_DISPLAY_GROUP 时创建
            // 成功无异常，但读回 display.flags 无 ALWAYS_UNLOCKED）。
            // ROM 不认这个组合时（抛异常或静默返回 null）降级去掉重试，行为与旧版一致。
            val flags = baseFlags or FLAG_ALWAYS_UNLOCKED or FLAG_OWN_DISPLAY_GROUP
            fileLog("flags=0x${flags.toString(16)}")
            val vd =
                try {
                    dm.createVirtualDisplay(name, width, height, dpi, surface, flags, null, null)
                        ?: throw IllegalStateException("createVirtualDisplay 返回 null")
                } catch (t: Throwable) {
                    fileLog("带 ALWAYS_UNLOCKED 创建失败（$t），降级重试 flags=0x${baseFlags.toString(16)}")
                    dm.createVirtualDisplay(name, width, height, dpi, surface, baseFlags, null, null)
                }
            val id = vd?.display?.displayId ?: -1
            if (id >= 0) {
                // 读回校验：flag 生效时 display.flags 应带 FLAG_ALWAYS_UNLOCKED（Display 层 = 1 << 9）
                val realFlags =
                    try {
                        dm.getDisplay(id)?.flags ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                val effective = (realFlags and 0x200) != 0
                fileLog(
                    "ALWAYS_UNLOCKED 读回 display.flags=0x${realFlags.toString(16)} -> " +
                        if (effective) "生效" else "未生效（锁屏仍会影响该屏）",
                )
            }
            fileLog("createVirtualDisplay -> vd=$vd id=$id")
            if (vd == null || id < 0) {
                Log.w(TAG, "createDisplay $name -> null")
                return -1
            }
            synchronized(virtualDisplays) { virtualDisplays[id] = vd }
            // 只有受信屏能设 IME 策略（WMS 对非受信屏抛 SecurityException），且正需要它：
            // dock 屏只承载自有 DockActivity，无输入场景
            if (trusted) applyImePolicyLocal(id)
            Log.i(TAG, "createDisplay $name id=$id size=${width}x$height@$dpi flags=0x${flags.toString(16)}")
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

    override fun ping(): Boolean = injector != null

    override fun setScreenPower(on: Boolean): Boolean =
        try {
            val token = builtInDisplayToken()
            if (token == null) {
                fileLog("setScreenPower($on) 失败：各入口都拿不到内置屏 token")
                false
            } else {
                val mode = if (on) powerModeNormal else powerModeOff
                screenPowerMethod().invoke(null, token, mode)
                fileLog("setScreenPower($on) ok mode=$mode")
                true
            }
        } catch (t: Throwable) {
            fileLog("setScreenPower($on) failed: $t")
            false
        }

    /**
     * 内置屏 token：静态方法随系统版本迁移，逐级回退并留痕——
     * ① SurfaceControl.getPhysicalDisplayIds/getPhysicalDisplayToken（Android 11~14）
     * ② DisplayControl.getPhysicalDisplayIds/getPhysicalDisplayToken（新系统入口，@hide 类）
     * ③ SurfaceControl.getInternalDisplayToken（更老 API，部分 ROM 仍保留）
     * 首个物理屏 id 即内置屏（AOSP 约定数组首元素为默认屏）。
     */
    // BlockedPrivateApi：本方法就是刻意走隐藏 API（UserService 跑在 shell 域，不受应用层
    // hidden API 强制约束；三分支运行时行为已真机实证）。
    @android.annotation.SuppressLint("BlockedPrivateApi")
    private fun builtInDisplayToken(): Any? {
        val sc = SurfaceControl::class.java
        // ① SurfaceControl
        runCatching {
            val ids = sc.getDeclaredMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val id = ids.firstOrNull() ?: return@runCatching
            val token = sc.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id)
            if (token != null) {
                fileLog("token via SurfaceControl id=$id")
                return token
            }
        }.onFailure { fileLog("SurfaceControl token 入口失败：$it") }
        // ② DisplayControl（新系统：SurfaceControl 上的两个方法已迁出）
        runCatching {
            val dc = Class.forName("android.hardware.display.DisplayControl")
            val ids = dc.getDeclaredMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val id = ids.firstOrNull() ?: return@runCatching
            val token = dc.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id)
            if (token != null) {
                fileLog("token via DisplayControl id=$id")
                return token
            }
        }.onFailure { fileLog("DisplayControl token 入口失败：$it") }
        // ③ 内部屏旧入口
        return runCatching {
            val token = sc.getDeclaredMethod("getInternalDisplayToken").invoke(null)
            if (token != null) fileLog("token via getInternalDisplayToken")
            token
        }.onFailure {
            fileLog("getInternalDisplayToken 入口失败：$it")
        }.getOrNull()
    }

    @Volatile
    private var screenPowerMethodCache: Method? = null

    // SoonBlockedPrivateApi：setDisplayPowerMode 对普通应用会被逐步封锁，但 UserService
    // 跑在 shell 域不受约束（真机实证：断电/恢复均已生效）。
    @android.annotation.SuppressLint("SoonBlockedPrivateApi")
    private fun screenPowerMethod(): Method {
        screenPowerMethodCache?.let { return it }
        // 常量 reflective 读 + 字面量兜底：POWER_MODE_OFF=0 / POWER_MODE_NORMAL=2（AOSP 稳定值）
        try {
            powerModeNormal = SurfaceControl::class.java.getField("POWER_MODE_NORMAL").getInt(null)
            powerModeOff = SurfaceControl::class.java.getField("POWER_MODE_OFF").getInt(null)
        } catch (_: Throwable) {
        }
        return SurfaceControl::class.java
            .getDeclaredMethod("setDisplayPowerMode", IBinder::class.java, Integer.TYPE)
            .also { screenPowerMethodCache = it }
    }

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

        /** VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED（@hide；AOSP 定义 = 1 << 12 = 0x1000）。 */
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12

        /** VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP（@hide；AOSP 定义 = 1 << 11 = 0x800）。 */
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

        /** SurfaceControl 显示电源模式（@hide；AOSP 定义 OFF=0 / NORMAL=2，反射失败时兜底用）。 */
        private var powerModeOff = 0

        private var powerModeNormal = 2
    }
}
