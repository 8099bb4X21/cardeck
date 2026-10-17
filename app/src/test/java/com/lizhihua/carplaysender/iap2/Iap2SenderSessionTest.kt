package com.lizhihua.carplaysender.iap2

import com.lizhihua.carplaysender.transport.Iap2LinkChannel
import com.lizhihua.carplaysender.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 发送端会话对 mock 配件端的端到端回环测试：
 * 内存双工流两端各跑一个链路通道，mock 按 DiPlay/xcertplay 的配件侧时序应答，
 * 验证 Phase 1 全序列（识别→MFi 跳过→HID→WiFi 配置→CarPlay 启动→通知）。
 */
class Iap2SenderSessionTest {
    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** 按 DiPlay Iap2WirelessControlClient 的时序脚本化应答。 */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, "MockCarPlay"),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64) { it.toByte() }))
            expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            // 订阅 + StartHID
            send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
            send(Iap2MessageIds.START_POWER_UPDATES)
            send(
                Iap2MessageIds.START_HID,
                Iap2Csm.u16(0, 0),
                Iap2Csm.u16(1, 2),
                Iap2Csm.u16(2, 1),
                Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
            )
            expect(Iap2MessageIds.DEVICE_HID_REPORT)

            // WiFi 配置
            expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
            // 迟到一条订阅请求（真实车机 bring-up 常见：0x5000 与 0x5703 相邻到达）。
            // 它必须被暂存而不是在 expectOrNull 里被反复重读——旧实现把非预期帧塞回
            // 「自己下一轮优先读取」的队列，同一帧无限循环（2026-10-18 日志刷屏根因）。
            send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
            send(
                Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                Iap2Csm.string(1, "MockAP"),
                Iap2Csm.string(2, "secret-passphrase"),
                Iap2Csm.u8(3, 2),
                Iap2Csm.u8(4, 36),
            )

            // 设备状态问候（真机顺序：0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C → 0x4300）
            expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            expect(Iap2MessageIds.DEVICE_UUID_UPDATE)

            // CarPlay 启动
            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    Iap2Csm.string(3, "192.168.49.1"),
                    Iap2Csm.string(3, "fd00::1"),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )

            expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        private fun expect(messageId: Int) {
            println("MOCK expect 0x${messageId.toString(16)}")
            val frame =
                recvFrame(10_000)
                    ?: error("mock timed out waiting 0x${messageId.toString(16)}")
            check(frame.messageId == messageId) {
                "mock expected 0x${messageId.toString(16)} got 0x${frame.messageId.toString(16)}"
            }
            println("MOCK got 0x${frame.messageId.toString(16)}")
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            return frames.first()
        }
    }
}
