package com.example.ui.chat

import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.R
import com.example.data.model.Contact
import com.example.data.model.Conversation
import com.example.data.model.Message
import com.example.data.model.Receipt
import com.example.data.model.User
import com.example.data.prefs.AppSettings
import com.example.data.repository.AiError
import com.example.data.repository.AiException
import com.example.data.repository.AiRepository
import com.example.data.repository.ChatError
import com.example.data.repository.DiagnosticsLog
import com.example.data.repository.ScheduleException
import com.example.data.repository.TranslationException
import com.example.data.model.MessageTranscript
import com.example.data.model.ReminderSource
import com.example.data.model.ScheduledMessage
import com.example.domain.AiParsing
import com.example.domain.TextTools
import com.example.data.repository.ChatException
import com.example.data.repository.GroupValidationException
import com.example.domain.Ids
import com.example.domain.MediaRefs
import com.example.domain.Mentions
import com.example.domain.MessagePolicy
import com.example.domain.MessageTimeline
import com.example.domain.NetworkKind
import com.example.domain.PresenceDisplay
import com.example.domain.model.MessageStatus
import com.example.domain.model.MessageType
import com.example.media.VoicePlayer
import com.example.media.VoiceRecorder
import com.example.ui.common.aiErrorText
import com.example.ui.common.chatErrorText
import com.example.ui.common.groupErrorText
import com.example.util.LocaleHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import java.util.TimeZone
import java.io.File
import java.util.Calendar

sealed interface ChatItem { val key: String }
data class MessageItem(val message: Message, val showSender: Boolean, val groupedWithOlder: Boolean) : ChatItem { override val key: String get() = message.messageId }
data class DayItem(val at: Long) : ChatItem { override val key: String get() = "day_$at" }
data class UnreadItem(val count: Int) : ChatItem { override val key: String get() = "unread_separator" }

sealed interface ChatEvent {
    data class Toast(val text: String) : ChatEvent
    data class NeedAiConsent(val action: () -> Unit) : ChatEvent
    /** On-device translation needs language packs; the user confirms the download. */
    data class NeedLanguageDownload(val messageId: String, val languages: List<String>) : ChatEvent
}

