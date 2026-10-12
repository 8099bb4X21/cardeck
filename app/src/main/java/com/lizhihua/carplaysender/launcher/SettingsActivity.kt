package com.lizhihua.carplaysender.launcher

import com.lizhihua.carplaysender.R

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 车机设置（双栏：左菜单 / 右内容；对照 design/launcher-proto.html）。
 *
 * 菜单：
 *  - 「管理桌面应用」：勾选的应用显示在桌面，勾选状态即时持久化（DesktopApps）；
 *    「车机设置」入口固定桌面第一格、不可取消（不在本列表内）。
 *  - 「应用超采样」：每应用三态（自动/开启/关闭，SupersamplePrefs）；自动 =
 *    检测为竖屏应用时开启。保存后下次启动该应用生效（应用屏路由在启动时决定）。
 */
class SettingsActivity : AppCompatActivity() {

    private var apps: List<VirtualLauncher.AppEntry> = emptyList()
    private val selected = mutableSetOf<String>()
    private var countText: TextView? = null
    private var toastView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        apps = VirtualLauncher.launchableApps(this)
        selected += DesktopApps.selected(this, apps)

        countText = findViewById(R.id.settingsCount)
        toastView = findViewById(R.id.settingsToast)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { close() }

        // 布局按横屏车机（超采样屏，301dpi）标定；竖屏车机（未实测形态，
        // dockAtBottom、无超采样、420dpi）下 210dp 会超过屏宽，按比例收敛菜单栏
        if (VirtualLauncher.dockAtBottom) {
            findViewById<android.view.View>(R.id.settingsMenu)?.let { menu ->
                (menu.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { params ->
                    params.width = (resources.displayMetrics.widthPixels * 0.36f).toInt()
                    params.weight = 0f
                    menu.layoutParams = params
                }
            }
        }

        val desktopList = findViewById<RecyclerView>(R.id.appList)
        desktopList.layoutManager = LinearLayoutManager(this)
        desktopList.adapter = AppPickerAdapter()
        setupIndexBar(findViewById(R.id.appListIndex), desktopList) { (desktopList.adapter as AppPickerAdapter).rows }

        val ssList = findViewById<RecyclerView>(R.id.ssList)
        ssList.layoutManager = LinearLayoutManager(this)
        ssList.adapter = SupersampleAdapter()
        setupIndexBar(findViewById(R.id.ssListIndex), ssList) { (ssList.adapter as SupersampleAdapter).rows }

        setupMenuSwitching()
        updateCount()
    }

    /** 左菜单切换：选中态样式 + 右栏内容显隐。 */
    private fun setupMenuSwitching() {
        val menuDesktop = findViewById<LinearLayout>(R.id.menuDesktopApps)
        val menuSs = findViewById<LinearLayout>(R.id.menuSupersample)
        val contentDesktop = findViewById<View>(R.id.contentDesktopApps)
        val contentSs = findViewById<View>(R.id.contentSupersample)

        fun apply(desktop: Boolean) {
            menuDesktop.background = if (desktop) {
                getDrawable(R.drawable.bg_menu_item)
            } else {
                android.util.TypedValue().let {
                    val out = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                    getDrawable(out.resourceId)
                }
            }
            menuSs.background = if (!desktop) {
                getDrawable(R.drawable.bg_menu_item)
            } else {
                android.util.TypedValue().let {
                    val out = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                    getDrawable(out.resourceId)
                }
            }
            setMenuTextStyle(menuDesktop, desktop)
            setMenuTextStyle(menuSs, !desktop)
            contentDesktop.visibility = if (desktop) View.VISIBLE else View.GONE
            contentSs.visibility = if (!desktop) View.VISIBLE else View.GONE
        }

        menuDesktop.setOnClickListener { apply(true) }
        menuSs.setOnClickListener { apply(false) }
        apply(true)
    }

    /** 菜单项内文字样式（选中=亮色粗体；未选中=暗色常规）。 */
    private fun setMenuTextStyle(menu: LinearLayout, active: Boolean) {
        for (i in 0 until menu.childCount) {
            val child = menu.getChildAt(i)
            if (child is TextView) {
                child.setTextColor(if (active) 0xFFF5F5F7.toInt() else 0xFF8E8E93.toInt())
                child.paint.isFakeBoldText = active
            }
        }
    }

