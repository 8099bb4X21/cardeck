package com.lizhihua.carplaysender.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface

/**
 * 车机画面来源抽象：编码器输入 Surface 由各模式的源填充。
 * - DesktopSource：CarDisplayRenderer（自绘桌面，Presentation 私有虚拟屏）
 * - MirrorScreenSource：MediaProjection 镜像手机主屏（AUTO_MIRROR）
 * - HiddenDisplaySource：公共虚拟屏 + Shizuku 把应用启动进去（隐形屏模式）
 */
interface ScreenSource {
    val name: String

    /** 绑定（或重绑）编码 Surface；返回是否成功 */
    fun attach(surface: Surface, width: Int, height: Int, densityDpi: Int): Boolean

    /** 解绑 Surface（保留底层资源以便快速重连） */
    fun detach()

    /** 释放全部资源 */
    fun release()
}

/** 镜像手机主屏：MediaProjection 直接合成到编码 Surface（零拷贝）。要求保持亮屏解锁。 */
class MirrorScreenSource(private val projection: MediaProjection) : ScreenSource {

    override val name: String = "镜像主屏"

    private var display: VirtualDisplay? = null

    override fun attach(surface: Surface, width: Int, height: Int, densityDpi: Int): Boolean {
        detach()
        display = projection.createVirtualDisplay(
            "carplaysender-mirror",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            surface,
            null,
            null,
        )
        return display != null
    }

    override fun detach() {
        display?.release()
        display = null
    }

    override fun release() = detach()
}
