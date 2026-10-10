package com.fieldpass.student.activities

import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.AttendanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class DaySummaryTest {
    private val now = Instant.parse("2026-10-10T08:00:00Z")

    private fun onTrack(activities: List<Activity>) = DaySummary.isOnTrack(activities, now, ZoneOffset.UTC)

    private fun activity(status: AttendanceStatus?, missingCheckout: Boolean = false, endsAt: String = "2026-10-10T07:30:00Z") = Activity(
        id = "act_1", name = "Site", venueName = "Gate", venueLat = 1.0, venueLng = 2.0, radiusM = 100,
        startsAt = "2026-10-10T03:30:00Z", endsAt = endsAt,
        myAttendance = AttendanceRecord(activityId = "act_1", activityName = "Site", status = status, missingCheckout = missingCheckout),
    )

    @Test fun greetingFollowsTheHourOfTheDay() {
        assertEquals(GreetingPeriod.MORNING, GreetingPeriod.forHour(0))
        assertEquals(GreetingPeriod.MORNING, GreetingPeriod.forHour(11))
        assertEquals(GreetingPeriod.AFTERNOON, GreetingPeriod.forHour(12))
        assertEquals(GreetingPeriod.AFTERNOON, GreetingPeriod.forHour(16))
        assertEquals(GreetingPeriod.EVENING, GreetingPeriod.forHour(17))
        assertEquals(GreetingPeriod.EVENING, GreetingPeriod.forHour(23))
    }

    @Test fun firstNameIsTheFirstWordOfTheFullName() {
        assertEquals("Asha", DaySummary.firstName("Asha Verma"))
        assertEquals("Asha", DaySummary.firstName("  Asha  "))
        assertNull(DaySummary.firstName("   "))
        assertNull(DaySummary.firstName(null))
    }

    @Test fun onTrackWhenNothingIsNotConfirmedOrMissingACheckout() {
        assertTrue(onTrack(emptyList()))
        assertTrue(onTrack(listOf(activity(null), activity(AttendanceStatus.RECORDED), activity(AttendanceStatus.NEEDS_REVIEW), activity(AttendanceStatus.CONFIRMED))))
    }

    @Test fun notOnTrackWhenFacultyDidNotConfirmAnActivity() {
        assertFalse(onTrack(listOf(activity(AttendanceStatus.CONFIRMED), activity(AttendanceStatus.NOT_CONFIRMED))))
    }

    @Test fun notOnTrackWhenACheckoutIsMissing() {
        assertFalse(onTrack(listOf(activity(AttendanceStatus.RECORDED, missingCheckout = true))))
    }

    @Test fun anActivityFromAnEarlierDayDoesNotAffectToday() {
        val old = activity(AttendanceStatus.NOT_CONFIRMED, endsAt = "2026-10-07T07:30:00Z")
        assertTrue(onTrack(listOf(old, activity(AttendanceStatus.RECORDED))))
    }

    @Test fun anActivityThatEndsTomorrowStillCounts() {
        assertFalse(onTrack(listOf(activity(AttendanceStatus.NOT_CONFIRMED, endsAt = "2026-10-11T07:30:00Z"))))
    }
}
