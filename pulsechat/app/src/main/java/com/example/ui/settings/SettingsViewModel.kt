package com.example.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.R
import com.example.data.model.ContactRow
import com.example.data.model.MediaTypeCount
import com.example.data.model.StorageRow
import com.example.data.model.User
import com.example.data.prefs.AppLanguage
import com.example.data.prefs.AppSettings
import com.example.data.prefs.ThemeMode
import com.example.data.repository.ProfileUpdateException
import com.example.domain.AutoDownloadSettings
import com.example.domain.StatusPolicy
import com.example.domain.model.Visibility
import com.example.ui.common.fieldErrorText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val me = c.authRepository.currentUid.orEmpty()
    val user: StateFlow<User?> = c.userRepository.observeUser(me).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<AppSettings> = c.prefs.settings
    val blockedUsers: StateFlow<List<User>> = c.userRepository.observeBlockedUsers().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val contacts: StateFlow<List<ContactRow>> = c.userRepository.observeContacts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val storageByChat: StateFlow<List<StorageRow>> = c.db.messageDao().observeStorageByConversation().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val storageByType: StateFlow<List<MediaTypeCount>> = c.db.messageDao().observeStorageByType().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totalMediaBytes = MutableStateFlow(0L)
    val freeBytes = MutableStateFlow(0L)
    val busy = MutableStateFlow(false)
    val autoReply = c.autoReply
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun refreshStorage() = viewModelScope.launch {
        totalMediaBytes.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.media.totalLocalBytes() }
        freeBytes.value = c.media.freeBytes()
    }

    private fun act(success: Int? = null, block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try {
            block()
            success?.let { _messages.tryEmit(c.app.getString(it)) }
        } catch (e: ProfileUpdateException) {
            _messages.tryEmit(fieldErrorText(c.app, e.field))
        } catch (e: Exception) {
            _messages.tryEmit(c.app.getString(R.string.error_update_failed))
        } finally {
            busy.value = false
        }
    }

    fun updateName(name: String) = act(R.string.toast_saved) { c.userRepository.updateDisplayName(name) }
    fun updateAbout(about: String) = act(R.string.toast_saved) { c.userRepository.updateAbout(about) }
    fun updatePhoto(uri: Uri) = act(R.string.toast_saved) { c.userRepository.updatePhoto(uri) }
    fun removePhoto() = act { c.userRepository.removePhoto() }

    fun setVisibility(lastSeen: Visibility? = null, photo: Visibility? = null, about: Visibility? = null) = act {
        val s = settings.value
        c.userRepository.updateVisibility(lastSeen ?: s.lastSeenVisibility, photo ?: s.photoVisibility, about ?: s.aboutVisibility)
    }

    fun setReadReceipts(enabled: Boolean) = c.userRepository.setReadReceipts(enabled)
    fun setNotificationPreviews(enabled: Boolean) = c.userRepository.setNotificationPreviews(enabled)
    fun setStatusAudience(mode: StatusPolicy.AudienceMode, list: Set<String>) = c.prefs.setStatusAudience(mode, list)
    fun setDefaultDisappearing(seconds: Long) = c.prefs.setDefaultDisappearing(seconds)
    fun setMessageNotifications(v: Boolean) = c.prefs.setMessageNotifications(v)
    fun setGroupNotifications(v: Boolean) = c.prefs.setGroupNotifications(v)
    fun setCallNotifications(v: Boolean) = c.prefs.setCallNotifications(v)
    fun setTheme(mode: ThemeMode) = c.prefs.setThemeMode(mode)
    fun setLanguage(language: AppLanguage) = c.prefs.setLanguage(language)
    fun setEnterToSend(v: Boolean) = c.prefs.setEnterToSend(v)
    fun setAutoDownload(s: AutoDownloadSettings) = c.prefs.setAutoDownload(s)
    fun setAiConsent(v: Boolean) = c.prefs.setAiConsent(v)
    fun setAppLock(enabled: Boolean, timeoutMs: Long) {
        c.prefs.setAppLock(enabled, timeoutMs)
        if (!enabled) c.appLock.disableLock()
    }

    /** Turns app lock on with a new PIN. */
    fun enableAppLock(pin: String) = act(R.string.app_lock_on) { c.appLock.enable(pin, settings.value.appLockTimeoutMs) }

    /** Turns app lock off (after the current PIN was confirmed) and forgets the PIN. */
    fun disableAppLock() = act(R.string.app_lock_off) { c.appLock.disable() }

    fun changeAppLockPin(pin: String) = act(R.string.toast_saved) { c.appLock.changePin(pin) }
    fun setAppLockBiometric(enabled: Boolean) = c.prefs.setAppLockBiometric(enabled)
    suspend fun checkAppLockPin(pin: String) = c.appLock.checkPin(pin)
    fun biometricAvailable(): Boolean = c.appLock.biometricAvailable(c.app)
    fun unblock(uid: String) = act { c.userRepository.unblock(uid) }
    fun clearMedia(conversationId: String) = act(R.string.toast_media_cleared) { c.chatRepository.clearMediaFor(conversationId); refreshStorage() }
    fun signOut() = act { c.authRepository.signOut() }
}
