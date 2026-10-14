package com.lizhihua.carplaysender

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.lizhihua.carplaysender.ui.SignalWavesView
import java.util.Locale

/**
 * 手机侧界面（Material 3 暗色重构，设计稿 design/phone-ui-proto.html）：
 * 英雄卡承载连接状态与主操作；连接准备清单化；Shell 状态与日志折叠收纳。
 * 会话管线（对象、日志缓冲）全部由 [SessionController] 持有——Activity 只是挂屏：
 * 销毁重建不影响会话，重进界面日志自动续显；连接状态由 1s 轮询对账。
 */
class MainActivity : AppCompatActivity() {
    private enum class HeroState { IDLE, CONNECTING, CONNECTED }

    /** 连接中的默认副标题（无阶段提示时）。 */
    private companion object {
        const val DEFAULT_CONNECTING_HINT = "蓝牙 iAP2 握手 → 加入车机热点 → 建立 CarPlay 会话"
    }

    private lateinit var deviceSubtitle: TextView
    private lateinit var shizukuChip: TextView
    private lateinit var signalWaves: SignalWavesView
    private lateinit var hubPlate: FrameLayout
    private lateinit var hubCar: ImageView
    private lateinit var hubCaption: TextView
    private lateinit var heroStateText: TextView
    private lateinit var btnResidualCleanup: TextView
    private lateinit var heroSubline: TextView
    private lateinit var heroProgress: View
    private lateinit var ctaButton: LinearLayout
    private lateinit var ctaIcon: ImageView
    private lateinit var ctaLabel: TextView
    private lateinit var audioTag: TextView
    private lateinit var readyCount: TextView
    private lateinit var ssidInput: EditText
    private lateinit var btDesc: TextView
    private lateinit var btBadge: TextView
    private lateinit var audioDesc: TextView
    private lateinit var btnAudio: TextView
    private lateinit var switchAlwaysOn: androidx.appcompat.widget.SwitchCompat
    private lateinit var advBody: View
    private lateinit var advChevron: ImageView
    private lateinit var shellStateText: TextView
    private lateinit var logBody: View
    private lateinit var logChevron: ImageView
    private lateinit var switchLog: androidx.appcompat.widget.SwitchCompat
    private lateinit var logText: TextView
    private lateinit var snackbar: TextView

    // ---- 英雄卡状态机（1s 轮询 SessionController 对账）----
    private var heroState: HeroState? = null
    private var connectedAt = 0L
    private var hubBlink: ObjectAnimator? = null

