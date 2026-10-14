package com.lizhihua.carplaysender.launcher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizhihua.carplaysender.R
import com.lizhihua.carplaysender.launcher.AppIcons
import com.lizhihua.carplaysender.launcher.DefaultMapPrefs
import com.lizhihua.carplaysender.launcher.DesktopApps
import com.lizhihua.carplaysender.launcher.SupersamplePrefs
import com.lizhihua.carplaysender.launcher.VirtualLauncher
import com.lizhihua.carplaysender.shizuku.Shell

/**
 * 车机设置内容面板（Fragment 化）：车机 SettingsActivity 双栏右栏、
 * 手机侧二级菜单页共用同一套面板实例，逻辑零拷贝。
 */
class DesktopPanelFragment : SettingsPanelFragment() {
    private var countText: TextView? = null
    private var selected: MutableSet<String> = mutableSetOf()
    private lateinit var adapter: DesktopAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_desktop, container, false)

    override fun onViewCreated(view: View) {
        countText = view.findViewById(R.id.settingsCount)
        val list = view.findViewById<RecyclerView>(R.id.appList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = DesktopAdapter()
        list.adapter = adapter
        SettingsRows.setupIndexBar(this, view.findViewById(R.id.appListIndex), list) { adapter.rows }
        val loading = view.findViewById<TextView>(R.id.listLoading)
        loadApps {
            loading.visibility = View.GONE
            selected.clear()
            selected += DesktopApps.selected(requireContext(), apps)
            updateCount()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateCount() {
        countText?.text = "已选 ${selected.size} 个"
    }

    private fun toggle(
        entry: VirtualLauncher.AppEntry,
        checked: Boolean,
    ) {
        val key = entry.component.flattenToString()
        if (checked) selected.add(key) else selected.remove(key)
        DesktopApps.save(requireContext(), selected)
        updateCount()
        hostToast(if (checked) "「${entry.label}」已显示到桌面" else "「${entry.label}」已从桌面移除")
        adapter.notifyDataSetChanged()
    }

    private inner class DesktopAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val rows: List<SettingsRow> get() = SettingsRows.build(requireContext(), apps)

        override fun getItemViewType(position: Int): Int = if (rows[position] is SettingsRow.Header) 0 else 1

        override fun getItemId(position: Int): Long = rows[position].id

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                SettingsRows.HeaderHolder(SettingsRows.inflateHeader(this@DesktopPanelFragment, parent))
            } else {
                PickerHolder(layoutInflater.inflate(R.layout.item_app_pick, parent, false))
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (val row = rows[position]) {
                is SettingsRow.Header -> (holder as SettingsRows.HeaderHolder).bind(row.title)
                is SettingsRow.App -> (holder as PickerHolder).bind(row.entry)
            }
        }
    }

    private inner class PickerHolder(
        private val row: View,
    ) : RecyclerView.ViewHolder(row) {
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
}

class SupersamplePanelFragment : SettingsPanelFragment() {
    private lateinit var adapter: SupersampleAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_supersample, container, false)

    override fun onViewCreated(view: View) {
        val list = view.findViewById<RecyclerView>(R.id.ssList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = SupersampleAdapter()
        list.adapter = adapter
        SettingsRows.setupIndexBar(this, view.findViewById(R.id.ssListIndex), list) { adapter.rows }
        val loading = view.findViewById<TextView>(R.id.listLoading)
        loadApps {
            loading.visibility = View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    private inner class SupersampleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val rows: List<SettingsRow> get() = SettingsRows.build(requireContext(), apps)

        override fun getItemViewType(position: Int): Int = if (rows[position] is SettingsRow.Header) 0 else 1

        override fun getItemId(position: Int): Long = rows[position].id

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                SettingsRows.HeaderHolder(SettingsRows.inflateHeader(this@SupersamplePanelFragment, parent))
            } else {
                SsHolder(layoutInflater.inflate(R.layout.item_app_ss, parent, false))
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (val row = rows[position]) {
                is SettingsRow.Header -> (holder as SettingsRows.HeaderHolder).bind(row.title)
                is SettingsRow.App -> (holder as SsHolder).bind(row.entry)
            }
        }
    }

    private inner class SsHolder(
        private val row: View,
    ) : RecyclerView.ViewHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.ssIcon)
        private val name: TextView = row.findViewById(R.id.ssName)
        private val segAuto: TextView = row.findViewById(R.id.ssAuto)
        private val segOn: TextView = row.findViewById(R.id.ssOn)
        private val segOff: TextView = row.findViewById(R.id.ssOff)

        fun bind(entry: VirtualLauncher.AppEntry) {
            val iconPx = (28 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            refresh(SupersamplePrefs.mode(requireContext(), entry.component))

            fun pick(
                mode: Int,
                label: String,
            ) {
                SupersamplePrefs.setMode(requireContext(), entry.component, mode)
                refresh(mode)
                hostToast("「${entry.label}」超采样：$label（下次启动生效）")
            }
            segAuto.setOnClickListener { pick(SupersamplePrefs.MODE_AUTO, "自动") }
            segOn.setOnClickListener { pick(SupersamplePrefs.MODE_ON, "开启") }
            segOff.setOnClickListener { pick(SupersamplePrefs.MODE_OFF, "关闭") }
        }

        private fun refresh(mode: Int) {
            val segs =
                listOf(
                    segAuto to SupersamplePrefs.MODE_AUTO,
                    segOn to SupersamplePrefs.MODE_ON,
                    segOff to SupersamplePrefs.MODE_OFF,
                )
            segs.forEach { (view, segMode) ->
                val active = segMode == mode
                view.background = if (active) requireActivity().getDrawable(R.drawable.bg_segment_sel) else null
                view.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt())
            }
        }
    }
}

