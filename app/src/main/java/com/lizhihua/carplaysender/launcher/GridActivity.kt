package com.lizhihua.carplaysender.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.lizhihua.carplaysender.R

/**
 * CarPlay 主区桌面：纯黑背景 + 4×3 图标分页网格 + 底部页点。
 * 布局声明在 res/layout（activity_grid / item_grid_page / item_app_cell），
 * 代码只做动态格子尺寸计算与数据绑定。无字母导航（对照 CarPlay 主屏）。
 *
 * 默认只显示地图类应用（DesktopApps 默认选择），第一格固定「车机设置」入口；
 * 用户可在设置页勾选要显示的应用，返回桌面（onResume）即时刷新。
 */
class GridActivity : AppCompatActivity() {
    /** 桌面格子：仪表盘页（第 0 页）/ 固定设置入口 / 普通应用。 */
    private sealed interface Cell {
        object Dashboard : Cell

        object Pinned : Cell

        data class App(
            val entry: VirtualLauncher.AppEntry,
        ) : Cell
    }

    private var pages: List<List<Cell>> = emptyList()
    private var pager: ViewPager2? = null
    private var dotsRow: LinearLayout? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0
    private var iconSizePx = 0

    /** 应用清单缓存（每次 resume 重扫 packageManager 会拖慢回桌面——仅在首次载入时扫描）。 */
    private var appsCache: List<VirtualLauncher.AppEntry>? = null

    /** 壁纸背景层（对齐全流画布的切片；版本变化时后台换图）。 */
    private var wallpaperView: ImageView? = null
    private var wallpaperShownVersion = -1L

    /** 壁纸变更监听（WallpaperStore 主线程回调；解码在本类自行切后台线程）。 */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    /** 仪表盘模式面板（分屏时显示在应用区右半；左半由地图 tile 覆盖）。 */
    private var dashboardView: android.view.View? = null

    /** 仪表盘模式变化（VirtualLauncher 通知）。 */
    private val dashboardListener = { dashboard: Boolean ->
        runOnUiThread { applyDashboardMode(dashboard) }
    }

    /** 媒体状态监听（MediaSessionsState 主线程回调）。 */
    private val mediaStateListener = {
        runOnUiThread { renderMediaCard() }
    }

    /** 全画布位图就绪（WallpaperStore 首次构建/重建后回调）。 */
    private val canvasReadyListener = { _: Long -> runOnUiThread { refreshDashWallpaper() } }

    /** 卡片视觉（暗幕透明度/模糊）——与 dock 一致（DockPrefs）。 */
    private val dockPrefsListener = {
        runOnUiThread {
            applyDashCardChrome()
            refreshDashWallpaper()
        }
    }

