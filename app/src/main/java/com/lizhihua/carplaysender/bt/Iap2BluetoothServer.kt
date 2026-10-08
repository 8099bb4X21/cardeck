package com.lizhihua.carplaysender.bt

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lizhihua.carplaysender.util.HexDumpObserver
import java.io.IOException
import java.util.UUID

/**
 * 手机侧（iPhone 角色）的 iAP2 蓝牙 RFCOMM 监听。
 *
 * 真实无线 CarPlay 里 iPhone 配对后会监听这个 SDP 服务等待车机回连；
 * 测试台上 xcertplay 会扫描已配对的 PHONE_SMART 设备并主动连入。
 *
 * Phase 0：接受连接后只做十六进制转储——能直接观察到 FF55 SYN / FF5A 链路帧。
 * Phase 1：把流交给 Iap2LinkEngine 状态机。
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        /**
         * iPhone 的 iAP2 服务 UUID（Oligo Security 公布；与 xcertplay
         * CarPlayController.IAP2_IPHONE_UUID 一致）。配件端是 …decacaff。
         */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val SDP_NAME = "iAP2"
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var observers = mutableListOf<HexDumpObserver>()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter = try {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        } catch (_: Exception) {
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: 蓝牙不可用或未开启")
            return false
        }
        return try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID)
            running = true
            log("BT: 监听 $SDP_NAME ($IPHONE_IAP2_UUID)，等待接收端回连…")
            Thread(::acceptLoop, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            log("BT: 监听失败 - ${error.message}")
            false
        }
    }

    private fun acceptLoop() {
        while (running) {
            val socket: BluetoothSocket = try {
                serverSocket?.accept() ?: break
            } catch (error: IOException) {
                if (running) log("BT: accept 失败 - ${error.message}")
                break
            }
            val peerName = try {
                socket.remoteDevice?.name
            } catch (_: SecurityException) {
                null
            } ?: "unknown"
            val peerAddress = try {
                socket.remoteDevice?.address
            } catch (_: SecurityException) {
                null
            } ?: "unknown"
            log("BT: 接收端已连入 name=$peerName address=$peerAddress，开始转储字节流")
            val observer = HexDumpObserver(socket.inputStream, "BT", onLine = { line -> log(line) })
            observers += observer
            observer.start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        observers.clear()
        log("BT: 监听已停止")
    }
}
