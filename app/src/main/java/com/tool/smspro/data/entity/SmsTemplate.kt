package com.tool.smspro.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_templates")
data class SmsTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val category: String = "其他",
    val pinned: Boolean = false,
    val useCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
