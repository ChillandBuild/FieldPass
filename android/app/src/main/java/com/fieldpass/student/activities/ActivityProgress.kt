package com.fieldpass.student.activities

import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity

enum class StepKind { CHECK_IN, CHECKPOINT, CHECK_OUT }

enum class StepState { DONE, CURRENT, UPCOMING }

/**
 * One row of the check-in / checkpoint / check-out tracker.
 * [timeIso] is when it was recorded (done) or when the open window closes (current checkpoint).
 * [count] is how many checkpoints were answered. [skipped] means the visit ended without any.
 */
data class ProgressStep(
    val kind: StepKind,
    val state: StepState,
    val timeIso: String? = null,
    val count: Int = 0,
    val skipped: Boolean = false,
)

/** Pure rules for the tracker. The server's `next_actions` still decides what the student may do. */
object ActivityProgress {
    fun steps(activity: Activity): List<ProgressStep> {
        val record = activity.myAttendance
        val checkIn = record?.checkIn
        val checkOut = record?.checkOut
        val answered = record?.checkpoints.orEmpty()
        val openWindow = ActivityActions.checkpointToShow(activity)

        val checkInStep = ProgressStep(
            StepKind.CHECK_IN,
            when {
                checkIn != null -> StepState.DONE
                ActivityActions.canAct(activity, Action.CHECK_IN) -> StepState.CURRENT
                else -> StepState.UPCOMING
            },
            timeIso = checkIn?.receivedAt,
        )
        val checkpointStep = when {
            openWindow != null -> ProgressStep(StepKind.CHECKPOINT, StepState.CURRENT, timeIso = openWindow.closesAt, count = answered.size)
            answered.isNotEmpty() -> ProgressStep(StepKind.CHECKPOINT, StepState.DONE, timeIso = answered.last().receivedAt, count = answered.size)
            else -> ProgressStep(StepKind.CHECKPOINT, StepState.UPCOMING, skipped = checkOut != null)
        }
        val checkOutStep = when {
            checkOut != null -> ProgressStep(StepKind.CHECK_OUT, StepState.DONE, timeIso = checkOut.receivedAt)
            openWindow == null && ActivityActions.canAct(activity, Action.CHECK_OUT) -> ProgressStep(StepKind.CHECK_OUT, StepState.CURRENT)
            else -> ProgressStep(StepKind.CHECK_OUT, StepState.UPCOMING)
        }
        return listOf(checkInStep, checkpointStep, checkOutStep)
    }
}
