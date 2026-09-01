package com.qweexam.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 设备鉴权表（v1.2）：2FA 通过后存储设备指纹，DB 有指纹且匹配当前设备才可用 */
@Entity(tableName = "device_auth")
data class DeviceAuthEntity(
    @PrimaryKey val id: Int = 1,
    val fingerprint: String
)
