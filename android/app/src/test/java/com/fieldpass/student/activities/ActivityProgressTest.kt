package com.fieldpass.student.activities

import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.CheckpointWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityProgressTest {
    private val window = CheckpointWindow("cp_1", "act_1", "2026-10-10T04:00:00Z", "2026-10-10T04:10:00Z", "Dr. Meera Rao")

    private fun event(action: Action, at: String) = AttendanceEvent(
        id = "ev_$at", attemptId = "at_$at", activityId = "act_1", studentId = "stu_1",
        action = action, status = AttendanceStatus.RECORDED, receivedAt = at,
    )

    private fun activity(
        checkIn: String? = null,
        checkpoints: List<String> = emptyList(),
        checkOut: String? = null,
        open: CheckpointWindow? = null,
        next: List<Action> = emptyList(),
    ) = Activity(
        id = "act_1", name = "Site", venueName = "Gate", venueLat = 1.0, venueLng = 2.0, radiusM = 100,
        startsAt = "2026-10-10T03:30:00Z", endsAt = "2026-10-10T07:30:00Z",
        openCheckpoint = open,
        myAttendance = AttendanceRecord(
            activityId = "act_1", activityName = "Site",
            checkIn = checkIn?.let { event(Action.CHECK_IN, it) },
            checkpoints = checkpoints.map { event(Action.CHECKPOINT, it) },
            checkOut = checkOut?.let { event(Action.CHECK_OUT, it) },
            nextActions = next,
        ),
    )

    private fun states(a: Activity) = ActivityProgress.steps(a).map { it.state }

    @Test fun beforeCheckInTheFirstStepIsCurrentAndTheRestWait() {
        val steps = ActivityProgress.steps(activity(next = listOf(Action.CHECK_IN)))
        assertEquals(listOf(StepKind.CHECK_IN, StepKind.CHECKPOINT, StepKind.CHECK_OUT), steps.map { it.kind })
        assertEquals(listOf(StepState.CURRENT, StepState.UPCOMING, StepState.UPCOMING), steps.map { it.state })
    }

    @Test fun checkInDoneKeepsItsTimeAndMovesOnToCheckOut() {
        val a = activity(checkIn = "2026-10-10T03:42:00Z", next = listOf(Action.CHECK_OUT))
        val steps = ActivityProgress.steps(a)
        assertEquals(StepState.DONE, steps[0].state)
        assertEquals("2026-10-10T03:42:00Z", steps[0].timeIso)
        assertEquals(StepState.UPCOMING, steps[1].state)
        assertEquals(StepState.CURRENT, steps[2].state)
    }

    @Test fun openCheckpointIsTheCurrentStepAndCarriesItsDeadline() {
        val a = activity(checkIn = "2026-10-10T03:42:00Z", open = window, next = listOf(Action.CHECKPOINT, Action.CHECK_OUT))
        val steps = ActivityProgress.steps(a)
        assertEquals(listOf(StepState.DONE, StepState.CURRENT, StepState.UPCOMING), steps.map { it.state })
        assertEquals(window.closesAt, steps[1].timeIso)
    }

    @Test fun anOpenWindowTheServerDoesNotAllowIsNotShownAsCurrent() {
        val a = activity(checkIn = "2026-10-10T03:42:00Z", open = window, next = listOf(Action.CHECK_OUT))
        assertEquals(StepState.UPCOMING, ActivityProgress.steps(a)[1].state)
    }

    @Test fun answeredCheckpointsAreDoneWithACount() {
        val a = activity(
            checkIn = "2026-10-10T03:42:00Z",
            checkpoints = listOf("2026-10-10T04:02:00Z", "2026-10-10T05:02:00Z"),
            next = listOf(Action.CHECK_OUT),
        )
        val steps = ActivityProgress.steps(a)
        assertEquals(StepState.DONE, steps[1].state)
        assertEquals(2, steps[1].count)
        assertEquals(StepState.CURRENT, steps[2].state)
    }

    @Test fun checkedOutVisitWithNoCheckpointsMarksTheMiddleStepSkipped() {
        val a = activity(checkIn = "2026-10-10T03:42:00Z", checkOut = "2026-10-10T07:00:00Z")
        val steps = ActivityProgress.steps(a)
        assertEquals(StepState.DONE, steps[2].state)
        assertTrue(steps[1].skipped)
        assertEquals(StepState.UPCOMING, steps[1].state)
    }

    @Test fun skippedIsOnlySetOnceTheVisitIsOver() {
        assertFalse(ActivityProgress.steps(activity(checkIn = "2026-10-10T03:42:00Z"))[1].skipped)
    }

    @Test fun noAttendanceRecordAtAllStillGivesThreeStepsAndNoneIsCurrent() {
        val a = activity().copy(myAttendance = null)
        assertEquals(listOf(StepState.UPCOMING, StepState.UPCOMING, StepState.UPCOMING), states(a))
    }

    @Test fun nothingIsCurrentWhenTheServerOffersNoAction() {
        assertEquals(0, states(activity()).count { it == StepState.CURRENT })
        assertEquals(0, states(activity(checkIn = "2026-10-10T03:42:00Z")).count { it == StepState.CURRENT })
    }

    @Test fun exactlyOneStepIsCurrentWhileAnActionIsAvailable() {
        assertEquals(1, states(activity(checkIn = "2026-10-10T03:42:00Z", next = listOf(Action.CHECK_OUT))).count { it == StepState.CURRENT })
        assertEquals(0, states(activity(checkIn = "2026-10-10T03:42:00Z", checkOut = "2026-10-10T07:00:00Z")).count { it == StepState.CURRENT })
    }
}
