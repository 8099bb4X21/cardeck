package com.lizhihua.carplaysender

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程级会话中枢：**日志缓冲**与**会话管线所有权**的唯一持有者。
 *
 * 背景（2026-10-11 用户实测发现三个连带问题）：投屏管线运行在 MainActivity 派生线程里，
 * 划掉界面（Activity 销毁）后进程被前台服务保活、管线继续跑；但重进应用创建的是**新
 * Activity 实例**——
 *  1. 老线程的日志写进老实例（已销毁）的 UI → 新界面"日志断联"（文件日志实际未断）
 *  2. 「断开连接」操作新实例的新对象 → 老管线无人持有，断不掉
 *  3. 重开连接会建第二套对象 → 双会话风险
 *
 * 本对象把这些状态提升为进程级单例：Activity 只是"挂屏"（订阅日志、转发按钮），
 * 销毁/重建对其无影响。会话逻辑线程通过 [appContext] 运行，不依赖任何 Activity。
 */
object SessionController {

    // ---- 日志缓冲（进程级；Activity 订阅渲染）----

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 当前挂屏 Activity 的渲染回调（收到完整日志文本）；Activity onDestroy 时清空。 */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** 「监听日志」开关（由 Activity 从偏好读取后同步；关闭时不采集不渲染）。 */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** Activity onCreate 时调用：登记 applicationContext 并创建会话对象（幂等）。 */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
    }

    /** 追加一行日志：logcat + 文件 + 进程级缓冲 + 挂屏渲染。任意线程可调。 */
    fun appendLog(message: String) {
        android.util.Log.i("CarPlaySender", message)
        appContext?.let { com.lizhihua.carplaysender.util.DebugFileLog.append(it, message) }
        if (!logEnabled) return
        synchronized(logLock) {
            logBuilder.append('[').append(timestamp.format(Date())).append("] ")
                .append(message).append('\n')
            if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
        }
        logListener?.invoke(logText())
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
        logListener?.invoke("")
    }

    // ---- 会话对象（进程级所有权；Activity 重建不影响）----

    private var wifiConnector: com.lizhihua.carplaysender.net.WifiCarPlayConnector? = null
    private var bluetoothServer: com.lizhihua.carplaysender.bt.Iap2BluetoothServer? = null
    private var bluetoothClient: com.lizhihua.carplaysender.bt.Iap2BluetoothClient? = null
    private var ctrlAdvertiser: com.lizhihua.carplaysender.net.CarPlayCtrlAdvertiser? = null

    @Volatile
    var videoSender: com.lizhihua.carplaysender.video.VideoSender? = null

    @Volatile
    var audioSender: com.lizhihua.carplaysender.audio.AudioSender? = null

    @Volatile
    var currentClient: com.lizhihua.carplaysender.airplay.AirPlayClient? = null

    @Volatile
    var currentHiddenSource: com.lizhihua.carplaysender.capture.HiddenDisplaySource? = null

    /** 当前会话的共享密钥与接收端地址：投影授权晚于连接时，动态补启音频用。 */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** 本次会话是否在车机侧播放声音（由「启动连接」弹窗选择）。 */
    @Volatile
    var audioEnabledForSession: Boolean = false

    /** 会话建立互斥（端点交接 → 配对 → 推流全程只允许一条）。 */
    val sessionStarting = AtomicBoolean(false)

    /** 用户取消连接：配对线程在关键检查点看到后中止（mDNS/配对等待无法中途打断，粒度秒级）。 */
    @Volatile
    var connectCancelled: Boolean = false

    /**
     * 当前连接阶段的一句话提示（英雄卡副标题用，详细过程看日志）。
     * 由各阶段显式设置；null 时 UI 显示默认文案。
     */
    @Volatile
    var phaseHint: String? = null

    /** 取消「连接中」：停链路 + 置取消标志，卡在错误网络/等待回连时用户可主动脱身。 */
    fun cancelConnecting(context: Context) {
        connectCancelled = true
        appendLog("SESSION: 用户取消连接——正在停止蓝牙/WiFi 链路")
        disconnect(context)
        phaseHint = "正在取消连接…"
    }

    /** 挂屏 UI 钩子：SSID 变化时通知当前 Activity 回填输入框（可空）。 */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector = com.lizhihua.carplaysender.net.WifiCarPlayConnector(context) { line -> appendLog(line) }
            val wifiCredentialHook: (com.lizhihua.carplaysender.iap2.Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            bluetoothServer = com.lizhihua.carplaysender.bt.Iap2BluetoothServer(
                context,
                log = { line -> appendLog(line) },
                onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "车机回连") },
                onWifiCredentials = wifiCredentialHook,
            )
            bluetoothClient = com.lizhihua.carplaysender.bt.Iap2BluetoothClient(
                context,
                log = { line -> appendLog(line) },
                onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "主动连车机") },
                onWifiCredentials = wifiCredentialHook,
            )
            ctrlAdvertiser = com.lizhihua.carplaysender.net.CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }
        }
    }

    /** 状态行文本（Activity 渲染 statusText 用）。 */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** 会话是否活跃（推出流进程或 BT 任一在跑）。 */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            bluetoothServer?.isRunning == true ||
            bluetoothClient?.isRunning == true

    /**
     * 「启动连接」主体（权限检查由 Activity 完成后调用）：前台保活服务 + 双向蓝牙。
     * 幂等：已有会话时 BT 组件的 start() 自行短路，不会双开。
     */
    fun startConnection(context: Context) {
        connectCancelled = false
        phaseHint = "蓝牙就绪，等待车机回连…"
        when (com.lizhihua.carplaysender.shizuku.ShizukuGate.state(context)) {
            com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY ->
                appendLog("SHIZUKU: 就绪（触摸注入可用）")

            com.lizhihua.carplaysender.shizuku.ShizukuGate.State.UNAUTHORIZED -> {
                appendLog("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗点允许后重连")
                com.lizhihua.carplaysender.shizuku.ShizukuGate.requestPermission()
            }

            com.lizhihua.carplaysender.shizuku.ShizukuGate.State.NOT_RUNNING ->
                appendLog("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」")

            com.lizhihua.carplaysender.shizuku.ShizukuGate.State.NOT_INSTALLED ->
                appendLog("SHIZUKU: 未安装（隐形屏模式需要）")
        }
        com.lizhihua.carplaysender.service.CarPlayService.start(context)
        // 真车路径：手机主动连车机（SDP …caff 配件服务）
        // 测试台路径：监听手机侧 iPhone UUID，等 DiPlay/xcertplay 回连
        bluetoothClient?.start()
        bluetoothServer?.start()
    }

    /** 「断开连接」（可在任意 Activity 实例上调用——操作的是真管线对象）。 */
    fun disconnect(context: Context) {
        val hadProjection = com.lizhihua.carplaysender.service.CarPlayService.mediaProjection != null
        retireSession()
        phaseHint = null
        com.lizhihua.carplaysender.service.CarPlayService.stop(context)
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        wifiConnector?.release()
        if (hadProjection) {
            // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
            // 停服务会连带失效授权——下次连接需重新点「申请投屏权限」
            appendLog("已手动断开并停止保活服务（常亮已恢复）；投屏授权已随服务停止失效，下次连接如需声音请重新申请")
        } else {
            appendLog("已手动断开并停止保活服务（常亮已恢复）")
        }
    }

    /** 会话结束/新周期开始前彻底退役上一代会话（旧管线泄漏会累积编码器/虚拟显示器，最终 dequeueBuffer -19）。 */
    fun retireSession() {
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
        // 「强制应用横屏」覆盖随会话结束统一复原（应用回到手机侧正常竖屏形态）
        appContext?.let { ctx ->
            runCatching {
                val restored = com.lizhihua.carplaysender.launcher.settings.AppOrientationCompat.resetAll(ctx)
                if (restored > 0) appendLog("ORIENTATION: 已复原 $restored 个应用的显示设置")
            }
        }
    }

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client = currentClient ?: run {
            appendLog("MODE: 投屏已就绪（当前无会话——下次连接时自动启用音频）")
            return
        }
        val secret = currentSharedSecret ?: return
        if (audioSender?.isRunning == true) return
        appendLog("MODE: 会话进行中，动态补启音频通道…")
        startAudioChannel(context, client, secret, currentAudioHost)
    }

    /**
     * 0x5703 到达（拿到车机热点凭据）立即执行——真车 0x4301 的前置条件：
     * 车机在 WiFi 网络上发现手机的 `_carplay-ctrl._tcp`（TXT id=真实蓝牙 MAC）后
     * 主动发 `GET /ctrl-int/1/connect` 邀请，邀请被接受才发 0x4301。
     * 因此这里必须：绑车机 WiFi → 广播 ctrl；晚于 0x4300 则双方互等死锁（Audi 实测）。
     */
    fun onWifiCredentials(
        wifi: com.lizhihua.carplaysender.iap2.Iap2SenderSession.WifiConfiguration,
    ) {
        ssidSink?.invoke(wifi.ssid)
        phaseHint = "拿到车机热点凭据，绑定 WiFi…"
        appendLog(">>> 拿到车机热点凭据（0x5703）——立即绑定 WiFi + 广播 _carplay-ctrl")
        // 先占位注册（此时可能尚未绑定到目标网络）；绑定成功后再 force 重注册，
        // 保证 mDNS 组播落在车机所在网络（真车实测必需：注册早于绑定会广播到错误网络）
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        wifiConnector?.bindCurrentWifi { bound ->
            if (!bound) {
                phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
                appendLog(">>> 手机不在车机热点 \"${wifi.ssid}\" —— 请到 系统设置>WLAN 加入后重新连接")
            } else {
                // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
                ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true)
                appendLog(">>> WiFi 已绑定车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请（随后才回 0x4301）")
            }
        }
    }

    /** 复现「BT 控制已结束」状态：断开当前任一路径的 iAP2 控制会话（DiPlay 只在此状态应答 RTSP）。 */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    // ---- 以下方法体自 MainActivity 原样迁入（会话逻辑不依赖 Activity）----

    /**
     * 音频通道（手机 → 车机，RTP over UDP）：SETUP type=100 协商 → 播放捕获 → ChaCha 封包推流。
     *
     * 依赖 MediaProjection（AudioPlaybackCapture 的授权来源），且需用户在「启动连接」
     * 弹窗中选择「开启声音」——音视频同时推流显著增加负载（实测会卡顿/断连）。
     */
    fun startAudioChannel(
        context: Context,
        client: com.lizhihua.carplaysender.airplay.AirPlayClient,
        sharedSecret: ByteArray,
        host: String?,
    ) {
        if (!audioEnabledForSession) {
            appendLog("AUDIO: 本次连接未开启车机侧声音（「启动连接」弹窗选择）——仅推视频")
            return
        }
        if (host == null) {
            appendLog("AUDIO: 无接收端 IPv4 地址，跳过音频通道")
            return
        }
        val projection = com.lizhihua.carplaysender.service.CarPlayService.mediaProjection
        if (projection == null) {
            appendLog(
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
                log = { line -> appendLog(line) },
                context = context.applicationContext,
            )
            audioSender = sender
            if (!sender.start(projection)) {
                audioSender = null
            }
        } catch (error: Exception) {
            appendLog("AUDIO: 通道启动异常 - ${error.message}")
            audioSender = null
        }
    }

    /**
     * 端点补全：0x4301 缺失/无 IPv4 时用 mDNS 解析车机 `_airplay._tcp` 合成端点
     * （真 iPhone 同款发现路径，也是 Audi MIB3 唯一路径——它不发 0x4301）。
     * 测试台端点自带 IPv4 与端口，本方法秒过、行为不变。
     */
    private fun resolveEndpointHost(
        context: Context,
        endpoint: com.lizhihua.carplaysender.iap2.Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): com.lizhihua.carplaysender.iap2.Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val needHost = !effective.ipAddresses.any { it.contains('.') && !it.contains(':') }
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        phaseHint = "mDNS 解析车机地址…"
        appendLog(">>> [$source] 0x4301 端点不完整（port=${effective.airPlayPort} ips=${effective.ipAddresses}），mDNS 解析车机 _airplay._tcp…")
        // 车机网段前缀（本机 IP 前 3 段）：手机若同时开热点共享（如给电脑），mDNS 会跨网
        // 发现电脑的 AirPlay 接收器（macOS 自带）——2026-10-11 实测把 MacBook 10.180.48.59
        // 当成车机连了 120s。非本网段的一律跳过。
        val carSubnetPrefix = wifiConnector?.localIpv4Address()?.substringBeforeLast('.')?.plus(".")
        val latch = java.util.concurrent.CountDownLatch(1)
        val found = java.util.concurrent.atomic.AtomicReference<android.net.nsd.NsdServiceInfo?>(null)
        val browser = com.lizhihua.carplaysender.net.CarPlayServiceBrowser(
            context,
            log = { line -> appendLog(line) },
        ) { info ->
            if (found.get() != null) return@CarPlayServiceBrowser
            val ip = info.host?.hostAddress
            if (ip == null || !ip.contains('.') || ip.contains(':')) return@CarPlayServiceBrowser
            if (carSubnetPrefix != null && !ip.startsWith(carSubnetPrefix)) {
                val txtId = info.attributes["deviceid"]?.toString(Charsets.UTF_8)?.trimEnd { it.code == 0 }
                appendLog(">>> [$source] 跳过非车机 _airplay 服务 ${info.serviceName}: $ip" +
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
            appendLog(">>> [$source] mDNS 第 $round 轮未发现 _airplay._tcp——重试")
        }
        browser.stop()
        val info = found.get() ?: run {
            // 兜底：热点场景车机 IP = 网关（Audi 实测 .1，但取网关更通用）。
            // mDNS 在部分网络/机型上不稳定（2026-10-11 实测车机 mDNS 时有时无）；
            // 网关地址 + 默认端口 7000 直接连，AirPlay 端口是固定约定。
            val gateway = wifiConnector?.gatewayAddress()
            if (gateway != null && gateway.contains('.') && !gateway.contains(':')) {
                appendLog(">>> [$source] mDNS 未发现，兜底使用网关地址 $gateway:7000（车机热点网关=车机）")
                return effective.copy(
                    ipAddresses = listOf(gateway) + effective.ipAddresses,
                    airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                )
            }
            appendLog(">>> [$source] mDNS 未发现车机 AirPlay 服务（车机可能尚未启动无线会话）")
            return effective
        }
        val address = info.host?.hostAddress
        var result = effective
        if (address != null && address.contains('.') && !address.contains(':')) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            appendLog(">>> [$source] mDNS 解析到车机地址 $address:${info.port}")
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
        val context = appContext ?: return
        if (!sessionStarting.compareAndSet(false, true)) {
            appendLog(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
            return
        }
        appendLog(
            ">>> [$source] 拿到接收端热点：SSID=${endpoint.ssid}；" +
                "密码=${if (endpoint.passphrase.isEmpty()) "(空)" else "已获取"}",
        )
        phaseHint = "蓝牙已连上车机，解析热点地址…"
        ssidSink?.invoke(endpoint.ssid)
        // ctrl 广播已在 onWifiCredentials（0x5703 到达时）启动；这里兜底再确保一次
        // （SSID 变化会触发重新注册，见 ensureForNetwork）
        ctrlAdvertiser?.ensureForNetwork(endpoint.ssid)
        // 真车兜底：0x4301 可能不带 IPv4（只有 IPv6 或空）——按真 iPhone 行为用 mDNS
        // 解析车机 _airplay._tcp 拿地址（车机热点已在连就必须能发现，否则说明不在车机网络）
        val effectiveEndpoint = resolveEndpointHost(context, endpoint, source)
        // 若手机已加入过接收端热点：等 ctrl 探测完成（实测 +1s~+1.3s）→
        // 主动断开 BT 控制会话（复现接收端唯一应答 RTSP 的状态）→ 执行 AirPlay 配对
        wifiConnector?.bindCurrentWifi { bound ->
            if (!bound) {
                phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${endpoint.ssid}"
                appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${effectiveEndpoint.ssid}\" 后断开重连")
                sessionStarting.set(false)
                return@bindCurrentWifi
            }
            Thread({
                try {
                if (connectCancelled) {
                    appendLog("AIRPLAY: 用户已取消——中止配对")
                    sessionStarting.set(false)
                    return@Thread
                }
                Thread.sleep(1_600) // 等 ctrl 探测（实测 +1.0~+1.3s）
                if (connectCancelled) {
                    appendLog("AIRPLAY: 用户已取消——中止配对")
                    sessionStarting.set(false)
                    return@Thread
                }
                fun newClient() = com.lizhihua.carplaysender.airplay.AirPlayClient(
                    effectiveEndpoint,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                )
                retireSession()
                // 会话静默降噪（2026-10-10）：从交接点起暂停其它接收端的出站连接探测
                // （典型：连着 DiPlay 测试台时不再噪音式探测 Audi）。会话结束/断开时解除。
                com.lizhihua.carplaysender.bt.BtSessionGate.sessionActive = true
                // 第一阶段：保持 BT 在位直接配对 + SETUP（协议上正确的形态）
                phaseHint = "AirPlay 配对中…"
                var client = newClient()
                currentClient = client
                var keys = client.runPairing()
                if (connectCancelled) {
                    retireSession()
                    appendLog("AIRPLAY: 用户已取消——中止配对")
                    sessionStarting.set(false)
                    return@Thread
                }
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
                    if (connectCancelled) {
                        retireSession()
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@Thread
                    }
                }
                        if (keys != null) {
                            phaseHint = "配对完成，建立 CarPlay 会话…"
                            appendLog(
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
                                                if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                    appendLog(
                                                        "EVENT: 触摸 " + contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                    )
                                                }
                                                touchLogCount++
                                                contacts.forEach {
                                                    com.lizhihua.carplaysender.input.TouchRouter.onContact(
                                                        context.applicationContext,
                                                        it,
                                                    )
                                                }
                                            } else if (report != null) {
                                                appendLog("EVENT: 其他 HID 报告 uuid=$uuid bytes=${report.size}")
                                            }
                                        }

                                        "iAPSendMessage" -> {
                                            // 事件通道上的 iAP2 虚拟链路字节（车机先发 marker 开场）。
                                            // 喂给链路引擎：握手 → EA 会话 → 触摸 HID。
                                            val params = command["params"] as? Map<*, *>
                                            val data = params?.get("data") as? ByteArray
                                            if (data == null) {
                                                appendLog("EVENT: iAPSendMessage 无 data（params=$params）")
                                            } else {
                                                iapEventMessageCount++
                                                client.onIapEventBytes(data)
                                            }
                                        }

                                        "changeModes" -> {
                                            // 车机请求资源仲裁（如用户在 MMI 上点了手机）：
                                            // 回 modesChanged 声明手机持有主屏/主音频
                                            appendLog("EVENT: 车机请求 changeModes → 回 modesChanged 接管资源")
                                            client.assertModes()
                                        }

                                        else -> appendLog("EVENT: 命令 type=${command["type"]}")
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
                                            appendLog(
                                                "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                    "(${com.lizhihua.carplaysender.iap2.Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B" +
                                                    if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                            )
                                        }
                                    }
                                    // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                                    // 协议顺序：全部 SETUP 完成后再 RECORD 激活会话；失败不影响主链路。
                                    startAudioChannel(context, client, keys.sharedSecret, host)
                                    client.record()
                                    if (host != null) {
                                        videoSender = com.lizhihua.carplaysender.video.VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            streamKey = screen.streamKey,
                                            width = setup.displayWidth,
                                            height = setup.displayHeight,
                                            log = { line -> appendLog(line) },
                                            onEnded = {
                                                // 会话真实结束：解除静默降噪（客户端恢复探测/重连）
                                                appendLog(">>> CarPlay 会话结束（推流终止）——恢复蓝牙探测")
                                                com.lizhihua.carplaysender.bt.BtSessionGate.sessionActive = false
                                                // 车机掉线类结束（无用户断开操作）：同样复原横屏覆盖
                                                appContext?.let { ctx ->
                                                    runCatching {
                                                        com.lizhihua.carplaysender.launcher.settings.AppOrientationCompat.resetAll(ctx)
                                                    }
                                                }
                                            },
                                        )
                                        val density = 420
                                        videoSender?.start { surface ->
                                            // 唯一模式：隐形屏（双屏启动器：dock + 应用屏）
                                            val source =
                                                com.lizhihua.carplaysender.capture.HiddenDisplaySource(
                                                    context.applicationContext,
                                                    log = { line -> appendLog(line) },
                                                )
                                            currentHiddenSource = source
                                            val ok = source.attach(
                                                surface,
                                                setup.displayWidth,
                                                setup.displayHeight,
                                                density,
                                            )
                                            appendLog(
                                                "MODE: 隐形屏 ${if (ok) "已启动（高德车机版优先）display=${source.displayId}" else "启动失败（Shizuku 就绪？）"}",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                } catch (error: Throwable) {
                    // 配对线程任何异常都不能留下「已有会话在处理中」的卡死状态
                    appendLog("AIRPLAY: 配对线程异常 - ${error.message ?: error.javaClass.simpleName}")
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

    /** 触摸/隧道事件低频计数（迁移自 MainActivity 字段，保持日志节流行为一致）。 */
    @Volatile
    private var touchLogCount = 0
    @Volatile
    private var iapEventMessageCount = 0
}
