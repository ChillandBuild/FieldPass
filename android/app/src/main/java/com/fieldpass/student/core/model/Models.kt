package com.fieldpass.student.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String,
    @SerialName("full_name") val fullName: String,
    val role: Role,
)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_at") val expiresAt: String? = null,
    val user: User,
)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class Enrollment(
    val id: String? = null,
    val status: EnrollmentStatus,
    val student: User? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("decided_by") val decidedBy: String? = null,
    @SerialName("decision_reason") val decisionReason: String? = null,
)

@Serializable
data class CheckpointWindow(
    val id: String,
    @SerialName("activity_id") val activityId: String,
    @SerialName("opens_at") val opensAt: String,
    @SerialName("closes_at") val closesAt: String,
    @SerialName("opened_by") val openedBy: String? = null,
)

@Serializable
data class LocationEvidence(
    val verdict: LocationVerdict,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("captured_at") val capturedAt: String? = null,
)

@Serializable
data class FaceEvidence(val verdict: FaceVerdict)

@Serializable
data class AttendanceEvent(
    val id: String,
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("activity_id") val activityId: String,
    @SerialName("student_id") val studentId: String,
    val action: Action,
    val status: AttendanceStatus,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("checkpoint_id") val checkpointId: String? = null,
    @SerialName("review_reasons") val reviewReasons: List<ReviewReason> = emptyList(),
    val location: LocationEvidence? = null,
    val face: FaceEvidence? = null,
)

@Serializable
data class Decision(
    val id: String,
    val decision: DecisionKind,
    val reason: String,
    @SerialName("actor_name") val actorName: String,
    @SerialName("decided_at") val decidedAt: String,
    @SerialName("is_current") val isCurrent: Boolean,
)

@Serializable
data class Observation(
    val id: String,
    @SerialName("activity_id") val activityId: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("observation_type") val observationType: ObservationType,
    val note: String,
    @SerialName("actor_name") val actorName: String,
    @SerialName("observed_at") val observedAt: String,
)

@Serializable
data class AttendanceRecord(
    @SerialName("activity_id") val activityId: String,
    @SerialName("activity_name") val activityName: String,
    val student: User? = null,
    val status: AttendanceStatus? = null,
    @SerialName("check_in") val checkIn: AttendanceEvent? = null,
    val checkpoints: List<AttendanceEvent> = emptyList(),
    @SerialName("check_out") val checkOut: AttendanceEvent? = null,
    @SerialName("missing_checkout") val missingCheckout: Boolean = false,
    val complete: Boolean = false,
    @SerialName("review_reasons") val reviewReasons: List<ReviewReason> = emptyList(),
    val decision: Decision? = null,
    @SerialName("decision_history") val decisionHistory: List<Decision> = emptyList(),
    val observations: List<Observation> = emptyList(),
    @SerialName("next_actions") val nextActions: List<Action> = emptyList(),
)

@Serializable
data class Activity(
    val id: String,
    val name: String,
    @SerialName("venue_name") val venueName: String,
    @SerialName("venue_lat") val venueLat: Double,
    @SerialName("venue_lng") val venueLng: Double,
    @SerialName("radius_m") val radiusM: Int,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("ends_at") val endsAt: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("roster_count") val rosterCount: Int = 0,
    @SerialName("checkpoint_windows") val checkpointWindows: List<CheckpointWindow> = emptyList(),
    @SerialName("open_checkpoint") val openCheckpoint: CheckpointWindow? = null,
    @SerialName("my_attendance") val myAttendance: AttendanceRecord? = null,
)

@Serializable
data class Items<T>(val items: List<T>)

@Serializable
data class AttemptRequest(val action: Action)

@Serializable
data class Attempt(
    val id: String,
    @SerialName("activity_id") val activityId: String,
    val action: Action,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("checkpoint_id") val checkpointId: String? = null,
)

@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(val code: String, val message: String? = null)
