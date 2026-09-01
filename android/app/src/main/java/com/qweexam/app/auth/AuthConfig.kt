package com.qweexam.app.auth

/**
 * 2FA 鉴权配置（v1.2/v1.8）。
 *
 * v1.8 起：TOTP 密钥**不再硬编码在代码里**（防逆向拿到）。
 * 首次使用时由用户在验证页输入密钥，经 Android Keystore 加密后存入数据库。
 * 密钥原文请妥善保存（docs/2FA密钥.md）。
 */
object AuthConfig {

    /** TOTP 时间步长（秒）与允许的时间窗口偏移（±1 步，容忍时钟偏差 30s） */
    const val TIME_STEP_SECONDS = 30L
    const val WINDOW_SLACK = 1
}
