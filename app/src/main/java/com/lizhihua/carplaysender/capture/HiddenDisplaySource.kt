package com.lizhihua.carplaysender.capture

import android.content.Context
import android.view.Surface
import com.lizhihua.carplaysender.shizuku.CarInputClient
import com.lizhihua.carplaysender.shizuku.ShizukuGate

/**
 * 隐形屏模式：以普通 API 创建与车机同分辨率的 PUBLIC 虚拟屏（surface 即编码输入），
 * 经 Shizuku(shell) `am start --display` 把应用启动进去；手机主屏可正常息屏。
 *
 * 启动目标优先「高德地图车机版」（天生横屏；其引导页会丢 --display，需显式启动
 * MainMapActivity 并强制 fullscreen），未安装回退手机版高德。
 * （移植自参考项目 android-auto 的 VirtualDisplaySource）
 */
class HiddenDisplaySource(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : ScreenSource {
    override val name: String = "隐形屏"

    /** 兼容字段：双屏架构下指向应用屏 displayId。 */
    var displayId: Int = -1
        private set

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        detach()
        // 逐状态诊断：Shizuku「开着」≠ 已授权本应用（服务端授权必须弹窗点一次）
        when (val gate = ShizukuGate.state(context)) {
            ShizukuGate.State.READY -> Unit

            ShizukuGate.State.NOT_INSTALLED -> {
                log("SHIZUKU: 未安装 Shizuku App（shizuku.rikka.app）")
                return false
            }

            ShizukuGate.State.NOT_RUNNING -> {
                log("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」（手机重启后需重新启动）")
                return false
            }

            ShizukuGate.State.UNAUTHORIZED -> {
                log("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗中点「允许」后重连")
                ShizukuGate.requestPermission()
                return false
            }
        }
        CarInputClient.ensure(context)
        // 双屏启动器：GL 合成器（dock 屏 + 应用屏）→ 编码 Surface
        val ok =
            com.lizhihua.carplaysender.launcher.VirtualLauncher.attach(
                context,
                surface,
                width,
                height,
                densityDpi,
                log,
            )
        displayId =
            if (ok) com.lizhihua.carplaysender.launcher.VirtualLauncher.appDisplayId else -1
        return ok
    }

    override fun detach() {
        com.lizhihua.carplaysender.launcher.VirtualLauncher
            .detach()
        displayId = -1
    }

    override fun release() = detach()
}
