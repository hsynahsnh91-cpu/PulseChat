package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached public profile. Rows with a non-null [passwordHash] are legacy device-local accounts
 * created before Firebase Authentication was introduced; they are migrated on next sign-in.
 */
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
    val statusMessage: String = "", // "About"
    val isOnline: Boolean = false,
    val lastSeen: Long = 0L,
    val isAiAssistant: Boolean = false,
    val avatarColorHex: String = "#4F46E5",
    val createdAt: Long = System.currentTimeMillis(),
    // v3
    val profileSyncedAt: Long = 0L,
)
