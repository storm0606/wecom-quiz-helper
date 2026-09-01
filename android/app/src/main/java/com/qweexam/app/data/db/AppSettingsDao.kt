package com.qweexam.app.data.db

import androidx.room.Dao
import androidx.room.Query

@Dao
interface AppSettingsDao {

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("INSERT OR REPLACE INTO app_settings (`key`, value) VALUES (:key, :value)")
    suspend fun put(key: String, value: String)
}
