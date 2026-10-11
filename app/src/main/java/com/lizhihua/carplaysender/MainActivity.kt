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
    private lateinit var logText: TextView
    private lateinit var ssidInput: EditText

    private lateinit var wifiConnector: WifiCarPlayConnector
    private lateinit var serviceBrowser: CarPlayServiceBrowser
    private lateinit var bluetoothServer: Iap2BluetoothServer
    private lateinit var bluetoothClient: com.lizhihua.carplaysender.bt.Iap2BluetoothClient
    private lateinit var ctrlAdvertiser: CarPlayCtrlAdvertiser

    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logBuilder = StringBuilder()
    private var touchLogCount = 0
    private var iapEventMessageCount = 0

    private var videoSender: com.lizhihua.carplaysender.video.VideoSender? = null
    private var audioSender: com.lizhihua.carplaysender.audio.AudioSender? = null
    private var currentClient: com.lizhihua.carplaysender.airplay.AirPlayClient? = null
    private var currentHiddenSource: com.lizhihua.carplaysender.capture.HiddenDisplaySource? = null

    /** 当前会话的共享密钥与接收端地址：投影授权晚于连接时，动态补启音频用。 */
    @Volatile
    private var currentSharedSecret: ByteArray? = null

    @Volatile
    private var currentAudioHost: String? = null

    /** 新 BT 周期开始前彻底退役上一代会话（旧管线泄漏会累积编码器/虚拟显示器，最终 dequeueBuffer -19）。 */
    private fun retirePreviousSession() {
        videoSender?.stop()
        videoSender = null
        audioSender?.stop()
        audioSender = null
        currentSharedSecret = null
        currentAudioHost = null
        currentHiddenSource?.release()
        currentHiddenSource = null
        com.lizhihua.carplaysender.input.TouchRouter.reset()
        currentClient?.close()
        currentClient = null
        // 解除会话静默：出站探测恢复（下次会话建立时会再次置位）
        com.lizhihua.carplaysender.bt.BtSessionGate.sessionActive = false
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
                maybeStartAudioNow()
                // 「启动连接」时选了开启声音、投屏授权在此刻才拿到：接着启动连接
                if (pendingStartAfterProjection.compareAndSet(true, false)) {
                    startConnectionNow()
                }
            }, "audio-late-start").start()
        }

    /** 本次会话是否在车机侧播放声音（由「启动连接」弹窗选择）。 */
    @Volatile
    private var audioEnabledForSession = false

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
        audioEnabledForSession = enabled
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

    /** 「启动连接」主体（权限检查 + 保活服务 + 双向蓝牙启动）。原按钮逻辑抽取。 */
    private fun startConnectionNow() {
        runOnUiThread {
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
            // 隐形屏（唯一模式）：连接时提示 Shizuku 状态（冻结/未授权时启动器与触摸注入不可用）
            when (com.lizhihua.carplaysender.shizuku.ShizukuGate.state(this)) {
                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY ->
                    log("SHIZUKU: 就绪（触摸注入可用）")

                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.UNAUTHORIZED -> {
                    log("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗点允许后重连")
                    com.lizhihua.carplaysender.shizuku.ShizukuGate.requestPermission()
                }

                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.NOT_RUNNING ->
                    log("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」")

                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.NOT_INSTALLED ->
                    log("SHIZUKU: 未安装（隐形屏模式需要）")
            }
            com.lizhihua.carplaysender.service.CarPlayService.start(this)
            // 真车路径：手机主动连车机（SDP …caff 配件服务）
            // 测试台路径：监听手机侧 iPhone UUID，等 DiPlay/xcertplay 回连
            bluetoothClient.start()
            bluetoothServer.start()
        }
    }

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    private fun maybeStartAudioNow() {
        val client = currentClient ?: run {
            log("MODE: 投屏已就绪（当前无会话——下次连接时自动启用音频）")
            return
        }
        val secret = currentSharedSecret ?: return
        if (audioSender?.isRunning == true) return
        log("MODE: 会话进行中，动态补启音频通道…")
        startAudioChannel(client, secret, currentAudioHost)
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
        // 两条 BT 路径共用同一套端点处理：
        //  - Server：测试台（DiPlay/xcertplay 回连手机，等待接收端发起）
        //  - Client：真车（手机主动连入车机 SDP 的 …caff 配件端服务）
        val wifiCredentialHook: (com.lizhihua.carplaysender.iap2.Iap2SenderSession.WifiConfiguration) -> Unit =
            { wifi -> onWifiCredentials(wifi) }
        bluetoothServer = Iap2BluetoothServer(
            this,
            log = { line -> log(line) },
            onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "车机回连") },
            onWifiCredentials = wifiCredentialHook,
        )
        bluetoothClient = com.lizhihua.carplaysender.bt.Iap2BluetoothClient(
            this,
            log = { line -> log(line) },
            onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "主动连车机") },
            onWifiCredentials = wifiCredentialHook,
        )
        ctrlAdvertiser = CarPlayCtrlAdvertiser(this) { line -> log(line) }

        setUpUiControls()
    }

    /**
     * 0x5703 到达（拿到车机热点凭据）立即执行——真车 0x4301 的前置条件：
     * 车机在 WiFi 网络上发现手机的 `_carplay-ctrl._tcp`（TXT id=真实蓝牙 MAC）后
     * 主动发 `GET /ctrl-int/1/connect` 邀请，邀请被接受才发 0x4301。
     * 因此这里必须：绑车机 WiFi → 广播 ctrl；晚于 0x4300 则双方互等死锁（Audi 实测）。
     */
    private fun onWifiCredentials(
        wifi: com.lizhihua.carplaysender.iap2.Iap2SenderSession.WifiConfiguration,
    ) {
        runOnUiThread { ssidInput.setText(wifi.ssid) }
        log(">>> 拿到车机热点凭据（0x5703）——立即绑定 WiFi + 广播 _carplay-ctrl")
        // 先占位注册（此时可能尚未绑定到目标网络）；绑定成功后再 force 重注册，
        // 保证 mDNS 组播落在车机所在网络（真车实测必需：注册早于绑定会广播到错误网络）
        ctrlAdvertiser.ensureForNetwork(wifi.ssid)
        wifiConnector.bindCurrentWifi { bound ->
            if (!bound) {
                log(">>> 手机不在车机热点 \"${wifi.ssid}\" —— 请到 系统设置>WLAN 加入后重新连接")
            } else {
                // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
                ctrlAdvertiser.ensureForNetwork(wifi.ssid, force = true)
                log(">>> WiFi 已绑定车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请（随后才回 0x4301）")
            }
        }
    }

    private val sessionStarting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 复现「BT 控制已结束」状态：断开当前任一路径的 iAP2 控制会话（DiPlay 只在此状态应答 RTSP）。 */
    private fun detachActiveBtSessions() {
        bluetoothServer.detachCurrentSession()
        bluetoothClient.detachCurrentSession()
    }

    /**
     * 音频通道（手机 → 车机，RTP over UDP）：SETUP type=100 协商 → 播放捕获 → ChaCha 封包推流。
     *
     * 依赖 MediaProjection（AudioPlaybackCapture 的授权来源），且需用户在「启动连接」
     * 弹窗中选择「开启声音」——音视频同时推流显著增加负载（实测会卡顿/断连）。
     */
    private fun startAudioChannel(
        client: com.lizhihua.carplaysender.airplay.AirPlayClient,
        sharedSecret: ByteArray,
        host: String?,
    ) {
        if (!audioEnabledForSession) {
            log("AUDIO: 本次连接未开启车机侧声音（「启动连接」弹窗选择）——仅推视频")
            return
        }
        if (host == null) {
            log("AUDIO: 无接收端 IPv4 地址，跳过音频通道")
            return
        }
        val projection = com.lizhihua.carplaysender.service.CarPlayService.mediaProjection
        if (projection == null) {
            log(
                "AUDIO: 缺少投屏权限（播放捕获依赖 MediaProjection）——音频通道未启动。" +
                    "现在点「申请投屏权限」也可即时补启（无需重连）；注意「断开连接」会停服务并连带失效投屏授权",
            )
            return
        }
        try {
            val audio = client.runAudioStream(sharedSecret) ?: return
            val sender = com.lizhihua.carplaysender.audio.AudioSender(
                host = host,
                dataPort = audio.dataPort,
                streamKey = audio.streamKey,
                log = { line -> log(line) },
                context = applicationContext,
            )
            audioSender = sender
            if (!sender.start(projection)) {
                audioSender = null
            }
        } catch (error: Exception) {
            log("AUDIO: 通道启动异常 - ${error.message}")
            audioSender = null
        }
    }

    /**
     * 端点补全：0x4301 缺失/无 IPv4 时用 mDNS 解析车机 `_airplay._tcp` 合成端点
     * （真 iPhone 同款发现路径，也是 Audi MIB3 唯一路径——它不发 0x4301）。
     * 测试台端点自带 IPv4 与端口，本方法秒过、行为不变。
     */
    private fun resolveEndpointHost(
        endpoint: com.lizhihua.carplaysender.iap2.Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): com.lizhihua.carplaysender.iap2.Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val needHost = !effective.ipAddresses.any { it.contains('.') && !it.contains(':') }
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        log(">>> [$source] 0x4301 端点不完整（port=${effective.airPlayPort} ips=${effective.ipAddresses}），mDNS 解析车机 _airplay._tcp…")
        // 车机网段前缀（本机 IP 前 3 段）：手机若同时开热点共享（如给电脑），mDNS 会跨网
        // 发现电脑的 AirPlay 接收器（macOS 自带）——2026-10-11 实测把 MacBook 10.180.48.59
        // 当成车机连了 120s。非本网段的一律跳过。
        val carSubnetPrefix = wifiConnector.localIpv4Address()?.substringBeforeLast('.')?.plus(".")
        val latch = java.util.concurrent.CountDownLatch(1)
        val found = java.util.concurrent.atomic.AtomicReference<android.net.nsd.NsdServiceInfo?>(null)
        val browser = com.lizhihua.carplaysender.net.CarPlayServiceBrowser(
            this,
            log = { line -> log(line) },
        ) { info ->
            if (found.get() != null) return@CarPlayServiceBrowser
            val ip = info.host?.hostAddress
            if (ip == null || !ip.contains('.') || ip.contains(':')) return@CarPlayServiceBrowser
            if (carSubnetPrefix != null && !ip.startsWith(carSubnetPrefix)) {
                val txtId = info.attributes["deviceid"]?.toString(Charsets.UTF_8)?.trimEnd { it.code == 0 }
                log(">>> [$source] 跳过非车机 _airplay 服务 ${info.serviceName}: $ip" +
                    (txtId?.let { "（deviceid=$it）" } ?: "") + "——不在车机网段 $carSubnetPrefix x")
                return@CarPlayServiceBrowser
            }
            if (found.compareAndSet(null, info)) latch.countDown()
        }
        browser.start()
        // 车机热点刚绑定时 mDNS 可能还没起；重试三轮（Audi 的 AirPlay 服务在受邀后才上线）
        for (round in 1..3) {
            val resolved = try {
                latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            if (resolved) break
            log(">>> [$source] mDNS 第 $round 轮未发现 _airplay._tcp——重试")
        }
        browser.stop()
        val info = found.get() ?: run {
            // 兜底：热点场景车机 IP = 网关（Audi 实测 .1，但取网关更通用）。
            // mDNS 在部分网络/机型上不稳定（2026-10-11 实测车机 mDNS 时有时无）；
            // 网关地址 + 默认端口 7000 直接连，AirPlay 端口是固定约定。
            val gateway = wifiConnector.gatewayAddress()
            if (gateway != null && gateway.contains('.') && !gateway.contains(':')) {
                log(">>> [$source] mDNS 未发现，兜底使用网关地址 $gateway:7000（车机热点网关=车机）")
                return effective.copy(
                    ipAddresses = listOf(gateway) + effective.ipAddresses,
                    airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                )
            }
            log(">>> [$source] mDNS 未发现车机 AirPlay 服务（车机可能尚未启动无线会话）")
            return effective
        }
        val address = info.host?.hostAddress
        var result = effective
        if (address != null && address.contains('.') && !address.contains(':')) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            log(">>> [$source] mDNS 解析到车机地址 $address:${info.port}")
        }
        if (needPort && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        // TXT 公钥兜底（pair-verify 校验用；0x4301 缺失时只能从这里拿）
        if (result.publicKey == null) {
            val txtPk = info.attributes["pk"]?.toString(Charsets.UTF_8)
                ?.trimEnd { it.code == 0 }
                ?.takeIf { it.isNotEmpty() }
            if (txtPk != null) result = result.copy(publicKey = txtPk)
        }
        if (result.deviceIdentifier == null) {
            val txtId = info.attributes["deviceid"]?.toString(Charsets.UTF_8)
                ?.trimEnd { it.code == 0 }
                ?.takeIf { it.isNotEmpty() }
            if (txtId != null) result = result.copy(deviceIdentifier = txtId)
        }
        if (result.sourceVersion == null) {
            val txtVer = info.attributes["srcvers"]?.toString(Charsets.UTF_8)
                ?.trimEnd { it.code == 0 }
                ?.takeIf { it.isNotEmpty() }
            if (txtVer != null) result = result.copy(sourceVersion = txtVer)
        }
        return result
    }

    /** BT 会话交接点：拿到 0x5703/0x4301 后两条路径流程一致——绑 WiFi → AirPlay 配对 → SETUP → 推流。 */
    private fun onCarPlayEndpoint(
        endpoint: com.lizhihua.carplaysender.iap2.Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (!sessionStarting.compareAndSet(false, true)) {
            log(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
            return
        }
        log(
            ">>> [$source] 拿到接收端热点：SSID=${endpoint.ssid}；" +
                "密码=${if (endpoint.passphrase.isEmpty()) "(空)" else "已获取"}",
        )
        runOnUiThread { ssidInput.setText(endpoint.ssid) }
        // ctrl 广播已在 onWifiCredentials（0x5703 到达时）启动；这里兜底再确保一次
        // （SSID 变化会触发重新注册，见 ensureForNetwork）
        ctrlAdvertiser.ensureForNetwork(endpoint.ssid)
        // 真车兜底：0x4301 可能不带 IPv4（只有 IPv6 或空）——按真 iPhone 行为用 mDNS
        // 解析车机 _airplay._tcp 拿地址（车机热点已在连就必须能发现，否则说明不在车机网络）
        val effectiveEndpoint = resolveEndpointHost(endpoint, source)
        // 若手机已加入过接收端热点：等 ctrl 探测完成（实测 +1s~+1.3s）→
        // 主动断开 BT 控制会话（复现接收端唯一应答 RTSP 的状态）→ 执行 AirPlay 配对
        wifiConnector.bindCurrentWifi { bound ->
            if (!bound) {
                log(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${effectiveEndpoint.ssid}\" 后断开重连")
                sessionStarting.set(false)
                return@bindCurrentWifi
            }
            Thread({
                try {
                Thread.sleep(1_600) // 等 ctrl 探测（实测 +1.0~+1.3s）
                fun newClient() = com.lizhihua.carplaysender.airplay.AirPlayClient(
                    effectiveEndpoint,
                    log = { line -> log(line) },
                    context = applicationContext,
                )
                retirePreviousSession()
                // 会话静默降噪（2026-10-10）：从交接点起暂停其它接收端的出站连接探测
                // （典型：连着 DiPlay 测试台时不再噪音式探测 Audi）。会话结束/断开时解除。
                com.lizhihua.carplaysender.bt.BtSessionGate.sessionActive = true
                // 第一阶段：保持 BT 在位直接配对 + SETUP（协议上正确的形态）
                var client = newClient()
                currentClient = client
                var keys = client.runPairing()
                if (keys == null && !client.pairSetupRejected && client.attemptedPairing) {
                    // 第二阶段（仅测试台 DiPlay）：复现「BT 控制已结束」状态再试一次。
                    // 真车（Audi）绝不能断 BT——真无线 CarPlay 的 BT 控制通道全程保活，
                    // 断开会让车机拆掉整轮 AirPlay 窗口（实车实测）。
                    // 门槛：仅当本轮真的连上过接收端 RTSP（attemptedPairing）才用此 hack；
                    // 端点未解析（无 IP）/端口未开时绝不值得断 BT（2026-10-11 踩过：
                    // mDNS 失败也触发 detach，白白拆掉车机窗口）。
                    detachActiveBtSessions()
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
                                // 记录会话凭据：投影授权晚于连接时用于动态补启音频
                                currentSharedSecret = keys.sharedSecret
                                currentAudioHost = effectiveEndpoint.ipAddresses.firstOrNull { it.contains('.') }
                                client.connectEventChannel(keys.sharedSecret, setup) { command ->
                                    when (command["type"]) {
                                        "hidSendReport" -> {
                                            val uuid = command["uuid"]?.toString()
                                            val report = command["hidReport"] as? ByteArray
                                            val contacts = if (uuid != null && report != null) {
                                                com.lizhihua.carplaysender.input.TouchReports.parseByUuid(uuid, report)
                                            } else {
                                                null
                                            }
                                            if (contacts != null) {
                                                touchLogCount++
                                                if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
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

                                        "iAPSendMessage" -> {
                                            // 事件通道上的 iAP2 虚拟链路字节（车机先发 marker 开场）。
                                            // 喂给链路引擎：握手 → EA 会话 → 触摸 HID。
                                            val params = command["params"] as? Map<*, *>
                                            val data = params?.get("data") as? ByteArray
                                            if (data == null) {
                                                log("EVENT: iAPSendMessage 无 data（params=$params）")
                                            } else {
                                                iapEventMessageCount++
                                                client.onIapEventBytes(data)
                                            }
                                        }

                                        "changeModes" -> {
                                            // 车机请求资源仲裁（如用户在 MMI 上点了手机）：
                                            // 回 modesChanged 声明手机持有主屏/主音频
                                            log("EVENT: 车机请求 changeModes → 回 modesChanged 接管资源")
                                            client.assertModes()
                                        }

                                        else -> log("EVENT: 命令 type=${command["type"]}")
                                    }
                                }
                                // 资源声明（must）：真车对未声明资源的屏幕流 SETUP 直接 400
                                client.assertModes()
                                // HID 输入模式武装：/info hidDevices（奥迪字符串 "0"/"1"，触摸="1"）。
                                // display features=0x08（高保真触摸）已声明，缺这步车机不发 hidSendReport。
                                setup.hidDeviceUuids.forEach { uuid ->
                                    client.sendHidSetInputMode(uuid, mode = 0)
                                }
                                val screen = client.runScreenStream(keys.sharedSecret, setup.displayUuid)
                                if (screen != null) {
                                    val host = effectiveEndpoint.ipAddresses.firstOrNull { it.contains('.') }
                                    // 心跳双保险（缺失任一都会被车机 ~10s 看门狗拆会话，2026-10-11 实测）：
                                    //   keepAlive UDP ping（车机 SETUP 返回的 keepAlivePort，10s 周期）
                                    //   + RTSP POST /feedback（1s 周期，catplay FEEDBACK_INTERVAL）
                                    if (host != null && setup.keepAlivePort != null) {
                                        client.startKeepAlive(host, setup.keepAlivePort!!)
                                    }
                                    client.startFeedbackLoop()
                                    // iAP 隧道（type-130）：车机→手机的 iAP2 EA 数据（触摸 HID 载体）。
                                    // 建立失败不阻塞会话（触摸不可用但视频照常）。
                                    val tunnel = client.runIapTunnel(keys.sharedSecret)
                                    tunnel?.onIapMessage = { frame ->
                                        // 触摸证据采集：先全量记录，格式确认后接 TouchRouter
                                        if (frame.messageId != 0x0000) {
                                            val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                                            log(
                                                "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                    "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B" +
                                                    if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                            )
                                        }
                                    }                                    // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                                    // 协议顺序：全部 SETUP 完成后再 RECORD 激活会话；失败不影响主链路。
                                    startAudioChannel(client, keys.sharedSecret, host)
                                    client.record()
                                    if (host != null) {
                                        videoSender = com.lizhihua.carplaysender.video.VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            streamKey = screen.streamKey,
                                            width = setup.displayWidth,
                                            height = setup.displayHeight,
                                            log = { line -> log(line) },
                                            onEnded = {
                                                // 会话真实结束：解除静默降噪（客户端恢复探测/重连）
                                                log(">>> CarPlay 会话结束（推流终止）——恢复蓝牙探测")
                                                com.lizhihua.carplaysender.bt.BtSessionGate.sessionActive = false
                                            },
                                        )
                                        val density = 420
                                        videoSender?.start { surface ->
                                            // 唯一模式：隐形屏（双屏启动器：dock + 应用屏）
                                            val source =
                                                com.lizhihua.carplaysender.capture.HiddenDisplaySource(
                                                    applicationContext,
                                                    log = { line -> log(line) },
                                                )
                                            currentHiddenSource = source
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
                } catch (error: Throwable) {
                    // 配对线程任何异常都不能留下「已有会话在处理中」的卡死状态
                    log("AIRPLAY: 配对线程异常 - ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    sessionStarting.set(false)
                    // 配对未成功建立推流（fail/异常/start 失败提前退出）时解除静默降噪，
                    // 否则 BT-C 会永久停在门闸外、连真车路径失效。
                    // 推流活跃时保持关闭，由 VideoSender.onEnded（推流终止）负责解除。
                    if (videoSender?.isRunning != true) {
                        com.lizhihua.carplaysender.bt.BtSessionGate.sessionActive = false
                    }
                }
            }, "airplay-pairing").start()
        }
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
            val hadProjection = com.lizhihua.carplaysender.service.CarPlayService.mediaProjection != null
            retirePreviousSession()
            com.lizhihua.carplaysender.service.CarPlayService.stop(this)
            bluetoothServer.stop()
            bluetoothClient.stop()
            ctrlAdvertiser.stop()
            wifiConnector.release()
            if (hadProjection) {
                // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
                // 停服务会连带失效授权——下次连接需重新点「申请投屏权限」
                log("已手动断开并停止保活服务（常亮已恢复）；投屏授权已随服务停止失效，下次连接如需声音请重新申请")
            } else {
                log("已手动断开并停止保活服务（常亮已恢复）")
            }
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

        com.lizhihua.carplaysender.input.TouchRouter.logSink = { line -> log(line) }
        com.lizhihua.carplaysender.launcher.VirtualLauncher.logSink = { line -> log(line) }
        com.lizhihua.carplaysender.shizuku.CarInputClient.logSink = { line -> log(line) }

        log("CarPlay Sender Phase 0 就绪")
        log("真车：与车机蓝牙配对 + 手机加入车机热点 -> 点「启动连接」（自动主动连入车机）")
        log("测试台：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 点「启动连接」等待回连")
    }

    private fun log(message: String) {
        // 镜像到 logcat：真机排查（息屏/断链类问题）必须能从 adb 侧看到协议日志
        android.util.Log.i("CarPlaySender", message)
        appendDebugFile(message)
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
        // 注意：不再在这里拆连接——Activity 销毁（如划掉任务）时连接由前台服务保活；
        // 显式断开请用「断开」按钮。
    }
}
