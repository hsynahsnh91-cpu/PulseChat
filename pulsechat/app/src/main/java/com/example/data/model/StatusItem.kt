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
    // v7 — Feature 1: Status music metadata & segment
    val musicTrackId: String? = null,
    val musicTitle: String? = null,
    val musicArtist: String? = null,
    val musicUrl: String? = null,
    val musicStartMs: Long = 0L,
    val musicDurationMs: Long = 0L,
    // v7 — Feature 2: Structured mentions, resharing & likes
    val mentions: String = "", // Comma-separated mentioned user IDs
    val allowReshare: Boolean = true,
    val resharedFromStatusId: String? = null,
    val resharedFromUserId: String? = null,
    val resharedFromAuthorName: String? = null,
    val likeCount: Int = 0,
    val isLikedByMe: Boolean = false,
) {
    val mentionedUserIds: List<String>
        get() = mentions.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val audienceUserIds: List<String>
        get() = audience.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val hasMusic: Boolean
        get() = !musicTrackId.isNullOrBlank() || !musicUrl.isNullOrBlank()

    val isReshared: Boolean
        get() = !resharedFromStatusId.isNullOrBlank()

    companion object {
        const val UPLOAD_PENDING = "PENDING"
        const val UPLOAD_UPLOADING = "UPLOADING"
        const val UPLOAD_FAILED = "FAILED"
        const val UPLOAD_DONE = "DONE"
    }
}
