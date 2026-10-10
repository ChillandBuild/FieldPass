package com.fieldpass.student.data

import com.fieldpass.student.FakeSettings
import com.fieldpass.student.SoftwareDeviceSigner
import com.fieldpass.student.attempt
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.model.DeviceFlags
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.core.model.SubmitEvidence
import com.fieldpass.student.data.device.canonicalMessage
import com.fieldpass.student.data.api.ApiClient
import com.fieldpass.student.data.repo.RealFieldPassRepository
import com.fieldpass.student.data.session.MemoryTokenStore
import com.fieldpass.student.data.session.SessionManager
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.Signature
import java.util.Base64

/** Exercises the real Retrofit stack against a local server so paths and part names match API.md. */
class RealRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var session: SessionManager
    private lateinit var repo: RealFieldPassRepository
    private val signer = SoftwareDeviceSigner()

    private val studentJson = """{"id":"u_s1","username":"student1","full_name":"Asha Verma","role":"student"}"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val settings = FakeSettings("http://${server.hostName}:${server.port}")
        session = SessionManager(MemoryTokenStore())
        repo = RealFieldPassRepository(ApiClient.create(settings, session), session, signer)
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun studentLoginStoresTokenAndFacultyLoginDoesNot() = runTest {
        server.enqueue(json("""{"access_token":"tok1","token_type":"bearer","user":$studentJson}"""))
        repo.login("student1", "pw")
        assertEquals("tok1", session.token())
        val login = server.takeRequest()
        assertEquals("/api/v1/auth/login", login.path)
        assertNull(login.getHeader("Authorization"))
        assertTrue(login.body.readUtf8().contains("\"device_public_key\":\"${signer.publicKeyBase64()}\""))

        session.clear()
        server.enqueue(json("""{"access_token":"tok2","user":{"id":"u_f","username":"faculty1","full_name":"Dr","role":"faculty"}}"""))
        try { repo.login("faculty1", "pw"); fail() } catch (e: AppError) { assertEquals(ErrorCode.FACULTY_ACCOUNT, e.code) }
        assertNull(session.token())
    }

    @Test fun authenticatedCallsCarryBearerToken() = runTest {
        session.save("tok1")
        server.enqueue(json("""{"items":[]}"""))
        repo.activities()
        val req = server.takeRequest()
        assertEquals("/api/v1/activities", req.path)
        assertEquals("Bearer tok1", req.getHeader("Authorization"))
    }

    @Test fun submitSendsContractPartsToTheSameAttemptPath() = runTest {
        session.save("tok1")
        server.enqueue(json("""{"id":"evt_1","attempt_id":"att_1","activity_id":"act_1","student_id":"u_s1","action":"check_in","status":"needs_review","received_at":"2026-10-10T09:31:12Z"}"""))
        val fix = LocationFix(12.5, 77.5, 18.0, "2026-10-10T09:31:05Z", isMock = true)
        val selfie = byteArrayOf(1, 2, 3)
        val turn = byteArrayOf(4, 5)
        val sent = attempt("att_1").copy(nonce = "n0nce")
        val event = repo.submitAttempt(sent, SubmitEvidence(selfie, turn, fix, DeviceFlags(rooted = false, emulator = true)))
        assertEquals(AttendanceStatus.NEEDS_REVIEW, event.status)
        val req = server.takeRequest()
        assertEquals("/api/v1/attempts/att_1/submit", req.path)
        val body = req.body.readUtf8()
        fun part(name: String) = requireNotNull(Regex("name=\"$name\"[\\s\\S]*?\\r\\n\\r\\n([^\\r]*)\\r\\n").find(body)) { "missing part $name" }.groupValues[1]
        assertEquals("12.500000", part("lat"))
        assertEquals("77.500000", part("lng"))
        assertEquals("18.0", part("accuracy_m"))
        assertEquals("2026-10-10T09:31:05Z", part("location_captured_at"))
        assertEquals("true", part("location_is_mock"))
        assertEquals("false", part("device_rooted"))
        assertEquals("true", part("device_emulator"))
        assertTrue(body.contains("filename=\"selfie.jpg\"") && body.contains("filename=\"turn_photo.jpg\""))
        val message = canonicalMessage("att_1", "n0nce", selfie, turn, fix, rooted = false, emulator = true)
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(signer.publicKey); update(message) }
        assertTrue("signature must cover exactly what was sent", verifier.verify(Base64.getDecoder().decode(part("signature"))))
        assertTrue(body.contains("2026-10-10T09:31:05Z"))
    }

    @Test fun createAttemptPostsAction() = runTest {
        session.save("tok1")
        server.enqueue(json("""{"id":"att_1","activity_id":"act_1","action":"check_in","created_at":"2026-10-10T09:31:00Z","expires_at":"2026-10-10T09:32:30Z","checkpoint_id":null}""", 201))
        repo.createAttempt("act_1", Action.CHECK_IN)
        val req = server.takeRequest()
        assertEquals("/api/v1/activities/act_1/attempts", req.path)
        assertEquals("""{"action":"check_in"}""", req.body.readUtf8())
    }

    @Test fun errorEnvelopeBecomesTypedErrorWithoutLeakingBody() = runTest {
        session.save("tok1")
        server.enqueue(json("""{"error":{"code":"unusable_capture","message":"No face found."}}""", 422))
        try {
            repo.submitAttempt(attempt("att_1"), SubmitEvidence(byteArrayOf(1), byteArrayOf(1), LocationFix(1.0, 2.0, 3.0, "2026-10-10T09:31:05Z"), DeviceFlags(false, false)))
            fail()
        } catch (e: AppError) {
            assertEquals(ErrorCode.UNUSABLE_CAPTURE, e.code)
            assertEquals(422, e.httpStatus)
        }
    }

    @Test fun expiredTokenClearsSessionAndSignals() = runTest {
        session.save("old")
        server.enqueue(json("""{"error":{"code":"not_authenticated","message":"Expired."}}""", 401))
        try { repo.enrollment(); fail() } catch (e: AppError) { assertTrue(e.isSessionExpired) }
        assertNull(session.token())
    }

    @Test fun wrongPasswordIsNotTreatedAsExpiredSession() = runTest {
        server.enqueue(json("""{"error":{"code":"invalid_credentials","message":"Wrong."}}""", 401))
        try { repo.login("student1", "bad"); fail() } catch (e: AppError) { assertEquals(ErrorCode.INVALID_CREDENTIALS, e.code) }
    }

    @Test fun unreachableServerIsLostResponseError() = runTest {
        server.shutdown()
        session.save("tok1")
        try { repo.activities(); fail() } catch (e: AppError) { assertTrue(e.isLostResponse) }
    }

    @Test fun healthTestUsesGivenAddressWithoutToken() = runTest {
        session.save("tok1")
        server.enqueue(json("""{"status":"ok","server_time":"2026-10-10T09:00:00Z"}"""))
        repo.testConnection("http://${server.hostName}:${server.port}")
        val req = server.takeRequest()
        assertEquals("/api/v1/health", req.path)
        assertNull(req.getHeader("Authorization"))
    }
}
