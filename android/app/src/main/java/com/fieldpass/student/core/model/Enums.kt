package com.fieldpass.student.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    @SerialName("student") STUDENT,
    @SerialName("faculty") FACULTY,
}

@Serializable
enum class EnrollmentStatus {
    @SerialName("none") NONE,
    @SerialName("pending") PENDING,
    @SerialName("approved") APPROVED,
    @SerialName("rejected") REJECTED,
}

@Serializable
enum class Action {
    @SerialName("check_in") CHECK_IN,
    @SerialName("checkpoint") CHECKPOINT,
    @SerialName("check_out") CHECK_OUT,
}

@Serializable
enum class AttendanceStatus {
    @SerialName("recorded") RECORDED,
    @SerialName("needs_review") NEEDS_REVIEW,
    @SerialName("confirmed") CONFIRMED,
    @SerialName("not_confirmed") NOT_CONFIRMED,
}

@Serializable
enum class LocationVerdict {
    @SerialName("inside") INSIDE,
    @SerialName("uncertain") UNCERTAIN,
    @SerialName("outside") OUTSIDE,
}

@Serializable
enum class FaceVerdict {
    @SerialName("match") MATCH,
    @SerialName("uncertain") UNCERTAIN,
    @SerialName("no_match") NO_MATCH,
}

@Serializable
enum class ReviewReason {
    @SerialName("location_uncertain") LOCATION_UNCERTAIN,
    @SerialName("location_outside") LOCATION_OUTSIDE,
    @SerialName("face_mismatch") FACE_MISMATCH,
    @SerialName("face_uncertain") FACE_UNCERTAIN,
}

@Serializable
enum class ObservationType {
    @SerialName("present") PRESENT,
    @SerialName("absent") ABSENT,
    @SerialName("left_early") LEFT_EARLY,
    @SerialName("other") OTHER,
}

@Serializable
enum class DecisionKind {
    @SerialName("confirmed") CONFIRMED,
    @SerialName("not_confirmed") NOT_CONFIRMED,
}
