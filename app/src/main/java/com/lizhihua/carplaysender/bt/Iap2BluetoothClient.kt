package com.lizhihua.carplaysender.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lizhihua.carplaysender.iap2.Iap2SenderSession
import com.lizhihua.carplaysender.transport.Iap2LinkChannel
import com.lizhihua.carplaysender.transport.StreamDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机侧（iPhone 角色）**主动连接车机**的无线 iAP2 RFCOMM 客户端 —— 真车阶段新增。
 *
 * 真无线 CarPlay 建链方向（对照 carplay-wifi-extractor client 模式，已在真车验证）：
 * 手机与车机完成系统级蓝牙配对后，由**手机主动连入**车机 SDP 广播的配件端服务
 * （UUID 00000000-deca-fade-deca-deafdecacaff，奥迪 MIB3 SDP 实测存在）；
 * 车机从不主动回连手机（测试台 DiPlay 会回连，那是接收端的测试行为）。
 *
 * 连上后复用与测试台完全相同的发送端 bring-up（[Iap2SenderSession]），
 * 成功时回调 [onEndpoint] 交出热点凭据与 AirPlay 端点。
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
) {
    companion object {
        /**
         * 配件端（车机）无线 iAP2 服务 UUID。
         * 手机端 UUID 是 …cafe（xcertplay IAP2_IPHONE_UUID），配件端是 …caff。
         */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (_: Exception) {
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: 蓝牙不可用或未开启")
            return false
        }
        running = true
        log("BT-C: 启动——将主动连入车机的无线 iAP2 服务（…caff）")
        Thread(::connectLoop, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop() {
        val adapter = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (_: Exception) {
            null
        } ?: return
        var lastFailureLogAt = 0L
        while (running) {
            // 车机正在连我们（入站会话活跃）时不要出站——两条 RFCOMM 会互相踩，
            // 车机侧表现为双会话混乱（Audi 实测：出站并行时双方都静默）
            if (BtSessionGate.inboundActive) {
                sleepQuietly(2_000)
                continue
            }
            val candidates = candidateDevices(adapter)
            if (candidates.isEmpty()) {
                log("BT-C: 没有已配对的蓝牙设备——请先在系统设置里与车机完成蓝牙配对")
                sleepQuietly(RETRY_DELAY_MILLIS * 3)
                continue
            }
            var connected: BluetoothSocket? = null
            for (device in candidates) {
                if (!running) return
                val name = try {
                    device.name
                } catch (_: SecurityException) {
                    null
                } ?: device.address
                runCatching { adapter.cancelDiscovery() }
                val socket = tryConnect(device)
                if (socket != null) {
                    log("BT-C: ✓ 已连入 $name 的无线 iAP2 服务（RFCOMM 建立）")
                    connected = socket
                    break
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        log("BT-C: 连接 $name 失败（车机未就绪或不在无线配对窗口），3 秒后重试…")
                    }
                }
            }
            if (connected == null) {
                sleepQuietly(RETRY_DELAY_MILLIS)
                continue
            }
            runSession(connected)
            sleepQuietly(RETRY_DELAY_MILLIS)
        }
        log("BT-C: 客户端已停止")
    }

    /**
     * 候选设备：优先 SDP 缓存里带配件端 UUID 的已配对设备；
     * 缓存缺失时退化为全部已配对设备（connect 时的 SDP 查询会自行过滤）。
     */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        val withService = bonded.filter { device ->
            device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
        }
        return if (withService.isNotEmpty()) withService else bonded
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(device: BluetoothDevice): BluetoothSocket? {
        val socket = try {
            device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
        } catch (error: IOException) {
            return null
        }
        return try {
            socket.connect()
            currentSocket.set(socket)
            socket
        } catch (error: IOException) {
            runCatching { socket.close() }
            null
        }
    }

    private fun runSession(socket: BluetoothSocket) {
        val stream = StreamDuplexStream(socket.inputStream, socket.outputStream) {
            runCatching { socket.close() }
        }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt-car") { line -> log(line) }
        activeChannel = channel
        try {
            val mac = com.lizhihua.carplaysender.util.DeviceIdentity.bluetoothMac(context)
            val name = com.lizhihua.carplaysender.util.DeviceIdentity.deviceName(context)
            val session = Iap2SenderSession(
                channel,
                { line -> log(line) },
                mac,
                name,
                onWifiCredentials,
            )
            val endpoint = session.run(SESSION_TIMEOUT_MILLIS)
            onEndpoint(endpoint)
            session.pumpIncomingForever()
        } catch (error: IOException) {
            log("BT-C: SESSION 失败 - ${error.message}")
        } finally {
            activeChannel = null
            currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
            runCatching { socket.close() }
            channel.close()
            log("BT-C: 本轮会话结束（客户端仍在运行，将自动重连）")
        }
    }

    /** 主动结束当前 BT 控制会话（不影响重连循环）。供 AirPlay 配对前复现控制通道退役状态。 */
    fun detachCurrentSession() {
        activeChannel?.let { channel ->
            activeChannel = null
            channel.close()
            log("BT-C: 已主动断开当前控制会话")
        }
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
    }

    fun stop() {
        running = false
        currentSocket.getAndSet(null)?.let { runCatching { it.close() } }
        activeChannel?.let { runCatching { it.close() } }
        thread?.interrupt()
        thread = null
        log("BT-C: 已停止")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }
}

/**
 * 入站会话门闸：车机主动连入的 iAP2 会话活跃期间，出站客户端暂停连接尝试。
 * 目的：车机通常只有单条无线 iAP2 上下文，双向同时建链会让双方互相干扰。
 */
object BtSessionGate {
    @Volatile
    var inboundActive: Boolean = false
}
