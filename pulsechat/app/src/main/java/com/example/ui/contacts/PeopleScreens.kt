package com.example.ui.contacts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.User
import com.example.domain.GroupPolicy
import com.example.domain.TextTools
import com.example.ui.calls.rememberCallStarter
import com.example.ui.common.BackTopBar
import com.example.ui.common.EmptyState
import com.example.ui.common.SectionHeader
import com.example.ui.common.containerFactory
import com.example.ui.components.UserAvatar
import kotlinx.coroutines.launch

enum class PickMode { NEW_CHAT, CALL, NEW_GROUP, ADD_MEMBERS }

data class PeopleNav(
    val back: () -> Unit,
    val openChat: (String) -> Unit,
    val openNewGroup: () -> Unit,
    val openCall: () -> Unit,
)

@Composable
private fun PersonRow(name: String, subtitle: String?, avatar: String?, color: String, selected: Boolean? = null, enabled: Boolean = true, trailing: @Composable () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            UserAvatar(name, avatarUrl = avatar, size = 44.dp, colorHex = color)
            if (selected == true) Box(Modifier.size(20.dp).align(Alignment.BottomEnd).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (selected != null) Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        trailing()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeoplePickerScreen(mode: PickMode, conversationId: String?, nav: PeopleNav) {
    val vm: ContactsViewModel = viewModel(key = "people_${mode.name}_$conversationId", factory = containerFactory { ContactsViewModel(it) })
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val contacts by vm.contacts.collectAsState()
    val blocked by vm.blocked.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val searchError by vm.searchError.collectAsState()
    val selected by vm.selected.collectAsState()
    var existing by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailsStep by rememberSaveable { mutableStateOf(false) }
    val startCall = rememberCallStarter(onStarted = nav.openCall)
    val multi = mode == PickMode.NEW_GROUP || mode == PickMode.ADD_MEMBERS

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(conversationId) { if (conversationId != null) existing = vm.existingMembers(conversationId) }

    if (mode == PickMode.NEW_GROUP && detailsStep) {
        GroupDetailsStep(vm, onBack = { detailsStep = false }, onCreated = { cid -> nav.openChat(cid) })
        return
    }

    val title = stringResource(when (mode) {
        PickMode.NEW_CHAT -> R.string.new_chat
        PickMode.CALL -> R.string.calls_new
        PickMode.NEW_GROUP -> R.string.new_group
        PickMode.ADD_MEMBERS -> R.string.group_add_members
    })
    Scaffold(
        topBar = { BackTopBar(title, nav.back, subtitle = if (multi) stringResource(R.string.selected_count, selected.size) else null) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (multi && selected.isNotEmpty()) {
                FloatingActionButton(onClick = {
                    if (mode == PickMode.NEW_GROUP) detailsStep = true
                    else scope.launch { if (vm.addMembers(conversationId!!)) nav.back() }
                }) { Icon(if (mode == PickMode.NEW_GROUP) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.Check, stringResource(R.string.action_continue)) }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) } },
                    placeholder = { Text(stringResource(R.string.people_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (multi && selected.isNotEmpty()) {
                    LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(selected, key = { it }) { id ->
                            val u = vm.selectedUser(id)
                            InputChip(selected = true, onClick = { u?.let { vm.toggle(it) } }, label = { Text(u?.displayName ?: "") },
                                trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove), modifier = Modifier.size(16.dp)) })
                        }
                    }
                }
            }
            if (mode == PickMode.NEW_CHAT && query.isBlank()) item {
                PersonRow(stringResource(R.string.new_group), null, null, "#0D9488", onClick = nav.openNewGroup)
            }
            if (query.trim().length >= 2) {
                item { SectionHeader(stringResource(R.string.people_search_results)) }
                if (searchError) item { Text(stringResource(R.string.people_search_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                else if (!searching && results.isEmpty()) item { Text(stringResource(R.string.people_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
                items(results.filter { it.userId !in blocked }, key = { "r_" + it.userId }) { u ->
                    val already = u.userId in existing
                    val isContact = contacts.any { it.userId == u.userId }
                    PersonRow(
                        u.displayName, "@${u.username} · ${u.uniqueId10}", u.avatarUrl, u.avatarColorHex,
                        selected = if (multi) (u.userId in selected || already) else null, enabled = !already,
                        trailing = {
                            if (!multi && !isContact) IconButton(onClick = { vm.saveContact(u) }) { Icon(Icons.Rounded.PersonAdd, stringResource(R.string.contact_save)) }
                            if (mode == PickMode.CALL) CallButtons { video -> startCall(u.userId, video) }
                        },
                    ) { handlePick(mode, u.userId, vm, scope, nav, multi) { vm.toggle(u) } }
                }
            }
            val filteredContacts = contacts.filter { it.userId !in blocked && (query.isBlank() || TextTools.matches(query, it.shownName, it.username)) }
            item { SectionHeader(stringResource(R.string.contacts_title)) }
            if (filteredContacts.isEmpty()) item { EmptyState(Icons.Rounded.PersonAdd, stringResource(R.string.contacts_empty_title), stringResource(R.string.contacts_empty_body)) }
            items(filteredContacts, key = { "c_" + it.userId }) { row ->
                val already = row.userId in existing
                PersonRow(
                    row.shownName, row.statusMessage.ifBlank { "@" + row.username }, row.avatarUrl, row.avatarColorHex,
                    selected = if (multi) (row.userId in selected || already) else null, enabled = !already,
                    trailing = { if (mode == PickMode.CALL) CallButtons { video -> startCall(row.userId, video) } },
                ) { handlePick(mode, row.userId, vm, scope, nav, multi) { vm.toggleContact(row) } }
            }
        }
    }
}

@Composable
private fun CallButtons(onCall: (Boolean) -> Unit) {
    IconButton(onClick = { onCall(false) }) { Icon(Icons.Rounded.Call, stringResource(R.string.action_voice_call), tint = MaterialTheme.colorScheme.primary) }
    IconButton(onClick = { onCall(true) }) { Icon(Icons.Rounded.Videocam, stringResource(R.string.action_video_call), tint = MaterialTheme.colorScheme.primary) }
}

private fun handlePick(mode: PickMode, userId: String, vm: ContactsViewModel, scope: kotlinx.coroutines.CoroutineScope, nav: PeopleNav, multi: Boolean, toggle: () -> Unit) {
    when {
        multi -> toggle()
        mode == PickMode.NEW_CHAT -> scope.launch { nav.openChat(vm.openChat(userId)) }
        mode == PickMode.CALL -> scope.launch { nav.openChat(vm.openChat(userId)) }
        else -> Unit
    }
}

@Composable
private fun GroupDetailsStep(vm: ContactsViewModel, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val busy by vm.busy.collectAsState()
    val selected by vm.selected.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.new_group), onBack, subtitle = stringResource(R.string.group_members_count, selected.size + 1)) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (!busy) scope.launch { vm.createGroup(name, description, photo)?.let(onCreated) } }) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Check, stringResource(R.string.group_create))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                if (photo != null) AsyncImage(photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.CameraAlt, contentDescription = stringResource(R.string.group_change_photo), modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.size(16.dp))
            OutlinedTextField(value = name, onValueChange = { name = it.take(GroupPolicy.MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.group_name)) },
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(value = description, onValueChange = { description = it.take(GroupPolicy.MAX_DESCRIPTION) }, label = { Text(stringResource(R.string.group_description_optional)) },
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.group_creator_admin_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
