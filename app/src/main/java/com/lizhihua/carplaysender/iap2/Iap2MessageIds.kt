package com.lizhihua.carplaysender.iap2

/**
 * 无线 CarPlay 会话中「发送端（iPhone 角色）」视角的消息目录。
 *
 * 完整无线时序（对照 reference/xcertplay Iap2WirelessControlClient 与
 * reference/carplay-wifi-extractor README 的抓包流程）：
 *
 *  1. 蓝牙 RFCOMM 建链（两台设备需先完成系统级蓝牙配对）
 *  2. 链路层握手：对端发 FF55 SYN → 我方 SYN-ACK → 双方 FF5A 参数协商
 *  3. 手机 → 配件: StartIdentification(0x1D00)
 *     配件 → 手机: IdentificationInformation(0x1D01)   // 车机身份 + 能力清单
 *     手机 → 配件: IdentificationAccepted(0x1D02)
 *  4. MFi 认证（0xAA00 段，配件证明自己；手机侧可跳过校验）
 *  5. 手机 → 配件: RequestAccessoryWiFiConfiguration(0x5702)
 *     配件 → 手机: AccessoryWiFiConfiguration(0x5703)   // 明文 TLV：SSID/密码/信道
 *  6. 手机加入热点，mDNS 解析配件的 _airplay._tcp
 *  7. 手机 → 配件: CarPlayAvailability(0x4300)
 *     配件 → 手机: CarPlayStartSession(0x4301)          // AirPlay 端口/地址/公钥
 *  8. 手机 → 配件: WirelessCarPlayUpdate(0x4E0D)、DeviceTransportIdentifier(0x4E0E)
 *  9. AirPlay 主会话（RTSP 风格 + H.264 流 + HID 触摸回传）—— Phase 2
 */
object Iap2MessageIds {
    // ---- 识别（Identification）----
    const val START_IDENTIFICATION = 0x1D00        // 手机 → 配件
    const val IDENTIFICATION_INFORMATION = 0x1D01  // 配件 → 手机
    const val IDENTIFICATION_ACCEPTED = 0x1D02     // 手机 → 配件
    const val IDENTIFICATION_REJECTED = 0x1D03     // 配件 → 手机

    // ---- 无线 CarPlay ----
    const val CARPLAY_AVAILABILITY = 0x4300        // 手机 → 配件：询问能否开始
    const val CARPLAY_START_SESSION = 0x4301       // 配件 → 手机：无线参数 + AirPlay 端点
    const val WIRELESS_CARPLAY_UPDATE = 0x4E0D     // 手机 → 配件：无线可用性通知
    const val DEVICE_TRANSPORT_IDENTIFIER = 0x4E0E // 手机 → 配件：蓝牙/USB 标识通知

    // ---- WiFi 配置交换 ----
    const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702      // 手机 → 配件
    const val ACCESSORY_WIFI_CONFIGURATION = 0x5703              // 配件 → 手机

    // ---- HID（Phase 2：车机触摸回传）----
    const val START_HID = 0x6800                  // 配件 → 手机
    const val DEVICE_HID_REPORT = 0x6801          // 手机 → 配件（应答）
    const val ACCESSORY_HID_REPORT = 0x6802       // 配件 → 手机（触摸/旋钮事件）
    const val STOP_HID = 0x6803

    // ---- MFi 认证（Phase 1；Oligo 证实可单向跳过校验）----
    const val MFI_AUTH_BASE = 0xAA00

    fun nameOf(messageId: Int): String = when (messageId) {
        START_IDENTIFICATION -> "StartIdentification"
        IDENTIFICATION_INFORMATION -> "IdentificationInformation"
        IDENTIFICATION_ACCEPTED -> "IdentificationAccepted"
        IDENTIFICATION_REJECTED -> "IdentificationRejected"
        CARPLAY_AVAILABILITY -> "CarPlayAvailability"
        CARPLAY_START_SESSION -> "CarPlayStartSession"
        WIRELESS_CARPLAY_UPDATE -> "WirelessCarPlayUpdate"
        DEVICE_TRANSPORT_IDENTIFIER -> "DeviceTransportIdentifier"
        REQUEST_ACCESSORY_WIFI_CONFIGURATION -> "RequestAccessoryWiFiConfiguration"
        ACCESSORY_WIFI_CONFIGURATION -> "AccessoryWiFiConfiguration"
        START_HID -> "StartHID"
        DEVICE_HID_REPORT -> "DeviceHIDReport"
        ACCESSORY_HID_REPORT -> "AccessoryHIDReport"
        STOP_HID -> "StopHID"
        else -> "0x%04X".format(messageId)
    }
}
