package com.fieldpass.student.data

import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.core.model.FaceVerdict
import com.fieldpass.student.core.model.HeadTurn
import com.fieldpass.student.core.model.Items
import com.fieldpass.student.core.model.LocationVerdict
import com.fieldpass.student.core.model.LoginRequest
import com.fieldpass.student.core.model.LoginResponse
import com.fieldpass.student.core.model.PresenceRequest
import com.fieldpass.student.core.model.PresenceResponse
import com.fieldpass.student.core.model.ReviewReason
import com.fieldpass.student.core.model.Role
import com.fieldpass.student.core.model.AttemptRequest
import com.fieldpass.student.data.api.ApiClient
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON below is copied from contracts/API.md. */
class DtoSerializationTest {
    private val json = ApiClient.json

    private val student = """{"id": "u_s1", "username": "student1", "full_name": "Asha Verma", "role": "student"}"""

    private val eventJson = """
    {
      "id": "evt_1", "attempt_id": "att_1", "activity_id": "act_1", "student_id": "u_s1",
      "action": "check_in", "status": "recorded",
      "received_at": "2026-10-10T09:31:12Z",
      "checkpoint_id": null,
      "review_reasons": [],
      "location": {"verdict": "inside", "distance_m": 40, "accuracy_m": 15, "captured_at": "2026-10-10T09:31:05Z"},
      "face": {"verdict": "match"}
    }"""

    @Test fun loginResponse() {
        val r = json.decodeFromString<LoginResponse>(
            """{"access_token": "tok", "token_type": "bearer", "expires_at": "2026-10-10T21:00:00Z", "user": $student}""",
        )
        assertEquals("tok", r.accessToken)
        assertEquals(Role.STUDENT, r.user.role)
        assertEquals("Asha Verma", r.user.fullName)
    }

    @Test fun loginRequestEncodesSnakeCase() {
        assertEquals("""{"username":"student1","password":"pw"}""", json.encodeToString(LoginRequest("student1", "pw")))
        assertEquals("""{"action":"check_out"}""", json.encodeToString(AttemptRequest(Action.CHECK_OUT)))
    }

    @Test fun enrollmentPending() {
        val e = json.decodeFromString<Enrollment>(
            """{"id": "enr_1", "status": "pending", "student": $student, "submitted_at": "2026-10-10T09:00:00Z",
               "decided_at": null, "decided_by": null, "decision_reason": null}""",
        )
        assertEquals(EnrollmentStatus.PENDING, e.status)
        assertNull(e.decisionReason)
    }

    @Test fun enrollmentNoneHasNullId() {
        val e = json.decodeFromString<Enrollment>(
            """{"id": null, "status": "none", "student": $student, "submitted_at": null, "decided_at": null, "decided_by": null, "decision_reason": null}""",
        )
        assertEquals(EnrollmentStatus.NONE, e.status)
        assertNull(e.id)
    }

    @Test fun enrollmentRejectedKeepsReason() {
        val e = json.decodeFromString<Enrollment>(
            """{"id": "enr_2", "status": "rejected", "decided_by": "Dr. Meera Rao", "decision_reason": "Face not clear"}""",
        )
        assertEquals("Face not clear", e.decisionReason)
        assertEquals("Dr. Meera Rao", e.decidedBy)
    }

    @Test fun attendanceEvent() {
        val e = json.decodeFromString<AttendanceEvent>(eventJson)
        assertEquals(Action.CHECK_IN, e.action)
        assertEquals(AttendanceStatus.RECORDED, e.status)
        assertEquals(LocationVerdict.INSIDE, e.location?.verdict)
        assertEquals(15.0, e.location?.accuracyM ?: 0.0, 0.0)
        assertEquals(FaceVerdict.MATCH, e.face?.verdict)
    }

    @Test fun presenceVerifiedDefaultsToFalseWhenAnOlderBackendOmitsIt() {
        val e = json.decodeFromString<AttendanceEvent>(eventJson)
        assertFalse(e.presenceVerified)
    }

    @Test fun presenceVerifiedIsReadWhenPresent() {
        val yes = json.decodeFromString<AttendanceEvent>(eventJson.replace(""""face": {""", """"presence_verified": true, "face": {"""))
        assertTrue(yes.presenceVerified)
        val no = json.decodeFromString<AttendanceEvent>(eventJson.replace(""""face": {""", """"presence_verified": false, "face": {"""))
        assertFalse(no.presenceVerified)
    }

    @Test fun presenceRequestAndResponse() {
        assertEquals("""{"code":"fp1.176000000.AbCd"}""", json.encodeToString(PresenceRequest("fp1.176000000.AbCd")))
        assertTrue(json.decodeFromString<PresenceResponse>("""{"verified": true}""").verified)
        assertFalse(json.decodeFromString<PresenceResponse>("""{"verified": false}""").verified)
    }