class OrientationPanelFragment : SettingsPanelFragment() {
    private lateinit var adapter: OrientationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_orientation, container, false)

    override fun onViewCreated(view: View) {
        val list = view.findViewById<RecyclerView>(R.id.oriList)
        list.layoutManager = LinearLayoutManager(requireContext())
        adapter = OrientationAdapter()
        list.adapter = adapter
        SettingsRows.setupIndexBar(this, view.findViewById(R.id.oriListIndex), list) { adapter.rows }
        val loading = view.findViewById<TextView>(R.id.listLoading)
        loadApps {
            loading.visibility = View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    private inner class OrientationAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val rows: List<SettingsRow> get() = SettingsRows.build(requireContext(), apps)

        override fun getItemViewType(position: Int): Int = if (rows[position] is SettingsRow.Header) 0 else 1

        override fun getItemId(position: Int): Long = rows[position].id

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                SettingsRows.HeaderHolder(SettingsRows.inflateHeader(this@OrientationPanelFragment, parent))
            } else {
                OriHolder(layoutInflater.inflate(R.layout.item_orientation_car, parent, false))
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (val row = rows[position]) {
                is SettingsRow.Header -> (holder as SettingsRows.HeaderHolder).bind(row.title)
                is SettingsRow.App -> (holder as OriHolder).bind(row.entry)
            }
        }
    }

    private inner class OriHolder(
        private val row: View,
    ) : RecyclerView.ViewHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.oriIcon)
        private val name: TextView = row.findViewById(R.id.oriName)
        private val check: ImageView = row.findViewById(R.id.oriCheck)
        private lateinit var entry: VirtualLauncher.AppEntry
        private var shown = false

        fun bind(entry: VirtualLauncher.AppEntry) {
            this.entry = entry
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            icon.setImageBitmap(AppIcons.rounded(entry.icon, iconPx))
            name.text = entry.label
            // 界面勾选状态即事实源：点击 = 取反当前显示状态（不重新读偏好，
            // 避免读值失真导致方向算反——实测「取消」会变成再次「启用」）
            shown = AppOrientationCompat.isEnabled(requireContext(), pkg())
            refresh(shown)

            row.setOnClickListener {
                shown = !shown
                if (!AppOrientationCompat.setEnabled(requireContext(), pkg(), shown)) {
                    shown = !shown
                    refresh(shown)
                    hostToast("写入失败（Shizuku 异常）")
                    return@setOnClickListener
                }
                refresh(shown)
                hostToast("「${entry.label}」横屏：${if (shown) "开" else "关"}")
                askKillIfAlive(entry)
            }
        }

        private fun pkg() = entry.component.packageName

        private fun refresh(enabled: Boolean) {
            check.setImageResource(
                if (enabled) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
        }
    }

    private fun askKillIfAlive(entry: VirtualLauncher.AppEntry) {
        val pkg = entry.component.packageName
        val pid = Shell.exec("pidof $pkg", timeoutMs = 4000).output.trim()
        if (pid.isEmpty()) return
        androidx.appcompat.app.AlertDialog
            .Builder(requireContext())
            .setTitle("应用正在运行")
            .setMessage("「${entry.label}」正在运行，显示设置的更改需要重启应用后才能生效。是否立即结束它？")
            .setPositiveButton("结束应用") { _, _ ->
                Shell.exec("am force-stop $pkg", timeoutMs = 4000)
                hostToast("已结束「${entry.label}」，重新打开即按新设置显示")
            }.setNegativeButton("稍后手动重启", null)
            .show()
    }
}

class DockPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_dock, container, false)

    override fun onViewCreated(view: View) {
        val scrimSeek = view.findViewById<SeekBar>(R.id.dockScrimSeek)
        val scrimValue = view.findViewById<TextView>(R.id.dockScrimValue)
        val blurSeek = view.findViewById<SeekBar>(R.id.dockBlurSeek)
        val blurValue = view.findViewById<TextView>(R.id.dockBlurValue)

        scrimSeek.progress =
            com.lizhihua.carplaysender.launcher.DockPrefs
                .scrimOpacity(requireContext())
        scrimValue.text = "${scrimSeek.progress}%"
        scrimSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scrimValue.text = "$value%"
                    com.lizhihua.carplaysender.launcher.DockPrefs
                        .setScrimOpacity(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )

        blurSeek.progress =
            com.lizhihua.carplaysender.launcher.DockPrefs
                .blur(requireContext())
        blurValue.text = "${blurSeek.progress}%"
        blurSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    blurValue.text = "$value%"
                    com.lizhihua.carplaysender.launcher.DockPrefs
                        .setBlur(requireContext(), value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
    }
}

class DefaultMapPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_map, container, false)

    override fun onViewCreated(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.mapOptions)
        val installed =
            VirtualLauncher.MAP_PACKAGES_PRIORITY.mapNotNull { pkg ->
                val launch =
                    requireContext().packageManager.getLaunchIntentForPackage(pkg)
                        ?: return@mapNotNull null
                val label =
                    runCatching {
                        requireContext()
                            .packageManager
                            .getApplicationInfo(pkg, 0)
                            .loadLabel(requireContext().packageManager)
                            .toString()
                    }.getOrDefault(pkg)
                pkg to label
            }
        if (installed.isEmpty()) {
            container.addView(
                TextView(requireContext()).apply {
                    text = "未安装任何受支持的地图应用（高德/百度/腾讯）"
                    setTextColor(0xFF8E8E93.toInt())
                    textSize = 13f
                },
            )
            return
        }
        var current =
            DefaultMapPrefs.manualPackage(requireContext())
                ?: installed.firstOrNull()?.first
        installed.forEach { (pkg, label) ->
            val row = layoutInflater.inflate(R.layout.item_map_option, container, false)
            val iconPx = (34 * resources.displayMetrics.density).toInt()
            val appIcon =
                runCatching {
                    requireContext().packageManager.getApplicationIcon(pkg)
                }.getOrNull()
            if (appIcon != null) {
                row
                    .findViewById<ImageView>(R.id.mapOptionIcon)
                    .setImageBitmap(AppIcons.rounded(appIcon, iconPx))
            }
            row.findViewById<TextView>(R.id.mapOptionName).text = label
            val radio = row.findViewById<ImageView>(R.id.mapOptionRadio)

            fun refresh() {
                radio.setImageResource(
                    if (pkg == current) R.drawable.bg_radio_on else R.drawable.bg_radio_off,
                )
            }
            refresh()
            row.setOnClickListener {
                current = pkg
                DefaultMapPrefs.set(requireContext(), pkg)
                for (i in 0 until container.childCount) {
                    (container.getChildAt(i).tag as? (() -> Unit))?.invoke()
                }
                hostToast("默认地图：$label")
            }
            row.tag = { refresh() }
            container.addView(row)
        }
    }
}

