package com.example.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.AppContainer
import com.example.R
import com.example.data.model.Message
import com.example.domain.DurationFormat
import com.example.domain.LinkExtractor
import com.example.domain.model.MessageType
import com.example.ui.common.BackTopBar
import com.example.ui.common.EmptyState
import com.example.ui.common.Formatters
import com.example.ui.common.LoadingBox
import com.example.ui.common.appContainer
import com.example.ui.common.containerFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class LinkRow(val message: Message, val link: LinkExtractor.Link)

class ChatMediaViewModel(private val c: AppContainer, val conversationId: String) : ViewModel() {
    private val repo = c.chatRepository
    val media: StateFlow<List<Message>?> = repo.observeMessagesOfTypes(conversationId, listOf(MessageType.IMAGE, MessageType.VIDEO))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val docs: StateFlow<List<Message>?> = repo.observeMessagesOfTypes(conversationId, listOf(MessageType.DOCUMENT))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val voice: StateFlow<List<Message>?> = repo.observeMessagesOfTypes(conversationId, listOf(MessageType.AUDIO))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val links: StateFlow<List<LinkRow>?> = repo.observeMessagesWithLinks(conversationId)
        .map { list -> list.flatMap { m -> LinkExtractor.extract(m.content).map { LinkRow(m, it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun download(id: String) = viewModelScope.launch { runCatching { repo.downloadMedia(id) } }
}

/** Every photo, video, document, link and voice message of one conversation. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMediaScreen(conversationId: String, onBack: () -> Unit, openMedia: (String) -> Unit, showInChat: (String) -> Unit) {
    val vm: ChatMediaViewModel = viewModel(key = "media_$conversationId", factory = containerFactory { ChatMediaViewModel(it, conversationId) })
    val container = appContainer()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val media by vm.media.collectAsState()
    val docs by vm.docs.collectAsState()
    val links by vm.links.collectAsState()
    val voice by vm.voice.collectAsState()
    Scaffold(topBar = { BackTopBar(stringResource(R.string.media_browser_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                listOf(R.string.media_tab_media, R.string.media_tab_docs, R.string.media_tab_links, R.string.media_tab_voice).forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) })
                }
            }
            when (tab) {
                0 -> media.let { list ->
                    when {
                        list == null -> LoadingBox()
                        list.isEmpty() -> EmptyState(Icons.Rounded.Image, stringResource(R.string.media_empty_media))
                        else -> LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(list, key = { it.messageId }) { m ->
                                val label = stringResource(if (m.type == MessageType.VIDEO) R.string.preview_video else R.string.preview_photo) + ", " + Formatters.dateTime(context, m.createdAt)
                                Box(
                                    Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)
                                        .combinedClickable(onClick = { openMedia(m.messageId) }, onLongClick = { showInChat(m.messageId) })
                                        .semantics { contentDescription = label },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // Only files already on the device: browsing never downloads media behind the user's back.
                                    val model: File? = m.localMediaPath?.let { File(it) }?.takeIf { it.exists() && m.type == MessageType.IMAGE }
                                    if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    else Icon(if (m.type == MessageType.VIDEO) Icons.Rounded.PlayCircle else Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (m.type == MessageType.VIDEO) Icon(Icons.Rounded.PlayCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                                }
                            }
                        }
                    }
                }
                1 -> docs.let { list ->
                    when {
                        list == null -> LoadingBox()
                        list.isEmpty() -> EmptyState(Icons.Rounded.Description, stringResource(R.string.media_empty_docs))
                        else -> LazyColumn {
                            items(list, key = { it.messageId }) { m ->
                                MediaRow(
                                    icon = Icons.Rounded.Description,
                                    title = m.fileName ?: stringResource(R.string.preview_document),
                                    subtitle = listOfNotNull(m.fileSize, Formatters.dateTime(context, m.createdAt)).joinToString(stringResource(R.string.list_separator)),
                                    onClick = {
                                        val path = m.localMediaPath
                                        if (path != null && File(path).exists()) openDocument(context, container.media.contentUriFor(path), m.mediaMimeType)
                                        else { vm.download(m.messageId); Toast.makeText(context, R.string.media_downloading, Toast.LENGTH_SHORT).show() }
                                    },
                                    onShowInChat = { showInChat(m.messageId) },
                                )
                            }
                        }
                    }
                }
                2 -> links.let { list ->
                    when {
                        list == null -> LoadingBox()
                        list.isEmpty() -> EmptyState(Icons.Rounded.Link, stringResource(R.string.media_empty_links))
                        else -> LazyColumn {
                            items(list, key = { it.message.messageId + "|" + it.link.url }) { row ->
                                MediaRow(
                                    icon = Icons.Rounded.Link,
                                    title = row.link.host,
                                    subtitle = row.link.url,
                                    trailingText = Formatters.chatListTime(context, row.message.createdAt),
                                    onClick = {
                                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                        catch (e: ActivityNotFoundException) { Toast.makeText(context, R.string.error_no_app, Toast.LENGTH_SHORT).show() }
                                    },
                                    onShowInChat = { showInChat(row.message.messageId) },
                                )
                            }
                        }
                    }
                }
                else -> voice.let { list ->
                    when {
                        list == null -> LoadingBox()
                        list.isEmpty() -> EmptyState(Icons.Rounded.Mic, stringResource(R.string.media_empty_voice))
                        else -> LazyColumn {
                            items(list, key = { it.messageId }) { m ->
                                MediaRow(
                                    icon = Icons.Rounded.Mic,
                                    title = stringResource(R.string.preview_voice) + " · " + DurationFormat.clock(m.mediaDurationSeconds * 1000L),
                                    subtitle = Formatters.dateTime(context, m.createdAt),
                                    onClick = { showInChat(m.messageId) },
                                    onShowInChat = { showInChat(m.messageId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onShowInChat: () -> Unit,
    trailingText: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).combinedClickable(onClick = onClick, onLongClick = onShowInChat).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailingText?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = onShowInChat) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.media_show_in_chat)) }
    }
    HorizontalDivider()
}
