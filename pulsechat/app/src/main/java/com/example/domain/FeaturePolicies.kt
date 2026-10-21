package com.example.domain

import java.util.Calendar
import java.util.TimeZone

/** Rules for scheduled messages (pure; unit-tested). */
object ScheduledPolicy {
    /** A scheduled time must be at least this far ahead (otherwise "send" is the right action). */
    const val MIN_LEAD_MS = 60_000L
    const val MAX_AHEAD_MS = 365L * 24 * 3_600_000L
    /** A claimed message whose attempt shows no progress for this long belongs to a dead process. */
    const val STALE_SENDING_MS = 3 * 60_000L
    /** Early runs (e.g. after the clock was set back) are re-queued instead of sent early. */
    const val EARLY_TOLERANCE_MS = 30_000L
    /** Transient failures are retried with back-off; after this many attempts the message is marked failed. */
    const val MAX_ATTEMPTS = 20

    enum class Error { EMPTY, TOO_LONG, TOO_SOON, TOO_FAR }

    fun validate(text: String, at: Long, now: Long): Error? = when {
        text.isBlank() -> Error.EMPTY
        text.trim().length > MessagePolicy.MAX_TEXT_LENGTH -> Error.TOO_LONG
        at < now + MIN_LEAD_MS -> Error.TOO_SOON
        at > now + MAX_AHEAD_MS -> Error.TOO_FAR
        else -> null
    }

    /** True when a due message should be sent now; false when it must wait (run came too early). */
    fun isDue(scheduledAt: Long, now: Long): Boolean = scheduledAt <= now + EARLY_TOLERANCE_MS
}

/** Quick choices for reminders, computed in the device's time zone (pure; unit-tested). */
object ReminderTimes {
    enum class Preset { IN_1_HOUR, IN_3_HOURS, THIS_EVENING, TOMORROW_MORNING, NEXT_WEEK }

    fun presets(now: Long, zone: TimeZone = TimeZone.getDefault()): List<Pair<Preset, Long>> =
        Preset.entries.mapNotNull { p -> at(p, now, zone)?.let { p to it } }

    /** Null when the preset makes no sense at this time of day (e.g. "this evening" at 22:00). */
    fun at(preset: Preset, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val c = Calendar.getInstance(zone).apply { timeInMillis = now }
        fun dayAt(hour: Int, plusDays: Int): Long = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, plusDays)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return when (preset) {
            Preset.IN_1_HOUR -> now + 3_600_000L
            Preset.IN_3_HOURS -> now + 3 * 3_600_000L
            Preset.THIS_EVENING -> if (c.get(Calendar.HOUR_OF_DAY) < 17) dayAt(19, 0) else null
            Preset.TOMORROW_MORNING -> dayAt(9, 1)
            Preset.NEXT_WEEK -> {
                // Next Monday 09:00 (at least one day ahead).
                val daysToMonday = ((Calendar.MONDAY - c.get(Calendar.DAY_OF_WEEK) + 7) % 7).let { if (it == 0) 7 else it }
                dayAt(9, daysToMonday)
            }
        }
    }

    /** Combines a picked date (any time on that day, UTC midnight from a date picker) with hour/minute. */
    fun combine(dateUtcMidnight: Long, hour: Int, minute: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateUtcMidnight }
        return Calendar.getInstance(zone).apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
        }.timeInMillis
    }
}

/** Finds web links in message text (pure; unit-tested). */
object LinkExtractor {
    private val pattern = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"'()\[\]{}]+(?:\([^\s<>"'()]*\)[^\s<>"'()\[\]{}]*)*)""")
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', '،', '؛', '؟', '…')

    data class Link(val url: String, val host: String)

    fun extract(text: String): List<Link> = pattern.findAll(text).mapNotNull { m ->
        val raw = m.value.trimEnd(*trailing)
        if (raw.length < 5) return@mapNotNull null
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) null else Link(url, host.removePrefix("www."))
    }.distinctBy { it.url }.toList()
}
