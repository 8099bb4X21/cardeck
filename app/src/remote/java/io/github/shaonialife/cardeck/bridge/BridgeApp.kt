package io.github.shaonialife.cardeck.bridge

import io.github.shaonialife.cardeck.SenderApp
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * remote 变体的 Application（manifest 覆盖 android:name）。
 *
 * 在进程最早入口安装日志 tap 与调试桥；standard 变体不含本文件，一切无从谈起。
 */
class BridgeApp : SenderApp() {
    override fun onCreate() {
        super.onCreate()
        DebugFileLog.append(this, "BRIDGE: 远程调试变体启动（${io.github.shaonialife.cardeck.BuildConfig.VERSION_NAME}）")

        // 重装后的续接：上次安装前置位 → 询问是否继续进入调试模式
        if (BridgePrefs.resumePending(this)) {
            DebugFileLog.append(this, "BRIDGE: 检测到重装前标记——询问是否继续调试")
            val ctx = this
            BridgeOverlay.show(
                ctx,
                "继续进入调试模式？",
                buttonText = "继续",
                onClick = {
                    BridgePrefs.setResumePending(ctx, false)
                    Bg.run("bridge-start") { BridgeClient.start(ctx) }
                },
            )
        } else if (BridgePrefs.autoConnect(this) && BridgePrefs.serverUrl(this).isNotBlank()) {
            Bg.run("bridge-start") { BridgeClient.start(this) }
        }
    }
}
