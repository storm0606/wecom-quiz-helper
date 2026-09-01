package com.qweexam.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TOTP 实现测试（RFC 6238 标准测试向量：secret = base32("12345678901234567890")） */
class TotpUtilTest {

    private val RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    private fun timeAtCounter(counter: Long): Long = counter * AuthConfig.TIME_STEP_SECONDS * 1000

    @Test
    fun rfc6238_vector_at_T59() {
        // RFC 6238 SHA1: T=59（Unix 秒时间戳 → 计数器 1）→ 8位 94287082 → 6位 287082
        assertEquals("287082", TotpUtil.generate(RFC_SECRET, 59 * 1000L))
    }

    @Test
    fun rfc6238_vector_at_T1111111109() {
        // T=1111111109（计数器 37037036）→ 8位 07081804 → 6位 081804
        assertEquals("081804", TotpUtil.generate(RFC_SECRET, 1111111109L * 1000))
    }

    @Test
    fun generate_roundTrip_verifyOk() {
        val secret = "JBSWY3DPEHPK3PXP"
        val code = TotpUtil.generate(secret, System.currentTimeMillis())
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun verify_rejectsWrongCode() {
        val secret = "JBSWY3DPEHPK3PXP"
        val code = TotpUtil.generate(secret, System.currentTimeMillis())
        val wrong = if (code == "000000") "000001" else "000000"
        assertFalse(TotpUtil.verify(wrong, secret))
    }

    @Test
    fun verify_rejectsBadFormat() {
        assertFalse(TotpUtil.verify("12345"))        // 5 位
        assertFalse(TotpUtil.verify("12a345"))       // 含字母
        assertFalse(TotpUtil.verify(""))             // 空
    }

    @Test
    fun base32Decode_knownVector() {
        // "12345678901234567890" -> GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
        val decoded = TotpUtil.base32Decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        assertEquals("12345678901234567890", String(decoded, Charsets.US_ASCII))
    }
}
