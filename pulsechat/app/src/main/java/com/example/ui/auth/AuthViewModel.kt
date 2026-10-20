package com.example.ui.auth

import android.app.Activity
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.prefs.AppPreferences
import com.example.data.repository.AuthError
import com.example.data.repository.AuthException
import com.example.data.repository.AuthRepository
import com.example.data.repository.SessionState
import com.example.ui.common.authErrorText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val recentAccounts: List<String> = emptyList(),
    val legacyAccounts: List<String> = emptyList(),
)

class AuthViewModel(private val app: Application, private val auth: AuthRepository, private val prefs: AppPreferences) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUiState(recentAccounts = prefs.recentAccounts()))
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()
    val session: StateFlow<SessionState> = auth.session

    init {
        viewModelScope.launch { _ui.value = _ui.value.copy(legacyAccounts = auth.legacyAccountUsernames()) }
    }

    private fun launchAuth(block: suspend () -> Unit) {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                block()
                _ui.value = _ui.value.copy(loading = false, error = null)
            } catch (e: Exception) {
                val mapped = auth.map(e)
                val message = if (mapped.error == AuthError.CANCELLED) null else authErrorText(app, mapped.error, (e as? AuthException)?.field)
                _ui.value = _ui.value.copy(loading = false, error = message)
            }
        }
    }

    fun clearError() { _ui.value = _ui.value.copy(error = null) }
    fun signIn(identifier: String, password: String) = launchAuth { auth.signInWithPassword(identifier, password) }
    fun register(name: String, username: String, password: String, confirm: String) = launchAuth { auth.register(name, username, password, confirm) }
    fun google(activity: Activity) = launchAuth { auth.signInWithGoogle(activity) }
    fun completeProfile(username: String, name: String) = launchAuth { auth.completeProfile(username, name) }
    fun retryProfile() = launchAuth { auth.retryProfileLoad() }
    fun signOut() = launchAuth { auth.signOut() }
    fun forgetRecent(username: String) {
        prefs.forgetAccount(username)
        _ui.value = _ui.value.copy(recentAccounts = prefs.recentAccounts())
    }
}
