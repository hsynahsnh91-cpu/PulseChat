package com.example.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduledPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun validatesTextAndTime() {
        assertNull(ScheduledPolicy.validate("hi", now + 120_000, now))
        assertEquals(ScheduledPolicy.Error.EMPTY, ScheduledPolicy.validate("  ", now + 120_000, now))
        assertEquals(ScheduledPolicy.Error.TOO_LONG, ScheduledPolicy.validate("x".repeat(MessagePolicy.MAX_TEXT_LENGTH + 1), now + 120_000, now))
        assertEquals(ScheduledPolicy.Error.TOO_SOON, ScheduledPolicy.validate("hi", now + 10_000, now))
        assertEquals(ScheduledPolicy.Error.TOO_FAR, ScheduledPolicy.validate("hi", now + ScheduledPolicy.MAX_AHEAD_MS + 1, now))
    }

    @Test fun earlyRunsWaitForTheRealTime() {
        assertTrue(ScheduledPolicy.isDue(now, now))
        assertTrue(ScheduledPolicy.isDue(now + 10_000, now)) // within the tolerance
        assertTrue(!ScheduledPolicy.isDue(now + 5 * 60_000, now)) // e.g. clock was set back
    }
}

class ReminderTimesTest {
    private val zone = TimeZone.getTimeZone("Asia/Damascus")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, h, min, 0) }.timeInMillis

    @Test fun presetsAreComputedInTheLocalZone() {
        val now = at(2026, 10, 21, 10) // Wednesday 10:00
        assertEquals(now + 3_600_000, ReminderTimes.at(ReminderTimes.Preset.IN_1_HOUR, now, zone))
        assertEquals(at(2026, 10, 21, 19), ReminderTimes.at(ReminderTimes.Preset.THIS_EVENING, now, zone))
        assertEquals(at(2026, 10, 22, 9), ReminderTimes.at(ReminderTimes.Preset.TOMORROW_MORNING, now, zone))
        assertEquals(at(2026, 10, 26, 9), ReminderTimes.at(ReminderTimes.Preset.NEXT_WEEK, now, zone)) // next Monday
    }

    @Test fun thisEveningIsOfferedOnlyBeforeFivePm() {
        assertNull(ReminderTimes.at(ReminderTimes.Preset.THIS_EVENING, at(2026, 10, 21, 18), zone))
        assertTrue(ReminderTimes.presets(at(2026, 10, 21, 18), zone).none { it.first == ReminderTimes.Preset.THIS_EVENING })
    }

    @Test fun nextWeekOnAMondayMeansTheFollowingMonday() {
        assertEquals(at(2026, 11, 2, 9), ReminderTimes.at(ReminderTimes.Preset.NEXT_WEEK, at(2026, 10, 26, 8), zone))
    }

    @Test fun combinesAPickedDateWithATime() {
        val utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 11, 31) }.timeInMillis
        assertEquals(at(2026, 12, 31, 23, 45), ReminderTimes.combine(utcMidnight, 23, 45, zone))
    }
}

class LinkExtractorTest {
    @Test fun findsLinksAndTrimsPunctuation() {
        val links = LinkExtractor.extract("See https://example.com/a?b=1, and www.pulse.dev/docs. Also (https://x.org/p_(1)) ok?")
        assertEquals(listOf("https://example.com/a?b=1", "https://www.pulse.dev/docs", "https://x.org/p_(1)"), links.map { it.url })
        assertEquals(listOf("example.com", "pulse.dev", "x.org"), links.map { it.host })
    }

    @Test fun worksInArabicTextAndDeduplicates() {
        val links = LinkExtractor.extract("الرابط: https://example.com/ar، ثم https://example.com/ar؟")
        assertEquals(listOf("https://example.com/ar"), links.map { it.url })
    }

    @Test fun ignoresNonLinks() {
        assertTrue(LinkExtractor.extract("email me at a@b.c or call 555-1234; http:// is not a link").isEmpty())
    }
}

class AiParsingTest {
    private val refs = setOf("m1", "m2", "m3")

