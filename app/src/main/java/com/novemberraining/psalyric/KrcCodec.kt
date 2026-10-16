package com.novemberraining.psalyric

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream

object KrcCodec {
    private val key = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
    )

    fun decrypt(bytes: ByteArray): String {
        if (bytes.size < 4 ||
            bytes[0] != 'k'.code.toByte() ||
            bytes[1] != 'r'.code.toByte() ||
            bytes[2] != 'c'.code.toByte() ||
            bytes[3] != '1'.code.toByte()
        ) {
            return bytes.toString(Charsets.UTF_8)
        }

        val compressed = ByteArray(bytes.size - 4)
        for (i in compressed.indices) {
            compressed[i] = (bytes[i + 4].toInt() xor key[i % key.size].toInt()).toByte()
        }

        InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    if (output.size() + count > 10 * 1024 * 1024) {
                        throw IllegalStateException("KRC lyric exceeds size limit")
                    }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name()).replace("\uFEFF", "")
        }
    }
}
