package com.fieldpass.student.activities

import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.time.TimeFormat
import java.time.Instant
import java.time.ZoneId

/** Which activity the home screen puts in the large card. Pure rules; wording lives in strings.xml. */
object TodayActivity {
    /**
     * The activity with something to do right now, else the one running now, else the next to start.
     * Null when every activity has finished, so home falls back to the plain list.
     */
    fun pick(activities: List<Activity>, now: Instant = Instant.now()): Activity? =
        activities.firstOrNull { ActivityActions.visible(it).isNotEmpty() }
            ?: activities.firstOrNull { isRunning(it, now) }
            ?: activities
                .mapNotNull { activity -> TimeFormat.parseUtc(activity.startsAt)?.takeIf { it.isAfter(now) }?.let { activity to it } }
                .minByOrNull { it.second }
                ?.first

    fun isRunning(activity: Activity, now: Instant = Instant.now()): Boolean {
        val start = TimeFormat.parseUtc(activity.startsAt) ?: return false
        val end = TimeFormat.parseUtc(activity.endsAt) ?: return false
        return !now.isBefore(start) && now.isBefore(end)
    }

    /** True when the activity's start-to-end span touches the local calendar date of [now]. */
    fun isToday(activity: Activity, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val start = TimeFormat.parseUtc(activity.startsAt)?.atZone(zone)?.toLocalDate() ?: return false
        val end = TimeFormat.parseUtc(activity.endsAt)?.atZone(zone)?.toLocalDate() ?: return false
        val today = now.atZone(zone).toLocalDate()
        return !today.isBefore(start) && !today.isAfter(end)
    }
}
