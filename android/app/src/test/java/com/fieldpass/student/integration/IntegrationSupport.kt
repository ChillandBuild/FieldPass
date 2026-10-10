package com.fieldpass.student.integration

import com.fieldpass.student.FakeSettings
import com.fieldpass.student.SoftwareDeviceSigner
import com.fieldpass.student.data.api.ApiClient
import com.fieldpass.student.data.api.ApiService
import com.fieldpass.student.data.repo.RealFieldPassRepository
import com.fieldpass.student.data.session.MemoryTokenStore
import com.fieldpass.student.data.session.SessionManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO

/**
 * Settings for the opt-in backend contract tests. Everything comes from the environment so the
 * normal hermetic unit-test run (no variables set) skips this package entirely.
 *
 * FIELDPASS_API_URL   base URL of a throw-away backend, e.g. http://127.0.0.1:8001
 * FIELDPASS_PASSWORD  the SEED_PASSWORD of that backend: student accounts (never printed)
 * FIELDPASS_FACULTY_PASSWORD  the FACULTY_SEED_PASSWORD of that backend: faculty1 (never printed)
 * FIELDPASS_FACE_IMAGE path to a JPEG with exactly one clear face (OpenCV's lena.jpg works)
 * FIELDPASS_SHORT_TIMING  set to 1 ONLY when that backend runs with ATTEMPT_TTL_SECONDS=4 and
 *                      CHECKOUT_GRACE_MINUTES=0 (enables TimingContractTest; BackendContractTest needs the defaults)
 * Start the backend with the head-turn rule opened wide (no turned-head sample photo exists):
 *                      LIVENESS_FRONT_MAX_OFFSET=9 LIVENESS_TURN_MIN_OFFSET=-9 in its environment.
 */
object IntegrationEnv {
    val apiUrl: String? = System.getenv("FIELDPASS_API_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val password: String? = System.getenv("FIELDPASS_PASSWORD")?.takeIf { it.isNotEmpty() }
    val facultyPassword: String? = System.getenv("FIELDPASS_FACULTY_PASSWORD")?.takeIf { it.isNotEmpty() }
    val faceImage: File? = System.getenv("FIELDPASS_FACE_IMAGE")?.let(::File)?.takeIf { it.isFile }

    val shortTiming: Boolean = System.getenv("FIELDPASS_SHORT_TIMING") == "1"

    val isConfigured: Boolean get() = apiUrl != null && password != null && facultyPassword != null && faceImage != null
}

/** One student device: the app's production API service, repository and session wired to the server. */
/** One software "phone" per username, so a second stack for the same student is the same phone. */
object TestPhones {
    private val phones = mutableMapOf<String, SoftwareDeviceSigner>()
    fun of(username: String): SoftwareDeviceSigner = synchronized(phones) { phones.getOrPut(username) { SoftwareDeviceSigner() } }
}

class StudentStack(baseUrl: String, username: String = "student1") {
    val session = SessionManager(MemoryTokenStore())
    val api: ApiService = ApiClient.create(FakeSettings(baseUrl), session)
    val repo = RealFieldPassRepository(api, session, TestPhones.of(username))
}

/** Plain HTTP faculty client. The app has no faculty features, so this deliberately bypasses app code. */
class FacultyHttp(private val baseUrl: String, password: String) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val token: String

    init {
        val body = buildJsonObject { put("username", "faculty1"); put("password", password) }
        token = post("/api/v1/auth/login", body, auth = false).getValue("access_token").jsonPrimitive.content
    }

    private fun call(request: Request): JsonObject = client.newCall(request).execute().use { response ->
        val text = response.body.string()
        check(response.isSuccessful) { "faculty call ${request.method} ${request.url.encodedPath} -> ${response.code}" }
        json.parseToJsonElement(text).jsonObject
    }

    private fun builder(path: String, auth: Boolean) = Request.Builder().url("$baseUrl$path").apply {
        if (auth) header("Authorization", "Bearer $token")
    }

    fun get(path: String): JsonObject = call(builder(path, true).get().build())

    fun post(path: String, body: JsonObject, auth: Boolean = true): JsonObject =
        call(builder(path, auth).post(body.toString().toRequestBody("application/json".toMediaType())).build())

    fun studentIds(): Map<String, String> =
        get("/api/v1/students").getValue("items").jsonArray.associate {
            val o = it.jsonObject
            o.getValue("username").jsonPrimitive.content to o.getValue("id").jsonPrimitive.content
        }

    fun pendingEnrollmentIdFor(username: String): String {
        val items: JsonArray = get("/api/v1/enrollments?status=pending").getValue("items").jsonArray
        return items.map { it.jsonObject }
            .first { it.getValue("student").jsonObject.getValue("username").jsonPrimitive.content == username }
            .getValue("id").jsonPrimitive.content
    }

    fun approve(enrollmentId: String) {
        post("/api/v1/enrollments/$enrollmentId/approve", buildJsonObject { put("identity_confirmed", true) })
    }

    /** Creates an activity from [startsAt] to [endsAt] (default two hours later) for [studentIds]. Returns its id. */
    fun createActivity(
        name: String, lat: Double, lng: Double, startsAt: Instant, studentIds: List<String>,
        endsAt: Instant = startsAt.plusSeconds(2 * 3600),
    ): String {
        val body = buildJsonObject {
            put("name", name)
            put("venue_name", "Integration Gate")
            put("venue_lat", lat)
            put("venue_lng", lng)
            put("radius_m", 100)
            put("starts_at", startsAt.toString())
            put("ends_at", endsAt.toString())
            putJsonArray("student_ids") { studentIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        return post("/api/v1/activities", body).getValue("id").jsonPrimitive.content
    }
}

/** A flat white JPEG: readable by any decoder but with no face in it. */
fun blankJpeg(): ByteArray {
    val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, 640, 480)
    g.dispose()
    val out = ByteArrayOutputStream()
    check(ImageIO.write(image, "jpg", out))
    return out.toByteArray()
}
