package com.fieldpass.student.activities

import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.time.TimeFormat
import java.time.Instant
import java.time.ZoneId

private const val NOON_HOUR = 12
private const val EVENING_START_HOUR = 17

enum class GreetingPeriod {
    MORNING, AFTERNOON, EVENING;

    companion object {
        fun forHour(hour: Int): GreetingPeriod = when {
            hour < NOON_HOUR -> MORNING
            hour < EVENING_START_HOUR -> AFTERNOON
            else -> EVENING
        }
    }
}

/** What the home header says about the student's day. Wording lives in strings.xml. */
object DaySummary {
    fun firstName(fullName: String?): String? =
        fullName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    /**
     * "On track" only when no activity from today onward is Not confirmed or missing its check-out.
     * Earlier days are left to History so an old record does not colour today's header.
     */
    fun isOnTrack(
        activities: List<Activity>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val today = now.atZone(zone).toLocalDate()
        return activities.none { activity ->
            val endDate = TimeFormat.parseUtc(activity.endsAt)?.atZone(zone)?.toLocalDate()
            val record = activity.myAttendance
            val isCurrent = endDate == null || !endDate.isBefore(today)
            isCurrent && record != null && (record.status == AttendanceStatus.NOT_CONFIRMED || record.missingCheckout)
        }
    }
}
