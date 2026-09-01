package com.qweexam.app.auth

/**
 * 2FA 鉴权配置（v1.2）。
 *
 * TOTP 密钥（BASE32）：请在 Google Authenticator / Aegis / 微软验证器 等应用中
 * 扫码或手动添加下方 URI（或直接用密钥），App 启动时输入 6 位动态验证码完成设备绑定。
 *
 * ⚠️ 该密钥与用户持有的密钥一致才能通过校验；请务必妥善保存。
 */
object AuthConfig {

    /** TOTP 密钥（BASE32，160 位随机） */
    const val TOTP_SECRET = "Y7WZ3ASBGAKLXZHZ3HP42X7QK2YYYWQI"

    /** 扫码添加 URI */
    const val OTPAUTH_URI =
        "otpauth://totp/%E4%BC%81%E5%BE%AE%E8%80%83%E8%AF%95?secret=$TOTP_SECRET&issuer=%E4%BC%81%E5%BE%AE%E8%80%83%E8%AF%95"

    /** 允许的时间窗口偏移（±1 步，容忍时钟偏差 30s） */
    const val TIME_STEP_SECONDS = 30L
    const val WINDOW_SLACK = 1
}
