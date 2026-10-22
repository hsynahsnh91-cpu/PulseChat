package com.example.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ContactRow
import com.example.domain.Validators
import com.example.ui.common.ltrIsolate
import com.example.ui.components.UserAvatar
import com.example.ui.theme.Spacing

/** Filtering for the contact picker: name or nickname contains the query, or the username starts with it. */
object ContactSearch {
    fun filter(contacts: List<ContactRow>, query: String): List<ContactRow> {
        val q = query.trim()
        if (q.isEmpty()) return contacts
        val handle = Validators.normalizeUsername(q)
        return contacts.filter { c ->
            c.shownName.contains(q, ignoreCase = true) || c.displayName.contains(q, ignoreCase = true) ||
                (handle.isNotEmpty() && c.username.startsWith(handle, ignoreCase = true))
        }
    }

    /** What a contact card carries for a PulseChat user: the unique "@username". */
    fun handleOf(contact: ContactRow): String = "@" + contact.username

    /** True for a contact card that points at a PulseChat user rather than a phone number. */
    fun isPulseHandle(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.startsWith("@") && Validators.usernameError(v) == null
    }
}

/**
 * Picks one of my PulseChat contacts (saved contacts and people I chat with) to share in a chat.
 * Nothing leaves the app: the phone's address book is not read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareContactSheet(contacts: List<ContactRow>?, onDismiss: () -> Unit, onPick: (ContactRow) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().testTag("share_contact_sheet")) {
            Text(
                stringResource(R.string.contact_share_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = Spacing.screenEdge, end = Spacing.screenEdge, bottom = Spacing.md),
            )
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(64) }, singleLine = true,
                placeholder = { Text(stringResource(R.string.contact_share_search)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenEdge).testTag("share_contact_search"),
            )
            Spacer(Modifier.height(Spacing.sm))
            val shown = remember(contacts, query) { ContactSearch.filter(contacts.orEmpty(), query) }
            when {
                contacts == null -> Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                contacts.isEmpty() -> SheetNote(stringResource(R.string.contact_share_empty))
                shown.isEmpty() -> SheetNote(stringResource(R.string.contact_share_no_match))
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = Spacing.xxl)) {
                    items(shown, key = { it.userId }) { contact -> ContactPickRow(contact) { onPick(contact) } }
                }
            }
        }
    }
}

@Composable
private fun SheetNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
    )
}

@Composable
private fun ContactPickRow(contact: ContactRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp)
            .padding(horizontal = Spacing.screenEdge, vertical = Spacing.sm).testTag("share_contact_${contact.username}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(contact.shownName, avatarUrl = contact.avatarUrl, size = 44.dp, colorHex = contact.avatarColorHex)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(contact.shownName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ltrIsolate(ContactSearch.handleOf(contact), LocalLayoutDirection.current == LayoutDirection.Rtl),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