/**
 * 「显示」面板：流分辨率缩放（30%–160%，下次连接起效）+ 目标帧率（30/60，
 * 运行中实时切换）。英雄卡展示的是会话运行时生效值（SessionController 快照），
 * 与本页偏好值相区别。
 */
class DisplayPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_display, container, false)

    override fun onViewCreated(view: View) {
        val ctx = requireContext()
        val scaleSeek = view.findViewById<SeekBar>(R.id.displayScaleSeek)
        val scaleValue = view.findViewById<TextView>(R.id.displayScaleValue)
        val scaleNote = view.findViewById<TextView>(R.id.displayScaleNote)
        val fps30 = view.findViewById<TextView>(R.id.btnFps30)
        val fps60 = view.findViewById<TextView>(R.id.btnFps60)

        // 滑条 progress（0..26）→ 百分比（30..160，步进 5）
        fun progressOf(percent: Int) = (percent - DisplayPrefs.SCALE_MIN) / DisplayPrefs.SCALE_STEP

        fun percentOf(progress: Int) = DisplayPrefs.SCALE_MIN + progress * DisplayPrefs.SCALE_STEP

        val saved = DisplayPrefs.scalePercent(ctx)
        scaleSeek.progress = progressOf(saved)
        scaleValue.text = "$saved%"

        fun refreshScaleNote() {
            val runningNow =
                com.lizhihua.carplaysender.SessionController.videoSender
                    ?.isRunning == true
            scaleNote.text =
                if (runningNow) {
                    "保存后下次连接起效；当前连接按 " +
                        "${com.lizhihua.carplaysender.SessionController.activeScalePercent}% 运行"
                } else {
                    "保存后下次连接起效"
                }
        }
        refreshScaleNote()

        scaleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    scaleValue.text = "${percentOf(progress)}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent = percentOf(scaleSeek.progress)
                    DisplayPrefs.setScalePercent(ctx, percent)
                    hostToast("分辨率 $percent% 已保存，下次连接起效")
                    refreshScaleNote()
                }
            },
        )

        fun refreshFps(active30: Boolean) {
            // 未选中片用暗色底（与容器同色）而非 null 背景，避免背景重置内边距
            fps30.setBackgroundResource(
                if (active30) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            fps30.setTextColor(
                if (active30) 0xFFFFFFFF.toInt() else 0xFF8E8E93.toInt(),
            )
            fps60.setBackgroundResource(
                if (active30) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            fps60.setTextColor(
                if (active30) 0xFF8E8E93.toInt() else 0xFFFFFFFF.toInt(),
            )
        }

        val savedFps = DisplayPrefs.fps(ctx)
        refreshFps(savedFps < 60)

        fun applyFps(fps: Int) {
            DisplayPrefs.setFps(ctx, fps)
            refreshFps(fps < 60)
            com.lizhihua.carplaysender.launcher.VirtualLauncher
                .setStreamFps(fps)
            val live =
                com.lizhihua.carplaysender.SessionController.videoSender
                    ?.isRunning == true
            hostToast("帧率已切换为 ${fps}fps${if (live) "（实时生效）" else ""}")
            if (live) com.lizhihua.carplaysender.SessionController.activeTargetFps = fps
        }
        fps30.setOnClickListener { applyFps(30) }
        fps60.setOnClickListener { applyFps(60) }
    }
}
