package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey
    val userId: String = "",
    val uniqueId10: String = "", // Unique 10-digit numeric ID
    val googleId: String? = null,
    val username: String = "", // Unique username
    val displayName: String = "",
    val passwordHash: String? = null,
    val avatarUrl: String? = null,
    val statusMessage: String = "متاح على PulseChat",
    val isOnline: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis(),
    val isAiAssistant: Boolean = false,
    val avatarColorHex: String = "#4F46E5",
    val createdAt: Long = System.currentTimeMillis()
)
