package com.qweexam.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 题库表（可存多套题库） */
@Entity(tableName = "banks")
data class BankEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filePath: String,
    val questionCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)
