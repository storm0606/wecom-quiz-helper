package com.qweexam.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qweexam.app.auth.SecretStore
import com.qweexam.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** SecretStore（Android Keystore AES-GCM）真机加解密测试（v1.8） */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {

    private val secret = "Y7WZ3ASBGAKLXZHZ3HP42X7QK2YYYWQI"

    @Test
    fun secretRoundTrip_keystoreEncrypted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            assertTrue(SecretStore.save(db, secret))
            assertEquals(secret, SecretStore.getOrNull(db))

            // 数据库中只存密文，不含明文密钥
            val stored = db.appSettingsDao().get("totp_secret_enc")
            assertNotNull(stored)
            assertFalse(stored!!.contains(secret))
        } finally {
            db.close()
        }
    }

    @Test
    fun missingSecret_returnsNull() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            assertNull(SecretStore.getOrNull(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun corruptedCiphertext_returnsNull() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            assertTrue(SecretStore.save(db, secret))
            db.appSettingsDao().put("totp_secret_enc", "AAAA")
            assertNull(SecretStore.getOrNull(db))
        } finally {
            db.close()
        }
    }
}
