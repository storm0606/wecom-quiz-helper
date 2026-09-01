package com.qweexam.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface QuestionDao {

    @Insert
    suspend fun insertAll(questions: List<QuestionEntity>)

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId")
    suspend fun countByBank(bankId: Long): Int

    /** 精确匹配：归一化题干直查（走 stemNorm 索引） */
    @Query("SELECT * FROM questions WHERE stemNorm = :stemNorm LIMIT 10")
    suspend fun findByStemNorm(stemNorm: String): List<QuestionEntity>

    /** 包含匹配：屏幕文本可能混入选项，题库题干包含于屏幕文本 */
    @Query("SELECT * FROM questions WHERE :screenNorm LIKE '%' || stemNorm || '%' LIMIT 10")
    suspend fun findContainedIn(screenNorm: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE bankId = :bankId")
    suspend fun getByBank(bankId: Long): List<QuestionEntity>

    /** 合并所有题库（v1.2：all 模式） */
    @Query("SELECT * FROM questions")
    suspend fun getAll(): List<QuestionEntity>

    @Query("DELETE FROM questions WHERE bankId = :bankId")
    suspend fun deleteByBank(bankId: Long)
}
