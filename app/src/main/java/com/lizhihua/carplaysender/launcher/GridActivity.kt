package com.lizhihua.carplaysender.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * 应用桌面（跑在主应用虚拟屏上）—— CarPlay 车机版样式。
 *
 * 纯黑背景 + 顶部状态栏时钟（走秒=视频链路活性的直观证据）+ 大圆角图标
 * 分页网格（4×3/页，左右滑动翻页）+ 底部页点；右侧半透明窄字母条可快速
 * 跳到目标字母所在页（车机上找中文 App 的效率件，CarPlay 本体没有）。
 * 排序：数字组（#）在前，随后 A-Z；汉字经 ICU 转写归入拼音首字母组。
 */
class GridActivity : AppCompatActivity() {

    // ---- 数据（onCreate 一次装载）----
    private data class Cell(val entry: VirtualLauncher.AppEntry, val letter: String)

    private var cells: List<Cell> = emptyList()
    private var columns = 4
    private var perPage = 12
    private var cellWidthPx = 0
    private var cellHeightPx = 0

    // ---- UI 句柄 ----
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var navBar: LinearLayout? = null
    private var bubble: TextView? = null

    private var letters: List<String> = emptyList() // 去重后的分区字母（flat 顺序）
    private val letterFirstIndex = HashMap<String, Int>() // 字母 → flat 下标（跳页用）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // ---- 应用列表（字母分组顺序拍平；CarPlay 无分区头，纯图标流）----
        val apps = VirtualLauncher.launchableApps(this)
        synchronized(VirtualLauncher.runningApps) { VirtualLauncher.iconCache.clear() }
        apps.forEach { VirtualLauncher.iconCache[it.component] = it.icon }
        cells = apps.map { Cell(it, VirtualLauncher.indexLetterOf(it.label)) }
        letters = cells.map { it.letter }.distinct()
        letters.forEachIndexed { index, letter ->
            letterFirstIndex.putIfAbsent(letter, index)
        }

        // ---- 布局 ----
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 页面区：ViewPager + 右侧字母条浮层。
        // 格子尺寸按「状态栏/页点占用后的真实可用区域」精确计算， GridLayout 显式
        // 指定格子宽高——否则按内容 packing 会挤在左上角、右侧大片留白
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val statusPx = dp(60)
        val dotsPx = dp(35)
        val navPx = dp(40)
        val pagerW = screenW - navPx
        val pagerH = screenH - statusPx - dotsPx
        columns = if (pagerW / 4 >= dp(170)) 4 else 3
        perPage = columns * 3
        cellWidthPx = pagerW / columns
        cellHeightPx = pagerH / 3
        val pagerArea = FrameLayout(this)

        val viewPager = ViewPager2(this).apply {
            adapter = PageAdapter()
            offscreenPageLimit = 2
        }
        pager = viewPager
        pagerArea.addView(
            viewPager,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x26FFFFFF)
            }
            setPadding(dp(2), dp(16), dp(2), dp(16))
        }
        navBar = nav
        letters.forEach { letter ->
            nav.addView(
                TextView(this).apply {
                    text = letter
                    setTextColor(0xFF9A9AA5.toInt())
                    textSize = 11f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(30), 0, 1f),
            )
        }
        nav.setOnTouchListener { _, event -> handleNavTouch(event); true }
        pagerArea.addView(
            nav,
            FrameLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
                rightMargin = dp(6)
            },
        )

        // 中央字母气泡（拖动反馈）
        val bubbleView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 44f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xDD1F6FEB.toInt())
            }
            visibility = View.INVISIBLE
        }
        bubble = bubbleView
        pagerArea.addView(
            bubbleView,
            FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER),
        )

        content.addView(
            pagerArea,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // 页点
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(10))
        }
        dotsRow = dots
        content.addView(
            dots,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        rebuildDots()
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                highlightLetter(letterForPage(position))
                updateDots(position)
            }
        })
        highlightLetter(letterForPage(0))
    }

    // ---- 分页 ----

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = (cells.size + perPage - 1) / perPage

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val grid = GridLayout(parent.context).apply {
                columnCount = columns
                useDefaultMargins = false
                // ViewPager2 硬性要求：页面根布局必须 match_parent（否则 IllegalStateException 崩溃）
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            return PageHolder(grid)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(position, cellWidthPx, cellHeightPx)
        }
    }

    private inner class PageHolder(private val grid: GridLayout) : RecyclerView.ViewHolder(grid) {
        fun bind(page: Int, cellW: Int, cellH: Int) {
            grid.removeAllViews()
            val from = page * perPage
            val to = minOf(from + perPage, cells.size)
            for (index in from until to) {
                grid.addView(
                    buildCell(cells[index]),
                    GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED),
                        GridLayout.spec(GridLayout.UNDEFINED),
                    ).apply {
                        width = cellW
                        height = cellH
                    },
                )
            }
        }
    }

    private fun buildCell(cell: Cell): View {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val iconSize = (cellHeightPx * 0.55f).toInt().coerceAtMost((cellWidthPx * 0.62f).toInt())
        layout.addView(
            ImageView(this).apply {
                setImageBitmap(roundedIcon(cell.entry.icon, iconSize))
            },
            LinearLayout.LayoutParams(iconSize, iconSize),
        )
        layout.addView(
            TextView(this).apply {
                text = cell.entry.label
                setTextColor(0xFFF2F2F7.toInt())
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        layout.setOnClickListener {
            Toast.makeText(this, "启动 ${cell.entry.label}", Toast.LENGTH_SHORT).show()
            Thread { VirtualLauncher.launchApp(cell.entry.component) }.start()
        }
        return layout
    }

    /** App 图标 → 圆角位图（iOS 风格 ~22% 圆角；AdaptiveIcon 直接绘制两层）。 */
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

    // ---- 页点 ----

    private fun rebuildDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
        val count = (cells.size + perPage - 1) / perPage
        val density = resources.displayMetrics.density
        repeat(count) {
            row.addView(
                View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0x66FFFFFF)
                    }
                },
                LinearLayout.LayoutParams((7 * density).toInt(), (7 * density).toInt()).apply {
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

    // ---- 字母条：拖动/点击 → 跳到目标字母所在页 ----

    private fun letterForPage(page: Int): String? {
        val index = page * perPage
        return cells.getOrNull(index)?.letter
    }

    private fun pageForLetter(letter: String): Int {
        val index = letterFirstIndex[letter] ?: return pager?.currentItem ?: 0
        return index / perPage
    }

    private fun handleNavTouch(event: MotionEvent) {
        val nav = navBar ?: return
        if (letters.isEmpty() || nav.height == 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val index = (event.y / nav.height * letters.size)
                    .toInt().coerceIn(0, letters.size - 1)
                val letter = letters[index]
                bubble?.text = letter
                bubble?.visibility = View.VISIBLE
                highlightLetter(letter)
                pager?.currentItem = pageForLetter(letter)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                bubble?.postDelayed({ bubble?.visibility = View.INVISIBLE }, 500)
            }
        }
    }

    private fun highlightLetter(letter: String?) {
        val bar = navBar ?: return
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i) as? TextView ?: continue
            val selected = letters.getOrNull(i) == letter
            child.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF9A9AA5.toInt())
            child.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    override fun onBackPressed() = Unit // 桌面不退出
}
