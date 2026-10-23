package com.example.domain

/**
 * @mentions. The composer inserts "@Display Name" text and remembers the stable user ID of every
 * chosen member; messages carry the list of mentioned IDs (never names), so renames don't break them.
 */
object Mentions {
    const val ALL_ID = "all"
    const val ALL_DISPLAY = "all"
    const val ALL_DISPLAY_AR = "الكل"

    data class Candidate(val userId: String, val displayName: String, val isAll: Boolean = userId == ALL_ID)
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

    fun suggestions(
        query: String,
        candidates: List<Candidate>,
        exclude: String?,
        allowAll: Boolean = false,
    ): List<Candidate> {
        val allOption = if (allowAll && (query.isBlank() || TextTools.matches(query, ALL_DISPLAY, ALL_DISPLAY_AR, "everyone"))) {
            listOf(Candidate(ALL_ID, ALL_DISPLAY, isAll = true))
        } else {
            emptyList()
        }
        val memberMatches = candidates
            .filter { it.userId != exclude && it.userId != ALL_ID && TextTools.matches(query, it.displayName) }
            .sortedBy { it.displayName.lowercase() }
            .take(8)
        return allOption + memberMatches
    }

    /** Replaces the "@partial" before the cursor with "@Display Name ". */
    fun insert(text: String, cursor: Int, displayName: String): Insertion {
        val safeCursor = cursor.coerceIn(0, text.length)
        val at = text.substring(0, safeCursor).lastIndexOf('@')
        if (at < 0) return Insertion(text, safeCursor)
        val token = "@$displayName "
        val newText = text.substring(0, at) + token + text.substring(safeCursor)
        return Insertion(newText, at + token.length)
    }

    /** True if [text] contains a standalone `@all` or `@الكل` token. */
    fun containsAllToken(text: String): Boolean {
        val regex = Regex("(?<=^|\\s)@(all|الكل)(?=\\s|$|[.,!?;:])", RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(text)
    }

    /** Keeps only selected IDs whose "@Name" token is still present in the final text, plus `@all` if allowed. */
    fun resolve(text: String, selected: Collection<Candidate>, allowAll: Boolean = true): List<String> {
        val out = selected
            .filter { c ->
                if (c.userId == ALL_ID) allowAll && containsAllToken(text)
                else text.contains("@" + c.displayName)
            }
            .map { it.userId }
            .toMutableList()
        if (allowAll && containsAllToken(text) && ALL_ID !in out) {
            out.add(0, ALL_ID)
        }
        return out.distinct()
    }

    /** Character ranges to highlight for the given mentioned members (and `@all` when present). */
    fun highlightRanges(text: String, mentioned: Collection<Candidate>, includeAll: Boolean = mentioned.any { it.userId == ALL_ID }): List<IntRange> {
        val memberRanges = mentioned.filter { it.userId != ALL_ID }.flatMap { c -> TextTools.occurrences(text, "@" + c.displayName) }
        val allRanges = if (!includeAll) emptyList() else {
            TextTools.occurrences(text, "@all") + TextTools.occurrences(text, "@الكل")
        }
        return (memberRanges + allRanges).sortedBy { it.first }
    }

    fun mentionsUser(mentionedIds: Collection<String>, userId: String) =
        userId in mentionedIds || ALL_ID in mentionedIds

    fun mentionsAll(mentionedIds: Collection<String>) = ALL_ID in mentionedIds
}
