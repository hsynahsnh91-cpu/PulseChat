package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.User
import com.example.ui.components.UserAvatar

@Composable
fun NewChatDialog(
    users: List<User>,
    currentUserId: String?,
    onDismiss: () -> Unit,
    onStartDirectChat: (targetUserId: String) -> Unit,
    onCreateGroup: (title: String, memberIds: List<String>) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Direct, 1: Group
    var groupTitle by remember { mutableStateOf("") }
    val selectedMembers = remember { mutableStateListOf<String>() }

    val selectableUsers = users.filter { it.userId != currentUserId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("محادثة خاصة") },
                    icon = { Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("مجموعة جديدة") },
                    icon = { Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                var dialogSearch by remember { mutableStateOf("") }
                val filteredUsers = selectableUsers.filter {
                    if (dialogSearch.isBlank()) true
                    else it.displayName.contains(dialogSearch, ignoreCase = true) ||
                            it.username.contains(dialogSearch, ignoreCase = true) ||
                            it.uniqueId10.contains(dialogSearch)
                }

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = dialogSearch,
                        onValueChange = { dialogSearch = it },
                        placeholder = { Text("بحث بالاسم أو المعرف (10 أرقام)…", fontSize = 12.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    LazyColumn(modifier = Modifier.height(240.dp)) {
                        items(filteredUsers) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onStartDirectChat(user.userId)
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    displayName = user.displayName,
                                    size = 40.dp,
                                    isAiAssistant = user.isAiAssistant,
                                    isOnline = user.isOnline,
                                    colorHex = user.avatarColorHex
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = user.displayName,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "@${user.username} • المعرف: ${user.uniqueId10}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Group Creation Tab
                    OutlinedTextField(
                        value = groupTitle,
                        onValueChange = { groupTitle = it },
                        label = { Text("اسم المجموعة") },
                        placeholder = { Text("مثال: فريق العمل") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("group_title_field"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "حدد أعضاء المجموعة:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(selectableUsers.filter { !it.isAiAssistant }) { user ->
                            val isChecked = selectedMembers.contains(user.userId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) selectedMembers.remove(user.userId)
                                        else selectedMembers.add(user.userId)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedMembers.add(user.userId)
                                        else selectedMembers.remove(user.userId)
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                UserAvatar(
                                    displayName = user.displayName,
                                    size = 34.dp,
                                    colorHex = user.avatarColorHex
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = user.displayName,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab == 1) {
                Button(
                    onClick = {
                        if (groupTitle.isNotBlank() && selectedMembers.isNotEmpty()) {
                            onCreateGroup(groupTitle, selectedMembers.toList())
                            onDismiss()
                        }
                    },
                    enabled = groupTitle.isNotBlank() && selectedMembers.isNotEmpty(),
                    modifier = Modifier.testTag("create_group_button")
                ) {
                    Text("إنشاء المجموعة")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
