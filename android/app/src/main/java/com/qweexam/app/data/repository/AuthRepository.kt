package com.qweexam.app.data.repository

import android.content.Context
import com.qweexam.app.auth.DeviceFingerprint
import com.qweexam.app.auth.TotpUtil
import com.qweexam.app.data.db.AppDatabase

/**
 * 2FA 设备鉴权仓库（v1.2）：
 * - 首次使用：输入 TOTP 验证码 → 校验通过 → 存储当前设备指纹；
 * - 之后：DB 中存在指纹且与当前设备一致才可用（换机/清库 → 需重新验证）。
 */
class AuthRepository(private val db: AppDatabase) {

    suspend fun isBound(context: Context): Boolean {
        val stored = db.deviceAuthDao().get()?.fingerprint ?: return false
        return stored == DeviceFingerprint.compute(context)
    }

    /** 校验 2FA 验证码（正确则绑定当前设备指纹） */
    suspend fun verifyAndBind(context: Context, code: String): Boolean {
        if (!TotpUtil.verify(code)) return false
        db.deviceAuthDao().save(DeviceFingerprint.compute(context))
        return true
    }

    /** 测试辅助：直接以给定指纹绑定 */
    suspend fun bindWith(fingerprint: String) = db.deviceAuthDao().save(fingerprint)
}
