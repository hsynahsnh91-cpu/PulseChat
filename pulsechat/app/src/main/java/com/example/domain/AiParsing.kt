package com.example.domain

import com.squareup.moshi.Moshi
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parsing of the JSON returned by the AI features (pure; unit-tested). Model output is untrusted:
 * anything malformed is dropped, and message references are accepted only if they are one of the
 * aliases ("m1", "m2", ...) that were actually sent, so a reference can never point anywhere else.
 */
object AiParsing {
    data class SummaryPoint(val text: String, val refs: List<String>)

    data class ActionItem(val kind: Kind, val title: String, val due: String?, val allDay: Boolean, val refs: List<String>) {
        enum class Kind { TASK, EVENT }
    }

    private val adapter = Moshi.Builder().build().adapter(Any::class.java).lenient()

    /** The first JSON object or array in [raw] (models sometimes wrap JSON in prose or ``` fences). */
    fun jsonPayload(raw: String): String? {
        val text = raw.trim().removePrefix("\uFEFF")
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                open -> depth++
                close -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun parse(raw: String): Any? = jsonPayload(raw)?.let { runCatching { adapter.fromJson(it) }.getOrNull() }

    private fun refsOf(value: Any?, valid: Set<String>): List<String> =
        (value as? List<*>)?.mapNotNull { (it as? String)?.trim()?.lowercase() }?.filter { it in valid }?.distinct() ?: emptyList()

    private fun listUnder(root: Any?, key: String): List<*>? = when (root) {
        is Map<*, *> -> root[key] as? List<*>
        is List<*> -> root
        else -> null
    }

    /** {"points":[{"text":"...","refs":["m3","m7"]}]} */
    fun parseSummary(raw: String, validRefs: Set<String>): List<SummaryPoint> =
        listUnder(parse(raw), "points").orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val text = (m["text"] as? String)?.trim()?.take(500).orEmpty()
            if (text.isEmpty()) null else SummaryPoint(text, refsOf(m["refs"], validRefs))
        }.take(12)

    /** {"items":[{"kind":"task|event","title":"...","due":"2026-10-22T17:00:00+03:00"|null,"allDay":false,"refs":["m2"]}]} */
    fun parseActions(raw: String, validRefs: Set<String>): List<ActionItem> =
        listUnder(parse(raw), "items").orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val title = (m["title"] as? String)?.trim()?.take(200).orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val kind = if ((m["kind"] as? String)?.lowercase() == "event") ActionItem.Kind.EVENT else ActionItem.Kind.TASK
            val due = (m["due"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "null" }
            ActionItem(kind, title, due, m["allDay"] == true, refsOf(m["refs"], validRefs))
        }.take(20)

    /**
     * ISO-8601 date/time to epoch millis. Times without an offset are read in [zone]; a date
     * without a time means [defaultHour]:00 that day. Returns null when unparseable.
     */
    fun parseDateTime(iso: String, zone: TimeZone, defaultHour: Int = 9): Long? {
        val s = iso.trim()
        val withOffset = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX")
        val local = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm")
        for (p in withOffset) parseExact(s, p, null)?.let { return it }
        for (p in local) parseExact(s, p, zone)?.let { return it }
        parseExact(s, "yyyy-MM-dd", zone)?.let { return it + defaultHour * 3_600_000L }
        return null
    }

    private fun parseExact(s: String, pattern: String, zone: TimeZone?): Long? {
        val f = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false; if (zone != null) timeZone = zone }
        val pos = ParsePosition(0)
        val d = runCatching { f.parse(s, pos) }.getOrNull() ?: return null
        return if (pos.index == s.length) d.time else null
    }

    /** Current local time as ISO-8601 with offset, given to the model to resolve "tomorrow at 5". */
    fun nowIso(now: Long, zone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX (EEEE)", Locale.US).apply { timeZone = zone }.format(now)
}
