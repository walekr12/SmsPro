package com.tool.smspro.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.tool.smspro.data.entity.SendRecord

@Dao
interface SendRecordDao {
    @Query("SELECT * FROM send_records WHERE taskId = :taskId ORDER BY id ASC")
    fun getByTask(taskId: Long): LiveData<List<SendRecord>>

    @Query("SELECT * FROM send_records WHERE taskId = :taskId ORDER BY id ASC")
    suspend fun getByTaskList(taskId: Long): List<SendRecord>

    @Query("SELECT * FROM send_records WHERE taskId = :taskId AND status = :status")
    suspend fun getByTaskAndStatus(taskId: Long, status: String): List<SendRecord>

    @Insert
    suspend fun insert(record: SendRecord): Long

    @Insert
    suspend fun insertAll(records: List<SendRecord>)

    @Update
    suspend fun update(record: SendRecord)

    @Query("UPDATE send_records SET status = :status, sentAt = :sentAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, sentAt: Long)
}
