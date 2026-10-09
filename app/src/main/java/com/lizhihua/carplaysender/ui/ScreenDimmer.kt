package com.lizhihua.carplaysender.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.lizhihua.carplaysender.shizuku.Shell

/**
 * 常亮 + 暗幕：替代息屏的锁屏规避方案。
 *
 * 车机场景下手机锁屏会冻结非默认屏的输入（系统硬边界），且安全锁无法被命令解除。
 * 本方案让屏幕永不自动息屏（屏幕超时设为极大值，断开时恢复原值），
 * 无操作 45 秒后盖一层近黑全屏 View（模拟息屏观感），触摸手机任意位置即移除并重新计时。
 *
 * 触摸探针：1px 窗口 + FLAG_WATCH_OUTSIDE_TOUCH，捕获屏幕任意处的真实触摸（含第三方 app），
 * 仅用于重置计时，不拦截、不消费任何事件。
 */
object ScreenDimmer {

    private const val TAG = "ScreenDimmer"
    private const val IDLE_TIMEOUT_MS = 45_000L
    private const val TIMEOUT_MAX = 2_147_483_647

    /** 由设置项控制（默认开）。 */
    @Volatile
    var enabled: Boolean = true

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var handler: Handler? = null
    private var dimView: View? = null
    private var watchView: View? = null

    @Volatile
    private var dimShown = false

    private val showRunnable = Runnable { showDim() }

    fun attach(context: Context) {
        if (!enabled || appContext != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "缺少悬浮窗权限，暗幕不可用（常亮仍生效）")
            appContext = context.applicationContext
            windowManager = null
            handler = Handler(Looper.getMainLooper())
            enableStayAwake(context)
            return
        }
        appContext = context.applicationContext
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        enableStayAwake(context)
        handler?.post {
            addWatcher()
            schedule()
        }
        Log.i("CarPlaySender", "DIMMER: 已启动（常亮 + ${IDLE_TIMEOUT_MS / 1000}s 无操作后暗幕，点击恢复）")
    }

    fun detach() {
        val h = handler
        handler = null
        h?.removeCallbacksAndMessages(null)
        val wm = windowManager
        val dim = dimView
        val watch = watchView
        dimView = null
        watchView = null
        dimShown = false
        windowManager = null
        h?.post {
            dim?.let { v -> runCatching { wm?.removeView(v) } }
            watch?.let { v -> runCatching { wm?.removeView(v) } }
        }
        val ctx = appContext
        appContext = null
        if (ctx != null) restoreTimeout(ctx)
        Log.i("CarPlaySender", "DIMMER: 已停止（屏幕超时恢复）")
    }

    /** 暗幕是否已就绪（attach 完成且具备悬浮窗能力）。 */
    fun isAttached(): Boolean = handler != null && windowManager != null

    /** 手动进入假息屏（主页面按钮触发；点击暗幕或手机屏幕任意处恢复）。 */
    fun enterDimNow() {
        val h = handler ?: return
        // attach 时缺权限、之后才授予的：此处补建窗口管理器与触摸探针
        if (windowManager == null) {
            val ctx = appContext ?: return
            if (!android.provider.Settings.canDrawOverlays(ctx)) return
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            h.post { addWatcher() }
        }
        h.post { showDim() }
    }

    /** 物理触摸（任意 app/界面）或暗幕点击时调用。 */
    fun onUserInteraction() {
        val h = handler ?: return
        if (dimShown) hideDim()
        schedule()
    }

    private fun schedule() {
        handler?.removeCallbacks(showRunnable)
        handler?.postDelayed(showRunnable, IDLE_TIMEOUT_MS)
    }

    private fun showDim() {
        val ctx = appContext
        if (ctx == null) {
            android.util.Log.w("ScreenDimmer", "showDim: appContext 为空（attach 未执行）")
            return
        }
        val wm = windowManager
        if (wm == null) {
            android.util.Log.w("ScreenDimmer", "showDim: windowManager 为空（悬浮窗权限缺失？）")
            return
        }
        if (dimShown) return
        val view = View(ctx).apply {
            setBackgroundColor(Color.argb(0xF8, 0, 0, 0))
            isClickable = true
            setOnClickListener { onUserInteraction() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(view, params) }
            .onSuccess {
                dimView = view
                dimShown = true
                Log.i("CarPlaySender", "DIMMER: 暗幕已显示")
            }
            .onFailure { Log.w(TAG, "暗幕添加失败：${it.message}") }
    }

    private fun hideDim() {
        val view = dimView ?: return
        dimView = null
        dimShown = false
        runCatching { windowManager?.removeView(view) }
    }

    /** 1px 触摸探针：捕获屏幕上任意处的 DOWN 事件用于重置计时，不消费事件。 */
    private fun addWatcher() {
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val view = View(ctx).apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN ||
                    event.action == MotionEvent.ACTION_OUTSIDE
                ) {
                    onUserInteraction()
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { wm.addView(view, params) }
            .onSuccess { watchView = view }
            .onFailure { Log.w(TAG, "触摸探针添加失败：${it.message}") }
    }

    // ---- 屏幕常亮：改系统屏幕超时为永不，断开时恢复 ----

    private fun enableStayAwake(ctx: Context) {
        Thread({
            val current = Shell.exec("settings get system screen_off_timeout").output.trim().toIntOrNull()
            if (current == null) {
                Log.w(TAG, "读取屏幕超时失败（Shizuku 未就绪？）")
                return@Thread
            }
            if (current < TIMEOUT_MAX) {
                ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                    .putInt("screen_off_timeout_backup", current).apply()
            }
            Shell.exec("settings put system screen_off_timeout $TIMEOUT_MAX")
            Log.i("CarPlaySender", "DIMMER: 屏幕超时设为永不（原值 $current）")
        }, "dimmer-stayawake").start()
    }

    private fun restoreTimeout(ctx: Context) {
        Thread({
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.contains("screen_off_timeout_backup")) return@Thread
            val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
            prefs.edit().remove("screen_off_timeout_backup").apply()
            Shell.exec("settings put system screen_off_timeout $backup")
            Log.i("CarPlaySender", "DIMMER: 屏幕超时恢复为 $backup")
        }, "dimmer-restore").start()
    }
}
