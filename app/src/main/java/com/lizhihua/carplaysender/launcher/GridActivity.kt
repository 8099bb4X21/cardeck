package com.lizhihua.carplaysender.launcher

import com.lizhihua.carplaysender.R

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * CarPlay 主区桌面：纯黑背景 + 4×3 图标分页网格 + 底部页点。
 * 布局声明在 res/layout（activity_grid / item_grid_page / item_app_cell），
 * 代码只做动态格子尺寸计算与数据绑定。无字母导航（对照 CarPlay 主屏）。
 *
 * 默认只显示地图类应用（DesktopApps 默认选择），第一格固定「车机设置」入口；
 * 用户可在设置页勾选要显示的应用，返回桌面（onResume）即时刷新。
 */
class GridActivity : AppCompatActivity() {

    /** 桌面格子：固定设置入口 / 普通应用。 */
    private sealed interface Cell {
        object Pinned : Cell
        data class App(val entry: VirtualLauncher.AppEntry) : Cell
    }

    private var pages: List<List<Cell>> = emptyList()
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var iconSizePx = 0

    /** 应用清单缓存（每次 resume 重扫 packageManager 会拖慢回桌面——仅在首次载入时扫描）。 */
    private var appsCache: List<VirtualLauncher.AppEntry>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid)

        // 格子尺寸按屏幕动态计算（布局关系在 XML，尺寸测量在代码）
        val dm = resources.displayMetrics
        val density = dm.density
        val chromeHorizontalPx = (2 * 10 * density).toInt() // item_grid_page 左右 padding
        val chromeVerticalPx = ((8 + 26 + 16) * density).toInt() // 上/下 padding + 页点区
        cellWidthPx = (dm.widthPixels - chromeHorizontalPx) / COLUMNS
        cellHeightPx = (dm.heightPixels - chromeVerticalPx) / ROWS
        iconSizePx = (cellHeightPx * 0.63f).toInt().coerceAtMost((cellWidthPx * 0.72f).toInt())

        pager = findViewById<ViewPager2>(R.id.gridPager).apply {
            offscreenPageLimit = 2
            adapter = PageAdapter()
        }
        dotsRow = findViewById(R.id.dotsRow)
        pager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)
        })
    }

    override fun onResume() {
        super.onResume()
        // 也覆盖从设置页返回：勾选变化即时反映
        reload()
    }

    /** 重新读取应用清单与桌面选择（首帧与每次回到桌面时调用）。 */
    private fun reload() {
        val apps = appsCache ?: run {
            val scanned = VirtualLauncher.launchableApps(this)
            // iconCache 供 dock 运行中图标使用（每次重扫时同步刷新）
            synchronized(VirtualLauncher.runningApps) { VirtualLauncher.iconCache.clear() }
            scanned.forEach { VirtualLauncher.iconCache[it.component] = it.icon }
            appsCache = scanned
            scanned
        }

        val selected = DesktopApps.selected(this, apps)
        val cells = buildList {
            add(Cell.Pinned) // 第一格：车机设置（固定，不可取消）
            apps.filter { it.component.flattenToString() in selected }.forEach { add(Cell.App(it)) }
        }
        pages = cells.chunked(COLUMNS * ROWS)

        val current = pager?.currentItem ?: 0
        pager?.adapter?.notifyDataSetChanged()
        rebuildDots()
        if (current >= pages.size) pager?.setCurrentItem(pages.size - 1, false)
        updateDots(pager?.currentItem ?: 0)
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val grid = layoutInflater.inflate(R.layout.item_grid_page, parent, false) as GridLayout
            grid.columnCount = COLUMNS
            return PageHolder(grid)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) = holder.bind(position)
    }

    private inner class PageHolder(private val grid: GridLayout) : RecyclerView.ViewHolder(grid) {
        fun bind(page: Int) {
            grid.removeAllViews()
            pages[page].forEach { cell ->
                val view = layoutInflater.inflate(R.layout.item_app_cell, grid, false)
                val icon = view.findViewById<ImageView>(R.id.cellIcon).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                }
                val label = view.findViewById<TextView>(R.id.cellLabel)
                when (cell) {
                    is Cell.Pinned -> {
                        icon.setImageResource(R.drawable.ic_app_settings)
                        label.text = getString(R.string.car_settings)
                        view.setOnClickListener { openSettings() }
                    }

                    is Cell.App -> {
                        icon.setImageBitmap(AppIcons.rounded(cell.entry.icon, iconSizePx))
                        label.text = cell.entry.label
                        view.setOnClickListener {
                            Toast.makeText(this@GridActivity, "启动 ${cell.entry.label}", Toast.LENGTH_SHORT).show()
                            Thread { VirtualLauncher.launchApp(cell.entry.component) }.start()
                        }
                    }
                }
                grid.addView(
                    view,
                    GridLayout.LayoutParams().apply {
                        width = cellWidthPx
                        height = cellHeightPx
                    },
                )
            }
        }
    }

    private fun openSettings() {
        // 滑动转场由 SettingsActivity 主题的 windowAnimationStyle 提供
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun rebuildDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
        if (pages.size < 2) return // 单页不显示页点（对照原型）
        val density = resources.displayMetrics.density
        repeat(pages.size) {
            row.addView(
                android.view.View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0x66FFFFFF)
                    }
                },
                LinearLayout.LayoutParams((6 * density).toInt(), (6 * density).toInt()).apply {
                    marginStart = (3 * density).toInt()
                    marginEnd = (3 * density).toInt()
                },
            )
        }
        updateDots(0)
    }

    private fun updateDots(selected: Int) {
        val row = dotsRow ?: return
        for (i in 0 until row.childCount) {
            (row.getChildAt(i).background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (i == selected) 0xFFFFFFFF.toInt() else 0x66FFFFFF.toInt())
        }
    }

    override fun onBackPressed() = Unit // 桌面不退出

    private companion object {
        const val COLUMNS = 4 // CarPlay 规格：强制 4 列
        const val ROWS = 3
    }
}
