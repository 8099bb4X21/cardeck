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

    /** 主线程调用 `Shell.exec` 的累计次数（诊断用；见 [exec] 里的守卫）。 */
    @Volatile
    var mainThreadViolations: Int = 0
        private set

    /**
     * 在主线程调用 shell 的告警出口（SessionController.appendLog）。
     * 单条 shell 通常 100ms 量级、超时可达数秒，串几条就够 ANR——
     * 这类问题只在真机上暴露（HKS 下 logcat 不可读），必须进文件日志。
     */
    @Volatile
    var violationSink: ((String) -> Unit)? = null

    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result {
        // **UI 线程禁止 shell**：每次 exec 都是 Shizuku newProcess + 进程等待。
        // 这里不强行拦截（会让原本可用的功能失效），但记录并告警——历史上
        // 已有两次真机 ANR 源于此（detach 清理、横屏勾选框）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mainThreadViolations++
            violationSink?.invoke(
                "SHELL: ⚠ 主线程调用 shell（第 $mainThreadViolations 次）cmd=${cmd.take(60)}" +
                    "（$timeoutMs ms 上限）——应改用 Bg.run / 后台线程",
            )
        }
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

    private fun diag(
        message: String,
        force: Boolean = false,
    ) {
        Log.i(TAG, message)
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - diagAt < 1_000L) return
        diagAt = now
        logSink?.invoke("SHIZUKU: $message")
    }

    private var binderListenerAdded = false

    /**
     * 回收旧版本残留的 UserService 进程，返回实际 kill 的数量（不足条件时 0）。
     *
     * Shizuku 按「组件+进程名+版本(lastUpdateTime)」复用常驻 shell 进程——重装 APK 后
     * 旧版本进程既不受 force-stop 影响、Shizuku 也不回收；而虚拟屏只有创建它的进程能
     * 释放（releaseDisplay 只查本进程的 map），表现为「重启后检测到残留屏，点清理却
     * 释放 0/N」。这里枚举 :car_input 进程，杀掉除当前绑定进程外的全部——旧进程只持有
     * 残留屏，进程回收即屏随之释放（不打断触摸注入，当前会话屏属于绑定进程）。
     * 配合 UserService 侧的过期自清（CarInputServiceImpl 的 stale self-reap）双保险。
     *
     * 阻塞调用（含 shell ps/kill），须在后台线程执行；绑定成功时自动触发一次。
     */
    fun reapStaleUserServices(): Int {
        val bound = service ?: return 0
        val keepPid =
            try {
                bound.pid()
            } catch (t: Throwable) {
                // 旧 dex 无 pid() 事务（正常不会发生：Shizuku 按版本复用，绑定的必是当前版本）
                diag("reap 跳过：pid() 不可用（${t.javaClass.simpleName}）")
                return 0
            }
        val procName = (appContext?.packageName ?: return 0) + ":car_input"
        val ps = Shell.exec("ps -A -o PID,ARGS", 5_000L)
        if (!ps.ok) {
            diag("reap 跳过：ps 失败（${ps.output.take(40)}）")
            return 0
        }
        val stale =
            ps.output
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    if (parts.getOrNull(1) == procName && pid != keepPid) pid else null
                }.toList()
        if (stale.isEmpty()) return 0
        val killed = stale.count { pid -> Shell.exec("kill -9 $pid", 3_000L).ok }
        // force：紧随 bind 的 1s 节流窗口会吞掉这条关键结果（排查时必查）
        diag("已回收 $killed/${stale.size} 个旧版本 UserService 进程（其残留虚拟屏随之释放）", force = true)
        return killed
    }

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
                } else {
                    // 绑定成功后顺手回收旧版本残留进程（其持有的虚拟屏跨进程无法释放，
                    // 只有进程回收才能让屏消失）。后台线程执行：内部含 shell ps/kill。
                    Bg.run("stale-user-service-reap") { reapStaleUserServices() }
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
                    // 版本号取 APK lastUpdateTime（见 serviceVersion 注释）
                    .version(serviceVersion())
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

    /**
     * UserService 版本 = APK lastUpdateTime（秒）。Shizuku 按「组件+进程名+版本」复用
     * 常驻服务进程：硬编码版本号时重装 APK 不会换进程——复用的旧进程跑的还是旧 dex，
     * 新代码静默不生效（2026-10-15 实测：受信屏加 SUPPORTS_IME 后 flags 仍是旧值
     * 0x448 的根因；且 force-stop 杀不掉这些 shell 进程，会越积越多）。
     * lastUpdateTime 重装必变 → 必起新进程；APK 不变时恒定 → 正常复用常驻进程。
     */
    private fun serviceVersion(): Int =
        try {
            val pm = appContext?.packageManager ?: return 15

            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(appContext!!.packageName, 0)
            (info.lastUpdateTime / 1000L).toInt()
        } catch (_: Throwable) {
            15 // 兜底：与历史手写版本号序列衔接，保证不低于 14
        }

    private const val TAG = "CarInputClient"
}
