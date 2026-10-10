package com.fieldpass.student.integration

import com.fieldpass.student.activities.ActivityActions
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.DeviceFlags
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.core.model.FaceVerdict
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.LocationVerdict
import com.fieldpass.student.core.model.Role
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.core.time.AttemptCountdown
import com.fieldpass.student.core.time.TimeFormat
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import retrofit2.HttpException
import java.time.Duration
import java.time.Instant

/**
 * Drives the app's PRODUCTION networking stack (ApiClient/ApiService, RealFieldPassRepository, DTOs,
 * ErrorParser) against a REAL backend. Skipped unless FIELDPASS_API_URL, FIELDPASS_PASSWORD and
 * FIELDPASS_FACE_IMAGE are set (see android/README.md). Steps share state and run in name order, so
 * run against a FRESH database: student1 must still have enrollment status "none".
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BackendContractTest {

    companion object {
        private lateinit var base: String
        private lateinit var password: String
        private lateinit var facultyPassword: String
        private lateinit var face: ByteArray
        private lateinit var blank: ByteArray
        private lateinit var faculty: FacultyHttp
        private lateinit var student1: StudentStack
        private lateinit var ids: Map<String, String>
        private lateinit var activityId: String
        private lateinit var attempt: Attempt
        private lateinit var event: AttendanceEvent
        private var skew: Duration = Duration.ZERO
        private var venueLat = 12.9716
        private var venueLng = 77.5946

        @BeforeClass @JvmStatic fun gate() {
            assumeTrue("FIELDPASS_API_URL / FIELDPASS_PASSWORD / FIELDPASS_FACULTY_PASSWORD / FIELDPASS_FACE_IMAGE not set", IntegrationEnv.isConfigured)
            base = requireNotNull(IntegrationEnv.apiUrl)
            password = requireNotNull(IntegrationEnv.password)
            facultyPassword = requireNotNull(IntegrationEnv.facultyPassword)
            face = requireNotNull(IntegrationEnv.faceImage).readBytes()
            blank = blankJpeg()
            student1 = StudentStack(base)
        }

        private fun say(message: String) = println("[integration] $message")

        private fun serverNow(): Instant = Instant.now().plus(skew)

        private var fixes = 0
        private fun nudge(lat: Double): Double = lat + (++fixes) * 0.000001 // ~0.1 m: real GPS never repeats exactly
        private fun evidence(photo: ByteArray, fix: LocationFix) =
            SubmitEvidence(photo, photo, fix, DeviceFlags(rooted = false, emulator = false))

        private fun fix(ageSeconds: Long = 5, lat: Double = nudge(venueLat), lng: Double = venueLng, accuracy: Double = 15.0) =
            LocationFix(lat, lng, accuracy, Instant.ofEpochSecond(serverNow().minusSeconds(ageSeconds).epochSecond).toString())

        private suspend fun expectError(code: ErrorCode, block: suspend () -> Any?): AppError {
            try {
                block()
            } catch (e: AppError) {
                assertEquals("wrong error code (http=${e.httpStatus})", code, e.code)
                return e
            }
            fail("expected AppError $code but the call succeeded")
            error("unreachable")
        }
    }

    @Test fun s01_health() = runBlocking {
        student1.repo.testConnection(base)
        val health = student1.api.health("$base/api/v1/health")
        assertEquals("ok", health.status)
        val serverTime = requireNotNull(TimeFormat.parseUtc(health.serverTime)) { "server_time not parseable: ${health.serverTime}" }
        skew = Duration.between(Instant.now(), serverTime)
        say("s01 health ok, server_time=${health.serverTime} parsed, clock skew ${skew.toMillis()} ms")
    }

    @Test fun s02_loginStudent() = runBlocking {
        val user = student1.repo.login("student1", password)
        assertEquals(Role.STUDENT, user.role)
        assertEquals("student1", user.username)
        assertTrue(requireNotNull(student1.session.token()).isNotEmpty())
        val me = requireNotNull(student1.repo.currentUser())
        assertEquals(user.id, me.id)
        say("s02 login ok: ${user.fullName} role=${user.role}, token stored, /auth/me ok")
    }

    @Test fun s03_enrollmentNone() = runBlocking {
        val e = student1.repo.enrollment()
        assertEquals(EnrollmentStatus.NONE, e.status)
        assertNull(e.id); assertNull(e.submittedAt); assertNull(e.decidedAt)
        assertNull(e.decidedBy); assertNull(e.decisionReason)
        assertEquals("student1", e.student?.username)
        say("s03 enrollment none: id/submitted_at/decided_* were real nulls and parsed")
    }

    @Test fun s04_uploadEnrollmentPhoto() = runBlocking {
        val e = student1.repo.submitEnrollment(face)
        assertEquals(EnrollmentStatus.PENDING, e.status)
        assertNotNull(e.id)
        assertNotNull("submitted_at parse", TimeFormat.parseUtc(e.submittedAt))
        assertNull(e.decidedAt)
        say("s04 enrollment upload ok via production multipart code (part 'photo', image/jpeg): pending, submitted_at=${e.submittedAt}")
    }

    @Test fun s05_enrollmentPending() = runBlocking {
        assertEquals(EnrollmentStatus.PENDING, student1.repo.enrollment().status)
        say("s05 enrollment/me reports pending")
    }

    @Test fun s06_facultyApproves() = runBlocking {
        faculty = FacultyHttp(base, facultyPassword)
        ids = faculty.studentIds()
        faculty.approve(faculty.pendingEnrollmentIdFor("student1"))
        val e = student1.repo.enrollment()
        assertEquals(EnrollmentStatus.APPROVED, e.status)
        assertEquals("Dr. Meera Rao", e.decidedBy)
        assertNotNull(TimeFormat.parseUtc(e.decidedAt))
        say("s06 faculty approved over plain HTTP; student sees approved by ${e.decidedBy} at ${e.decidedAt}")
    }

    @Test fun s07_activitiesAndNextActions() = runBlocking {
        activityId = faculty.createActivity(
            "Integration visit", venueLat, venueLng, serverNow().minusSeconds(60),
            listOf(requireNotNull(ids["student1"]), requireNotNull(ids["student3"])),
        )
        val list = student1.repo.activities()
        val a: Activity = requireNotNull(list.firstOrNull { it.id == activityId }) { "activity not listed" }
        assertEquals(venueLat, a.venueLat, 1e-9)
        assertEquals(100, a.radiusM)
        assertNotNull(TimeFormat.parseUtc(a.startsAt)); assertNotNull(TimeFormat.parseUtc(a.endsAt))
        assertEquals("Dr. Meera Rao", a.createdBy)
        assertNull(a.openCheckpoint)
        val mine = requireNotNull(a.myAttendance)
        assertNull(mine.status); assertNull(mine.checkIn); assertNull(mine.checkOut)
        assertEquals(listOf(Action.CHECK_IN), mine.nextActions)
        assertEquals(listOf(Action.CHECK_IN), ActivityActions.visible(a))
        assertEquals(a.id, student1.repo.activity(activityId).id)
        say("s07 activities ok: next_actions=${mine.nextActions}, my_attendance.status null, starts_at=${a.startsAt}")
    }

    @Test fun s08_createAttempt() = runBlocking {
        attempt = student1.repo.createAttempt(activityId, Action.CHECK_IN)
        assertEquals(Action.CHECK_IN, attempt.action)
        assertEquals(activityId, attempt.activityId)
        assertNull(attempt.checkpointId)
        val total = requireNotNull(TimeFormat.millisBetween(attempt.createdAt, attempt.expiresAt))
        assertEquals(90_000L, total)
        assertNotNull(AttemptCountdown.fromServerTimes(attempt.createdAt, attempt.expiresAt, 0))
        say("s08 attempt created ${attempt.id}: ttl ${total / 1000}s from created_at/expires_at")
    }

    @Test fun s09_blankSelfieIsUnusableAndAttemptStaysOpen() = runBlocking {
        val e = expectError(ErrorCode.UNUSABLE_CAPTURE) { student1.repo.submitAttempt(attempt, evidence(blank, fix())) }
        assertEquals(422, e.httpStatus)
        say("s09 blank image -> unusable_capture (422); retried on the same attempt in later steps")
    }

    @Test fun s10_staleLocation() = runBlocking {
        // lat 1.0E-5 is how Kotlin prints small doubles; location_stale (not validation_error) proves it parsed.
        val stale = fix(ageSeconds = 120, lat = 0.00001)
        assertEquals("1.0E-5", stale.lat.toString())
        val e = expectError(ErrorCode.LOCATION_STALE) { student1.repo.submitAttempt(attempt, evidence(face, stale)) }
        assertEquals(422, e.httpStatus)
        say("s10 120 s old location_captured_at -> location_stale (422); '1.0E-5' lat accepted by server")
    }

    @Test fun s11_submitSuccess() = runBlocking {
        event = student1.repo.submitAttempt(attempt, evidence(face, fix()))
        assertEquals(attempt.id, event.attemptId)
        assertEquals(activityId, event.activityId)
        assertEquals(Action.CHECK_IN, event.action)
        assertNull(event.checkpointId)
        assertNotNull(TimeFormat.parseUtc(event.receivedAt))
        assertEquals(LocationVerdict.INSIDE, event.location?.verdict)
        assertNotNull(event.location?.distanceM)
        assertEquals(15.0, requireNotNull(event.location?.accuracyM), 0.0)
        assertNotNull(TimeFormat.parseUtc(event.location?.capturedAt))
        assertEquals(FaceVerdict.MATCH, event.face?.verdict)
        assertTrue(event.reviewReasons.isEmpty())
        assertEquals(AttendanceStatus.RECORDED, event.status)
        say("s11 submit ok on SAME attempt after unusable+stale: ${event.id} status=${event.status} location=${event.location?.verdict} face=${event.face?.verdict}")
    }

    @Test fun s12_replayReturnsSameEvent() = runBlocking {
        val again = student1.repo.submitAttempt(attempt, evidence(face, fix()))
        assertEquals(event.id, again.id)
        val withDifferentBody = student1.repo.submitAttempt(attempt, evidence(blank, fix(ageSeconds = 120)))
        assertEquals(event.id, withDifferentBody.id)
        say("s12 replay returned the same event id ${again.id} (also with a different body)")
    }

    @Test fun s13_history() = runBlocking {
        val record = requireNotNull(student1.repo.attendanceHistory().firstOrNull { it.activityId == activityId })
        assertEquals(event.id, record.checkIn?.id)
        assertEquals(AttendanceStatus.RECORDED, record.status)
        assertNull(record.checkOut); assertNull(record.decision)
        assertTrue(record.checkpoints.isEmpty())
        assertFalse(record.complete); assertFalse(record.missingCheckout)
        assertEquals("student1", record.student?.username)
        assertTrue(Action.CHECK_OUT in record.nextActions)
        assertFalse(Action.CHECK_IN in record.nextActions)
        val detail = requireNotNull(student1.repo.activity(activityId).myAttendance)
        assertEquals(record.nextActions, detail.nextActions)
        say("s13 history ok: status=${record.status} next_actions=${record.nextActions} complete=${record.complete}")
    }

    @Test fun s14_secondCheckInIsInvalidActionOrder() = runBlocking {
        val e = expectError(ErrorCode.INVALID_ACTION_ORDER) { student1.repo.createAttempt(activityId, Action.CHECK_IN) }
        assertEquals(409, e.httpStatus)
        say("s14 second check_in -> invalid_action_order (409)")
    }

    @Test fun s15_unapprovedStudentSeesCheckInThenEnrollmentNotApproved() = runBlocking {
        val s3 = StudentStack(base, "student3")
        s3.repo.login("student3", password)
        assertEquals(EnrollmentStatus.NONE, s3.repo.enrollment().status)
        val a = requireNotNull(s3.repo.activities().firstOrNull { it.id == activityId })
        assertTrue("server offers check_in regardless of enrollment", Action.CHECK_IN in a.myAttendance!!.nextActions)
        val e = expectError(ErrorCode.ENROLLMENT_NOT_APPROVED) { s3.repo.createAttempt(activityId, Action.CHECK_IN) }
        assertEquals(409, e.httpStatus)
        say("s15 student3 (no enrollment): next_actions has check_in, createAttempt -> enrollment_not_approved (409)")
    }

    @Test fun s16_wrongPassword() = runBlocking {
        val s = StudentStack(base)
        val e = expectError(ErrorCode.INVALID_CREDENTIALS) { s.repo.login("student1", password + "x") }
        assertEquals(401, e.httpStatus)
        assertFalse("wrong password must not look like an expired session", e.isSessionExpired)
        assertNull(s.session.token())
        say("s16 wrong password -> invalid_credentials (401), no token stored")
    }

    @Test fun s17_badToken() = runBlocking {
        val s = StudentStack(base)
        s.session.save("not-a-real-token")
        val e = expectError(ErrorCode.NOT_AUTHENTICATED) { s.repo.activities() }
        assertEquals(401, e.httpStatus)
        assertTrue(e.isSessionExpired)
        assertNull("expired session clears the stored token", s.session.token())
        s.session.save("not-a-real-token")
        assertNull("currentUser maps an expired token to signed-out", s.repo.currentUser())
        say("s17 bad token -> not_authenticated (401), token cleared, currentUser()=null")
    }

    @Test fun s18_fastApiValidationErrorUsesEnvelope() = runBlocking {
        val text = "abc".toRequestBody("text/plain".toMediaType())
        val selfie = MultipartBody.Part.createFormData("selfie", "selfie.jpg", face.toRequestBody("image/jpeg".toMediaType()))
        try {
            student1.api.submitAttempt("att_does_not_matter", selfie, selfie, text, text, text, text, text, text, text, text)
            fail("expected 422")
        } catch (e: HttpException) {
            val parsed = ErrorParser.fromHttp(e.code(), e.response()?.errorBody()?.string())
            assertEquals(ErrorCode.VALIDATION_ERROR, parsed.code)
            assertEquals(422, parsed.httpStatus)
            say("s18 non-numeric lat -> validation_error (422) parsed from the server's envelope")
        }
    }

    @Test fun s19_notFoundAndNotAssigned() = runBlocking {
        expectError(ErrorCode.NOT_FOUND) { student1.repo.activity("act_missing") }
        val s2 = StudentStack(base, "student2")
        s2.repo.login("student2", password)
        val e = expectError(ErrorCode.NOT_ASSIGNED) { s2.repo.createAttempt(activityId, Action.CHECK_IN) }
        assertEquals(403, e.httpStatus)
        say("s19 unknown activity -> not_found (404); off-roster student2 -> not_assigned (403)")
    }

    @Test fun s20_facultyAccountIsTurnedAway() = runBlocking {
        val s = StudentStack(base, "faculty1")
        expectError(ErrorCode.FACULTY_ACCOUNT) { s.repo.login("faculty1", facultyPassword) }
        assertNull(s.session.token())
        say("s20 faculty login through the app -> FACULTY_ACCOUNT, no token kept")
    }
}
