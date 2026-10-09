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

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }

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
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }

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
    }

    override fun onDestroy() {
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        pollHandler.removeCallbacksAndMessages(null)
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
                LinearLayout.LayoutParams(dp(56), dp(56)),
            )
        }
    }

    override fun onBackPressed() = Unit // dock 常驻，不响应返回
}
