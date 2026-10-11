package com.lizhihua.carplaysender.launcher

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 常驻 dock（跑在专用窄条虚拟屏上，经 GL 合成器与主应用屏拼成完整画面）。
 * 自上而下：⌂ 回桌面（应用屏切到 GridActivity）、运行中应用图标（点击切换/续运行）。
 */
class DockActivity : AppCompatActivity() {

    private var list: LinearLayout? = null
    private var clockView: TextView? = null
    private var batteryText: TextView? = null
    private var batteryBarBg: android.widget.FrameLayout? = null
    private var batteryBarFill: android.view.View? = null

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }

    private val clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            clockView?.text = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis())
            clockHandler.postDelayed(this, 1_000)
        }
    }

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            updateBattery(intent)
        }
    }

    private fun updateBattery(intent: android.content.Intent?) {
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = (level * 100) / scale
        val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
        runOnUiThread {
            batteryText?.text = (if (charging) "⚡" else "") + "$percent%"
            (batteryBarFill?.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
                val bar = batteryBarBg?.width?.takeIf { it > 0 } ?: return@let
                lp.width = (bar * percent / 100).coerceAtLeast(bar / 12)
                batteryBarFill?.layoutParams = lp
            }
            batteryBarFill?.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = (resources.displayMetrics.density * 2).toInt().toFloat()
                setColor(
                    when {
                        charging -> 0xFF7EE3A0.toInt()
                        percent <= 20 -> 0xFFFF6B6B.toInt()
                        else -> 0xFF7EE3A0.toInt()
                    },
                )
            }
        }
    }

    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            pollHandler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF161C22.toInt())
            setPadding(dp(6), dp(12), dp(6), dp(10))
        }

        // 时钟（CarPlay dock 顶部样式，HH:mm 每秒刷新）
        clockView = TextView(this).apply {
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(clockView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 电量：百分比 + 细条（注册系统电池广播实时更新）
        batteryText = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF9AB0C4.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(batteryText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        batteryBarBg = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(0xFF2A3441.toInt())
            }
            batteryBarFill = android.view.View(this@DockActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(0xFF7EE3A0.toInt())
                }
            }
            addView(
                batteryBarFill,
                android.widget.FrameLayout.LayoutParams(0, dp(4)),
            )
        }
        root.addView(
            batteryBarBg,
            LinearLayout.LayoutParams(dp(38), dp(4)).apply { topMargin = dp(3) },
        )

        root.addView(
            View_separator(this, dp),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) },
        )

        val home = TextView(this).apply {
            text = "⌂"
            textSize = 30f
            setTextColor(0xFFE6EDF3.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                Thread { VirtualLauncher.goGrid() }.start()
            }
        }
        root.addView(home, LinearLayout.LayoutParams(dp(64), dp(64)))

        root.addView(
            View_separator(this, dp),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
        )

        root.addView(
            TextView(this).apply {
                text = "运行中"
                setTextColor(0xFF9AB0C4.toInt())
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            },
        )

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // 运行中列表放入 ScrollView：应用多时图标可上下滚动（问题 2 修复）。
        // 用 weight=1f 占满剩余高度，ScrollView 才能获得有限的测量高度并启用滚动。
        val listScroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            addView(
                list,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(
            listScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        VirtualLauncher.addRunningAppsListener(refreshListener)
        pollHandler.postDelayed(pollRunnable, 3_000)
        clockHandler.post(clockTicker)
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED))
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_POWER_DISCONNECTED))
        updateBattery(registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)))
    }

    override fun onDestroy() {
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        pollHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacks(clockTicker)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    private fun View_separator(context: android.content.Context, dp: (Int) -> Int): android.view.View =
        android.view.View(context).apply { setBackgroundColor(0xFF2A3441.toInt()) }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private var lastRendered: List<String> = emptyList()

    private fun refresh() {
        val container = list ?: return
        val current = synchronized(VirtualLauncher.runningApps) {
            VirtualLauncher.runningApps.map { it.flattenToString() }
        }
        if (current == lastRendered) return
        lastRendered = current
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val apps = synchronized(VirtualLauncher.runningApps) { VirtualLauncher.runningApps.toList() }
        if (apps.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "暂无"
                    setTextColor(0xFF5A6B7A.toInt())
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                },
            )
            return
        }
        val iconCache = VirtualLauncher.iconCache
        apps.forEach { component ->
            val icon = iconCache[component] ?: packageManager.defaultActivityIcon
            container.addView(
                ImageView(this).apply {
                    setImageDrawable(icon)
                    setPadding(0, dp(6), 0, dp(6))
                    contentDescription = component.shortClassName
                    setOnClickListener {
                        Thread { VirtualLauncher.launchApp(component) }.start()
                    }
                },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
        }
    }

    override fun onBackPressed() = Unit // dock 常驻，不响应返回
}
