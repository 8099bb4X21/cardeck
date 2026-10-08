package com.lizhihua.carplaysender.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** 控制通道加密帧与参考接收端格式互操作（双向、分帧、分片送达）。 */
class ControlCipherTest {

    @Test
    fun phoneAndReceiverCiphersInteropBothDirections() {
        val phoneWriteKey = ByteArray(32) { it.toByte() }
        val phoneReadKey = ByteArray(32) { (it + 7).toByte() }
        val phone = ControlCipher(phoneReadKey, phoneWriteKey)
        val receiver = ControlCipher(phoneWriteKey, phoneReadKey) // 接收端视角：读我写、写我读

        val request = "SETUP rtsp://192.168.149.253:7000/ RTSP/1.0\r\nContent-Length: 4\r\n\r\nbody"
            .toByteArray()
        assertArrayEquals(request, receiver.decrypt(phone.encrypt(request)).data)

        val reply = "RTSP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
        assertArrayEquals(reply, phone.decrypt(receiver.encrypt(reply)).data)
    }

    @Test
    fun largePayloadIsChunkedIntoMultipleFrames() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 1 })
        val receiver = ControlCipher(ByteArray(32) { 1 }, ByteArray(32))
        val big = ByteArray(0x5000) { (it % 251).toByte() } // 超过单帧 0x4000 上限
        assertArrayEquals(big, receiver.decrypt(phone.encrypt(big)).data)
    }

    @Test
    fun partialFramesDeliveredInPieces() {
        val phone = ControlCipher(ByteArray(32), ByteArray(32) { 2 })
        val receiver = ControlCipher(ByteArray(32) { 2 }, ByteArray(32))
        val message = "RTSP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()
        val encrypted = receiver.encrypt(message + message)

        val half = encrypted.size / 2
        val first = phone.decrypt(encrypted.copyOfRange(0, half))
        val second = phone.decrypt(first.rest + encrypted.copyOfRange(half, encrypted.size))
        assertArrayEquals(message + message, first.data + second.data)
    }
}
