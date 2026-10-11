package com.fieldpass.student.activities

import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TodayActivityTest {
    private val now = Instant.parse("2026-10-10T04:54:00Z")

    private fun pick(vararg activities: Activity) = TodayActivity.pick(activities.toList(), now)?.id

    private fun activity(id: String, startsAt: String, endsAt: String, nextActions: List<Action> = emptyList()) = Activity(
        id = id, name = "Site", venueName = "Gate", venueLat = 1.0, venueLng = 2.0, radiusM = 100,
        startsAt = startsAt, endsAt = endsAt,
        myAttendance = AttendanceRecord(activityId = id, activityName = "Site", nextActions = nextActions),
    )

    private val finished = activity("finished", "2026-10-09T03:30:00Z", "2026-10-09T07:30:00Z")
    private val running = activity("running", "2026-10-10T03:30:00Z", "2026-10-10T07:30:00Z")
    private val tomorrow = activity("tomorrow", "2026-10-11T04:30:00Z", "2026-10-11T07:30:00Z")
    private val nextWeek = activity("next_week", "2026-10-17T04:30:00Z", "2026-10-17T07:30:00Z")

    @Test fun nothingIsPickedWhenThereAreNoActivities() {
        assertNull(pick())
    }

    @Test fun anActivityWithSomethingToDoNowWinsOverOneThatIsOnlyRunning() {
        val actionable = activity("actionable", "2026-10-10T05:00:00Z", "2026-10-10T08:00:00Z", listOf(Action.CHECK_IN))
        assertEquals("actionable", pick(running, actionable))
    }

    @Test fun theRunningActivityWinsOverALaterOne() {
        assertEquals("running", pick(tomorrow, running, finished))
    }

    @Test fun theSoonestUpcomingActivityIsPickedWhenNothingIsRunning() {
        assertEquals("tomorrow", pick(nextWeek, finished, tomorrow))
    }

    @Test fun nothingIsPickedWhenEveryActivityHasFinished() {
        assertNull(pick(finished))
    }

    @Test fun anActivityWithUnreadableTimesIsNeverPickedOnItsTimes() {
        assertNull(pick(activity("broken", "not a time", "also not a time")))
    }

    @Test fun runningMeansStartedAndNotYetEnded() {
        assertTrue(TodayActivity.isRunning(running, now))
        assertFalse(TodayActivity.isRunning(tomorrow, now))
        assertFalse(TodayActivity.isRunning(finished, now))
    }

    @Test fun todayMeansTheActivityOverlapsTheLocalDate() {
        assertTrue(TodayActivity.isToday(running, now, ZoneOffset.UTC))
        assertFalse(TodayActivity.isToday(tomorrow, now, ZoneOffset.UTC))
        assertFalse(TodayActivity.isToday(finished, now, ZoneOffset.UTC))
    }
}
