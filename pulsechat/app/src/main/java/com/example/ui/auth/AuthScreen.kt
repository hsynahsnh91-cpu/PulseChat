package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.data.repository.SessionState
import com.example.ui.common.containerFactory

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun AuthRoute(session: SessionState) {
    val vm: AuthViewModel = viewModel(factory = containerFactory { AuthViewModel(it.app, it.authRepository, it.prefs) })
    val ui by vm.ui.collectAsState()
    when (session) {
        is SessionState.NeedsProfile -> CompleteProfileScreen(session.suggestedName, ui, vm)
        is SessionState.ProfileUnavailable -> ProfileUnavailableScreen(ui, vm)
        else -> AuthScreen(ui, vm)
    }
}

@Composable
private fun AuthFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_app_logo), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.auth_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            content()
        }
    }
}

@Composable
private fun ErrorCard(error: String?, onDismiss: () -> Unit) {
    if (error == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, imeAction: ImeAction, onDone: () -> Unit = {}, tag: String) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(if (visible) R.string.auth_hide_password else R.string.auth_show_password))
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun PrimaryButton(text: String, loading: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth().height(52.dp).testTag(tag), shape = RoundedCornerShape(14.dp)) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AuthScreen(ui: AuthUiState, vm: AuthViewModel) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var identifier by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    AuthFrame {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0; vm.clearError() }, text = { Text(stringResource(R.string.auth_sign_in)) }, modifier = Modifier.testTag("tab_login"))
            Tab(selected = tab == 1, onClick = { tab = 1; vm.clearError() }, text = { Text(stringResource(R.string.auth_create_account)) }, modifier = Modifier.testTag("tab_register"))
        }
        Spacer(Modifier.height(20.dp))
        ErrorCard(ui.error, vm::clearError)
        if (tab == 0) {
            if (ui.recentAccounts.isNotEmpty()) {
                Text(stringResource(R.string.auth_recent_accounts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.recentAccounts.take(3).forEach { account ->
                        InputChip(
                            selected = identifier == account, onClick = { identifier = account },
                            label = { Text("@$account") },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.auth_forget_account),
                                    modifier = Modifier.size(18.dp).clip(CircleShape).clickable { vm.forgetRecent(account) })
                            },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = identifier, onValueChange = { identifier = it }, singleLine = true,
                label = { Text(stringResource(R.string.auth_identifier)) },
                supportingText = { Text(stringResource(R.string.auth_identifier_hint)) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("login_identifier"),
            )
            Spacer(Modifier.height(8.dp))
            PasswordField(password, { password = it }, stringResource(R.string.auth_password), ImeAction.Done, { vm.signIn(identifier, password) }, "login_password")
            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(R.string.auth_sign_in), ui.loading, identifier.isNotBlank() && password.isNotBlank(), "login_button") { vm.signIn(identifier, password) }
            if (ui.legacyAccounts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(12.dp)) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.auth_legacy_hint, ui.legacyAccounts.joinToString { "@$it" }), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(50) }, singleLine = true, label = { Text(stringResource(R.string.auth_display_name)) },
                leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().testTag("register_name"),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it.trim().lowercase().take(20) }, singleLine = true,
                label = { Text(stringResource(R.string.auth_username)) }, prefix = { Text("@") },
                supportingText = { Text(stringResource(R.string.auth_username_rules)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("register_username"),
            )
            Spacer(Modifier.height(8.dp))
            PasswordField(newPassword, { newPassword = it }, stringResource(R.string.auth_password), ImeAction.Next, tag = "register_password")
            Text(stringResource(R.string.auth_password_rules), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 8.dp))
            PasswordField(confirm, { confirm = it }, stringResource(R.string.auth_confirm_password), ImeAction.Done, { vm.register(name, username, newPassword, confirm) }, "register_confirm")
            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(R.string.auth_create_account), ui.loading,
                name.isNotBlank() && username.isNotBlank() && newPassword.isNotBlank() && confirm.isNotBlank(), "register_button") {
                vm.register(name, username, newPassword, confirm)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text(stringResource(R.string.auth_or), modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { context.findActivity()?.let { vm.google(it) } }, enabled = !ui.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("google_signin_button"), shape = RoundedCornerShape(14.dp),
        ) { Text(stringResource(R.string.sign_in_google), fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.auth_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CompleteProfileScreen(suggestedName: String, ui: AuthUiState, vm: AuthViewModel) {
    var name by rememberSaveable { mutableStateOf(suggestedName.take(50)) }
    var username by rememberSaveable { mutableStateOf(suggestedName.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }.take(20)) }
    AuthFrame {
        Text(stringResource(R.string.auth_complete_profile_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.auth_complete_profile_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
        ErrorCard(ui.error, vm::clearError)
        OutlinedTextField(value = name, onValueChange = { name = it.take(50) }, singleLine = true, label = { Text(stringResource(R.string.auth_display_name)) },
            modifier = Modifier.fillMaxWidth().testTag("profile_name"))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = username, onValueChange = { username = it.trim().lowercase().take(20) }, singleLine = true,
            label = { Text(stringResource(R.string.auth_username)) }, prefix = { Text("@") },
            supportingText = { Text(stringResource(R.string.auth_username_rules)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth().testTag("profile_username"),
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.action_continue), ui.loading, name.isNotBlank() && username.isNotBlank(), "profile_continue") { vm.completeProfile(username, name) }
        TextButton(onClick = vm::signOut, enabled = !ui.loading) { Text(stringResource(R.string.auth_use_other_account)) }
    }
}

@Composable
private fun ProfileUnavailableScreen(ui: AuthUiState, vm: AuthViewModel) {
    AuthFrame {
        Text(stringResource(R.string.auth_profile_unavailable), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        ErrorCard(ui.error, vm::clearError)
        PrimaryButton(stringResource(R.string.action_retry), ui.loading, true, "profile_retry") { vm.retryProfile() }
        TextButton(onClick = vm::signOut, enabled = !ui.loading) { Text(stringResource(R.string.settings_logout)) }
    }
}
