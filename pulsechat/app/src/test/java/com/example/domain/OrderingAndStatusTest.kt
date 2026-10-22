package com.example.domain

import com.example.util.ServerClock
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderingAndStatusTest {

    // ------------------------------------------------------------------ outgoing message position

    @Test fun newMessageUsesServerTimeWhenItIsNewest() {
        assertEquals(10_000L, MessageOrder.nextCreatedAt(now = 10_000L, newest = 9_000L))
        assertEquals(10_000L, MessageOrder.nextCreatedAt(now = 10_000L, newest = null))
    }

    @Test fun newMessageNeverSortsAboveTheNewestOne() {
        // Phone clock behind the server: the last incoming message looks "in the future".
        assertEquals(12_001L, MessageOrder.nextCreatedAt(now = 10_000L, newest = 12_000L))
        // Same millisecond: still strictly after it.
        assertEquals(10_001L, MessageOrder.nextCreatedAt(now = 10_000L, newest = 10_000L))
    }

    @Test fun messagesSentInARowKeepTheirOrder() {
        var newest: Long? = 50_000L
        val stamps = (1..5).map { MessageOrder.nextCreatedAt(now = 40_000L, newest = newest).also { newest = it } }
        assertEquals(listOf(50_001L, 50_002L, 50_003L, 50_004L, 50_005L), stamps)
    }

    // ------------------------------------------------------------------ server clock

    @Test fun firstClockSampleIsUsedDirectly() {
        var device = 1_000_000L
        val clock = ServerClock { device }
        assertEquals(1_000_000L, clock.now())
        clock.observe(serverMillis = 1_120_000L, localSentAt = 1_000_000L) // phone 2 minutes behind
        assertEquals(1_120_000L, clock.now())
        device += 5_000
        assertEquals(1_125_000L, clock.now())
    }

    @Test fun laterSamplesAreAveragedAndOutliersIgnored() {
        val clock = ServerClock { 0L }
        clock.observe(serverMillis = 1_000L + 400, localSentAt = 1_000L) // offset 400
        clock.observe(serverMillis = 1_000L + 800, localSentAt = 1_000L) // (400 * 3 + 800) / 4 = 500
        assertEquals(500L, clock.now())
        clock.observe(serverMillis = 10_000_000_000L, localSentAt = 1_000L) // more than a week off: ignored
        clock.observe(serverMillis = 0L, localSentAt = 1_000L) // no server time: ignored
        assertEquals(500L, clock.now())
    }

    // ------------------------------------------------------------------ status viewer

    private fun group(owner: String, unviewed: Boolean, latest: Long) =
        StatusPolicy.OwnerGroup(owner, listOf(StatusPolicy.Item("s_$owner", owner, latest, latest + 1, viewed = !unviewed)), unviewed, latest)

    @Test fun playOrderContinuesWithinTheSameSection() {
        val groups = listOf(group("a", true, 30), group("b", true, 20), group("c", true, 10), group("d", false, 40), group("e", false, 5))
        assertEquals(listOf("b", "c"), StatusPolicy.playOrder(groups, "b"))
        assertEquals(listOf("d", "e"), StatusPolicy.playOrder(groups, "d"))
        assertEquals(listOf("e"), StatusPolicy.playOrder(groups, "e"))
        // Someone not listed (or my own updates) plays alone.
        assertEquals(listOf("me"), StatusPolicy.playOrder(groups, "me"))
    }

    @Test fun displayTimeGrowsWithTextLength() {
        assertEquals(5_000L, StatusPolicy.displayMillis(isText = false, textLength = 0))
        assertEquals(5_000L, StatusPolicy.displayMillis(isText = true, textLength = 10))
        assertEquals(8_500L, StatusPolicy.displayMillis(isText = true, textLength = 100))
        assertEquals(10_000L, StatusPolicy.displayMillis(isText = true, textLength = 700))
    }
}