data class AiPanel(val busy: Boolean = false, val title: Int = 0, val suggestions: List<String> = emptyList(), val text: String? = null, val replaceDraft: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChatViewModel(private val c: AppContainer, val conversationId: String, initialJump: String?) : ViewModel() {
    val me: String = c.authRepository.currentUid.orEmpty()
    private val repo = c.chatRepository
    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    val conversation: StateFlow<Conversation?> = repo.observeConversation(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val limit = MutableStateFlow(PAGE)
    val messages: StateFlow<List<Message>> = limit.flatMapLatest { repo.observeMessages(conversationId, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val participants: StateFlow<List<User>> = conversation.map { it?.getParticipantsList() ?: emptyList() }.distinctUntilChanged()
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyList()) else c.userRepository.observeUsers(ids) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val peerContact: StateFlow<Contact?> = conversation.map { it?.peerId }.distinctUntilChanged()
        .flatMapLatest { peer -> if (peer == null) flowOf(null) else c.userRepository.observeContact(peer) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val typing: StateFlow<Set<String>> = repo.observeTyping(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val presence: StateFlow<PresenceDisplay> = conversation.map { if (it == null || it.isGroup) null else it.peerId }.distinctUntilChanged()
        .flatMapLatest { peer -> if (peer == null) flowOf(PresenceDisplay.Hidden) else c.userRepository.observePresence(peer) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PresenceDisplay.Hidden)
    val blockedIds: StateFlow<List<String>> = c.userRepository.observeBlockedIds().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val receipts: StateFlow<List<Receipt>> = repo.observeReceipts(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> = c.prefs.settings
    val network: StateFlow<NetworkKind> = c.connectivity.network
    val voice: StateFlow<VoicePlayer.State> = c.voicePlayer.state
    private val recorder = VoiceRecorder(c.app)
    val recording: StateFlow<VoiceRecorder.State> = recorder.state

    // ---- timeline
    private var unreadSnapshot: Pair<Long, Long>? = null // (lastReadAt at open, newest message at open)
    val items: StateFlow<List<ChatItem>> = combine(messages, conversation.filterNotNull()) { list, conv ->
        if (unreadSnapshot == null && list.isNotEmpty()) unreadSnapshot = conv.lastReadAt to list.first().createdAt
        buildItems(list, conv.isGroup, unreadSnapshot)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- composer state
    val draft = MutableStateFlow(TextFieldValue(""))
    val replyTo = MutableStateFlow<Message?>(null)
    val editing = MutableStateFlow<Message?>(null)
    private val chosenMentions = mutableListOf<Mentions.Candidate>()
    val mentionSuggestions: StateFlow<List<Mentions.Candidate>> = combine(draft, participants, conversation) { value, users, conv ->
        if (conv?.isGroup != true) return@combine emptyList()
        val q = Mentions.activeQuery(value.text, value.selection.end) ?: return@combine emptyList()
        Mentions.suggestions(q, users.map { Mentions.Candidate(it.userId, it.displayName) }, exclude = me)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- selection, search, jumps
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val searchActive = MutableStateFlow(false)
    val searchQuery = MutableStateFlow("")
    val searchResults = MutableStateFlow<List<Message>>(emptyList())
    val searchIndex = MutableStateFlow(-1)
    private val _scrollTo = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val scrollTo: SharedFlow<Int> = _scrollTo.asSharedFlow()
    val highlighted = MutableStateFlow<String?>(null)
    val loadingOlder = MutableStateFlow(false)
    private var reachedServerEnd = false

    // ---- AI
    val ai = MutableStateFlow(AiPanel())
    val aiTyping = MutableStateFlow(false)
    private var aiJob: Job? = null

    private var typingJob: Job? = null

    // ---- phase 1: scheduled messages, reminders, translation, transcripts, AI summary & tasks
    val scheduled: StateFlow<List<ScheduledMessage>> = c.scheduledMessages.observeForConversation(conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val translations = MutableStateFlow<Map<String, TranslationUi>>(emptyMap())
    private val transcriptState = MutableStateFlow<Map<String, TranscriptUi>>(emptyMap())
    /** Saved transcripts (Room) merged with in-flight/failed requests of this screen. */
    val transcripts: StateFlow<Map<String, TranscriptUi>> = combine(c.db.transcriptDao().observeForConversation(conversationId), transcriptState) { saved, local ->
        val out = saved.associate { it.messageId to TranscriptUi(text = it.text) }.toMutableMap()
        local.forEach { (id, ui) -> out[id] = if (ui.loading || ui.error != null || out[id] == null) ui else out.getValue(id).copy(hidden = ui.hidden) }
        out.toMap()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val summary = MutableStateFlow<SummaryUi?>(null)
    val actionSuggestions = MutableStateFlow<List<ActionCandidateUi>?>(null)
    /** Messages from others since the last visit (read watermark when the chat was opened). */
    val newSinceVisit = MutableStateFlow(0)
    val newBannerDismissed = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val conv = conversation.filterNotNull().first()
            if (draft.value.text.isEmpty() && !conv.draftText.isNullOrEmpty()) {
                draft.value = TextFieldValue(conv.draftText, TextRange(conv.draftText.length))
            }
            c.userRepository.ensureProfiles(conv.getParticipantsList())
        }
        viewModelScope.launch {
            draft.map { it.text }.distinctUntilChanged().debounce(600).collect { text ->
                if (editing.value == null) repo.setDraft(conversationId, text)
            }
        }
        viewModelScope.launch { repo.errors.collect { _events.tryEmit(ChatEvent.Toast(chatErrorText(c.app, it))) } }
        if (initialJump != null) jumpTo(initialJump)
        viewModelScope.launch {
            items.first { it.isNotEmpty() }
            val since = unreadSnapshot?.first ?: return@launch
            if (since > 0) newSinceVisit.value = runCatching { c.db.messageDao().countIncomingSince(conversationId, me, since) }.getOrDefault(0)
        }
    }

    private fun buildItems(desc: List<Message>, isGroup: Boolean, snapshot: Pair<Long, Long>?): List<ChatItem> {
        val asc = desc.asReversed()
        val out = ArrayList<ChatItem>(asc.size + 8)
        val unreadIndex = if (snapshot == null) -1 else asc.indexOfFirst {
            it.senderId != me && it.type != MessageType.SYSTEM && it.createdAt > snapshot.first && it.createdAt <= snapshot.second
        }
        var prev: Message? = null
        asc.forEachIndexed { i, m ->
            if (prev == null || !MessageTimeline.isSameDay(prev!!.createdAt, m.createdAt)) out += DayItem(startOfDay(m.createdAt))
            if (i == unreadIndex) out += UnreadItem(asc.size - i)
            val grouped = prev?.let { p ->
                MessageTimeline.isGroupedWithPrevious(MessageTimeline.Entry(p.messageId, p.senderId, p.createdAt, p.type), MessageTimeline.Entry(m.messageId, m.senderId, m.createdAt, m.type))
            } ?: false
            out += MessageItem(m, showSender = isGroup && m.senderId != me && !grouped && m.type != MessageType.SYSTEM, groupedWithOlder = grouped)
            prev = m
        }
        out.reverse()
        return out
    }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun toast(res: Int) { _events.tryEmit(ChatEvent.Toast(c.app.getString(res))) }

    private fun launchSafely(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() }
        catch (e: ChatException) { _events.tryEmit(ChatEvent.Toast(chatErrorText(c.app, e.error))) }
        catch (e: GroupValidationException) { _events.tryEmit(ChatEvent.Toast(groupErrorText(c.app, e.errors))) }
        catch (e: AiException) { _events.tryEmit(ChatEvent.Toast(aiErrorText(c.app, e.error))) }
        catch (e: Exception) { toast(R.string.error_update_failed) }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onVisible(visible: Boolean) {
        if (visible) {
            c.syncManager.activeConversationId = conversationId
            c.notifications.cancelConversation(conversationId)
            viewModelScope.launch { repo.markRead(conversationId) }
        } else {
            if (c.syncManager.activeConversationId == conversationId) c.syncManager.activeConversationId = null
            stopTyping()
            c.voicePlayer.pause()
        }
    }

    override fun onCleared() {
        if (c.syncManager.activeConversationId == conversationId) c.syncManager.activeConversationId = null
        recorder.cancel()
        super.onCleared()
    }

    // ------------------------------------------------------------------ composer

    fun onDraftChange(value: TextFieldValue) {
        draft.value = value
        if (conversation.value?.isAiConversation == true) return
        if (value.text.isNotBlank()) {
            repo.setTyping(conversationId, true)
            typingJob?.cancel()
            typingJob = viewModelScope.launch { delay(4_000); repo.setTyping(conversationId, false) }
        } else stopTyping()
    }

    private fun stopTyping() { typingJob?.cancel(); repo.setTyping(conversationId, false) }

    fun pickMention(candidate: Mentions.Candidate) {
        val value = draft.value
        val ins = Mentions.insert(value.text, value.selection.end, candidate.displayName)
        chosenMentions += candidate
        draft.value = TextFieldValue(ins.text, TextRange(ins.cursor))
    }

    fun startReply(m: Message) { editing.value = null; replyTo.value = m }
    fun cancelReply() { replyTo.value = null }

    fun startEdit(m: Message) {
        replyTo.value = null
        editing.value = m
        draft.value = TextFieldValue(m.content, TextRange(m.content.length))
    }

    fun cancelEdit() {
        editing.value = null
        draft.value = TextFieldValue(conversation.value?.draftText.orEmpty())
    }

    fun send() {
        val text = draft.value.text
        if (text.isBlank()) return
        val conv = conversation.value ?: return
        val edit = editing.value
        if (edit != null) {
            editing.value = null
            draft.value = TextFieldValue("")
            launchSafely { repo.editMessage(edit.messageId, text) }
            return
        }
        if (conv.isAiConversation) { askAssistant(text); return }
        val reply = replyTo.value
        val mentions = Mentions.resolve(text, chosenMentions)
        draft.value = TextFieldValue("")
        replyTo.value = null
        chosenMentions.clear()
        stopTyping()
        launchSafely { repo.sendText(conversationId, text, reply, mentions) }
    }

    fun sendMedia(uri: Uri, type: MessageType, caption: String) {
        val reply = replyTo.value
        replyTo.value = null
        launchSafely { repo.sendMediaUri(conversationId, uri, type, caption, reply) }
    }

    fun sendContact(name: String, phone: String) = launchSafely { repo.sendContact(conversationId, name, phone) }
    fun sendLocation(lat: Double, lng: Double) = launchSafely { repo.sendLocation(conversationId, lat, lng) }
    fun sendPoll(question: String, options: List<String>) = launchSafely { repo.sendPoll(conversationId, question, options) }

    fun startRecording(): Boolean {
        c.voicePlayer.stop()
        val ok = recorder.start(viewModelScope)
        if (!ok) toast(R.string.voice_record_failed)
        return ok
    }

    fun finishRecording(send: Boolean) {
        if (!send) { recorder.cancel(); return }
        val result = recorder.stop()
        if (result == null) { toast(R.string.voice_too_short); return }
        val reply = replyTo.value
        replyTo.value = null
        launchSafely { repo.sendVoice(conversationId, result.file, result.durationMs, result.waveform, reply) }
    }

    // ------------------------------------------------------------------ message actions

    fun facts(m: Message) = MessagePolicy.Facts(
        isMine = m.senderId == me, type = m.type, status = m.status, createdAt = m.createdAt, deletedForEveryone = m.deletedForEveryone,
        hasText = m.content.isNotBlank() || m.type == MessageType.LOCATION || m.type == MessageType.CONTACT,
        isAiConversation = conversation.value?.isAiConversation == true,
    )

    fun now(): Long = c.serverClock.now()
    fun retry(id: String) = launchSafely { repo.retry(id) }
    fun react(id: String, emoji: String?) = launchSafely { repo.react(id, emoji) }
    fun vote(id: String, option: Int) = launchSafely { repo.votePoll(id, option) }
    fun download(id: String) = launchSafely { repo.downloadMedia(id) }
    fun cancelTransfer(m: Message) { if (m.senderId == me) repo.cancelUpload(m.messageId) else repo.cancelDownload(m.messageId) }

    fun star(ids: Collection<String>, starred: Boolean) = launchSafely {
        repo.setStarred(ids.toList(), starred)
        toast(if (starred) R.string.toast_starred else R.string.toast_unstarred)
        selection.value = emptySet()
    }

    fun deleteForMe(ids: Collection<String>) = launchSafely { repo.deleteForMe(ids.toList()); selection.value = emptySet() }
    fun deleteForEveryone(ids: Collection<String>) = launchSafely { repo.deleteForEveryone(ids.toList()); selection.value = emptySet() }

    fun forward(ids: Collection<String>, targets: List<String>) = launchSafely {
        val n = repo.forward(ids.toList(), targets)
        if (n > 0) toast(R.string.toast_forwarded)
        selection.value = emptySet()
    }

    suspend fun forwardTargets(): List<Conversation> = repo.forwardTargets()
    suspend fun messagesFor(ids: Collection<String>): List<Message> = repo.getMessages(ids.toList()).sortedBy { it.createdAt }

    fun toggleSelect(id: String) {
        val s = selection.value
        selection.value = if (id in s) s - id else s + id
    }

    fun clearSelection() { selection.value = emptySet() }

    fun toggleVoice(m: Message) {
        val local = m.localMediaPath?.takeIf { File(it).exists() }
        val remote = m.mediaUrl
        when {
            local != null -> c.voicePlayer.toggle(m.messageId, local, m.mediaDurationSeconds * 1000L)
            remote == null -> Unit
            // Built-in media store files cannot be streamed: download, then play.
            MediaRefs.isPulse(remote) -> viewModelScope.launch {
                repo.downloadMedia(m.messageId)
                val path = c.db.messageDao().get(m.messageId)?.localMediaPath?.takeIf { File(it).exists() } ?: return@launch
                c.voicePlayer.toggle(m.messageId, path, m.mediaDurationSeconds * 1000L)
            }
            else -> c.voicePlayer.toggle(m.messageId, remote, m.mediaDurationSeconds * 1000L)
        }
    }

    fun seekVoice(m: Message, fraction: Float) = c.voicePlayer.seekTo(m.messageId, fraction)
    fun cycleVoiceSpeed() = c.voicePlayer.cycleSpeed()

    // ------------------------------------------------------------------ paging & jumps

    fun loadMore() {
        if (loadingOlder.value) return
        val conv = conversation.value ?: return
        viewModelScope.launch {
            loadingOlder.value = true
            try {
                val shown = messages.value.size
                if (shown >= limit.value) {
                    limit.value = limit.value + PAGE
                } else if (!reachedServerEnd && !conv.isLocalOnly && network.value != NetworkKind.NONE) {
                    val fetched = runCatching { repo.loadOlder(conversationId) }.getOrDefault(0)
                    if (fetched == 0) reachedServerEnd = true else limit.value = limit.value + PAGE
                }
            } finally {
                loadingOlder.value = false
            }
        }
    }

    fun jumpTo(messageId: String) {
        viewModelScope.launch {
            val m = repo.getMessage(messageId)
            if (m == null || m.deletedForMe) { toast(R.string.toast_message_unavailable); return@launch }
            val pos = repo.positionFromNewest(m)
            if (limit.value <= pos + 10) limit.value = pos + PAGE
            val list = withTimeoutOrNull(3_000) { items.first { l -> l.any { it is MessageItem && it.message.messageId == messageId } } } ?: return@launch
            val index = list.indexOfFirst { it is MessageItem && it.message.messageId == messageId }
            highlighted.value = messageId
            _scrollTo.tryEmit(index)
            delay(2_000)
            if (highlighted.value == messageId) highlighted.value = null
        }
    }

    // ------------------------------------------------------------------ in-chat search

    fun setSearchActive(active: Boolean) {
        searchActive.value = active
        if (!active) { searchQuery.value = ""; searchResults.value = emptyList(); searchIndex.value = -1 }
    }

    fun search(query: String) {
        searchQuery.value = query
        viewModelScope.launch {
            val results = if (query.isBlank()) emptyList() else repo.searchInConversation(conversationId, query)
            searchResults.value = results
            searchIndex.value = if (results.isEmpty()) -1 else 0
            results.firstOrNull()?.let { jumpTo(it.messageId) }
        }
    }

    /** Moves through results: +1 = older, -1 = newer (results are newest first). */
    fun searchStep(delta: Int) {
        val results = searchResults.value
        if (results.isEmpty()) return
        val next = (searchIndex.value + delta).coerceIn(0, results.size - 1)
        searchIndex.value = next
        jumpTo(results[next].messageId)
    }

    // ------------------------------------------------------------------ conversation actions

    fun unblockPeer() = launchSafely { conversation.value?.peerId?.let { c.userRepository.unblock(it) } }
    fun setMuted(until: Long) = launchSafely { repo.setMutedUntil(conversationId, until) }
    fun clearChat(keepStarred: Boolean) = launchSafely { repo.clearChat(conversationId, keepStarred) }
    fun setDisappearing(seconds: Long) = launchSafely { repo.setDisappearing(conversationId, seconds) }
    fun blockPeer() = launchSafely { conversation.value?.peerId?.let { c.userRepository.block(it) } }
    fun reportPeer(alsoBlock: Boolean) = launchSafely {
        val conv = conversation.value ?: return@launchSafely
        val recent = messages.value.filter { it.senderId != me }.take(5).map { it.messageId }
        c.userRepository.report(conv.peerId, conversationId, recent, "user_report")
        if (alsoBlock) conv.peerId?.let { c.userRepository.block(it) }
        toast(R.string.toast_reported)
    }

    // ------------------------------------------------------------------ AI (explicit, consented)

    fun grantAiConsent() = c.prefs.setAiConsent(true)

    private fun withConsent(action: () -> Unit) {
        if (settings.value.aiConsent) action() else _events.tryEmit(ChatEvent.NeedAiConsent(action))
    }

    private fun languageName(): String = if (LocaleHelper.isArabic(c.app)) "Arabic" else "the same language as the conversation"

    private fun transcript(limitCount: Int = 40): String {
        val names = participants.value.associate { it.userId to it.displayName }
        return messages.value.take(limitCount).asReversed()
            .filter { !it.deletedForEveryone && it.content.isNotBlank() && it.type != MessageType.SYSTEM }
            .joinToString("\n") { (if (it.senderId == me) "Me" else names[it.senderId] ?: "Them") + ": " + it.content }
    }

    private fun askAssistant(text: String) = withConsent {
        draft.value = TextFieldValue("")
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            try {
                val history = messages.value.asReversed()
                    .filter { (it.type == MessageType.TEXT || it.type == MessageType.AI_RESPONSE) && !it.deletedForEveryone }
                    .takeLast(20).map { (it.senderId == me) to it.content }
                repo.sendText(conversationId, text, null, emptyList())
                aiTyping.value = true
                val reply = c.aiRepository.chat(history, text, c.app.getString(R.string.ai_system_prompt))
                repo.insertLocal(
                    Message(
                        messageId = Ids.newMessageId(), conversationId = conversationId, senderId = Ids.AI_ASSISTANT_ID, content = reply,
                        type = MessageType.AI_RESPONSE, status = MessageStatus.READ, createdAt = System.currentTimeMillis(), isAiGenerated = true,
                    )
                )
                repo.markRead(conversationId)
            } catch (e: AiException) {
                _events.tryEmit(ChatEvent.Toast(aiErrorText(c.app, e.error)))
            } catch (e: ChatException) {
                _events.tryEmit(ChatEvent.Toast(chatErrorText(c.app, e.error)))
            } finally {
                aiTyping.value = false
            }
        }
    }

    fun cancelAi() {
        aiJob?.cancel()
        aiTyping.value = false
        ai.value = AiPanel()
    }

    fun aiSuggestReplies() = withConsent {
        val lastIncoming = messages.value.filter { it.senderId != me && it.content.isNotBlank() }.take(6).asReversed().map { it.content }
        if (lastIncoming.isEmpty()) { toast(R.string.ai_nothing_to_reply); return@withConsent }
        runAi(R.string.ai_suggest_replies) { AiPanel(title = R.string.ai_suggest_replies, suggestions = c.aiRepository.smartReplies(lastIncoming, languageName())) }
    }

    fun aiPolish() = withConsent {
        val text = draft.value.text
        if (text.isBlank()) { toast(R.string.ai_nothing_to_polish); return@withConsent }
        runAi(R.string.ai_polish) { AiPanel(title = R.string.ai_polish, text = c.aiRepository.polish(text, languageName()), replaceDraft = true) }
    }

    fun aiSummarize() = withConsent {
        val t = transcript()
        if (t.isBlank()) { toast(R.string.ai_nothing_to_summarize); return@withConsent }
        runAi(R.string.ai_summarize) { AiPanel(title = R.string.ai_summarize, text = c.aiRepository.summarize(t, languageName())) }
    }

    private fun runAi(title: Int, block: suspend () -> AiPanel) {
        aiJob?.cancel()
        ai.value = AiPanel(busy = true, title = title)
        aiJob = viewModelScope.launch {
            try { ai.value = block() }
            catch (e: AiException) { ai.value = AiPanel(); _events.tryEmit(ChatEvent.Toast(aiErrorText(c.app, e.error))) }
            catch (e: Exception) { if (e !is kotlinx.coroutines.CancellationException) { ai.value = AiPanel(); toast(R.string.error_unknown) } }
        }
    }

    fun useSuggestion(text: String) {
        draft.value = TextFieldValue(text, TextRange(text.length))
        ai.value = AiPanel()
    }

    // ------------------------------------------------------------------ scheduled messages

    private fun scheduleErrorToast(e: ScheduleException) = toast(when (e.error) {
        com.example.domain.ScheduledPolicy.Error.TOO_SOON -> R.string.schedule_error_too_soon
        com.example.domain.ScheduledPolicy.Error.TOO_FAR -> R.string.schedule_error_too_far
        com.example.domain.ScheduledPolicy.Error.TOO_LONG -> R.string.error_too_long
        com.example.domain.ScheduledPolicy.Error.EMPTY -> R.string.error_empty
    })

    /** Schedules [text]; on success the composer is cleared. */
    fun schedule(text: String, at: Long) = viewModelScope.launch {
        try {
            c.scheduledMessages.schedule(conversationId, text, at)
            if (draft.value.text.trim() == text.trim()) { draft.value = TextFieldValue(""); repo.setDraft(conversationId, "") }
            toast(R.string.schedule_done)
        } catch (e: ScheduleException) { scheduleErrorToast(e) }
        catch (e: ChatException) { _events.tryEmit(ChatEvent.Toast(chatErrorText(c.app, e.error))) }
        catch (e: Exception) { if (e is CancellationException) throw e; toast(R.string.error_update_failed) }
    }

    fun editScheduled(id: String, text: String, at: Long) = viewModelScope.launch {
        try { if (!c.scheduledMessages.edit(id, text, at)) toast(R.string.scheduled_locked) }
        catch (e: ScheduleException) { scheduleErrorToast(e) }
    }

    fun cancelScheduled(id: String) = viewModelScope.launch { if (!c.scheduledMessages.cancel(id)) toast(R.string.scheduled_locked) }

    fun sendScheduledNow(id: String) = viewModelScope.launch { if (!c.scheduledMessages.sendNow(id)) toast(R.string.scheduled_locked) }

    // ------------------------------------------------------------------ reminders

    val exactAlarmsAllowed: Boolean get() = c.reminders.scheduler.canScheduleExact()

    fun remindAbout(m: Message, at: Long) = launchSafely {
        c.reminders.createForMessage(m, at)
        _events.tryEmit(ChatEvent.Toast(c.app.getString(R.string.reminder_set, com.example.ui.common.Formatters.dateTime(c.app, at))))
    }

    // ------------------------------------------------------------------ translation (on device)

    fun translationTarget(): String = settings.value.translationTarget.ifBlank {
        c.translations.defaultTarget(settings.value.language.tag ?: java.util.Locale.getDefault().language)
    }

    fun languageName(tag: String): String = c.translations.displayName(tag)

    fun languageChoices(): List<Pair<String, String>> = c.translations.supportedLanguages().map { it to c.translations.displayName(it) }

    fun translate(m: Message, allowDownload: Boolean = false, target: String = translationTarget()) {
        translations.update { it + (m.messageId to TranslationUi(loading = true, targetLang = target)) }
        viewModelScope.launch {
            val ui = try {
                val r = c.translations.translate(m, target, allowDownload)
                TranslationUi(text = r.text, sourceLang = r.sourceLang, targetLang = r.targetLang)
            } catch (e: TranslationException) {
                when (e.reason) {
                    TranslationException.Reason.NEEDS_DOWNLOAD -> {
                        _events.tryEmit(ChatEvent.NeedLanguageDownload(m.messageId, e.languages))
                        null
                    }
                    else -> {
                        if (e.reason == TranslationException.Reason.DOWNLOAD_FAILED) c.diagnosticsLog.record(DiagnosticsLog.TRANSLATION, "translation_download_failed")
                        TranslationUi(targetLang = target, error = c.app.getString(when (e.reason) {
                            TranslationException.Reason.SAME_LANGUAGE -> R.string.translate_error_same
                            TranslationException.Reason.UNDETECTED -> R.string.translate_error_undetected
                            TranslationException.Reason.UNSUPPORTED -> R.string.translate_error_unsupported
                            TranslationException.Reason.DOWNLOAD_FAILED -> R.string.translate_error_download
                            TranslationException.Reason.EMPTY -> R.string.translate_error_empty
                            else -> R.string.translate_error_failed
                        }))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationUi(targetLang = target, error = c.app.getString(R.string.translate_error_failed))
            }
            translations.update { if (ui == null) it - m.messageId else it + (m.messageId to ui) }
        }
    }

    fun translateById(id: String, allowDownload: Boolean) = viewModelScope.launch { repo.getMessage(id)?.let { translate(it, allowDownload) } }

    fun setTranslationTarget(tag: String, retranslate: Message?) {
        c.prefs.setTranslationTarget(tag)
        if (retranslate != null) translate(retranslate, target = tag)
    }

    fun toggleOriginal(id: String) = translations.update { m -> m[id]?.let { m + (id to it.copy(showOriginal = !it.showOriginal)) } ?: m }
    fun closeTranslation(id: String) = translations.update { it - id }

    // ------------------------------------------------------------------ AI (explicit actions after consent)

    private fun reportAi(e: AiException) {
        _events.tryEmit(ChatEvent.Toast(aiErrorText(c.app, e.error)))
        if (e.error == AiError.NOT_CONFIGURED || e.error == AiError.RATE_LIMITED || e.error == AiError.NETWORK || e.error == AiError.TIMEOUT) {
            c.diagnosticsLog.record(DiagnosticsLog.AI, "ai_" + e.error.name.lowercase())
        }
    }

    /** Lines "m1 | Sara: text" for the model, plus the alias -> message ID map (only aliases are sent). */
    private fun aiLines(msgs: List<Message>): Pair<String, Map<String, String>> {
        val names = participants.value.associate { it.userId to it.displayName }
        val aliases = LinkedHashMap<String, String>()
        val lines = ArrayList<String>()
        var budget = AI_CONTEXT_CHARS
        msgs.asReversed().forEach { m ->
            val body = when (m.type) {
                MessageType.TEXT, MessageType.AI_RESPONSE -> m.content
                MessageType.IMAGE -> "[photo] " + m.content
                MessageType.VIDEO -> "[video] " + m.content
                MessageType.AUDIO -> "[voice message]" + (transcripts.value[m.messageId]?.text?.let { " transcript: $it" } ?: "")
                MessageType.DOCUMENT -> "[document] " + (m.fileName ?: "")
                MessageType.POLL -> "[poll] " + (m.pollQuestion ?: "")
                MessageType.LOCATION -> "[location]"
                MessageType.CONTACT -> "[contact card]"
                else -> ""
            }.trim().take(600)
            if (body.isEmpty() || budget <= 0) return@forEach
            val sender = if (m.senderId == me) "Me" else names[m.senderId] ?: "Member"
            lines += "${m.messageId}\u0000$sender: $body"
            budget -= body.length + sender.length + 8
        }
        val ordered = lines.asReversed().mapIndexed { i, l ->
            val alias = "m${i + 1}"
            aliases[alias] = l.substringBefore('\u0000')
            "$alias | " + l.substringAfter('\u0000')
        }
        return ordered.joinToString("\n") to aliases
    }

    fun summarizeSinceLastVisit() = withConsent {
        val since = unreadSnapshot?.first
        if (since == null || newSinceVisit.value == 0) { toast(R.string.ai_nothing_to_summarize); return@withConsent }
        aiJob?.cancel()
        summary.value = SummaryUi(busy = true)
        aiJob = viewModelScope.launch {
            try {
                val msgs = repo.messagesSince(conversationId, since, AI_MAX_MESSAGES).filter { it.type != MessageType.SYSTEM }
                if (msgs.isEmpty()) { summary.value = null; toast(R.string.ai_nothing_to_summarize); return@launch }
                val (lines, aliases) = aiLines(msgs)
                val raw = c.aiRepository.summarizeWithReferences(lines, languageName())
                val points = AiParsing.parseSummary(raw, aliases.keys)
                if (points.isEmpty()) throw AiException(AiError.BAD_RESPONSE)
                summary.value = SummaryUi(points = points.map { p -> SummaryPointUi(p.text, p.refs.mapNotNull { aliases[it] }) }, coveredCount = msgs.size)
                newBannerDismissed.value = true
            } catch (e: AiException) {
                summary.value = null
                reportAi(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                summary.value = null
                toast(R.string.error_unknown)
            }
        }
    }

    fun closeSummary() { aiJob?.cancel(); summary.value = null }

    /** Suggests tasks/events from recent messages; nothing is created until the user confirms. */
    fun findTasksAndEvents() = withConsent {
        aiJob?.cancel()
        ai.value = AiPanel(busy = true, title = R.string.ai_find_tasks)
        aiJob = viewModelScope.launch {
            try {
                val since = unreadSnapshot?.first
                val msgs = (if (since != null && newSinceVisit.value >= 5) repo.messagesSince(conversationId, since, AI_MAX_MESSAGES) else repo.recentMessages(conversationId, 80))
                    .filter { it.type != MessageType.SYSTEM }
                if (msgs.isEmpty()) { ai.value = AiPanel(); toast(R.string.ai_nothing_to_summarize); return@launch }
                val (lines, aliases) = aiLines(msgs)
                val zone = TimeZone.getDefault()
                val now = System.currentTimeMillis()
                val raw = c.aiRepository.extractActions(lines, AiParsing.nowIso(now, zone), zone.id, languageName())
                val items = AiParsing.parseActions(raw, aliases.keys)
                actionSuggestions.value = items.mapIndexed { i, item ->
                    val due = item.due?.let { AiParsing.parseDateTime(it, zone) }
                    val event = item.kind == AiParsing.ActionItem.Kind.EVENT
                    // Events: remind 30 minutes before when there is time; tasks: at the stated time.
                    val at = due?.let { if (event && it - 30 * 60_000L > now + 60_000L) it - 30 * 60_000L else it }?.takeIf { it > now }
                    ActionCandidateUi(i, event, item.title, at, item.refs.mapNotNull { aliases[it] }, checked = at != null)
                }
                ai.value = AiPanel()
            } catch (e: AiException) {
                ai.value = AiPanel()
                reportAi(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ai.value = AiPanel()
                toast(R.string.error_unknown)
            }
        }
    }

    fun createRemindersFrom(items: List<ActionCandidateUi>) = launchSafely {
        var created = 0
        items.forEach { item ->
            val at = item.at ?: return@forEach
            c.reminders.create(item.title, conversationId, item.messageIds.firstOrNull(), at, if (item.event) ReminderSource.AI_EVENT else ReminderSource.AI_TASK)
            created++
        }
        actionSuggestions.value = null
        _events.tryEmit(ChatEvent.Toast(c.app.resources.getQuantityString(R.plurals.reminders_created, created, created)))
    }

    fun dismissSuggestions() { actionSuggestions.value = null }

    /** Voice message -> text. The audio is sent to the AI service only after this explicit action. */
    fun transcribe(m: Message) = withConsent {
        if (m.type != MessageType.AUDIO) return@withConsent
        transcriptState.update { it + (m.messageId to TranscriptUi(loading = true)) }
        viewModelScope.launch {
            try {
                var path = m.localMediaPath
                if (path == null || !File(path).exists()) {
                    repo.downloadMedia(m.messageId)
                    path = repo.getMessage(m.messageId)?.localMediaPath
                }
                val file = path?.let { File(it) }?.takeIf { it.exists() } ?: throw ChatException(ChatError.FILE_UNREADABLE)
                if (file.length() > AiRepository.MAX_AUDIO_BYTES) throw AiException(AiError.TOO_LARGE)
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val text = c.aiRepository.transcribe(bytes, m.mediaMimeType?.takeIf { it.startsWith("audio/") } ?: "audio/mp4")
                c.db.transcriptDao().upsert(MessageTranscript(m.messageId, text.take(TRANSCRIPT_MAX), "gemini", System.currentTimeMillis()))
                transcriptState.update { it - m.messageId }
            } catch (e: AiException) {
                transcriptState.update { it + (m.messageId to TranscriptUi(error = aiErrorText(c.app, e.error))) }
                if (e.error != AiError.TOO_LARGE) c.diagnosticsLog.record(DiagnosticsLog.AI, "ai_" + e.error.name.lowercase())
            } catch (e: ChatException) {
                transcriptState.update { it + (m.messageId to TranscriptUi(error = chatErrorText(c.app, e.error))) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transcriptState.update { it + (m.messageId to TranscriptUi(error = c.app.getString(R.string.error_unknown))) }
            }
        }
    }

    fun toggleTranscript(id: String) = transcriptState.update { m ->
        val cur = m[id] ?: transcripts.value[id] ?: return@update m
        m + (id to cur.copy(hidden = !cur.hidden, loading = false, error = null))
    }

    companion object {
        const val PAGE = 60
        private const val AI_MAX_MESSAGES = 300
        private const val AI_CONTEXT_CHARS = 24_000
        private const val TRANSCRIPT_MAX = 8_000
    }
}
