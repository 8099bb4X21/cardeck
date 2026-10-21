package io.github.shaonialife.cardeck.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.shaonialife.cardeck.util.DebugFileLog

/**
 * PackageInstaller 结果接收：STATUS_PENDING_USER_ACTION 时把系统确认 Intent
 * 拉到手机主屏（此时会话已停、虚拟屏已释放，弹窗不会再落到车机侧）。
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }

            PackageInstaller.STATUS_SUCCESS ->
                DebugFileLog.append(context, "BRIDGE: 安装成功")

            else ->
                DebugFileLog.append(
                    context,
                    "BRIDGE: 安装未完成 status=$status msg=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
                )
        }
    }
}
