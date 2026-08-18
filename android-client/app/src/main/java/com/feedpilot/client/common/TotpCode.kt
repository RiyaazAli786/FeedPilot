package com.feedpilot.client.common

import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpCode {
    private const val TIME_STEP_SECONDS = 30L
    private const val CODE_DIGITS = 6

    fun generate(secret: String, timeMillis: Long = System.currentTimeMillis()): String? {
        val key = decodeBase32(secret) ?: return null
        val counter = timeMillis / 1000L / TIME_STEP_SECONDS
        val data = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1").apply {
            init(SecretKeySpec(key, "HmacSHA1"))
        }
        val hash = mac.doFinal(data)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val modulo = 10.0.pow(CODE_DIGITS).toInt()
        return (binary % modulo).toString().padStart(CODE_DIGITS, '0')
    }

    private fun decodeBase32(raw: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val normalized = raw
            .trim()
            .removePrefix("otpauth://")
            .substringAfter("secret=", raw)
            .substringBefore('&')
            .replace(" ", "")
            .replace("-", "")
            .replace("=", "")
            .uppercase(Locale.US)
        if (normalized.isBlank()) return null

        var buffer = 0
        var bitsLeft = 0
        val bytes = ArrayList<Byte>()
        for (char in normalized) {
            val value = alphabet.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bytes.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        return bytes.toByteArray().takeIf { it.isNotEmpty() }
    }
}
