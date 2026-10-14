package com.lizhihua.carplaysender.airplay

/**
 * 控制通道加密帧（与参考接收端 ControlCipher 逐字节兼容）：
 * 每帧 = [2 字节小端密文长度(作 AAD)][密文][16 字节 tag]，
 * nonce = 4 字节零 + 8 字节小端计数器，收发方向各自的密钥与计数器独立递增。
 * 单帧明文上限 0x4000，超出自动分帧。
 */
class ControlCipher(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
) {
    class Decrypted(
        val data: ByteArray,
        val rest: ByteArray,
    )

    private var readCounter = 0L
    private var writeCounter = 0L

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            output += AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(readCounter), ciphertextAndTag, aad)
            readCounter++
            offset = frameEnd
        }
        val joined = if (output.isEmpty()) ByteArray(0) else output.reduce { acc, bytes -> acc + bytes }
        return Decrypted(joined, buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag =
                AirPlayCrypto.chachaSeal(
                    writeKey,
                    AirPlayCrypto.nonce64(writeCounter),
                    chunk,
                    header,
                )
            output += header
            output += ciphertextAndTag
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return output.reduce { acc, bytes -> acc + bytes }
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
