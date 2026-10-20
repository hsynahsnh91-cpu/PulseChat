package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts",
    indices = [Index(value = ["ownerUserId", "targetUserId"], unique = true)]
)
data class Contact(
    @PrimaryKey
    val contactId: String,
    val ownerUserId: String,
    val targetUserId: String,
    val customNickname: String? = null,
    val isBlocked: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)
