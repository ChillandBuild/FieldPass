package com.fieldpass.student

import com.fieldpass.student.capture.LocationProvider
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.Attempt
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.HeadTurn
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.core.model.User
import com.fieldpass.student.data.device.DeviceSigner
import com.fieldpass.student.data.repo.FieldPassRepository
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Repository whose every method fails unless a test overrides it. */
open class StubRepository : FieldPassRepository {
    override suspend fun testConnection(baseUrl: String) { error("not stubbed") }
    override suspend fun login(username: String, password: String): User = error("not stubbed")
    override suspend fun logout() { error("not stubbed") }
    override suspend fun currentUser(): User? = error("not stubbed")
    override suspend fun enrollment(): Enrollment = error("not stubbed")
    override suspend fun submitEnrollment(photo: ByteArray): Enrollment = error("not stubbed")
    override suspend fun activities(): List<Activity> = error("not stubbed")
    override suspend fun activity(id: String): Activity = error("not stubbed")
    override suspend fun attendanceHistory(): List<AttendanceRecord> = error("not stubbed")
    override suspend fun createAttempt(activityId: String, action: Action): Attempt = error("not stubbed")
    override suspend fun submitAttempt(attempt: Attempt, evidence: SubmitEvidence): AttendanceEvent =
        error("not stubbed")
}

/** Location provider that replays queued fixes or errors in order. */
class ScriptedLocation(vararg steps: Any) : LocationProvider {
    private val queue = ArrayDeque(steps.toList())
    var calls = 0
        private set

    override suspend fun freshFix(): LocationFix {
        calls++
        return when (val step = queue.removeFirst()) {
            is LocationFix -> step
            is AppError -> throw step
            else -> error("bad step")
        }
    }
}

fun fix(accuracy: Double = 12.0) = LocationFix(12.97, 77.59, accuracy, "2026-10-10T09:31:05Z")

fun attempt(id: String = "att_1") = Attempt(
    id = id, activityId = "act_1", action = Action.CHECK_IN,
    createdAt = "2026-10-10T09:31:00Z", expiresAt = "2026-10-10T09:32:30Z",
    nonce = "n0nce", challenge = HeadTurn.LEFT,
)

fun event(attemptId: String = "att_1", status: AttendanceStatus = AttendanceStatus.RECORDED) = AttendanceEvent(
    id = "evt_1", attemptId = attemptId, activityId = "act_1", studentId = "u_s1",
    action = Action.CHECK_IN, status = status, receivedAt = "2026-10-10T09:31:12Z",
)

/** JVM stand-in for the Keystore signer: same key type, same signature format. */
class SoftwareDeviceSigner : DeviceSigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicKey: PublicKey get() = pair.public
    override fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(pair.public.encoded)
    override fun sign(message: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }
        return Base64.getEncoder().encodeToString(signature.sign())
    }
}

class FakeSettings(base: String = "http://192.168.43.10:8000") : com.fieldpass.student.data.settings.AppSettings {
    private val _base = kotlinx.coroutines.flow.MutableStateFlow(base)
    private val _fixture = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val baseUrl: kotlinx.coroutines.flow.StateFlow<String> = _base
    override val fixtureMode: kotlinx.coroutines.flow.StateFlow<Boolean> = _fixture
}
