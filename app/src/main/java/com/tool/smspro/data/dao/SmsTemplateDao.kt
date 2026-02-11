package com.tool.smspro.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.tool.smspro.data.entity.SmsTemplate

@Dao
interface SmsTemplateDao {
    @Query("SELECT * FROM sms_templates ORDER BY pinned DESC, useCount DESC, createdAt DESC")
    fun getAll(): LiveData<List<SmsTemplate>>

    @Query("SELECT * FROM sms_templates ORDER BY pinned DESC, useCount DESC, createdAt DESC")
    suspend fun getAllList(): List<SmsTemplate>

    @Query("SELECT * FROM sms_templates WHERE category = :category ORDER BY pinned DESC, useCount DESC")
    fun getByCategory(category: String): LiveData<List<SmsTemplate>>

    @Query("SELECT * FROM sms_templates WHERE id = :id")
    suspend fun getById(id: Long): SmsTemplate?

    @Insert
    suspend fun insert(template: SmsTemplate): Long

    @Insert
    suspend fun insertAll(templates: List<SmsTemplate>)

    @Update
    suspend fun update(template: SmsTemplate)

    @Delete
    suspend fun delete(template: SmsTemplate)

    @Query("DELETE FROM sms_templates WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE sms_templates SET useCount = useCount + 1 WHERE id = :id")
    suspend fun incrementUseCount(id: Long)

    @Query("UPDATE sms_templates SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("SELECT COUNT(*) FROM sms_templates")
    suspend fun count(): Int
}
