package com.example.domain

import com.example.domain.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGroupAndPollTest {

    @Test
    fun `group @all mention suggestions, token detection, resolution, and permission cooldown`() {
        val candidates = listOf(
            Mentions.Candidate("bob", "Bob"),
            Mentions.Candidate("carol", "Carol"),
        )
        val withAll = Mentions.suggestions(query = "al", candidates = candidates, exclude = "alice", allowAll = true)
        assertEquals(Mentions.ALL_ID, withAll.first().userId)

        val withoutAll = Mentions.suggestions(query = "al", candidates = candidates, exclude = "alice", allowAll = false)
        assertTrue(withoutAll.none { it.userId == Mentions.ALL_ID })

        assertTrue(Mentions.containsAllToken("Hello @all please check"))
        assertTrue(Mentions.containsAllToken("مرحبا @الكل"))
        assertFalse(Mentions.containsAllToken("Hello @allan"))

        val resolvedAllowed = Mentions.resolve("Ping @all and @Bob", candidates, allowAll = true)
        assertEquals(listOf("bob", "all"), resolvedAllowed)
        assertTrue(Mentions.mentionsUser(resolvedAllowed, "carol"))

        val resolvedDenied = Mentions.resolve("Ping @all and @Bob", candidates, allowAll = false)
        assertEquals(listOf("bob"), resolvedDenied)

        val roster = GroupRoster(
            participants = setOf("alice", "bob", "carol"),
            admins = setOf("alice"),
            createdBy = "alice",
            onlyAdminsCanSend = false,
            onlyAdminsCanEditInfo = true,
            onlyAdminsCanMentionAll = true,
            lastMentionAllAt = 10_000L,
        )
        assertTrue(GroupPolicy.canMentionAll(roster, "alice"))
        assertFalse(GroupPolicy.canMentionAll(roster, "bob"))
        assertFalse(GroupPolicy.canMentionAllNow(roster, "alice", 60_000L))
        assertTrue(GroupPolicy.canMentionAllNow(roster, "alice", 10_000L + GroupPolicy.MENTION_ALL_COOLDOWN_MS))

        val eligible = GroupPolicy.eligibleMembersFromGroup(
            sourceParticipants = listOf("alice", "bob", "carol", Ids.AI_ASSISTANT_ID),
            creatorId = "alice",
            blockedIds = setOf("carol"),
        )
        assertEquals(listOf("bob"), eligible)
    }

    @Test
    fun `poll anonymous voter keys, expiration, and edit rules before first vote`() {
        val anonKeyBob = PollPolicy.voterKey(messageId = "m_poll_1", uid = "bob", anonymous = true)
        val anonKeyCarol = PollPolicy.voterKey(messageId = "m_poll_1", uid = "carol", anonymous = true)
        assertEquals(64, anonKeyBob.length)
        assertFalse(anonKeyBob.contains("bob"))
        assertTrue(anonKeyBob != anonKeyCarol)
        assertEquals("bob", PollPolicy.voterKey(messageId = "m_poll_1", uid = "bob", anonymous = false))

        val votes = mapOf(anonKeyBob to 1)
        assertEquals(1, PollPolicy.myVote("m_poll_1", "bob", anonymous = true, votes = votes))
        assertEquals(null, PollPolicy.myVote("m_poll_1", "carol", anonymous = true, votes = votes))

        val exp = PollPolicy.expiresAt(createdAtMs = 1_000L, durationSeconds = 3600L)
        assertEquals(3_601_000L, exp)
        assertFalse(PollPolicy.isExpired(exp, 2_000_000L))
        assertTrue(PollPolicy.isExpired(exp, 3_601_000L))

        assertTrue(
            PollPolicy.canEditPoll(
                isMine = true,
                status = MessageStatus.SENT,
                createdAtMs = 1_000L,
                pollExpiresAtMs = exp,
                totalVotes = 0,
                deletedForEveryone = false,
                nowMs = 60_000L,
            )
        )
        // Once any vote is cast, poll editing is disallowed
        assertFalse(
            PollPolicy.canEditPoll(
                isMine = true,
                status = MessageStatus.SENT,
                createdAtMs = 1_000L,
                pollExpiresAtMs = exp,
                totalVotes = 1,
                deletedForEveryone = false,
                nowMs = 60_000L,
            )
        )
    }
}
