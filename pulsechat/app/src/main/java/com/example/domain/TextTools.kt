package com.example.domain

/** Search normalisation and lightweight text analysis used by search, previews and rendering. */
object TextTools {
    // Arabic harakat, superscript alef and tatweel are ignored when searching.
    private val ignorable = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
    private val urlRegex = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"]+)""")
    private val trailingPunctuation = charArrayOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '\'', '"', '،', '؟')

    fun normalize(input: String): String = input.lowercase()
        .replace(ignorable, "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .trim()

    /** True when every whitespace-separated query term occurs in at least one of the fields. */
    fun matches(query: String, vararg fields: String?): Boolean {
        val terms = normalize(query).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val haystack = fields.filterNotNull().joinToString(" ") { normalize(it) }
        return terms.all { haystack.contains(it) }
    }

    /** Ranges (end exclusive) of every occurrence of [query] inside [text], for highlighting. */
    fun occurrences(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var index = text.indexOf(q, ignoreCase = true)
        while (index >= 0) {
            result += index until index + q.length
            index = text.indexOf(q, index + q.length, ignoreCase = true)
        }
        return result
    }

    data class Link(val range: IntRange, val url: String)

    fun findLinks(text: String): List<Link> = urlRegex.findAll(text).map { match ->
        var value = match.value
        while (value.isNotEmpty() && value.last() in trailingPunctuation) value = value.dropLast(1)
        val start = match.range.first
        val url = if (value.startsWith("www.", ignoreCase = true)) "https://$value" else value
        Link(start until start + value.length, url)
    }.filter { it.range.last >= it.range.first }.toList()

    /** 1..3 emoji with nothing else: rendered larger, like most messengers do. */
    fun isEmojiOnly(text: String, maxEmoji: Int = 3): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 32) return false
        var i = 0
        var baseCount = 0
        while (i < t.length) {
            val cp = Character.codePointAt(t, i)
            i += Character.charCount(cp)
            when {
                cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F -> Unit // joiners/modifiers
                Character.isWhitespace(cp) -> Unit
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x1F1E6..0x1F1FF ||
                    cp == 0x00A9 || cp == 0x00AE || cp in 0x2190..0x21FF || cp in 0x2300..0x23FF -> baseCount++
                else -> return false
            }
        }
        return baseCount in 1..maxEmoji
    }

    /** Single-line preview, collapsing whitespace and truncating politely. */
    fun preview(text: String, max: Int = 80): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= max) collapsed else collapsed.take(max - 1).trimEnd() + "…"
    }
}
