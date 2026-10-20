package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey
    val conversationId: String,
    val isGroup: Boolean = false,
    val title: String = "",
    val avatarUrl: String? = null,
    val participantIds: String = "", // Comma-separated user IDs
    val lastMessageText: String? = null,
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val lastMessageSenderId: String? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getParticipantsList(): List<String> = participantIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
