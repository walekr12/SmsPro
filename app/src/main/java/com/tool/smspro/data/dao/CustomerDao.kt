package com.tool.smspro.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.tool.smspro.data.entity.Customer

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<Customer>>

    @Query("SELECT * FROM customers ORDER BY createdAt DESC")
    suspend fun getAllList(): List<Customer>

    @Query("SELECT * FROM customers WHERE groupId = :groupId ORDER BY createdAt DESC")
    fun getByGroup(groupId: Long): LiveData<List<Customer>>

    @Query("SELECT * FROM customers WHERE name LIKE '%' || :q || '%' OR phone LIKE '%' || :q || '%' ORDER BY createdAt DESC")
    fun search(q: String): LiveData<List<Customer>>

    @Query("SELECT * FROM customers WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Customer>

    @Query("SELECT * FROM customers WHERE phone = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): Customer?

    @Query("SELECT COUNT(*) FROM customers WHERE phone = :phone AND id != :excludeId")
    suspend fun countByPhoneExcluding(phone: String, excludeId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(customer: Customer): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(customers: List<Customer>): List<Long>

    @Update
    suspend fun update(customer: Customer)

    @Delete
    suspend fun delete(customer: Customer)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteById(id: Long)
}
