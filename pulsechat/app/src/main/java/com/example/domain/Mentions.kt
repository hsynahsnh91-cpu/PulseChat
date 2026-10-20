package com.example.domain

/**
 * @mentions. The composer inserts "@Display Name" text and remembers the stable user ID of every
 * chosen member; messages carry the list of mentioned IDs (never names), so renames don't break them.
 */
object Mentions {
    data class Candidate(val userId: String, val displayName: String)
    data class Insertion(val text: String, val cursor: Int)

    /** The partial name typed after '@' at the cursor, or null when the cursor is not in a mention. */
    fun activeQuery(text: String, cursor: Int): String? {
        if (cursor < 1 || cursor > text.length) return null
        val before = text.substring(0, cursor)
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !before[at - 1].isWhitespace()) return null // e-mail addresses etc.
        val query = before.substring(at + 1)
        if (query.contains('\n') || query.length > 30) return null
        if (query.isNotEmpty() && query.first().isWhitespace()) return null
        return query
    }

    fun suggestions(query: String, candidates: List<Candidate>, exclude: String?): List<Candidate> =
        candidates.filter { it.userId != exclude && TextTools.matches(query, it.displayName) }
            .sortedBy { it.displayName.lowercase() }
            .take(8)

    /** Replaces the "@partial" before the cursor with "@Display Name ". */
    fun insert(text: String, cursor: Int, displayName: String): Insertion {
        val safeCursor = cursor.coerceIn(0, text.length)
        val at = text.substring(0, safeCursor).lastIndexOf('@')
        if (at < 0) return Insertion(text, safeCursor)
        val token = "@$displayName "
        val newText = text.substring(0, at) + token + text.substring(safeCursor)
        return Insertion(newText, at + token.length)
    }

    /** Keeps only selected IDs whose "@Name" token is still present in the final text. */
    fun resolve(text: String, selected: Collection<Candidate>): List<String> =
        selected.filter { text.contains("@" + it.displayName) }.map { it.userId }.distinct()

    /** Character ranges to highlight for the given mentioned members. */
    fun highlightRanges(text: String, mentioned: Collection<Candidate>): List<IntRange> =
        mentioned.flatMap { c -> TextTools.occurrences(text, "@" + c.displayName) }.sortedBy { it.first }

    fun mentionsUser(mentionedIds: Collection<String>, userId: String) = userId in mentionedIds
}
