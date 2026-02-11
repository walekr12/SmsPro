package com.tool.smspro.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "customers",
    indices = [Index(value = ["phone"], unique = true), Index(value = ["groupId"])],
    foreignKeys = [ForeignKey(
        entity = CustomerGroup::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.SET_NULL
    )]
)
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val phone: String,
    val company: String = "",
    val remark: String = "",
    val groupId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun displayName(): String = name.ifBlank { phone }
}
