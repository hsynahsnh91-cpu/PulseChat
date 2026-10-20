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
    val backgroundColorHex: String = "#3730A3",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 86400000L, // 24 hours
    val isViewed: Boolean = false,
    // v3
    val localMediaPath: String? = null,
    val uploadState: String = UPLOAD_DONE,
    val uploadProgress: Int = 0,
    val audience: String = "", // Comma-separated viewer IDs (own statuses only)
    val storagePath: String? = null,
) {
    companion object {
        const val UPLOAD_PENDING = "PENDING"
        const val UPLOAD_UPLOADING = "UPLOADING"
        const val UPLOAD_FAILED = "FAILED"
        const val UPLOAD_DONE = "DONE"
    }
}
