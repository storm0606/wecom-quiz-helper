package com.qweexam.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BankEntity::class,
        QuestionEntity::class,
        DeviceAuthEntity::class,
        AppSettingsEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bankDao(): BankDao
    abstract fun questionDao(): QuestionDao
    abstract fun deviceAuthDao(): DeviceAuthDao
    abstract fun appSettingsDao(): AppSettingsDao

    /** v1 → v2：新增 device_auth / app_settings 表（保留已导入题库） */
    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `device_auth` (" +
                        "`id` INTEGER NOT NULL, `fingerprint` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_settings` (" +
                        "`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "qweexam.db"
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
