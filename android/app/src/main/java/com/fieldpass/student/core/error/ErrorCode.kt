package com.fieldpass.student.core.error

import androidx.annotation.StringRes
import com.fieldpass.student.R

/**
 * Every failure the app can show. Server codes carry the contract `code` in [wire];
 * client-side conditions have a null [wire]. User-facing text lives in strings.xml only.
 */
enum class ErrorCode(val wire: String?, @StringRes val messageRes: Int) {
    INVALID_CREDENTIALS("invalid_credentials", R.string.error_invalid_credentials),
    NOT_AUTHENTICATED("not_authenticated", R.string.error_session_expired),
    FORBIDDEN("forbidden", R.string.error_forbidden),
    NOT_ASSIGNED("not_assigned", R.string.error_not_assigned),
    NOT_FOUND("not_found", R.string.error_not_found),
    ENROLLMENT_NOT_APPROVED("enrollment_not_approved", R.string.error_enrollment_not_approved),
    OUTSIDE_ACTIVITY_WINDOW("outside_activity_window", R.string.error_outside_window),
    INVALID_ACTION_ORDER("invalid_action_order", R.string.error_invalid_order),
    CHECKPOINT_NOT_OPEN("checkpoint_not_open", R.string.error_checkpoint_not_open),
    ALREADY_DECIDED("already_decided", R.string.error_already_decided),
    ATTEMPT_EXPIRED("attempt_expired", R.string.error_attempt_expired),
    IMAGE_TOO_LARGE("image_too_large", R.string.error_image_too_large),
    VALIDATION_ERROR("validation_error", R.string.error_validation),
    UNUSABLE_CAPTURE("unusable_capture", R.string.error_unusable_capture),
    MULTIPLE_FACES("multiple_faces", R.string.error_multiple_faces),
    LOCATION_STALE("location_stale", R.string.error_location_stale),

    // Client-side conditions (no wire code).
    NETWORK(null, R.string.error_network),
    TIMEOUT(null, R.string.error_timeout),
    SERVER_ERROR(null, R.string.error_server),
    INVALID_BASE_URL(null, R.string.error_invalid_base_url),
    FACULTY_ACCOUNT(null, R.string.error_faculty_account),
    LOCATION_PERMISSION_DENIED(null, R.string.error_location_permission),
    LOCATION_SERVICES_OFF(null, R.string.error_location_off),
    LOCATION_TIMEOUT(null, R.string.error_location_timeout),
    CAMERA_PERMISSION_DENIED(null, R.string.error_camera_permission),
    CAPTURE_FAILED(null, R.string.error_capture_failed),
    UNKNOWN(null, R.string.error_unknown);

    companion object {
        private val byWire = entries.filter { it.wire != null }.associateBy { it.wire }

        /** Unknown server codes fall back to [UNKNOWN]; callers branch on the code, never the message. */
        fun fromWire(code: String?): ErrorCode = byWire[code] ?: UNKNOWN
    }
}
