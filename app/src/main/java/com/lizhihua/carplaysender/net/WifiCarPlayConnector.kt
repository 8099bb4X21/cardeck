package com.lizhihua.carplaysender.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
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

    fun request(ssid: String) {
        release()
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                boundNetwork.set(network)
                val bound = connectivityManager.bindProcessToNetwork(network)
                log("WiFi: 网络可用 network=$network bindProcessToNetwork=$bound（请在弹窗输入热点密码）")
            }

            override fun onUnavailable() {
                log("WiFi: 请求失败（超时/用户拒绝/未找到该 SSID）")
            }

            override fun onLost(network: Network) {
                if (boundNetwork.compareAndSet(network, null)) {
                    connectivityManager.bindProcessToNetwork(null)
                    log("WiFi: 网络已断开")
                }
            }
        }
        callback.set(newCallback)
        log("WiFi: 请求加入 \"$ssid\"（若弹出系统面板请选择该热点并输入密码）")
        connectivityManager.requestNetwork(request, newCallback)
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
