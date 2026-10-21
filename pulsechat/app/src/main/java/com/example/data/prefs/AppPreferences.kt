package com.example.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.calls.EchoCancellerMode
import com.example.domain.AutoDownloadSettings
import com.example.domain.StatusPolicy
import com.example.domain.model.MessageType
import com.example.domain.model.Visibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage(val tag: String?) { SYSTEM(null), ENGLISH("en"), ARABIC("ar") }

/** Snapshot of all user-facing settings, observed by Compose. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val enterToSend: Boolean = false,
    val messageNotifications: Boolean = true,
    val groupNotifications: Boolean = true,
    val callNotifications: Boolean = true,
    val notificationPreviews: Boolean = true,
    val autoDownload: AutoDownloadSettings = AutoDownloadSettings(),
    val readReceipts: Boolean = true,
    val lastSeenVisibility: Visibility = Visibility.EVERYONE,
    val photoVisibility: Visibility = Visibility.EVERYONE,
    val aboutVisibility: Visibility = Visibility.EVERYONE,
    val statusAudienceMode: StatusPolicy.AudienceMode = StatusPolicy.AudienceMode.MY_CONTACTS,
    val statusAudienceList: Set<String> = emptySet(),
    val defaultDisappearingSeconds: Long = 0L,
    val aiConsent: Boolean = false,
    val aiSmartReplies: Boolean = false,
    val appLockEnabled: Boolean = false,
    val appLockTimeoutMs: Long = 0L,
    val appLockHasPin: Boolean = false,
    val appLockBiometric: Boolean = false,
    // Calls (Settings › Diagnostics › Call audio)
    val callEchoMode: EchoCancellerMode = EchoCancellerMode.AUTO,
    val callAutoGain: Boolean = false,
    val callShowDetails: Boolean = false,
    // In-chat translation: BCP-47 tag of the target language ("" = the app language)
    val translationTarget: String = "",
)

/**
 * Settings storage. Non-sensitive settings live in `pulse_prefs` (eligible for backup);
 * security settings live in `pulse_security`, which backup rules exclude.
 */
