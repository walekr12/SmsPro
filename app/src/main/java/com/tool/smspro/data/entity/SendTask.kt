package com.tool.smspro.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "send_tasks")
data class SendTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val status: String = "pending",
    val interval: Int = 3,
    val simCard: Int = 0,
    val scheduledAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
