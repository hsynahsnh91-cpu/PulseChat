package com.example.ui.info

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.R
import com.example.data.model.ChatNotificationSettings
import com.example.data.model.Contact
import com.example.data.model.ContactRow
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.User
import com.example.data.repository.ChatException
import com.example.data.repository.GroupValidationException
import com.example.domain.PresenceDisplay
import com.example.ui.common.chatErrorText
import com.example.ui.common.groupErrorText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class InfoViewModel(private val c: AppContainer, val conversationId: String) : ViewModel() {
    val me = c.authRepository.currentUid.orEmpty()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    val conversation: StateFlow<Conversation?> = c.chatRepository.observeConversation(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val members: StateFlow<List<User>> = conversation.map { it?.getParticipantsList() ?: emptyList() }.distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyList()) else c.userRepository.observeUsers(ids) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val peerId = conversation.map { if (it?.isGroup == false) it.peerId else null }.distinctUntilChanged()
    val peer: StateFlow<User?> = peerId.flatMapLatest { id -> if (id == null) flowOf(null) else c.userRepository.observeUser(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val contact: StateFlow<Contact?> = peerId.flatMapLatest { id -> if (id == null) flowOf(null) else c.userRepository.observeContact(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val presence: StateFlow<PresenceDisplay> = peerId.flatMapLatest { id -> if (id == null) flowOf(PresenceDisplay.Hidden) else c.userRepository.observePresence(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PresenceDisplay.Hidden)
    val media: StateFlow<List<Message>> = c.chatRepository.observeMedia(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val blocked: StateFlow<List<String>> = c.userRepository.observeBlockedIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val contacts: StateFlow<List<ContactRow>> = c.userRepository.observeContacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            conversation.map { it?.getParticipantsList() ?: emptyList() }.distinctUntilChanged().collect { ids ->
                ids.forEach { launch { c.userRepository.refreshProfile(it, force = it != me) } }
            }
        }
    }

    private fun act(success: Int? = null, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            success?.let { _messages.tryEmit(c.app.getString(it)) }
        } catch (e: ChatException) {
            _messages.tryEmit(chatErrorText(c.app, e.error))
        } catch (e: GroupValidationException) {
            _messages.tryEmit(groupErrorText(c.app, e.errors))
        } catch (e: Exception) {
            _messages.tryEmit(c.app.getString(R.string.error_update_failed))
        }
    }

    /** This chat's notification settings (separate from the conversation) and the app-wide preview setting. */
    val notificationSettings: StateFlow<ChatNotificationSettings?> =
        c.chatSettings.observeNotificationSettings(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val appPreviews: StateFlow<Boolean> = c.prefs.settings.map { it.notificationPreviews }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setMuted(until: Long) = act { c.chatSettings.setMutedUntil(conversationId, until) }
    fun setPreview(preview: String) = act { c.chatSettings.setPreview(conversationId, preview) }
    fun setDisappearing(seconds: Long) = act { c.chatRepository.setDisappearing(conversationId, seconds) }
    fun saveContact(nickname: String?) = act(R.string.toast_contact_saved) { conversation.value?.peerId?.let { c.userRepository.saveContact(it, nickname) } }
    fun removeContact() = act(R.string.toast_contact_removed) { conversation.value?.peerId?.let { c.userRepository.removeContact(it) } }
    fun block() = act { conversation.value?.peerId?.let { c.userRepository.block(it) } }
    fun unblock() = act { conversation.value?.peerId?.let { c.userRepository.unblock(it) } }
    fun report(alsoBlock: Boolean) = act(R.string.toast_reported) {
        val conv = conversation.value ?: return@act
        c.userRepository.report(if (conv.isGroup) null else conv.peerId, conversationId, emptyList(), if (conv.isGroup) "group_report" else "user_report")
        if (alsoBlock && !conv.isGroup) conv.peerId?.let { c.userRepository.block(it) }
        if (alsoBlock && conv.isGroup && !conv.isLeft) c.chatRepository.leaveGroup(conversationId)
    }
    fun clearChat(keepStarred: Boolean) = act { c.chatRepository.clearChat(conversationId, keepStarred) }
    fun deleteChat() = act { c.chatRepository.deleteChat(conversationId); _closed.tryEmit(Unit) }
    fun leaveGroup() = act { c.chatRepository.leaveGroup(conversationId) }
    fun removeMember(uid: String) = act { c.chatRepository.removeMember(conversationId, uid) }
    fun setAdmin(uid: String, admin: Boolean) = act { c.chatRepository.setAdmin(conversationId, uid, admin) }
    fun updateGroup(name: String, description: String, photo: Uri?) = act { c.chatRepository.updateGroupInfo(conversationId, name, description, photo) }
    fun setPermissions(
        onlyAdminsSend: Boolean,
        onlyAdminsEdit: Boolean,
        onlyAdminsMentionAll: Boolean? = null,
    ) = act {
        c.chatRepository.setGroupPermissions(conversationId, onlyAdminsSend, onlyAdminsEdit, onlyAdminsMentionAll)
    }

    fun createGroupFromMembers(
        name: String,
        description: String,
        photo: Uri?,
        selectedMemberIds: List<String>,
        requestGroupId: String,
        onCreated: (String) -> Unit,
    ) = act(R.string.group_created_from_members_toast) {
        val cid = c.chatRepository.createGroupFromExistingMembers(
            name = name,
            description = description,
            photo = photo,
            selectedMemberIds = selectedMemberIds,
            requestGroupId = requestGroupId,
        )
        onCreated(cid)
    }

    suspend fun openDirect(uid: String): String = c.chatRepository.openDirectChat(uid)
}
