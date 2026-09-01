package com.qweexam.app.auth

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TOTP（RFC 6238，HMAC-SHA1，30 秒步长）实现——纯 Kotlin/JVM，可单元测试。
 * 用于 2FA 动态验证码校验（Google Authenticator 兼容）。
 */
object TotpUtil {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** BASE32 解码（忽略空格/小写） */
    fun base32Decode(input: String): ByteArray {
        val clean = input.uppercase().replace(" ", "")
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "非法 BASE32 字符: $c" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.write((buffer shr (bitsLeft - 8)) and 0xFF)
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    /** 计算指定时间点的 6 位 TOTP 码 */
    fun generate(secretBase32: String, timeMillis: Long = System.currentTimeMillis()): String {
        val key = base32Decode(secretBase32)
        val counter = timeMillis / 1000 / AuthConfig.TIME_STEP_SECONDS
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xFF).toByte()
            c = c shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val bin = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    /** 校验用户输入的 6 位码（含 ±windowSlack 步时间窗口） */
    fun verify(code: String, secretBase32: String = AuthConfig.TOTP_SECRET): Boolean {
        val input = code.trim()
        if (input.length != 6 || !input.all { it.isDigit() }) return false
        val now = System.currentTimeMillis()
        for (step in -AuthConfig.WINDOW_SLACK..AuthConfig.WINDOW_SLACK) {
            val expected = generate(secretBase32, now + step * AuthConfig.TIME_STEP_SECONDS * 1000)
            if (expected == input) return true
        }
        return false
    }
}
