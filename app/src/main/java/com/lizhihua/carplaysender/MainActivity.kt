package com.lizhihua.carplaysender

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lizhihua.carplaysender.bt.Iap2BluetoothServer
import com.lizhihua.carplaysender.net.CarPlayCtrlAdvertiser
import com.lizhihua.carplaysender.net.CarPlayServiceBrowser
import com.lizhihua.carplaysender.iap2.Iap2MessageIds
import com.lizhihua.carplaysender.net.WifiCarPlayConnector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 验证台：四步验证无线 CarPlay 发送端的网络链路。
 *  ① 加入接收端热点（WifiNetworkSpecifier，app 专属网络）
 *  ② mDNS 发现接收端的 _airplay._tcp（拿到 AirPlay 端点 + TXT）
 *  ③ 蓝牙监听 iAP2 UUID，等接收端（xcertplay）回连并转储链路层字节
 *  ④ 广播 _carplay-ctrl._tcp，验证接收端能反连手机控制通道
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var shellStateText: TextView
    private lateinit var logText: TextView
    private lateinit var ssidInput: EditText

    /**
     * 会话管线（对象、日志缓冲）全部由 [SessionController] 持有——Activity 只是挂屏：
     * 销毁重建不影响会话，重进界面日志自动续显（见 SessionController 类注释）。
     */
    private val logListener: (String) -> Unit = { text ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) logText.text = text
        }
    }

    private val ssidSink: (String) -> Unit = { ssid ->
        runOnUiThread { ssidInput.setText(ssid) }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BLUETOOTH_CONNECT 授权: $granted")
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("MODE: RECORD_AUDIO 授权: $granted（音频通道需要）")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                log("MODE: 投屏权限被拒绝（车机侧声音不可用）")
                return@registerForActivityResult
            }
            // Android 14+ 顺序：先把结果交给服务，由服务升级 FGS 类型后调 getMediaProjection
            com.lizhihua.carplaysender.service.CarPlayService.pendingProjectionResult =
                result.resultCode to data
            com.lizhihua.carplaysender.service.CarPlayService.start(this)
            log("MODE: ✓ 投屏权限已授予（重启手机后需重新授权）")
            // 会话已在进行中授权（授权晚于连接）：等投影就绪后动态补启音频通道
            Thread({
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    if (com.lizhihua.carplaysender.service.CarPlayService.mediaProjection != null) break
                    Thread.sleep(200)
                }
                if (com.lizhihua.carplaysender.service.CarPlayService.mediaProjection == null) {
                    log("MODE: 投影 5 秒未就绪（服务异常？），音频通道本次未启动")
                    return@Thread
                }
                SessionController.maybeStartAudioNow(applicationContext)
                // 「启动连接」时选了开启声音、投屏授权在此刻才拿到：接着启动连接
                if (pendingStartAfterProjection.compareAndSet(true, false)) {
                    startConnectionNow()
                }
            }, "audio-late-start").start()
        }

    /** 投屏授权是「启动连接」的前置（选了开启声音且尚未授权）时置位。 */
    private val pendingStartAfterProjection = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 「启动连接」前的音频选择弹窗（2026-10-10 用户要求）：
     * 音频与视频同时推流会显著增加负载（实测 48k 立体声 PCM ≈1.5Mbps + 视频 ≈4Mbps），
     * 测试台出现画面卡顿与断连；真车链路能力未知。因此默认不推音频，需用户显式开启。
     */
    private fun showAudioChoiceDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("是否在车机侧播放声音？")
            .setMessage(
                "开启后：手机媒体/导航声音将投到车机播放，手机本机静音（不开启则车上无声音）。\n\n" +
                    "注意：声音与画面同时传输会明显增加负载，可能出现画面卡顿或连接不稳；" +
                    "以看视频为主时建议暂不开启。\n\n" +
                    "开启声音需要「投屏」权限，选择后会一并申请。",
            )
            .setPositiveButton("开启声音") { _, _ -> beginWithAudio(enabled = true) }
            .setNegativeButton("仅视频（更流畅）") { _, _ -> beginWithAudio(enabled = false) }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun beginWithAudio(enabled: Boolean) {
        SessionController.audioEnabledForSession = enabled
        if (!enabled) {
            log("MODE: 本次连接仅推视频（未开启车机侧声音）")
            startConnectionNow()
            return
        }
        if (com.lizhihua.carplaysender.service.CarPlayService.mediaProjection != null) {
            log("MODE: 已选择开启声音（投屏权限已就绪）")
            startConnectionNow()
            return
        }
        // 开启声音需要投屏权限（播放捕获的授权来源）：先申请，授权完成后再启动连接
        log("MODE: 开启声音需要投屏权限——先后申请（音频捕获依赖），授权后自动开始连接")
        pendingStartAfterProjection.set(true)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /** 「启动连接」主体（权限检查留在这里；会话启动交给 [SessionController]，幂等不双开）。 */
    private fun startConnectionNow() {
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // 通知权限（API 33+）：前台服务通知可见；拒绝也不影响保活
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
            SessionController.startConnection(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)

        // 进程重启兜底：若上次会话未正常退役，「常亮」改过的屏幕超时需要恢复
        run {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.contains("screen_off_timeout_backup") &&
                com.lizhihua.carplaysender.launcher.VirtualLauncher.appDisplayId < 0
            ) {
                val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
                prefs.edit().remove("screen_off_timeout_backup").apply()
                Thread {
                    com.lizhihua.carplaysender.shizuku.Shell.exec(
                        "settings put system screen_off_timeout $backup",
                    )
                }.start()
            }
        }
        logText = findViewById(R.id.logText)
        ssidInput = findViewById(R.id.ssidInput)

        // 会话中枢（进程级）：登记 applicationContext + 创建会话对象；
        // 挂屏注册：日志续显（含划掉界面重进时已有会话的历史日志）+ SSID 回填
        SessionController.ensure(this)
        SessionController.logListener = logListener
        SessionController.ssidSink = ssidSink
        logText.text = SessionController.logText()

        setUpUiControls()
    }

    /** onCreate 的 UI 初始化尾部：开关/按钮/日志面板（从原 onCreate 拆出，保持结构清晰）。 */
    private fun setUpUiControls() {
        // 模式已简化为固定「隐形屏」（桌面/镜像模式已按需移除，2026-10-10）：
        // 启动器（dock + 应用屏双虚拟屏）即唯一形态。
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // 清理旧版本遗留的模式偏好
        if (preferences.contains("mode")) {
            preferences.edit().remove("mode").apply()
        }
        val alwaysOn = findViewById<android.widget.CheckBox>(R.id.switchAlwaysOn)
        com.lizhihua.carplaysender.ui.ScreenDimmer.enabled =
            preferences.getBoolean("alwaysOn", true)
        alwaysOn.isChecked = com.lizhihua.carplaysender.ui.ScreenDimmer.enabled
        alwaysOn.setOnCheckedChangeListener { _, checked ->
            com.lizhihua.carplaysender.ui.ScreenDimmer.enabled = checked
            preferences.edit().putBoolean("alwaysOn", checked).apply()
            if (checked) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    promptOverlayPermission("已开启「常亮+暗幕」。")
                } else {
                    com.lizhihua.carplaysender.ui.ScreenDimmer.attach(this)
                }
            } else {
                if (com.lizhihua.carplaysender.launcher.VirtualLauncher.appDisplayId >= 0) {
                    // 会话中：仅关闭常亮/暗幕（投屏探针等资源留待会话结束统一释放）
                    com.lizhihua.carplaysender.ui.ScreenDimmer.disableDim()
                } else {
                    com.lizhihua.carplaysender.ui.ScreenDimmer.detach()
                }
            }
            log("MODE: 常亮+暗幕 ${if (checked) "开启" else "关闭"}")
        }

        findViewById<Button>(R.id.btnProjection).setOnClickListener {
            // 投屏权限的唯一用途现在是「车机侧声音」（播放捕获）：与 RECORD_AUDIO 一起请求。
            // （镜像模式已移除，投屏不再用于画面。）
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnWifi).setOnClickListener {
            val ssid = ssidInput.text.toString().trim()
            log(
                "WiFi: 打开系统WLAN设置——请选择 \"$ssid\" 并输入热点密码连接；" +
                    "若提示\"无法访问互联网\"请选择保持连接；连上后回来点②扫描",
            )
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                log("WiFi: 无法打开WLAN设置，请手动前往 设置 > WLAN")
            }
        }

        findViewById<Button>(R.id.btnBluetooth).setOnClickListener {
            showAudioChoiceDialog()
        }
        findViewById<Button>(R.id.btnDimNow).setOnClickListener {
            if (!com.lizhihua.carplaysender.ui.ScreenDimmer.isAttached()) {
                log("DIMMER: 暗幕未就绪（连接成功后自动启用；或打开「常亮+暗幕」开关）")
                return@setOnClickListener
            }
            com.lizhihua.carplaysender.ui.ScreenDimmer.enterDimNow()
            log("DIMMER: 手动假息屏（点击屏幕任意处恢复）")
        }

        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            // 操作 SessionController 持有的真管线对象——任意 Activity 实例都能断到真会话
            SessionController.disconnect(this)
        }

        // ---- Shell 全局状态面板（调试/复位）----
        // 展示调试期间可能被 shell 改动过、且影响全局的项（letterbox 宽高比由本 App
        // 会话期间设置；force_resizable_activities 为开发者选项；overlay 为叠加屏实验
        // 残留；wm size/density 为主屏覆盖；CarPlay 虚拟屏为会话/残留清单）。
        // 「设为默认值」一键复位全部（下次连接会自动重新应用会话调优）。
        shellStateText = findViewById(R.id.shellStateText)
        findViewById<Button>(R.id.btnShellRefresh).setOnClickListener { refreshShellState() }
        findViewById<Button>(R.id.btnShellReset).setOnClickListener { resetShellState() }
        refreshShellState()

        // 日志开关（默认开；关闭后不采集、不显示日志区域；选择持久化）
        val logSwitch = findViewById<android.widget.CheckBox>(R.id.switchLog)
        val logHeader = findViewById<android.view.View>(R.id.logHeader)
        var logEnabled = preferences.getBoolean("logEnabled", true)
        SessionController.logEnabled = logEnabled
        logSwitch.isChecked = logEnabled
        fun applyLogVisibility() {
            // 只隐藏日志内容：标题行必须常驻（否则关掉后无法再打开）
            logHeader.visibility = android.view.View.VISIBLE
            logText.visibility = if (logEnabled) android.view.View.VISIBLE else android.view.View.GONE
        }
        logSwitch.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            SessionController.logEnabled = checked
            preferences.edit().putBoolean("logEnabled", checked).apply()
            applyLogVisibility()
        }
        applyLogVisibility()
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            SessionController.clearLog()
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val text = SessionController.logText()
            if (text.isEmpty()) {
                Toast.makeText(this, "日志为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CarPlay Sender 日志", text))
            Toast.makeText(this, "已复制 ${text.length} 字符日志", Toast.LENGTH_SHORT).show()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 启动即检查：隐形屏模式 + 常亮开 + 无悬浮窗权限 → 主动请求
        // （模式与开关都持久化，切换监听器不会在启动时触发，必须在这里补）
        if (com.lizhihua.carplaysender.ui.ScreenDimmer.enabled) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                promptOverlayPermission("「常亮+暗幕」已开启。")
            } else {
                // 立即就绪：假息屏按钮/自动暗幕不再依赖连接建立
                com.lizhihua.carplaysender.ui.ScreenDimmer.attach(this)
            }
        }

        com.lizhihua.carplaysender.input.TouchRouter.logSink = { line -> log(line) }
        com.lizhihua.carplaysender.launcher.VirtualLauncher.logSink = { line -> log(line) }
        com.lizhihua.carplaysender.shizuku.CarInputClient.logSink = { line -> log(line) }

        log("CarPlay Sender Phase 0 就绪")
        log("真车：与车机蓝牙配对 + 手机加入车机热点 -> 点「启动连接」（自动主动连入车机）")
        log("测试台：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 点「启动连接」等待回连")
    }

    private fun log(message: String) {
        // 写入进程级缓冲（logcat + 文件 + 挂屏渲染均由 SessionController 统一处理）
        SessionController.appendLog(message)
        runOnUiThread {
            if (!isFinishing && !isDestroyed) statusText.text = SessionController.statusLine()
        }
    }

    /**
     * 解析 dumpsys display 中的 CarPlay 虚拟屏：返回 (displayId, 名称)。
     * 注意 uniqueId 形如 virtual:com.android.shell,2000,CarPlayDock,36——尾数 36 是
     * 代次计数器而非显示屏 ID；真实 ID 在 "displayId=39, uniqueId='…'" 段里。
     */
    private fun parseCarPlayDisplays(dump: String): List<Pair<Int, String>> =
        Regex("displayId=(\\d+), uniqueId='[^']*CarPlay(Dock|App)")
            .findAll(dump)
            .map { it.groupValues[1].toInt() to "CarPlay${it.groupValues[2]}" }
            .distinctBy { it.first }
            .toList()

    /** 读取 shell 全局状态（后台线程 + UI 回填）。 */
    private fun refreshShellState() {
        val view = shellStateText
        view.text = "读取中…"
        Thread {
            if (com.lizhihua.carplaysender.shizuku.ShizukuGate.state(this) !=
                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY
            ) {
                runOnUiThread { view.text = "Shizuku 未就绪——shell 状态读取需要 Shizuku" }
                return@Thread
            }
            fun sh(cmd: String) = com.lizhihua.carplaysender.shizuku.Shell.exec(cmd, 4_000L).output
            val letterbox = Regex("Aspect ratio:\\s*([0-9.]+)")
                .find(sh("cmd window get-letterbox-style"))?.groupValues?.get(1) ?: "?"
            val forceResizable = sh("settings get global force_resizable_activities").ifBlank { "null" }
            val overlay = sh("settings get global overlay_display_devices").ifBlank { "null" }
            val wmSize = sh("wm size").replace("\n", " / ").ifBlank { "?" }
            val wmDensity = sh("wm density").replace("\n", " / ").ifBlank { "?" }
            val displays = parseCarPlayDisplays(sh("dumpsys display"))
                .joinToString(", ") { "${it.second}(${it.first})" }
            val timeout = sh("settings get system screen_off_timeout").ifBlank { "?" }
            val text = buildString {
                appendLine("letterbox 宽高比: $letterbox  （1.05=会话调优生效；0.0=系统默认）")
                appendLine("force_resizable: $forceResizable  （平板模式实验；默认 null/0）")
                appendLine("overlay_display_devices: $overlay  （叠加屏实验残留；应为 null）")
                appendLine("wm size: $wmSize")
                appendLine("wm density: $wmDensity")
                appendLine("CarPlay 虚拟屏: " + displays.trim().replace("\n", ", ").ifEmpty { "无" })
                appendLine("screen_off_timeout: $timeout  （App 常亮功能管理）")
            }
            runOnUiThread { view.text = text.trim() }
        }.start()
    }

    /** 一键把 shell 全局状态复位为系统默认值（含残留虚拟屏释放，保留当前会话屏）。 */
    private fun resetShellState() {
        val view = shellStateText
        view.text = "复位中…"
        Thread {
            if (com.lizhihua.carplaysender.shizuku.ShizukuGate.state(this) !=
                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY
            ) {
                runOnUiThread { view.text = "Shizuku 未就绪——shell 复位需要 Shizuku" }
                return@Thread
            }
            fun sh(cmd: String) = com.lizhihua.carplaysender.shizuku.Shell.exec(cmd, 5_000L)
            val steps = listOf(
                "letterbox 宽高比" to "cmd window reset-letterbox-style aspectRatio",
                "overlay 叠加屏" to "settings delete global overlay_display_devices",
                "force_resizable" to "settings delete global force_resizable_activities",
                "wm size" to "wm size reset",
                "wm density" to "wm density reset",
            )
            val results = steps.map { (name, cmd) ->
                val r = sh(cmd)
                if (r.ok) "✓ $name" else "✗ $name（${r.output.take(40)}）"
            }
            // 残留虚拟屏释放：保留当前会话的 dock/app 屏，其余 CarPlay* 屏逐一释放
            val keep = setOf(
                com.lizhihua.carplaysender.launcher.VirtualLauncher.dockDisplayId,
                com.lizhihua.carplaysender.launcher.VirtualLauncher.appDisplayId,
            )
            // uniqueId 尾数是代次计数器（≠ display id）！实际 id 取 "displayId=NN, uniqueId=…CarPlayXxx"
            val ids = parseCarPlayDisplays(sh("dumpsys display").output)
                .map { it.first }
                .filter { it !in keep }
                .toList()
            // UserService 懒绑定：新进程里 service 可能还是 null——先 ensure 并短等
            var svc = com.lizhihua.carplaysender.shizuku.CarInputClient.service
            if (svc == null && ids.isNotEmpty()) {
                com.lizhihua.carplaysender.shizuku.CarInputClient.ensure(this)
                val deadline = android.os.SystemClock.uptimeMillis() + 3_000L
                while (svc == null && android.os.SystemClock.uptimeMillis() < deadline) {
                    Thread.sleep(150)
                    svc = com.lizhihua.carplaysender.shizuku.CarInputClient.service
                }
            }
            val bound = svc
            val released = if (bound == null) 0 else ids.count { id ->
                runCatching { bound.releaseDisplay(id) }.getOrDefault(false)
            }
            val summary = results.joinToString("；") + "；残留屏释放 $released/${ids.size}"
            log("SHELL-RESET: $summary")
            runOnUiThread {
                view.text = "复位结果：\n" + results.joinToString("\n") +
                    "\n残留虚拟屏: 释放 $released/${ids.size}（保留当前会话屏）\n" +
                    "提示：letterbox 调优会在下次连接时自动重新应用"
            }
            refreshShellState()
        }.start()
    }

    /** 悬浮窗权限请求：先弹说明弹窗，用户确认后再跳系统设置。 */
    private fun promptOverlayPermission(reason: String) {
        if (android.provider.Settings.canDrawOverlays(this)) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("需要「显示在其他应用上层」权限")
            .setMessage(
                "$reason\n\n" +
                    "用途：无操作时在屏幕上盖一层暗幕（替代息屏，防止手机锁屏后车机触摸失效）。" +
                    "不会读取或遮挡其他应用内容，点击屏幕立即消失。",
            )
            .setPositiveButton("去授权") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                } catch (_: Exception) {
                    log("DIMMER: 无法打开授权页，请手动前往 设置>应用>特殊权限>显示在其他应用上层")
                }
            }
            .setNegativeButton("暂不") { _, _ ->
                log("DIMMER: 用户暂未授权悬浮窗（暗幕不可用，常亮仍生效）")
            }
            .show()
    }

    private fun preferences(): android.content.SharedPreferences =
        getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * 日志落盘（files/main_debug.log）：荣耀机型 logcat 被 HKS 加密，
     * 该文件是 adb 侧唯一调试通道（run-as 读取）。2MB 上限自动滚动。
     */
    private fun appendDebugFile(message: String) {
        // 与 CarPlayService 共用 DebugFileLog（同一把锁，避免同文件并发写交叉）
        com.lizhihua.carplaysender.util.DebugFileLog.append(this, message)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只解绑"挂屏"，不动会话——连接由前台服务保活、对象归 SessionController 持有；
        // 显式断开请用「断开」按钮（任意实例都能断到真管线）。
        if (SessionController.logListener === logListener) SessionController.logListener = null
        if (SessionController.ssidSink === ssidSink) SessionController.ssidSink = null
    }
}
