package io.github.shaonialife.cardeck

import android.content.Context
import android.view.KeyEvent
import io.github.shaonialife.cardeck.airplay.AirPlayClient
import io.github.shaonialife.cardeck.audio.AudioSender
import io.github.shaonialife.cardeck.bt.BtProfiles
import io.github.shaonialife.cardeck.bt.BtSessionGate
import io.github.shaonialife.cardeck.bt.Iap2BluetoothClient
import io.github.shaonialife.cardeck.bt.Iap2BluetoothServer
import io.github.shaonialife.cardeck.bt.TargetDevicePrefs
import io.github.shaonialife.cardeck.capture.HiddenDisplaySource
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.TouchReports
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.net.CarPlayCtrlAdvertiser
import io.github.shaonialife.cardeck.net.CarPlayServiceBrowser
import io.github.shaonialife.cardeck.net.WifiCarPlayConnector
import io.github.shaonialife.cardeck.net.WifiSavedNetworks
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import io.github.shaonialife.cardeck.video.VideoSender
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

    /** 当前挂屏 Activity 的渲染回调（收到**本条新增日志行**，含时间戳前缀）；Activity onDestroy 时清空。 */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** 「监听日志」开关（由 Activity 从偏好读取后同步；关闭时不采集不渲染）。 */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** WiFi 等待用户操作的窗口（打开 WLAN 开关 / 手动加入热点的轮询上限）。 */
    private const val WLAN_WAIT_TIMEOUT_MS = 120_000L

    /** 加入确认弹窗的应答等待上限（无人应答按中止处理）。 */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /**
     * 等车机 ctrl 邀请的上限（事件驱动，收到即走）。
     * catplay 以邀请为连接触发点；实测邀请在 ctrl 重注册后 ~1.0-1.3s 到达。
     * 车机不发邀请时（旧机型）由「已发现 _airplay._tcp」提前放行，最坏也只等这么久。
     */
    private const val CTRL_INVITE_WAIT_MS = 4_000L

    /** 端点不完整时等 mDNS 缓存命中：绑网时已开始发现，通常几百毫秒内命中。 */
    private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L

    /**
     * 端点已到但手机还没切到车机热点时的等待上限。
     * 覆盖「自动加入（几秒）」与「用户手动加入（常见 10-30s）」两种路径；
     * 超时后明确提示并复位 sessionStarting，不留「永久正在连接」。
     */
    private const val CAR_WIFI_BIND_WAIT_MS = 45_000L

    /**
     * `requestNetwork(WIFI)` 等 onAvailable 的上限（秒）。
     * 用户正在系统设置里手动加入热点时，回调要等关联+DHCP 完成才来，实测可达 10s+。
     * 旧值 5s 会在成功前 116ms 误判失败并放弃整轮会话（见 bindCarWifiAndRegister）。
     */
    private const val WIFI_BIND_WAIT_S = 15L

    /**
     * 等用户确认「连接车机热点」系统弹窗的上限（应用专属网络）。
     * 系统确认框可能停留很久；用户取消连接时 [connectCancelled] 会即时退出。
     */
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 120_000L

    /** Activity onCreate 时调用：登记 applicationContext 并创建会话对象（幂等）。 */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
        // 横屏兼容写入失败的原因（user build 限制 / Shizuku 故障）只有这里能落盘：
        // 车机侧设置面板的 toast 只给用户看，排查需要日志。
        AppOrientationCompat.diagSink = ::appendLog
        // 主线程调用 shell 的告警（历史上两次真机 ANR 都源于此；HKS 下 logcat 不可读）
        Shell.violationSink = ::appendLog
    }

    /** 追加一行日志：logcat + 文件 + 进程级缓冲 + 挂屏渲染（增量行直送）。任意线程可调。 */
    fun appendLog(message: String) {
        // NUL 会写进日志文件：文本编辑器/文件管理器打不开（Read 工具也会判为二进制），
        // 用户反馈日志时等于白导。0x4301 的定长地址字段曾原样打印出多个 NUL
        // （2026-10-18 实测 216 个），这里替换成可见占位符——顺带让「字符串里有脏字节」
        // 在日志上直接可见，而不是隐形成 InetSocketAddress 的解析失败。
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        android.util.Log.i("CarPlaySender", clean)
        appContext?.let {
            DebugFileLog
                .append(it, clean)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date()) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // 增量行直送 UI：此前每行 toString 整个缓冲再 setText 全文，
        // 高频日志（触摸/帧）下是 UI 线程与 GC 的双重放大器
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /**
     * 派发系统媒体键（AudioManager 全局路由给**当前活跃媒体会话**，无需权限）。
     * 桌面音乐卡的按钮与车机/方向盘媒体键（Consumer HID）共用这一路径。
     */
    fun dispatchMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * 清空进程级日志缓冲（UI 刷新是调用方的事：显式 `renderConsole(logText())`）。
     * 历史上这里靠 `logListener?.invoke("")` 通知 UI——那是「每行全量 setText」时代的
     * 语义；改为增量 appendConsole 后空串等于追加空行，UI 原地不动（「清除按钮点了
     * 没反应」根因，2026-10-16）。不要恢复该调用：空串与真实日志行无法区分。
     */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- 会话对象（进程级所有权；Activity 重建不影响）----

    private var wifiConnector: WifiCarPlayConnector? = null

    /**
     * 当前已绑定到哪个车机热点（bindCarWifiAndRegister 成功后写入）。
     * 端点交接时若已绑定到同一 SSID，跳过二次 bindCurrentWifi——它内部先
     * release() 解绑，会打断刚注册好的 ctrl mDNS 广播并白等系统重连。
     */
    @Volatile
    private var boundCarSsid: String? = null

    /** 本次进程内已就「是否清除系统保存热点」询问过的 SSID（避免重试时重复弹窗）。 */
    private val forgetPromptHandled =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** WiFi 绑定互斥：auto-join 与端点交接两条路径可能同时绑定同一热点。 */
    private val wifiBindLock = Any()

    /** DCL 双检锁的可发布字段（无 volatile 的双检锁是坏发布——虽然当前仅主线程调用，别留隐患）。 */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /**
     * 常驻 `_airplay._tcp` 发现器（对照 catplay 的常驻 Bonjour cache）。
     * 加入热点即开始后台发现，交接端点时地址多半已就绪，不再现查现等。
     */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    @Volatile
    var videoSender: VideoSender? = null

    // ---- 「显示」运行时生效值（会话建立时快照；英雄卡展示运行时值而非偏好值）----

    /** 本会话流分辨率缩放百分比（按车机分辨率的百分比生成；100=原尺寸）。 */
    @Volatile
    var activeScalePercent: Int = 100

    /** 本会话目标帧率（合成节奏上限；「显示」设置运行中切换可实时生效）。 */
    @Volatile
    var activeTargetFps: Int = 30

    @Volatile
    var audioSender: AudioSender? = null

    @Volatile
    var currentClient: AirPlayClient? = null

    @Volatile
    var currentHiddenSource: HiddenDisplaySource? = null

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

    /**
     * 交接流程已在跑、但 AirPlayClient 尚未创建时收到的晚到端点（0x4301）。
     * 配对线程起步时取用一次后清空——此刻没有 client 可更新，丢了就白等到下一轮。
     */
    @Volatile
    private var pendingEndpointUpdate: Iap2SenderSession.CarPlayEndpoint? = null

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
        // 强制释放互斥：配对流程线程可能阻塞在不可中断的连接/mDNS 等待上（如手机
        // 不在热点网段时连热点 IP），finally 遥遥无期——用户显式取消即强制解锁，
        // 否则英雄卡永久「正在连接」（旧流程产出已被 retireSession 清理，无冲突）
        sessionStarting.set(false)
        phaseHint = "已取消连接"
    }

    /**
     * 最近一次 0x5703 的热点密码（仅内存，不落盘）。
     * 「车机热点 → 去连接」按钮复制到剪贴板用（手动加入兜底场景）。
     */
    @Volatile
    var lastWifiPsk: String? = null

    /** 挂屏 UI 钩子：SSID 变化时通知当前 Activity 回填输入框（可空）。 */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    /** 挂屏 UI 钩子：重要连接提示（WiFi 未开启/热点加入限制等）弹给用户看。 */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** 弹窗规格（加入热点确认等两按钮询问）。 */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
    )

    /**
     * 挂屏 UI 钩子：两按钮确认弹窗。
     * onChoice：true=positive；false=negative；null=无人应答/被关闭（调用方兜底）。
     */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // 应用专属网络被系统断开（车机熄火/离开范围）：明确告知用户，
                    // 而不是让英雄卡在「已连接」与失败之间静默摇摆
                    connector.onNetworkLost = { ssid ->
                        boundCarSsid = null
                        phaseHint = "车机热点已断开（$ssid）"
                        uiHintSink?.invoke("车机热点 \"$ssid\" 已断开——请重新启动连接")
                        appendLog(">>> 车机热点 \"$ssid\" 已断开——本次会话需重新连接")
                    }
                }
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // 目标车机动态读取：选择后下一轮连接尝试/下一次连入即生效，无需重启链路
            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "车机回连") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "车机回连") },
                    targetProvider = targetProvider,
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> onCarPlayEndpoint(endpoint, "主动连车机") },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> onLateEndpoint(endpoint, "主动连车机") },
                    targetProvider = targetProvider,
                )
            ctrlAdvertiser = CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // 手机双归属（连车机热点 + 自己开热点共享给电脑）时 mDNS 会跨网
                        // 发现电脑的 AirPlay 接收器（macOS 自带）——2026-10-11 实测把
                        // MacBook 当成车机连了 120s。非本网段一律不算车机。
                        val ip = info.host?.hostAddress ?: return@CarPlayServiceBrowser false
                        val prefix = wifiConnector?.localIpv4Address()?.substringBeforeLast('.')?.plus(".")
                        ip.contains('.') && !ip.contains(':') && (prefix == null || ip.startsWith(prefix))
                    },
                )
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
        when (
            ShizukuGate
                .state(context)
        ) {
            ShizukuGate.State.READY ->
                appendLog("SHIZUKU: 就绪（触摸注入可用）")

            ShizukuGate.State.UNAUTHORIZED -> {
                appendLog("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗点允许后重连")
                ShizukuGate
                    .requestPermission()
            }

            ShizukuGate.State.NOT_RUNNING ->
                appendLog("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」")

            ShizukuGate.State.NOT_INSTALLED ->
                appendLog("SHIZUKU: 未安装（隐形屏模式需要）")
        }
        CarPlayService
            .start(context)
        // 能力发布先于连接（catplay 的做法：CarPlayCtrlServer 在会话一开始就常驻广播
        // `_carplay-ctrl._tcp`，之后每 1s force_announce，收到车机邀请才建 AirPlay）。
        // 我们此前要等 0x5703 才注册，导致「车机发现手机」这一步总落在热点绑定之后，
        // 每轮多花 1~3 秒；现在启动连接即发布，换网时 ensureForNetwork 会自动重注册。
        ctrlAdvertiser?.resetInviteCount()
        // 后台发布：start() 首次要向 Shizuku 解析真实蓝牙 MAC（shell），不能在主线程做
        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        // 真车路径：手机主动连车机（SDP …caff 配件服务）
        // 测试台路径：监听手机侧 iPhone UUID，等 DiPlay/xcertplay 回连
        bluetoothClient?.start()
        bluetoothServer?.start()
        // 诊断（奥迪实测：手机与车机「蓝牙已连接」时车机不开放 CarPlay）：
        // 连之前把目标设备的蓝牙配置文件状态打出来，失败时一眼看出是不是这个原因。
        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-PROFILE: 连接前「${target.name}」的蓝牙配置文件状态=" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "未连接"),
            )
        }
    }

    /** 「断开连接」（可在任意 Activity 实例上调用——操作的是真管线对象）。 */
    fun disconnect(context: Context) {
        val hadProjection = CarPlayService.mediaProjection != null
        pendingEndpointUpdate = null
        retireSession()
        // BT 控制会话只在这里（用户终态）退役，socket close 让保活读循环立刻退出：
        // 否则它阻塞到对端超时（实测 ~24s）才结束，用户取消后英雄卡持续转圈。
        // 配对中绝不能断——DiPlay 在隧道 iAP2 就绪前看到 RFCOMM 断开即判
        // bring-up failed 并拆整轮无线栈（含推流中的 AirPlay 会话，2026-10-16 实测）。
        detachActiveBtSessions()
        phaseHint = null
        CarPlayService
            .stop(context)
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        endpointBrowser?.stop()
        wifiConnector?.release()
        boundCarSsid = null
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
        TouchRouter
            .reset()
        currentClient?.cancelled = true
        currentClient?.close()
        currentClient = null
        // 注意：这里绝不能 detach BT 控制会话。本方法在每轮配对起步时也会执行，
        // 而 DiPlay 在 WiFi 隧道就绪前看到 RFCOMM 断开即判 bring-up failed、
        // closeWirelessStack 拆掉整轮无线栈（Bonjour/热点/AirPlay 服务），把已建立
        // 甚至正在推流的 AirPlay 会话一起拆掉——「连上约 3 秒即断」循环的根因
        // （2026-10-16 实测）。BT 退役只在用户终态做，见 disconnect()。
        // 解除会话静默：出站探测恢复（下次会话建立时会再次置位）
        BtSessionGate.sessionActive = false
        // 「强制应用横屏」覆盖随会话结束统一复原（应用回到手机侧正常竖屏形态）。
        // 异步：每应用两次 am compat shell 往返，6 个应用实测 ~1.6s；此前同步跑在
        // 配对前，等于每次连接白等 1.6s（2026-10-18 实测）。执行器串行保证
        // 「先复原上一会话、后写入本会话」的顺序。
        appContext?.let { ctx -> AppOrientationCompat.resetAllAsync(ctx, ::appendLog) }
    }

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client =
            currentClient ?: run {
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
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        // 会话推流中收到的 0x5703 是冗余凭据（DiPlay 控制器周期重连会重发一轮
        // Phase 1，2026-10-15 实测「连上后自动断开」循环的推手之一）：此时再走绑定
        // 流程会 release+重绑进程网络（扰动正在推流的 socket）、force 重注册 ctrl
        // mDNS（DiPlay 视为手机重新上线、再拉一轮 RFCOMM）。只刷新密码缓存。
        if (videoSender?.isRunning == true) {
            lastWifiPsk = wifi.passphrase
            appendLog("0x5703 冗余凭据（会话推流中）——保持现有 WiFi 绑定与 ctrl 广播不变")
            return
        }
        ssidSink?.invoke(wifi.ssid)
        lastWifiPsk = wifi.passphrase
        phaseHint = "拿到车机热点凭据，绑定 WiFi…"
        appendLog(">>> 拿到车机热点凭据（0x5703）——立即绑定 WiFi + 广播 _carplay-ctrl")
        // ctrl 广播已在 startConnection 时发布（catplay：连接前保持发布能力在线）。
        // 这里登记 SSID 并让注册对齐一次（手机若已手动连在车机热点上、Shizuku 不可用
        // 时这是唯一一次重注册机会）；加入热点后 bindCarWifiAndRegister 还会 force
        // 重注册，把组播落到车机所在网络（真车实测必需）。
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: 广播已在启动连接时发布，此处对齐到热点 ${wifi.ssid}")
        // 绑定与切换统一走自动加入流程：bindCurrentWifi 是「任意 WiFi 即成功」的盲绑，
        // 手机连着公司 WiFi 时会误判已在车机热点（ctrl 广播落在错误网络 → 双方互等死锁），
        // 必须先核对真实 SSID 再决定绑定或切换
        autoJoinCarWifi(wifi)
    }

    /**
     * 校对真实 WiFi 并加入车机热点（Shizuku shell，Android 12+ `cmd wifi`）：
     *  1. `cmd wifi status` 查当前 SSID——已是车机热点直接绑定（快路径）；
     *  2. 否则 `cmd wifi connect-network` 保存并切换（免系统弹窗、免输密码；
     *     网络被系统保存，下次进入热点范围自动重连，契合上车即连）；
     *  3. 轮询 SSID 确认切换完成后再绑定，各环节检查 [connectCancelled]——
     *     用户取消后不得继续跑（否则 phaseHint 被覆盖、ctrl 广播被重新拉起，表现为「取消不了」）。
     * Shizuku 未就绪/连接失败时回退「系统 WLAN 手动加入」指引（密码可经「去连接」复制）。
     */
    private fun autoJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        Bg.run("wifi-auto-join") {
            val shellReady =
                appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
            // 1) WLAN 开关。shell 不可用时跳过检查——requestNetwork 会以 Unavailable 反馈。
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled()) return@run
            if (connectCancelled) return@run
            // 2) 首选应用专属网络（不影响 4G/5G、不会被系统切回、不需要 Shizuku）
            when (val result = joinCarWifiSpecifier(wifi, shellReady)) {
                is WifiCarPlayConnector.JoinResult.Joined -> Unit

                WifiCarPlayConnector.JoinResult.Unsupported -> {
                    appendLog("WIFI: 热点安全类型 ${wifi.securityType} 无法用应用专属网络表达——退回系统级加入")
                    legacyJoinCarWifi(wifi, shellReady)
                }

                WifiCarPlayConnector.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady)
            }
        }
    }

    /**
     * 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点，成功后进程网络已绑定。
     *
     * 先清掉系统里保存的同名热点：旧版本用 `cmd wifi connect-network` 存过它，
     * 只要还在系统配置里，系统就会自动全局连接——无互联网热点于是被当成默认网络，
     * 部分 ROM 连带断掉移动数据，正是本次要解决的场景。
     */
    private fun joinCarWifiSpecifier(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ): WifiCarPlayConnector.JoinResult {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        // 已保存到系统配置里才询问是否清除：不擅自删用户网络（有的场景就是要用
        // 车机流量/互联网，或用户希望系统自动连）。选择保留不影响本轮连接。
        if (shellReady) {
            promptForgetSavedNetworkIfAny(wifi.ssid)
            if (connectCancelled) return WifiCarPlayConnector.JoinResult.Unavailable
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…（不影响移动数据）"
        appendLog(
            ">>> 以应用专属网络加入 \"${wifi.ssid}\"（不保存为系统 WiFi，其它应用继续走 4G/5G）",
        )
        val result =
            synchronized(wifiBindLock) {
                val existing = connector.currentNetwork
                if (boundCarSsid == wifi.ssid && existing != null) {
                    appendLog("WIFI: 应用专属网络已在位——跳过重复加入")
                    WifiCarPlayConnector.JoinResult.Joined(existing)
                } else {
                    connector.joinCarPlayNetwork(
                        ssid = wifi.ssid,
                        passphrase = wifi.passphrase,
                        securityType = wifi.securityType,
                        timeoutMillis = SPECIFIER_JOIN_TIMEOUT_MS,
                        isCancelled = { connectCancelled },
                    )
                }
            }
        if (result is WifiCarPlayConnector.JoinResult.Joined) {
            boundCarSsid = wifi.ssid
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = connector.currentNetwork)
            appendLog(">>> WiFi 已加入车机热点（应用专属网络），ctrl 广播已重新注册，等待车机 ctrl 邀请")
        } else if (!connectCancelled) {
            appendLog("WIFI: 应用专属网络加入未成功（${result.javaClass.simpleName}）")
        }
        return result
    }

    /**
     * 系统里已保存同名热点时，询问用户是否清除（**不擅自删除**）。
     *
     * 为什么要问而不是直接删：保留也有正当用途——用户可能就想让系统自动连车机、
     * 或车机热点确实带互联网（部分车型提供流量）。删除只影响「系统会不会全局
     * 自动连它」，与本轮连接无关；选择「保留」时继续正常连接。
     *
     * 每个 SSID 每个进程只问一次：配对失败重试、端点交接重入都会再次走到这里，
     * 重复弹窗会挡住连接（2026-10-18 实测）。用户的选择在本次进程内记忆。
     *
     * 阻塞直到用户选择（[JOIN_DIALOG_TIMEOUT_S] 超时按「保留」处理——不做破坏性动作）。
     */
    private fun promptForgetSavedNetworkIfAny(ssid: String) {
        if (!forgetPromptHandled.add(ssid)) return
        val ids = savedNetworkIds(ssid)
        if (ids.isEmpty()) return
        appendLog(
            "WIFI: 系统里已保存热点 \"$ssid\"（netId=${ids.joinToString(",")}）" +
                "——询问用户是否清除（保留也能正常连接）",
        )
        val forget =
            askWifiJoinChoice(
                DialogSpec(
                    title = "已保存该车机热点",
                    message =
                        "系统里保存过 \"$ssid\"。保留会让手机在进入范围时自动连接它，" +
                            "若该热点没有互联网，可能导致手机无法使用移动数据。\n\n" +
                            "「清除」只是不再自动连接，不影响本次使用；" +
                            "如果车机热点本身提供网络，选「保留」。",
                    positive = "清除保存",
                    negative = "保留",
                ),
            )
        if (connectCancelled) return
        when (forget) {
            true -> {
                ids.forEach { id ->
                    val result = Shell.exec("cmd wifi forget-network $id", 5_000L)
                    appendLog(
                        "WIFI: 已按用户选择清除系统级保存的热点 \"$ssid\"（netId=$id ok=${result.ok}）",
                    )
                }
            }

            false -> appendLog("WIFI: 用户选择保留系统保存的 \"$ssid\"——继续以应用专属网络连接")

            // 无人应答（UI 不在前台）：按「保留」处理，绝不在用户未确认时删网络
            null -> appendLog("WIFI: 无人应答是否清除保存的网络——按保留处理（不改用户配置）")
        }
    }

    /** 查系统已保存的、SSID 与目标完全一致的网络 id（`cmd wifi list-networks`）。 */
    private fun savedNetworkIds(ssid: String): List<Int> {
        val listing = Shell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: 无法读取系统已保存网络（exit=${listing.exitCode}）——跳过检查")
            return emptyList()
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** WiFi 未开启时的引导与等待；true = 已开启可继续。 */
    private fun waitForWifiEnabled(): Boolean {
        val open =
            askWifiJoinChoice(
                DialogSpec(
                    title = "WiFi 未开启",
                    message = "连接车机热点需要打开手机 WLAN。是否打开 WLAN 设置？",
                    positive = "打开 WLAN 设置",
                    negative = "取消",
                ),
            )
        if (connectCancelled) return false
        when (open) {
            true -> {
                openWlanSettings()
                uiHintSink?.invoke("请在 WLAN 设置中打开 WiFi 开关")
            }

            false -> {
                phaseHint = "已取消——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 用户取消——自动加入中止（WiFi 未开启）")
                return false
            }

            null -> {
                phaseHint = "WiFi 未开启——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 弹窗无人应答——自动加入中止（WiFi 未开启）")
                return false
            }
        }
        var waited = 0
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && !connectCancelled) {
            if (waited == 0) {
                phaseHint = "等待打开 WLAN（最长 120 秒，取消连接可中止）"
                appendLog("WIFI: 等待用户打开 WLAN（最长 120 秒，取消连接可中止）")
            }
            Thread.sleep(2_000)
            waited += 2_000
        }
        if (connectCancelled) {
            phaseHint = "已取消连接"
            return false
        }
        if (shellWifiDisabled()) {
            phaseHint = "WiFi 未开启——无法连接车机热点"
            appendLog("WIFI: 等待超时 WiFi 仍未开启——自动加入中止，请打开 WLAN 后重新连接")
            return false
        }
        return true
    }

    /** 应用专属网络被系统拒绝/超时：让用户重试或转手动连接。 */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "未能加入车机热点",
                    message =
                        "系统没有允许加入 \"${wifi.ssid}\"（可能点了拒绝，或热点不在范围内）。\n\n" +
                            "「重试」会再次弹出系统确认框。也可以到 WLAN 设置手动连接——" +
                            "手动连接会把热点保存为系统网络，可能影响移动数据。",
                    positive = "重试",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        when (choice) {
            true -> {
                when (val retry = joinCarWifiSpecifier(wifi, shellReady)) {
                    is WifiCarPlayConnector.JoinResult.Joined -> Unit
                    WifiCarPlayConnector.JoinResult.Unsupported -> legacyJoinCarWifi(wifi, shellReady)
                    WifiCarPlayConnector.JoinResult.Unavailable -> {
                        phaseHint = "未能加入车机热点——请到 WLAN 设置手动连接"
                        appendLog("WIFI: 重试仍未加入 \"${wifi.ssid}\"——请到系统 WLAN 手动连接")
                    }
                }
            }

            false -> manualJoinCarWifi(wifi)

            null -> {
                phaseHint = "未能加入车机热点——请重新连接"
                appendLog("WIFI: 弹窗无人应答——自动加入中止")
            }
        }
    }

    /** 手动兜底：密码进剪贴板 + 跳 WLAN 设置，轮询用户是否连上。 */
    private fun manualJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration) {
        copyHotspotPsk(wifi.passphrase)
        openWlanSettings()
        phaseHint = "请在 WLAN 设置中连接 ${wifi.ssid}…"
        appendLog(
            "WIFI: 用户选择手动连接——等待 WLAN 连上 \"${wifi.ssid}\"（最长 120 秒）；" +
                "注意：手动连接会把该热点保存为系统网络，若它没有互联网可能影响移动数据",
        )
        val manualDeadline = android.os.SystemClock.uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < manualDeadline) {
            if (connectCancelled) return
            Thread.sleep(5_000)
            if (shellWifiStatus().second == wifi.ssid) {
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
        }
        phaseHint = "尚未连接 ${wifi.ssid}——自动加入已中止"
        appendLog("WIFI: 手动连接等待超时——自动加入中止")
    }

    /**
     * 旧路径（WEP 等 specifier 无法表达的安全类型）：`cmd wifi connect-network`
     * 系统级保存并切换。注意这会保存热点为系统网络——无互联网时可能影响移动数据，
     * 只作为兼容兜底。
     */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
    ) {
        if (!shellReady) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                ">>> 手机不在车机热点 \"${wifi.ssid}\" 且 Shizuku 未就绪（无法自动加入）——" +
                    "请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        val current = shellWifiStatus().second
        if (current == wifi.ssid) {
            appendLog("WIFI: 手机已在车机热点 \"$current\"——直接绑定")
            bindCarWifiAndRegister(wifi.ssid)
            return
        }
        if (connectCancelled) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "连接车机热点",
                    message =
                        "将自动加入 \"${wifi.ssid}\" 热点。该热点无互联网，系统可能自动切回原 WiFi。" +
                            "\n\n出现断连时，请到 WLAN 设置中主动连接 \"${wifi.ssid}\"（手动连接不会被切回）。",
                    positive = "继续自动加入",
                    negative = "打开 WLAN 设置",
                ),
            )
        if (connectCancelled) return
        if (choice == false) {
            manualJoinCarWifi(wifi)
            return
        }
        if (choice == null) {
            phaseHint = "未能加入车机热点——请重新连接"
            appendLog("WIFI: 弹窗无人应答——自动加入中止")
            return
        }
        phaseHint = "正在自动加入车机热点 ${wifi.ssid}…"
        appendLog(
            ">>> 手机当前 WiFi: ${current ?: "未连接"}（非车机热点）——" +
                "经 Shizuku 自动切换到 \"${wifi.ssid}\"",
        )
        if (connectCancelled) return
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val result =
            Shell
                .exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security", 10_000L)
        if (!result.ok) {
            phaseHint = "自动加入热点失败——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                "WIFI: connect-network 失败（${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }}）" +
                    "——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…若被切回原 WiFi，请在 WLAN 中手动连接"
        appendLog("WIFI: connect-network 已发起（自动保存网络，等待切换…）")
        // 系统切换是异步的（断开当前网/扫描/关联/DHCP，2-10 秒）：轮询 SSID 确认。
        repeat(20) {
            if (connectCancelled) {
                appendLog("WIFI: 自动加入已取消（用户断开）")
                return
            }
            val ssid = shellWifiStatus().second
            if (ssid == null) {
                // status 命令不可用（非 AOSP 系统）：退化为直接尝试绑定（无 SSID 校验）
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            if (ssid == wifi.ssid) {
                appendLog("WIFI: 已切到车机热点 ${wifi.ssid}")
                bindCarWifiAndRegister(wifi.ssid)
                return
            }
            Thread.sleep(800)
        }
        phaseHint = "未能在热点驻留——请到 WLAN 设置手动连接 ${wifi.ssid}"
        uiHintSink?.invoke("未能在热点驻留——请到 WLAN 设置中主动连接 ${wifi.ssid}")
        appendLog(
            ">>> WiFi 自动加入超时（可能被系统切回原 WiFi）——" +
                "请到 系统设置>WLAN 手动连接 \"${wifi.ssid}\"（手动连接不会被自动切回）",
        )
    }

    /**
     * 绑定当前 WiFi（应为车机热点）并重注册 ctrl 广播；绑定超时给出指引。
     *
     * 绑定动作用 [wifiBindLock] 串行化：auto-join 线程与端点交接线程都可能触发，
     * 并发调用 bindCurrentWifi 会互相 release（解绑瞬间 mDNS 广播直接失效）。
     *
     * 等待窗口 [WIFI_BIND_WAIT_S] 必须够长：`requestNetwork(WIFI)` 的 onAvailable
     * 在「手机还没连上任何 WiFi、用户正在系统设置里手动加入热点」时，要等到真正
     * 关联+DCHP 完成才回调。旧值 5s 在奥迪实测中是致命竞态——20:02:09.352 判定
     * 「不在热点」放弃整轮会话，20:02:09.467（**116ms 后**）onAvailable 就到了并
     * 绑定成功；用户被迫多等一轮 BT 重连（+13s）。宁可可中断地多等，也不要假失败。
     *
     * @return 进程网络是否绑定成功
     */
    private fun bindCarWifiAndRegister(ssid: String): Boolean {
        var bound = false
        synchronized(wifiBindLock) {
            // 另一个路径已经绑定同一热点：别再走一遍 release+requestNetwork
            if (boundCarSsid == ssid && wifiConnector?.currentNetwork != null) {
                bound = true
            } else {
                val latch = java.util.concurrent.CountDownLatch(1)
                wifiConnector?.bindCurrentWifi { ok ->
                    bound = ok
                    latch.countDown()
                }
                // 分片等待：期间用户取消要能立刻退出，不必等满窗口
                val deadline = android.os.SystemClock.uptimeMillis() + WIFI_BIND_WAIT_S * 1_000
                while (!connectCancelled &&
                    bound.not() &&
                    android.os.SystemClock.uptimeMillis() < deadline
                ) {
                    if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                }
                if (bound) boundCarSsid = ssid
            }
        }
        if (bound && !connectCancelled) {
            phaseHint = "已加入车机热点"
            // 关键时机：bindProcessToNetwork 完成后再注册，车机才能看到 ctrl 广播
            ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            appendLog(">>> WiFi 已绑定车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请（随后才回 0x4301）")
        } else if (!connectCancelled) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 $ssid"
            appendLog(
                ">>> 等待 ${WIFI_BIND_WAIT_S}s 仍未绑定到 \"$ssid\"——" +
                    "请在 系统设置>WLAN 手动加入该热点后重新连接",
            )
        }
        return bound
    }

    /**
     * 弹窗询问用户（阻塞调用线程直到选择/关闭/90 秒超时）：
     * true=positive；false=negative；null=无人应答（调用方决定兜底）。
     */
    private fun askWifiJoinChoice(spec: DialogSpec): Boolean? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var choice: Boolean? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val sink = dialogSink
            if (sink == null) {
                // 无 UI 宿主（Activity 销毁窗口）：宁可中止也不默认替用户决定加入
                latch.countDown()
                return@post
            }
            sink(spec) { result ->
                choice = result
                latch.countDown()
            }
        }
        latch.await(JOIN_DIALOG_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
        return choice
    }

    private fun openWlanSettings() {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** 热点密码复制到剪贴板（手动到 WLAN 页连接时粘贴），并提示用户。 */
    private fun copyHotspotPsk(passphrase: String) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    uiHintSink?.invoke("热点密码已复制（${passphrase.length} 位）——粘贴到密码框即可")
                }
            }
        }
    }

    /**
     * WiFi 开关与当前连接的 SSID（一次 `cmd wifi status` 查询）。
     * 输出形如：`Wifi is enabled` + `Wifi is connected to "Redmi K40"`（未连接无此行）。
     */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result =
            Shell
                .exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
        return enabled to ssid
    }

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** 单引号包裹的 POSIX shell 字面量（SSID/密码可能含空格与特殊字符）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 复现「BT 控制已结束」状态：断开当前任一路径的 iAP2 控制会话（DiPlay 只在此状态应答 RTSP）。 */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    /**
     * 等车机 ctrl 邀请（`GET /ctrl-int/1/connect`）——catplay 的连接触发点。
     *
     * 邀请意味着「车机已在 WiFi 上发现我们，且已把 AirPlay 端口打开」，此时连
     * AirPlay 才不会被 Connection refused。旧实现用固定 sleep 1.6s 赌邀请已到：
     * 车机快时白等，慢时又不够。这里事件驱动，并且若 mDNS 已发现 `_airplay._tcp`
     * （端口确定可连）也立即放行，避免旧机型不发邀请时白等满窗口。
     *
     * @return true = 收到邀请 / 已发现端点；false = 等待窗口耗尽
     */
    private fun awaitCtrlInvite(
        ssid: String,
        timeoutMillis: Long,
    ): Boolean {
        val advertiser = ctrlAdvertiser
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastLogAt = startedAt
        var reannounced = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled) return false
            val invites = advertiser?.inviteCount ?: 0
            if (invites > 0) {
                appendLog(
                    "CTRL: ✓ 收到车机邀请（${android.os.SystemClock.uptimeMillis() - startedAt}ms，第 $invites 次）" +
                        "——立即连接 AirPlay",
                )
                return true
            }
            if (endpointBrowser?.awaitEndpoint(0) != null) {
                appendLog(
                    "CTRL: 已发现 _airplay._tcp（未收到邀请，旧机型行为）——直接连接 AirPlay",
                )
                return true
            }
            val now = android.os.SystemClock.uptimeMillis()
            // catplay 实测：iPhone 的 Bonjour 公告常被车机丢弃——它在每次邀请前都
            // force_announce。这里在等待中途补发一次，覆盖「绑网瞬间公告丢失」的情况。
            if (!reannounced && now - startedAt >= 2_000) {
                reannounced = true
                appendLog("CTRL: 2s 未收到邀请——强制重新公告一次（catplay 同款 force_announce）")
                ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
            }
            if (now - lastLogAt > 1_200) {
                appendLog("CTRL: 等待车机 ctrl 邀请…（已等 ${now - startedAt}ms）")
                lastLogAt = now
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        appendLog("CTRL: ${timeoutMillis}ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）")
        return false
    }

    // ---- 以下方法体自 MainActivity 原样迁入（会话逻辑不依赖 Activity）----

    /**
     * 音频通道（手机 → 车机，RTP over UDP）：SETUP type=100 协商 → 播放捕获 → ChaCha 封包推流。
     *
     * 依赖 MediaProjection（AudioPlaybackCapture 的授权来源），且需用户在「启动连接」
     * 弹窗中选择「开启声音」——音视频同时推流显著增加负载（实测会卡顿/断连）。
     *
     * @return 音频通道是否真正启动（调用方据此决定是否补发 RECORD 激活新加入的流）
     */
    fun startAudioChannel(
        context: Context,
        client: AirPlayClient,
        sharedSecret: ByteArray,
        host: String?,
    ): Boolean {
        if (!audioEnabledForSession) {
            appendLog("AUDIO: 本次连接未开启车机侧声音（「启动连接」弹窗选择）——仅推视频")
            return false
        }
        if (host == null) {
            appendLog("AUDIO: 无接收端 IPv4 地址，跳过音频通道")
            return false
        }
        val projection = CarPlayService.mediaProjection
        if (projection == null) {
            appendLog(
                "AUDIO: 缺少投屏权限（播放捕获依赖 MediaProjection）——音频通道未启动。" +
                    "现在点「申请投屏权限」也可即时补启（无需重连）；注意「断开连接」会停服务并连带失效投屏授权",
            )
            return false
        }
        return try {
            val audio = client.runAudioStream(sharedSecret) ?: return false
            val sender =
                AudioSender(
                    host = host,
                    dataPort = audio.dataPort,
                    streamKey = audio.streamKey,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                    sampleRate = audio.sampleRate,
                )
            audioSender = sender
            if (!sender.start(projection)) {
                audioSender = null
                false
            } else {
                true
            }
        } catch (error: Exception) {
            appendLog("AUDIO: 通道启动异常 - ${error.message}")
            audioSender = null
            false
        }
    }

    /**
     * 端点补全：0x4301 缺失/无 IPv4 时用 mDNS 解析车机 `_airplay._tcp` 合成端点
     * （真 iPhone 同款发现路径，也是 Audi MIB3 唯一路径——它不发 0x4301）。
     * 测试台端点自带 IPv4 与端口，本方法秒过、行为不变。
     */
    private fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val needHost = !effective.ipAddresses.any { it.contains('.') && !it.contains(':') }
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        phaseHint = "mDNS 解析车机地址…"
        appendLog(">>> [$source] 0x4301 端点不完整（port=${effective.airPlayPort} ips=${effective.ipAddresses}），取 mDNS 车机 _airplay._tcp…")
        // 用常驻发现器的缓存（绑网时已开始后台发现，通常此时已命中）。
        // 此前现起一次发现并硬重试 3×6s：车机 mDNS 稳定时白等，不稳定时又拖满 18s。
        // 网段过滤（防误连同网 macOS 接收器）由 browser 的 accept 统一做。
        val info =
            endpointBrowser?.awaitEndpoint(ENDPOINT_RESOLVE_WAIT_MS) ?: run {
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
            val txtPk =
                info.attributes["pk"]
                    ?.toString(Charsets.UTF_8)
                    ?.trimEnd { it.code == 0 }
                    ?.takeIf { it.isNotEmpty() }
            if (txtPk != null) result = result.copy(publicKey = txtPk)
        }
        if (result.deviceIdentifier == null) {
            val txtId =
                info.attributes["deviceid"]
                    ?.toString(Charsets.UTF_8)
                    ?.trimEnd { it.code == 0 }
                    ?.takeIf { it.isNotEmpty() }
            if (txtId != null) result = result.copy(deviceIdentifier = txtId)
        }
        if (result.sourceVersion == null) {
            val txtVer =
                info.attributes["srcvers"]
                    ?.toString(Charsets.UTF_8)
                    ?.trimEnd { it.code == 0 }
                    ?.takeIf { it.isNotEmpty() }
            if (txtVer != null) result = result.copy(sourceVersion = txtVer)
        }
        return result
    }

    /**
     * Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时的热更新入口。
     *
     * 三种归属：
     *  - 正在推流：冗余端点，忽略（与 [onCarPlayEndpoint] 同一防护，避免拆掉健康会话）；
     *  - 交接/配对进行中：热更新当前 AirPlayClient 的端点，配对循环下一轮即用新地址；
     *    若 client 尚未创建（还在绑网/等邀请）先暂存，配对线程起步时并入；
     *  - 上一轮已失败（无会话在跑）：按新端点重走一轮完整交接——Audi MMI 9310
     *    实测 0x4301 在 120s 配对窗口耗尽后 28s 才到，旧实现把它当日志垃圾丢掉，
     *    车机等 15s 无人连入即拆链，表现为「连上后自动断开」。
     */
    private fun onLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        if (connectCancelled) {
            appendLog(">>> [$source] 0x4301 晚到（已取消连接）——忽略")
            return
        }
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 0x4301 晚到（推流中）——忽略冗余端点")
            return
        }
        val client = currentClient
        if (sessionStarting.get()) {
            if (client != null && client.pairingInProgress) {
                // 合并而非替换：0x4301 可能不带 IPv4（Audi MMI 9310 实测端点解析依赖
                // mDNS/网关），直接替换会把已解析到的地址抹掉，配对循环反而连不上。
                val merged = preferEndpoint(endpoint, client.currentEndpoint)
                val changed = client.updateEndpoint(merged, "0x4301 晚到")
                appendLog(
                    if (changed) {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点已热更新，下一轮重试用新地址"
                    } else {
                        ">>> [$source] 0x4301 晚到（配对进行中）——端点无变化，忽略"
                    },
                )
            } else {
                pendingEndpointUpdate = endpoint
                appendLog(">>> [$source] 0x4301 晚到（交接尚未创建配对客户端）——暂存，配对起步时采用")
            }
            return
        }
        // 用户已断开（BT 组件已停）时不得用晚到消息复活会话
        if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) {
            appendLog(">>> [$source] 0x4301 晚到（蓝牙链路已退役）——忽略")
            return
        }
        appendLog(">>> [$source] 0x4301 晚到（上一轮已结束）——按新端点重走一轮连接")
        onCarPlayEndpoint(endpoint, "$source/0x4301 晚到")
    }

    /** 把交接期间暂存的晚到端点并入新建的客户端（建 client 与收到 0x4301 的竞态窗口）。 */
    private fun drainPendingEndpoint(client: AirPlayClient) {
        val late = pendingEndpointUpdate ?: return
        pendingEndpointUpdate = null
        if (client.updateEndpoint(preferEndpoint(late, client.currentEndpoint), "交接期间晚到 0x4301")) {
            appendLog("AIRPLAY: 交界窗口的 0x4301 端点已并入配对客户端")
        }
    }

    /**
     * 配对循环的端点刷新器（非阻塞）：只读浏览器缓存的 `_airplay._tcp`，
     * 有则并入 IP/端口/公钥等字段。返回 null = 无新信息（配对循环继续用当前端点）。
     */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = endpointBrowser?.awaitEndpoint(0) ?: return null
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            address.contains('.') &&
            !address.contains(':') &&
            result.ipAddresses.none { it == address }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if (result.airPlayPort <= 0 && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result == endpoint) return null
        appendLog(
            ">>> [$source] 配对期间 mDNS 刷新端点 → " +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    private fun txtValue(
        info: android.net.nsd.NsdServiceInfo,
        key: String,
    ): String? =
        info.attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trimEnd { it.code == 0 }
            ?.takeIf { it.isNotEmpty() }

    /**
     * 晚到端点优先：车机明确告知的端口/公钥优先于 mDNS 合成值，
     * 但 IP 列表取并集（0x4301 可能不带 IPv4，Audi MMI 9310 实测即如此）。
     */
    private fun preferEndpoint(
        late: Iap2SenderSession.CarPlayEndpoint,
        fallback: Iap2SenderSession.CarPlayEndpoint,
    ): Iap2SenderSession.CarPlayEndpoint =
        late.copy(
            airPlayPort = if (late.airPlayPort > 0) late.airPlayPort else fallback.airPlayPort,
            ipAddresses = (late.ipAddresses + fallback.ipAddresses).distinct(),
            publicKey = late.publicKey ?: fallback.publicKey,
            deviceIdentifier = late.deviceIdentifier ?: fallback.deviceIdentifier,
            sourceVersion = late.sourceVersion ?: fallback.sourceVersion,
        )

    /** BT 会话交接点：拿到 0x5703/0x4301 后两条路径流程一致——绑 WiFi → AirPlay 配对 → SETUP → 推流。 */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ) {
        val context = appContext ?: return
        // 冗余端点防护：接收端控制器（DiPlay 实测）在我们配对推流后才完成既有的
        // RFCOMM 重连并重发 0x4301。此时重启配对会 retireSession 掉健康会话
        // （编码器 stop 竞态 = 「连上后自动断开」循环的根因）。推流中的端点一律忽略；
        // 真断连时推流自行终止（isRunning=false），下一个端点自然接续重连。
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 会话推流中——忽略冗余端点（接收端周期重连的重复 0x4301）")
            return
        }
        if (!sessionStarting.compareAndSet(false, true)) {
            appendLog(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
            return
        }
        // 新一轮交接开始：清掉上一轮（可能中途失败）遗留的晚到端点暂存，
        // 否则它会在本轮的 drain 阶段覆盖掉本会话刚解析出的端点。
        pendingEndpointUpdate = null
        appendLog(
            ">>> [$source] 拿到接收端热点：SSID=${endpoint.ssid}；" +
                "密码=${if (endpoint.passphrase.isEmpty()) "(空)" else "已获取"}",
        )
        phaseHint = "蓝牙已连上车机，解析热点地址…"
        ssidSink?.invoke(endpoint.ssid)
        // 先绑网络再解析端点：mDNS 组播跟随 bindProcessToNetwork 的网络，解析放前面
        // 只会拿到旧网络的结果（此前靠 resolveEndpointHost 内部重试掩盖，白等数秒）。
        // 绑定完成后再并行做三件事：ctrl 广播针对新网络重注册、后台发现 _airplay._tcp、
        // 等车机 ctrl 邀请（车机已发现我们的权威信号）。
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!bound) {
                phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${endpoint.ssid}"
                appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${endpoint.ssid}\" 后断开重连")
                sessionStarting.set(false)
                return@proceed
            }
            // 网络绑定完成：ctrl 广播重新注册（落到车机所在网络），同时开始后台 mDNS 发现。
            // 两者都发生在等邀请期间，不额外占用时间。
            ctrlAdvertiser?.ensureForNetwork(endpoint.ssid, force = true, network = wifiConnector?.currentNetwork)
            endpointBrowser?.resetCache()
            endpointBrowser?.start()
            // 0x4301 自带完整 IPv4+端口 = 接收端已明确告知「连这里」，无需等邀请
            // （DiPlay 测试台路径：本轮直接配对，省掉固定等待）。
            val endpointSelfContained =
                endpoint.airPlayPort > 0 && endpoint.ipAddresses.any { it.contains('.') && !it.contains(':') }
            Bg.run("airplay-pairing") {
                try {
                    if (connectCancelled) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }
                    if (endpointSelfContained) {
                        appendLog("AIRPLAY: 0x4301 端点完整（${endpoint.ipAddresses.firstOrNull()}:${endpoint.airPlayPort}）——直接配对，不等 ctrl 邀请")
                    } else {
                        // 等 ctrl 邀请（catplay CarPlayCtrlServer：邀请=车机已发现手机且
                        // AirPlay 窗口已开，收到即刻连接）。此前是「固定睡 1.6s 赌它已到」，
                        // 快的时候白等、慢的时候不够；改为事件驱动。
                        awaitCtrlInvite(endpoint.ssid, CTRL_INVITE_WAIT_MS)
                    }
                    // 端点解析放在邀请之后：车机的 `_airplay._tcp` 常在受邀后才上线，
                    // 且这一步最长 3s——必须在后台线程做，不能占住网络回调线程。
                    var effectiveEndpoint = resolveEndpointHost(endpoint, source)
                    // 交接流程起步期间车机补发的 0x4301（比 mDNS 合成更权威）在此并入。
                    pendingEndpointUpdate?.let { late ->
                        pendingEndpointUpdate = null
                        effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)
                        appendLog(
                            "AIRPLAY: 并入交接期间晚到的 0x4301 端点 → " +
                                "${effectiveEndpoint.ipAddresses.firstOrNull()}:${effectiveEndpoint.airPlayPort}",
                        )
                    }
                    if (connectCancelled) {
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
                    }

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            endpointRefresher = { current -> enrichEndpointFromBrowser(current, source) },
                        )
                    retireSession()
                    // 会话静默降噪（2026-10-10）：从交接点起暂停其它接收端的出站连接探测
                    // （典型：连着 DiPlay 测试台时不再噪音式探测 Audi）。会话结束/断开时解除。
                    BtSessionGate.sessionActive = true
                    // 第一阶段：保持 BT 在位直接配对 + SETUP（协议上正确的形态）
                    phaseHint = "AirPlay 配对中…"
                    var client = newClient()
                    currentClient = client
                    drainPendingEndpoint(client)
                    var keys = client.runPairing()
                    if (connectCancelled) {
                        retireSession()
                        appendLog("AIRPLAY: 用户已取消——中止配对")
                        sessionStarting.set(false)
                        return@run
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
                        drainPendingEndpoint(client)
                        keys = client.runPairing()
                        if (connectCancelled) {
                            retireSession()
                            appendLog("AIRPLAY: 用户已取消——中止配对")
                            sessionStarting.set(false)
                            return@run
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
                            // 记录会话凭据：投影授权晚于连接时用于动态补启音频。
                            // 用 client.currentEndpoint 而非 effectiveEndpoint：配对期间
                            // mDNS 刷新过端点时，真正连上的是 client 里的地址（2026-10-18：
                            // 0x4301 的定长 IP 字段带 NUL，mDNS 给的是干净地址，取旧值
                            // 会让视频端口/keepAlive 用脏地址 → 一律解析失败）。
                            currentSharedSecret = keys.sharedSecret
                            currentAudioHost = client.currentEndpoint.ipAddresses.firstOrNull { it.contains('.') }
                            client.connectEventChannel(keys.sharedSecret, setup) { command ->
                                when (command["type"]) {
                                    "hidSendReport" -> {
                                        val uuid = command["uuid"]?.toString()
                                        val report = command["hidReport"] as? ByteArray
                                        // 车机媒体键（Consumer Control）走 hidSendReport 通路，
                                        // 判定**以设备描述符为准**（不按 uuid、不按车机型号写死）：
                                        // 描述符声明了 Consumer 页(0x0C)的设备才当媒体键，报告字节是
                                        // Usage 数组下标（HID Array 语义）。触摸/旋钮/电话设备的描述符
                                        // 分别是 Digitizer/GenericDesktop/Telephony 页，一律不走这里——
                                        // DiPlay 的 knob(4B) 与 telephony(1B) 报告长度与媒体键重叠，
                                        // 只靠长度会误判（如 telephony usage 0xB3 会误发「快进」）。
                                        val descriptor = uuid?.let { setup.hidDescriptors[it] }
                                        val keyMap =
                                            descriptor?.let(ConsumerKeys::parseDescriptor)
                                        val rawValue =
                                            report
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { it[0].toInt() and 0xFF }
                                        val consumerUsage =
                                            when {
                                                // 有描述符：只有 Consumer 设备才解析（非 Consumer → null）
                                                descriptor != null -> keyMap?.let { rawValue?.let(it::usageFor) }
                                                // 描述符缺失（未知车机）：保守回退——仅 1 字节报告，
                                                // 值当 Usage ID 直读
                                                report != null && report.size == 1 -> rawValue?.takeIf { it != 0 }
                                                else -> null
                                            }
                                        val contacts =
                                            if (consumerUsage == null && report != null) {
                                                // 触摸格式按设备描述符判定（Digitizer 页 + 单指/多槽），
                                                // 描述符不可用时按报文长度回退——不依赖车机型号或 uuid
                                                val touchFormat =
                                                    descriptor?.let(TouchReports::formatFromDescriptor)
                                                TouchReports.parse(report, touchFormat)
                                            } else {
                                                null
                                            }
                                        if (consumerUsage != null) {
                                            val keyCode = ConsumerKeys.keyCodeFor(consumerUsage)
                                            if (keyCode != 0) {
                                                dispatchMediaKey(keyCode)
                                                appendLog(
                                                    "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x → keyCode=$keyCode）"
                                                            .format(consumerUsage),
                                                )
                                            } else {
                                                appendLog(
                                                    "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x）无对应 Android 键，忽略"
                                                            .format(consumerUsage),
                                                )
                                            }
                                        } else if (keyMap != null && report != null) {
                                            // Consumer 设备的「无按键」/释放报文（值为 0 或下标越界）：
                                            // 静默计数，避免释放事件刷屏
                                            consumerReleaseCount++
                                        } else if (contacts != null) {
                                            if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                appendLog(
                                                    "EVENT: 触摸 " +
                                                        contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                )
                                            }
                                            touchLogCount++
                                            contacts.forEach {
                                                TouchRouter.onContact(
                                                    context.applicationContext,
                                                    it,
                                                )
                                            }
                                        } else if (report != null) {
                                            appendLog(
                                                "EVENT: 其他 HID 报告 uuid=$uuid bytes=${report.size}" +
                                                    " hex=${report.joinToString(" ") { "%02x".format(it) }}",
                                            )
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
                                // 地址取 client 的当前端点（配对期间可能被 mDNS 刷新过）：
                                // 视频数据端口与 keepAlive 都按它建 socket，用陈旧地址会
                                // 直接解析失败（2026-10-18 实测「TCP 已连通」但视频端口挂掉）。
                                val host = client.currentEndpoint.ipAddresses.firstOrNull { it.contains('.') }
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
                                                "(${Iap2MessageIds.nameOf(
                                                    frame.messageId,
                                                )}) ${frame.body.size}B" +
                                                if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                        )
                                    }
                                }
                                // 关键时序（2026-10-17 奥迪定谳）：屏幕 SETUP 后必须尽快连上
                                // dataPort——车机有 ~2s 看门狗。音频 SETUP 实测要 ~2.1s，
                                // 若排在前面，端口连接迟到即触发车机 reset 控制通道
                                // （日志特征：`音频 SETUP 往返 2091ms` 与 `EVENT 读取结束
                                // - Connection reset` 同一毫秒）。故先建 VideoSender 并
                                // openDataPort()，再协商音频，最后 RECORD + 启动编码器。
                                var pendingVideoW = 0
                                var pendingVideoH = 0
                                var pendingVideoDensity = 0
                                if (host != null) {
                                    // 「显示」偏好：流画布/虚拟屏按车机分辨率百分比生成。
                                    // 只在会话建立时生效——编码器与虚拟屏尺寸此时定型；
                                    // 密度同步缩放，逻辑布局（dp）与 100% 完全一致。
                                    val scalePct =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .scalePercent(it)
                                            } ?: 100
                                    val scale = scalePct / 100f
                                    // 缩放后向下对齐偶数（H.264 要求）：虚拟屏/合成器/编码器
                                    // 三方必须同一尺寸，故在源头对齐（VideoSender 内另有兜底）
                                    val streamW =
                                        ((setup.displayWidth * scale).toInt().coerceAtLeast(320) / 2) * 2
                                    val streamH =
                                        ((setup.displayHeight * scale).toInt().coerceAtLeast(180) / 2) * 2
                                    val fps =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .fps(it)
                                            } ?: 30
                                    activeScalePercent = scalePct
                                    activeTargetFps = fps
                                    // 车机触摸在通告视图坐标系（如 1160×540），流已按比例
                                    // 缩放（如 1450×674）——注入前需映射，否则点击漂移
                                    TouchRouter
                                        .setCoordinateScale(
                                            setup.displayWidth,
                                            setup.displayHeight,
                                            streamW,
                                            streamH,
                                        )
                                    appendLog(
                                        "DISPLAY: 流 ${setup.displayWidth}x${setup.displayHeight} → " +
                                            "${streamW}x$streamH（$scalePct%）@${fps}fps",
                                    )
                                    pendingVideoW = streamW
                                    pendingVideoH = streamH
                                    // dock 屏密度随画布短边等比（设计基准 1160×540@420dpi，
                                    // 见 design/launcher-proto.html：dock 48dp=126px）：高分辨率
                                    // 车机（测试台 2320×1080=2×）下 dock 文字/图标与奥迪同比例
                                    // 放大——此前固定 420dpi，dock 相对画布减半显小（2026-10-17）。
                                    // 上限 4×（4K 级画布）。
                                    pendingVideoDensity =
                                        (420f * minOf(streamW, streamH) / 540f)
                                            .toInt()
                                            .coerceIn(120, 1680)
                                    videoSender =
                                        VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            streamKey = screen.streamKey,
                                            width = streamW,
                                            height = streamH,
                                            targetFps = fps,
                                            log = { line -> appendLog(line) },
                                            onEnded = {
                                                // 会话真实结束：解除静默降噪（客户端恢复探测/重连）
                                                appendLog(">>> CarPlay 会话结束（推流终止）——恢复蓝牙探测")
                                                BtSessionGate.sessionActive = false
                                                // 车机掉线类结束（无用户断开操作）：同样复原横屏覆盖。
                                                // 这里跑在推流收尾线程，不是连接关键路径，但统一走
                                                // 异步口以保持「串行、不重入」的语义。
                                                appContext?.let { ctx ->
                                                    AppOrientationCompat.resetAllAsync(ctx, ::appendLog)
                                                }
                                            },
                                        )
                                    // 先连端口（车机看门狗），编码器等 RECORD 后再启动
                                    videoSender?.openDataPort()
                                }
                                // 先 RECORD 激活会话并起视频，再协商音频（2026-10-17 奥迪定谳）：
                                // 车机在「屏幕流 SETUP 之后」有约 2s 看门狗，等不到 RECORD/数据流即
                                // 拆掉整轮会话。音频 SETUP 实测本身要 ~2.05-2.16s（车机侧音频通路
                                // 初始化），排在 RECORD 之前必然超时——日志里 reset 与音频 SETUP
                                // 返回同一毫秒、且 5 次实测全部复现。故顺序改为：
                                //   屏幕 SETUP → 连数据端口 → RECORD → 起编码器（出帧）→ 音频 SETUP
                                // 音频 SETUP 失败只丢声音，视频会话保持。
                                client.record()
                                if (host != null) {
                                    val videoW = pendingVideoW
                                    val videoH = pendingVideoH
                                    val videoDensity = pendingVideoDensity
                                    videoSender?.start { surface ->
                                        // 唯一模式：隐形屏（双屏启动器：dock + 应用屏）
                                        val source =
                                            HiddenDisplaySource(
                                                context.applicationContext,
                                                log = { line -> appendLog(line) },
                                            )
                                        currentHiddenSource = source
                                        val ok =
                                            source.attach(
                                                surface,
                                                videoW,
                                                videoH,
                                                videoDensity,
                                            )
                                        appendLog(
                                            "MODE: 隐形屏 ${if (ok) "已启动（高德车机版优先）display=${source.displayId}" else "启动失败（Shizuku 就绪？）"}",
                                        )
                                    }
                                }
                                // 音频通道（可选）：SETUP type=100 → UDP RTP 推流。
                                // 会话已激活（RECORD 完成）后再协商；失败不影响视频主链路。
                                val audioStarted = startAudioChannel(context, client, keys.sharedSecret, host)
                                if (audioStarted) {
                                    // 流在 RECORD 之后加入：AirPlay 语义要求再发一次 RECORD
                                    // 把新流纳入已激活的会话。缺这一步时车机对 SETUP 回 200
                                    // 并给 dataPort，但不会真正播放（2026-10-17 奥迪实测：
                                    // 音频包持续发送、采集内容非零，车机无声）。
                                    appendLog("AUDIO: 音频流已加入——补发 RECORD 激活（流后加入会话）")
                                    client.record()
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
                        BtSessionGate.sessionActive = false
                        // 收尾竞态：晚到 0x4301 若恰好在「配对已失败、互斥尚未释放」的
                        // 窗口内到达，会被暂存但没人消费。此处补投一次——车机等 15s
                        // 无人连入就拆链，错过这一投就是整轮失败（2026-10-18 分析）。
                        val pending = pendingEndpointUpdate
                        if (pending != null && !connectCancelled) {
                            pendingEndpointUpdate = null
                            appendLog(">>> 收尾时发现暂存的晚到 0x4301——立即按新端点重走一轮")
                            Bg.run("late-endpoint-retry") {
                                onCarPlayEndpoint(pending, "0x4301 晚到（本轮收尾补投）")
                            }
                        }
                    }
                }
            }
        }
        // 已绑定过网络（autoJoinCarWifi 刚绑好并重注册过）就不要再走一遍加入流程：
        // WifiCarPlayConnector 内部先 release() → bindProcessToNetwork(null)，
        // 会把刚注册好的 ctrl mDNS 广播打断，再等系统重建绑定。
        val wifiConfig =
            Iap2SenderSession.WifiConfiguration(
                endpoint.ssid,
                endpoint.passphrase,
                endpoint.channel,
                endpoint.securityType,
            )
        if (boundCarSsid == endpoint.ssid && wifiConnector?.currentNetwork != null) {
            appendLog(">>> [$source] 已绑定车机热点 ${endpoint.ssid}，跳过重复绑定")
            continueWithNetwork(true)
        } else {
            // 端点先于 0x5703 到达（或上一轮换网失败）：同样走应用专属网络。
            // 不能再用「按 TRANSPORT_WIFI 盲绑当前任意 WiFi」——切网途中会绑到旧网络，
            // AirPlay 必然连不上，且会把无互联网热点留在系统默认网络里。
            Bg.run("wifi-join-on-endpoint") {
                while (!connectCancelled) {
                    val shellReady =
                        appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
                    when (val result = joinCarWifiSpecifier(wifiConfig, shellReady)) {
                        is WifiCarPlayConnector.JoinResult.Joined -> {
                            continueWithNetwork(true)
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unsupported -> {
                            // WEP 等无法用 specifier 表达：等用户在系统 WLAN 里连上后绑定
                            waitForManualWifiThenContinue(wifiConfig) { bound ->
                                continueWithNetwork(bound)
                            }
                            return@run
                        }

                        WifiCarPlayConnector.JoinResult.Unavailable -> {
                            promptSpecifierFailed(wifiConfig, shellReady)
                            // promptSpecifierFailed 内部可能已重试/转手动；两者都会
                            // 通过 joinCarWifiSpecifier → continueWithNetwork 续上流程，
                            // 这里只在彻底失败时结束本轮
                            if (boundCarSsid == wifiConfig.ssid && wifiConnector?.currentNetwork != null) {
                                continueWithNetwork(true)
                            } else {
                                sessionStarting.set(false)
                            }
                            return@run
                        }
                    }
                }
                sessionStarting.set(false)
            }
        }
    }

    /** specifier 不可用时的手动兜底：轮询系统 WLAN 是否连上目标热点，而后绑定。 */
    private fun waitForManualWifiThenContinue(
        wifi: Iap2SenderSession.WifiConfiguration,
        onBound: (Boolean) -> Unit,
    ) {
        val deadline = android.os.SystemClock.uptimeMillis() + CAR_WIFI_BIND_WAIT_MS
        var lastHintAt = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled) {
            val current = shellWifiStatus().second
            if (current == wifi.ssid) {
                onBound(bindCarWifiAndRegister(wifi.ssid))
                return
            }
            if (android.os.SystemClock.uptimeMillis() - lastHintAt > 5_000) {
                lastHintAt = android.os.SystemClock.uptimeMillis()
                phaseHint = "等待手机加入车机热点 ${wifi.ssid}…"
                appendLog("WIFI: 等待用户在系统 WLAN 中加入车机热点（当前 ${current ?: "未连接"}）…")
                openWlanSettings()
            }
            Thread.sleep(1_000)
        }
        if (!connectCancelled) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(">>> 等待车机热点超时——请到 设置>WLAN 加入 \"${wifi.ssid}\" 后重新连接")
            uiHintSink?.invoke("未连上车机热点 \"${wifi.ssid}\"——请到 WLAN 设置中主动连接")
        }
        onBound(false)
    }

    /** 触摸/隧道事件低频计数（迁移自 MainActivity 字段，保持日志节流行为一致）。 */
    @Volatile
    private var touchLogCount = 0

    /** 车机媒体键「释放」报文计数（值为 0 的 1 字节报告，静默丢弃，仅计数备查）。 */
    @Volatile
    private var consumerReleaseCount = 0

    @Volatile
    private var iapEventMessageCount = 0
}
