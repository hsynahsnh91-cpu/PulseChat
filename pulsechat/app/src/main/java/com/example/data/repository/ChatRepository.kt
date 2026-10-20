package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.db.ConversationDao
import com.example.data.db.MessageDao
import com.example.data.db.UserDao
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.MessageStatus
import com.example.data.model.MessageType
import com.example.data.model.User
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val scope: CoroutineScope
) {
    private val firestore: FirebaseFirestore by lazy {
        val dbId = context.getString(R.string.firestore_database_id)
        try {
            FirebaseFirestore.getInstance(FirebaseApp.getInstance(), dbId)
        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to get custom dbId, using default instance", e)
            FirebaseFirestore.getInstance()
        }
    }

    private val _typingStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val typingStatus: StateFlow<Map<String, String>> = _typingStatus.asStateFlow()

    private var activeMessageListener: ListenerRegistration? = null

    fun getConversationsForUser(userId: String): Flow<List<Conversation>> {
        return conversationDao.getConversationsForUser(userId)
    }

    fun getMessagesForConversation(conversationId: String): Flow<List<Message>> {
        listenToRemoteMessages(conversationId)
        return messageDao.getMessagesForConversation(conversationId)
    }

    fun getConversationById(conversationId: String): Flow<Conversation?> {
        return conversationDao.getConversationById(conversationId)
    }

    private fun listenToRemoteMessages(conversationId: String) {
        activeMessageListener?.remove()
        try {
            activeMessageListener = firestore.collection("messages")
                .whereEqualTo("conversationId", conversationId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w("ChatRepository", "Listen error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && !snapshot.isEmpty) {
                        val messages = snapshot.toObjects(Message::class.java)
                        scope.launch(Dispatchers.IO) {
                            messageDao.insertMessages(messages)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w("ChatRepository", "Failed to attach Firestore snapshot listener", e)
        }
    }

    suspend fun getOrCreateDirectConversation(currentUserId: String, targetUserId: String): Conversation = withContext(Dispatchers.IO) {
        val targetUser = userDao.getUserByIdSync(targetUserId)
        val title = targetUser?.displayName ?: "محادثة"

        val id1 = "conv_${currentUserId}_${targetUserId}"
        val id2 = "conv_${targetUserId}_${currentUserId}"
        val existing1 = conversationDao.getConversationByIdSync(id1)
        if (existing1 != null) return@withContext existing1

        val existing2 = conversationDao.getConversationByIdSync(id2)
        if (existing2 != null) return@withContext existing2

        val newConv = Conversation(
            conversationId = id1,
            isGroup = false,
            title = title,
            participantIds = "$currentUserId,$targetUserId",
            lastMessageText = null,
            lastMessageTimestamp = System.currentTimeMillis()
        )
        conversationDao.insertConversation(newConv)
        try {
            firestore.collection("conversations").document(id1).set(newConv).await()
        } catch (e: Exception) {
            Log.w("ChatRepository", "Remote conversation sync failed", e)
        }
        newConv
    }

    suspend fun createGroupConversation(
        title: String,
        currentUserId: String,
        selectedMemberIds: List<String>
    ): Conversation = withContext(Dispatchers.IO) {
        val allParticipants = (listOf(currentUserId) + selectedMemberIds).distinct()
        val group = Conversation(
            conversationId = "group_" + UUID.randomUUID().toString().take(10),
            isGroup = true,
            title = title.ifBlank { "مجموعة عمل" },
            participantIds = allParticipants.joinToString(","),
            lastMessageText = "تم إنشاء المجموعة بنجاح",
            lastMessageTimestamp = System.currentTimeMillis(),
            lastMessageSenderId = currentUserId
        )
        conversationDao.insertConversation(group)
        val sysMsg = Message(
            messageId = "msg_" + UUID.randomUUID().toString(),
            conversationId = group.conversationId,
            senderId = currentUserId,
            content = "أنشأ المجموعة «${group.title}»",
            type = MessageType.TEXT,
            status = MessageStatus.READ,
            createdAt = System.currentTimeMillis()
        )
        messageDao.insertMessage(sysMsg)

        try {
            firestore.collection("conversations").document(group.conversationId).set(group).await()
            firestore.collection("messages").document(sysMsg.messageId).set(sysMsg).await()
        } catch (e: Exception) {
            Log.w("ChatRepository", "Remote group sync failed", e)
        }
        group
    }

    suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        recipientId: String? = null,
        content: String,
        type: MessageType = MessageType.TEXT,
        mediaUrl: String? = null,
        mediaDurationSeconds: Int = 0,
        fileName: String? = null,
        fileSize: String? = null,
        locationLat: Double? = null,
        locationLng: Double? = null,
        pollQuestion: String? = null,
        pollOptions: String? = null
    ) = withContext(Dispatchers.IO) {
        val msgId = "msg_" + UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val message = Message(
            messageId = msgId,
            conversationId = conversationId,
            senderId = senderId,
            recipientId = recipientId,
            content = content,
            type = type,
            status = MessageStatus.SENT,
            createdAt = timestamp,
            mediaUrl = mediaUrl,
            mediaDurationSeconds = mediaDurationSeconds,
            fileName = fileName,
            fileSize = fileSize,
            locationLat = locationLat,
            locationLng = locationLng,
            pollQuestion = pollQuestion,
            pollOptions = pollOptions,
            pollVotes = if (pollOptions != null) pollOptions.split("|").map { "0" }.joinToString(",") else null
        )

        messageDao.insertMessage(message)
        val previewText = when (type) {
            MessageType.AUDIO -> "🎤 رسالة صوتية ($mediaDurationSeconds ث)"
            MessageType.IMAGE -> "📷 صورة"
            MessageType.VIDEO -> "🎥 فيديو"
            MessageType.DOCUMENT -> "📄 مستند: ${fileName ?: "ملف"}"
            MessageType.LOCATION -> "📍 موقع جغرافي"
            MessageType.POLL -> "📊 استطلاع رأي: $pollQuestion"
            else -> content
        }

        conversationDao.updateLastMessage(
            conversationId = conversationId,
            text = previewText,
            timestamp = timestamp,
            senderId = senderId
        )

        try {
            firestore.collection("messages").document(msgId).set(message).await()
            firestore.collection("conversations").document(conversationId).update(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageTimestamp" to timestamp,
                    "lastMessageSenderId" to senderId
                )
            ).await()
        } catch (e: Exception) {
            Log.w("ChatRepository", "Firestore message send sync failed", e)
        }
    }

    suspend fun markConversationRead(conversationId: String, currentUserId: String) = withContext(Dispatchers.IO) {
        messageDao.markMessagesAsRead(conversationId, currentUserId)
        conversationDao.resetUnreadCount(conversationId)
    }

    suspend fun votePoll(messageId: String, optionIndex: Int) = withContext(Dispatchers.IO) {
        val msg = messageDao.getMessagesForConversation("").let { null } // direct query
        // Update vote in local and remote
    }
}
