package com.lizhihua.carplaysender.util

import java.io.InputStream

/** 协议观测：把输入流按行十六进制转储到日志回调。Phase 0 的核心验证工具。 */
class HexDumpObserver(
    private val input: InputStream,
    private val label: String,
    private val onLine: (String) -> Unit,
    private val onClosed: (Throwable?) -> Unit = {},
) : Thread("hexdump-$label") {

    init {
        isDaemon = true
    }

    override fun run() {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                var offset = 0
                while (offset < count) {
                    val chunk = buffer.copyOfRange(offset, minOf(offset + 16, count))
                    onLine(
                        "[$label] " + chunk.joinToString(" ") {
                            "%02x".format(it.toInt() and 0xFF)
                        },
                    )
                    offset += 16
                }
            }
            onLine("[$label] EOF")
            onClosed(null)
        } catch (error: Throwable) {
            onLine("[$label] closed: ${error.message}")
            onClosed(error)
        }
    }
}

fun ByteArray.toHexString(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
