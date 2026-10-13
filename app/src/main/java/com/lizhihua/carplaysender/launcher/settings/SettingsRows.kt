package com.lizhihua.carplaysender.launcher.settings

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizhihua.carplaysender.R
import com.lizhihua.carplaysender.launcher.DesktopApps
import com.lizhihua.carplaysender.launcher.VirtualLauncher

/** 设置列表行：分组标题 / 拼音字母标题 / 应用（三个应用列表共用）。 */
sealed class SettingsRow {
    abstract val id: Long

    data class Header(val title: String, val key: String) : SettingsRow() {
        override val id: Long get() = key.hashCode().toLong()
    }

    data class App(val entry: VirtualLauncher.AppEntry) : SettingsRow() {
        override val id: Long get() = entry.component.hashCode().toLong()
    }
}

/**
 * 行构建与字母导航条（管理桌面 / 应用超采样 / 应用横屏共用）：
 * 两级分组——先按桌面勾选（「在桌面的应用」→「不在桌面的应用」），
 * 组内按拼音首字母分节（A/B/…/#，数字应用归 #）。字母导航条在两大组内各自跳转。
 */
object SettingsRows {

    fun build(context: Context, apps: List<VirtualLauncher.AppEntry>): List<SettingsRow> {
        val selected = DesktopApps.selected(context, apps)
        val onDesktop = apps.filter { it.component.flattenToString() in selected }
        val offDesktop = apps.filter { it.component.flattenToString() !in selected }
        val rows = ArrayList<SettingsRow>(apps.size * 2)
        if (onDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("在桌面的应用（${onDesktop.size}）", "on")
            rows += letterRows(onDesktop)
        }
        if (offDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("不在桌面的应用（${offDesktop.size}）", "off")
            rows += letterRows(offDesktop)
        }
        return rows
    }

    private fun letterRows(group: List<VirtualLauncher.AppEntry>): List<SettingsRow> {
        val byLetter = LinkedHashMap<String, MutableList<VirtualLauncher.AppEntry>>()
        group.forEach { entry ->
            byLetter.getOrPut(VirtualLauncher.indexLetterOf(entry.label)) { mutableListOf() }
                .add(entry)
        }
        val rows = ArrayList<SettingsRow>(group.size + byLetter.size)
        byLetter.forEach { (letter, list) ->
            rows += SettingsRow.Header(letter, "letter_$letter")
            rows += list.map { SettingsRow.App(it) }
        }
        return rows
    }

    /** 右侧字母导航条：行集合变化时由 AdapterDataObserver 驱动重建。 */
    fun setupIndexBar(
        fragment: androidx.fragment.app.Fragment,
        bar: LinearLayout,
        list: RecyclerView,
        rowsProvider: () -> List<SettingsRow>,
    ) {
        val density = fragment.resources.displayMetrics.density
        fun rebuild() {
            bar.removeAllViews()
            val letters = LinkedHashSet<String>()
            rowsProvider().forEach { row ->
                if (row is SettingsRow.Header && row.key.startsWith("letter_")) {
                    letters += row.key.removePrefix("letter_")
                }
            }
            letters.forEach { letter ->
                bar.addView(
                    TextView(fragment.requireContext()).apply {
                        text = letter
                        textSize = 9f
                        setTextColor(0xFF8E8E93.toInt())
                        setPadding(0, (1.5f * density).toInt(), 0, (1.5f * density).toInt())
                        setOnClickListener {
                            val target = rowsProvider()
                                .indexOfFirst { it is SettingsRow.Header && it.key == "letter_$letter" }
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
        list.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = rebuild()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = rebuild()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = rebuild()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = rebuild()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = rebuild()
        })
    }

    /** 分组标题行复用 ViewHolder。 */
    class HeaderHolder(val title: TextView) : RecyclerView.ViewHolder(title) {
        fun bind(text: String) {
            title.text = text
        }
    }

    fun inflateHeader(fragment: androidx.fragment.app.Fragment, parent: ViewGroup): TextView =
        fragment.layoutInflater.inflate(R.layout.item_section_header, parent, false) as TextView
}