    @Test fun eventWithReviewReasons() {
        val e = json.decodeFromString<AttendanceEvent>(
            eventJson.replace(""""status": "recorded"""", """"status": "needs_review"""")
                .replace(""""review_reasons": []""", """"review_reasons": ["location_uncertain", "face_uncertain"]"""),
        )
        assertEquals(AttendanceStatus.NEEDS_REVIEW, e.status)
        assertEquals(listOf(ReviewReason.LOCATION_UNCERTAIN, ReviewReason.FACE_UNCERTAIN), e.reviewReasons)
    }

    @Test fun attemptResponse() {
        val a = json.decodeFromString<Attempt>(
            """{"id": "att_1", "activity_id": "act_1", "action": "checkpoint", "created_at": "2026-10-10T09:31:00Z",
               "expires_at": "2026-10-10T09:32:30Z", "checkpoint_id": "cp_1"}""",
        )
        assertEquals(Action.CHECKPOINT, a.action)
        assertEquals("cp_1", a.checkpointId)
    }

    @Test fun activityListForStudentWithNextActions() {
        val raw = """{"items": [{
          "id": "act_1", "name": "Site Visit: Water Treatment Plant",
          "venue_name": "Plant Gate 2", "venue_lat": 12.9716, "venue_lng": 77.5946, "radius_m": 100,
          "starts_at": "2026-10-10T09:30:00Z", "ends_at": "2026-10-10T13:30:00Z",
          "created_by": "Dr. Meera Rao", "roster_count": 3,
          "checkpoint_windows": [],
          "open_checkpoint": {"id": "cp_1", "activity_id": "act_1", "opens_at": "2026-10-10T10:30:00Z", "closes_at": "2026-10-10T10:40:00Z", "opened_by": "Dr. Meera Rao"},
          "my_attendance": {
            "activity_id": "act_1", "activity_name": "Site Visit: Water Treatment Plant", "student": $student,
            "status": "recorded", "check_in": $eventJson, "checkpoints": [], "check_out": null,
            "missing_checkout": false, "complete": false, "review_reasons": [], "decision": null,
            "decision_history": [], "observations": [], "next_actions": ["checkpoint", "check_out"]
          }}]}"""
        val list = json.decodeFromString<Items<Activity>>(raw).items
        val a = list.single()
        assertEquals(100, a.radiusM)
        assertEquals("cp_1", a.openCheckpoint?.id)
        assertEquals(listOf(Action.CHECKPOINT, Action.CHECK_OUT), a.myAttendance?.nextActions)
        assertEquals("evt_1", a.myAttendance?.checkIn?.id)
    }

    @Test fun activityWithoutAttendanceForFaculty() {
        val a = json.decodeFromString<Activity>(
            """{"id": "act_1", "name": "N", "venue_name": "V", "venue_lat": 1, "venue_lng": 2, "radius_m": 100,
               "starts_at": "2026-10-10T09:30:00Z", "ends_at": "2026-10-10T13:30:00Z", "created_by": "X",
               "roster_count": 3, "checkpoint_windows": [], "open_checkpoint": null, "my_attendance": null}""",
        )
        assertNull(a.myAttendance)
    }

    @Test fun attendanceRecordWithDecisionAndMissingCheckout() {
        val r = json.decodeFromString<AttendanceRecord>(
            """{"activity_id": "act_1", "activity_name": "N", "status": "not_confirmed", "check_in": null,
               "checkpoints": [], "check_out": null, "missing_checkout": true, "complete": false, "review_reasons": [],
               "decision": {"id": "dec_1", "decision": "not_confirmed", "reason": "Not seen", "actor_name": "Dr. Meera Rao",
                            "decided_at": "2026-10-10T13:40:00Z", "is_current": true},
               "decision_history": [], "observations": [], "next_actions": []}""",
        )
        assertEquals(AttendanceStatus.NOT_CONFIRMED, r.status)
        assertTrue(r.missingCheckout)
        assertEquals("Not seen", r.decision?.reason)
    }

    @Test fun nullStatusRecordAndUnknownFieldsAreTolerated() {
        val r = json.decodeFromString<AttendanceRecord>(
            """{"activity_id": "a", "activity_name": "N", "status": null, "future_field": 1, "next_actions": ["check_in"]}""",
        )
        assertNull(r.status)
        assertEquals(listOf(Action.CHECK_IN), r.nextActions)
    }

    @Test fun `attempt carries nonce and head-turn challenge`() {
        val a = json.decodeFromString<Attempt>(
            """{"id": "att_1", "activity_id": "act_1", "action": "check_in", "created_at": "2026-10-10T09:31:00Z",
               "expires_at": "2026-10-10T09:32:30Z", "checkpoint_id": null, "nonce": "n0nce", "challenge": "right"}""",
        )
        assertEquals("n0nce", a.nonce)
        assertEquals(HeadTurn.RIGHT, a.challenge)
    }

    @Test fun `new review reasons decode`() {
        val reasons = json.decodeFromString<List<ReviewReason>>(
            """["liveness_failed","location_mocked","location_repeated","device_rooted","device_emulator"]""",
        )
        assertEquals(
            listOf(ReviewReason.LIVENESS_FAILED, ReviewReason.LOCATION_MOCKED, ReviewReason.LOCATION_REPEATED,
                ReviewReason.DEVICE_ROOTED, ReviewReason.DEVICE_EMULATOR),
            reasons,
        )
    }
}
