package com.lizhihua.carplaysender.launcher

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 应用桌面（跑在主应用虚拟屏上）：应用图标网格。
 * 点击图标 → 应用启动到本屏（全屏覆盖网格）；dock ⌂ 或切换回本 activity 即回桌面，
 * 后台应用任务保留（进程不杀），再次启动即续运行。
 */
class GridActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1117.toInt())
        }
        root.addView(
            TextView(this).apply {
                text = "CarPlay Sender"
                setTextColor(0xFFE6EDF3.toInt())
                textSize = 22f
                setPadding(dp(20), dp(20), dp(20), dp(8))
            },
        )
        root.addView(
            TextView(this).apply {
                text = "点击应用在车机屏运行；左栏切换运行中的应用"
                setTextColor(0xFF5A6B7A.toInt())
                textSize = 12f
                setPadding(dp(20), 0, dp(20), dp(12))
            },
        )

        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val columns = (screenWidthDp / 200f).toInt().coerceAtLeast(3)
        val cellDp = (screenWidthDp / columns).toInt()
        val grid = GridLayout(this).apply {
            columnCount = columns
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)

        val apps = VirtualLauncher.launchableApps(this)
        synchronized(VirtualLauncher.runningApps) {
            VirtualLauncher.iconCache.clear()
        }
        apps.forEach { entry ->
            VirtualLauncher.iconCache[entry.component] = entry.icon
        }
        apps.forEach { entry ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(14))
            }
            cell.addView(
                ImageView(this).apply { setImageDrawable(entry.icon) },
                LinearLayout.LayoutParams(dp(84), dp(84)),
            )
            cell.addView(
                TextView(this).apply {
                    text = entry.label
                    setTextColor(0xFFE6EDF3.toInt())
                    textSize = 14f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            cell.setOnClickListener {
                Toast.makeText(this, "启动 ${entry.label}", Toast.LENGTH_SHORT).show()
                Thread { VirtualLauncher.launchApp(entry.component) }.start()
            }
            grid.addView(
                cell,
                GridLayout.LayoutParams().apply {
                    width = dp(cellDp)
                    height = dp(168)
                },
            )
        }
    }

    override fun onBackPressed() = Unit // 桌面不退出
}
