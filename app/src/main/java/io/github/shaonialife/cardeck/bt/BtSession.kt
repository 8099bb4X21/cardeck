package io.github.shaonialife.cardeck.bt

import android.content.Context
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.util.DeviceIdentity

/**
 * BT 控制会话核心流程（Server/Client 共用，此前两份近乎复制）：
 * 构建发送端会话 → Phase 1 交付端点 → 保活泵（阻塞直至链路断开）。
 * socket 封装 / activeChannel 登记 / 生命周期收尾由两条路径各自处理。
 */
internal fun Iap2LinkChannel.runSenderSession(
    context: Context,
    timeoutMillis: Long,
    log: (String) -> Unit,
    onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit,
    onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
) {
    val session =
        Iap2SenderSession(
            this,
            { line -> log(line) },
            DeviceIdentity.bluetoothMac(context),
            DeviceIdentity.deviceName(context),
            onWifiCredentials,
            onEndpointUpdate,
            // MFi 挑战长度按车机身份持久化（省掉 20B 静默 6 秒的试探）
            context.applicationContext,
        )
    val endpoint = session.run(timeoutMillis)
    onEndpoint(endpoint)
    // Phase 2 过渡：保持控制通道存活记录配件消息；AirPlay 配对由 onEndpoint 回调发起
    session.pumpIncomingForever()
}
