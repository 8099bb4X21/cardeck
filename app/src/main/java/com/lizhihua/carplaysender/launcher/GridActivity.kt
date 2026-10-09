package com.lizhihua.carplaysender.launcher

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 应用桌面（跑在主应用虚拟屏上）：应用图标网格 + 右侧字母导航条。
 *
 * 排序：数字组（#）在前，随后 A-Z；汉字经 ICU 转写归入其拼音首字母组，
 * 组内用 zh Collator（拼音级）排序。点击图标 → 应用启动到本屏。
 */
class GridActivity : AppCompatActivity() {

    private var scroll: ScrollView? = null
    private var bubble: TextView? = null
    private var navBar: LinearLayout? = null
    private var letters: List<String> = emptyList()
    private val sectionOffsets = HashMap<String, Int>()

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
                text = "点击应用在车机屏运行；右栏字母条可快速定位"
                setTextColor(0xFF5A6B7A.toInt())
                textSize = 12f
                setPadding(dp(20), 0, dp(20), dp(12))
            },
        )

        val apps = VirtualLauncher.launchableApps(this)
        synchronized(VirtualLauncher.runningApps) {
            VirtualLauncher.iconCache.clear()
        }
        apps.forEach { VirtualLauncher.iconCache[it.component] = it.icon }

        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val contentWidthDp = screenWidthDp - 48f // 扣除右侧字母导航条
        val columns = (contentWidthDp / 200f).toInt().coerceAtLeast(3)
        val cellDp = (contentWidthDp / columns).toInt()

        val grid = GridLayout(this).apply {
            columnCount = columns
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        val grouped = LinkedHashMap<String, MutableList<VirtualLauncher.AppEntry>>()
        apps.forEach { entry ->
            grouped.getOrPut(VirtualLauncher.indexLetterOf(entry.label)) { mutableListOf() }.add(entry)
        }
        letters = grouped.keys.toList()

        val headerViews = LinkedHashMap<String, View>()
        grouped.forEach { (letter, entries) ->
            val header = TextView(this).apply {
                text = letter
                setTextColor(0xFF7EE3A0.toInt())
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(6), dp(14), 0, dp(6))
            }
            grid.addView(
                header,
                GridLayout.LayoutParams().apply {
                    columnSpec = GridLayout.spec(0, columns, 1f)
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                },
            )
            headerViews[letter] = header
            entries.forEach { entry ->
                grid.addView(
                    buildCell(entry, dp, cellDp, columns),
                    GridLayout.LayoutParams().apply {
                        width = dp(cellDp)
                        height = dp(168)
                    },
                )
            }
        }

        val scrollView = ScrollView(this).apply { addView(grid) }
        scroll = scrollView
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), dp(4), dp(24))
        }
        navBar = nav
        letters.forEach { letter ->
            nav.addView(
                TextView(this).apply {
                    text = letter
                    setTextColor(0xFF5A6B7A.toInt())
                    textSize = 11f
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(44), 0, 1f),
            )
        }
        nav.setOnTouchListener { _, event ->
            handleNavTouch(event, dp)
            true
        }

        val horizontal = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        horizontal.addView(
            scrollView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        horizontal.addView(
            nav,
            LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT),
        )

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

        val content = FrameLayout(this)
        content.addView(horizontal, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        content.addView(
            bubbleView,
            FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER),
        )
        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)

        // 布局完成后记录各分区头的 y 偏移（导航条跳转目标）
        scrollView.post {
            headerViews.forEach { (letter, view) ->
                sectionOffsets[letter] = view.top
            }
        }
    }

    private fun buildCell(
        entry: VirtualLauncher.AppEntry,
        dp: (Int) -> Int,
        cellDp: Int,
        columns: Int,
    ): View {
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
        return cell
    }

    /** 导航条触摸：按 y 位置选字母 → 高亮 + 滚动到分区 + 中央气泡。 */
    private fun handleNavTouch(event: MotionEvent, dp: (Int) -> Int) {
        val nav = navBar ?: return
        if (letters.isEmpty() || nav.height == 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val index = (event.y / nav.height * letters.size)
                    .toInt().coerceIn(0, letters.size - 1)
                selectLetter(letters[index], dp)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                bubble?.postDelayed({ bubble?.visibility = View.INVISIBLE }, 600)
            }
        }
    }

    private fun selectLetter(letter: String, dp: (Int) -> Int) {
        bubble?.text = letter
        bubble?.visibility = View.VISIBLE
        val target = sectionOffsets[letter]
        if (target != null) {
            scroll?.smoothScrollTo(0, target)
        } else {
            // 布局未完成时补记偏移后再跳
            scroll?.post {
                scroll?.smoothScrollTo(0, sectionOffsets[letter] ?: 0)
            }
        }
        // 导航条字母随选中高亮
        navBar?.let { bar ->
            for (i in 0 until bar.childCount) {
                (bar.getChildAt(i) as? TextView)?.setTextColor(
                    if (letters.getOrNull(i) == letter) 0xFF7EE3A0.toInt() else 0xFF5A6B7A.toInt(),
                )
            }
        }
    }

    override fun onBackPressed() = Unit // 桌面不退出
}
