package com.tool.smspro.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "customer_groups")
data class CustomerGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: String = "#6750A4",
    val createdAt: Long = System.currentTimeMillis()
)
