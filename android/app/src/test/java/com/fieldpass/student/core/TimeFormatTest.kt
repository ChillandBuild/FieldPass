package com.fieldpass.student.core

import com.fieldpass.student.core.time.AttemptCountdown
import com.fieldpass.student.core.time.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class TimeFormatTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test fun parsesUtcZSuffix() {
        assertEquals(1_791_624_600L, TimeFormat.parseUtc("2026-10-10T09:30:00Z")?.epochSecond)
    }

    @Test fun parsesFractionalSeconds() {
        assertNotNull(TimeFormat.parseUtc("2026-10-10T09:30:00.123456Z"))
    }

    @Test fun malformedOrBlankIsNull() {
        assertNull(TimeFormat.parseUtc("not a date"))
        assertNull(TimeFormat.parseUtc(""))
        assertNull(TimeFormat.parseUtc(null))
        assertEquals("", TimeFormat.formatDateTime("garbage"))
    }

    @Test fun convertsToLocalZone() {
        val utc = TimeFormat.formatTime("2026-10-10T09:30:00Z", ZoneId.of("UTC"), Locale.US)
        val ist = TimeFormat.formatTime("2026-10-10T09:30:00Z", kolkata, Locale.US)
        assertTrue(utc, utc.startsWith("9:30"))
        assertTrue(ist, ist.startsWith("3:00"))
    }

    @Test fun rangeContainsStartAndEnd() {
        val range = TimeFormat.formatRange("2026-10-10T09:30:00Z", "2026-10-10T13:30:00Z", kolkata, Locale.US)
        assertTrue(range, range.contains("3:00") && range.contains("7:00"))
    }

    @Test fun millisBetweenUsesServerTimes() {
        assertEquals(90_000L, TimeFormat.millisBetween("2026-10-10T09:31:00Z", "2026-10-10T09:32:30Z"))
        assertNull(TimeFormat.millisBetween("x", "2026-10-10T09:32:30Z"))
    }

    @Test fun countdownFormatting() {
        assertEquals("1:30", TimeFormat.formatCountdown(90_000))
        assertEquals("0:01", TimeFormat.formatCountdown(1))
        assertEquals("0:00", TimeFormat.formatCountdown(-5_000))
    }

    @Test fun attemptCountdownIgnoresAbsoluteDeviceClock() {
        val c = AttemptCountdown.fromServerTimes("2026-10-10T09:31:00Z", "2026-10-10T09:32:30Z", nowElapsed = 5_000)!!
        assertEquals(90_000L, c.remainingMillis(5_000))
        assertEquals(60_000L, c.remainingMillis(35_000))
        assertTrue(c.isExpired(95_000))
        assertEquals(0L, c.remainingMillis(1_000_000))
    }

    @Test fun attemptCountdownRejectsBadOrBackwardsTimes() {
        assertNull(AttemptCountdown.fromServerTimes("bad", "2026-10-10T09:32:30Z", 0))
        assertNull(AttemptCountdown.fromServerTimes("2026-10-10T09:32:30Z", "2026-10-10T09:31:00Z", 0))
    }
}
