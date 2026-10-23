package com.example.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.example.calls.EchoCancellerMode
import com.example.domain.AutoDownloadSettings
import com.example.domain.MultiAccountPolicy
import com.example.domain.StatusPolicy
import com.example.domain.model.MessageType
import com.example.domain.model.Visibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

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
    /** Voice isolation on the outgoing call audio (automatic; can only be turned off in Diagnostics). */
    val callVoiceIsolation: Boolean = true,
    // In-chat translation: BCP-47 tag of the target language ("" = the app language)
    val translationTarget: String = "",
)

/**
 * Settings storage. Non-sensitive settings live in `pulse_prefs` (eligible for backup);
 * security settings live in `pulse_security`, which backup rules exclude.
 */
class AppPreferences(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("pulse_prefs", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = appContext.getSharedPreferences("pulse_security", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    private val _savedAccounts = MutableStateFlow(loadSavedAccounts())
    val savedAccounts: StateFlow<List<MultiAccountPolicy.StoredAccount>> = _savedAccounts.asStateFlow()

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
            callVoiceIsolation = prefs.getBoolean("call_voice_isolation", d.callVoiceIsolation),
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
    fun setCallVoiceIsolation(value: Boolean) = edit { putBoolean("call_voice_isolation", value) }
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

    fun recentSearches(): List<String> =
        prefs.getString("recent_searches", "")!!.split("\n").filter { it.isNotBlank() }

    fun rememberSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val list = (listOf(q) + recentSearches().filter { it != q }).take(10)
        prefs.edit().putString("recent_searches", list.joinToString("\n")).apply()
    }

    /** Random per-installation ID used to key this device's push token (not a hardware ID). */
    val deviceId: String
        get() = secure.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            secure.edit().putString("device_id", it).apply()
        }

    var registeredPushToken: String?
        get() = secure.getString("push_token", null)
        set(value) { secure.edit().putString("push_token", value).apply() }

    /** How the current session authenticated on this device ("primary_login" or "qr_linked"). */
    var currentAuthMethod: String?
        get() = secure.getString("auth_method", null)
        set(value) {
            secure.edit().apply {
                if (value == null) remove("auth_method") else putString("auth_method", value)
            }.apply()
        }

    /** Pairing session ID that authorized this device when [currentAuthMethod] is "qr_linked". */
    var currentPairingSessionId: String?
        get() = secure.getString("pairing_session_id", null)
        set(value) {
            secure.edit().apply {
                if (value == null) remove("pairing_session_id") else putString("pairing_session_id", value)
            }.apply()
        }

    /** Clears per-account settings on sign-out (device-wide appearance settings are kept). */
    fun clearAccountScoped() {
        prefs.edit()
            .remove("read_receipts").remove("vis_last_seen").remove("vis_photo").remove("vis_about")
            .remove("status_mode").remove("status_list").remove("default_disappearing")
            .remove("ai_consent").remove("ai_smart_replies").remove("recent_searches")
            .apply()
        // App lock belongs to the signed-in account (and signing out is the way out of a forgotten PIN).
        secure.edit().remove("push_token").remove("auth_method").remove("pairing_session_id")
            .putBoolean("app_lock", false).remove("app_lock_pin").remove("app_lock_biometric")
            .remove("app_lock_failures").remove("app_lock_lockout_until")
            .apply()
        _settings.value = load()
    }

    /**
     * Marks a status mention notification as delivered for `(accountUid, statusId)` so real-time
     * listener reconnects or account switches never show duplicate mention notifications.
     * Returns `true` only the first time for a given `(accountUid, statusId)`.
     */
    fun markStatusMentionNotified(accountUid: String, statusId: String): Boolean {
        if (accountUid.isBlank() || statusId.isBlank()) return false
        val key = "status_mention_notified_$accountUid"
        val currentSet = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!currentSet.add(statusId)) return false
        val trimmed = if (currentSet.size > 200) currentSet.toList().takeLast(200).toSet() else currentSet
        prefs.edit().putStringSet(key, trimmed).apply()
        return true
    }

    // ---- Feature 4: Multi-Account metadata & per-account isolated preferences ----

    private fun loadSavedAccounts(): List<MultiAccountPolicy.StoredAccount> {
        val raw = secure.getString("saved_accounts_json", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val uid = o.optString("uid")
                    val username = o.optString("username")
                    if (uid.isBlank() || username.isBlank()) continue
                    add(
                        MultiAccountPolicy.StoredAccount(
                            uid = uid,
                            username = username,
                            displayName = o.optString("displayName", username),
                            uniqueId10 = o.optString("uniqueId10", ""),
                            avatarUrl = o.optString("avatarUrl", "").takeIf { it.isNotBlank() },
                            avatarColorHex = o.optString("avatarColorHex", "#4F46E5"),
                            authMethod = o.optString("authMethod", "primary_login"),
                            addedAt = o.optLong("addedAt", 0L),
                            lastActiveAt = o.optLong("lastActiveAt", 0L),
                            needsReauth = o.optBoolean("needsReauth", false),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistSavedAccounts(list: List<MultiAccountPolicy.StoredAccount>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("uid", a.uid)
                    .put("username", a.username)
                    .put("displayName", a.displayName)
                    .put("uniqueId10", a.uniqueId10)
                    .put("avatarUrl", a.avatarUrl ?: "")
                    .put("avatarColorHex", a.avatarColorHex)
                    .put("authMethod", a.authMethod)
                    .put("addedAt", a.addedAt)
                    .put("lastActiveAt", a.lastActiveAt)
                    .put("needsReauth", a.needsReauth)
            )
        }
        secure.edit().putString("saved_accounts_json", arr.toString()).apply()
        _savedAccounts.value = list
    }

    fun setSavedAccounts(list: List<MultiAccountPolicy.StoredAccount>) {
        persistSavedAccounts(list)
    }

    fun upsertSavedAccount(account: MultiAccountPolicy.StoredAccount, activeUid: String? = account.uid) {
        val updated = MultiAccountPolicy.upsertAccount(_savedAccounts.value, account, activeUid)
        persistSavedAccounts(updated)
        rememberAccount(account.username)
    }

    fun removeSavedAccount(uid: String) {
        val target = _savedAccounts.value.firstOrNull { it.uid == uid }
        val updated = MultiAccountPolicy.removeAccount(_savedAccounts.value, uid).remaining
        persistSavedAccounts(updated)
        target?.let { forgetAccount(it.username) }
        deleteAccountPreferencesSnapshot(uid)
    }

    fun setAccountNeedsReauth(uid: String, needsReauth: Boolean) {
        val updated = _savedAccounts.value.map {
            if (it.uid == uid) it.copy(needsReauth = needsReauth, sessionValid = !needsReauth) else it
        }
        persistSavedAccounts(updated)
    }

    /** Saves the current account-scoped settings into a dedicated per-account preferences file. */
    fun saveAccountPreferencesSnapshot(uid: String) {
        if (uid.isBlank()) return
        val acctPrefs = appContext.getSharedPreferences(MultiAccountPolicy.prefsNameForAccount(uid), Context.MODE_PRIVATE)
        val c = current
        acctPrefs.edit()
            .putBoolean("initialized", true)
            .putBoolean("enter_to_send", c.enterToSend)
            .putBoolean("notif_messages", c.messageNotifications)
            .putBoolean("notif_groups", c.groupNotifications)
            .putBoolean("notif_calls", c.callNotifications)
            .putBoolean("notif_previews", c.notificationPreviews)
            .putBoolean("read_receipts", c.readReceipts)
            .putString("vis_last_seen", c.lastSeenVisibility.name)
            .putString("vis_photo", c.photoVisibility.name)
            .putString("vis_about", c.aboutVisibility.name)
            .putString("status_mode", c.statusAudienceMode.name)
            .putStringSet("status_list", c.statusAudienceList)
            .putLong("default_disappearing", c.defaultDisappearingSeconds)
            .putBoolean("ai_consent", c.aiConsent)
            .putBoolean("ai_smart_replies", c.aiSmartReplies)
            .putString("translation_target", c.translationTarget)
            .putString("recent_searches", prefs.getString("recent_searches", "") ?: "")
            .putString("auth_method", currentAuthMethod)
            .putString("pairing_session_id", currentPairingSessionId)
            .apply()
    }

    fun snapshotAccountPrefs(uid: String) = saveAccountPreferencesSnapshot(uid)

    /** Restores per-account settings from [uid]'s isolated preferences file (or defaults if brand new). */
    fun restoreAccountPreferencesSnapshot(uid: String) {
        if (uid.isBlank()) return
        val acctPrefs = appContext.getSharedPreferences(MultiAccountPolicy.prefsNameForAccount(uid), Context.MODE_PRIVATE)
        val d = AppSettings()
        if (!acctPrefs.getBoolean("initialized", false)) {
            clearAccountScoped()
            return
        }
        prefs.edit()
            .putBoolean("enter_to_send", acctPrefs.getBoolean("enter_to_send", d.enterToSend))
            .putBoolean("notif_messages", acctPrefs.getBoolean("notif_messages", d.messageNotifications))
            .putBoolean("notif_groups", acctPrefs.getBoolean("notif_groups", d.groupNotifications))
            .putBoolean("notif_calls", acctPrefs.getBoolean("notif_calls", d.callNotifications))
            .putBoolean("notif_previews", acctPrefs.getBoolean("notif_previews", d.notificationPreviews))
            .putBoolean("read_receipts", acctPrefs.getBoolean("read_receipts", d.readReceipts))
            .putString("vis_last_seen", acctPrefs.getString("vis_last_seen", d.lastSeenVisibility.name))
            .putString("vis_photo", acctPrefs.getString("vis_photo", d.photoVisibility.name))
            .putString("vis_about", acctPrefs.getString("vis_about", d.aboutVisibility.name))
            .putString("status_mode", acctPrefs.getString("status_mode", d.statusAudienceMode.name))
            .putStringSet("status_list", acctPrefs.getStringSet("status_list", emptySet()) ?: emptySet())
            .putLong("default_disappearing", acctPrefs.getLong("default_disappearing", 0L))
            .putBoolean("ai_consent", acctPrefs.getBoolean("ai_consent", false))
            .putBoolean("ai_smart_replies", acctPrefs.getBoolean("ai_smart_replies", false))
            .putString("translation_target", acctPrefs.getString("translation_target", "") ?: "")
            .putString("recent_searches", acctPrefs.getString("recent_searches", "") ?: "")
            .apply()
        currentAuthMethod = acctPrefs.getString("auth_method", "primary_login")
        currentPairingSessionId = acctPrefs.getString("pairing_session_id", null)
        _settings.value = load()
    }

    fun restoreAccountPrefs(uid: String) = restoreAccountPreferencesSnapshot(uid)

    fun deleteAccountPreferencesSnapshot(uid: String) {
        if (uid.isBlank()) return
        val name = MultiAccountPolicy.prefsNameForAccount(uid)
        appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        prefs.edit().remove("status_mention_notified_$uid").apply()
    }

    fun deleteAccountPrefs(uid: String) = deleteAccountPreferencesSnapshot(uid)
}
