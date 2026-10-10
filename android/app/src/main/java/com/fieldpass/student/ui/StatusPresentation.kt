package com.fieldpass.student.ui

import androidx.annotation.StringRes
import com.fieldpass.student.R
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.ReviewReason

/** Maps contract enums to user-facing string resources. Wording lives in strings.xml only. */
object StatusPresentation {
    @StringRes
    fun label(status: AttendanceStatus?): Int = when (status) {
        null -> R.string.status_none
        AttendanceStatus.RECORDED -> R.string.status_recorded
        AttendanceStatus.NEEDS_REVIEW -> R.string.status_needs_review
        AttendanceStatus.CONFIRMED -> R.string.status_confirmed
        AttendanceStatus.NOT_CONFIRMED -> R.string.status_not_confirmed
    }

    @StringRes
    fun meaning(status: AttendanceStatus): Int = when (status) {
        AttendanceStatus.RECORDED -> R.string.status_recorded_meaning
        AttendanceStatus.NEEDS_REVIEW -> R.string.status_needs_review_meaning
        AttendanceStatus.CONFIRMED -> R.string.status_confirmed_meaning
        AttendanceStatus.NOT_CONFIRMED -> R.string.status_not_confirmed_meaning
    }

    @StringRes
    fun reason(reason: ReviewReason): Int = when (reason) {
        ReviewReason.LOCATION_UNCERTAIN -> R.string.reason_location_uncertain
        ReviewReason.LOCATION_OUTSIDE -> R.string.reason_location_outside
        ReviewReason.FACE_MISMATCH -> R.string.reason_face_mismatch
        ReviewReason.FACE_UNCERTAIN -> R.string.reason_face_uncertain
    }

    @StringRes
    fun actionLabel(action: Action): Int = when (action) {
        Action.CHECK_IN -> R.string.action_check_in
        Action.CHECKPOINT -> R.string.action_checkpoint
        Action.CHECK_OUT -> R.string.action_check_out
    }

    @StringRes
    fun eventLabel(action: Action): Int = when (action) {
        Action.CHECK_IN -> R.string.event_check_in
        Action.CHECKPOINT -> R.string.event_checkpoint
        Action.CHECK_OUT -> R.string.event_check_out
    }
}
