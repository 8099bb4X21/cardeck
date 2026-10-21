package io.github.shaonialife.cardeck.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 重装闭环：服务端推送新 APK → 下载并校验 → 悬浮窗提示 → 点击后
 * **先彻底停会话释放虚拟屏**再走 PackageInstaller。
 *
 * 停会话释放屏是硬要求：安装确认弹窗会落到仍存活的虚拟屏上（车机侧），
 * 手机上没人点得到——AGENTS.md 记录过的「安装卡死」根因。
 */
object ReinstallFlow {
    private val main = Handler(Looper.getMainLooper())

    fun offer(
        context: Context?,
        url: String,
        sha256: String,
        version: String,
    ) {
        val ctx = context?.applicationContext ?: return
        if (url.isBlank()) return
        DebugFileLog.append(ctx, "BRIDGE: 收到新版本推送 v$version，开始下载 $url")
        Bg.run("bridge-apk-download") {
            val file = download(ctx, url)
            if (file == null) {
                DebugFileLog.append(ctx, "BRIDGE: APK 下载失败：$url")
                return@run
            }
            val digest = sha256Of(file)
            if (sha256.isNotBlank() && !digest.equals(sha256, ignoreCase = true)) {
                DebugFileLog.append(ctx, "BRIDGE: APK 校验失败（本地=$digest 期望=$sha256）——拒绝安装")
                file.delete()
                return@run
            }
            DebugFileLog.append(ctx, "BRIDGE: 新版本 v$version 就绪（${file.length() / 1024 / 1024}MB，sha256 ✓）")
            main.post {
                BridgeOverlay.show(
                    ctx,
                    "调试新版本 v$version 已就绪",
                    buttonText = "安装",
                    onClick = { installNow(ctx, file) },
                )
            }
        }
    }

    private fun installNow(
        ctx: Context,
        file: File,
    ) {
        BridgePrefs.setResumePending(ctx, true)
        // 先停会话释放全部虚拟屏（含 UserService 持有的）：否则系统安装确认弹窗
        // 落在车机侧虚拟屏，手机上无法确认
        runCatching { SessionController.disconnect(ctx) }
        Bg.run("bridge-install") {
            try {
                val installer = ctx.packageManager.packageInstaller
                val session = installer.openSession(0)
                session.openWrite("apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                // 有意用隐式 action 广播：InstallReceiver 在本包内（exported=false）
                val intent =
                    Intent("io.github.shaonialife.cardeck.BRIDGE_INSTALL_RESULT")
                        .setPackage(ctx.packageName)
                val sender =
                    PendingIntent.getBroadcast(
                        ctx,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(sender.intentSender)
                session.close()
                DebugFileLog.append(ctx, "BRIDGE: 已发起安装（等待系统确认）")
            } catch (error: Exception) {
                DebugFileLog.append(ctx, "BRIDGE: 安装发起失败 - ${error.message}")
            }
        }
    }

    private fun download(
        ctx: Context,
        url: String,
    ): File? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            val target = File(ctx.cacheDir, "bridge-update.apk")
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        } catch (error: Exception) {
            DebugFileLog.append(ctx, "BRIDGE: 下载异常 - ${error.message}")
            null
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
