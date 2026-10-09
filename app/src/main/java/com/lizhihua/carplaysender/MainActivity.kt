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
    private lateinit var logText: TextView
    private lateinit var ssidInput: EditText

    private lateinit var wifiConnector: WifiCarPlayConnector
    private lateinit var serviceBrowser: CarPlayServiceBrowser
    private lateinit var bluetoothServer: Iap2BluetoothServer
    private lateinit var ctrlAdvertiser: CarPlayCtrlAdvertiser

    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logBuilder = StringBuilder()
    private var touchLogCount = 0

    private var videoSender: com.lizhihua.carplaysender.video.VideoSender? = null
    private var currentRenderer: com.lizhihua.carplaysender.ui.CarDisplayRenderer? = null
    private var currentClient: com.lizhihua.carplaysender.airplay.AirPlayClient? = null
    private var currentMirrorSource: com.lizhihua.carplaysender.capture.MirrorScreenSource? = null
    private var currentHiddenSource: com.lizhihua.carplaysender.capture.HiddenDisplaySource? = null

    /** 新 BT 周期开始前彻底退役上一代会话（旧管线泄漏会累积编码器/虚拟显示器，最终 dequeueBuffer -19）。 */
    private fun retirePreviousSession() {
        videoSender?.stop()
        videoSender = null
        currentRenderer?.release()
        currentRenderer = null
        currentMirrorSource?.release()
        currentMirrorSource = null
        currentHiddenSource?.release()
        currentHiddenSource = null
        com.lizhihua.carplaysender.input.TouchRouter.renderer = null
        com.lizhihua.carplaysender.input.TouchRouter.hiddenSource = null
        com.lizhihua.carplaysender.input.TouchRouter.reset()
        currentClient?.close()
        currentClient = null
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BLUETOOTH_CONNECT 授权: $granted")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                log("MODE: 投屏权限被拒绝（镜像模式不可用）")
                return@registerForActivityResult
            }
            // Android 14+ 顺序：先把结果交给服务，由服务升级 FGS 类型后调 getMediaProjection
            com.lizhihua.carplaysender.service.CarPlayService.pendingProjectionResult =
                result.resultCode to data
            com.lizhihua.carplaysender.service.CarPlayService.start(this)
            log("MODE: ✓ 投屏权限已授予（重启手机后需重新授权）")
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

        wifiConnector = WifiCarPlayConnector(this) { line -> log(line) }
        serviceBrowser = CarPlayServiceBrowser(this, log = { line -> log(line) })
        bluetoothServer = Iap2BluetoothServer(this, log = { line -> log(line) }) { endpoint ->
            log(
                ">>> 拿到接收端热点：SSID=${endpoint.ssid} 密码=${endpoint.passphrase} " +
                    "（已回填输入框；点①进系统WLAN设置加入该热点，连上后点②扫描）",
            )
            runOnUiThread { ssidInput.setText(endpoint.ssid) }
            // 自动广播 _carplay-ctrl._tcp：无线 CarPlay 中接收端会反连手机控制端口
            ctrlAdvertiser.start()
            // 若手机已加入过接收端热点：等 ctrl 探测完成（实测 +1s~+1.3s）→
            // 主动断开 BT 控制会话（复现接收端唯一应答 RTSP 的状态）→ 执行 AirPlay 配对
            wifiConnector.bindCurrentWifi { bound ->
                if (bound) {
                    Thread({
                        Thread.sleep(1_600) // 等 ctrl 探测（实测 +1.0~+1.3s）
                        fun newClient() = com.lizhihua.carplaysender.airplay.AirPlayClient(
                            endpoint,
                            log = { line -> log(line) },
                            context = applicationContext,
                        )
                        retirePreviousSession()
                        // 第一阶段：保持 BT 在位直接配对 + SETUP（协议上正确的形态）
                        var client = newClient()
                        currentClient = client
                        var keys = client.runPairing()
                        if (keys == null) {
                            // 第二阶段：复现「BT 控制已结束」状态再试一次
                            bluetoothServer.detachCurrentSession()
                            client = newClient()
                            currentClient = client
                            keys = client.runPairing()
                        }
                        if (keys != null) {
                            log(
                                "AIRPLAY: ✓ 配对完成 writeKey=" +
                                    keys.phoneWriteKey.joinToString("") { "%02x".format(it) }.take(16) + "… " +
                                    "readKey=" + keys.phoneReadKey.joinToString("") { "%02x".format(it) }.take(16) + "…",
                            )
                            val setup = client.runSetup()
                            if (setup != null) {
                                val mode = com.lizhihua.carplaysender.input.TouchRouter.mode
                                com.lizhihua.carplaysender.input.TouchRouter.carWidth = setup.displayWidth
                                com.lizhihua.carplaysender.input.TouchRouter.carHeight = setup.displayHeight
                                var desktopRenderer: com.lizhihua.carplaysender.ui.CarDisplayRenderer? = null
                                if (mode == com.lizhihua.carplaysender.input.TouchRouter.Mode.DESKTOP) {
                                    desktopRenderer = com.lizhihua.carplaysender.ui.CarDisplayRenderer(
                                        applicationContext,
                                        width = setup.displayWidth,
                                        height = setup.displayHeight,
                                        log = { line -> log(line) },
                                    )
                                    currentRenderer = desktopRenderer
                                    com.lizhihua.carplaysender.input.TouchRouter.renderer = desktopRenderer
                                }
                                client.connectEventChannel(keys.sharedSecret, setup) { command ->
                                    when (command["type"]) {
                                        "hidSendReport" -> {
                                            val uuid = command["uuid"]?.toString()
                                            val report = command["hidReport"] as? ByteArray
                                            if (uuid == com.lizhihua.carplaysender.input.TouchReports.TOUCH_UUID_HEX &&
                                                report != null
                                            ) {
                                                val contacts = com.lizhihua.carplaysender.input.TouchReports.parse(report)
                                                if (touchLogCount < 20) {
                                                    touchLogCount++
                                                    log(
                                                        "EVENT: 触摸 " + contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                    )
                                                }
                                                contacts.forEach {
                                                    com.lizhihua.carplaysender.input.TouchRouter.onContact(
                                                        applicationContext,
                                                        it,
                                                    )
                                                }
                                            } else if (report != null) {
                                                log("EVENT: 其他 HID 报告 uuid=$uuid bytes=${report.size}")
                                            }
                                        }

                                        else -> log("EVENT: 命令 type=${command["type"]}")
                                    }
                                }
                                val screen = client.runScreenStream(keys.sharedSecret)
                                if (screen != null) {
                                    client.record()
                                    val host = endpoint.ipAddresses.firstOrNull { it.contains('.') }
                                    if (host != null) {
                                        videoSender = com.lizhihua.carplaysender.video.VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            streamKey = screen.streamKey,
                                            width = setup.displayWidth,
                                            height = setup.displayHeight,
                                            log = { line -> log(line) },
                                        )
                                        val density = 420
                                        videoSender?.start { surface ->
                                            when (mode) {
                                                com.lizhihua.carplaysender.input.TouchRouter.Mode.DESKTOP -> {
                                                    desktopRenderer?.attach(surface)
                                                }

                                                com.lizhihua.carplaysender.input.TouchRouter.Mode.MIRROR -> {
                                                    val projection =
                                                        com.lizhihua.carplaysender.service.CarPlayService.mediaProjection
                                                    if (projection == null) {
                                                        log("MODE: 镜像模式缺少投屏权限——请先点「申请投屏权限」")
                                                    } else {
                                                        val source = com.lizhihua.carplaysender.capture.MirrorScreenSource(
                                                            projection,
                                                        )
                                                        currentMirrorSource = source
                                                        val ok = source.attach(
                                                            surface,
                                                            setup.displayWidth,
                                                            setup.displayHeight,
                                                            density,
                                                        )
                                                        log("MODE: 镜像主屏 ${if (ok) "已启动" else "启动失败"}（注意保持手机亮屏解锁）")
                                                    }
                                                }

                                                com.lizhihua.carplaysender.input.TouchRouter.Mode.HIDDEN -> {
                                                    val source =
                                                        com.lizhihua.carplaysender.capture.HiddenDisplaySource(
                                                            applicationContext,
                                                            log = { line -> log(line) },
                                                        )
                                                    currentHiddenSource = source
                                                    com.lizhihua.carplaysender.input.TouchRouter.hiddenSource = source
                                                    val ok = source.attach(
                                                        surface,
                                                        setup.displayWidth,
                                                        setup.displayHeight,
                                                        density,
                                                    )
                                                    log(
                                                        "MODE: 隐形屏 ${if (ok) "已启动（高德车机版优先）display=${source.displayId}" else "启动失败（Shizuku 就绪？）"}",
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }, "airplay-pairing").start()
                }
            }
        }
        ctrlAdvertiser = CarPlayCtrlAdvertiser(this) { line -> log(line) }

        // 模式选择（持久化；切换后如已连接，断开当前蓝牙会话触发下一轮按新模式重建）
        val modeGroup = findViewById<android.widget.RadioGroup>(R.id.modeGroup)
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedMode = preferences.getString("mode", "DESKTOP") ?: "DESKTOP"
        val initialMode = com.lizhihua.carplaysender.input.TouchRouter.Mode.valueOf(savedMode)
        com.lizhihua.carplaysender.input.TouchRouter.mode = initialMode
        modeGroup.check(
            when (initialMode) {
                com.lizhihua.carplaysender.input.TouchRouter.Mode.DESKTOP -> R.id.modeDesktop
                com.lizhihua.carplaysender.input.TouchRouter.Mode.MIRROR -> R.id.modeMirror
                com.lizhihua.carplaysender.input.TouchRouter.Mode.HIDDEN -> R.id.modeHidden
            },
        )
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.modeMirror -> com.lizhihua.carplaysender.input.TouchRouter.Mode.MIRROR
                R.id.modeHidden -> com.lizhihua.carplaysender.input.TouchRouter.Mode.HIDDEN
                else -> com.lizhihua.carplaysender.input.TouchRouter.Mode.DESKTOP
            }
            preferences.edit().putString("mode", mode.name).apply()
            com.lizhihua.carplaysender.input.TouchRouter.mode = mode
            when (mode) {
                com.lizhihua.carplaysender.input.TouchRouter.Mode.DESKTOP ->
                    log("MODE: 切换到 桌面（自绘 UI），下一轮连接生效")

                com.lizhihua.carplaysender.input.TouchRouter.Mode.MIRROR ->
                    log("MODE: 切换到 镜像主屏（需先申请投屏权限，保持亮屏），下一轮连接生效")

                com.lizhihua.carplaysender.input.TouchRouter.Mode.HIDDEN -> {
                    log("MODE: 切换到 隐形屏（启动器：桌面+dock，需 Shizuku 就绪），下一轮连接生效")
                    if (com.lizhihua.carplaysender.ui.ScreenDimmer.enabled) {
                        promptOverlayPermission("已切换到「隐形屏」模式且常亮暗幕开启。")
                    }
                }
            }
            bluetoothServer.detachCurrentSession()
        }
        // 常亮 + 暗幕（替代息屏，规避锁屏冻结车机触摸）
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
                com.lizhihua.carplaysender.ui.ScreenDimmer.detach()
            }
            log("MODE: 常亮+暗幕 ${if (checked) "开启" else "关闭"}")
        }

        findViewById<Button>(R.id.btnProjection).setOnClickListener {
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
            if (!bluetoothServer.hasPermission()) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // 通知权限（API 33+）：前台服务通知可见；拒绝也不影响保活
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
            // 前台保活：连接期间进程不被后台清理杀死
            com.lizhihua.carplaysender.service.CarPlayService.start(this)
            bluetoothServer.start()
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
            retirePreviousSession()
            com.lizhihua.carplaysender.service.CarPlayService.stop(this)
            bluetoothServer.stop()
            ctrlAdvertiser.stop()
            wifiConnector.release()
            log("已手动断开并停止保活服务（常亮已恢复）")
        }

        // 日志开关（默认开；关闭后不采集、不显示日志区域；选择持久化）
        val logSwitch = findViewById<android.widget.CheckBox>(R.id.switchLog)
        val logHeader = findViewById<android.view.View>(R.id.logHeader)
        var logEnabled = preferences.getBoolean("logEnabled", true)
        logSwitch.isChecked = logEnabled
        fun applyLogVisibility() {
            // 只隐藏日志内容：标题行必须常驻（否则关掉后无法再打开）
            logHeader.visibility = android.view.View.VISIBLE
            logText.visibility = if (logEnabled) android.view.View.VISIBLE else android.view.View.GONE
        }
        logSwitch.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            preferences.edit().putBoolean("logEnabled", checked).apply()
            applyLogVisibility()
        }
        applyLogVisibility()
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            logBuilder.setLength(0)
            logText.text = ""
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val text = logBuilder.toString()
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

        log("CarPlay Sender Phase 0 就绪")
        log("测试台流程：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 本机 ③ 监听")
    }

    private fun log(message: String) {
        // 镜像到 logcat：真机排查（息屏/断链类问题）必须能从 adb 侧看到协议日志
        android.util.Log.i("CarPlaySender", message)
        // 关闭「监听日志」后：不采集也不渲染（状态行仍更新）
        if (preferences().getBoolean("logEnabled", true)) {
            runOnUiThread {
                logBuilder.append('[').append(timestamp.format(Date())).append("] ")
                    .append(message).append('\n')
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                logText.text = logBuilder
            }
        }
        runOnUiThread {
            statusText.text = "wifi=${wifiConnector.currentNetwork != null} " +
                "bt=${bluetoothServer.isRunning} ctrl=${ctrlAdvertiser.port > 0}"
        }
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

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不再在这里拆连接——Activity 销毁（如划掉任务）时连接由前台服务保活；
        // 显式断开请用「断开」按钮。
    }
}
