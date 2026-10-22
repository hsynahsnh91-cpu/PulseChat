package com.example.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Earlier versions of an edited message.
 *
 * Policy: each edit stores the replaced text and the time of the edit inside the message itself
 * (`edits` on the message document), so exactly the people who can read the message - the chat's
 * current members, enforced by firestore.rules - can read its history, and nobody else. The history
 * lives as long as the message: "delete for everyone" and disappearing-message expiry remove it with
 * the message, "delete for me" hides it on this phone. Messages can be edited only within
 * [MessagePolicy.EDIT_WINDOW_MS], at most [MAX] times. History text is never written to logs or
 * diagnostics. PulseChat messages are not end-to-end encrypted (the server can read them, under the
 * same rules as the message text), so the history follows the same model as the message itself.
 */
object EditHistory {
    const val MAX = 20

    data class Entry(val text: String, val at: Long)

    /** One version as shown in the history sheet: [at] is when this text was sent or became current. */
    data class Version(val text: String, val at: Long, val current: Boolean, val original: Boolean)

    fun parse(json: String): List<Entry> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text", "")
                Entry(text, o.optLong("at", 0L))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun encode(entries: List<Entry>): String {
        if (entries.isEmpty()) return ""
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("text", it.text).put("at", it.at)) }
        return array.toString()
    }

    /** Local copy after an edit: the replaced [text] is appended (the server does the same with arrayUnion). */
    fun append(json: String, text: String, at: Long): String = encode((parse(json) + Entry(text, at)).takeLast(MAX))

    fun canEditAgain(json: String): Boolean = parse(json).size < MAX

    /**
     * All versions, newest first. The first stored entry is the original text (sent at [createdAt]);
     * every later one became current when the previous edit happened; the current text dates from
     * [editedAt] (or, while that edit is still being sent, from the last entry).
     */
    fun versions(currentText: String, createdAt: Long, editedAt: Long, history: List<Entry>): List<Version> {
        val list = ArrayList<Version>(history.size + 1)
        history.forEachIndexed { i, e ->
            list += Version(e.text, if (i == 0) createdAt else history[i - 1].at, current = false, original = i == 0)
        }
        val currentAt = when {
            history.isEmpty() -> if (editedAt > 0) editedAt else createdAt
            editedAt > 0 -> editedAt
            else -> history.last().at
        }
        list += Version(currentText, currentAt, current = true, original = history.isEmpty() && editedAt <= 0)
        return list.asReversed()
    }
}
