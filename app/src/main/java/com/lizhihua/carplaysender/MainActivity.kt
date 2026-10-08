package com.lizhihua.carplaysender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
        bluetoothServer = Iap2BluetoothServer(this) { line -> log(line) }
        ctrlAdvertiser = CarPlayCtrlAdvertiser(this) { line -> log(line) }

        findViewById<Button>(R.id.btnWifi).setOnClickListener {
            val ssid = ssidInput.text.toString().trim()
            if (ssid.isEmpty()) {
                log("WiFi: 请先输入接收端热点 SSID")
            } else {
                wifiConnector.request(ssid)
            }
        }
        findViewById<Button>(R.id.btnScan).setOnClickListener {
            serviceBrowser.start(CarPlayServiceBrowser.AIRPLAY_TYPE)
        }
        findViewById<Button>(R.id.btnBluetooth).setOnClickListener {
            if (!bluetoothServer.hasPermission()) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            bluetoothServer.start()
        }
        findViewById<Button>(R.id.btnAdvertise).setOnClickListener {
            ctrlAdvertiser.start()
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
        wifiConnector.release()
        serviceBrowser.stop()
        bluetoothServer.stop()
        ctrlAdvertiser.stop()
    }
}
