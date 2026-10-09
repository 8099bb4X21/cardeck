package com.lizhihua.carplaysender.shizuku

import android.content.Context
import android.hardware.input.InputManager
import android.view.Surface
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
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
class CarInputServiceImpl(private val context: Context) : ICarInput.Stub() {

    private class Injector(val receiver: Any, val method: Method)

    private val injector: Injector? by lazy {
        val im = try {
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

    private fun inject(event: MotionEvent, displayId: Int): Boolean {
        val inj = injector
        if (inj == null) {
            event.recycle()
            return false
        }
        return try {
            setDisplayIdMethod?.invoke(event, displayId)
            val m = inj.method
            val result: Boolean = when {
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
    private fun build(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float): MotionEvent? {
        return try {
            val e = MotionEvent.obtain(
                downTime, eventTime, action, x, y,
                1f, 1f, 0, 1f, 1f, 0, 0,
            )
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            e
        } catch (t: Throwable) {
            Log.d(TAG, "build MotionEvent 异常", t)
            null
        }
    }

    override fun tap(displayId: Int, x: Int, y: Int): Boolean {
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

    override fun pointerDown(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        dragDownTime = now
        dragDisplay = displayId
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        return inject(down, displayId)
    }

    override fun pointerMove(displayId: Int, x: Int, y: Int): Boolean {
        if (dragDisplay != displayId) return false
        val now = SystemClock.uptimeMillis()
        val move = build(dragDownTime, now, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat()) ?: return false
        return inject(move, displayId)
    }

    override fun pointerUp(displayId: Int, x: Int, y: Int): Boolean {
        if (dragDisplay != displayId) return false
        dragDisplay = -1
        val now = SystemClock.uptimeMillis()
        val up = build(dragDownTime, now, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    // ---- 受信虚拟屏 ----

    // VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 30（@hide/@SystemApi，编译期不可引用，写原始值）
    private val virtualDisplays = HashMap<Int, android.hardware.display.VirtualDisplay>()

    /** 文件日志：shell 进程 logcat 被 HKS 加密，写 /data/local/tmp 供 adb 读取。 */
    private fun fileLog(message: String) {
        try {
            java.io.FileWriter("/data/local/tmp/carinput_svc.log", true).use { w ->
                w.append(android.text.format.DateFormat.format("HH:mm:ss.SSS", System.currentTimeMillis()).toString())
                    .append(' ').append(message).append('\n')
            }
        } catch (_: Throwable) {
        }
    }

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int {
        return try {
            fileLog("createDisplay $name ${width}x$height@$dpi start")
            // 关键：必须用「shell 包的 Context」——系统检查 packageName 与调用 uid(2000) 匹配，
            // 用本 app 的 Context 会报 SecurityException('packageName must match the owner uid')
            val shellContext = try {
                context.createPackageContext("com.android.shell", 0)
            } catch (t: Throwable) {
                fileLog("createPackageContext(com.android.shell) 失败：$t，回退本 app context")
                context
            }
            fileLog("ctx package=${shellContext.packageName}")
            val dm = shellContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val flags = FLAG_TRUSTED or
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            fileLog("flags=0x${flags.toString(16)}")
            val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, flags, null, null)
            val id = vd?.display?.displayId ?: -1
            fileLog("createVirtualDisplay -> vd=$vd id=$id")
            if (vd == null || id < 0) {
                Log.w(TAG, "createDisplay $name -> null")
                return -1
            }
            synchronized(virtualDisplays) { virtualDisplays[id] = vd }
            Log.i(TAG, "createDisplay $name id=$id size=${width}x$height@$dpi flags=0x${flags.toString(16)}")
            id
        } catch (t: Throwable) {
            fileLog("createDisplay $name failed: $t")
            Log.w(TAG, "createDisplay $name failed: $t")
            -1
        }
    }

    override fun releaseDisplay(displayId: Int): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays.remove(displayId) } ?: return false
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

    override fun diag(): String {
        return "injector=" + (injector?.let { "${it.receiver.javaClass.name} ${it.method}" } ?: "null") +
            " setDisplayId=${setDisplayIdMethod != null}"
    }

    companion object {
        private const val TAG = "CarInputSvc"

        /** VIRTUAL_DISPLAY_FLAG_TRUSTED（@hide；AOSP 定义 = 1 << 10 = 0x400）。 */
        private const val FLAG_TRUSTED = 1 shl 10
    }
}
