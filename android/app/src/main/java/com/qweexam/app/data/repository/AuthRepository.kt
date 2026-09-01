package com.qweexam.app.data.repository

import android.content.Context
import com.qweexam.app.auth.DeviceFingerprint
import com.qweexam.app.auth.SecretStore
import com.qweexam.app.auth.TotpUtil
import com.qweexam.app.data.db.AppDatabase

/**
 * 2FA 设备鉴权仓库（v1.2/v1.8）：
 * - 密钥由用户首次输入，经 Keystore 加密存储（v1.8，不再硬编码）；
 * - 校验 TOTP 验证码 → 通过后存储当前设备指纹；
 * - DB 中存在指纹且与当前设备一致才可用（换机/清库 → 重新验证）。
 */
class AuthRepository(private val db: AppDatabase) {

    /** 是否已绑定当前设备 */
    suspend fun isBound(context: Context): Boolean {
        val stored = db.deviceAuthDao().get()?.fingerprint ?: return false
        return stored == DeviceFingerprint.compute(context)
    }

    /** 是否已保存 2FA 密钥 */
    suspend fun hasSecret(): Boolean = SecretStore.getOrNull(db) != null

    /** 保存 2FA 密钥（Keystore 加密存储） */
    suspend fun saveSecret(secret: String): Boolean = SecretStore.save(db, secret.trim())

    /** 校验 2FA 验证码（使用已存密钥），正确则绑定当前设备指纹 */
    suspend fun verifyAndBind(context: Context, code: String): Boolean {
        val secret = SecretStore.getOrNull(db) ?: return false
        if (!TotpUtil.verify(code, secret)) return false
        db.deviceAuthDao().save(DeviceFingerprint.compute(context))
        return true
    }

    /** 测试辅助：直接以给定指纹绑定 */
    suspend fun bindWith(fingerprint: String) = db.deviceAuthDao().save(fingerprint)
}
