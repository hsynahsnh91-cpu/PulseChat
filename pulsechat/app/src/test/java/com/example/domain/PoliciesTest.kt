package com.example.domain

import com.example.domain.model.MessageType
import com.example.domain.model.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliciesTest {
    @Test fun presenceHonoursPrivacyAndStaleness() {
        val now = 1_000_000L
        assertEquals(PresenceDisplay.Online, PresencePolicy.resolve(true, now - 10_000, 0, now, readable = true))
        assertEquals(PresenceDisplay.LastSeen(now - 200_000), PresencePolicy.resolve(true, now - 200_000, 5, now, readable = true))
        assertEquals(PresenceDisplay.Hidden, PresencePolicy.resolve(true, now, now, now, readable = false))
        assertEquals(PresenceDisplay.Hidden, PresencePolicy.resolve(true, now, now, now, readable = true, viewerSharesLastSeen = false))
        assertTrue(PresencePolicy.canSee(Visibility.CONTACTS, false, true))
        assertFalse(PresencePolicy.canSee(Visibility.CONTACTS, false, false))
        assertFalse(PresencePolicy.canSee(Visibility.NOBODY, false, true))
        assertTrue(PresencePolicy.canSee(Visibility.NOBODY, true, false))
        assertTrue(PresencePolicy.isTypingActive(now - 1000, now))
        assertFalse(PresencePolicy.isTypingActive(now - 10_000, now))
        assertEquals(Visibility.CONTACTS, Visibility.parse("contacts"))
        assertEquals(Visibility.EVERYONE, Visibility.parse(null))
    }

    @Test fun disappearing() {
        assertEquals(0L, DisappearingPolicy.expiresAt(1000, 0))
        assertEquals(1000L + 86_400_000L, DisappearingPolicy.expiresAt(1000, 86_400))
        assertTrue(DisappearingPolicy.isExpired(5, 5))
        assertFalse(DisappearingPolicy.isExpired(0, 5))
        assertFalse(DisappearingPolicy.isValidOption(123))
    }

    @Test fun statusGroupingAndAudience() {
        val now = 100_000L
        val items = listOf(
            StatusPolicy.Item("1", "a", 10, now + 1, viewed = true),
            StatusPolicy.Item("2", "b", 20, now + 1, viewed = false),
            StatusPolicy.Item("3", "a", 30, now + 1, viewed = true),
            StatusPolicy.Item("4", "c", 40, now - 1, viewed = false), // expired
            StatusPolicy.Item("5", "me", 50, now + 1, viewed = false),
        )
        val groups = StatusPolicy.group(items, now, excludeOwner = "me")
        assertEquals(listOf("b", "a"), groups.map { it.ownerId })
        assertEquals(0, StatusPolicy.startIndex(groups[1]))
        assertEquals(setOf("x"), StatusPolicy.audience(StatusPolicy.AudienceMode.CONTACTS_EXCEPT, setOf("x", "y"), setOf("y")))
        assertEquals(setOf("z"), StatusPolicy.audience(StatusPolicy.AudienceMode.ONLY_SHARE_WITH, setOf("x"), setOf("z")))
    }

    @Test fun autoDownloadAndLimits() {
        val s = AutoDownloadSettings()
        assertTrue(AutoDownloadPolicy.shouldAutoDownload(MessageType.VIDEO, NetworkKind.WIFI, s, 50_000_000))
        assertFalse(AutoDownloadPolicy.shouldAutoDownload(MessageType.VIDEO, NetworkKind.CELLULAR, s, 1000))
        assertFalse(AutoDownloadPolicy.shouldAutoDownload(MessageType.IMAGE, NetworkKind.CELLULAR, s, 20_000_000))
        assertFalse(AutoDownloadPolicy.shouldAutoDownload(MessageType.IMAGE, NetworkKind.ROAMING, s, 10))
        assertFalse(AutoDownloadPolicy.shouldAutoDownload(MessageType.IMAGE, NetworkKind.NONE, s, 10))
        assertTrue(MediaLimits.isAllowedSize(MessageType.IMAGE, 1000))
        assertFalse(MediaLimits.isAllowedSize(MessageType.IMAGE, MediaLimits.MAX_IMAGE_BYTES + 1))
        assertEquals("1.5 MB", MediaLimits.humanSize(1_572_864))
        assertEquals("1:05", DurationFormat.clock(65_000))
        assertEquals("1:00:05", DurationFormat.clock(3_605_000))
    }
}
