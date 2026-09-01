package com.qweexam.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BankDao {

    @Insert
    suspend fun insert(bank: BankEntity): Long

    @Query("SELECT * FROM banks ORDER BY createdAt DESC")
    suspend fun getAll(): List<BankEntity>

    @Query("SELECT * FROM banks WHERE id = :id")
    suspend fun getById(id: Long): BankEntity?

    @Query("DELETE FROM banks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE banks SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)
}