    // 启动残留屏检查进行中（英雄卡显示「检查中」）；发现的残留暂缓清理时
    // 记在 pendingResidualDisplays，状态旁的「清理残留屏」按钮可再次发起
    private var startupCheckRunning = false
    private var pendingResidualDisplays: List<Pair<Int, String>>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heroTick: Runnable =
        object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                tickHero()
                mainHandler.postDelayed(this, 1_000L)
            }
        }

    /**
     * 挂屏注册：日志续显（含划掉界面重进时已有会话的历史日志）+ SSID 回填。
     */
    private val logListener: (String) -> Unit = { text ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) renderConsole(text)
        }
    }

    private val ssidSink: (String) -> Unit = { ssid ->
        runOnUiThread { ssidInput.setText(ssid) }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BLUETOOTH_CONNECT 授权: $granted")
            if (granted && pendingStartAfterBtPermission.compareAndSet(true, false)) {
                ensureBluetoothReady { showAudioChoiceDialog() }
            }
        }

    /** 蓝牙权限授权后自动继续启动连接。 */
    private val pendingStartAfterBtPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** 车机壁纸选择（Photo Picker；无需存储权限）。选中即持久化并通知合成器换图。 */
    private val wallpaperPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                log("WALLPAPER: 未选择图片")
                return@registerForActivityResult
            }
            val ok =
                com.lizhihua.carplaysender.ui.WallpaperStore
                    .setFromUri(this, uri)
            log(
                "WALLPAPER: ${if (ok) "壁纸已更新（会话进行中约 1 秒内上车；下次连接自动生效）" else "保存失败"}",
            )
            if (ok) snack("壁纸已更新") else snack("保存失败")
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
            com.lizhihua.carplaysender.service.CarPlayService
                .start(this)
            log("MODE: ✓ 投屏权限已授予（重启手机后需重新授权）")
            // 会话已在进行中授权（授权晚于连接）：等投影就绪后动态补启音频通道
            Thread({
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (SystemClock.uptimeMillis() < deadline) {
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
    private val pendingStartAfterProjection =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * 「启动连接」前的音频选择弹窗：
     * 音频与视频同时推流会显著增加负载（实测 48k 立体声 PCM ≈1.5Mbps + 视频 ≈4Mbps），
     * 测试台出现画面卡顿与断连；真车链路能力未知。因此默认不推音频，需用户显式开启。
     */
    private fun showAudioChoiceDialog() {
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle("是否在车机侧播放声音？")
            .setMessage(
                "开启后：手机媒体/导航声音将投到车机播放，手机本机静音（不开启则车上无声音）。\n\n" +
                    "注意：声音与画面同时传输会明显增加负载，可能出现画面卡顿或连接不稳；" +
                    "以看视频为主时建议暂不开启。\n\n" +
                    "开启声音需要「投屏」权限，选择后会一并申请。",
            ).setPositiveButton("开启声音") { _, _ -> beginWithAudio(enabled = true) }
            .setNegativeButton("仅视频（更流畅）") { _, _ -> beginWithAudio(enabled = false) }
            .setNeutralButton("取消", null)
            .show()
    }

    /** 「取消连接」确认弹窗（防误触）：中止当前连接尝试并断开蓝牙/WiFi 链路。 */
    private fun promptCancelConnecting() {
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle("取消连接？")
            .setMessage("将停止当前连接尝试：蓝牙与 WiFi 链路都会断开，需重新点「启动连接」。")
            .setPositiveButton("取消连接") { _, _ -> SessionController.cancelConnecting(this) }
            .setNegativeButton("继续连接", null)
            .show()
    }

    /**
     * 启动连接的前置检查：蓝牙权限 → 蓝牙开关。
     * 任一不满足都弹窗引导（蓝牙关闭时可一键跳系统蓝牙设置），通过后回调继续。
     */
    private fun ensureBluetoothReady(onReady: () -> Unit) {
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterBtPermission.set(true)
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val on =
            try {
                (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                    .adapter
                    ?.isEnabled == true
            } catch (_: SecurityException) {
                true
            }
        if (!on) {
            androidx.appcompat.app.AlertDialog
                .Builder(this)
                .setTitle("蓝牙未开启")
                .setMessage("与车机的无线配对通信依赖蓝牙。请先开启手机蓝牙，并确认已与车机配对。")
                .setPositiveButton("去开启蓝牙") { _, _ ->
                    runCatching {
                        startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                        )
                    }.onFailure { log("BT: 无法打开蓝牙设置，请手动开启") }
                }.setNegativeButton("取消", null)
                .show()
            log("BT: 蓝牙未开启——本次连接未发起")
            return
        }
        onReady()
    }

    /**
     * 「断开连接」确认弹窗（防误触）：断开会立即中断车机画面，
     * 且投屏授权随保活服务停止而失效（下次要车机侧声音需重新授权）。
     */
    private fun promptDisconnect() {
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle("断开连接？")
            .setMessage(
                "将停止车机推流并关闭保活服务，车机画面会立即中断。\n\n" +
                    "注意：投屏授权会随服务停止失效，下次如需车机侧声音请重新授权。",
            ).setPositiveButton("断开") { _, _ -> SessionController.disconnect(this) }
            .setNegativeButton("取消", null)
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
            if (Build.VERSION.SDK_INT >= 33 &&
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

        deviceSubtitle = findViewById(R.id.deviceSubtitle)
        shizukuChip = findViewById(R.id.shizukuChip)
        signalWaves = findViewById(R.id.signalWaves)
        hubPlate = findViewById(R.id.hubPlate)
        hubCar = findViewById(R.id.hubCar)
        hubCaption = findViewById(R.id.hubCaption)
        heroStateText = findViewById(R.id.heroStateText)
        btnResidualCleanup = findViewById(R.id.btnResidualCleanup)
        btnResidualCleanup.setOnClickListener {
            pendingResidualDisplays?.let { promptResidualDisplays(it) }
        }
        heroSubline = findViewById(R.id.heroSubline)
        heroProgress = findViewById(R.id.heroProgress)
        ctaButton = findViewById(R.id.ctaButton)
        ctaIcon = findViewById(R.id.ctaIcon)
        ctaLabel = findViewById(R.id.ctaLabel)
        audioTag = findViewById(R.id.audioTag)
        readyCount = findViewById(R.id.readyCount)
        ssidInput = findViewById(R.id.ssidInput)
        btDesc = findViewById(R.id.btDesc)
        btBadge = findViewById(R.id.btBadge)
        audioDesc = findViewById(R.id.audioDesc)
        btnAudio = findViewById(R.id.btnAudio)
        switchAlwaysOn = findViewById(R.id.switchAlwaysOn)
        advBody = findViewById(R.id.advBody)
        advChevron = findViewById(R.id.advChevron)
        shellStateText = findViewById(R.id.shellStateText)
        logBody = findViewById(R.id.logBody)
        logChevron = findViewById(R.id.logChevron)
        switchLog = findViewById(R.id.switchLog)
        logText = findViewById(R.id.logText)
        snackbar = findViewById(R.id.snackbar)

        deviceSubtitle.text = deviceName()
        logText.movementMethod = ScrollingMovementMethod()
        // 控制台在长页面 ScrollView 内：按下时申请父级不拦截竖向手势，否则无法手动滚动
        logText.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,
                -> updateLogBottomButton()
            }
            false
        }
        findViewById<ImageView>(R.id.btnLogBottom).setOnClickListener {
            // 跳到底部：滚动后自动跟底也随之恢复
            scrollConsoleToBottom()
        }

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

        // 会话中枢（进程级）：登记 applicationContext + 创建会话对象；
        // 挂屏注册：日志续显 + SSID 回填
        SessionController.ensure(this)
        SessionController.logListener = logListener
        SessionController.ssidSink = ssidSink
        renderConsole(SessionController.logText())

        setUpUiControls()
    }

    /** onCreate 的 UI 初始化尾部：开关/按钮/折叠卡（会话逻辑不变，仅挂新界面）。 */
    private fun setUpUiControls() {
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // 清理旧版本遗留的模式偏好（模式已固定为「隐形屏」启动器）
        if (preferences.contains("mode")) {
            preferences.edit().remove("mode").apply()
        }

        // ---- 常亮+暗幕 ----
        com.lizhihua.carplaysender.ui.ScreenDimmer.enabled =
            preferences.getBoolean("alwaysOn", true)
        switchAlwaysOn.isChecked = com.lizhihua.carplaysender.ui.ScreenDimmer.enabled
        switchAlwaysOn.setOnCheckedChangeListener { _, checked ->
            com.lizhihua.carplaysender.ui.ScreenDimmer.enabled = checked
            preferences.edit().putBoolean("alwaysOn", checked).apply()
            if (checked) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    promptOverlayPermission("已开启「常亮+暗幕」。")
                } else {
                    com.lizhihua.carplaysender.ui.ScreenDimmer
                        .attach(this)
                }
            } else {
                if (com.lizhihua.carplaysender.launcher.VirtualLauncher.appDisplayId >= 0) {
                    // 会话中：仅关闭常亮/暗幕（投屏探针等资源留待会话结束统一释放）
                    com.lizhihua.carplaysender.ui.ScreenDimmer
                        .disableDim()
                } else {
                    com.lizhihua.carplaysender.ui.ScreenDimmer
                        .detach()
                }
            }
            log("MODE: 常亮+暗幕 ${if (checked) "开启" else "关闭"}")
        }

        // ---- 英雄卡主操作 ----
        ctaButton.setOnClickListener {
            when (heroState) {
                HeroState.CONNECTED -> promptDisconnect()
                // 前置检查：蓝牙权限/开关，静默失败 zero-tolerance
                HeroState.IDLE -> ensureBluetoothReady { showAudioChoiceDialog() }
                // 连接中可取消：卡在错误网络/等待回连时用户主动脱身（弹窗防误触）
                HeroState.CONNECTING -> promptCancelConnecting()
                null -> {}
            }
        }

        // ---- 连接准备 ----
        findViewById<TextView>(R.id.btnWifi).setOnClickListener {
            val ssid = ssidInput.text.toString().trim()
            log(
                "WiFi: 打开系统WLAN设置——请选择 \"$ssid\" 并输入热点密码连接；" +
                    "若提示\"无法访问互联网\"请选择保持连接；连上后回来等待自动连接",
            )
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                log("WiFi: 无法打开WLAN设置，请手动前往 设置 > WLAN")
            }
        }

        // 车机侧声音授权（原「申请投屏权限」）：RECORD_AUDIO + MediaProjection 一起请求
        btnAudio.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }

        // ---- 车机显示 ----
        findViewById<LinearLayout>(R.id.rowDim).setOnClickListener {
            if (!com.lizhihua.carplaysender.ui.ScreenDimmer
                    .isAttached()
            ) {
                snack("暗幕未就绪（连接成功后自动启用）")
                return@setOnClickListener
            }
            com.lizhihua.carplaysender.ui.ScreenDimmer
                .enterDimNow()
            log("DIMMER: 手动假息屏（点击屏幕恢复）")
        }

        findViewById<TextView>(R.id.btnWallpaperPick).setOnClickListener {
            wallpaperPicker.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }
        findViewById<TextView>(R.id.btnWallpaperReset).setOnClickListener {
            val ok =
                com.lizhihua.carplaysender.ui.WallpaperStore
                    .resetToDefault(this)
            log("WALLPAPER: ${if (ok) "已恢复内置默认（CarPlay 官方深色壁纸）" else "恢复失败"}")
            snack(if (ok) "已恢复默认壁纸" else "恢复失败")
        }

        // ---- 折叠卡：高级 / 日志 ----
        bindExpandable(R.id.advHeader, advBody, advChevron)
        bindExpandable(R.id.logHeader, logBody, logChevron)

        // ---- Shell 全局状态 ----
        findViewById<View>(R.id.btnShellRefresh).setOnClickListener { refreshShellState() }
        findViewById<View>(R.id.btnShellReset).setOnClickListener { resetShellState() }
        refreshShellState(startupCheck = true)

        // ---- 日志开关（默认开；关闭后不采集、不渲染；选择持久化） ----
        var logEnabled = preferences.getBoolean("logEnabled", true)
        SessionController.logEnabled = logEnabled
        switchLog.isChecked = logEnabled
        switchLog.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            SessionController.logEnabled = checked
            preferences.edit().putBoolean("logEnabled", checked).apply()
            renderConsole(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnClearLog).setOnClickListener { SessionController.clearLog() }
        findViewById<ImageView>(R.id.btnCopyLog).setOnClickListener {
            val text = SessionController.logText()
            if (text.isEmpty()) {
                snack("日志为空")
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CarPlay Sender 日志", text))
            snack("已复制 ${text.length} 字符日志")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 通知使用权（媒体会话枚举，音乐卡数据源）：Shizuku 在场时静默授权
        Thread {
            if (com.lizhihua.carplaysender.shizuku.ShizukuGate
                    .state(this) ==
                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY
            ) {
                val result =
                    com.lizhihua.carplaysender.shizuku.Shell.exec(
                        "cmd notification allow_listener " +
                            com.lizhihua.carplaysender.media.MediaListenerService
                                .componentName(this)
                                .flattenToString(),
                        timeoutMs = 4000,
                    )
                log(
                    if (result.ok) {
                        "MEDIA: 通知使用权已授予（音乐卡可显示歌名/播放状态）"
                    } else {
                        "MEDIA: 通知使用权授予失败——音乐卡无歌名，可在系统设置手动开「通知使用权」"
                    },
                )
            }
        }.start()

        // ---- 车机设置（手机侧二级菜单：桌面/横屏/超采样/dock/默认地图） ----
        findViewById<LinearLayout>(R.id.btnCarSettings).setOnClickListener {
            startActivity(android.content.Intent(this, PhoneSettingsActivity::class.java))
        }

        // 启动即检查：常亮开 + 无悬浮窗权限 → 主动请求
        if (com.lizhihua.carplaysender.ui.ScreenDimmer.enabled) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                promptOverlayPermission("「常亮+暗幕」已开启。")
            } else {
                // 立即就绪：假息屏/自动暗幕不再依赖连接建立
                com.lizhihua.carplaysender.ui.ScreenDimmer
                    .attach(this)
            }
        }

        com.lizhihua.carplaysender.input.TouchRouter.logSink = { line -> log(line) }
        com.lizhihua.carplaysender.launcher.VirtualLauncher.logSink = { line -> log(line) }
        com.lizhihua.carplaysender.shizuku.CarInputClient.logSink = { line -> log(line) }

        log("CarPlay Sender Phase 0 就绪")
        log("真车：与车机蓝牙配对 + 手机加入车机热点 -> 点「启动连接」（自动主动连入车机）")
        log("测试台：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 点「启动连接」等待回连")
    }

    private fun bindExpandable(
        headerId: Int,
        body: View,
        chevron: ImageView,
    ) {
        findViewById<View>(headerId).setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron
                .animate()
                .rotation(if (opening) 180f else 0f)
                .setDuration(250L)
                .start()
        }
    }

    // ==================== 英雄卡状态机 ====================

    private fun tickHero() {
        val s =
            when {
                SessionController.videoSender?.isRunning == true -> HeroState.CONNECTED
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() ->
                    HeroState.CONNECTING
                else -> HeroState.IDLE
            }
        if (s != heroState) {
            if (s == HeroState.CONNECTED) connectedAt = SystemClock.elapsedRealtime()
            heroState = s
            applyHeroState(s)
        }
        // 连接中：副标题跟随阶段提示（英雄卡内即可见，不必划到底部看日志）
        if (s == HeroState.CONNECTING) {
            heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
        }
        // 启动残留屏检查进行中：状态与副标题临时切换为检查提示
        if (s == HeroState.IDLE && startupCheckRunning) {
            heroStateText.text = "检查中"
            heroSubline.text = "正在检查残留屏…"
            heroProgress.visibility = View.VISIBLE
        }
        updateConnectedSubline()
        updateAudioTag()
        updateReadyRow()
        updateShizukuChip()
    }

    private fun applyHeroState(s: HeroState) {
        signalWaves.setState(
            when (s) {
                HeroState.IDLE -> SignalWavesView.State.IDLE
                HeroState.CONNECTING -> SignalWavesView.State.CONNECTING
                HeroState.CONNECTED -> SignalWavesView.State.CONNECTED
            },
        )
        val (plateColor, carColor) =
            when (s) {
                HeroState.IDLE -> getColor(R.color.m3_surface_high) to getColor(R.color.m3_on_var)
                HeroState.CONNECTING ->
                    getColor(R.color.m3_pri_container) to getColor(R.color.m3_on_pri_container)
                HeroState.CONNECTED -> getColor(R.color.m3_primary) to getColor(R.color.m3_on_primary)
            }
        hubPlate.background.mutate().setTint(plateColor)
        hubCar.setColorFilter(carColor)
        hubCaption.setTextColor(
            if (s == HeroState.IDLE) getColor(R.color.m3_mono_dim) else 0xFF5C8B96.toInt(),
        )

        hubBlink?.cancel()
        hubBlink = null
        hubCar.alpha = 1f
        if (s == HeroState.CONNECTING) {
            hubBlink =
                ObjectAnimator.ofFloat(hubCar, View.ALPHA, 1f, 0.45f).apply {
                    duration = 1_200L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                    start()
                }
        }

        heroStateText.text =
            when (s) {
                HeroState.IDLE -> "未连接"
                HeroState.CONNECTING -> "正在连接"
                HeroState.CONNECTED -> "已连接"
            }
        heroProgress.visibility = if (s == HeroState.CONNECTING) View.VISIBLE else View.GONE

        when (s) {
            HeroState.IDLE -> {
                val pending = pendingResidualDisplays
                heroSubline.text =
                    if (pending != null) {
                        "上次会话异常退出，遗留 ${pending.size} 个虚拟屏待清理"
                    } else {
                        "与车机蓝牙完成配对后，点击下方按钮发起无线连接"
                    }
                ctaButton.setBackgroundResource(R.drawable.bg_cta_fill)
                ctaLabel.setTextColor(getColor(R.color.m3_on_primary))
                ctaIcon.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                        getColor(R.color.m3_on_primary),
                    )
                ctaIcon.setImageResource(R.drawable.ic_play)
                ctaLabel.text = "启动连接"
                ctaButton.isEnabled = true
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTING -> {
                heroSubline.text = SessionController.phaseHint ?: DEFAULT_CONNECTING_HINT
                ctaButton.setBackgroundResource(R.drawable.bg_cta_fill)
                ctaLabel.setTextColor(getColor(R.color.m3_on_primary))
                ctaIcon.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                        getColor(R.color.m3_on_primary),
                    )
                ctaIcon.setImageResource(R.drawable.ic_close)
                ctaLabel.text = "取消连接"
                ctaButton.isEnabled = true
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTED -> {
                ctaButton.setBackgroundResource(R.drawable.bg_cta_outline_err)
                ctaLabel.setTextColor(getColor(R.color.m3_error))
                ctaIcon.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                        getColor(R.color.m3_error),
                    )
                ctaIcon.setImageResource(R.drawable.ic_power)
                ctaLabel.text = "断开连接"
                ctaButton.isEnabled = true
                audioTag.visibility = View.VISIBLE
            }
        }

        ctaIcon.rotation = 0f
    }

    private fun updateConnectedSubline() {
        if (heroState != HeroState.CONNECTED) return
        val elapsed = SystemClock.elapsedRealtime() - connectedAt
        val mm = elapsed / 60_000
        val ss = elapsed % 60_000 / 1_000
        val ssid =
            ssidInput.text
                .toString()
                .trim()
                .ifEmpty { "车机热点" }
        heroSubline.text = String.format(Locale.US, "已连接 · %s · %02d:%02d · 视频推流中", ssid, mm, ss)
    }

    private fun updateAudioTag() {
        if (heroState != HeroState.CONNECTED) return
        val on =
            SessionController.audioEnabledForSession &&
                SessionController.audioSender?.isRunning == true
        val icon =
            ContextCompat.getDrawable(
                this,
                if (on) R.drawable.ic_music_small else R.drawable.ic_volume_off_small,
            )!!
        DrawableCompat.setTint(icon.mutate(), getColor(if (on) R.color.m3_ok else R.color.m3_outline))
        audioTag.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        audioTag.text = if (on) "车机侧声音 · 开启" else "车机侧声音 · 关闭"
    }

    /** 连接准备计数：热点 SSID / 蓝牙配对 / 投屏权限。 */
    private fun updateReadyRow() {
        var ready = 0
        if (ssidInput.text
                .toString()
                .trim()
                .isNotEmpty()
        ) {
            ready++
        }
        val btGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        var bondedCount = -1
        if (btGranted) {
            try {
                val adapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                bondedCount = adapter?.bondedDevices?.size ?: -1
            } catch (_: SecurityException) {
                bondedCount = -1
            }
        }
        if (bondedCount > 0) ready++
        if (com.lizhihua.carplaysender.service.CarPlayService.mediaProjection != null) ready++

        btBadge.visibility = if (bondedCount >= 0) View.VISIBLE else View.GONE
        if (bondedCount >= 0) {
            if (bondedCount > 0) {
                btBadge.background.mutate().setTint(getColor(R.color.m3_ok_dot))
                btBadge.text = "就绪"
                btDesc.text = "已与 $bondedCount 台设备配对 · 连接自动回连"
            } else {
                btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                btBadge.text = "未配对"
                btDesc.text = "尚未配对——请先与车机蓝牙配对"
            }
        }
        val projectionReady =
            com.lizhihua.carplaysender.service.CarPlayService.mediaProjection != null
        if (projectionReady) {
            btnAudio.text = "已授权"
            btnAudio.setTextColor(getColor(R.color.m3_ok))
            audioDesc.text = "投屏权限已就绪 · 连接时可选开启"
        } else {
            btnAudio.text = "授权"
            btnAudio.setTextColor(getColor(R.color.m3_primary))
            audioDesc.text = "需要「投屏」权限，可选 · 关闭时车机无声音"
        }
        readyCount.text = "$ready/3"
    }

    /** 设备营销名（ro.product.marketname），取不到时退回 Build.MODEL。 */
    private fun deviceName(): String =
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            (get.invoke(null, "ro.product.marketname") as? String)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (_: Exception) {
            Build.MODEL
        }

    private fun updateShizukuChip() {
        val ready =
            com.lizhihua.carplaysender.shizuku.ShizukuGate
                .state(this) ==
                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY
        shizukuChip.text = if (ready) "车机服务在线" else "Shizuku 未就绪"
        shizukuChip.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
        // 副标题与原型一致：设备名 · Shizuku 状态（点色随状态）
        deviceSubtitle.text = "${deviceName()} · ${if (ready) "Shizuku 就绪" else "Shizuku 未就绪"}"
        deviceSubtitle.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
    }

    // ==================== 通用 UI 工具 ====================

    private fun log(message: String) {
        // 写入进程级缓冲（logcat + 文件 + 挂屏渲染均由 SessionController 统一处理）
        SessionController.appendLog(message)
    }

    private fun updateLogBottomButton() {
        findViewById<ImageView>(R.id.btnLogBottom)?.visibility =
            if (consoleAtBottom()) View.INVISIBLE else View.VISIBLE
    }

    /** 日志内容最大滚动偏移。 */
    private fun consoleMaxScroll(): Int {
        val layout = logText.layout ?: return 0
        return (
            layout.getLineBottom(layout.lineCount - 1) +
                logText.paddingBottom - logText.height
        ).coerceAtLeast(0)
    }

    /** 是否已在底部（40px 容差：吸收行高舍入，避免永远差几像素判不到底）。 */
    private fun consoleAtBottom(): Boolean = consoleMaxScroll() - logText.scrollY <= 40

    private fun scrollConsoleToBottom() {
        // +16px 过冲补齐舍入差（控制台深色底，过冲不可见；View 不钳制但视觉无感）
        logText.scrollTo(0, consoleMaxScroll() + 16)
        logText.post { updateLogBottomButton() }
    }

    private fun renderConsole(text: String) {
        // 改文本前已在底部（或用户上翻阅读中）：决定本次是否自动跟底
        val follow = consoleAtBottom()
        logText.text =
            if (SessionController.logEnabled) text else "日志采集已关闭（打开「监听日志」开关恢复）"
        logText.post {
            if (follow) scrollConsoleToBottom() else updateLogBottomButton()
        }
    }

    private var snackRunnable: Runnable? = null

    /** 自绘 M3 Snackbar（底部反色胶囊，替代 Toast）。 */
    private fun snack(message: String) {
        snackbar.text = message
        snackbar.visibility = View.VISIBLE
        snackbar.translationY = snackbar.height + 60f
        snackbar
            .animate()
            .translationY(0f)
            .setDuration(260L)
            .start()
        snackRunnable?.let { mainHandler.removeCallbacks(it) }
        snackRunnable =
            Runnable {
                snackbar
                    .animate()
                    .translationY(snackbar.height + 60f)
                    .setDuration(260L)
                    .withEndAction { snackbar.visibility = View.GONE }
                    .start()
            }.also { mainHandler.postDelayed(it, 2_400L) }
    }

    /**
     * 解析 dumpsys display 中的 CarPlay 虚拟屏：返回 (displayId, 名称)。
     * 注意 uniqueId 形如 virtual:com.android.shell,2000,CarPlayDock,36——尾数 36 是
     * 代次计数器而非显示屏 ID；真实 ID 在 "displayId=39, uniqueId='…'" 段里。
     */
    private fun parseCarPlayDisplays(dump: String): List<Pair<Int, String>> =
        Regex("displayId=(\\d+), uniqueId='[^']*CarPlay(Dock|AppHi|App|Map)")
            .findAll(dump)
            .map { it.groupValues[1].toInt() to "CarPlay${it.groupValues[2]}" }
            .distinctBy { it.first }
            .toList()

    /** 读取 shell 全局状态（后台线程 + UI 回填）。[startupCheck] 时顺带做残留虚拟屏检测。 */
    private fun refreshShellState(startupCheck: Boolean = false) {
        if (startupCheck) {
            startupCheckRunning = true
            tickHero()
        }
        shellStateText.text = "读取中…"
        Thread {
            if (com.lizhihua.carplaysender.shizuku.ShizukuGate
                    .state(this) !=
                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY
            ) {
                runOnUiThread {
                    startupCheckRunning = false
                    if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                    shellStateText.text = "Shizuku 未就绪——shell 状态读取需要 Shizuku"
                }
                return@Thread
            }

            fun sh(cmd: String) =
                com.lizhihua.carplaysender.shizuku.Shell
                    .exec(cmd, 4_000L)
                    .output
            val letterbox =
                Regex("Aspect ratio:\\s*([0-9.]+)")
                    .find(sh("cmd window get-letterbox-style"))
                    ?.groupValues
                    ?.get(1) ?: "?"
            val forceResizable = sh("settings get global force_resizable_activities").ifBlank { "null" }
            val overlay = sh("settings get global overlay_display_devices").ifBlank { "null" }
            val wmSize = sh("wm size").replace("\n", " / ").ifBlank { "?" }
            val wmDensity = sh("wm density").replace("\n", " / ").ifBlank { "?" }
            val carDisplays = parseCarPlayDisplays(sh("dumpsys display"))
            val displays = carDisplays.joinToString(", ") { "${it.second}(${it.first})" }
            val timeout = sh("settings get system screen_off_timeout").ifBlank { "?" }
            val text =
                buildString {
                    appendLine("letterbox 宽高比: $letterbox  （1.05=会话调优生效；0.0=系统默认）")
                    appendLine("force_resizable: $forceResizable  （平板模式实验；默认 null/0）")
                    appendLine("overlay_display_devices: $overlay  （叠加屏实验残留；应为 null）")
                    appendLine("wm size: $wmSize")
                    appendLine("wm density: $wmDensity")
                    appendLine("CarPlay 虚拟屏: " + displays.trim().replace("\n", ", ").ifEmpty { "无" })
                    appendLine("screen_off_timeout: $timeout  （App 常亮功能管理）")
                }
            runOnUiThread { shellStateText.text = text.trim() }
            if (startupCheck) {
                val residual = carDisplays.filter { it.first !in currentSessionDisplayIds() }
                if (residual.isNotEmpty()) {
                    log(
                        "SHELL: 启动检测到 ${residual.size} 个残留虚拟屏: " +
                            residual.joinToString(", ") { "${it.second}(${it.first})" },
                    )
                    // 残留屏 = 上次会话异常退出（进程被杀）：横屏覆盖的会话末复原
                    // 也没执行——一并清理，避免应用在手机侧残留横屏状态
                    runCatching {
                        val reset =
                            com.lizhihua.carplaysender.launcher.settings.AppOrientationCompat
                                .resetAll(applicationContext)
                        if (reset > 0) log("ORIENTATION: 残留会话的横屏覆盖已复原 $reset 个")
                    }
                }
                runOnUiThread {
                    startupCheckRunning = false
                    if (residual.isNotEmpty()) {
                        // 用户「暂不」时保留 pending，状态旁的按钮可再次发起清理
                        pendingResidualDisplays = residual
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        promptResidualDisplays(residual)
                        // 自动展开日志卡：启动清理的两条记录直接可见（卡片默认折叠）
                        findViewById<View>(R.id.logBody).visibility = View.VISIBLE
                        findViewById<ImageView>(R.id.logChevron)
                            .animate()
                            .rotation(180f)
                            .setDuration(250L)
                            .start()
                    } else if (heroState == HeroState.IDLE) {
                        // 无残留：恢复「未连接」默认状态展示
                        applyHeroState(HeroState.IDLE)
                    }
                }
            }
        }.start()
    }

    /** 当前会话持有的虚拟屏（不参与残留清理）。 */
    private fun currentSessionDisplayIds(): Set<Int> =
        buildSet {
            val launcher = com.lizhihua.carplaysender.launcher.VirtualLauncher
            listOf(
                launcher.dockDisplayId,
                launcher.appDisplayId,
                launcher.mapDisplayId,
                launcher.gridDisplayId,
            ).forEach { if (it >= 0) add(it) }
        }

    /** 启动检测到残留 CarPlay 虚拟屏：询问是否立即清理（上次会话异常退出遗留）。 */
    private fun promptResidualDisplays(residual: List<Pair<Int, String>>) {
        if (isFinishing || isDestroyed) return
        val names = residual.joinToString("\n") { "· ${it.second}（displayId=${it.first}）" }
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle("检测到残留虚拟屏")
            .setMessage(
                "发现 ${residual.size} 个上次会话遗留的 CarPlay 虚拟屏：\n$names\n\n" +
                    "残留屏会持续占用显示资源，并可能影响下次连接。是否立即清理？",
            ).setPositiveButton("清理") { _, _ ->
                pendingResidualDisplays = null
                updateResidualChip()
                if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                Thread {
                    val released = releaseDisplays(residual.map { it.first })
                    log("SHELL: 残留虚拟屏清理完成（释放 $released/${residual.size}）")
                    runOnUiThread {
                        snack("已释放 $released/${residual.size} 个残留屏")
                        refreshShellState()
                    }
                }.start()
            }.setNegativeButton("暂不", null)
            .setOnCancelListener {
                // 返回键/点外部关闭 = 暂不：保留英雄卡的「清理残留屏」入口
                updateResidualChip()
            }.show()
    }

    /** 英雄卡状态旁的「清理残留屏」按钮显隐（有待清理残留时出现）。 */
    private fun updateResidualChip() {
        btnResidualCleanup.visibility =
            if (pendingResidualDisplays != null) View.VISIBLE else View.GONE
    }

    /** 释放指定虚拟屏（CarInputClient 懒绑定 + 3 秒短等）。后台线程调用，返回成功数。 */
    private fun releaseDisplays(ids: List<Int>): Int {
        if (ids.isEmpty()) return 0
        var svc = com.lizhihua.carplaysender.shizuku.CarInputClient.service
        if (svc == null) {
            com.lizhihua.carplaysender.shizuku.CarInputClient
                .ensure(this)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(150)
                svc = com.lizhihua.carplaysender.shizuku.CarInputClient.service
            }
        }
        val bound = svc ?: return 0
        return ids.count { runCatching { bound.releaseDisplay(it) }.getOrDefault(false) }
    }

    /** 一键把 shell 全局状态复位为系统默认值（含残留虚拟屏释放，保留当前会话屏）。 */
    private fun resetShellState() {
        shellStateText.text = "复位中…"
        Thread {
            if (com.lizhihua.carplaysender.shizuku.ShizukuGate
                    .state(this) !=
                com.lizhihua.carplaysender.shizuku.ShizukuGate.State.READY
            ) {
                runOnUiThread { shellStateText.text = "Shizuku 未就绪——shell 复位需要 Shizuku" }
                return@Thread
            }

            fun sh(cmd: String) =
                com.lizhihua.carplaysender.shizuku.Shell
                    .exec(cmd, 5_000L)
            val steps =
                listOf(
                    "letterbox 宽高比" to "cmd window reset-letterbox-style aspectRatio",
                    "overlay 叠加屏" to "settings delete global overlay_display_devices",
                    "force_resizable" to "settings delete global force_resizable_activities",
                    "wm size" to "wm size reset",
                    "wm density" to "wm density reset",
                )
            val results =
                steps.map { (name, cmd) ->
                    val r = sh(cmd)
                    if (r.ok) "✓ $name" else "✗ $name（${r.output.take(40)}）"
                }
            // 残留虚拟屏释放：保留当前会话的各屏，其余 CarPlay* 屏逐一释放
            val ids =
                parseCarPlayDisplays(sh("dumpsys display").output)
                    .map { it.first }
                    .filter { it !in currentSessionDisplayIds() }
                    .toList()
            val released = releaseDisplays(ids)
            val summary = results.joinToString("；") + "；残留屏释放 $released/${ids.size}"
            log("SHELL-RESET: $summary")
            runOnUiThread {
                shellStateText.text = "复位结果：\n" + results.joinToString("\n") +
                    "\n残留虚拟屏: 释放 $released/${ids.size}（保留当前会话屏）\n" +
                    "提示：letterbox 调优会在下次连接时自动重新应用"
                refreshShellState()
            }
        }.start()
    }

    /** 悬浮窗权限请求：先弹说明弹窗，用户确认后再跳系统设置。 */
    private fun promptOverlayPermission(reason: String) {
        if (android.provider.Settings.canDrawOverlays(this)) return
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            .setTitle("需要「显示在其他应用上层」权限")
            .setMessage(
                "$reason\n\n" +
                    "用途：无操作时在屏幕上盖一层暗幕（替代息屏，防止手机锁屏后车机触摸失效）。" +
                    "不会读取或遮挡其他应用内容，点击屏幕立即消失。",
            ).setPositiveButton("去授权") { _, _ ->
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
            }.setNegativeButton("暂不") { _, _ ->
                log("DIMMER: 用户暂未授权悬浮窗（暗幕不可用，常亮仍生效）")
            }.show()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(heroTick)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(heroTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只解绑"挂屏"，不动会话——连接由前台服务保活、对象归 SessionController 持有；
        // 显式断开请用「断开连接」按钮（任意实例都能断到真管线）。
        if (SessionController.logListener === logListener) SessionController.logListener = null
        if (SessionController.ssidSink === ssidSink) SessionController.ssidSink = null
    }
}
