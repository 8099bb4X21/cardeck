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

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BLUETOOTH_CONNECT 授权: $granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
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
                        // 第一阶段：保持 BT 在位直接配对 + SETUP（协议上正确的形态）
                        var client = newClient()
                        var keys = client.runPairing()
                        if (keys == null) {
                            // 第二阶段：复现「BT 控制已结束」状态再试一次
                            bluetoothServer.detachCurrentSession()
                            client = newClient()
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
                                val uiState = com.lizhihua.carplaysender.video.CarUiState(1080, 2320)
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
                                                contacts.forEach(uiState::onTouch)
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
                                        com.lizhihua.carplaysender.video.VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            streamKey = screen.streamKey,
                                            width = 1080,
                                            height = 2320,
                                            state = uiState,
                                            log = { line -> log(line) },
                                        ).start()
                                    }
                                }
                            }
                        }
                    }, "airplay-pairing").start()
                }
            }
        }
        ctrlAdvertiser = CarPlayCtrlAdvertiser(this) { line -> log(line) }

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
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            // 热点无互联网时不是默认网络：先绑定进程到 WiFi，再启动 mDNS 扫描
            wifiConnector.bindCurrentWifi { bound ->
                if (bound) {
                    serviceBrowser.start(CarPlayServiceBrowser.AIRPLAY_TYPE)
                }
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
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            com.lizhihua.carplaysender.service.CarPlayService.stop(this)
            bluetoothServer.stop()
            ctrlAdvertiser.stop()
            wifiConnector.release()
            log("已手动断开并停止保活服务")
        }
        findViewById<Button>(R.id.btnAdvertise).setOnClickListener {
            ctrlAdvertiser.start()
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

        log("CarPlay Sender Phase 0 就绪")
        log("测试台流程：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 本机 ③ 监听")
    }

    private fun log(message: String) {
        runOnUiThread {
            logBuilder.append('[').append(timestamp.format(Date())).append("] ")
                .append(message).append('\n')
            if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
            logText.text = logBuilder
            statusText.text = "wifi=${wifiConnector.currentNetwork != null} " +
                "bt=${bluetoothServer.isRunning} ctrl=${ctrlAdvertiser.port > 0}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不再在这里拆连接——Activity 销毁（如划掉任务）时连接由前台服务保活；
        // 显式断开请用「断开」按钮。
    }
}
