package com.qweexam.app.auth

/**
 * 2FA 密钥隐藏存储（v1.9）：
 * - 密钥**不以明文出现**：32 字符密钥按 8 字节分段，与 8 字节 XOR 密钥异或后散落为字节数组；
 * - 每个单独片段 / 整个数组都不是明文，`strings`/grep 无法直接找到；
 * - R8 混淆后类与方法名不可读，进一步加大定位难度；
 * - 运行时组装 + 首尾特征校验（防误组装）；
 * - 组装后的明文仅短暂存在内存，随即交给 SecretStore 做 Keystore 二次包装。
 *
 * 边界说明：分段 XOR 属"混淆"而非"加密"——静态分析者若找到全部片段与异或密钥仍可还原；
 * 本方案目标是把定位成本提高到普通逆向者放弃，而不是绝对不可破解。
 */
object ObfuscatedSecret {

    /** 8 字节 XOR 密钥（与各分段异或还原原始字符） */
    private val XOR_KEY = byteArrayOf(0x5A, 0x3C, 0x7E, 0x21, 0x4D, 0x6B, 0x0F, 0x98.toByte())

    /** 分段（每段 8 字节 = 密钥 8 字符 xor XOR_KEY 的密文），分散存放 */
    private val SEG1 = byteArrayOf(0x03, 0x0B, 0x29, 0x7B, 0x7E, 0x2A, 0x5C, 0xDA.toByte())
    private val SEG2 = byteArrayOf(0x1D, 0x7D, 0x35, 0x6D, 0x15, 0x31, 0x47, 0xC2.toByte())
    private val SEG3 = byteArrayOf(0x69, 0x74, 0x2E, 0x15, 0x7F, 0x33, 0x38, 0xC9.toByte())
    private val SEG4 = byteArrayOf(0x11, 0x0E, 0x27, 0x78, 0x14, 0x3C, 0x5E, 0xD1.toByte())

    /** 诱饵：形似 BASE32 密钥的字符串，用于干扰字符串搜索（不会被使用） */
    @Suppress("unused")
    private val DECOY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    /** 组装出 2FA 密钥（BASE32）；首尾特征校验失败抛异常 */
    fun assemble(): String {
        val segments = arrayOf(SEG1, SEG2, SEG3, SEG4)
        val bytes = ByteArray(32)
        for ((si, seg) in segments.withIndex()) {
            for (i in seg.indices) {
                bytes[si * 8 + i] = (seg[i].toInt() xor XOR_KEY[i].toInt()).toByte()
            }
        }
        val s = String(bytes, Charsets.US_ASCII)
        check(s.length == 32 && s.first() == 'Y' && s.last() == 'I') {
            "2FA 密钥组装校验失败"
        }
        return s
    }
}
