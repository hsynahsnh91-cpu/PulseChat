package com.example.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.AppContainer
import com.example.R
import com.example.data.model.ChatListRow
import com.example.data.model.FolderWithCount
import com.example.data.repository.FolderException
import com.example.data.repository.FolderRepository
import com.example.ui.common.BackTopBar
import com.example.ui.common.ConfirmDialog
import com.example.ui.common.EmptyState
import com.example.ui.common.LoadingBox
import com.example.ui.common.containerFactory
import com.example.ui.components.UserAvatar
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FoldersViewModel(private val c: AppContainer) : ViewModel() {
    private val me = c.authRepository.currentUid.orEmpty()
    val folders: StateFlow<List<FolderWithCount>?> = c.folders.observeFoldersWithCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val chats: StateFlow<List<ChatListRow>> = c.chatRepository.observeChatList(me)
        .map { list -> list.filter { !it.conversation.isHidden }.sortedByDescending { it.conversation.lastMessageTimestamp } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val errors = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** Returns true when saved; errors are reported through [errors]. */
    fun save(folderId: String?, name: String, members: Set<String>, onSaved: () -> Unit) = viewModelScope.launch {
        try {
            if (folderId == null) c.folders.create(name, members) else c.folders.update(folderId, name, members)
            onSaved()
        } catch (e: FolderException) {
            errors.tryEmit(folderErrorText(e.reason))
        }
    }

    fun delete(id: String) = viewModelScope.launch { c.folders.delete(id) }
    fun move(id: String, delta: Int) = viewModelScope.launch { c.folders.move(id, delta) }
    suspend fun members(id: String): Set<String> = c.folders.memberIds(id)
}

fun folderErrorText(reason: FolderException.Reason): Int = when (reason) {
    FolderException.Reason.EMPTY_NAME -> R.string.folder_error_empty
    FolderException.Reason.NAME_TOO_LONG -> R.string.folder_error_long
    FolderException.Reason.DUPLICATE_NAME -> R.string.folder_error_duplicate
    FolderException.Reason.LIMIT -> R.string.folder_error_limit
}

/** Create, rename, reorder and delete chat folders, and choose the chats in each. */
@Composable
fun FoldersScreen(onBack: () -> Unit) {
    val vm: FoldersViewModel = viewModel(factory = containerFactory { FoldersViewModel(it) })
    val context = LocalContext.current
    val folders by vm.folders.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<Pair<FolderWithCount?, Set<String>>?>(null) }
    var deleting by remember { mutableStateOf<FolderWithCount?>(null) }
    LaunchedEffect(Unit) { vm.errors.collect { snackbar.showSnackbar(context.getString(it)) } }
    var openFor by remember { mutableStateOf<FolderWithCount?>(null) }
    LaunchedEffect(openFor) { openFor?.let { f -> editor = f to vm.members(f.folderId); openFor = null } }
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.folders_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editor = null to emptySet() }, icon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                text = { Text(stringResource(R.string.folder_new)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = folders
        when {
            list == null -> LoadingBox(Modifier.padding(padding))
            list.isEmpty() -> EmptyState(Icons.Rounded.Folder, stringResource(R.string.folders_empty_title), stringResource(R.string.folders_empty_body), Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.folderId }) { f ->
                    val index = list.indexOf(f)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { openFor = f }.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.Medium)
                            Text(context.resources.getQuantityString(R.plurals.folder_chat_count, f.members, f.members), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.move(f.folderId, -1) }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.folder_move_up)) }
                        IconButton(onClick = { vm.move(f.folderId, 1) }, enabled = index < list.size - 1) { Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.folder_move_down)) }
                        IconButton(onClick = { openFor = f }) { Icon(Icons.Rounded.Edit, stringResource(R.string.action_edit)) }
                        IconButton(onClick = { deleting = f }) { Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete)) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    editor?.let { (folder, members) ->
        FolderEditorDialog(
            initialName = folder?.name.orEmpty(), initialMembers = members, chats = chats, isNew = folder == null,
            onSave = { name, ids -> vm.save(folder?.folderId, name, ids) { editor = null } },
            onDismiss = { editor = null },
        )
    }
    deleting?.let { f ->
        ConfirmDialog(title = stringResource(R.string.folder_delete_title, f.name), text = stringResource(R.string.folder_delete_body),
            confirmLabel = stringResource(R.string.action_delete), destructive = true,
            onConfirm = { vm.delete(f.folderId); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
fun FolderEditorDialog(initialName: String, initialMembers: Set<String>, chats: List<ChatListRow>, isNew: Boolean, onSave: (String, Set<String>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var selected by remember { mutableStateOf(initialMembers) }
    var query by remember { mutableStateOf("") }
    val shown = chats.filter { query.isBlank() || it.displayTitle.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.folder_new else R.string.folder_edit)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(FolderRepository.MAX_NAME) }, singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.folder_search_chats)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(LocalContext.current.resources.getQuantityString(R.plurals.folder_chat_count, selected.size, selected.size),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.conversation.conversationId }) { row ->
                        val id = row.conversation.conversationId
                        val checked = id in selected
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { selected = if (it) selected + id else selected - id }).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            UserAvatar(row.displayTitle, avatarUrl = if (row.conversation.isGroup) row.conversation.avatarUrl else row.peerAvatar, size = 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(row.displayTitle, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, selected) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Add to folder" for the chats selected on the Chats tab. */
@Composable
fun AssignFoldersDialog(folders: List<FolderWithCount>, initiallyChecked: Set<String>, onSave: (Set<String>) -> Unit, onNewFolder: () -> Unit, onDismiss: () -> Unit) {
    var checked by remember { mutableStateOf(initiallyChecked) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_add_to)) },
        text = {
            Column {
                if (folders.isEmpty()) Text(stringResource(R.string.folders_empty_body))
                folders.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = f.folderId in checked, role = Role.Checkbox,
                            onValueChange = { c -> checked = if (c) checked + f.folderId else checked - f.folderId }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f.folderId in checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(f.name)
                    }
                }
                TextButton(onClick = onNewFolder) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.folder_new))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(checked) }, enabled = folders.isNotEmpty()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