    private fun close() {
        finish()
    }

    override fun onBackPressed() = close()

    private fun updateCount() {
        countText?.text = "已选 ${selected.size} 个"
    }

    private fun toggle(entry: VirtualLauncher.AppEntry, checked: Boolean) {
        val key = entry.component.flattenToString()
        if (checked) selected.add(key) else selected.remove(key)
        DesktopApps.save(this, selected)
        updateCount()
        showToast(if (checked) "「${entry.label}」已显示到桌面" else "「${entry.label}」已从桌面移除")
        // 勾选变化会改变分组归属：两个列表都重排（勾选的应用移入「在桌面」组）
        findViewById<RecyclerView>(R.id.appList).adapter?.notifyDataSetChanged()
        findViewById<RecyclerView>(R.id.ssList).adapter?.notifyDataSetChanged()
    }

    /** 页内轻提示（不用系统 Toast：虚拟屏上显示位置不可控）。 */
    private fun showToast(text: String) {
        val view = toastView ?: return
        view.text = text
        view.animate().cancel()
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(160).withEndAction {
            view.postDelayed({ view.animate().alpha(0f).setDuration(220).start() }, 1_250)
        }.start()
    }

    // ===================== 分组行模型（两个列表共用） =====================

    /** 列表行：分组标题 / 字母标题 / 应用。 */
    private sealed class Row {
        abstract val id: Long

        data class Header(val title: String, val key: String) : Row() {
            override val id: Long get() = key.hashCode().toLong()
        }

        data class App(val entry: VirtualLauncher.AppEntry) : Row() {
            override val id: Long get() = entry.component.hashCode().toLong()
        }
    }

    /**
     * 两级分组：先按桌面勾选（「在桌面的应用」→「不在桌面的应用」），
     * 组内再按拼音首字母分节（A/B/…/#，数字应用归 #，与桌面网格同规则）。
     * 字母导航条在两大组内各自跳转（同字母取首个出现的位置）。
     */
    private fun buildRows(filter: (VirtualLauncher.AppEntry) -> Boolean = { true }): List<Row> {
        fun letterRows(group: List<VirtualLauncher.AppEntry>): List<Row> {
            val byLetter = LinkedHashMap<String, MutableList<VirtualLauncher.AppEntry>>()
            group.forEach { entry ->
                byLetter.getOrPut(VirtualLauncher.indexLetterOf(entry.label)) { mutableListOf() }
                    .add(entry)
            }
            val rows = ArrayList<Row>(group.size + byLetter.size)
            byLetter.forEach { (letter, list) ->
                rows += Row.Header(letter, "letter_$letter")
                rows += list.map { Row.App(it) }
            }
            return rows
        }

        val onDesktop = apps.filter { it.component.flattenToString() in selected && filter(it) }
        val offDesktop = apps.filter { it.component.flattenToString() !in selected && filter(it) }
        val rows = ArrayList<Row>(apps.size * 2)
        if (onDesktop.isNotEmpty()) {
            rows += Row.Header("在桌面的应用（${onDesktop.size}）", "on")
            rows += letterRows(onDesktop)
        }
        if (offDesktop.isNotEmpty()) {
            rows += Row.Header("不在桌面的应用（${offDesktop.size}）", "off")
            rows += letterRows(offDesktop)
        }
        return rows
    }

