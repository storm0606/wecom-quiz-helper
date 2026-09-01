package com.qweexam.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 应用设置表（v1.2）：键值对，用于匹配范围/当前题库等 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val key: String,
    val value: String
)

/** 设置键 */
object AppSettingsKeys {
    const val MATCH_MODE = "match_mode"          // all / specific
    const val CURRENT_BANK_ID = "current_bank_id"
    const val OVERLAY_FEEDBACK = "overlay_feedback"   // none / flash / color
    const val OVERLAY_BG_COLOR = "overlay_bg_color"   // #AARRGGBB
}
