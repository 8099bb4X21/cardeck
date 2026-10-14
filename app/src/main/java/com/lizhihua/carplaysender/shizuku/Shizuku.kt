package com.lizhihua.carplaysender.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.lizhihua.carplaysender.util.Bg
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku 网关 + shell 执行 + CarInput UserService 绑定。
 * 激活（无线调试，Android 11+，免 root）：开发者选项 → 无线调试 → 配对 → Shizuku App 点启动。
 * （移植自参考项目 android-auto）
 */
object ShizukuGate {
    enum class State { NOT_INSTALLED, NOT_RUNNING, UNAUTHORIZED, READY }

    fun state(context: Context): State {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return State.NOT_INSTALLED
        }.let {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) return State.NOT_RUNNING
                if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    State.READY
                } else {
                    State.UNAUTHORIZED
                }
            } catch (_: Throwable) {
                State.NOT_RUNNING
            }
        }
    }

    fun requestPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(101)
        } catch (_: Throwable) {
        }
    }
}

/** 以 shell 身份执行命令（等同 adb shell）。Shizuku api 13 需经 IShizukuService binder 调 newProcess。 */
object Shell {
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result {
        val binder =
            rikka.shizuku.Shizuku.getBinder()
                ?: return Result(-1, "Shizuku 未连接")
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)

            Bg.run("shell-stderr") {
                try {
                    process.errorStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes()
                    }
                } catch (_: Exception) {
                }
            }

            val output =
                try {
                    process.inputStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes().decodeToString().trim()
                    }
                } catch (_: Exception) {
                    ""
                }

            if (!process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())) {
                process.destroy()
            }
            val exit =
                try {
                    process.exitValue()
                } catch (_: Exception) {
                    -1
                }
            Result(exit, output)
        } catch (e: Exception) {
            Result(-1, "命令执行失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** CarInput UserService 的绑定管理：绑定一次后所有触摸注入直连常驻 shell 进程（毫秒级）。 */
object CarInputClient {
    @Volatile
    var service: ICarInput? = null
        private set

    @Volatile
    private var binding = false

    @Volatile
    private var appContext: Context? = null

    /** UserService 参数（unbind 时需同一实例）。 */
    @Volatile
    private var userServiceArgs: rikka.shizuku.Shizuku.UserServiceArgs? = null

    /** 诊断日志出口（接入文件日志，HKS 加密下 logcat 不可读）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(message: String) {
        Log.i(TAG, message)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - diagAt < 1_000L) return
        diagAt = now
        logSink?.invoke("SHIZUKU: $message")
    }

    private var binderListenerAdded = false

    private val conn =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: IBinder?,
            ) {
                service = ICarInput.Stub.asInterface(binder)
                binding = false
                val ok =
                    try {
                        service?.ping() == true
                    } catch (_: Throwable) {
                        false
                    }
                diag("connected ping=$ok")
                if (!ok) {
                    service = null
                    binding = false
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                diag("service disconnected")
                service = null
                binding = false
            }
        }

    /** 上次绑定尝试时间（防止高频重试）。 */
    @Volatile
    private var lastBindAttemptAt = 0L

    /**
     * 确保 UserService 已绑定。可高频调用：service 为空且距上次尝试 >3s 时自动重绑
     * （手机重启/Shizuku 重启后 binder 可能丢失而进程残留，需主动重建连接）。
     */
    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!binderListenerAdded) {
            binderListenerAdded = true
            try {
                rikka.shizuku.Shizuku.addBinderReceivedListener {
                    android.util.Log.i(TAG, "binder received -> rebind")
                    binding = false
                    appContext?.let { ensure(it) }
                }
                rikka.shizuku.Shizuku.addBinderDeadListener {
                    android.util.Log.w(TAG, "binder dead")
                    service = null
                    binding = false
                }
            } catch (_: Throwable) {
            }
        }
        if (service != null) return
        val now = android.os.SystemClock.uptimeMillis()
        // 1.5s 节流：Shizuku 被系统冻结期间绑定会静默失败，解冻后需要尽快恢复
        if (now - lastBindAttemptAt < 1_500L) return
        // 关键修复（2026-10-09）：binding=true 但 service 长期为空 = 上次绑定静默失败
        // （Shizuku 被冻结时常见），必须解除卡死状态，否则重试被永久挡住
        // （表现为"必须强行停止 App 重开才恢复"）。
        if (binding) {
            binding = false
            diag("绑定卡死状态已解除（binding 超时未回调），立即重试")
        }
        lastBindAttemptAt = now
        val gate = ShizukuGate.state(app)
        if (gate != ShizukuGate.State.READY) {
            diag("绑定暂停：state=$gate")
            return
        }
        binding = true
        try {
            val args =
                userServiceArgs ?: rikka.shizuku.Shizuku
                    .UserServiceArgs(
                        ComponentName("com.lizhihua.carplaysender", CarInputServiceImpl::class.java.name),
                    ).processNameSuffix("car_input")
                    .debuggable(true)
                    .version(14) // 14：shell 包 Context 创建受信屏（修 packageName/uid 匹配校验）
                    .also { userServiceArgs = it }
            // 重绑前先解绑旧连接（清掉可能残留的 binder 状态）
            if (hasBoundOnce) {
                runCatching { rikka.shizuku.Shizuku.unbindUserService(args, conn, true) }
            }
            rikka.shizuku.Shizuku.bindUserService(args, conn)
            hasBoundOnce = true
            diag("bindUserService requested")
        } catch (e: Throwable) {
            binding = false
            diag("bindUserService failed: ${e.message}")
        }
    }

    @Volatile
    private var hasBoundOnce = false

    private const val TAG = "CarInputClient"
}