    /**
     * 右侧字母导航条：列出当前行集合里的字母标题（去重，保序），点击滚动到该字母
     * 首次出现的位置。行集合变化（勾选导致分组移动）时由 adapter notifyDataSetChanged
     * 后的下一次触摸重新读取——这里用 lambda 惰性取 rows 保证始终最新。
     */
    private fun setupIndexBar(
        bar: LinearLayout,
        list: RecyclerView,
        rowsProvider: () -> List<Row>,
    ) {
        val density = resources.displayMetrics.density
        fun rebuild() {
            bar.removeAllViews()
            val letters = LinkedHashSet<String>()
            rowsProvider().forEach { row ->
                if (row is Row.Header && row.key.startsWith("letter_")) {
                    letters += row.key.removePrefix("letter_")
                }
            }
            letters.forEach { letter ->
                bar.addView(
                    TextView(this).apply {
                        text = letter
                        textSize = 9f
                        setTextColor(0xFF8E8E93.toInt())
                        setPadding(0, (1.5f * density).toInt(), 0, (1.5f * density).toInt())
                        setOnClickListener {
                            val target = rowsProvider()
                                .indexOfFirst { it is Row.Header && it.key == "letter_$letter" }
                            if (target >= 0) {
                                (list.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(target, 0)
                            }
                        }
                    },
                )
            }
        }
        rebuild()
        // 行集合随勾选变化：列表滚动/数据变化时重建导航条（轻量：仅若干 TextView）
        var lastSignature = ""
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val signature = rowsProvider().joinToString(",") { row ->
                    if (row is Row.Header) row.key else ""
                }
                if (signature != lastSignature) {
                    lastSignature = signature
                    rebuild()
                }
            }
        })
    }

    // ===================== 内容 1：管理桌面应用 =====================

    private inner class AppPickerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val rows get() = buildRows()

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) 0 else 1

        override fun getItemId(position: Int): Long = rows[position].id

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) {
                HeaderHolder(
                    layoutInflater.inflate(R.layout.item_section_header, parent, false) as TextView,
                )
            } else {
                PickerHolder(layoutInflater.inflate(R.layout.item_app_pick, parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.title)
                is Row.App -> (holder as PickerHolder).bind(row.entry)
            }
        }
    }

    private class HeaderHolder(val title: TextView) : RecyclerView.ViewHolder(title) {
        fun bind(text: String) {
            title.text = text
        }
    }

    private inner class PickerHolder(private val row: View) : RecyclerView.ViewHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.pickIcon)
        private val name: TextView = row.findViewById(R.id.pickName)
        private val check: ImageView = row.findViewById(R.id.pickCheck)

        fun bind(entry: VirtualLauncher.AppEntry) {
            val key = entry.component.flattenToString()
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            check.setImageResource(
                if (key in selected) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            row.setOnClickListener {
                val nowChecked = key !in selected
                toggle(entry, nowChecked)
                check.setImageResource(
                    if (nowChecked) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
                )
            }
        }
    }

    // ===================== 内容 2：应用超采样 =====================

    private inner class SupersampleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val rows get() = buildRows()

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) 0 else 1

        override fun getItemId(position: Int): Long = rows[position].id

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) {
                HeaderHolder(
                    layoutInflater.inflate(R.layout.item_section_header, parent, false) as TextView,
                )
            } else {
                SsHolder(layoutInflater.inflate(R.layout.item_app_ss, parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.title)
                is Row.App -> (holder as SsHolder).bind(row.entry)
            }
        }
    }

    private inner class SsHolder(private val row: View) : RecyclerView.ViewHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.ssIcon)
        private val name: TextView = row.findViewById(R.id.ssName)
        private val segAuto: TextView = row.findViewById(R.id.ssAuto)
        private val segOn: TextView = row.findViewById(R.id.ssOn)
        private val segOff: TextView = row.findViewById(R.id.ssOff)

        fun bind(entry: VirtualLauncher.AppEntry) {
            val iconPx = (28 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            refresh(SupersamplePrefs.mode(this@SettingsActivity, entry.component))

            fun pick(mode: Int, label: String) {
                SupersamplePrefs.setMode(this@SettingsActivity, entry.component, mode)
                refresh(mode)
                showToast("「${entry.label}」超采样：$label（下次启动生效）")
            }
            segAuto.setOnClickListener { pick(SupersamplePrefs.MODE_AUTO, "自动") }
            segOn.setOnClickListener { pick(SupersamplePrefs.MODE_ON, "开启") }
            segOff.setOnClickListener { pick(SupersamplePrefs.MODE_OFF, "关闭") }
        }

        /** 三态选中样式刷新（选中=蓝色片 + 白字，未选中=透明 + 灰字）。 */
        private fun refresh(mode: Int) {
            val segs = listOf(
                segAuto to SupersamplePrefs.MODE_AUTO,
                segOn to SupersamplePrefs.MODE_ON,
                segOff to SupersamplePrefs.MODE_OFF,
            )
            segs.forEach { (view, segMode) ->
                val active = segMode == mode
                view.background = if (active) getDrawable(R.drawable.bg_segment_sel) else null
                view.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            }
        }
    }
}
