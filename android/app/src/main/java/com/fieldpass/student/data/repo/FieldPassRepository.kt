package com.fieldpass.student.data.repo

import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.User

/**
 * The one seam between UI and server. The server is the only judge of attendance; no method
 * here decides success locally. All failures are thrown as [com.fieldpass.student.core.error.AppError].
 */
interface FieldPassRepository {
    /** Calls GET /api/v1/health on [baseUrl] (already normalised). Throws AppError on failure. */
    suspend fun testConnection(baseUrl: String)

    suspend fun login(username: String, password: String): User
    suspend fun logout()
    suspend fun currentUser(): User?

    suspend fun enrollment(): Enrollment
    suspend fun submitEnrollment(photo: ByteArray): Enrollment

    suspend fun activities(): List<Activity>
    suspend fun activity(id: String): Activity
    suspend fun attendanceHistory(): List<AttendanceRecord>

    suspend fun createAttempt(activityId: String, action: Action): Attempt
    suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent

    /**
     * Sends a scanned faculty-screen code for [attemptId]. True = the server accepted it as fresh;
     * false = wrong or too old (scan again). It is an extra signal only; callers never block on it.
     */
    suspend fun submitPresenceCode(attemptId: String, code: String): Boolean
}
