package com.qweexam.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 题目表（对应开发文档 §4.5） */
@Entity(
    tableName = "questions",
    indices = [Index(value = ["stemNorm"])]
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bankId: Long,
    val seq: String,
    val qtype: String,          // 单选/多选/判断/简答
    val stem: String,           // 题干原文
    val stemNorm: String,       // 归一化题干（建索引，精确匹配）
    val optionA: String? = null,
    val optionB: String? = null,
    val optionC: String? = null,
    val optionD: String? = null,
    val optionE: String? = null,
    val optionF: String? = null,
    val answer: String          // 归一化答案（A|B|C|D| -> ABCD）
)
