package com.example.domain

import com.example.data.model.ChatNotificationSettings
import com.example.domain.LiveLocationPolicy.Phase
import com.example.domain.NotificationPolicy.Content
import com.example.domain.NotificationPolicy.MuteOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Per-chat notifications, edit history and live-location rules (Robolectric for org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class Phase2PoliciesTest {
    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    // ------------------------------------------------------------------ notifications

    @Test
    fun muteChoicesEndAtTheRightTime() {
        assertEquals(now + hour, NotificationPolicy.mutedUntil(MuteOption.ONE_HOUR, now))
        assertEquals(now + 8 * hour, NotificationPolicy.mutedUntil(MuteOption.EIGHT_HOURS, now))
        assertEquals(now + 24 * hour, NotificationPolicy.mutedUntil(MuteOption.ONE_DAY, now))
        assertEquals(ChatNotificationSettings.MUTED_FOREVER, NotificationPolicy.mutedUntil(MuteOption.UNTIL_TURNED_ON, now))
        assertEquals(listOf(MuteOption.ONE_HOUR, MuteOption.EIGHT_HOURS, MuteOption.ONE_DAY, MuteOption.UNTIL_TURNED_ON), MuteOption.entries.toList())
    }

    @Test
    fun aMuteExpiresByItselfAndMentionsStillReachYouInGroups() {
        val until = now + hour
        assertFalse(NotificationPolicy.shouldNotify(until, now, isGroup = false, mentionsMe = false))
        assertFalse(NotificationPolicy.shouldNotify(until, now, isGroup = false, mentionsMe = true))
        assertTrue(NotificationPolicy.shouldNotify(until, now, isGroup = true, mentionsMe = true))
        assertFalse(NotificationPolicy.shouldNotify(until, now, isGroup = true, mentionsMe = false))
        assertTrue(NotificationPolicy.shouldNotify(until, until + 1, isGroup = false, mentionsMe = false))
        assertTrue(NotificationPolicy.shouldNotify(0L, now, isGroup = false, mentionsMe = false))
        assertFalse(NotificationPolicy.shouldNotify(ChatNotificationSettings.MUTED_FOREVER, now + 1000 * hour, isGroup = false, mentionsMe = false))
    }

    @Test
    fun previewChoicePerChatAndLockedChatsNeverShowContent() {
        val d = ChatNotificationSettings.PREVIEW_DEFAULT
        assertEquals(Content.FULL, NotificationPolicy.content(appPreviews = true, chatPreview = d, locked = false))
        assertEquals(Content.HIDDEN, NotificationPolicy.content(appPreviews = false, chatPreview = d, locked = false))
        assertEquals(Content.NAME_ONLY, NotificationPolicy.content(appPreviews = true, chatPreview = ChatNotificationSettings.PREVIEW_HIDE, locked = false))
        assertEquals(Content.FULL, NotificationPolicy.content(appPreviews = false, chatPreview = ChatNotificationSettings.PREVIEW_SHOW, locked = false))
        for (preview in ChatNotificationSettings.PREVIEWS) for (app in listOf(true, false)) {
            assertEquals(Content.HIDDEN, NotificationPolicy.content(app, preview, locked = true))
        }
    }

    // ------------------------------------------------------------------ edit history

    @Test
    fun editHistoryRoundTripsAndKeepsAtMostTwentyVersions() {
        var json = ""
        assertTrue(EditHistory.parse(json).isEmpty())
        json = EditHistory.append(json, "first", 10L)
        json = EditHistory.append(json, "second, with \"quotes\" and عربي", 20L)
        assertEquals(listOf(EditHistory.Entry("first", 10L), EditHistory.Entry("second, with \"quotes\" and عربي", 20L)), EditHistory.parse(json))
        repeat(30) { json = EditHistory.append(json, "v$it", 100L + it) }
        val entries = EditHistory.parse(json)
        assertEquals(EditHistory.MAX, entries.size)
        assertEquals("v29", entries.last().text)
        assertFalse(EditHistory.canEditAgain(json))
        assertTrue(EditHistory.parse("not json").isEmpty())
    }

    @Test
    fun versionsAreNewestFirstWithTheTimeEachTextWasWritten() {
        val history = listOf(EditHistory.Entry("Helo", 1_100L), EditHistory.Entry("Hello", 1_200L))
        val versions = EditHistory.versions("Hello there", createdAt = 1_000L, editedAt = 1_201L, history = history)
        assertEquals(listOf("Hello there", "Hello", "Helo"), versions.map { it.text })
        assertEquals(listOf(1_201L, 1_100L, 1_000L), versions.map { it.at })
        assertEquals(listOf(true, false, false), versions.map { it.current })
        assertEquals(listOf(false, false, true), versions.map { it.original })
        // Edited before history existed: only the current text, marked as edited (not original).
        val legacy = EditHistory.versions("now", createdAt = 1_000L, editedAt = 2_000L, history = emptyList())
        assertEquals(1, legacy.size)
        assertEquals(2_000L, legacy.single().at)
        assertFalse(legacy.single().original)
        // While the edit is still being sent (no server time yet), the last entry gives the time.
        assertEquals(1_200L, EditHistory.versions("x", 1_000L, 0L, history).first().at)
    }

    // ------------------------------------------------------------------ live location

    @Test
    fun durationsAreFifteenMinutesOneHourAndEightHours() {
        assertEquals(listOf(15 * 60_000L, hour, 8 * hour), LiveLocationPolicy.DURATIONS)
        assertTrue(LiveLocationPolicy.isValidDuration(15 * 60_000L))
        assertFalse(LiveLocationPolicy.isValidDuration(9 * hour))
        assertFalse(LiveLocationPolicy.isValidDuration(0L))
    }

    @Test
    fun pointsAreSentOnMovementOrHeartbeatButNotTooOften() {
        assertTrue(LiveLocationPolicy.shouldPublish(now, 0L, null))
        assertFalse(LiveLocationPolicy.shouldPublish(now, now - 5_000L, 500f)) // moved, but 5 s after the last point
        assertTrue(LiveLocationPolicy.shouldPublish(now, now - 11_000L, 20f))
        assertFalse(LiveLocationPolicy.shouldPublish(now, now - 30_000L, 3f)) // standing still
        assertTrue(LiveLocationPolicy.shouldPublish(now, now - 61_000L, 0f)) // heartbeat
    }

    @Test
    fun anOldPointIsNeverShownAsLive() {
        val expires = now + hour
        assertEquals(Phase.LIVE, LiveLocationPolicy.phase(now, fixAt = now - 20_000L, expiresAt = expires, endedAt = 0L, fromCache = false))
        assertEquals(Phase.STALE, LiveLocationPolicy.phase(now, fixAt = now - 4 * 60_000L, expiresAt = expires, endedAt = 0L, fromCache = false))
        // Offline viewer: a point older than a heartbeat is not presented as live.
        assertEquals(Phase.STALE, LiveLocationPolicy.phase(now, fixAt = now - 90_000L, expiresAt = expires, endedAt = 0L, fromCache = true))
        assertEquals(Phase.ENDED, LiveLocationPolicy.phase(now, fixAt = now, expiresAt = expires, endedAt = now - 1, fromCache = false))
        // Time is up even if the sender's phone never said so.
        assertEquals(Phase.ENDED, LiveLocationPolicy.phase(expires, fixAt = expires - 1_000L, expiresAt = expires, endedAt = 0L, fromCache = false))
    }

    @Test
    fun aNewerFixReplacesTheLastOneUnlessItIsMuchLessPrecise() {
        assertTrue(LiveLocationPolicy.isBetter(1_000L, 50f, null, null))
        assertTrue(LiveLocationPolicy.isBetter(2_000L, 10f, 1_000L, 20f))
        assertFalse(LiveLocationPolicy.isBetter(2_000L, 500f, 1_000L, 20f))
        assertTrue(LiveLocationPolicy.isBetter(40_000L, 500f, 1_000L, 20f))
        assertFalse(LiveLocationPolicy.isBetter(900L, 5f, 1_000L, 20f))
    }

    @Test
    fun mapTilesCoverOnlyTheVisibleArea() {
        // Zoom 0: the whole world is one tile; its centre is (128, 128).
        assertEquals(128.0, MapMath.x(0.0, 0), 1e-9)
        assertEquals(128.0, MapMath.y(0.0, 0), 1e-9)
        // Delft (52.01 N, 4.36 E) at zoom 15 lies in tile x 16780, y 10822 (standard XYZ numbering).
        val x = MapMath.x(4.36, 15)
        val y = MapMath.y(52.01, 15)
        assertEquals(16780, (x / MapMath.TILE_SIZE).toInt())
        assertEquals(10822, (y / MapMath.TILE_SIZE).toInt())
        val tiles = MapMath.visibleTiles(x, y, width = 300.0, height = 140.0, zoom = 15)
        assertTrue(tiles.size in 2..6)
        // Every tile intersects the viewport, so nothing is fetched ahead of what is on screen.
        tiles.forEach { t ->
            assertTrue(t.left < 300.0 && t.left + MapMath.TILE_SIZE > 0)
            assertTrue(t.top < 140.0 && t.top + MapMath.TILE_SIZE > 0)
        }
        assertEquals(tiles.size, tiles.distinctBy { it.x to it.y }.size)
        // Columns wrap around the date line; rows outside the world are skipped.
        assertEquals(0, MapMath.Tile(4, 0, 2, 0.0, 0.0).wrappedX)
        assertEquals(3, MapMath.Tile(-1, 0, 2, 0.0, 0.0).wrappedX)
        assertTrue(MapMath.visibleTiles(128.0, -500.0, 256.0, 256.0, 0).isEmpty())
    }
}
