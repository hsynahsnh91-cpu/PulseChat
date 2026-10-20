package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

class AutoReplyManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("pulsechat_autoreply", Context.MODE_PRIVATE)

    companion object {
        const val CONDITION_ALL = "ALL"
        const val CONDITION_NON_CONTACTS = "NON_CONTACTS"
        const val CONDITION_CONTACTS_ONLY = "CONTACTS_ONLY"

        val PRESET_TEMPLATES = listOf(
            "مرحباً! أنا غير متاح حالياً، سأقوم بالرد عليك بأقرب وقت ممكن.",
            "أنا في اجتماع حالياً، سأتواصل معك فور انتهائه.",
            "أقود السيارة بأمان الآن 🚗 سأعاود الاتصال بك لاحقاً.",
            "في إجازة حالياً، سأقوم بالرد فور عودتي بإذن الله.",
            "مشغول بالعمل الآن، أرجو ترك رسالتك وسأجيبك قريباً."
        )
    }

    private val _isEnabled = MutableStateFlow(prefs.getBoolean("auto_reply_enabled", false))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _customMessage = MutableStateFlow(
        prefs.getString("auto_reply_message", PRESET_TEMPLATES.first()) ?: PRESET_TEMPLATES.first()
    )
    val customMessage: StateFlow<String> = _customMessage.asStateFlow()

    private val _isAiEnabled = MutableStateFlow(prefs.getBoolean("auto_reply_ai_enabled", false))
    val isAiEnabled: StateFlow<Boolean> = _isAiEnabled.asStateFlow()

    private val _condition = MutableStateFlow(prefs.getString("auto_reply_condition", CONDITION_ALL) ?: CONDITION_ALL)
    val condition: StateFlow<String> = _condition.asStateFlow()

    private val _isScheduleOnly = MutableStateFlow(prefs.getBoolean("auto_reply_schedule_only", false))
    val isScheduleOnly: StateFlow<Boolean> = _isScheduleOnly.asStateFlow()

    // 3 minutes cooldown per contact to prevent infinite reply loops
    private val lastRepliedMap = mutableMapOf<String, Long>()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_reply_enabled", enabled).apply()
        _isEnabled.value = enabled
    }

    fun setCustomMessage(msg: String) {
        val trimmed = msg.trim()
        val text = if (trimmed.isNotEmpty()) trimmed else PRESET_TEMPLATES.first()
        prefs.edit().putString("auto_reply_message", text).apply()
        _customMessage.value = text
    }

    fun setAiEnabled(aiEnabled: Boolean) {
        prefs.edit().putBoolean("auto_reply_ai_enabled", aiEnabled).apply()
        _isAiEnabled.value = aiEnabled
    }

    fun setCondition(condition: String) {
        prefs.edit().putString("auto_reply_condition", condition).apply()
        _condition.value = condition
    }

    fun setScheduleOnly(scheduleOnly: Boolean) {
        prefs.edit().putBoolean("auto_reply_schedule_only", scheduleOnly).apply()
        _isScheduleOnly.value = scheduleOnly
    }

    fun shouldAutoReplyTo(contactId: String, isContactInAddressBook: Boolean = true): Boolean {
        if (!_isEnabled.value) return false

        // Check schedule if enabled (e.g. outside 9 AM - 6 PM or weekend)
        if (_isScheduleOnly.value) {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val isWorkingHours = (dayOfWeek != Calendar.FRIDAY && dayOfWeek != Calendar.SATURDAY) && (hour in 9..17)
            if (isWorkingHours) return false
        }

        // Check condition filters
        when (_condition.value) {
            CONDITION_NON_CONTACTS -> if (isContactInAddressBook) return false
            CONDITION_CONTACTS_ONLY -> if (!isContactInAddressBook) return false
        }

        val now = System.currentTimeMillis()
        val lastTime = lastRepliedMap[contactId] ?: 0L
        val cooldownMillis = 3 * 60 * 1000L // 3 minutes
        return (now - lastTime) > cooldownMillis
    }

    fun recordAutoReplySent(contactId: String) {
        lastRepliedMap[contactId] = System.currentTimeMillis()
    }
}
