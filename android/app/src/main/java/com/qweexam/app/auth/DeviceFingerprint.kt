package com.qweexam.app.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * 设备指纹（v1.2）：基于 Android ID + 设备硬件信息生成，用于 2FA 绑定后锁定设备。
 * 同一设备指纹稳定；不同设备指纹不同。
 */
object DeviceFingerprint {

    fun compute(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val raw = listOf(
            androidId,
            Build.MODEL,
            Build.MANUFACTURER,
            Build.BOARD,
            Build.FINGERPRINT
        ).joinToString("|")
        return sha256(raw)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
