package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.GeminiApiService
import com.example.data.db.AppDatabase
import com.example.data.model.CallRecord
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.MessageType
import com.example.data.model.User
import com.example.data.repository.ActiveCall
import com.example.data.repository.AuthRepository
import com.example.data.repository.CallRepository
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    val geminiApi = GeminiApiService()

    val authRepo = AuthRepository(
        context = application,
        userDao = database.userDao(),
        scope = viewModelScope
    )
    val chatRepo = ChatRepository(
        context = application,
        conversationDao = database.conversationDao(),
        messageDao = database.messageDao(),
        userDao = database.userDao(),
        scope = viewModelScope
    )
    val callRepo = CallRepository(
        callDao = database.callDao(),
        messageDao = database.messageDao(),
        scope = viewModelScope
    )
    val statusRepo = com.example.data.repository.StatusRepository(
        context = application,
        statusDao = database.statusDao(),
        scope = viewModelScope
    )

    val activeStatuses: StateFlow<List<com.example.data.model.StatusItem>> = statusRepo.getActiveStatuses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentUser: StateFlow<User?> = authRepo.currentUser

    val allUsers: StateFlow<List<User>> = database.userDao().getAllUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<Conversation>> = currentUser.flatMapLatest { user ->
        if (user != null) {
            chatRepo.getConversationsForUser(user.userId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val calls: StateFlow<List<CallRecord>> = currentUser.flatMapLatest { user ->
        if (user != null) {
            database.callDao().getCallsForUser(user.userId)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    val activeConversation: StateFlow<Conversation?> = _selectedConversationId.flatMapLatest { id ->
        if (id != null) {
            chatRepo.getConversationById(id)
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeMessages: StateFlow<List<Message>> = _selectedConversationId.flatMapLatest { id ->
        if (id != null) {
            chatRepo.getMessagesForConversation(id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val typingStatus: StateFlow<Map<String, String>> = chatRepo.typingStatus

    val activeCall: StateFlow<ActiveCall?> = callRepo.activeCall
    val callDuration: StateFlow<Int> = callRepo.callDuration
    val isMuted: StateFlow<Boolean> = callRepo.isMuted
    val isSpeakerOn: StateFlow<Boolean> = callRepo.isSpeakerOn
    val isVideoEnabled: StateFlow<Boolean> = callRepo.isVideoEnabled

    private val _smartReplies = MutableStateFlow<List<String>>(emptyList())
    val smartReplies: StateFlow<List<String>> = _smartReplies.asStateFlow()

    val autoReplyManager = com.example.data.repository.AutoReplyManager(application)

    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _summaryContent = MutableStateFlow<String?>(null)
    val summaryContent: StateFlow<String?> = _summaryContent.asStateFlow()

    private val _isPolishing = MutableStateFlow(false)
    val isPolishing: StateFlow<Boolean> = _isPolishing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchingUser = MutableStateFlow(false)
    val isSearchingUser: StateFlow<Boolean> = _isSearchingUser.asStateFlow()

    private val _userNotFound = MutableStateFlow(false)
    val userNotFound: StateFlow<Boolean> = _userNotFound.asStateFlow()

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        val clean = query.trim()
        if (clean.length >= 3) {
            viewModelScope.launch(Dispatchers.IO) {
                _isSearchingUser.value = true
                val found = authRepo.lookupUserByIdOrUsername(clean)
                _isSearchingUser.value = false
                _userNotFound.value = (found == null)
            }
        } else {
            _userNotFound.value = false
            _isSearchingUser.value = false
        }
    }

    fun selectConversation(conversationId: String) {
        _selectedConversationId.value = conversationId
        _smartReplies.value = emptyList()
        val user = currentUser.value
        if (user != null) {
            viewModelScope.launch(Dispatchers.IO) {
                chatRepo.markConversationRead(conversationId, user.userId)
            }
        }
    }

    fun closeConversation() {
        _selectedConversationId.value = null
        _smartReplies.value = emptyList()
    }

    fun sendMessage(content: String) {
        val convId = _selectedConversationId.value ?: return
        val user = currentUser.value ?: return
        val trimmed = content.trim()
        if (trimmed.isBlank()) return

        val conv = activeConversation.value

        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.sendMessage(
                conversationId = convId,
                senderId = user.userId,
                content = trimmed,
                type = MessageType.TEXT
            )

            // If messaging Gemini AI Assistant, automatically generate AI response
            val isAi = conv?.participantIds?.contains(AuthRepository.AI_ASSISTANT_ID) == true ||
                    conv?.title?.contains("Gemini", ignoreCase = true) == true ||
                    conv?.title?.contains("Assistant", ignoreCase = true) == true

            if (isAi) {
                val aiResult = geminiApi.generateContent(
                    prompt = trimmed,
                    systemInstruction = "أنت PulseChat AI Assistant، مساعد ذكي ومفيد ولطيف باللغة العربية."
                )
                aiResult.onSuccess { reply ->
                    chatRepo.sendMessage(
                        conversationId = convId,
                        senderId = AuthRepository.AI_ASSISTANT_ID,
                        content = reply,
                        type = MessageType.AI_RESPONSE
                    )
                }.onFailure { err ->
                    chatRepo.sendMessage(
                        conversationId = convId,
                        senderId = AuthRepository.AI_ASSISTANT_ID,
                        content = "عذراً، حدث خطأ أثناء معالجة الطلب: ${err.localizedMessage}",
                        type = MessageType.AI_RESPONSE
                    )
                }
            }
        }
    }

    fun sendVoiceNote(durationSeconds: Int) {
        val convId = _selectedConversationId.value ?: return
        val user = currentUser.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.sendMessage(
                conversationId = convId,
                senderId = user.userId,
                content = "رسالة صوتية",
                type = MessageType.AUDIO,
                mediaDurationSeconds = durationSeconds
            )
        }
    }

    fun requestSmartReplies() {
        val currentMsgs = activeMessages.value.takeLast(4).map { it.content }
        if (currentMsgs.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val replies = geminiApi.generateSmartReplies(currentMsgs)
            _smartReplies.value = replies
        }
    }

    fun summarizeConversation() {
        val currentMsgs = activeMessages.value
        if (currentMsgs.isEmpty()) return

        _isSummarizing.value = true
        val transcript = currentMsgs.joinToString("\n") {
            val sender = if (it.senderId == currentUser.value?.userId) "أنا" else "الطرف الآخر"
            "$sender: ${it.content}"
        }

        viewModelScope.launch(Dispatchers.IO) {
            val summary = geminiApi.summarizeConversation(transcript)
            _summaryContent.value = summary
            _isSummarizing.value = false
        }
    }

    fun clearSummary() {
        _summaryContent.value = null
    }

    fun polishDraft(draft: String, onResult: (String) -> Unit) {
        if (draft.isBlank()) return
        _isPolishing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val polished = geminiApi.polishMessage(draft)
            _isPolishing.value = false
            onResult(polished)
        }
    }

    fun switchUser(userId: String) {
        authRepo.switchUser(userId)
        _selectedConversationId.value = null
    }

    fun createNewAccount(username: String, displayName: String, status: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = authRepo.createNewAccount(username, displayName, status)
            result.fold(
                onSuccess = { onResult(true, null) },
                onFailure = { onResult(false, it.message) }
            )
        }
    }

    fun startDirectChat(targetUserId: String) {
        val user = currentUser.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val conv = chatRepo.getOrCreateDirectConversation(user.userId, targetUserId)
            _selectedConversationId.value = conv.conversationId
        }
    }

    fun createGroup(title: String, memberIds: List<String>) {
        val user = currentUser.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val conv = chatRepo.createGroupConversation(title, user.userId, memberIds)
            _selectedConversationId.value = conv.conversationId
        }
    }

    fun startCall(targetUserId: String, isVideo: Boolean) {
        val caller = currentUser.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val receiver = database.userDao().getUserByIdSync(targetUserId)
            val receiverName = receiver?.displayName ?: "مستخدم"
            callRepo.startOutgoingCall(
                callerId = caller.userId,
                callerName = caller.displayName,
                receiverId = targetUserId,
                receiverName = receiverName,
                isVideo = isVideo
            )
        }
    }

    fun sendPhoto(photoUri: String) {
        val convId = _selectedConversationId.value ?: return
        val user = currentUser.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.sendMessage(
                conversationId = convId,
                senderId = user.userId,
                content = "صورة",
                type = MessageType.IMAGE,
                mediaUrl = photoUri
            )
        }
    }

    fun sendVideo(videoUri: String) {
        val convId = _selectedConversationId.value ?: return
        val user = currentUser.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.sendMessage(
                conversationId = convId,
                senderId = user.userId,
                content = "فيديو",
                type = MessageType.VIDEO,
                mediaUrl = videoUri
            )
        }
    }

    fun sendDocument(fileName: String, uriString: String) {
        val convId = _selectedConversationId.value ?: return
        val user = currentUser.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.sendMessage(
                conversationId = convId,
                senderId = user.userId,
                content = fileName,
                type = MessageType.DOCUMENT,
                mediaUrl = uriString,
                fileName = fileName
            )
        }
    }

    fun sendLocation(lat: Double, lng: Double, address: String) {
        val convId = _selectedConversationId.value ?: return
        val user = currentUser.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.sendMessage(
                conversationId = convId,
                senderId = user.userId,
                content = address,
                type = MessageType.LOCATION,
                locationLat = lat,
                locationLng = lng
            )
        }
    }

    fun sendPoll(question: String, options: List<String>) {
        val convId = _selectedConversationId.value ?: return
        val user = currentUser.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            chatRepo.sendMessage(
                conversationId = convId,
                senderId = user.userId,
                content = question,
                type = MessageType.POLL,
                pollQuestion = question,
                pollOptions = options.joinToString("|")
            )
        }
    }

    fun postStatus(
        text: String?,
        mediaUrl: String? = null,
        mediaType: String = "TEXT",
        fontFamily: String = "DEFAULT",
        backgroundColorHex: String = "#075E54"
    ) {
        val user = currentUser.value ?: return
        viewModelScope.launch {
            statusRepo.postStatus(
                userId = user.userId,
                authorName = user.displayName,
                authorPhotoUrl = user.avatarUrl,
                text = text,
                mediaUrl = mediaUrl,
                mediaType = mediaType,
                fontFamily = fontFamily,
                backgroundColorHex = backgroundColorHex
            )
        }
    }

    fun markStatusViewed(statusId: String) {
        viewModelScope.launch {
            statusRepo.markStatusViewed(statusId)
        }
    }

    fun simulateIncomingCall(isVideo: Boolean) {
        val user = currentUser.value ?: return
        val users = allUsers.value.filter { it.userId != user.userId && !it.isAiAssistant }
        val caller = users.randomOrNull() ?: return
        callRepo.simulateIncomingCall(caller, user.userId, isVideo)
    }

    fun acceptCall() = callRepo.acceptCall()
    fun rejectCall() = callRepo.rejectCall()
    fun endCall() = callRepo.endCall()
    fun toggleMute() = callRepo.toggleMute()
    fun toggleSpeaker() = callRepo.toggleSpeaker()
    fun toggleVideo() = callRepo.toggleVideo()
}
