package com.example.ui.misc

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.model.MessageSearchRow
import com.example.data.model.StarredRow
import com.example.domain.model.MessageType
import com.example.ui.common.BackTopBar
import com.example.ui.common.EmptyState
import com.example.ui.common.Formatters
import com.example.ui.common.appContainer
import com.example.ui.common.containerFactory
import com.example.ui.common.previewLine
import com.example.ui.home.ChatRow
import com.example.ui.home.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StarredScreen(onBack: () -> Unit, openMessage: (conversationId: String, messageId: String) -> Unit) {
    val container = appContainer()
    val me = container.authRepository.currentUid.orEmpty()
    val starred by remember { container.chatRepository.observeStarred(me) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(topBar = { BackTopBar(stringResource(R.string.starred_messages), onBack) }) { padding ->
        val list = starred
        if (list != null && list.isEmpty()) {
            EmptyState(Icons.Rounded.StarBorder, stringResource(R.string.starred_empty_title), stringResource(R.string.starred_empty_body), Modifier.padding(padding))
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(list.orEmpty(), key = { it.message.messageId }) { row: StarredRow ->
                val m = row.message
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { openMessage(m.conversationId, m.messageId) },
                        onLongClick = { scope.launch { container.chatRepository.setStarred(listOf(m.messageId), false) } })
                        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val sender = if (m.senderId == me) stringResource(R.string.you) else row.senderName ?: stringResource(R.string.unknown_user)
                        Text(if (row.conversationIsGroup == true) "$sender · ${row.conversationTitle}" else sender, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(previewLine(m.type, if (m.type == MessageType.DOCUMENT) m.fileName else m.content, false), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Formatters.dateTime(context, m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { scope.launch { container.chatRepository.setStarred(listOf(m.messageId), false) } }) {
                        Icon(Icons.Rounded.StarBorder, contentDescription = stringResource(R.string.action_unstar))
                    }
                }
            }
        }
    }
}

@Composable
fun ArchivedScreen(onBack: () -> Unit, openChat: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(key = "archived", factory = containerFactory { HomeViewModel(it) })
    val archived by vm.archivedChats.collectAsState()
    val selection by vm.selection.collectAsState()
    Scaffold(topBar = {
        BackTopBar(stringResource(R.string.archived_chats), onBack, subtitle = if (selection.isNotEmpty()) stringResource(R.string.selected_count, selection.size) else null) {
            if (selection.isNotEmpty()) IconButton(onClick = { vm.archiveSelected(false) }) { Icon(Icons.Rounded.Unarchive, stringResource(R.string.action_unarchive)) }
        }
    }) { padding ->
        if (archived.isEmpty()) EmptyState(Icons.Rounded.Archive, stringResource(R.string.archived_empty_title), stringResource(R.string.archived_empty_body), Modifier.padding(padding))
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Text(stringResource(R.string.archived_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            items(archived, key = { it.conversation.conversationId }) { row ->
                ChatRow(row, vm.me, emptySet(), row.conversation.conversationId in selection, selection.isNotEmpty(),
                    onClick = { if (selection.isNotEmpty()) vm.toggleSelection(row.conversation.conversationId) else openChat(row.conversation.conversationId) },
                    onLongClick = { vm.toggleSelection(row.conversation.conversationId) })
            }
        }
    }
}

/** Chats locked on this phone; reached only through [com.example.ui.security.ChatLockGuard]. */
@Composable
fun LockedChatsScreen(onBack: () -> Unit, openChat: (String) -> Unit) {
    val vm: HomeViewModel = viewModel(key = "locked", factory = containerFactory { HomeViewModel(it) })
    val locked by vm.lockedChats.collectAsState()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.chat_locked_list_title), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("locked_chats_list")) {
            item { Text(stringResource(R.string.locked_chats_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            if (locked.isEmpty()) item { EmptyState(Icons.Rounded.Lock, stringResource(R.string.locked_chats_empty)) }
            items(locked, key = { it.conversation.conversationId }) { row ->
                ChatRow(row, vm.me, emptySet(), selected = false, selectionMode = false,
                    onClick = { openChat(row.conversation.conversationId) }, onLongClick = { openChat(row.conversation.conversationId) })
            }
        }
    }
}

private enum class SearchFilter(val type: MessageType?, val links: Boolean, val label: Int) {
    ALL(null, false, R.string.filter_all), PHOTOS(MessageType.IMAGE, false, R.string.media_photos), VIDEOS(MessageType.VIDEO, false, R.string.media_videos),
    LINKS(null, true, R.string.media_links), DOCUMENTS(MessageType.DOCUMENT, false, R.string.media_documents), AUDIO(MessageType.AUDIO, false, R.string.media_audio)
}

@Composable
fun GlobalSearchScreen(initialQuery: String, onBack: () -> Unit, openMessage: (String, String) -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val me = container.authRepository.currentUid.orEmpty()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    var results by remember { mutableStateOf<List<MessageSearchRow>>(emptyList()) }
    LaunchedEffect(query, filter) {
        kotlinx.coroutines.delay(250)
        results = if (query.isBlank() && filter == SearchFilter.ALL) emptyList() else container.chatRepository.searchAll(query, filter.type, filter.links)
    }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.search_messages), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(stringResource(R.string.search_hint)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(stringResource(f.label)) }) }
            }
            if (results.isEmpty() && (query.isNotBlank() || filter != SearchFilter.ALL)) {
                EmptyState(Icons.Rounded.Search, stringResource(R.string.empty_search_title), stringResource(R.string.empty_search_body))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = { it.message.messageId }) { row ->
                    val m = row.message
                    val chat = if (row.conversationIsGroup == true) row.conversationTitle else row.peerName ?: row.conversationTitle
                    Column(Modifier.fillMaxWidth().clickable { openMessage(m.conversationId, m.messageId) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row {
                            Text(chat.orEmpty().ifBlank { stringResource(R.string.unknown_user) }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(Formatters.chatListTime(context, m.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val sender = if (m.senderId == me) stringResource(R.string.you) else row.senderName ?: ""
                        Text("$sender: " + previewLine(m.type, if (m.type == MessageType.DOCUMENT) m.fileName else m.content, false), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
