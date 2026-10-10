package com.fieldpass.student.activities

import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.CheckpointWindow

/** Pure rules for what the activity screens offer. The server's `next_actions` is the only source. */
object ActivityActions {
    private val ORDER = listOf(Action.CHECK_IN, Action.CHECKPOINT, Action.CHECK_OUT)

    /** Buttons to show: exactly the actions in `my_attendance.next_actions`, in a stable order. */
    fun visible(activity: Activity): List<Action> {
        val allowed = activity.myAttendance?.nextActions.orEmpty().toSet()
        return ORDER.filter { it in allowed }
    }

    fun canAct(activity: Activity, action: Action): Boolean = action in visible(activity)

    /** Checkpoints are only part of the activity when faculty has opened at least one window. */
    fun hasCheckpoints(activity: Activity): Boolean =
        activity.checkpointWindows.isNotEmpty() || activity.openCheckpoint != null

    /** The window to show, only when the server says a checkpoint can be answered now. */
    fun checkpointToShow(activity: Activity): CheckpointWindow? =
        activity.openCheckpoint.takeIf { canAct(activity, Action.CHECKPOINT) }
}
