package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsAndTextTest {
    private val sara = Mentions.Candidate("u_sara", "Sara Ali")
    private val omar = Mentions.Candidate("u_omar", "عمر")

    @Test fun activeQueryDetection() {
        assertEquals("Sa", Mentions.activeQuery("hi @Sa", 6))
        assertEquals("", Mentions.activeQuery("@", 1))
        assertNull(Mentions.activeQuery("mail me a@b.com", 15))
        assertNull(Mentions.activeQuery("no mention", 5))
        assertNull(Mentions.activeQuery("@ space", 7))
    }

    @Test fun insertAndResolveByStableIds() {
        val ins = Mentions.insert("hi @Sa how", 6, sara.displayName)
        assertEquals("hi @Sara Ali  how", ins.text)
        assertEquals(13, ins.cursor)
        assertEquals(listOf("u_sara"), Mentions.resolve(ins.text, listOf(sara, omar)))
        assertTrue(Mentions.resolve("removed the name", listOf(sara)).isEmpty())
        assertEquals(listOf(3..11), Mentions.highlightRanges(ins.text, listOf(sara)))
        assertEquals(listOf(omar), Mentions.suggestions("عم", listOf(sara, omar), exclude = null))
        assertTrue(Mentions.suggestions("", listOf(sara), exclude = "u_sara").isEmpty())
    }

    @Test fun normalizationAndSearch() {
        assertEquals("احمد", TextTools.normalize("أَحْمَد"))
        assertTrue(TextTools.matches("ahmed ali", "Ali", "Ahmed"))
        assertFalse(TextTools.matches("zed", "Ali"))
        assertEquals(listOf(0..1, 6..7), TextTools.occurrences("ab cd ab", "AB"))
    }

    @Test fun linksAndEmoji() {
        val links = TextTools.findLinks("see https://example.com/a?b=1. and www.test.org!")
        assertEquals(listOf("https://example.com/a?b=1", "https://www.test.org"), links.map { it.url })
        assertEquals(4..28, links[0].range)
        assertTrue(TextTools.isEmojiOnly("😀"))
        assertTrue(TextTools.isEmojiOnly("👍🏽❤️"))
        assertFalse(TextTools.isEmojiOnly("hi 😀"))
        assertFalse(TextTools.isEmojiOnly("😀😀😀😀"))
        assertEquals("a b", TextTools.preview("  a \n b "))
        assertEquals(5, TextTools.preview("abcdefgh", 5).length)
    }

    @Test fun validators() {
        assertNull(Validators.usernameError("@Sara_99"))
        assertEquals(FieldError.MUST_START_WITH_LETTER, Validators.usernameError("9sara"))
        assertEquals(FieldError.INVALID_CHARS, Validators.usernameError("sa ra"))
        assertEquals(FieldError.TOO_SHORT, Validators.usernameError("ab"))
        assertEquals(FieldError.WEAK, Validators.passwordError("abcdefgh"))
        assertNull(Validators.passwordError("abcdefg1"))
        assertEquals(FieldError.MISMATCH, Validators.confirmError("a", "b"))
        assertTrue(Validators.isTenDigitId("1234567890"))
        assertFalse(Validators.isTenDigitId("0123456789"))
        assertEquals("sara@demo-proj.firebaseapp.com", Ids.authEmailForUsername(" Sara ", "Demo-Proj"))
        assertEquals("dm_a_b", Ids.directConversationId("b", "a"))
        assertEquals(Ids.directConversationId("x", "y"), Ids.directConversationId("y", "x"))
        assertTrue(Validators.isTenDigitId(Ids.generate10DigitId()))
    }
}
