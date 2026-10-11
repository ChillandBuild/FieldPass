package com.fieldpass.student.capture

import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.Selfie

/** Explicit phases of one attendance attempt. */
enum class CapturePhase {
    CREATING_ATTEMPT,
    LOCATING,
    LOCATION_FAILED,
    POOR_ACCURACY,
    AWAITING_CODE,
    AWAITING_SELFIE,
    READY_TO_SUBMIT,
    SUBMITTING,
    DONE,
    EXPIRED,
    ATTEMPT_FAILED,
}

/** What the optional "scan the faculty code" step is doing right now. */
enum class CodeStatus {
    SCANNING,
    CHECKING,
    TOO_OLD,
    FAILED,
}

/**
 * Single immutable UI state. [error] is a plain-language hint for the current phase
 * (e.g. retake guidance, or why the code could not be checked); [pendingReplay] means a response was lost and the SAME attempt is retried.
 */
data class CaptureUiState(
    val phase: CapturePhase = CapturePhase.CREATING_ATTEMPT,
    val attempt: Attempt? = null,
    val remainingMillis: Long? = null,
    val fix: LocationFix? = null,
    val selfie: Selfie? = null,
    val turnPhoto: Selfie? = null,
    val error: ErrorCode? = null,
    val pendingReplay: Boolean = false,
    val poorAccuracyAccepted: Boolean = false,
    val codeStatus: CodeStatus = CodeStatus.SCANNING,
    val codeVerified: Boolean = false,
    val codeStepDone: Boolean = false,
    val result: AttendanceEvent? = null,
) {
    val canSubmit: Boolean get() = phase == CapturePhase.READY_TO_SUBMIT
}