    /**
     * 应用卡片视觉：暗幕 alpha 与壁纸模糊半径（RenderEffect，半径随网格屏
     * 密度放大 1.5 倍以与 dock 视觉一致）。
     */
    private fun applyDashCardChrome() {
        val panel = dashboardView ?: return
        val scrim = panel.findViewById<View>(R.id.dashScrim)
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        scrim.background =
            android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(alpha, 0, 0, 0),
            )
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper)
        val percent = DockPrefs.blur(this)
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * resources.displayMetrics.density
            wallpaper.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    /**
     * 音乐卡壁纸：全画布切片中「音乐卡区域」的裁剪（与全屏壁纸同映射，
     * 区域 = 地图卡右侧至卡片右边距）。
     */
    private fun refreshDashWallpaper() {
        val panel = dashboardView ?: return
        val wallpaper = panel.findViewById<ImageView>(R.id.dashWallpaper) ?: return
        val params = wallpaper.layoutParams ?: return
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        Thread({
            val bitmap =
                runCatching {
                    com.lizhihua.carplaysender.ui.WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        canvasWidth,
                        canvasHeight,
                    )
                }.getOrNull() ?: return@Thread
            val margin = (CARD_MARGIN_PX * scale).toInt()
            // 音乐卡左缘 = 应用区中线 + 半边距（8）：与地图 tile 右缘（中线-8）之间
            // 形成 16px 中缝（两侧各让一半）。显式按中线算，勿引用 mapTileWidth——
            // 其语义是 tile 实际宽度（含边距扣减），会随 tile 布局变化
            val cardX =
                ((VirtualLauncher.dockWidthPx + VirtualLauncher.appAreaWidth / 2 + CARD_MARGIN_PX / 2) * scale)
                    .toInt()
            val cropX = cardX.coerceAtMost(bitmap.width - 2)
            val cropY = margin.coerceAtMost(bitmap.height - 2)
            val cropW = (bitmap.width - cropX - margin).coerceAtLeast(2)
            val cropH = (bitmap.height - cropY * 2).coerceAtLeast(2)
            runOnUiThread {
                wallpaper.setImageBitmap(
                    Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH),
                )
                // 位图就绪后重申 RenderEffect（视图特效在位图更新后仍需生效）
                applyDashCardChrome()
            }
        }, "wallpaper-dash").start()
    }

    /** 音乐卡渲染：标题/歌手/播放暂停按钮（播放中=暂停图标，暂停=播放图标）。 */
    private fun renderMediaCard() {
        val panel = dashboardView ?: return
        val now = com.lizhihua.carplaysender.media.MediaSessionsState.nowPlaying
        panel.findViewById<TextView>(R.id.dashMediaTitle).text =
            now?.title?.takeIf { it.isNotBlank() } ?: "未在播放"
        panel.findViewById<TextView>(R.id.dashMediaArtist).text =
            now?.artist?.takeIf { it.isNotBlank() } ?: "媒体信息未连接"
        panel.findViewById<ImageView>(R.id.dashPlayPause).setImageResource(
            if (now?.playing == true) R.drawable.ic_media_pause else R.drawable.ic_media_play,
        )
    }

    /** 仪表盘模式 → pager 位置同步（0=仪表盘，1+=桌面；已在目标位则不动）。 */
    private fun applyDashboardMode(dashboard: Boolean) {
        val pager = findViewById<ViewPager2>(R.id.gridPager)
        if (dashboard && pager.currentItem != 0) pager.setCurrentItem(0, false)
        if (!dashboard && pager.currentItem == 0) pager.setCurrentItem(1, false)
    }

    /** 音乐控制：系统媒体键（AudioManager 全局路由给活跃媒体会话，无需权限）。 */
    private fun dispatchMediaKey(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

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
        // 虚拟屏重建竞态：dm 短暂为 0 时 cell/icon 尺寸会算出 0，首帧绑定即崩
        // （Bitmap.createBitmap 0 宽高）。夹最小值保底，并在 pager 首帧布局后重算+重绑。
        if (iconSizePx <= 0) {
            iconSizePx = 48
            findViewById<ViewPager2>(R.id.gridPager).post {
                cellWidthPx = (dm.widthPixels - chromeHorizontalPx) / COLUMNS
                cellHeightPx = (dm.heightPixels - chromeVerticalPx) / ROWS
                iconSizePx =
                    (cellHeightPx * 0.63f)
                        .toInt()
                        .coerceAtMost((cellWidthPx * 0.72f).toInt())
                        .coerceAtLeast(48)
                if (cellWidthPx > 0) reload()
            }
        }

        // 壁纸背景（index 0 = 最底）：窗口必须不透明——透明窗口压在应用窗口前面时
        // 会把应用内容透出来（点 home 后桌面图标叠在头条上的根因）。
        // 切片映射：把「整个视频流画布」的 aspectFill 壁纸按 dock 位置偏移，
        // 本窗口正好露出应用区的切片，与 dock 处的切片逐像素连续（同一渲染路径）。
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth > 0 && canvasHeight > 0) {
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            wallpaperView =
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    layoutParams =
                        android.widget.FrameLayout
                            .LayoutParams(
                                canvasWidth,
                                canvasHeight,
                            ).apply {
                                if (VirtualLauncher.dockAtBottom) {
                                    // 竖屏车机：dock 在底部，桌面切片 = 画布顶部
                                    leftMargin = 0
                                    topMargin = 0
                                } else {
                                    // 横屏车机：dock 在左侧，桌面切片 = 画布右部
                                    leftMargin = -(VirtualLauncher.dockWidthPx * scale).toInt()
                                    topMargin = 0
                                }
                            }
                }
            root.addView(wallpaperView, 0)
            // 注册变更通知 + 立即对账一次（兜底注册前发生的更换——事件不补发）
            com.lizhihua.carplaysender.ui.WallpaperStore
                .addListener(wallpaperListener)
            refreshWallpaperIfChanged()

            // 仪表盘面板已改为 ViewPager2 首页（PageAdapter 绑定时创建），
            // 此处仅注册全局监听：卡片视觉/媒体状态/壁纸切片/仪表盘模式。
            DockPrefs.addListener(dockPrefsListener)
            com.lizhihua.carplaysender.media.MediaSessionsState
                .addListener(mediaStateListener)
            com.lizhihua.carplaysender.ui.WallpaperStore
                .addCanvasReadyListener(canvasReadyListener)
            VirtualLauncher.addDashboardListener(dashboardListener)
            VirtualLauncher.addMapLayoutListener { runOnUiThread { refreshDashWallpaper() } }
            applyDashboardMode(VirtualLauncher.dashboardMode)
            VirtualLauncher.addDashboardListener(dashboardListener)
            VirtualLauncher.addMapLayoutListener { runOnUiThread { refreshDashWallpaper() } }
            applyDashboardMode(VirtualLauncher.dashboardMode)
        }

        pager =
            findViewById<ViewPager2>(R.id.gridPager).apply {
                offscreenPageLimit = 2
                adapter = PageAdapter()
            }
        dotsRow = findViewById(R.id.dotsRow)
        pager?.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                    // 用户滑动切换仪表盘/桌面：回写 launcher（含地图任务与 tile 布局）
                    if (position == 0 && !VirtualLauncher.dashboardMode) VirtualLauncher.showDashboard()
                    if (position != 0 && VirtualLauncher.dashboardMode) VirtualLauncher.showDesktop()
                }

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    offsetPixels: Int,
                ) {
                    // 滑动过程中 tile 跟随手指平移（合成器采样位置随进度左移/右移）；
                    // 回滑进仪表盘时提前显示 tile（纯视觉，状态在落定 IDLE 时统一对账）
                    if (position == 0) {
                        VirtualLauncher.setTileSlide(positionOffset)
                        if (positionOffset < 1f) VirtualLauncher.showMapTile()
                    }
                }

                override fun onPageScrollStateChanged(state: Int) {
                    if (state == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE) {
                        VirtualLauncher.setTileSlide(0f)
                        // 落定对账：仪表盘模式与所在页一致（含异常中断的对账恢复）
                        VirtualLauncher.syncDashboard(pager?.currentItem == 0)
                    }
                }
            },
        )
    }

    /** 壁纸版本变化时后台重解码并换图（手机侧换壁纸后 ≤2s 桌面更新）。 */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val current = com.lizhihua.carplaysender.ui.WallpaperStore.version
        if (current == wallpaperShownVersion) return
        wallpaperShownVersion = current
        val params = view.layoutParams as? android.widget.FrameLayout.LayoutParams
        Thread({
            val bitmap =
                runCatching {
                    com.lizhihua.carplaysender.ui.WallpaperStore.aspectFillCanvas(
                        this@GridActivity,
                        params?.width ?: 0,
                        params?.height ?: 0,
                    )
                }.getOrNull()
            if (bitmap != null) {
                // 诊断：bitmap 三点采样（中线上 25%/50%/75% 宽）——区分「bitmap 内容缺失」
                // 与「视图/合成问题」（本机 logcat 受抑制，走 logSink 落文件日志）
                val samples =
                    listOf(0.25f, 0.5f, 0.75f).joinToString(",") {
                        "%06x".format(bitmap.getPixel((bitmap.width * it).toInt(), bitmap.height / 2))
                    }
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-GRID: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params?.width}x${params?.height} left=${params?.leftMargin} " +
                        "samples=[$samples]",
                )
                runOnUiThread { view.setImageBitmap(bitmap) }
            } else {
                VirtualLauncher.logSink?.invoke("WALLPAPER-GRID: 解码失败")
            }
        }, "wallpaper-grid").start()
    }

    override fun onResume() {
        super.onResume()
        // 也覆盖从设置页返回：勾选变化即时反映
        reload()
        refreshWallpaperIfChanged()
    }

    /** 重新读取应用清单与桌面选择（首帧与每次回到桌面时调用）。 */
    private fun reload() {
        val apps =
            appsCache ?: run {
                val scanned = VirtualLauncher.launchableApps(this)
                // iconCache 供 dock 运行中图标使用（每次重扫时同步刷新）
                synchronized(VirtualLauncher.runningApps) { VirtualLauncher.iconCache.clear() }
                scanned.forEach { VirtualLauncher.iconCache[it.component] = it.icon }
                appsCache = scanned
                // 纳入进程级共享缓存：桌面扫过之后，车机设置首次打开即秒开
                VirtualLauncher.adoptAppsCache(scanned)
                scanned
            }

        val selected = DesktopApps.selected(this, apps)
        val cells =
            buildList {
                add(Cell.Pinned) // 桌面第一格：车机设置（固定，不可取消）
                apps.filter { it.component.flattenToString() in selected }.forEach { add(Cell.App(it)) }
            }
        // 第 0 页 = 仪表盘，其后为桌面网格页：左右滑动即互相切换
        pages = listOf(listOf<Cell>(Cell.Dashboard)) + cells.chunked(COLUMNS * ROWS)

        val current = pager?.currentItem ?: 0
        pager?.adapter?.notifyDataSetChanged()
        rebuildDots()
        // 位置与仪表盘模式对账（home 键循环/应用启动会改 dashboardMode）：
        // 仅纠偏方向，不把停在桌面第 N 页的用户拽回第 1 页
        if (VirtualLauncher.dashboardMode && current != 0) pager?.setCurrentItem(0, false)
        if (!VirtualLauncher.dashboardMode && current == 0) pager?.setCurrentItem(1, false)
        if ((pager?.currentItem ?: 0) >= pages.size) pager?.setCurrentItem(pages.size - 1, false)
        updateDots(pager?.currentItem ?: 0)
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = pages.size

        override fun getItemViewType(position: Int): Int = if (pages.getOrNull(position)?.firstOrNull() is Cell.Dashboard) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder =
            if (viewType == 0) {
                DashboardHolder(layoutInflater.inflate(R.layout.view_dashboard, parent, false))
            } else {
                val grid = layoutInflater.inflate(R.layout.item_grid_page, parent, false) as GridLayout
                grid.columnCount = COLUMNS
                PageHolder(grid)
            }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            when (holder) {
                is DashboardHolder -> holder.bind()
                is PageHolder -> holder.bind(position)
            }
        }
    }

    /** 仪表盘页 Holder：面板随页面创建（卡片边距/圆角/媒体控制/壁纸切片）。 */
    private inner class DashboardHolder(
        val panel: View,
    ) : RecyclerView.ViewHolder(panel) {
        fun bind() {
            dashboardView = panel
            val scale = VirtualLauncher.gridScale()
            val cardMargin = (CARD_MARGIN_PX * scale).toInt()
            panel.layoutParams =
                android.view.ViewGroup
                    .MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ).apply {
                        // 左缘 = 应用区中线 + 半边距（8）：与地图 tile 右缘（中线-8）之间
                        // 形成 16px 中缝。显式按中线算，勿引用 mapTileWidth（语义已变）
                        leftMargin =
                            ((VirtualLauncher.appAreaWidth / 2 + CARD_MARGIN_PX / 2) * scale).toInt()
                        topMargin = cardMargin
                        rightMargin = cardMargin
                        bottomMargin = cardMargin
                    }
            panel.outlineProvider =
                object : android.view.ViewOutlineProvider() {
                    override fun getOutline(
                        view: View,
                        outline: android.graphics.Outline,
                    ) {
                        outline.setRoundRect(0, 0, view.width, view.height, cardMargin * 1.4f)
                    }
                }
            panel.clipToOutline = true
            panel.findViewById<ImageView>(R.id.dashPrev).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            panel.findViewById<ImageView>(R.id.dashNext).setOnClickListener {
                dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            panel.findViewById<ImageView>(R.id.dashPlayPause).setOnClickListener {
                // 按当前状态显式发播放/暂停；乐观翻转图标（状态回调可能滞后），
                // 400ms 后请求监听服务重新同步真实状态
                val state = com.lizhihua.carplaysender.media.MediaSessionsState.nowPlaying
                val playing = state?.playing == true
                dispatchMediaKey(
                    if (playing) {
                        android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    } else {
                        android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    },
                )
                com.lizhihua.carplaysender.media.MediaSessionsState.publish(
                    com.lizhihua.carplaysender.media.MediaSessionsState.NowPlaying(
                        state?.title,
                        state?.artist,
                        !playing,
                    ),
                )
                panel.postDelayed(
                    {
                        com.lizhihua.carplaysender.media.MediaSessionsState
                            .requestResync()
                    },
                    400,
                )
            }
            panel.findViewById<TextView>(R.id.dashFullscreenMap).setOnClickListener {
                Thread { VirtualLauncher.setMapFullscreen(!VirtualLauncher.mapFullscreen) }.start()
            }
            applyDashCardChrome()
            renderMediaCard()
            refreshDashWallpaper()
        }
    }

    private inner class PageHolder(
        private val grid: GridLayout,
    ) : RecyclerView.ViewHolder(grid) {
        fun bind(page: Int) {
            grid.removeAllViews()
            pages[page].forEach { cell ->
                val view = layoutInflater.inflate(R.layout.item_app_cell, grid, false)
                val icon =
                    view.findViewById<ImageView>(R.id.cellIcon).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
                    }
                val label = view.findViewById<TextView>(R.id.cellLabel)
                when (cell) {
                    is Cell.Dashboard -> Unit // 仪表盘独立成页，不进网格
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
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
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

    override fun onDestroy() {
        com.lizhihua.carplaysender.ui.WallpaperStore
            .removeListener(wallpaperListener)
        VirtualLauncher.removeDashboardListener(dashboardListener)
        com.lizhihua.carplaysender.media.MediaSessionsState
            .removeListener(mediaStateListener)
        DockPrefs.removeListener(dockPrefsListener)
        com.lizhihua.carplaysender.ui.WallpaperStore
            .removeCanvasReadyListener(canvasReadyListener)
        super.onDestroy()
    }

    override fun onBackPressed() = Unit // 桌面不退出

    private companion object {
        const val COLUMNS = 4 // CarPlay 规格：强制 4 列
        const val ROWS = 3
        const val CARD_MARGIN_PX = 16 // 仪表盘卡片边距（流像素）
    }
}
