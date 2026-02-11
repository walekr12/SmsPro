package com.tool.smspro.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.tool.smspro.data.entity.SendTask

@Dao
interface SendTaskDao {
    @Query("SELECT * FROM send_tasks ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<SendTask>>

    @Query("SELECT * FROM send_tasks WHERE id = :id")
    suspend fun getById(id: Long): SendTask?

    @Insert
    suspend fun insert(task: SendTask): Long

    @Update
    suspend fun update(task: SendTask)

    @Query("UPDATE send_tasks SET successCount = :success, failCount = :fail, status = :status WHERE id = :id")
    suspend fun updateCounts(id: Long, success: Int, fail: Int, status: String)
}
