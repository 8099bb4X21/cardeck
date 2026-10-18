package io.github.shaonialife.cardeck.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局调试文件日志（files/main_debug.log）。
 *
 * 荣耀机型 logcat 被 HKS 加密，本文件是 adb 侧唯一调试通道（run-as 读取）。
 * MainActivity 与 CarPlayService（投影生命周期诊断）共用同一把锁，避免同文件并发写交叉。
 */
object DebugFileLog {
    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(
        context: Context,
        message: String,
    ) {
        try {
            synchronized(lock) {
                val file = File(context.filesDir, "main_debug.log")
                if (file.length() > 2_000_000) file.delete()
                FileWriter(file, true).use { writer ->
                    writer
                        .append('[')
                        .append(timestamp.format(Date()))
                        .append("] ")
                        .append(message)
                        .append('\n')
                }
            }
        } catch (_: Throwable) {
        }
    }
}