    @Test fun parsesSummaryAndDropsUnknownReferences() {
        val raw = "```json\n{\"points\":[{\"text\":\"Lunch moved to 2pm\",\"refs\":[\"m2\",\"m9\",\"M3\"]},{\"text\":\"  \"},{\"text\":\"Bob is late\",\"refs\":[]}]}\n```"
        val points = AiParsing.parseSummary(raw, refs)
        assertEquals(2, points.size)
        assertEquals("Lunch moved to 2pm", points[0].text)
        assertEquals(listOf("m2", "m3"), points[0].refs) // m9 was never sent: dropped
        assertTrue(points[1].refs.isEmpty())
    }

    @Test fun parsesActionsAndDates() {
        val raw = """Here you go: {"items":[{"kind":"event","title":"Dentist","due":"2026-10-22T17:00","allDay":false,"refs":["m1"]},
            {"kind":"task","title":"Send the report","due":null,"refs":["m3"]},{"kind":"task","title":""}]} thanks"""
        val items = AiParsing.parseActions(raw, refs)
        assertEquals(2, items.size)
        assertEquals(AiParsing.ActionItem.Kind.EVENT, items[0].kind)
        assertEquals("2026-10-22T17:00", items[0].due)
        assertNull(items[1].due)
        val zone = TimeZone.getTimeZone("Asia/Damascus")
        val expected = Calendar.getInstance(zone).apply { clear(); set(2026, 9, 22, 17, 0, 0) }.timeInMillis
        assertEquals(expected, AiParsing.parseDateTime("2026-10-22T17:00", zone))
        assertEquals(expected, AiParsing.parseDateTime("2026-10-22T17:00:00+03:00", TimeZone.getTimeZone("UTC")))
        assertEquals(Calendar.getInstance(zone).apply { clear(); set(2026, 9, 23, 9, 0, 0) }.timeInMillis, AiParsing.parseDateTime("2026-10-23", zone))
        assertNull(AiParsing.parseDateTime("tomorrow", zone))
    }

    @Test fun malformedOutputGivesNothing() {
        assertTrue(AiParsing.parseSummary("I can't do that", refs).isEmpty())
        assertTrue(AiParsing.parseActions("{\"items\": [", refs).isEmpty())
        assertEquals("{\"a\":\"}\"}", AiParsing.jsonPayload("x {\"a\":\"}\"} y"))
    }
}

class ImageEditMathTest {
    @Test fun fitLetterboxesTheImage() {
        val b = ImageEditMath.fit(1000f, 1000f, 2000f, 1000f)
        assertEquals(ImageEditMath.Box(0f, 250f, 1000f, 750f), b)
        assertEquals(0.5f to 0.5f, ImageEditMath.normalize(500f, 500f, b))
        assertEquals(0f to 0f, ImageEditMath.normalize(-10f, 100f, b)) // clamped to the image
    }

    @Test fun cropStaysInsideAndKeepsAMinimumSize() {
        val c = ImageEditMath.clampCrop(ImageEditMath.Box(-0.2f, 0.5f, 0.55f, 0.52f))
        assertTrue(c.left >= 0f && c.right <= 1f && c.top >= 0f && c.bottom <= 1f)
        assertTrue(c.width >= ImageEditMath.MIN_CROP - 1e-4f && c.height >= ImageEditMath.MIN_CROP - 1e-4f)
        val moved = ImageEditMath.dragCrop(ImageEditMath.Box(0.6f, 0.6f, 0.9f, 0.9f), ImageEditMath.Handle.MOVE, 0.5f, 0.5f)
        assertEquals(1f, moved.right, 1e-4f)
        assertEquals(0.3f, moved.width, 1e-4f)
    }

    @Test fun pixelateReplacesEveryPixelOfACellWithTheAverage() {
        val px = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        ImageEditMath.pixelate(px, 2, 2, 2)
        assertTrue(px.all { it == px[0] })
        assertNotEquals(0xFF000000.toInt(), px[0])
        assertEquals(12, ImageEditMath.pixelateBlock(50, 30))
        assertEquals(40, ImageEditMath.pixelateBlock(400, 100))
        assertArrayEquals(intArrayOf(5), intArrayOf(5))
    }

    @Test fun pixelBoxesStayInsideTheBitmap() {
        val p = ImageEditMath.toPixels(ImageEditMath.Box(0.9f, 0.9f, 1.2f, 1.5f), 100, 50)
        assertTrue(p.left + p.width <= 100 && p.top + p.height <= 50 && p.width >= 1 && p.height >= 1)
    }
}
