package com.lizhihua.carplaysender.launcher

import com.lizhihua.carplaysender.R

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
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
 */
class GridActivity : AppCompatActivity() {

    private var pages: List<List<VirtualLauncher.AppEntry>> = emptyList()
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var iconSizePx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grid)

        val apps = VirtualLauncher.launchableApps(this)
        synchronized(VirtualLauncher.runningApps) { VirtualLauncher.iconCache.clear() }
        apps.forEach { VirtualLauncher.iconCache[it.component] = it.icon }

        // 排序：按首字母分组（数字组在前、汉字转拼音首字母），组内保持系统返回顺序
        val grouped = LinkedHashMap<String, MutableList<VirtualLauncher.AppEntry>>()
        apps.forEach { entry ->
            grouped.getOrPut(VirtualLauncher.indexLetterOf(entry.label)) { mutableListOf() }.add(entry)
        }
        pages = grouped.values.flatten().chunked(COLUMNS * ROWS)

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
        rebuildDots()
        pager?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)
        })
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
            pages[page].forEach { entry ->
                val cell = layoutInflater.inflate(R.layout.item_app_cell, grid, false)
                cell.findViewById<ImageView>(R.id.cellIcon).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    setImageBitmap(roundedIcon(entry.icon, iconSizePx))
                }
                cell.findViewById<TextView>(R.id.cellLabel).text = entry.label
                cell.setOnClickListener {
                    Toast.makeText(this@GridActivity, "启动 ${entry.label}", Toast.LENGTH_SHORT).show()
                    Thread { VirtualLauncher.launchApp(entry.component) }.start()
                }
                grid.addView(
                    cell,
                    GridLayout.LayoutParams().apply {
                        width = cellWidthPx
                        height = cellHeightPx
                    },
                )
            }
        }
    }

    /** App 图标 → 圆角位图（iOS 风格 ~22% 圆角）。 */
    private fun roundedIcon(drawable: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size * 0.22f
        val path = Path().apply {
            addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun rebuildDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
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
                ?.setColor(if (i == selected) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
        }
    }

    override fun onBackPressed() = Unit // 桌面不退出

    private companion object {
        const val COLUMNS = 4 // CarPlay 规格：强制 4 列
        const val ROWS = 3
    }
}
