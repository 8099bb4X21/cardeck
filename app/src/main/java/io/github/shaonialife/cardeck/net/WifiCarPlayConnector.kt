package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import io.github.shaonialife.cardeck.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 用车机热点承载 CarPlay 流量。
 *
 * **两种入网方式（2026-10-22 起默认静默命令行）**：
 *  - 静默路径（默认，见 `SessionController.joinCarWifiSilently`）：Shizuku 执行
 *    `cmd wifi connect-network`，系统直接把热点连上（**0 弹窗**），随后
 *    [bindCurrentWifi] 用**不带 specifier** 的 `requestNetwork` 捕获 `Network` 句柄并绑定。
 *  - [joinCarPlayNetwork]：`WifiNetworkSpecifier` 应用专属网络——静默路径不可用时的兜底。
 *    网络只对本进程可见（不保存、不成为默认网络），但首次加入会弹系统确认框。
 *
 * 关键点：
 *  - 请求不带 `NET_CAPABILITY_INTERNET`，系统不会因「无互联网」拆连接；
 *  - 两条路径都必须 `bindProcessToNetwork` 后再建 socket / 注册 mDNS——mDNS 广播
 *    (`NsdManager.registerService`) 没有 Network 参数，只能跟随进程默认网络；
 *  - SSID 必须精确匹配（热点名可能带空格/中文）：静默路径用 `cmd wifi status` 核对。
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** 应用专属网络的连接结果。 */
    sealed interface JoinResult {
        /** 已连接并绑定到本进程；[network] 可用于 NSD/端点判断。 */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** 系统未允许（用户拒绝/热点不在范围/超时）。可重试。 */
        data object Unavailable : JoinResult

        /** 该安全类型无法用 WifiNetworkSpecifier 表达（WEP）——调用方走旧路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    val currentNetwork: Network? get() = boundNetwork.get()
    private val boundNetwork = AtomicReference<Network?>(null)

    /**
     * 应用专属网络被系统断开（热点关闭/离开范围）时回调。会话层据此提示用户；
     * 空实现 = 不关心（例如手动绑定路径）。
     */
    @Volatile
    var onNetworkLost: ((ssid: String) -> Unit)? = null

    /**
     * 当前绑定网络的网关地址（车机热点场景 = 车机 IP）。
     * mDNS 发现失败时的兜底路径。
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
     * 本机在当前绑定网络上的全部 IPv6 地址。
     *
     * mDNS 结果的网段验收用：奥迪 4xxx 的 `_airplay._tcp` 常只发链路本地 IPv6，
     * 旧过滤只按 IPv4 前缀判定，会把车机本身也当成「非本网段」丢掉（2026-10-19）。
     */
    fun localIpv6Addresses(): List<String> {
        val network = boundNetwork.get() ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 用应用专属网络（`WifiNetworkSpecifier`）加入车机热点——**静默路径不可用时的兜底**。
     * 首次加入会弹一次系统确认框；网络不保存、不成为默认网络。
     *
     * 阻塞直到连接成功/系统拒绝/超时（内部 200ms 分片，[isCancelled] 可即时退出）。
     * 成功后进程网络已绑定，调用方可直接注册 mDNS / 建 socket。
     *
     * 安全类型映射按 0x5703（xcertplay Iap2WirelessSecurity）：
     * 0=NONE 1=WEP 2=WPA/WPA2 3=WPA3 过渡 4=WPA3 仅。
     * WEP 无对应 API（返回 [JoinResult.Unsupported]，调用方走旧路径）。
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        isCancelled: () -> Boolean = { false },
    ): JoinResult {
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        release()
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val unavailable = AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    boundNetwork.set(network)
                    result.set(network)
                    log("WiFi: 已加入应用专属网络 \"$ssid\"（$network bindProcessToNetwork=$bound）——不影响其它应用联网")
                    latch.countDown()
                }

                override fun onUnavailable() {
                    unavailable.set(true)
                    log("WiFi: 系统未允许加入 \"$ssid\"（用户拒绝或热点不在范围内）")
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        connectivityManager.bindProcessToNetwork(null)
                        log("WiFi: 应用专属网络 \"$ssid\" 已断开（$network）")
                        onNetworkLost?.invoke(ssid)
                    }
                }
            }
        callback.set(newCallback)
        val accepted =
            try {
                connectivityManager.requestNetwork(request, newCallback)
                true
            } catch (error: Exception) {
                log("WiFi: 发起应用专属网络请求失败（${error.message}）——可能需要位置权限或系统不支持")
                false
            }
        if (!accepted) {
            release()
            return JoinResult.Unavailable
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) {
                release()
                return JoinResult.Unavailable
            }
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                release()
                return JoinResult.Unavailable
            }
        }
        val network = result.get()
        if (network == null) {
            log(
                "WiFi: 等待加入 \"$ssid\" 超时（${timeoutMillis / 1000}s）" +
                    if (unavailable.get()) "——系统已判定不可用" else "——系统确认框可能仍在前台",
            )
            release()
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /** 构造 specifier；WEP 或参数不合法返回 null（走旧路径）。 */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // 开放热点：无密码
                0 -> Unit
                // WEP：WifiNetworkSpecifier 无对应 API（现代车机不再使用）
                1 -> return null
                // WPA/WPA2 与 WPA3 过渡模式：过渡模式 AP 同时接受 WPA2-PSK
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // WPA3 仅
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // 未知类型：按有密码的 WPA2 尝试（比直接放弃更可能成功）
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("WiFi: 构造应用专属网络参数失败（${error.message}）")
            null
        }
    }

    /**
     * 绑定「当前已连接的 WiFi」——静默命令行连网成功后用它捕获 `Network` 句柄。
     *
     * 请求**不带 specifier**（只声明 TRANSPORT_WIFI、移除 INTERNET），所以系统不会弹
     * 任何确认框，也会把刚连上的车机 WiFi 立刻回调过来。热点无互联网时系统可能不把它
     * 设为默认网络，必须显式 bindProcessToNetwork，我们的 socket / mDNS 才会走 WiFi。
     * 带看门狗：WiFi 未连接时 requestNetwork 会无限静默挂起——5 秒无果给出明确指引
     * （请求保持挂起，用户加入热点后仍会自动完成绑定并回调）。
     */
    fun bindCurrentWifi(
        ssid: String,
        onResult: (Boolean) -> Unit = {},
    ) {
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

                override fun onLost(network: Network) {
                    if (boundNetwork.compareAndSet(network, null)) {
                        // 熄火/离开范围：解绑进程网络，否则所有 socket 继续指向死网络；
                        // 并通知会话层（英雄卡不能停在「已连接」）
                        connectivityManager.bindProcessToNetwork(null)
                        log("WiFi: 绑定的 WiFi 网络已断开（$network）")
                        onNetworkLost?.invoke(ssid)
                    }
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
