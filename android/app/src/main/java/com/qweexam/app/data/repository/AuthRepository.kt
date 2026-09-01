package com.qweexam.app.data.repository

import android.content.Context
import com.qweexam.app.auth.DeviceFingerprint
import com.qweexam.app.auth.SecretStore
import com.qweexam.app.auth.TotpUtil
import com.qweexam.app.data.db.AppDatabase

/**
 * 2FA 设备鉴权仓库（v1.2/v1.8/v1.9）：
 * - 密钥自动从代码隐藏段组装 + Keystore 二次包装（v1.9，无需用户输入）；
 * - 校验 TOTP 验证码 → 通过后存储当前设备指纹；
 * - DB 中存在指纹且与当前设备一致才可用（换机/清库 → 重新验证）。
 */
class AuthRepository(private val db: AppDatabase) {

    /** 是否已绑定当前设备 */
    suspend fun isBound(context: Context): Boolean {
        val stored = db.deviceAuthDao().get()?.fingerprint ?: return false
        return stored == DeviceFingerprint.compute(context)
    }

    /** 校验 2FA 验证码（密钥自动装配），正确则绑定当前设备指纹；内部异常不抛给 UI（v2.2 防闪退） */
    suspend fun verifyAndBind(context: Context, code: String): Boolean {
        val secret = try {
            SecretStore.getOrCreate(db)
        } catch (e: Exception) {
            return false
        }
        if (!TotpUtil.verify(code, secret)) return false
        db.deviceAuthDao().save(DeviceFingerprint.compute(context))
        return true
    }

    /** 测试辅助：直接以给定指纹绑定 */
    suspend fun bindWith(fingerprint: String) = db.deviceAuthDao().save(fingerprint)
}
