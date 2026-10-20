package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "statuses")
data class StatusItem(
    @PrimaryKey
    val statusId: String = "",
    val userId: String = "",
    val authorName: String = "",
    val authorPhotoUrl: String? = null,
    val text: String? = null,
    val mediaUrl: String? = null,
    val mediaType: String = "TEXT", // "TEXT", "IMAGE", "VIDEO"
    val fontFamily: String = "DEFAULT", // "DEFAULT", "SERIF", "SANS", "MONO", "CURSIVE"
    val backgroundColorHex: String = "#075E54",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 86400000L, // 24 hours
    val isViewed: Boolean = false
)
