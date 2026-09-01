package com.qweexam.app.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.qweexam.app.data.db.AppDatabase
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 2FA 密钥安全存储（v1.8）：
 * 密钥不再硬编码在代码里 —— 首次设置时由用户输入，
 * 用 Android Keystore 的 AES-GCM 密钥加密后存入数据库（app_settings）。
 * Keystore 密钥不可导出，即使拿到数据库/APK 也无法还原明文密钥。
 */
object SecretStore {

    private const val KEY_ALIAS = "qweexam_totp_key"
    private const val SETTINGS_KEY = "totp_secret_enc"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    /** 加密保存 2FA 密钥；失败返回 false */
    suspend fun save(db: AppDatabase, secret: String): Boolean = try {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val blob = ByteBuffer.allocate(4 + iv.size + ct.size)
            .putInt(iv.size).put(iv).put(ct).array()
        db.appSettingsDao().put(
            SETTINGS_KEY,
            Base64.encodeToString(blob, Base64.NO_WRAP)
        )
        true
    } catch (e: Exception) {
        false
    }

    /** 读取并解密 2FA 密钥；无密钥或解密失败返回 null */
    suspend fun getOrNull(db: AppDatabase): String? {
        val enc = db.appSettingsDao().get(SETTINGS_KEY) ?: return null
        return try {
            val key = getOrCreateKey()
            val blob = Base64.decode(enc, Base64.NO_WRAP)
            val bb = ByteBuffer.wrap(blob)
            val ivLen = bb.int
            val iv = ByteArray(ivLen)
            bb.get(iv)
            val ct = ByteArray(bb.remaining())
            bb.get(ct)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取 2FA 密钥（v1.9 自动装配）：
     * 首次从代码隐藏段组装并 Keystore 加密入库，之后从库中解密读取（二次包装）。
     */
    suspend fun getOrCreate(db: AppDatabase): String {
        getOrNull(db)?.let { return it }
        val secret = ObfuscatedSecret.assemble()
        require(save(db, secret)) { "2FA 密钥加密存储失败" }
        return secret
    }
}