class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pulse_prefs", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = context.getSharedPreferences("pulse_security", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    private fun types(key: String, default: Set<MessageType>): Set<MessageType> {
        val raw = prefs.getStringSet(key, null) ?: return default
        return raw.mapNotNull { name -> MessageType.entries.firstOrNull { it.name == name } }.toSet()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme", d.themeMode.name)!!) }.getOrDefault(d.themeMode),
            language = runCatching { AppLanguage.valueOf(prefs.getString("language", d.language.name)!!) }.getOrDefault(d.language),
            enterToSend = prefs.getBoolean("enter_to_send", d.enterToSend),
            messageNotifications = prefs.getBoolean("notif_messages", d.messageNotifications),
            groupNotifications = prefs.getBoolean("notif_groups", d.groupNotifications),
            callNotifications = prefs.getBoolean("notif_calls", d.callNotifications),
            notificationPreviews = prefs.getBoolean("notif_previews", d.notificationPreviews),
            autoDownload = AutoDownloadSettings(
                wifi = types("auto_wifi", d.autoDownload.wifi),
                cellular = types("auto_cellular", d.autoDownload.cellular),
                roaming = types("auto_roaming", d.autoDownload.roaming),
            ),
            readReceipts = prefs.getBoolean("read_receipts", d.readReceipts),
            lastSeenVisibility = Visibility.parse(prefs.getString("vis_last_seen", null)),
            photoVisibility = Visibility.parse(prefs.getString("vis_photo", null)),
            aboutVisibility = Visibility.parse(prefs.getString("vis_about", null)),
            statusAudienceMode = runCatching { StatusPolicy.AudienceMode.valueOf(prefs.getString("status_mode", d.statusAudienceMode.name)!!) }
                .getOrDefault(d.statusAudienceMode),
            statusAudienceList = prefs.getStringSet("status_list", emptySet()) ?: emptySet(),
            defaultDisappearingSeconds = prefs.getLong("default_disappearing", 0L),
            aiConsent = prefs.getBoolean("ai_consent", false),
            aiSmartReplies = prefs.getBoolean("ai_smart_replies", false),
            appLockEnabled = secure.getBoolean("app_lock", false),
            appLockTimeoutMs = secure.getLong("app_lock_timeout", d.appLockTimeoutMs),
            appLockHasPin = secure.getString("app_lock_pin", null) != null,
            appLockBiometric = secure.getBoolean("app_lock_biometric", false),
            callEchoMode = runCatching { EchoCancellerMode.valueOf(prefs.getString("call_echo_mode", d.callEchoMode.name)!!) }.getOrDefault(d.callEchoMode),
            callAutoGain = prefs.getBoolean("call_auto_gain", d.callAutoGain),
            callShowDetails = prefs.getBoolean("call_show_details", d.callShowDetails),
            translationTarget = prefs.getString("translation_target", "") ?: "",
        )
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _settings.value = load()
    }

    private fun editSecure(block: SharedPreferences.Editor.() -> Unit) {
        secure.edit().apply(block).apply()
        _settings.value = load()
    }

    fun setThemeMode(mode: ThemeMode) = edit { putString("theme", mode.name) }
    fun setLanguage(language: AppLanguage) = edit { putString("language", language.name) }
    fun setEnterToSend(value: Boolean) = edit { putBoolean("enter_to_send", value) }
    fun setMessageNotifications(value: Boolean) = edit { putBoolean("notif_messages", value) }
    fun setGroupNotifications(value: Boolean) = edit { putBoolean("notif_groups", value) }
    fun setCallNotifications(value: Boolean) = edit { putBoolean("notif_calls", value) }
    fun setNotificationPreviews(value: Boolean) = edit { putBoolean("notif_previews", value) }
    fun setAutoDownload(settings: AutoDownloadSettings) = edit {
        putStringSet("auto_wifi", settings.wifi.map { it.name }.toSet())
        putStringSet("auto_cellular", settings.cellular.map { it.name }.toSet())
        putStringSet("auto_roaming", settings.roaming.map { it.name }.toSet())
    }
    fun setReadReceipts(value: Boolean) = edit { putBoolean("read_receipts", value) }
    fun setVisibilities(lastSeen: Visibility, photo: Visibility, about: Visibility) = edit {
        putString("vis_last_seen", lastSeen.name); putString("vis_photo", photo.name); putString("vis_about", about.name)
    }
    fun setStatusAudience(mode: StatusPolicy.AudienceMode, list: Set<String>) = edit {
        putString("status_mode", mode.name); putStringSet("status_list", list)
    }
    fun setDefaultDisappearing(seconds: Long) = edit { putLong("default_disappearing", seconds) }
    fun setAiConsent(value: Boolean) = edit {
        putBoolean("ai_consent", value)
        if (!value) putBoolean("ai_smart_replies", false)
    }
    fun setAiSmartReplies(value: Boolean) = edit { putBoolean("ai_smart_replies", value) }
    fun setAppLock(enabled: Boolean, timeoutMs: Long) = editSecure { putBoolean("app_lock", enabled); putLong("app_lock_timeout", timeoutMs) }
    fun setAppLockBiometric(enabled: Boolean) = editSecure { putBoolean("app_lock_biometric", enabled) }

    /** Salted PIN hash record written by AppLockManager (never the PIN itself). Excluded from backups. */
    val appLockPinRecord: String? get() = secure.getString("app_lock_pin", null)
    fun setAppLockPinRecord(record: String?) = editSecure { if (record == null) remove("app_lock_pin") else putString("app_lock_pin", record) }

    /** Failed PIN attempts and the lockout they caused (persisted, so restarting the app doesn't reset them). */
    var appLockFailures: Int
        get() = secure.getInt("app_lock_failures", 0)
        set(value) { secure.edit().putInt("app_lock_failures", value).apply() }
    var appLockLockoutUntil: Long
        get() = secure.getLong("app_lock_lockout_until", 0L)
        set(value) { secure.edit().putLong("app_lock_lockout_until", value).apply() }

    /** Turns app lock off and forgets the PIN (sign-out, "forgot PIN"). */
    fun resetAppLock() = editSecure {
        putBoolean("app_lock", false); remove("app_lock_pin"); remove("app_lock_biometric")
        remove("app_lock_failures"); remove("app_lock_lockout_until")
    }

    fun setCallEchoMode(mode: EchoCancellerMode) = edit { putString("call_echo_mode", mode.name) }
    fun setCallAutoGain(value: Boolean) = edit { putBoolean("call_auto_gain", value) }
    fun setCallShowDetails(value: Boolean) = edit { putBoolean("call_show_details", value) }
    fun setTranslationTarget(tag: String) = edit { putString("translation_target", tag) }

    /** Summary of the last call for Settings › Diagnostics (JSON, no personal data). */
    var lastCallSummary: String?
        get() = prefs.getString("last_call_summary", null)
        set(value) { prefs.edit().putString("last_call_summary", value).apply() }

    /** When the realtime sync last received data from the server (Settings › Diagnostics). */
    var lastServerSyncAt: Long
        get() = prefs.getLong("last_server_sync_at", 0L)
        set(value) { prefs.edit().putLong("last_server_sync_at", value).apply() }

    /** Until when uploads skip Cloud Storage because the project has no usable bucket (see MediaRepository). */
    var storageUnavailableUntil: Long
        get() = prefs.getLong("storage_unavailable_until", 0L)
        set(value) { prefs.edit().putLong("storage_unavailable_until", value).apply() }

    // ---- Account conveniences (no secrets) ----
    fun recentAccounts(): List<String> = prefs.getString("recent_accounts", "")!!.split("\n").filter { it.isNotBlank() }
    fun rememberAccount(username: String) {
        if (username.isBlank()) return
        val list = (listOf(username) + recentAccounts().filter { it != username }).take(5)
        prefs.edit().putString("recent_accounts", list.joinToString("\n")).apply()
    }
    fun forgetAccount(username: String) {
        prefs.edit().putString("recent_accounts", recentAccounts().filter { it != username }.joinToString("\n")).apply()
    }

    /** Random per-installation ID used to key this device's push token (not a hardware ID). */
    val deviceId: String
        get() = secure.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            secure.edit().putString("device_id", it).apply()
        }

    var registeredPushToken: String?
        get() = secure.getString("push_token", null)
        set(value) { secure.edit().putString("push_token", value).apply() }

    /** Clears per-account settings on sign-out (device-wide appearance settings are kept). */
    fun clearAccountScoped() {
        prefs.edit()
            .remove("read_receipts").remove("vis_last_seen").remove("vis_photo").remove("vis_about")
            .remove("status_mode").remove("status_list").remove("default_disappearing")
            .remove("ai_consent").remove("ai_smart_replies")
            .apply()
        // App lock belongs to the signed-in account (and signing out is the way out of a forgotten PIN).
        secure.edit().remove("push_token")
            .putBoolean("app_lock", false).remove("app_lock_pin").remove("app_lock_biometric")
            .remove("app_lock_failures").remove("app_lock_lockout_until")
            .apply()
        _settings.value = load()
    }
}
