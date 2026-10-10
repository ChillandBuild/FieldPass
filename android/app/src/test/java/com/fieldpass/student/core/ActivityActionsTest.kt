package com.fieldpass.student.core

import com.fieldpass.student.activities.ActivityActions
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.CheckpointWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityActionsTest {
    private val window = CheckpointWindow("cp_1", "act_1", "2026-10-10T10:30:00Z", "2026-10-10T10:40:00Z", "Dr. Meera Rao")

    private fun activity(next: List<Action>?, open: CheckpointWindow? = null, windows: List<CheckpointWindow> = emptyList()) = Activity(
        id = "act_1", name = "Site", venueName = "Gate", venueLat = 1.0, venueLng = 2.0, radiusM = 100,
        startsAt = "2026-10-10T09:30:00Z", endsAt = "2026-10-10T13:30:00Z",
        checkpointWindows = windows, openCheckpoint = open,
        myAttendance = next?.let { AttendanceRecord(activityId = "act_1", activityName = "Site", nextActions = it) },
    )

    @Test fun onlyServerListedActionsAreVisible() {
        assertEquals(listOf(Action.CHECK_IN), ActivityActions.visible(activity(listOf(Action.CHECK_IN))))
        assertEquals(
            listOf(Action.CHECKPOINT, Action.CHECK_OUT),
            ActivityActions.visible(activity(listOf(Action.CHECK_OUT, Action.CHECKPOINT))),
        )
    }

    @Test fun noNextActionsMeansNoButtons() {
        assertTrue(ActivityActions.visible(activity(emptyList())).isEmpty())
        assertTrue(ActivityActions.visible(activity(null)).isEmpty())
    }

    @Test fun openWindowAloneDoesNotEnableCheckpointButton() {
        val a = activity(listOf(Action.CHECK_OUT), open = window)
        assertFalse(ActivityActions.canAct(a, Action.CHECKPOINT))
        assertNull(ActivityActions.checkpointToShow(a))
    }

    @Test fun checkpointWindowShownWhenServerAllowsIt() {
        val a = activity(listOf(Action.CHECKPOINT, Action.CHECK_OUT), open = window)
        assertEquals(window, ActivityActions.checkpointToShow(a))
    }

    @Test fun checkpointsRequiredOnlyWhenFacultyOpenedAny() {
        assertFalse(ActivityActions.hasCheckpoints(activity(emptyList())))
        assertTrue(ActivityActions.hasCheckpoints(activity(emptyList(), windows = listOf(window))))
    }
}
