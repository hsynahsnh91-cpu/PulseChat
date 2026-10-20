package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Conversation
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE participantIds LIKE '%' || :userId || '%' ORDER BY lastMessageTimestamp DESC")
    fun getConversationsForUser(userId: String): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId LIMIT 1")
    fun getConversationById(conversationId: String): Flow<Conversation?>

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getConversationByIdSync(conversationId: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: Conversation)

    @Update
    suspend fun updateConversation(conversation: Conversation)

    @Query("UPDATE conversations SET lastMessageText = :text, lastMessageTimestamp = :timestamp, lastMessageSenderId = :senderId, unreadCount = unreadCount + 1 WHERE conversationId = :conversationId")
    suspend fun updateLastMessage(conversationId: String, text: String, timestamp: Long, senderId: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :conversationId")
    suspend fun resetUnreadCount(conversationId: String)
}
