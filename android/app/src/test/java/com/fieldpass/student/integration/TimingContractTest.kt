package com.fieldpass.student.integration

import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.DeviceFlags
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.core.time.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.time.Duration
import java.time.Instant

/**
 * Time-dependent server behaviour through the app's PRODUCTION stack, against a real backend started with
 * ATTEMPT_TTL_SECONDS=4 and CHECKOUT_GRACE_MINUTES=0 (android/scripts/integration-test.sh does this and sets
 * FIELDPASS_SHORT_TIMING=1). Skipped otherwise. The clock is never faked: each step waits in real time until
 * the SERVER's clock has passed the expiry or the end of the activity. Needs a FRESH database.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TimingContractTest {

    companion object {
        private const val SHORT_TTL_MS = 4_000L
        private const val WAIT_LIMIT_MS = 40_000L

        private lateinit var base: String
        private lateinit var password: String
        private lateinit var facultyPassword: String
        private lateinit var face: ByteArray
        private lateinit var faculty: FacultyHttp
        private lateinit var student1: StudentStack
        private lateinit var studentId: String
        private lateinit var expiryActivityId: String
        private lateinit var expiredAttempt: Attempt
        private lateinit var endingActivityId: String
        private lateinit var endsAt: Instant
        private val venueLat = 12.9716
        private val venueLng = 77.5946

        @BeforeClass @JvmStatic fun gate() {
            assumeTrue("backend contract env not set", IntegrationEnv.isConfigured)
            assumeTrue("FIELDPASS_SHORT_TIMING=1 not set: this needs a backend with a short attempt TTL and zero checkout grace", IntegrationEnv.shortTiming)
            base = requireNotNull(IntegrationEnv.apiUrl)
            password = requireNotNull(IntegrationEnv.password)
            facultyPassword = requireNotNull(IntegrationEnv.facultyPassword)
            face = requireNotNull(IntegrationEnv.faceImage).readBytes()
            student1 = StudentStack(base)
        }

        private fun say(message: String) = println("[integration] $message")

        private suspend fun serverNow(): Instant {
            val health = student1.api.health("$base/api/v1/health")
            return requireNotNull(TimeFormat.parseUtc(health.serverTime)) { "server_time not parseable: ${health.serverTime}" }
        }

        /** Polls the server's own clock until it is past [moment]; fails after [WAIT_LIMIT_MS]. */
        private suspend fun waitUntilServerPast(moment: Instant) {
            val deadline = System.currentTimeMillis() + WAIT_LIMIT_MS
            while (!serverNow().isAfter(moment)) {
                if (System.currentTimeMillis() > deadline) fail("server clock did not pass $moment in time")
                delay(250)
            }
        }

        private var fixes = 0
        private fun nudge(lat: Double): Double = lat + (++fixes) * 0.000001 // ~0.1 m: real GPS never repeats exactly
        private fun evidence(photo: ByteArray, fix: LocationFix) =
            SubmitEvidence(photo, photo, fix, DeviceFlags(rooted = false, emulator = false))

        private suspend fun fix(): LocationFix =
            LocationFix(nudge(venueLat), venueLng, 15.0, Instant.ofEpochSecond(serverNow().minusSeconds(5).epochSecond).toString())

        private suspend fun record(activityId: String): AttendanceRecord =
            requireNotNull(student1.repo.attendanceHistory().firstOrNull { it.activityId == activityId }) { "activity missing from history" }
    }

    @Test fun t01_enrolAndApprove() = runBlocking {
        student1.repo.login("student1", password)
        assertEquals(EnrollmentStatus.PENDING, student1.repo.submitEnrollment(face).status)
        faculty = FacultyHttp(base, facultyPassword)
        studentId = requireNotNull(faculty.studentIds()["student1"])
        faculty.approve(faculty.pendingEnrollmentIdFor("student1"))
        assertEquals(EnrollmentStatus.APPROVED, student1.repo.enrollment().status)
        say("t01 student1 enrolled and approved on the short-timing backend")
    }

    @Test fun t02_attemptLifetimeIsTheConfiguredFourSeconds() = runBlocking {
        expiryActivityId = faculty.createActivity("Expiry visit", venueLat, venueLng, serverNow().minusSeconds(60), listOf(studentId))
        expiredAttempt = student1.repo.createAttempt(expiryActivityId, Action.CHECK_IN)
        val total = requireNotNull(TimeFormat.millisBetween(expiredAttempt.createdAt, expiredAttempt.expiresAt))
        assertEquals(SHORT_TTL_MS, total)
        say("t02 attempt ${expiredAttempt.id} created with a ${total / 1000}s lifetime")
    }

    @Test fun t03_submitAfterExpiryMapsToAttemptExpiredAndRecordsNothing() = runBlocking {
        waitUntilServerPast(requireNotNull(TimeFormat.parseUtc(expiredAttempt.expiresAt)))
        try {
            student1.repo.submitAttempt(expiredAttempt, evidence(face, fix()))
            fail("expected attempt_expired but the submit succeeded")
        } catch (e: AppError) {
            assertEquals(ErrorCode.ATTEMPT_EXPIRED, e.code)
            assertEquals(410, e.httpStatus)
            assertFalse("an expired attempt is not an expired session", e.isSessionExpired)
            assertFalse("an expired attempt is not a lost response: the app must start a new attempt", e.isLostResponse)
            assertNotNull(e.serverMessage)
        }
        val mine = record(expiryActivityId)
        assertNull(mine.checkIn)
        assertNull(mine.status)
        assertEquals(listOf(Action.CHECK_IN), mine.nextActions)
        say("t03 submit after expiry -> ErrorCode.ATTEMPT_EXPIRED (410); history has no check-in, next_actions=${mine.nextActions}")
    }

    @Test fun t04_freshAttemptAfterExpirySucceeds() = runBlocking {
        val fresh = student1.repo.createAttempt(expiryActivityId, Action.CHECK_IN)
        assertTrue(fresh.id != expiredAttempt.id)
        val event = student1.repo.submitAttempt(fresh, evidence(face, fix()))
        assertEquals(AttendanceStatus.RECORDED, event.status)
        assertEquals(event.id, record(expiryActivityId).checkIn?.id)
        say("t04 fresh attempt ${fresh.id} recorded ${event.id}")
    }

    @Test fun t05_checkInThenMissingCheckoutIsParsedAfterTheEnd() = runBlocking {
        val now = serverNow()
        endsAt = Instant.ofEpochSecond(now.plusSeconds(20).epochSecond)
        endingActivityId = faculty.createActivity(
            "Ending visit", venueLat, venueLng, now.minusSeconds(60), listOf(studentId), endsAt = endsAt,
        )
        val attempt = student1.repo.createAttempt(endingActivityId, Action.CHECK_IN)
        val event = student1.repo.submitAttempt(attempt, evidence(face, fix()))
        assertEquals(AttendanceStatus.RECORDED, event.status)

        val before = record(endingActivityId)
        assertTrue("test started too late: the activity already ended", serverNow().isBefore(endsAt))
        assertFalse(before.missingCheckout)
        assertEquals(listOf(Action.CHECK_OUT), before.nextActions)

        waitUntilServerPast(endsAt)
        val after = record(endingActivityId)
        assertTrue("missing_checkout: true must parse to missingCheckout", after.missingCheckout)
        assertFalse(after.complete)
        assertNull(after.checkOut)
        assertNotNull(after.checkIn)
        assertEquals(AttendanceStatus.RECORDED, after.status)
        assertTrue("no next actions once the check-out window has closed", after.nextActions.isEmpty())
        val detail = requireNotNull(student1.repo.activity(endingActivityId).myAttendance)
        assertTrue(detail.missingCheckout)
        assertTrue(detail.nextActions.isEmpty())
        say("t05 after the end: missingCheckout=${after.missingCheckout} complete=${after.complete} next_actions=${after.nextActions}")
    }

    @Test fun t06_checkOutAfterTheEndIsOutsideActivityWindow() = runBlocking {
        try {
            student1.repo.createAttempt(endingActivityId, Action.CHECK_OUT)
            fail("expected outside_activity_window")
        } catch (e: AppError) {
            assertEquals(ErrorCode.OUTSIDE_ACTIVITY_WINDOW, e.code)
            assertEquals(409, e.httpStatus)
        }
        say("t06 check_out after the end -> OUTSIDE_ACTIVITY_WINDOW (409)")
    }
}
