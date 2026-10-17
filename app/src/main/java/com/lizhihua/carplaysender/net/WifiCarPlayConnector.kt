package com.lizhihua.carplaysender.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.lizhihua.carplaysender.util.Bg
import java.util.concurrent.atomic.AtomicReference

/**
 * 用 Wi-Fi Network Request API（app 专属网络）加入接收端热点。
 *
 * 关键点：
 *  - 请求不带 NET_CAPABILITY_INTERNET，系统不会因「无互联网」拆连接；
 *  - 得到的网络只对本进程可见，必须 bindProcessToNetwork 后再建 socket；
 *  - 首次连接会弹系统确认框；SSID 必须精确匹配。
 *
 * 正式流程中 SSID/密码来自 iAP2 0x5703（0x5702 请求）。Phase 0 先手动输入。
 * 注意：WifiNetworkSpecifier 不支持传密码——密码在系统弹窗中由用户输入；
 * 真机 Phase 3 需要 root/系统权限或改用 WiFi 配网的其他路径（已列入风险）。
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    val currentNetwork: Network? get() = boundNetwork.get()
    private val boundNetwork = AtomicReference<Network?>(null)

    /**
     * 当前绑定网络的网关地址（车机热点场景 = 车机 IP）。
     * mDNS 发现失败时的兜底路径：Audi 的 AirPlay 服务在网关 10.174.189.1:7000。
     */
    fun gatewayAddress(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 本机在当前绑定网络上的 IPv4 地址（如 10.174.189.57）。
     * 用于 mDNS 结果过滤：手机双归属（连车机热点 + 自己开热点共享给电脑）时，
     * mDNS 会跨网发现电脑的 AirPlay 接收器——非本网段地址一律不是车机。
     */
    fun localIpv4Address(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 绑定到当前已连接的 WiFi 网络（用户经系统设置手动加入热点后调用）。
     * 热点无互联网时系统可能不把它设为默认网络，必须显式 bindProcessToNetwork，
     * 我们的 socket 才会走 WiFi 而不是蜂窝。
     * 带看门狗：WiFi 未连接时 requestNetwork 会无限静默挂起——5 秒无果给出明确指引
     * （请求保持挂起，用户加入热点后仍会自动完成绑定并回调）。
     */
    fun bindCurrentWifi(onResult: (Boolean) -> Unit = {}) {
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        // 网络回调线程与 Bg 看门狗线程并发读写——原子标志保证可见性与单次投递
        val reported =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    boundNetwork.set(network)
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    log("WiFi: 已绑定当前WiFi网络 $network bindProcessToNetwork=$bound")
                    if (reported.compareAndSet(false, true)) onResult(true)
                }

                override fun onUnavailable() {
                    log("WiFi: 没有可用的WiFi连接——请先在系统设置里加入接收端热点")
                    if (reported.compareAndSet(false, true)) onResult(false)
                }
            }
        callback.set(newCallback)
        connectivityManager.requestNetwork(request, newCallback)
        // 提示性看门狗（**不决定成败**）：requestNetwork 在「手机尚未连上 WiFi」时
        // 会一直挂起，等系统关联+DCHP 完成才回调 onAvailable——实测用户手动加入
        // 热点时可达 10s+。这里只在 5s 时给用户一句指引，超时判定由调用方的
        // WIFI_BIND_WAIT_S 负责；不要在这里取消请求（回调晚到仍会正确绑定）。
        Bg.run("wifi-bind-watchdog") {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@run
            }
            if (!reported.get()) {
                log("WiFi: 5 秒尚未绑定（手机可能还没连上热点）——请到 设置>WLAN 加入接收端热点，加入后本轮绑定会自动完成")
            }
        }
    }

    fun release() {
        callback.getAndSet(null)?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        if (boundNetwork.getAndSet(null) != null) {
            connectivityManager.bindProcessToNetwork(null)
        }
    }
}
