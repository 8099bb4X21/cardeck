package com.lizhihua.carplaysender.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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

    class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    fun exec(cmd: String, timeoutMs: Long = 6000L): Result {
        val binder = rikka.shizuku.Shizuku.getBinder()
            ?: return Result(-1, "Shizuku 未连接")
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)

            thread(isDaemon = true) {
                try {
                    process.errorStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes()
                    }
                } catch (_: Exception) {
                }
            }

            val output = try {
                process.inputStream.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).readBytes().decodeToString().trim()
                }
            } catch (_: Exception) {
                ""
            }

            if (!process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())) {
                process.destroy()
            }
            val exit = try {
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

    private var binderListenerAdded = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ICarInput.Stub.asInterface(binder)
            binding = false
            val ok = try { service?.ping() == true } catch (_: Throwable) { false }
            Log.i(TAG, "CarInput connected ping=$ok diag=${try { service?.diag() } catch (_: Throwable) { "?" }}")
            if (!ok) service = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun ensure(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!binderListenerAdded) {
            binderListenerAdded = true
            try {
                rikka.shizuku.Shizuku.addBinderReceivedListener {
                    appContext?.let { ensure(it) }
                }
            } catch (_: Throwable) {
            }
        }
        if (service != null || binding) return
        if (ShizukuGate.state(app) != ShizukuGate.State.READY) return
        binding = true
        try {
            val args = rikka.shizuku.Shizuku.UserServiceArgs(
                ComponentName("com.lizhihua.carplaysender", CarInputServiceImpl::class.java.name)
            )
                .processNameSuffix("car_input")
                .debuggable(true)
                .version(11)
            rikka.shizuku.Shizuku.bindUserService(args, conn)
            Log.i(TAG, "bindUserService requested")
        } catch (e: Throwable) {
            binding = false
            Log.i(TAG, "bindUserService failed: ${e.message}")
        }
    }

    private const val TAG = "CarInputClient"
}
