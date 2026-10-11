package com.fieldpass.student.data.repo

import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttemptRequest
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.LoginRequest
import com.fieldpass.student.core.model.PresenceRequest
import com.fieldpass.student.core.model.Role
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.core.model.User
import com.fieldpass.student.data.api.ApiService
import com.fieldpass.student.data.device.DeviceSigner
import com.fieldpass.student.data.device.SubmitFormat
import com.fieldpass.student.data.device.canonicalMessage
import com.fieldpass.student.data.session.SessionManager
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/** Talks to the real backend through [ApiService]. Maps every failure to [AppError]. */
class RealFieldPassRepository(
    private val api: ApiService,
    private val session: SessionManager,
    private val signer: DeviceSigner,
) : FieldPassRepository {

    override suspend fun testConnection(baseUrl: String) {
        call { api.health("$baseUrl/api/v1/health") }
    }

    override suspend fun login(username: String, password: String): User {
        val response = call { api.login(LoginRequest(username.trim(), password, signer.publicKeyBase64())) }
        if (response.user.role != Role.STUDENT) throw AppError(ErrorCode.FACULTY_ACCOUNT)
        session.save(response.accessToken)
        return response.user
    }

    override suspend fun logout() = session.clear()

    override suspend fun currentUser(): User? {
        if (session.token() == null) return null
        return try {
            call { api.me() }
        } catch (e: AppError) {
            if (e.isSessionExpired) null else throw e
        }
    }

    override suspend fun enrollment(): Enrollment = call { api.enrollment() }

    override suspend fun submitEnrollment(photo: ByteArray): Enrollment =
        call { api.submitEnrollment(jpegPart("photo", photo)) }

    override suspend fun activities(): List<Activity> = call { api.activities() }.items

    override suspend fun activity(id: String): Activity = call { api.activity(id) }

    override suspend fun attendanceHistory(): List<AttendanceRecord> = call { api.attendance() }.items

    override suspend fun createAttempt(activityId: String, action: Action): Attempt =
        call { api.createAttempt(activityId, AttemptRequest(action)) }

    override suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent =
        call {
            val fix = evidence.fix
            val flags = evidence.flags
            val message = canonicalMessage(
                attempt.id, attempt.nonce, evidence.selfie, evidence.turnPhoto, fix, flags.rooted, flags.emulator,
            )
            api.submitAttempt(
                attemptId = attempt.id,
                selfie = jpegPart("selfie", evidence.selfie),
                turnPhoto = jpegPart("turn_photo", evidence.turnPhoto),
                lat = text(SubmitFormat.coordinate(fix.lat)),
                lng = text(SubmitFormat.coordinate(fix.lng)),
                accuracyM = text(SubmitFormat.accuracy(fix.accuracyM)),
                capturedAt = text(fix.capturedAt),
                isMock = text(fix.isMock.toString()),
                rooted = text(flags.rooted.toString()),
                emulator = text(flags.emulator.toString()),
                signature = text(signer.sign(message)),
            )
        }

    override suspend fun submitPresenceCode(attemptId: String, code: String): Boolean =
        call { api.presence(attemptId, PresenceRequest(code)) }.verified

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        val error = ErrorParser.fromHttp(e.code(), e.response()?.errorBody()?.string())
        if (error.isSessionExpired) session.onSessionExpired()
        throw error
    } catch (e: Exception) {
        throw ErrorParser.fromThrowable(e)
    }

    private fun jpegPart(name: String, bytes: ByteArray) = MultipartBody.Part.createFormData(
        name, "$name.jpg", bytes.toRequestBody("image/jpeg".toMediaType()),
    )

    private fun text(value: String) = value.toRequestBody("text/plain".toMediaType())
}
