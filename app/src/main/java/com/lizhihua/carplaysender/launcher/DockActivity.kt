package com.lizhihua.carplaysender.launcher

import com.lizhihua.carplaysender.R

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * CarPlay dock（独立虚拟屏，含时钟/电量/运行中应用/回桌面）。
 *
 * 两种形态（布局见 res/layout）：
 *  - 横屏车机：dock 是左侧竖条 → activity_dock_sidebar.xml（时钟居顶、回桌面居底）
 *  - 竖屏车机：dock 是底部横条 → activity_dock_bar.xml（⌂ | 应用横排 | 时间/电量）
 * 形态由 dock 虚拟屏自身宽高决定（竖条 height>width；横条 width>height），与车机方向自动对齐。
 */
class DockActivity : AppCompatActivity() {

    private var appsContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var batteryText: TextView? = null
    private var batteryBar: View? = null
    private var batteryFill: View? = null
    private var barForm = false

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            pollHandler.postDelayed(this, 3_000)
        }
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            // 秒级刷新：车机屏上走秒 = 视频链路活着的直观证据
            clockView?.text = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
            clockView?.let { fitClockText(it) }
            clockHandler.postDelayed(this, 1_000)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateBattery(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        barForm = dm.widthPixels > dm.heightPixels
        setContentView(if (barForm) R.layout.activity_dock_bar else R.layout.activity_dock_sidebar)

        clockView = findViewById(R.id.dockClock)
        batteryText = findViewById(R.id.dockBatteryText)
        batteryBar = findViewById(R.id.dockBatteryBar)
        batteryFill = findViewById(R.id.dockBatteryFill)
        appsContainer = findViewById(R.id.dockApps)
        findViewById<TextView>(R.id.dockHome).setOnClickListener {
            Thread { VirtualLauncher.goGrid() }.start()
        }

        VirtualLauncher.addRunningAppsListener(refreshListener)
        pollHandler.postDelayed(pollRunnable, 3_000)
        clockHandler.post(clockTicker)
        // 荣耀 ROM 上 XML autoSize 对窄 dock 不生效（时钟被 ellipsize 成 "18:0…"）：
        // 布局完成后按实测宽度手工缩字号，保证 HH:mm:ss 完整显示
        clockView?.post { fitClockText(clockView!!) }
        // 系统受保护广播：ACTION_BATTERY_CHANGED 为 sticky，注册即回填当前电量
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        pollHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacks(clockTicker)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 逐级缩小字号直至文本宽度放得下（下限 6sp）。 */
    private fun fitClockText(view: TextView) {
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0) return
        var size = 14f
        view.textSize = size
        val paint = view.paint
        while (size > 6f && paint.measureText(view.text.toString()) > available) {
            size -= 0.5f
            view.textSize = size
        }
    }

    private fun updateBattery(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        runOnUiThread {
            batteryText?.text = (if (charging) "⚡" else "") + "$percent%"
            val bar = batteryBar
            val fill = batteryFill
            if (bar != null && fill != null) {
                bar.post {
                    val width = bar.width
                    if (width > 0) {
                        fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply {
                            this.width = (width * percent / 100)
                                .coerceAtLeast((3 * resources.displayMetrics.density).toInt())
                        }
                    }
                }
                fill.setBackgroundColor(if (percent <= 20) 0xFFFF6B6B.toInt() else 0xFF34C759.toInt())
            }
        }
    }

    private var lastRendered: List<String> = emptyList()

    private fun refresh() {
        val container = appsContainer ?: return
        val current = synchronized(VirtualLauncher.runningApps) {
            VirtualLauncher.runningApps.map { it.flattenToString() }
        }
        if (current == lastRendered) return
        lastRendered = current
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val iconPx = ((if (barForm) 32 else 30) * density).toInt()
        val pad = (6 * density).toInt()
        val apps = synchronized(VirtualLauncher.runningApps) { VirtualLauncher.runningApps.toList() }
        val iconCache = VirtualLauncher.iconCache
        apps.forEach { component ->
            val icon = iconCache[component] ?: packageManager.defaultActivityIcon
            container.addView(
                ImageView(this).apply {
                    setImageDrawable(icon)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = component.shortClassName
                    setOnClickListener {
                        Thread { VirtualLauncher.launchApp(component) }.start()
                    }
                },
                LinearLayout.LayoutParams(iconPx + pad * 2, iconPx + pad * 2),
            )
        }
    }

    override fun onBackPressed() = Unit // dock 常驻，不响应返回
}
