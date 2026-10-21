package com.example.ui.common

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import com.example.R
import com.example.domain.PresenceDisplay
import java.util.Calendar
import java.util.Date

/** Locale-aware time formatting for chat rows, day headers and presence. */
object Formatters {
    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }

    fun isToday(t: Long) = DateUtils.isToday(t)
    fun isYesterday(t: Long) = DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS)

    // Building a time formatter is costly and it is needed for every visible message, so one is kept
    // per locale + 12/24-hour setting.
    private var timeFormatKey: Pair<java.util.Locale, Boolean>? = null
    private var timeFormat: java.text.DateFormat? = null

    fun time(context: Context, t: Long): String {
        val key = context.resources.configuration.locales[0] to DateFormat.is24HourFormat(context)
        val format = synchronized(this) {
            timeFormat?.takeIf { timeFormatKey == key } ?: DateFormat.getTimeFormat(context).also { timeFormat = it; timeFormatKey = key }
        }
        return synchronized(format) { format.format(Date(t)) }
    }

    fun chatListTime(context: Context, t: Long): String {
        if (t <= 0) return ""
        val now = System.currentTimeMillis()
        return when {
            isToday(t) -> time(context, t)
            isYesterday(t) -> context.getString(R.string.yesterday)
            now - t < 6 * DateUtils.DAY_IN_MILLIS -> DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_WEEKDAY)
            else -> DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
        }
    }

    fun dayHeader(context: Context, t: Long): String = when {
        isToday(t) -> context.getString(R.string.today)
        isYesterday(t) -> context.getString(R.string.yesterday)
        cal(t).get(Calendar.YEAR) == cal(System.currentTimeMillis()).get(Calendar.YEAR) ->
            DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_WEEKDAY)
        else -> DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
    }

    fun dateTime(context: Context, t: Long): String =
        DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)

    fun lastSeen(context: Context, presence: PresenceDisplay): String? = when (presence) {
        PresenceDisplay.Online -> context.getString(R.string.presence_online)
        PresenceDisplay.Hidden -> null
        is PresenceDisplay.LastSeen -> {
            val t = presence.at
            when {
                isToday(t) -> context.getString(R.string.presence_last_seen_today, time(context, t))
                isYesterday(t) -> context.getString(R.string.presence_last_seen_yesterday, time(context, t))
                else -> context.getString(R.string.presence_last_seen_date, DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE))
            }
        }
    }

    fun disappearingLabel(context: Context, seconds: Long): String = when (seconds) {
        0L -> context.getString(R.string.disappearing_off)
        86_400L -> context.getString(R.string.disappearing_24h)
        604_800L -> context.getString(R.string.disappearing_7d)
        7_776_000L -> context.getString(R.string.disappearing_90d)
        else -> context.getString(R.string.disappearing_off)
    }
}
