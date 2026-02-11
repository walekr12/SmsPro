package com.tool.smspro.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "send_records",
    indices = [Index(value = ["taskId"])],
    foreignKeys = [ForeignKey(
        entity = SendTask::class,
        parentColumns = ["id"],
        childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class SendRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val customerId: Long,
    val customerName: String = "",
    val phone: String,
    val content: String,
    val status: String = "pending",
    val sentAt: Long? = null
)
