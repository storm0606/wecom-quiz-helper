package com.qweexam.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/** 密钥隐藏组装测试（v1.9）：分段 XOR 还原出的密钥必须与真实密钥一致 */
class ObfuscatedSecretTest {

    @Test
    fun assemble_returnsRealSecret() {
        assertEquals("Y7WZ3ASBGAKLXZHZ3HP42X7QK2YYYWQI", ObfuscatedSecret.assemble())
    }

    @Test
    fun assemble_resultValidBase32Length() {
        val s = ObfuscatedSecret.assemble()
        assertEquals(32, s.length)
        // BASE32 字符集
        assert(s.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
    }
}
