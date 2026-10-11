package com.lizhihua.carplaysender.launcher

import com.lizhihua.carplaysender.R

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 车机设置（双栏：左菜单 / 右内容；对照 design/launcher-proto.html）。
 *
 * 目前唯一菜单「管理桌面应用」：勾选的应用显示在桌面，勾选状态即时持久化
 * （DesktopApps）；「车机设置」入口固定桌面第一格、不可取消（不在本列表内）。
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

        val list = findViewById<RecyclerView>(R.id.appList)
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = AppPickerAdapter()
        updateCount()
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

    private inner class AppPickerAdapter : RecyclerView.Adapter<PickerHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickerHolder =
            PickerHolder(layoutInflater.inflate(R.layout.item_app_pick, parent, false))

        override fun getItemCount(): Int = apps.size

        override fun onBindViewHolder(holder: PickerHolder, position: Int) =
            holder.bind(apps[position])
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
}
