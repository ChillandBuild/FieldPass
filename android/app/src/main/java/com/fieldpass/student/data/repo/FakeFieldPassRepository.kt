package com.fieldpass.student.data.repo

import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.Decision
import com.fieldpass.student.core.model.DecisionKind
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.core.model.FaceEvidence
import com.fieldpass.student.core.model.FaceVerdict
import com.fieldpass.student.core.model.LocationEvidence
import com.fieldpass.student.core.model.HeadTurn
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.LocationVerdict
import com.fieldpass.student.core.model.PresenceCode
import com.fieldpass.student.core.model.ReviewReason
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.core.model.Role
import com.fieldpass.student.core.model.User
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * Debug-only in-memory stand-in for the server (fixture mode). Same interface as the real
 * repository; sample data only. Behaviour mirrors the contract closely enough to walk every screen.
 */
class FakeFieldPassRepository(
    private val now: () -> Instant = { Instant.now() },
    private val latencyMs: Long = 300,
) : FieldPassRepository {

    private val student = User("u_s1", "student1", "Asha Verma", Role.STUDENT)
    private var signedIn = false
    private var enrollment = Enrollment(
        id = "enr_1", status = EnrollmentStatus.APPROVED, student = student,
        submittedAt = iso(now().minus(Duration.ofDays(2))), decidedAt = iso(now().minus(Duration.ofDays(1))),
        decidedBy = "Dr. Meera Rao",
    )
    private val attempts = mutableMapOf<String, Attempt>()
    private val events = mutableMapOf<String, AttendanceEvent>()
    private val presenceVerified = mutableSetOf<String>()
    private val sampleEvents = mutableListOf<AttendanceEvent>()
    private var counter = 0

    override suspend fun testConnection(baseUrl: String) = pause()

    override suspend fun login(username: String, password: String): User {
        pause()
        if (username.trim().startsWith("faculty")) throw AppError(ErrorCode.FACULTY_ACCOUNT)
        if (password.isEmpty()) throw AppError(ErrorCode.INVALID_CREDENTIALS)
        signedIn = true
        return student
    }

    override suspend fun logout() { signedIn = false }

    override suspend fun currentUser(): User? = if (signedIn) student else null

    override suspend fun enrollment(): Enrollment {
        pause()
        if (enrollment.status == EnrollmentStatus.PENDING) {
            enrollment = enrollment.copy(
                status = EnrollmentStatus.APPROVED, decidedAt = iso(now()), decidedBy = "Dr. Meera Rao",
            )
        }
        return enrollment
    }

    override suspend fun submitEnrollment(photo: ByteArray): Enrollment {
        pause()
        if (photo.isEmpty()) throw AppError(ErrorCode.UNUSABLE_CAPTURE)
        enrollment = Enrollment(
            id = "enr_${++counter + 1}", status = EnrollmentStatus.PENDING, student = student,
            submittedAt = iso(now()),
        )
        return enrollment
    }

    override suspend fun activities(): List<Activity> {
        pause()
        return listOf(activeActivity(), pastActivity())
    }

    override suspend fun activity(id: String): Activity {
        pause()
        return activities().firstOrNull { it.id == id } ?: throw AppError(ErrorCode.NOT_FOUND)
    }

    override suspend fun attendanceHistory(): List<AttendanceRecord> {
        pause()
        return activities().mapNotNull { it.myAttendance }
    }

    override suspend fun createAttempt(activityId: String, action: Action): Attempt {
        pause()
        val activity = activity(activityId)
        if (action !in (activity.myAttendance?.nextActions ?: emptyList())) {
            throw AppError(ErrorCode.INVALID_ACTION_ORDER)
        }
        val created = now()
        val attempt = Attempt(
            id = "att_${++counter}", activityId = activityId, action = action,
            createdAt = iso(created), expiresAt = iso(created.plusSeconds(90)),
            checkpointId = activity.openCheckpoint?.id.takeIf { action == Action.CHECKPOINT },
            nonce = "demo-nonce", challenge = if (counter % 2 == 0) HeadTurn.LEFT else HeadTurn.RIGHT,
        )
        attempts[attempt.id] = attempt
        return attempt
    }

    override suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent {
        val attemptId = attempt.id; val selfie = evidence.selfie; val fix = evidence.fix
        pause()
        events[attemptId]?.let { return it }
        val stored = attempts[attemptId] ?: throw AppError(ErrorCode.NOT_FOUND)
        val age = Duration.between(Instant.parse(stored.createdAt), now())
        if (age > Duration.ofSeconds(90)) throw AppError(ErrorCode.ATTEMPT_EXPIRED)
        if (selfie.isEmpty()) throw AppError(ErrorCode.UNUSABLE_CAPTURE)
        val inside = fix.accuracyM <= LocationFix.POOR_ACCURACY_THRESHOLD_M
        val event = AttendanceEvent(
            id = "evt_${++counter}", attemptId = attemptId, activityId = stored.activityId,
            studentId = student.id, action = stored.action,
            status = if (inside) AttendanceStatus.RECORDED else AttendanceStatus.NEEDS_REVIEW,
            receivedAt = iso(now()), checkpointId = stored.checkpointId,
            reviewReasons = if (inside) emptyList() else listOf(ReviewReason.LOCATION_UNCERTAIN),
            location = LocationEvidence(
                if (inside) LocationVerdict.INSIDE else LocationVerdict.UNCERTAIN, 40.0, fix.accuracyM, fix.capturedAt,
            ),
            face = FaceEvidence(FaceVerdict.MATCH),
            presenceVerified = attemptId in presenceVerified,
        )
        events[attemptId] = event
        sampleEvents += event
        return event
    }

    /** Any code that starts with the real format's prefix counts as fresh; there is no faculty screen in fixture mode. */
    override suspend fun submitPresenceCode(attemptId: String, code: String): Boolean {
        pause()
        val stored = attempts[attemptId] ?: throw AppError(ErrorCode.NOT_FOUND)
        if (Duration.between(Instant.parse(stored.createdAt), now()) > Duration.ofSeconds(90)) throw AppError(ErrorCode.ATTEMPT_EXPIRED)
        val verified = code.startsWith(PresenceCode.PREFIX)
        if (verified) presenceVerified += attemptId
        return verified
    }

    private fun activeActivity(): Activity {
        val mine = sampleEvents.filter { it.activityId == ACTIVE_ID }
        val checkIn = mine.firstOrNull { it.action == Action.CHECK_IN }
        val checkOut = mine.firstOrNull { it.action == Action.CHECK_OUT }
        val next = when {
            checkOut != null -> emptyList()
            checkIn != null -> listOf(Action.CHECKPOINT, Action.CHECK_OUT)
            else -> listOf(Action.CHECK_IN)
        }
        val record = AttendanceRecord(
            activityId = ACTIVE_ID, activityName = ACTIVE_NAME, student = student,
            status = mine.lastOrNull()?.let { e -> if (mine.any { it.status == AttendanceStatus.NEEDS_REVIEW }) AttendanceStatus.NEEDS_REVIEW else e.status },
            checkIn = checkIn, checkpoints = mine.filter { it.action == Action.CHECKPOINT },
            checkOut = checkOut, complete = checkIn != null && checkOut != null,
            reviewReasons = mine.flatMap { it.reviewReasons }.distinct(), nextActions = next,
        )
        val opened = now().minusSeconds(120)
        return Activity(
            id = ACTIVE_ID, name = ACTIVE_NAME, venueName = "Plant Gate 2", venueLat = 12.9716, venueLng = 77.5946,
            radiusM = 100, startsAt = iso(now().minus(Duration.ofMinutes(30))), endsAt = iso(now().plus(Duration.ofHours(3))),
            createdBy = "Dr. Meera Rao", rosterCount = 3,
            openCheckpoint = com.fieldpass.student.core.model.CheckpointWindow(
                "cp_1", ACTIVE_ID, iso(opened), iso(opened.plusSeconds(600)), "Dr. Meera Rao",
            ).takeIf { checkIn != null && checkOut == null },
            myAttendance = record,
        )
    }

    private fun pastActivity(): Activity {
        val start = now().minus(Duration.ofDays(3))
        val decision = Decision("dec_1", DecisionKind.NOT_CONFIRMED, "Not seen on the attendance sheet", "Dr. Meera Rao", iso(start.plus(Duration.ofHours(6))), true)
        val record = AttendanceRecord(
            activityId = "act_2", activityName = "Lab Safety Walkthrough", student = student,
            status = AttendanceStatus.NOT_CONFIRMED, missingCheckout = true, decision = decision, decisionHistory = listOf(decision),
        )
        return Activity(
            id = "act_2", name = "Lab Safety Walkthrough", venueName = "Chemistry Block", venueLat = 12.97, venueLng = 77.59,
            radiusM = 80, startsAt = iso(start), endsAt = iso(start.plus(Duration.ofHours(2))), createdBy = "Dr. Meera Rao",
            rosterCount = 3, myAttendance = record,
        )
    }

    private suspend fun pause() = delay(latencyMs)

    private fun iso(instant: Instant): String = instant.withNanoAsZero().toString()

    private fun Instant.withNanoAsZero(): Instant = Instant.ofEpochSecond(epochSecond)

    private companion object {
        const val ACTIVE_ID = "act_1"
        const val ACTIVE_NAME = "Site Visit: Water Treatment Plant"
    }
}
