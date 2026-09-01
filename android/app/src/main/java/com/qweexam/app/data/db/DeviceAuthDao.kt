package com.qweexam.app.data.db

import androidx.room.Dao
import androidx.room.Query

@Dao
interface DeviceAuthDao {

    @Query("SELECT * FROM device_auth WHERE id = 1")
    suspend fun get(): DeviceAuthEntity?

    @Query("INSERT OR REPLACE INTO device_auth (id, fingerprint) VALUES (1, :fingerprint)")
    suspend fun save(fingerprint: String)
}
