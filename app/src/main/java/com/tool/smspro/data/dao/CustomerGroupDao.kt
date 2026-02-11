package com.tool.smspro.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.tool.smspro.data.entity.CustomerGroup

@Dao
interface CustomerGroupDao {
    @Query("SELECT * FROM customer_groups ORDER BY createdAt DESC")
    fun getAll(): LiveData<List<CustomerGroup>>

    @Query("SELECT * FROM customer_groups ORDER BY createdAt DESC")
    suspend fun getAllList(): List<CustomerGroup>

    @Query("SELECT * FROM customer_groups WHERE id = :id")
    suspend fun getById(id: Long): CustomerGroup?

    @Insert
    suspend fun insert(group: CustomerGroup): Long

    @Update
    suspend fun update(group: CustomerGroup)

    @Delete
    suspend fun delete(group: CustomerGroup)

    @Query("DELETE FROM customer_groups WHERE id = :id")
    suspend fun deleteById(id: Long)
}
