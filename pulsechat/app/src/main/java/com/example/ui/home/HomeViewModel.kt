package com.example.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.model.CallRecord
import com.example.data.model.ChatListRow
import com.example.data.model.Conversation
import com.example.data.model.StatusItem
import com.example.data.model.User
import com.example.data.repository.ChatError
import com.example.data.repository.ChatException
import com.example.data.repository.SyncStatus
import com.example.domain.ChatListPolicy
import com.example.domain.NetworkKind
import com.example.domain.StatusPolicy
import com.example.data.model.ChatFolder
import com.example.data.model.ChatFolderMember
import com.example.data.model.FolderWithCount
import com.example.domain.model.ChatFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatsUi(
    val loaded: Boolean = false,
    val chats: List<ChatListRow> = emptyList(),
    val archivedCount: Int = 0,
    val archivedUnread: Int = 0,
    val typing: Map<String, Set<String>> = emptyMap(),
    val totalChats: Int = 0,
    /** Locked chats are listed only behind the "Locked chats" entry (no names or previews here). */
    val lockedCount: Int = 0,
)

data class StatusOwner(
    val ownerId: String,
    val name: String,
    val avatar: String?,
    val items: List<StatusItem>,
    val hasUnviewed: Boolean,
    val latest: Long,
)

data class UpdatesUi(val mine: List<StatusItem> = emptyList(), val recent: List<StatusOwner> = emptyList(), val viewed: List<StatusOwner> = emptyList())

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val me: String = c.authRepository.currentUid.orEmpty()
    private val _events = MutableSharedFlow<ChatError>(extraBufferCapacity = 4)
    val events: SharedFlow<ChatError> = _events.asSharedFlow()
    val linkSuccessEvents: SharedFlow<String> = c.deviceLinkRepository.linkSuccessEvents

    val meUser: StateFlow<User?> = c.userRepository.observeUser(me).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val filter = MutableStateFlow(ChatFilter.ALL)
    val query = MutableStateFlow("")
    val selection = MutableStateFlow<Set<String>>(emptySet())

    private val rows: Flow<List<ChatListRow>> = c.chatRepository.observeChatList(me)

    // ---- chat folders (phase 1)
    val selectedFolder = MutableStateFlow<String?>(null)
    val folders: StateFlow<List<ChatFolder>> = c.folders.observeFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val foldersWithCounts: StateFlow<List<FolderWithCount>> = c.folders.observeFoldersWithCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val folderMembers: StateFlow<List<ChatFolderMember>> = c.folders.observeMembers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val folderFilter: Flow<Set<String>?> = combine(selectedFolder, folderMembers) { id, members ->
        id?.let { fid -> members.filter { it.folderId == fid }.map { it.conversationId }.toSet() }
    }

    fun selectFolder(id: String?) {
        if (id != null) filter.value = ChatFilter.ALL
        selectedFolder.value = id
    }

    fun selectFilter(f: ChatFilter) {
        selectedFolder.value = null
        filter.value = f
    }

    /** Folders that already contain every selected chat (pre-checked in "Add to folder"). */
    fun foldersOfSelection(): Set<String> {
        val ids = selection.value
        if (ids.isEmpty()) return emptySet()
        return folderMembers.value.groupBy { it.folderId }.filterValues { m -> m.map { it.conversationId }.containsAll(ids) }.keys
    }

    fun assignSelectedToFolders(folderIds: Set<String>) {
        val ids = selection.value.toList()
        viewModelScope.launch { c.folders.assign(ids, folderIds); selection.value = emptySet() }
    }

    val chats: StateFlow<ChatsUi> = combine(rows, filter, query, c.chatRepository.observeAllTyping(), folderFilter) { all, f, q, typing, inFolder ->
        val list = all.filterNot { it.isLocked }
        val archived = list.filter { it.conversation.isArchived }
        val shown = if (inFolder == null) list else list.filter { it.conversation.conversationId in inFolder }
        ChatsUi(
            loaded = true,
            chats = ChatListPolicy.arrange(shown, f, q, archived = false),
            archivedCount = archived.size,
            archivedUnread = archived.count { ChatListPolicy.hasUnread(it) },
            typing = typing,
            totalChats = list.size,
            lockedCount = all.size - list.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUi())

    val archivedChats: StateFlow<List<ChatListRow>> = combine(rows, c.chatRepository.observeAllTyping()) { list, _ ->
        ChatListPolicy.arrange(list.filterNot { it.isLocked }, ChatFilter.ALL, "", archived = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Shown only after the screen-lock check (see ChatLockGuard), archived or not. */
    val lockedChats: StateFlow<List<ChatListRow>> = combine(rows, c.chatRepository.observeAllTyping()) { list, _ ->
        val locked = list.filter { it.isLocked }
        ChatListPolicy.arrange(locked, ChatFilter.ALL, "", archived = false) + ChatListPolicy.arrange(locked, ChatFilter.ALL, "", archived = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<Conversation>> = c.chatRepository.observeGroups(me).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val calls: StateFlow<List<CallRecord>> = c.callRepository.observeHistory(me).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val contacts: StateFlow<List<com.example.data.model.ContactRow>> = c.userRepository.observeContacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unreadChats: StateFlow<Int> = c.chatRepository.observeUnreadChats(me).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val sync: StateFlow<SyncStatus> = c.syncManager.status
    val network: StateFlow<NetworkKind> = c.connectivity.network

    val updates: StateFlow<UpdatesUi> = c.statusRepository.observeActive().map { list ->
        val now = System.currentTimeMillis()
        val byId = list.associateBy { it.statusId }
        val groups = StatusPolicy.group(list.map { StatusPolicy.Item(it.statusId, it.userId, it.createdAt, it.expiresAt, it.isViewed) }, now, excludeOwner = me)
            .map { g ->
                val items = g.items.mapNotNull { byId[it.statusId] }
                StatusOwner(g.ownerId, items.lastOrNull()?.authorName.orEmpty(), items.lastOrNull()?.authorPhotoUrl, items, g.hasUnviewed, g.latest)
            }
        UpdatesUi(
            mine = list.filter { it.userId == me && it.expiresAt > now }.sortedBy { it.createdAt },
            recent = groups.filter { it.hasUnviewed },
            viewed = groups.filter { !it.hasUnviewed },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdatesUi())

    /** How many people have seen my current status updates. Who they are is only shown inside my status. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val myStatusViews: StateFlow<Int> = updates
        .map { u -> u.mine.map { it.statusId } }
        .distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(0) else c.statusRepository.observeViewerCount(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ------------------------------------------------------------------ selection actions

    fun toggleSelection(cid: String) {
        val s = selection.value
        selection.value = if (cid in s) s - cid else s + cid
    }

    fun clearSelection() { selection.value = emptySet() }

    private fun selected(): List<ChatListRow> {
        val ids = selection.value
        return (chats.value.chats + archivedChats.value).filter { it.conversation.conversationId in ids }.distinctBy { it.conversation.conversationId }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: ChatException) { _events.tryEmit(e.error) } catch (e: Exception) { _events.tryEmit(ChatError.UPDATE_FAILED) }
            clearSelection()
        }
    }

    fun pinSelected() = act {
        val items = selected()
        val pin = items.any { !it.conversation.isPinned }
        for (row in items) if (!c.chatRepository.setPinned(row.conversation.conversationId, pin)) break
    }

    fun archiveSelected(archive: Boolean) = act { selected().forEach { c.chatRepository.setArchived(it.conversation.conversationId, archive) } }
    fun muteSelected(until: Long) = act { selected().forEach { c.chatSettings.setMutedUntil(it.conversation.conversationId, until) } }
    fun markSelected(unread: Boolean) = act { selected().forEach { c.chatRepository.setMarkedUnread(it.conversation.conversationId, unread) } }

    fun favoriteSelected() = act {
        val items = selected()
        val fav = items.any { !it.conversation.isFavorite }
        items.forEach { c.chatRepository.setFavorite(it.conversation.conversationId, fav) }
    }

    fun deleteSelected() = act { selected().forEach { c.chatRepository.deleteChat(it.conversation.conversationId) } }

    fun selectionState(): SelectionFlags {
        val items = selected()
        return SelectionFlags(
            allPinned = items.isNotEmpty() && items.all { it.conversation.isPinned },
            allMuted = items.isNotEmpty() && items.all { it.isMuted() },
            allFavorite = items.isNotEmpty() && items.all { it.conversation.isFavorite },
            anyUnread = items.any { ChatListPolicy.hasUnread(it) },
            allArchived = items.isNotEmpty() && items.all { it.conversation.isArchived },
            hasActiveGroup = items.any { it.conversation.isGroup && !it.conversation.isLeft && !it.conversation.isLocalOnly },
        )
    }

    suspend fun openAssistant(name: String): String = c.chatRepository.ensureAiConversation(name)

    fun deleteCall(callId: String) = viewModelScope.launch { c.callRepository.delete(callId) }
    fun clearCalls() = viewModelScope.launch { c.callRepository.clear(me) }
    fun retryStatus(id: String) = viewModelScope.launch { c.statusRepository.retry(id) }

    private val _statusErrors = MutableSharedFlow<Int>(extraBufferCapacity = 2)
    /** String resource IDs for status posting problems. */
    val statusErrors: SharedFlow<Int> = _statusErrors.asSharedFlow()

    fun postTextStatus(
        text: String,
        background: String,
        font: String,
        extras: com.example.ui.status.StatusComposeExtras = com.example.ui.status.StatusComposeExtras(),
    ) = viewModelScope.launch {
        try {
            c.statusRepository.postText(
                text = text,
                backgroundHex = background,
                font = font,
                musicSelection = extras.musicSelection,
                mentions = extras.mentionedUserIds,
                allowReshare = extras.allowReshare,
            )
        } catch (e: Exception) {
            _statusErrors.tryEmit(statusError(e))
        }
    }

    fun postMediaStatus(
        uri: android.net.Uri,
        video: Boolean,
        caption: String,
        extras: com.example.ui.status.StatusComposeExtras = com.example.ui.status.StatusComposeExtras(),
    ) = viewModelScope.launch {
        try {
            c.statusRepository.postMedia(
                uri = uri,
                type = if (video) com.example.domain.model.MessageType.VIDEO else com.example.domain.model.MessageType.IMAGE,
                caption = caption,
                musicSelection = extras.musicSelection,
                mentions = extras.mentionedUserIds,
                allowReshare = extras.allowReshare,
            )
        } catch (e: Exception) {
            _statusErrors.tryEmit(statusError(e))
        }
    }

    private fun statusError(e: Exception): Int = when (e) {
        is com.example.data.repository.StatusAudienceEmptyException -> com.example.R.string.status_error_no_audience
        is com.example.data.repository.MediaTooLargeException -> com.example.R.string.error_too_large
        else -> com.example.R.string.error_update_failed
    }
}

data class SelectionFlags(
    val allPinned: Boolean,
    val allMuted: Boolean,
    val allFavorite: Boolean,
    val anyUnread: Boolean,
    val allArchived: Boolean,
    val hasActiveGroup: Boolean,
)
