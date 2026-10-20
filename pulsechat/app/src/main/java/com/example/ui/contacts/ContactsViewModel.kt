package com.example.ui.contacts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.R
import com.example.data.model.ContactRow
import com.example.data.model.User
import com.example.data.repository.ChatException
import com.example.data.repository.GroupValidationException
import com.example.ui.common.chatErrorText
import com.example.ui.common.groupErrorText
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class ContactsViewModel(private val c: AppContainer) : ViewModel() {
    val me = c.authRepository.currentUid.orEmpty()
    val contacts: StateFlow<List<ContactRow>> = c.userRepository.observeContacts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val blocked: StateFlow<List<String>> = c.userRepository.observeBlockedIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val query = MutableStateFlow("")
    val results = MutableStateFlow<List<User>>(emptyList())
    val searching = MutableStateFlow(false)
    val searchError = MutableStateFlow(false)
    val selected = MutableStateFlow<List<String>>(emptyList())
    private val selectedUsers = mutableMapOf<String, User>()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    val busy = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            query.debounce(400).distinctUntilChanged().collect { q ->
                if (q.trim().length < 2) { results.value = emptyList(); searchError.value = false; return@collect }
                searching.value = true
                try {
                    results.value = c.userRepository.searchUsers(q)
                    searchError.value = false
                } catch (e: Exception) {
                    searchError.value = true
                } finally {
                    searching.value = false
                }
            }
        }
    }

    fun toggle(user: User) {
        val s = selected.value
        if (user.userId in s) { selected.value = s - user.userId; selectedUsers.remove(user.userId) }
        else { selected.value = s + user.userId; selectedUsers[user.userId] = user }
    }

    fun toggleContact(row: ContactRow) = toggle(User(userId = row.userId, displayName = row.shownName, username = row.username, avatarUrl = row.avatarUrl, avatarColorHex = row.avatarColorHex))
    fun selectedUser(id: String): User? = selectedUsers[id]

    suspend fun openChat(userId: String): String = c.chatRepository.openDirectChat(userId)

    fun saveContact(user: User) = viewModelScope.launch {
        runCatching { c.userRepository.saveContact(user.userId, null) }
            .onSuccess { _messages.tryEmit(c.app.getString(R.string.toast_contact_saved)) }
            .onFailure { _messages.tryEmit(c.app.getString(R.string.error_update_failed)) }
    }

    suspend fun createGroup(name: String, description: String, photo: Uri?): String? {
        busy.value = true
        return try {
            c.chatRepository.createGroup(name, description, photo, selected.value)
        } catch (e: GroupValidationException) {
            _messages.tryEmit(groupErrorText(c.app, e.errors)); null
        } catch (e: ChatException) {
            _messages.tryEmit(chatErrorText(c.app, e.error)); null
        } catch (e: Exception) {
            _messages.tryEmit(c.app.getString(R.string.error_update_failed)); null
        } finally {
            busy.value = false
        }
    }

    suspend fun addMembers(conversationId: String): Boolean = try {
        c.chatRepository.addMembers(conversationId, selected.value)
        true
    } catch (e: ChatException) {
        _messages.tryEmit(chatErrorText(c.app, e.error)); false
    } catch (e: Exception) {
        _messages.tryEmit(c.app.getString(R.string.error_update_failed)); false
    }

    suspend fun existingMembers(conversationId: String): List<String> = c.chatRepository.getConversation(conversationId)?.getParticipantsList() ?: emptyList()
}
